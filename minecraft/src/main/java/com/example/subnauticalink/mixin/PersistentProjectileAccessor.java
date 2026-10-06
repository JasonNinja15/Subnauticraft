package com.example.subnauticalink.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

import net.minecraft.entity.projectile.PersistentProjectileEntity;
import net.minecraft.item.ItemStack;

/** Lets the link read two things about an arrow-like projectile: whether it is stuck in something, and which item it is. */
@Mixin(PersistentProjectileEntity.class)
public interface PersistentProjectileAccessor {
	@Accessor("inGround")
	boolean subnauticaLink$inGround();

	@Invoker("getItemStack")
	ItemStack subnauticaLink$itemStack();
}
