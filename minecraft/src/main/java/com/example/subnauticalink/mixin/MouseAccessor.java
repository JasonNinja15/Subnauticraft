package com.example.subnauticalink.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

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

	/**
	 * What Minecraft does when a button of the real mouse is pressed (action 1) or let go
	 * (action 0) over its window. Used to click on Minecraft's menus from Subnautica, so the
	 * click is heard by everything that listens for real ones.
	 */
	@Invoker("onMouseButton")
	void subnauticaLink$onMouseButton(long window, int button, int action, int mods);
}
