package com.example.subnauticalink.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.example.subnauticalink.ClientLink;
import com.example.subnauticalink.OverlayShare;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.hud.InGameHud;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.entity.Entity;
import net.minecraft.util.Identifier;

/**
 * Two changes to Minecraft's HUD: the vignette is left out while the HUD is being shared with
 * Subnautica, and in one of Subnautica's vehicles the experience bar shows the vehicle's power.
 */
@Mixin(InGameHud.class)
public abstract class InGameHudMixin {
	/** The experience bar's own two pictures: the empty bar, and the filling. */
	@Unique
	private static final Identifier SUBNAUTICA_LINK_BAR = Identifier.ofVanilla("hud/experience_bar_background");
	@Unique
	private static final Identifier SUBNAUTICA_LINK_FILL = Identifier.ofVanilla("hud/experience_bar_progress");

	/** A horse's jump bar: the same shape, in blue. Used for the PRAWN suit's jump jets. */
	@Unique
	private static final Identifier SUBNAUTICA_LINK_BOOST_BAR = Identifier.ofVanilla("hud/jump_bar_background");
	@Unique
	private static final Identifier SUBNAUTICA_LINK_BOOST_FILL = Identifier.ofVanilla("hud/jump_bar_progress");

	/**
	 * The vignette is the darkening Minecraft adds around the edges of the screen. It is drawn
	 * in a way that would turn the whole see-through picture solid black.
	 */
	@Inject(method = "renderVignetteOverlay", at = @At("HEAD"), cancellable = true)
	private void subnauticaLink$noVignette(DrawContext context, Entity entity, CallbackInfo ci) {
		if (OverlayShare.isActive()) {
			ci.cancel();
		}
	}

	/**
	 * In one of Subnautica's vehicles, the experience bar is drawn filled to the vehicle's
	 * power instead (much as a horse's jump takes the bar over), in exactly the same place.
	 */
	@Inject(method = "renderExperienceBar", at = @At("HEAD"), cancellable = true)
	private void subnauticaLink$chargeBar(DrawContext context, int x, CallbackInfo ci) {
		float charge = ClientLink.vehicleCharge();

		if (charge < 0.0F) {
			return;
		}

		// While the PRAWN suit's jump jets are draining or refilling, the bar shows their
		// reserve instead, in the blue of a horse's jump bar.
		float boost = ClientLink.vehicleBoost();
		boolean boosting = boost >= 0.0F;
		int y = context.getScaledWindowHeight() - 32 + 3;
		int filled = (int) ((boosting ? boost : charge) * 183.0F);
		context.drawGuiTexture(boosting ? SUBNAUTICA_LINK_BOOST_BAR : SUBNAUTICA_LINK_BAR, x, y, 182, 5);

		if (filled > 0) {
			context.drawGuiTexture(boosting ? SUBNAUTICA_LINK_BOOST_FILL : SUBNAUTICA_LINK_FILL, 182, 5, 0, 0, x, y, Math.min(filled, 182), 5);
		}

		ci.cancel();
	}

	/** And where the player's level is normally written, it says "Charge". */
	@Inject(method = "renderExperienceLevel", at = @At("HEAD"), cancellable = true)
	private void subnauticaLink$chargeWord(DrawContext context, RenderTickCounter tickCounter, CallbackInfo ci) {
		MinecraftClient client = MinecraftClient.getInstance();

		if (ClientLink.vehicleCharge() < 0.0F || client.interactionManager == null) {
			return;
		}

		// Only where there is a bar to label (not in Creative mode).
		if (client.interactionManager.hasExperienceBar()) {
			TextRenderer text = client.textRenderer;
			boolean boosting = ClientLink.vehicleBoost() >= 0.0F;
			String word = boosting ? "Boost" : "Charge";
			int x = (context.getScaledWindowWidth() - text.getWidth(word)) / 2;
			int y = context.getScaledWindowHeight() - 31 - 4;

			// A black outline, then the word in the experience number's green: as Minecraft writes the level.
			context.drawText(text, word, x + 1, y, 0, false);
			context.drawText(text, word, x - 1, y, 0, false);
			context.drawText(text, word, x, y + 1, 0, false);
			context.drawText(text, word, x, y - 1, 0, false);
			context.drawText(text, word, x, y, boosting ? 0x8C7BFF : 8453920, false);
		}

		ci.cancel();
	}
}
