package com.example.subnauticalink.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.example.subnauticalink.ItemSync;

import net.minecraft.block.BlockState;
import net.minecraft.block.FallingBlock;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.random.Random;

/**
 * Lets fallen sand lie on Subnautica's scenery.
 *
 * <p>Sand (and gravel, and the other blocks that fall) checks now and then whether there is
 * anything under it, and falls if not. Sand that has landed on Subnautica's sea bed or an
 * island has nothing under it that Minecraft can see, so it would fall, land in the same
 * place, and fall again, for ever. The places where sand has landed that way are remembered
 * (see ItemSync), and for those this check is skipped.
 */
@Mixin(FallingBlock.class)
public abstract class FallingBlockMixin {
	@Inject(method = "scheduledTick", at = @At("HEAD"), cancellable = true)
	private void subnauticaLink$lieOnScenery(BlockState state, ServerWorld world, BlockPos pos, Random random, CallbackInfo ci) {
		if (ItemSync.restsOnScenery(world, pos)) {
			ci.cancel();
		}
	}
}
