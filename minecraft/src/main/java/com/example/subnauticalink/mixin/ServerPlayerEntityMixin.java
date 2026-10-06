package com.example.subnauticalink.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.example.subnauticalink.FallTracker;
import com.example.subnauticalink.RemoteCollision;

import net.minecraft.server.network.ServerPlayerEntity;

/**
 * Each time a player's new position arrives, the server calls {@code handleFall} to keep its
 * fall distance up to date and apply fall damage on landing. For the linked player, the fall
 * distance is worked out by {@link FallTracker} first; Minecraft then carries on as usual.
 */
@Mixin(ServerPlayerEntity.class)
public abstract class ServerPlayerEntityMixin {
	@Inject(method = "handleFall", at = @At("HEAD"))
	private void subnauticaLink$trackFall(double xDifference, double yDifference, double zDifference, boolean onGround, CallbackInfo ci) {
		ServerPlayerEntity self = (ServerPlayerEntity) (Object) this;

		if (RemoteCollision.isLinkedOnServer(self)) {
			FallTracker.update(self, onGround);
		}
	}
}
