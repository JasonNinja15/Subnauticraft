package com.example.subnauticalink.mixin;

import java.util.Locale;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.example.subnauticalink.MovementBridge;
import com.example.subnauticalink.Sessions;

import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.minecraft.world.explosion.Explosion;

/**
 * Tells Subnautica about explosions (TNT, creepers, anything), so its creatures are hurt too.
 *
 * <p>Minecraft's own explosion still does everything it normally does: breaks Minecraft's
 * blocks and hurts the player. This adds "BLAST x y z power" at the moment it goes off, and
 * Subnautica takes health off the creatures in range, by Minecraft's own formula.
 */
@Mixin(Explosion.class)
public abstract class ExplosionMixin {
	@Shadow
	@Final
	private World world;

	@Shadow
	public abstract Vec3d getPosition();

	@Shadow
	public abstract float getPower();

	@Inject(method = "collectBlocksAndDamageEntities", at = @At("HEAD"))
	private void subnauticaLink$blastSubnautica(CallbackInfo ci) {
		if (!this.world.isClient && this.world.getRegistryKey() == MovementBridge.OCEAN_VOID && Sessions.anyActive()) {
			Vec3d at = this.getPosition();
			// The nearest player's Subnautica hurts the creatures; everyone else's just shows it.
			Sessions.sendNearest(this.world, at,
					String.format(Locale.ROOT, "BLAST %.3f %.3f %.3f %.2f", at.x, at.y, at.z, this.getPower()),
					String.format(Locale.ROOT, "FX blast %.3f %.3f %.3f", at.x, at.y, at.z));
		}
	}
}
