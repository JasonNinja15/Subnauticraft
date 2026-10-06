package com.example.subnauticalink.mixin;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.model.ModelPart;
import net.minecraft.client.render.entity.model.BipedEntityModel;
import net.minecraft.entity.LivingEntity;
import net.minecraft.util.Arm;
import net.minecraft.util.Hand;
import net.minecraft.util.UseAction;
import net.minecraft.util.math.MathHelper;

/**
 * Eating and drinking, seen from outside.
 *
 * <p>Minecraft only shows food being eaten through the eater's own eyes. Seen from outside
 * (by another player, or by yourself in the view from behind) the arm just hangs there. This
 * lifts the hand holding the food or drink to the mouth and bobs it while it is being used.
 * Subnautica shows each player exactly as Minecraft draws them (see ClientAvatars), so this
 * is what everyone sees there too.
 */
@Mixin(BipedEntityModel.class)
public abstract class BipedEntityModelMixin {
	@Shadow
	@Final
	public ModelPart rightArm;

	@Shadow
	@Final
	public ModelPart leftArm;

	@Inject(method = "setAngles(Lnet/minecraft/entity/LivingEntity;FFFFF)V", at = @At("TAIL"))
	private void subnauticaLink$handToMouth(LivingEntity entity, float limbAngle, float limbDistance, float animationProgress, float headYaw, float headPitch, CallbackInfo ci) {
		if (!entity.isUsingItem()) {
			return;
		}

		UseAction action = entity.getActiveItem().getUseAction();

		if (action != UseAction.EAT && action != UseAction.DRINK) {
			return;
		}

		// Which arm: the hand in use, on whichever side this player's main hand is.
		boolean right = (entity.getActiveHand() == Hand.MAIN_HAND) == (entity.getMainArm() == Arm.RIGHT);
		ModelPart arm = right ? this.rightArm : this.leftArm;

		// Up and in towards the face, bobbing about five times a second.
		arm.pitch = -1.35F + MathHelper.sin(animationProgress * 1.6F) * 0.12F;
		arm.yaw = right ? -0.5F : 0.5F;
		arm.roll = 0.0F;
	}
}
