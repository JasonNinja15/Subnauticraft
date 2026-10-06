package com.example.subnauticalink.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.example.subnauticalink.BlockSync;

import net.minecraft.block.BlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

/**
 * Notices every block change. Every way of changing a block in Minecraft (placing, breaking,
 * explosions, commands) ends up in this one method, so this is the place to listen. Changes
 * that matter are passed on to Subnautica by {@link BlockSync}.
 */
@Mixin(World.class)
public abstract class WorldMixin {
	@Inject(method = "setBlockState(Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/block/BlockState;II)Z", at = @At("RETURN"))
	private void subnauticaLink$blockChanged(BlockPos pos, BlockState state, int flags, int maxUpdateDepth, CallbackInfoReturnable<Boolean> cir) {
		// The method returns true only if the block really changed.
		if (cir.getReturnValue()) {
			BlockSync.onChanged((World) (Object) this, pos.toImmutable(), state);
		}
	}
}
