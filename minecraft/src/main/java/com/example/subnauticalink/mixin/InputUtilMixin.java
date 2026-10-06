package com.example.subnauticalink.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.example.subnauticalink.OverlayShare;
import com.example.subnauticalink.RemoteControls;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.util.InputUtil;

/**
 * Shift-clicking in Minecraft's inventory from Subnautica.
 *
 * <p>When a slot is clicked, Minecraft asks its own keyboard whether a Shift key is held
 * (shift-click sends the whole pile to the other part of the inventory). The keyboard is
 * Subnautica's while linked, so the answer was always "no". Subnautica says with each click
 * whether Shift is down; while a screen is open under its control, that is the answer given.
 * The numbers are the ones Minecraft's window system uses for the two Shift keys.
 */
@Mixin(InputUtil.class)
public abstract class InputUtilMixin {
	@Inject(method = "isKeyPressed", at = @At("HEAD"), cancellable = true)
	private static void subnauticaLink$shiftFromSubnautica(long handle, int code, CallbackInfoReturnable<Boolean> cir) {
		if ((code == 340 || code == 344) && RemoteControls.shiftHeld && MinecraftClient.getInstance().currentScreen != null && OverlayShare.isActive()) {
			cir.setReturnValue(true);
		}
	}
}
