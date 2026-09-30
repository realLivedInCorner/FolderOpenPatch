package dev.folderopenpatch.client;

import java.awt.Desktop;
import java.io.File;
import java.net.URI;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Opens local files/folders without blocking Minecraft's client/render thread
 * and without going through SDL_OpenURL / AWT Desktop (both can hang on Windows).
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
		EXECUTOR.execute(() -> openBlocking(file));
	}

	/**
	 * Handles {@code file:} URIs. Returns {@code true} when consumed.
	 * Non-file schemes (http/https/…) return {@code false} so vanilla can handle them.
	 */
	public static boolean tryHandleUri(URI uri) {
		if (uri == null) {
			return false;
		}
		String scheme = uri.getScheme();
		if (scheme == null || !"file".equalsIgnoreCase(scheme)) {
			return false;
		}
		try {
			File file = new File(uri);
			open(file);
			return true;
		} catch (Throwable t) {
			FolderOpenPatchClient.LOGGER.warn("Failed to convert file URI: {}", uri, t);
			return false;
		}
	}

	static void openBlocking(File file) {
		try {
			if (!file.exists()) {
				// Pack/shader dirs may not exist yet.
				//noinspection ResultOfMethodCallIgnored
				file.mkdirs();
			}

			String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);

			if (os.contains("win")) {
				openWindows(file);
			} else if (os.contains("mac")) {
				new ProcessBuilder("open", file.getAbsolutePath())
						.redirectErrorStream(false)
						.start();
			} else {
				new ProcessBuilder("xdg-open", file.getAbsolutePath())
						.redirectErrorStream(false)
						.start();
			}

			FolderOpenPatchClient.LOGGER.info("Opened path asynchronously: {}", file.getAbsolutePath());
		} catch (Throwable t) {
			FolderOpenPatchClient.LOGGER.error("Failed to open path: {}", file, t);
		}
	}

	private static void openWindows(File file) throws Exception {
		// explorer.exe returns immediately for directories and avoids
		// SDL_OpenURL / ShellExecute hang sources.
		new ProcessBuilder("explorer.exe", file.getAbsolutePath())
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
