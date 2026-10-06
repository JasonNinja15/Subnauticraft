package com.example.subnauticalink.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.example.subnauticalink.FallTracker;
import com.example.subnauticalink.RemoteCollision;

import net.minecraft.server.network.ServerPlayNetworkHandler;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.Box;
import net.minecraft.world.WorldView;

/**
 * Stops the server second-guessing where the linked player is.
 *
 * <p>Each time the player moves, the server checks the new position against Minecraft's blocks
 * and, if the player seems to have moved into one, puts them back. But the linked player's
 * collision is worked out by Subnautica, whose measurements can differ from Minecraft's by a
 * hair. Standing on a placed block, the player could be a fraction of a millimetre "inside"
 * it as far as the server was concerned, so it kept putting them back: the jitter, and the
 * false fall damage. For the linked player this check now always passes.
 */
@Mixin(ServerPlayNetworkHandler.class)
public abstract class ServerPlayNetworkHandlerMixin {
	@Shadow
	public ServerPlayerEntity player;

	// Despite its name, this method answers "has the player moved INTO a block?".
	@Inject(method = "isPlayerNotCollidingWithBlocks", at = @At("HEAD"), cancellable = true)
	private void subnauticaLink$trustSubnautica(WorldView world, Box box, double newX, double newY, double newZ, CallbackInfoReturnable<Boolean> cir) {
		if (RemoteCollision.isLinkedOnServer(this.player)) {
			cir.setReturnValue(false);
		}
	}

	/**
	 * The server has just put the player somewhere else in one go (an ender pearl landed, a
	 * command moved them). How far they had fallen up to then no longer means anything: without
	 * this, a pearl thrown down a cliff counted the whole cliff as a fall.
	 */
	@Inject(method = "requestTeleport(DDDFFLjava/util/Set;)V", at = @At("TAIL"))
	private void subnauticaLink$forgetFall(CallbackInfo ci) {
		if (RemoteCollision.isLinkedOnServer(this.player)) {
			FallTracker.reset(this.player);
		}
	}
}
