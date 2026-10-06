package com.example.subnauticalink.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.example.subnauticalink.Projectiles;

import net.minecraft.entity.Entity;
import net.minecraft.entity.projectile.PersistentProjectileEntity;
import net.minecraft.util.math.Vec3d;

/**
 * Keeps arrows, knives and hooks that are stuck in Subnautica's scenery where they are.
 *
 * <p>The game keeps two copies of every projectile: one on the server side, which decides
 * what happens, and one on the client side. When the server's copy sticks into Subnautica's
 * scenery (see Projectiles), two things would undo it:
 *
 * <ul>
 *   <li>The client's copy can't see the scenery and would fly on through it. That matters
 *       for the grappling hook, whose rope pulls the player toward the client's copy. So
 *       while a projectile is stuck, the client's copy is simply held at the same spot.</li>
 *   <li>A stuck arrow checks that there is still a block around it and drops if not. There
 *       is no Minecraft block there, so that check is answered "don't drop".</li>
 * </ul>
 */
@Mixin(PersistentProjectileEntity.class)
public abstract class PersistentProjectileMixin {
	@Inject(method = "tick", at = @At("HEAD"), cancellable = true)
	private void subnauticaLink$holdClientCopy(CallbackInfo ci) {
		Entity self = (Entity) (Object) this;

		if (!self.getWorld().isClient) {
			return;
		}

		Vec3d at = Projectiles.stuckHere(self.getId());

		if (at != null) {
			self.setPosition(at.x, at.y, at.z);
			self.setVelocity(Vec3d.ZERO);
			ci.cancel();
		}
	}

	@Inject(method = "shouldFall", at = @At("HEAD"), cancellable = true)
	private void subnauticaLink$stayStuck(CallbackInfoReturnable<Boolean> cir) {
		Entity self = (Entity) (Object) this;

		if (!self.getWorld().isClient && Projectiles.stuckAt(self.getId()) != null) {
			cir.setReturnValue(false);
		}
	}
}
