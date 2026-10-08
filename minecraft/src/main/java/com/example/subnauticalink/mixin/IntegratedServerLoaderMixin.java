package com.example.subnauticalink.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import com.mojang.serialization.Lifecycle;

import net.minecraft.server.integrated.IntegratedServerLoader;

/**
 * Creating a world without the "experimental settings" question.
 *
 * <p>The same warning as in {@link LevelPropertiesMixin}, at the other place Minecraft shows
 * it: when "Create New World" is pressed. There the world's settings are handed to
 * IntegratedServerLoader.tryLoad along with how settled they are, and anything but "stable"
 * brings up the question first. So they are always handed over as stable.
 */
@Mixin(IntegratedServerLoader.class)
public abstract class IntegratedServerLoaderMixin {
	@ModifyVariable(method = "tryLoad", at = @At("HEAD"), argsOnly = true)
	private static Lifecycle subnauticaLink$alwaysStable(Lifecycle lifecycle) {
		return Lifecycle.stable();
	}
}
