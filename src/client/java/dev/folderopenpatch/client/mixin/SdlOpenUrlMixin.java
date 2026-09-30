package dev.folderopenpatch.client.mixin;

import dev.folderopenpatch.client.SafeFolderOpener;
import java.nio.ByteBuffer;
import org.lwjgl.sdl.SDLMisc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Ultimate sink: any mod that calls {@code SDLMisc.SDL_OpenURL} directly
 * (bypassing Blaze3D) still gets the non-blocking open path.
 *
 * Native {@code nSDL_OpenURL} is reached only through these Java wrappers
 * in practice; intercepting the public entry points is enough.
 */
@Mixin(value = SDLMisc.class, remap = false)
public class SdlOpenUrlMixin {
	@Inject(method = "SDL_OpenURL(Ljava/lang/CharSequence;)Z", at = @At("HEAD"), cancellable = true)
	private static void folderopenpatch$openUrl(CharSequence url, CallbackInfoReturnable<Boolean> cir) {
		if (SafeFolderOpener.tryHandleUriString(url)) {
			cir.setReturnValue(true);
		}
	}

	@Inject(method = "SDL_OpenURL(Ljava/nio/ByteBuffer;)Z", at = @At("HEAD"), cancellable = true)
	private static void folderopenpatch$openUrl(ByteBuffer url, CallbackInfoReturnable<Boolean> cir) {
		if (url == null) {
			return;
		}
		// ByteBuffer is typically a UTF-8 C string from LWJGL
		ByteBuffer dup = url.duplicate();
		byte[] bytes = new byte[dup.remaining()];
		dup.get(bytes);
		// strip trailing NULs
		int len = bytes.length;
		while (len > 0 && bytes[len - 1] == 0) {
			len--;
		}
		String s = new String(bytes, 0, len, java.nio.charset.StandardCharsets.UTF_8);
		if (SafeFolderOpener.tryHandleUriString(s)) {
			cir.setReturnValue(true);
		}
	}
}
