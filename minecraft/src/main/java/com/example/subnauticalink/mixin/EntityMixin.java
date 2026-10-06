package com.example.subnauticalink.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.example.subnauticalink.MovementBridge;
import com.example.subnauticalink.RemoteCollision;
import com.example.subnauticalink.WaterState;

import net.minecraft.entity.Entity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.MovementType;
import net.minecraft.fluid.Fluid;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.registry.tag.TagKey;
import net.minecraft.util.math.Vec3d;

/**
 * Changes to how every entity works, which only ever take effect for the linked player.
 *
 * <p>COLLISION. When anything moves, Minecraft calls {@code adjustMovementForCollisions} with
 * the movement it wants and gets back the movement that is possible. For the linked player
 * this steps in at the very start of that method and asks Subnautica about its scenery first
 * (see {@link RemoteCollision}); Minecraft then checks its own blocks as usual.
 *
 * <p>WATER. Minecraft finds out whether an entity is in water by looking for water blocks, and
 * the ocean void has none. The methods below are the questions the rest of the game asks
 * ("touching water?", "head under?", "how deep?"); for the linked player they are answered
 * from {@link WaterState}. Minecraft's own swimming then works as usual.
 */
@Mixin(Entity.class)
public abstract class EntityMixin {
	// ---- Collision ---------------------------------------------------------------------------

	@Inject(method = "adjustMovementForCollisions(Lnet/minecraft/util/math/Vec3d;)Lnet/minecraft/util/math/Vec3d;", at = @At("HEAD"), cancellable = true)
	private void subnauticaLink$askSubnautica(Vec3d movement, CallbackInfoReturnable<Vec3d> cir) {
		Entity self = (Entity) (Object) this;

		// The server's re-run of the linked player's move: nothing is in the way, because
		// Subnautica has already settled where the player can go.
		if (RemoteCollision.isLinkedOnServer(self)) {
			cir.setReturnValue(movement);
			return;
		}

	}

	/**
	 * The linked player's own game. Before Minecraft checks its own blocks, the movement is
	 * cut down to what Subnautica's scenery allows. Minecraft then carries on as normal with
	 * what is left, so its own blocks stop the player exactly as they do in any world (walking
	 * across a floor of them is as smooth as ever, and slabs and stairs are stepped up).
	 * Subnautica leaves Minecraft's blocks out of its answer, so nothing is counted twice.
	 */
	@ModifyVariable(method = "adjustMovementForCollisions(Lnet/minecraft/util/math/Vec3d;)Lnet/minecraft/util/math/Vec3d;", at = @At("HEAD"), argsOnly = true)
	private Vec3d subnauticaLink$askSubnauticaFirst(Vec3d movement) {
		Entity self = (Entity) (Object) this;

		// In the ocean void with no word from Subnautica (it is loading, or its world has been
		// closed): there is nothing to stand on, so the player is held where they are until
		// Subnautica is back or the server sends them home.
		if (RemoteCollision.strandedInVoid(self)) {
			return Vec3d.ZERO;
		}

		// Not moving: nothing to ask.
		if (movement.lengthSquared() == 0.0 || !(RemoteCollision.appliesTo(self) || RemoteCollision.appliesToBoat(self))) {
			return movement;
		}

		return RemoteCollision.resolve(self, movement);
	}

	/** Once the move is finished, give back the speed for sliding along slanted walls. */
	@Inject(method = "move", at = @At("RETURN"))
	private void subnauticaLink$keepSlidingSpeed(MovementType type, Vec3d movement, CallbackInfo ci) {
		RemoteCollision.afterMove((Entity) (Object) this);
	}

	// ---- Water -------------------------------------------------------------------------------

	/** In water, stretch each tick's movement so swimming is as fast as in Subnautica. */
	@ModifyVariable(method = "move", at = @At("HEAD"), argsOnly = true)
	private Vec3d subnauticaLink$swimAtSubnauticaSpeed(Vec3d movement) {
		return WaterState.scaleMovement((Entity) (Object) this, movement);
	}

