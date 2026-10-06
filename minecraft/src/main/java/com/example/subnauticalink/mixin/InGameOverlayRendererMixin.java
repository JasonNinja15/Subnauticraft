package com.example.subnauticalink.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.example.subnauticalink.OverlayShare;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.hud.InGameOverlayRenderer;
import net.minecraft.client.util.math.MatrixStack;

/**
 * When your head is underwater, Minecraft tints the whole screen with a faint water texture.
 * Subnautica already draws its own underwater view, so while the picture is being shared the
 * tint is left out. (It is drawn together with the hand, which is why it would otherwise end
 * up in the shared picture.)
 */
@Mixin(InGameOverlayRenderer.class)
public abstract class InGameOverlayRendererMixin {
	@Inject(method = "renderUnderwaterOverlay", at = @At("HEAD"), cancellable = true)
	private static void subnauticaLink$noWaterTint(MinecraftClient client, MatrixStack matrices, CallbackInfo ci) {
		if (OverlayShare.isActive()) {
			ci.cancel();
		}
	}
}
