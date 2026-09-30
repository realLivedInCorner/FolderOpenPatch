package dev.folderopenpatch.client.mixin;

import com.mojang.blaze3d.Blaze3D;
import dev.folderopenpatch.client.SafeFolderOpener;
import java.net.URI;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Intercepts {@code Blaze3D.openUri}. File URIs go to the safe opener;
 * other schemes are also routed through the safe opener so SDL is never hit
 * from this API (update links still open, just via explorer/open).
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
