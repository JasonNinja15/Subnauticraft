package com.example.subnauticalink.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.example.subnauticalink.LinkSession;
import com.example.subnauticalink.RemoteCollision;
import com.example.subnauticalink.Sessions;
import com.example.subnauticalink.WaterState;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityPose;
import net.minecraft.entity.player.PlayerEntity;

/**
 * Two changes to how players work, which only take effect for the linked player: standing up
 * is never refused (see below), and sprint-swimming upwards works by looking up.
 *
 * <p>While sprint-swimming, Minecraft steers the player up or down to follow where they look.
 * Going up, it first checks that the block above the player has water in it, so that you stop
 * at the surface. The ocean void has no water blocks, so for the linked player that check is
 * answered from {@link WaterState} instead.
 */
@Mixin(PlayerEntity.class)
public abstract class PlayerEntityMixin {
	/**
	 * Stops the linked player being forced into a crouch or a crawl.
	 *
	 * <p>Before letting a player stand, Minecraft checks that a standing player would fit
	 * without overlapping any block; if not, it makes them crouch, and failing that, crawl.
	 * The linked player's position comes from Subnautica's collision, which can leave them a
	 * hair inside a block they are standing on or leaning against. Minecraft took that as "no
	 * room to stand". So for the linked player the check is made a different way: only the
	 * space the taller pose would add above their head is looked at, in Minecraft's blocks and,
	 * by asking it, in Subnautica's scenery. (Until 2.17 the answer was always "there is
	 * room", which let a swimmer stand up into a low ceiling and so through it.)
	 */
	@Inject(method = "canChangeIntoPose", at = @At("HEAD"), cancellable = true)
	private void subnauticaLink$alwaysRoom(EntityPose pose, CallbackInfoReturnable<Boolean> cir) {
		Entity self = (Entity) (Object) this;

		if (RemoteCollision.appliesTo(self)) {
			// The player's own game: is there room, by Subnautica's scenery and Minecraft's blocks?
			cir.setReturnValue(RemoteCollision.hasRoomFor(self, self.getDimensions(pose).height()));
		} else if (RemoteCollision.isLinkedOnServer(self)) {
			// The server can't ask Subnautica, so it goes by what the player's own game found:
			// nothing taller than the player is there.
			LinkSession session = Sessions.of(self);
			cir.setReturnValue(session == null || self.getDimensions(pose).height() <= session.poseHeight + 0.01F);
		}
	}

	// require = 0: if a different Minecraft version words this check differently, skip this
	// change quietly instead of refusing to start. Swimming up then needs jump held.
	@ModifyExpressionValue(method = "travel", at = @At(value = "INVOKE", target = "Lnet/minecraft/fluid/FluidState;isEmpty()Z"), require = 0)
	private boolean subnauticaLink$waterAbove(boolean noWaterAbove) {
		return noWaterAbove && !WaterState.isSubmerged((Entity) (Object) this);
	}
}
