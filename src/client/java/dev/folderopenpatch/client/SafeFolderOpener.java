package dev.folderopenpatch.client;

import java.awt.Desktop;
import java.io.File;
import java.net.URI;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Opens local files/folders and URLs without blocking the client thread and
 * without going through SDL_OpenURL / AWT Desktop (both can hang on Windows).
 */
public final class SafeFolderOpener {
	private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(runnable -> {
		Thread thread = new Thread(runnable, "FolderOpenPatch-Open");
		thread.setDaemon(true);
		return thread;
	});

	private SafeFolderOpener() {
	}

	public static void open(File file) {
		if (file == null) {
			return;
		}
		EXECUTOR.execute(() -> openFileBlocking(file));
	}

	public static void open(Path path) {
		if (path == null) {
			return;
		}
		open(path.toFile());
	}

	/**
	 * Handles {@code file:} URIs only. Returns {@code true} when consumed.
	 */
	public static boolean tryHandleFileUri(URI uri) {
		if (uri == null) {
			return false;
		}
		String scheme = uri.getScheme();
		if (scheme == null || !"file".equalsIgnoreCase(scheme)) {
			return false;
		}
		try {
			open(new File(uri));
			return true;
		} catch (Throwable t) {
			FolderOpenPatchClient.LOGGER.warn("Failed to convert file URI: {}", uri, t);
			return false;
		}
	}

	/**
	 * Handles any URI (file or http/https/mailto…). Returns {@code true} when consumed.
	 * Used when callers (e.g. mods) go straight to {@code SDL_OpenURL}.
	 */
	public static boolean tryHandleUri(URI uri) {
		if (uri == null) {
			return false;
		}
		String scheme = uri.getScheme();
		if (scheme != null && "file".equalsIgnoreCase(scheme)) {
			return tryHandleFileUri(uri);
		}
		EXECUTOR.execute(() -> openUriBlocking(uri));
		return true;
	}

	/**
	 * Handles raw strings passed to {@code SDL_OpenURL}.
	 * Returns {@code true} when consumed; {@code false} lets vanilla SDL handle it
	 * only if we cannot parse anything sensible.
	 */
	public static boolean tryHandleUriString(CharSequence url) {
		if (url == null) {
			return false;
		}
		String s = url.toString().trim();
		if (s.isEmpty()) {
			return false;
		}
		try {
			String lower = s.toLowerCase(Locale.ROOT);
			if (lower.startsWith("file:")) {
				return tryHandleFileUri(URI.create(s));
			}
			// Absolute Windows path or UNC
			if (s.matches("^[A-Za-z]:[\\\\/].*") || s.startsWith("\\\\")) {
				open(new File(s));
				return true;
			}
			// http(s), mailto, etc.
			if (lower.startsWith("http://") || lower.startsWith("https://")
					|| lower.startsWith("mailto:") || lower.startsWith("ftp://")) {
				return tryHandleUri(URI.create(s));
			}
			// Bare path-like string
			File f = new File(s);
			if (f.exists()) {
				open(f);
				return true;
			}
		} catch (Throwable t) {
			FolderOpenPatchClient.LOGGER.warn("Could not handle open request: {}", s, t);
		}
		return false;
	}

	private static void openFileBlocking(File file) {
		try {
			if (!file.exists()) {
				//noinspection ResultOfMethodCallIgnored
				file.mkdirs();
			}

			String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
			if (os.contains("win")) {
				launchWindows(file.getAbsolutePath());
			} else if (os.contains("mac")) {
				new ProcessBuilder("open", file.getAbsolutePath()).start();
			} else {
				new ProcessBuilder("xdg-open", file.getAbsolutePath()).start();
			}
			FolderOpenPatchClient.LOGGER.info("Opened path asynchronously: {}", file.getAbsolutePath());
		} catch (Throwable t) {
			FolderOpenPatchClient.LOGGER.error("Failed to open path: {}", file, t);
		}
	}

	private static void openUriBlocking(URI uri) {
		try {
			String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
			String arg = uri.toString();
			if (os.contains("win")) {
				// explorer can open both folders and http(s) URLs without ShellExecute hang points
				launchWindows(arg);
			} else if (os.contains("mac")) {
				new ProcessBuilder("open", arg).start();
			} else {
				new ProcessBuilder("xdg-open", arg).start();
			}
			FolderOpenPatchClient.LOGGER.info("Opened URI asynchronously: {}", uri);
		} catch (Throwable t) {
			FolderOpenPatchClient.LOGGER.error("Failed to open URI: {}", uri, t);
		}
	}

	private static void launchWindows(String target) throws Exception {
		new ProcessBuilder("explorer.exe", target)
				.redirectErrorStream(false)
				.start();
	}

	@SuppressWarnings("unused")
	private static void openViaDesktop(File file) {
		try {
			if (!Desktop.isDesktopSupported()) {
				return;
			}
			Desktop desktop = Desktop.getDesktop();
			if (file.isDirectory() && desktop.isSupported(Desktop.Action.OPEN)) {
				desktop.open(file);
			} else if (desktop.isSupported(Desktop.Action.BROWSE_FILE_DIR)) {
				desktop.browseFileDirectory(file);
			}
		} catch (Throwable t) {
			FolderOpenPatchClient.LOGGER.warn("Desktop fallback open failed for {}", file, t);
		}
	}
}
