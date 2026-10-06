package com.example.subnauticalink.mixin;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.example.subnauticalink.WaterState;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.fluid.Fluid;
import net.minecraft.item.BucketItem;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

/**
 * Water can't be poured out under Subnautica's sea: there is sea there already. The bucket
 * simply stays full. Above the surface (on an island, a platform, a base's roof) it pours as
 * usual. Lava is not affected.
 */
@Mixin(BucketItem.class)
public abstract class BucketItemMixin {
	@Shadow
	@Final
	private Fluid fluid;

	@Inject(method = "placeFluid", at = @At("HEAD"), cancellable = true)
	private void subnauticaLink$noWaterUnderTheSea(PlayerEntity player, World world, BlockPos pos, BlockHitResult hitResult, CallbackInfoReturnable<Boolean> cir) {
		if (this.fluid.isIn(FluidTags.WATER) && WaterState.underTheSea(world, pos)) {
			cir.setReturnValue(false);
		}
	}
}