	@Inject(method = "isTouchingWater", at = @At("HEAD"), cancellable = true)
	private void subnauticaLink$touchingWater(CallbackInfoReturnable<Boolean> cir) {
		if (WaterState.isTouching((Entity) (Object) this)) {
			cir.setReturnValue(true);
		}
	}

	@Inject(method = "isSubmergedInWater", at = @At("HEAD"), cancellable = true)
	private void subnauticaLink$submergedInWater(CallbackInfoReturnable<Boolean> cir) {
		if (WaterState.isSubmerged((Entity) (Object) this)) {
			cir.setReturnValue(true);
		}
	}

	@Inject(method = "isSubmergedIn", at = @At("HEAD"), cancellable = true)
	private void subnauticaLink$submergedIn(TagKey<Fluid> fluidTag, CallbackInfoReturnable<Boolean> cir) {
		if (fluidTag == FluidTags.WATER && WaterState.isSubmerged((Entity) (Object) this)) {
			cir.setReturnValue(true);
		}
	}

	/** How far up the entity the water comes. Minecraft uses it to decide between swimming up and jumping. */
	@Inject(method = "getFluidHeight", at = @At("HEAD"), cancellable = true)
	private void subnauticaLink$waterHeight(TagKey<Fluid> fluid, CallbackInfoReturnable<Double> cir) {
		Entity self = (Entity) (Object) this;

		if (fluid == FluidTags.WATER && WaterState.isTouching(self)) {
			cir.setReturnValue(WaterState.depthOn(self));
		}
	}

	// ---- Dropped items -----------------------------------------------------------------------

	/**
	 * A dropped item that finds itself inside a block is shoved out sideways by Minecraft.
	 * In the ocean void, items are placed by Subnautica's collision (see ItemSync), which can
	 * rest one a hair inside the block it lies on. Minecraft took that as "inside a block" and
	 * kept shoving the item around. For items in the ocean void the shove is skipped.
	 */
	@Inject(method = "pushOutOfBlocks(DDD)V", at = @At("HEAD"), cancellable = true)
	private void subnauticaLink$noShovingItems(double x, double y, double z, CallbackInfo ci) {
		Entity self = (Entity) (Object) this;

		if (self instanceof ItemEntity && self.getWorld().getRegistryKey() == MovementBridge.OCEAN_VOID) {
			ci.cancel();
		}
	}

	/**
	 * Starting to sprint-swim. Minecraft only allows it if there is a water block where the
	 * entity is, which there never is here, so that one decision is made for it.
	 */
	@Inject(method = "updateSwimming", at = @At("HEAD"), cancellable = true)
	private void subnauticaLink$startSwimming(CallbackInfo ci) {
		Entity self = (Entity) (Object) this;

		if (!self.isSwimming() && self.isSprinting() && !self.hasVehicle() && WaterState.isSubmerged(self)) {
			self.setSwimming(true);
			ci.cancel();
		}
	}

	/**
	 * When a swimmer bumps into something, Minecraft checks whether the space just above is
	 * clear and out of the water, and if so hops them up (that is how you climb out at a
	 * shore). It checks for water blocks, so here it would hop on every bump, even on the
	 * seabed. This says "not clear" whenever that space is still under the sea.
	 */
	@Inject(method = "doesNotCollide(DDD)Z", at = @At("HEAD"), cancellable = true)
	private void subnauticaLink$noHoppingUnderwater(double offsetX, double offsetY, double offsetZ, CallbackInfoReturnable<Boolean> cir) {
		Entity self = (Entity) (Object) this;

		if (WaterState.isTouching(self) && self.getBoundingBox().minY + offsetY < WaterState.SEA_LEVEL) {
			cir.setReturnValue(false);
		}
	}
}
