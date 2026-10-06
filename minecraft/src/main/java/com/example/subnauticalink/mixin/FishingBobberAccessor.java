package com.example.subnauticalink.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.minecraft.entity.projectile.FishingBobberEntity;

/** Lets this mod ask a fishing bobber whether something is on the line, to show it in Subnautica. */
@Mixin(FishingBobberEntity.class)
public interface FishingBobberAccessor {
	@Accessor("caughtFish")
	boolean subnauticaLink$caughtFish();
}
