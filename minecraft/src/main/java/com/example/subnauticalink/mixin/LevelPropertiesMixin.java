package com.example.subnauticalink.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.mojang.serialization.Lifecycle;

import net.minecraft.world.level.LevelProperties;

/**
 * Opening a world without the "Worlds using experimental settings are not supported" screen.
 *
 * <p>Minecraft calls any world with a dimension of a mod's own "experimental", and stops at a
 * warning with a button to press every time such a world is opened. This mod adds a dimension
 * (the empty one linked players are kept in), so every world opened with it installed got the
 * warning; and a launcher that opens a world by itself was stopped there with nobody to press
 * the button.
 *
 * <p>The warning is shown when a world's saved settings say they are anything but "stable"
 * (IntegratedServerLoader.checkBackupAndStart asks this). So they always say stable. Nothing
 * else about the world changes, and nothing is written to the save differently.
 *
 * <p>This holds for every world opened while the mod is installed, including ones that are
 * experimental for some other reason.
 */
@Mixin(LevelProperties.class)
public abstract class LevelPropertiesMixin {
	@Inject(method = "getLifecycle", at = @At("HEAD"), cancellable = true)
	private void subnauticaLink$alwaysStable(CallbackInfoReturnable<Lifecycle> cir) {
		cir.setReturnValue(Lifecycle.stable());
	}
}
