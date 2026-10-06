package com.example.subnauticalink.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.example.subnauticalink.RemoteCollision;

import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.Entity;

/**
 * Stops Minecraft shoving the linked player off the blocks they stand on.
 *
 * <p>If Minecraft finds the player overlapping a solid block, it pushes them sideways out of
 * it (this is what squeezes you out when sand falls on you). The linked player's height comes
 * from Subnautica's collision, which can leave their feet a hair below the top of the block
 * they are standing on. Minecraft counted that as "inside the block" and pushed. The linked
 * player's collisions are Subnautica's business, so the push is switched off for them.
 */
@Mixin(ClientPlayerEntity.class)
public abstract class ClientPlayerEntityMixin {
	@Inject(method = "pushOutOfBlocks", at = @At("HEAD"), cancellable = true)
	private void subnauticaLink$noPush(double x, double z, CallbackInfo ci) {
		if (RemoteCollision.appliesTo((Entity) (Object) this)) {
			ci.cancel();
		}
	}
}
