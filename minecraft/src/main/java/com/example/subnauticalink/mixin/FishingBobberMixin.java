package com.example.subnauticalink.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.example.subnauticalink.WaterState;

import net.minecraft.entity.projectile.FishingBobberEntity;
import net.minecraft.fluid.FluidState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

/**
 * Lets a fishing rod be used on Subnautica's sea. A bobber floats, and fish bite, only where
 * it finds water; in the ocean void it is told there is water everywhere below sea level.
 */
@Mixin(FishingBobberEntity.class)
public abstract class FishingBobberMixin {
	// require = 0: if a different Minecraft version words this differently, fishing just doesn't work there.
	@WrapOperation(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/World;getFluidState(Lnet/minecraft/util/math/BlockPos;)Lnet/minecraft/fluid/FluidState;"), require = 0)
	private FluidState subnauticaLink$seaWater(World world, BlockPos pos, Operation<FluidState> original) {
		return WaterState.seaFor(world, pos, original.call(world, pos));
	}
}
