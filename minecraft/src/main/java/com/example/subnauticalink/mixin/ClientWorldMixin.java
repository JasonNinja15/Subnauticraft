package com.example.subnauticalink.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.example.subnauticalink.ClientBlocks;

import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.BlockPos;

/**
 * Passes on the cracks that spread over a block while it is being broken.
 *
 * <p>Each player's game is told how far along every block being broken nearby is, in ten
 * stages, whoever is breaking it: this player (their own game works it out) or anyone else
 * (the server says). Either way it arrives here, and Subnautica is told to draw the cracks.
 */
@Mixin(ClientWorld.class)
public abstract class ClientWorldMixin {
	@Inject(method = "setBlockBreakingInfo", at = @At("HEAD"))
	private void subnauticaLink$showCracks(int entityId, BlockPos pos, int progress, CallbackInfo ci) {
		ClientBlocks.onCrack(entityId, pos, progress);
	}
}
