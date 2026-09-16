package dev.folderopenpatch.client;

import java.awt.Desktop;
import java.io.File;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Opens folders without blocking Minecraft's client/render thread.
 *
 * Vanilla uses {@code Desktop.browseFileDirectory} / {@code Desktop.browse}
 * on the calling thread. On Windows those AWT calls can hang (shell extension,
 * COM/DDE wait, non-ASCII path, explorer busy), freezing the whole game.
 */
public final class SafeFolderOpener {
	private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(runnable -> {
		Thread thread = new Thread(runnable, "FolderOpenPatch-Open");
		thread.setDaemon(true);
		return thread;
	});

	private static final AtomicBoolean AWTRISK_WARNED = new AtomicBoolean(false);

	private SafeFolderOpener() {
	}

	/**
	 * Opens {@code file} on a background thread and returns immediately.
	 */
	public static void open(File file) {
		if (file == null) {
			return;
		}
		EXECUTOR.execute(() -> openBlocking(file));
	}

	/**
	 * Opens {@code file} on the current thread (used only by the worker).
	 */
	static void openBlocking(File file) {
		try {
			if (!file.exists()) {
				// Pack dir may not exist yet; create it so explorer can open.
				// Mirrors vanilla behaviour of creating the folder before open.
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

			FolderOpenPatchClient.LOGGER.info("Opened folder asynchronously: {}", file.getAbsolutePath());
		} catch (Throwable t) {
			FolderOpenPatchClient.LOGGER.error("Failed to open folder: {}", file, t);
		}
	}

	private static void openWindows(File file) throws Exception {
		// explorer.exe returns immediately for directories and does not
		// go through the AWT Desktop API that can hang forever.
		String path = file.getAbsolutePath();
		new ProcessBuilder("explorer.exe", path)
				.redirectErrorStream(false)
				.start();

		// Fallback only if process creation somehow succeeded but we still
		// want a Desktop path as last resort — intentionally NOT used by
		// default because Desktop.browseFileDirectory is the freeze source.
		if (AWTRISK_WARNED.compareAndSet(false, true)) {
			FolderOpenPatchClient.LOGGER.debug("Using explorer.exe instead of Desktop.browseFileDirectory");
		}
	}

	/**
	 * Last-resort open used if native launch fails. Still runs off-thread.
	 */
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
