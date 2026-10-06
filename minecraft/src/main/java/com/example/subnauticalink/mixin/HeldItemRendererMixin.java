package com.example.subnauticalink.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.example.subnauticalink.ClientTools;

import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.item.HeldItemRenderer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Arm;
import net.minecraft.util.Hand;

/**
 * While one of Subnautica's tools is being held on its work (see {@link ClientTools}), the
 * hand holding its token is drawn brought in toward the middle of the screen.
 *
 * <p>Minecraft draws each hand with this method. The first part below runs just before it and
 * shifts where the drawing will go; the second runs just after and puts things back, so the
 * other hand isn't shifted too.
 */
@Mixin(HeldItemRenderer.class)
public abstract class HeldItemRendererMixin {
	@Inject(method = "renderFirstPersonItem", at = @At("HEAD"))
	private void subnauticaLink$reachIn(AbstractClientPlayerEntity player, float tickDelta, float pitch, Hand hand, float swingProgress, ItemStack item,
			float equipProgress, MatrixStack matrices, VertexConsumerProvider vertexConsumers, int light, CallbackInfo ci) {
		matrices.push();
		float reach = hand == Hand.MAIN_HAND ? ClientTools.reach(tickDelta) : 0.0F;

		if (reach > 0.001F) {
			// Toward the middle (which side that is depends on which arm is the main one), up a little, and forward.
			float side = player.getMainArm() == Arm.RIGHT ? -1.0F : 1.0F;
			matrices.translate(side * 0.3F * reach, 0.12F * reach, -0.15F * reach);
		}
	}

	@Inject(method = "renderFirstPersonItem", at = @At("RETURN"))
	private void subnauticaLink$putBack(AbstractClientPlayerEntity player, float tickDelta, float pitch, Hand hand, float swingProgress, ItemStack item,
			float equipProgress, MatrixStack matrices, VertexConsumerProvider vertexConsumers, int light, CallbackInfo ci) {
		matrices.pop();
	}
}
