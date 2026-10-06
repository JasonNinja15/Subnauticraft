package com.example.subnauticalink.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.example.subnauticalink.WaterState;

import net.minecraft.entity.vehicle.BoatEntity;
import net.minecraft.fluid.FluidState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

/**
 * Lets a boat float on Subnautica's sea. A boat works out whether it is afloat, and how high
 * the water is, by looking for water blocks; in the ocean void it is told there is water
 * everywhere below sea level.
 */
@Mixin(BoatEntity.class)
public abstract class BoatEntityMixin {
	// require = 0: if a different Minecraft version words these differently, boats just don't float there.
	@WrapOperation(method = { "getWaterHeightBelow", "checkBoatInWater", "getUnderWaterLocation" },
			at = @At(value = "INVOKE", target = "Lnet/minecraft/world/World;getFluidState(Lnet/minecraft/util/math/BlockPos;)Lnet/minecraft/fluid/FluidState;"), require = 0)
	private FluidState subnauticaLink$seaWater(World world, BlockPos pos, Operation<FluidState> original) {
		return WaterState.seaFor(world, pos, original.call(world, pos));
	}
}
