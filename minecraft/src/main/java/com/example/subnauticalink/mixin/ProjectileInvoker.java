package com.example.subnauticalink.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

import net.minecraft.entity.projectile.ProjectileEntity;
import net.minecraft.util.hit.HitResult;

/**
 * Lets the link tell a projectile "you hit this", which normally only the projectile's own
 * flight code can do. Used when what it hit is in Subnautica (see Projectiles).
 */
@Mixin(ProjectileEntity.class)
public interface ProjectileInvoker {
	@Invoker("onCollision")
	void subnauticaLink$onCollision(HitResult hitResult);
}
