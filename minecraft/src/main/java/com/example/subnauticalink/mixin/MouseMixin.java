package com.example.subnauticalink.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.example.subnauticalink.OverlayShare;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.Mouse;

/**
 * Lets blocks be broken while Minecraft's window is in the background.
 *
 * <p>Holding the attack button only breaks a block while Minecraft has "captured" the mouse,
 * which is its sign that you are playing rather than using a menu. It can only capture the
 * mouse when its window is in front, and once an inventory or chat box has been opened and
 * closed from Subnautica, it has let go and can't take it back. So while linked, with no
 * screen open, Minecraft is told the mouse is captured.
 */
@Mixin(Mouse.class)
public abstract class MouseMixin {
	@Inject(method = "isCursorLocked", at = @At("HEAD"), cancellable = true)
	private void subnauticaLink$playingFromSubnautica(CallbackInfoReturnable<Boolean> cir) {
		if (MinecraftClient.getInstance().currentScreen == null && OverlayShare.isActive()) {
			cir.setReturnValue(true);
		}
	}
}
