package dev.folderopenpatch.client.mixin;

import com.mojang.blaze3d.Blaze3D;
import dev.folderopenpatch.client.SafeFolderOpener;
import java.nio.file.Path;
import net.minecraft.client.gui.screens.packs.PackSelectionScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Belt-and-suspenders: even if Blaze3D mixin fails to apply, still keep the
 * resource pack screen button off the blocking SDL path.
 */
@Mixin(value = PackSelectionScreen.class, remap = false)
public class PackSelectionScreenMixin {
	@Redirect(
			method = "lambda$init$0",
			at = @At(
					value = "INVOKE",
					target = "Lcom/mojang/blaze3d/Blaze3D;openPath(Ljava/nio/file/Path;)V"
			)
	)
	private void folderopenpatch$openPackFolderAsync(Path path) {
		SafeFolderOpener.open(path.toFile());
	}
}
