package com.example.subnauticalink.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.example.subnauticalink.OverlayShare;
import com.example.subnauticalink.RemoteControls;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;

/**
 * Shift-clicking in Minecraft's inventory from Subnautica.
 *
 * <p>When a slot is clicked, Minecraft checks its own keyboard to see whether Shift is held
 * (shift-click sends the whole pile to the other part of the inventory). The keyboard is
 * Subnautica's while linked, so Minecraft always saw "not held". Subnautica now says with each
 * click whether Shift is down, and while a screen is open under its control that answer is
 * given here.
 */
@Mixin(Screen.class)
public abstract class ScreenMixin {
	@Inject(method = "hasShiftDown", at = @At("HEAD"), cancellable = true)
	private static void subnauticaLink$shiftFromSubnautica(CallbackInfoReturnable<Boolean> cir) {
		if (RemoteControls.shiftHeld && MinecraftClient.getInstance().currentScreen != null && OverlayShare.isActive()) {
			cir.setReturnValue(true);
		}
	}

	/**
	 * No screen stops the game while linked. Minecraft's game menu (and its options, and
	 * anything else that asks to) pauses a world played alone; with Subnautica in front that
	 * would stop the link's own traffic, and Subnautica has its own say over pausing.
	 */
	@Inject(method = "shouldPause", at = @At("HEAD"), cancellable = true)
	private void subnauticaLink$noPauseWhileLinked(CallbackInfoReturnable<Boolean> cir) {
		if (OverlayShare.isActive()) {
			cir.setReturnValue(false);
		}
	}
}
