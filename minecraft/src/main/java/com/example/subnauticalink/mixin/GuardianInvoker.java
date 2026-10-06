package com.example.subnauticalink.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

import net.minecraft.entity.mob.GuardianEntity;

/**
 * Lets this mod point a guardian's beam at something (or, with 0, at nothing). A guardian's
 * own thinking normally does that, and it is switched off for the ones this mod steers.
 */
@Mixin(GuardianEntity.class)
public interface GuardianInvoker {
	@Invoker("setBeamTarget")
	void subnauticaLink$setBeamTarget(int entityId);
}
