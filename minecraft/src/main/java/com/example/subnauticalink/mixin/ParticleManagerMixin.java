package com.example.subnauticalink.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.example.subnauticalink.ClientEffects;

import net.minecraft.block.BlockState;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleManager;
import net.minecraft.particle.ParticleEffect;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

/**
 * Listens at the three places Minecraft makes particles: the burst when a block breaks, the
 * trickle while one is being dug, and everything else. Minecraft carries on as normal; the
 * link is just told as well (see ClientEffects), so Subnautica can show something.
 */
@Mixin(ParticleManager.class)
public abstract class ParticleManagerMixin {
	@Inject(method = "addBlockBreakParticles", at = @At("HEAD"))
	private void subnauticaLink$blockBroken(BlockPos pos, BlockState state, CallbackInfo ci) {
		ClientEffects.onBlockBroken(pos, state);
	}

	@Inject(method = "addBlockBreakingParticles", at = @At("HEAD"))
	private void subnauticaLink$blockDug(BlockPos pos, Direction direction, CallbackInfo ci) {
		ClientEffects.onBlockDug(pos, direction);
	}

	@Inject(method = "addParticle(Lnet/minecraft/particle/ParticleEffect;DDDDDD)Lnet/minecraft/client/particle/Particle;", at = @At("HEAD"))
	private void subnauticaLink$otherParticle(ParticleEffect parameters, double x, double y, double z, double velocityX, double velocityY, double velocityZ, CallbackInfoReturnable<Particle> cir) {
		ClientEffects.onParticle(parameters, x, y, z);
	}
}
