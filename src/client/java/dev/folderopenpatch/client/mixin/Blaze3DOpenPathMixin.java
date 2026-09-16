package dev.folderopenpatch.client.mixin;

import com.mojang.blaze3d.Blaze3D;
import dev.folderopenpatch.client.SafeFolderOpener;
import java.nio.file.Path;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Core fix for MC 26.3.
 *
 * Vanilla "Open pack folder" calls {@code Blaze3D.openPath}, which converts the
 * path to a {@code file://} URI and hands it to {@code SDL_OpenURL}. On Windows
 * that path can hang the process (ShellExecute / shell extension), making the
 * game look unresponsive.
 *
 * Replace it with an async {@code explorer.exe} open that returns immediately.
 */
@Mixin(value = Blaze3D.class, remap = false)
public class Blaze3DOpenPathMixin {
	@Inject(method = "openPath", at = @At("HEAD"), cancellable = true)
	private static void folderopenpatch$openPathAsync(Path path, CallbackInfo ci) {
		if (path == null) {
			return;
		}
		SafeFolderOpener.open(path.toFile());
		ci.cancel();
	}
}
