package com.example.subnauticalink.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.example.subnauticalink.WaterState;

import net.minecraft.block.BlockState;
import net.minecraft.fluid.FlowableFluid;
import net.minecraft.fluid.FluidState;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

/**
 * Makes Subnautica's sea a floor for Minecraft's water.
 *
 * <p>Poured water runs downhill for as long as there is nothing under it. Off the edge of an
 * island that would be for ever, straight down through the sea. So for water in the layer of
 * blocks resting on the sea's surface, "run down if you can, otherwise spread out" becomes
 * just "spread out", exactly as if the sea were ground. Lava is not affected.
 */
@Mixin(FlowableFluid.class)
public abstract class FlowableFluidMixin {
	@Shadow
	private void flowToSides(World world, BlockPos pos, FluidState fluidState, BlockState blockState) {
		throw new AssertionError();
	}

	@Inject(method = "tryFlow", at = @At("HEAD"), cancellable = true)
	private void subnauticaLink$seaIsAFloor(World world, BlockPos fluidPos, FluidState state, CallbackInfo ci) {
		if (!state.isEmpty() && state.isIn(FluidTags.WATER) && WaterState.underTheSea(world, fluidPos.down())) {
			// Already under the surface (poured before this rule, say): it goes nowhere.
			if (!WaterState.underTheSea(world, fluidPos)) {
				this.flowToSides(world, fluidPos, state, world.getBlockState(fluidPos));
			}

			ci.cancel();
		}
	}
}
