package com.example.subnauticalink.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.minecraft.client.Mouse;

/**
 * Lets the mod set where Minecraft thinks the mouse pointer is. Minecraft keeps this to
 * itself (it normally only comes from the real mouse over Minecraft's own window); with an
 * inventory open from Subnautica, the pointer position comes from Subnautica's window instead.
 */
@Mixin(Mouse.class)
public interface MouseAccessor {
	@Accessor("x")
	void subnauticaLink$setX(double x);

	@Accessor("y")
	void subnauticaLink$setY(double y);
}
