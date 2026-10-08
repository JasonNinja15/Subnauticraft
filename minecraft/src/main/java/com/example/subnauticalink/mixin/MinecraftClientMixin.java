package com.example.subnauticalink.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.example.subnauticalink.OverlayShare;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;

/**
 * No screen stops the game while linked: the same as in {@link ScreenMixin}, at the place
 * Minecraft asks the question each frame. This one also covers screens that answer for
 * themselves instead of leaving it to the usual answer.
 *
 * <p>If Minecraft doesn't ask there after all, this simply does nothing ("require = 0").
 */
@Mixin(MinecraftClient.class)
public abstract class MinecraftClientMixin {
	@WrapOperation(method = "render", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/screen/Screen;shouldPause()Z"), require = 0)
	private boolean subnauticaLink$noPauseWhileLinked(Screen screen, Operation<Boolean> original) {
		return !OverlayShare.isActive() && original.call(screen);
	}
}
