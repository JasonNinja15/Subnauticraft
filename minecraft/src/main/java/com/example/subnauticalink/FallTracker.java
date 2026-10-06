package com.example.subnauticalink;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.server.network.ServerPlayerEntity;

/**
 * Decides how far the linked player has really fallen.
 *
 * <p>Minecraft's own way is to add up every downward movement made while "not on the ground",
 * and turn the total into damage at the next "on the ground". That works when "on the ground"
 * is steady. Here it comes from Subnautica's collision answers, which can flicker for a tick
 * on block edges, so tiny dips were adding up into a big "fall" that never happened.
 *
 * <p>Instead, the player is always in one of three states:
 * <ul>
 *   <li>IN_WATER: no fall is building up.</li>
 *   <li>SUPPORTED: standing on something. No fall is building up.</li>
 *   <li>AIRBORNE: the highest point reached since leaving the water or the ground is
 *       remembered.</li>
 * </ul>
 * On landing, the fall is simply that highest point minus where the player landed. Wobbling
 * up and down cannot add up to anything, and coming out of the water starts from scratch.
 *
 * <p>This runs on the server each time the player's position arrives, just before Minecraft's
 * own fall handling (see {@code ServerPlayerEntityMixin}). It sets the fall distance Minecraft
 * is about to use; Minecraft still works out and applies the damage itself.
 */
public final class FallTracker {
	private enum State { IN_WATER, SUPPORTED, AIRBORNE }

	/** Falls shorter than this aren't worth a line in the log. Minecraft only hurts you beyond 3. */
	private static final double WORTH_LOGGING = 2.0;

	/** What is remembered for one player. */
	private static final class Track {
		State state = State.SUPPORTED;
		double highestPoint;
		double lastY;
	}

	private static final Map<UUID, Track> TRACKS = new ConcurrentHashMap<>();

	private FallTracker() {
	}

	/** The player was put somewhere new (linking, a hatch, respawning): nothing carries over. */
	public static void reset(ServerPlayerEntity player) {
		Track track = TRACKS.computeIfAbsent(player.getUuid(), id -> new Track());
		track.state = State.SUPPORTED;
		track.highestPoint = player.getY();
		player.fallDistance = 0.0F;
	}

	/** The player has left the game. */
	public static void forget(ServerPlayerEntity player) {
		TRACKS.remove(player.getUuid());
	}

	/**
	 * @param onGround what the player's own game says: is it standing on something?
	 */
	public static void update(ServerPlayerEntity player, boolean onGround) {
		Track track = TRACKS.computeIfAbsent(player.getUuid(), id -> new Track());
		double y = player.getY();
		// The things that break a fall in Minecraft break it here too: a ladder or vine,
		// Slow Falling or Levitation, flying, and water or lava someone has poured out. They
		// count as being in the water does: no fall is building up. (Without this, climbing
		// down a tall ladder ended as a fall from the top of it.)
		boolean held = player.isClimbing() || player.getAbilities().flying
				|| player.hasStatusEffect(StatusEffects.SLOW_FALLING) || player.hasStatusEffect(StatusEffects.LEVITATION)
				|| !player.getWorld().getFluidState(player.getBlockPos()).isEmpty();
		State now = WaterState.isTouching(player) || held ? State.IN_WATER : onGround ? State.SUPPORTED : State.AIRBORNE;

		switch (now) {
			case IN_WATER -> {
				player.fallDistance = 0.0F;
				track.highestPoint = y;
			}
			case SUPPORTED -> {
				// Only a landing counts: arriving on the ground from the air.
				double fallen = track.state == State.AIRBORNE ? Math.max(0.0, track.highestPoint - y) : 0.0;
				player.fallDistance = (float) fallen;
				track.highestPoint = y;

				// A real landing kicks up whatever is underfoot in Subnautica.
				if (fallen >= 1.0) {
					Sessions.broadcast(String.format(java.util.Locale.ROOT, "FX land %.3f %.3f %.3f 0 1 0", player.getX(), y, player.getZ()));
				}

				if (fallen >= WORTH_LOGGING) {
					SubnauticaLink.LOGGER.info("Fall: landed at y = {} after falling {} blocks", String.format("%.2f", y), String.format("%.2f", fallen));
				}
			}
			case AIRBORNE -> {
				// Just left the ground or the water: the starting height is where we were.
				// Then keep the highest point (a jump goes up before it comes down).
				track.highestPoint = Math.max(track.highestPoint, y);

				// Gliding on an elytra isn't falling. Minecraft's own rule: while the glide is
				// sinking gently (less than half a block a tick), the fall so far counts as one
				// block, however high it started. Only a steep dive builds up a real fall.
				if (player.isFallFlying() && y - track.lastY > -0.5) {
					track.highestPoint = y + 1.0;
				}

				// Minecraft adds this tick's drop to its own total straight after this; the
				// total is thrown away and replaced on landing, so start it from nothing.
				player.fallDistance = 0.0F;
			}
		}

		if (now != track.state && (now == State.IN_WATER || track.state == State.IN_WATER)) {
			SubnauticaLink.LOGGER.info("Fall: {} -> {} at y = {}", track.state, now, String.format("%.2f", y));
		}

		track.state = now;
		track.lastY = y;
	}
}
