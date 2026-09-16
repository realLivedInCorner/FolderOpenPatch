package dev.folderopenpatch.client;

import net.fabricmc.api.ClientModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Client entrypoint for Folder Open Patch.
 * Mixins do the actual work; this class only logs readiness.
 */
public class FolderOpenPatchClient implements ClientModInitializer {
	public static final String MOD_ID = "folderopenpatch";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitializeClient() {
		LOGGER.info("Folder Open Patch loaded: resource pack folder open will not block the render thread.");
	}
}
