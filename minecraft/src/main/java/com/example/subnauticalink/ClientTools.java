package com.example.subnauticalink;

import java.util.Set;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.util.Hand;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

/**
 * What holding and using one of Subnautica's tools does on the Minecraft side. Client only.
 *
 * <p>The tool itself works in Subnautica (see {@link ToolTokens}). Three things are added here,
 * because Minecraft is the one drawing the hand and moving the player:
 * <ul>
 *   <li><b>The hand moves when a tool is used.</b> A tool used in one go (the knife, a flare)
 *       swings, as any Minecraft item does. A tool that is held on its work (the laser cutter,
 *       the repair tool, the scanner...) instead has the arm brought in toward the middle of
 *       the screen for as long as the button is held, as Subnautica does. See
 *       {@code HeldItemRendererMixin} for the drawing.</li>
 *   <li><b>The air bladder lifts.</b> While Subnautica says the bladder is inflated and
 *       lifting, the player rises, faster and faster up to a limit.</li>
 *   <li><b>The Seaglide holds its depth.</b> With it in hand in the water, the player doesn't
 *       sink; holding the sneak key still goes down.</li>
 * </ul>
 */
public final class ClientTools {
	/** Tools that are held on their work: the arm reaches in for these. The rest swing once. */
	private static final Set<String> HELD_ON_WORK = Set.of("LaserCutter", "Welder", "Builder", "Scanner", "FireExtinguisher", "StasisRifle", "PropulsionCannon", "RepulsionCannon");

	/** The air bladder's lift: how much speed it gains each tick, and the most it reaches, in blocks a tick. */
	private static final double LIFT_GAIN = 0.015;
	private static final double MOST_LIFT = 0.35;

	/** How far the arm is reached in, from 0 (at rest) to 1, this tick and last. */
	private static float reach;
	private static float lastReach;

	/**
	 * What Subnautica says the tool in its hand is doing ("TOOLSTATE light lift"): its light is
	 * switched on and has charge, and it is lifting the player (an inflated air bladder).
	 */
	public static volatile boolean lightOn;
	public static volatile boolean lifting;

	private static boolean wasUsing;
	private static boolean rising;
	private static double lift;
	private static boolean holdingDepth;

	private ClientTools() {
	}

	/**
	 * Called every client tick while linked.
	 *
	 * @param tool the Subnautica tool whose token is in the main hand, or null
	 */
	public static void tick(MinecraftClient client, String tool) {
		ClientPlayerEntity player = client.player;

		if (player == null) {
			return;
		}

		boolean using = tool != null && (RemoteControls.attack || RemoteControls.use) && client.currentScreen == null;
		boolean heldOnWork = using && HELD_ON_WORK.contains(tool);

		// The arm eases in and back out, not jumping.
		lastReach = reach;
		reach += ((heldOnWork ? 1.0F : 0.0F) - reach) * 0.35F;

		if (using && !wasUsing && !heldOnWork) {
			player.swingHand(Hand.MAIN_HAND);
		}

		boolean inWater = WaterState.isTouching(player);

		// The air bladder. Subnautica decides when it is lifting (inflated, and in the water);
		// the lifting itself is done here, on Minecraft's player, because Minecraft is what
		// moves the player. It gathers speed up to a limit, and starts again from nothing after
		// bumping into something above.
		rising = "AirBladder".equals(tool) && lifting && inWater;

		if (!rising || (player.verticalCollision && !player.isOnGround())) {
			lift = 0.0;
		}

		if (rising) {
			lift = Math.min(MOST_LIFT, lift + LIFT_GAIN);
			Vec3d speed = player.getVelocity();
			player.setVelocity(speed.x, Math.max(speed.y, lift), speed.z);
		}

		// The Seaglide: no sinking in the water, unless sneak is held to go down.
		boolean holdDepth = "Seaglide".equals(tool) && inWater && !RemoteControls.sneak;

		if (holdDepth != holdingDepth) {
			holdingDepth = holdDepth;
			player.setNoGravity(holdDepth);
		}

		wasUsing = using;
	}

	/** "light lift": what the tool in Subnautica's hand is doing, each 1 or 0. */
	public static void onToolState(String text) {
		String[] parts = text.trim().split(" ");
		lightOn = parts.length > 0 && parts[0].equals("1");
		lifting = parts.length > 1 && parts[1].equals("1");
	}

	/** The link has ended: let go of everything this class was holding. */
	public static void release(ClientPlayerEntity player) {
		lightOn = false;
		lifting = false;
		reach = 0.0F;
		lastReach = 0.0F;
		rising = false;
		wasUsing = false;

		if (holdingDepth) {
			holdingDepth = false;

			if (player != null) {
				player.setNoGravity(false);
			}
		}
	}

	/** How far the arm is reached in at this instant between two ticks, from 0 to 1. */
	public static float reach(float tickDelta) {
		return MathHelper.lerp(tickDelta, lastReach, reach);
	}
}
