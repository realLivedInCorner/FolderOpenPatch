package dev.folderopenpatch.client.mixin;

import com.mojang.blaze3d.Blaze3D;
import dev.folderopenpatch.client.SafeFolderOpener;
import java.net.URI;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Central choke point used by both vanilla openPath and Iris ShaderPackScreen
 * (Iris calls {@code Blaze3D.openUri} with a {@code file://} directory URI).
 *
 * Only {@code file:} URIs are intercepted; http(s) (e.g. update links) fall through.
 */
@Mixin(value = Blaze3D.class, remap = false)
public class Blaze3DOpenUriMixin {
	@Inject(method = "openUri", at = @At("HEAD"), cancellable = true)
	private static void folderopenpatch$openUriAsync(URI uri, CallbackInfo ci) {
		if (SafeFolderOpener.tryHandleUri(uri)) {
			ci.cancel();
		}
	}
}
