package com.example.subnauticalink.mixin;

import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.example.subnauticalink.OverlayShare;
import com.example.subnauticalink.SubnauticaLinkClient;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.RenderTickCounter;

/**
 * The two moments in drawing a frame that the HUD sharing needs. See {@link OverlayShare}.
 */
@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {
	/**
	 * Minecraft draws a frame in this order: the world, then your hand, then the HUD. This
	 * steps in just before the hand. While linked it wipes the world away to see-through, so
	 * that only the hand and the HUD end up in the frame.
	 */
	@Inject(method = "renderHand", at = @At("HEAD"))
	private void subnauticaLink$onlyHandAndHud(Camera camera, float tickDelta, Matrix4f matrix, CallbackInfo ci) {
		if (OverlayShare.isActive()) {
			OverlayShare.clearToTransparent();
		}
	}

	/**
	 * Minecraft has just worked out which block you are looking at. If it found nothing but
	 * Subnautica's scenery is in reach, point it at the space in front of that scenery, so a
	 * block can be placed there.
	 */
	@Inject(method = "updateCrosshairTarget", at = @At("RETURN"))
	private void subnauticaLink$aimAtScenery(float tickDelta, CallbackInfo ci) {
		SubnauticaLinkClient.aimAtSubnauticaScenery(MinecraftClient.getInstance());
	}

	/** About to draw a frame: put the mouse pointer where it is in Subnautica's window. */
	@Inject(method = "render", at = @At("HEAD"))
	private void subnauticaLink$placePointer(RenderTickCounter tickCounter, boolean tick, CallbackInfo ci) {
		SubnauticaLinkClient.placePointer(MinecraftClient.getInstance());
	}

	/** The frame is finished: copy it to Subnautica. */
	@Inject(method = "render", at = @At("TAIL"))
	private void subnauticaLink$shareFrame(RenderTickCounter tickCounter, boolean tick, CallbackInfo ci) {
		OverlayShare.capture();
	}
}
