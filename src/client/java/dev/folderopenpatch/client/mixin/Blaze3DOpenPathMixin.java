package dev.folderopenpatch.client.mixin;

import com.mojang.blaze3d.Blaze3D;
import dev.folderopenpatch.client.SafeFolderOpener;
import java.nio.file.Path;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Direct openPath intercept (belt-and-suspenders).
 *
 * Vanilla pack screen uses this; it delegates to {@code openUri}. Iris and some
 * other mods call {@link Blaze3D#openUri} with a file URI directly — that is
 * covered by {@code Blaze3DOpenUriMixin}.
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
