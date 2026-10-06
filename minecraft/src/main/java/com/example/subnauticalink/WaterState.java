package com.example.subnauticalink;

import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.Vec3d;

/**
 * Water for the linked player.
 *
 * <p>The ocean void has no water blocks in it. Instead, the linked player is treated as being
 * in water whenever they are below sea level (y = 0, the same in both games), unless Subnautica
 * says they are somewhere dry: a base, the lifepod, an alien building. {@code EntityMixin}
 * feeds these answers into the handful of questions Minecraft asks about water ("is this
 * entity touching water?", "is its head under?"), and Minecraft's own swimming does the rest:
 * jump to rise, sneak to sink, sprint to swim.
 *
 * <p>Swim speed is scaled to Subnautica's. Subnautica reports how fast its player swims (with
 * fins and so on), and the distance Minecraft moves the player each tick in water is
 * multiplied to match.
 */
public final class WaterState {
	/** Sea level, the same in both games. */
	public static final double SEA_LEVEL = 0.0;

	/**
	 * For the few things that have to find real water to work at all (a boat, a fishing
	 * bobber): the ocean void has no water blocks, so they are told that every block below sea
	 * level there holds still water. Anything else is answered as Minecraft would answer it.
	 */
	public static net.minecraft.fluid.FluidState seaFor(net.minecraft.world.World world, net.minecraft.util.math.BlockPos pos, net.minecraft.fluid.FluidState real) {
		if (real.isEmpty() && pos.getY() < SEA_LEVEL && world.getRegistryKey() == MovementBridge.OCEAN_VOID) {
			return net.minecraft.fluid.Fluids.WATER.getStill(false);
		}

		return real;
	}

	/** Whether a block's place is under Subnautica's sea. (The block resting on the surface, from sea level up, is not.) */
	public static boolean underTheSea(net.minecraft.world.World world, net.minecraft.util.math.BlockPos pos) {
		return pos.getY() < SEA_LEVEL && world.getRegistryKey() == MovementBridge.OCEAN_VOID;
	}

	// How fast Minecraft's own swimming moves a player, in metres per second, before scaling.
	/** Swimming along without sprinting. */
	private static final double VANILLA_SWIM = 1.96;
	/** Rising with jump held, or sinking with sneak held. */
	private static final double VANILLA_RISE = 3.6;
	/** Sprint-swimming along level. */
	private static final double VANILLA_SPRINT_SWIM = 3.92;

	/** Sprint-swimming is this many times Subnautica's own swim speed: a little faster. */
	private static final double SWIM_BOOST = 1.15;

	/** Plain swimming (without sprint) is this many times the sprint-swimming speed. */
	private static final double PLAIN_SWIM = 0.7;

	/** Walking along the seabed is this many times the swimming speed: slower than swimming. */
	private static final double SEABED_WALK = 0.65;

	/** The feet have to be this far below sea level to count as getting into the water... */
	private static final double ENTER_DEPTH = 0.05;
	/** ...and come up to within this far of it to count as getting out. */
	private static final double LEAVE_DEPTH = 0.01;

	// Whether the linked player currently counts as in the water. The game keeps two copies of
	// the player (one for drawing and controls, one for the rules), so each has its own.
	private static volatile boolean wetOnClient;

	/** True when Subnautica says the player is somewhere with air around them. */
	private static volatile boolean dry;

	/** Which of Subnautica's regions the player is in, as their own game was last told (see ClientLink). Null until it is. */
	public static volatile String clientBiome;

	/** Subnautica's current swim speed, in metres per second. */
	private static volatile double swimSpeed = 5.0;

	private WaterState() {
	}

	/** "DRY 1" or "DRY 0". */
	public static void onDryLine(String value) {
		dry = value.trim().equals("1");
	}

	/** "SWIMSPEED 5.0". */
	public static void onSwimSpeedLine(String value) {
		try {
			double speed = Double.parseDouble(value.trim());

			if (speed >= 1.0 && speed <= 20.0) {
				swimSpeed = speed;
			}
		} catch (NumberFormatException e) {
			// A garbled line; ignore it.
		}
	}

	/** True when Subnautica says the player is somewhere with air around them. */
	public static boolean isDry() {
		return dry;
	}

	public static void reset() {
		dry = false;
		clientBiome = null;
		swimSpeed = 5.0;
	}

	/**
	 * Any part of the entity is in the water.
	 *
	 * <p>The surface is deliberately a little soft. Getting in takes the feet going clearly
	 * below sea level; getting out only takes them reaching it. Without that, someone standing
	 * on a platform right at sea level would count as in and out of the water from one tick to
	 * the next, as their height wobbles by a hair.
	 *
	 * <p>The game keeps a copy of each player on the server and one in every player's own
	 * game. On the server, each player has their own "dry" and "in the water" (kept in their
	 * session). In a player's game, those are known for that player's own character; any
	 * other player seen there simply counts as in the water when below sea level.
	 */
	public static boolean isTouching(Entity entity) {
		if (!(entity instanceof PlayerEntity player) || entity.getWorld().getRegistryKey() != MovementBridge.OCEAN_VOID) {
			return false;
		}

		double feet = entity.getY();

		if (entity.getWorld().isClient) {
			if (!RemoteControls.isActive()) {
				return false;
			}

			if (!player.isMainPlayer()) {
				return feet < SEA_LEVEL - ENTER_DEPTH;
			}

			wetOnClient = !dry && nowWet(wetOnClient, feet);
			return wetOnClient;
		}

		LinkSession session = Sessions.of(entity);

		if (session == null || !session.isActive()) {
			return false;
		}

		session.wet = !session.dry && nowWet(session.wet, feet);
		return session.wet;
	}

	private static boolean nowWet(boolean wet, double feet) {
		if (wet && feet >= SEA_LEVEL - LEAVE_DEPTH) {
			return false;
		}

		if (!wet && feet < SEA_LEVEL - ENTER_DEPTH) {
			return true;
		}

		return wet;
	}

	/** The entity's eyes are under the water. */
	public static boolean isSubmerged(Entity entity) {
		return isTouching(entity) && entity.getEyeY() < SEA_LEVEL;
	}

	/** How far up the entity the water comes, in blocks. */
	public static double depthOn(Entity entity) {
		return Math.max(0.0, Math.min(SEA_LEVEL - entity.getY(), entity.getHeight()));
	}

	/**
	 * Stretches one tick's movement so swimming is as fast as it is in Subnautica. Only the
	 * distance covered changes; Minecraft's own speeds, drag and controls are left alone, so
	 * swimming still handles like Minecraft.
	 */
	public static Vec3d scaleMovement(Entity entity, Vec3d movement) {
		if (!entity.getWorld().isClient || !isTouching(entity)) {
			return movement;
		}

		double speed = swimSpeed * SWIM_BOOST;

		if (entity.isOnGround()) {
			speed *= SEABED_WALK;
		}

		if (entity.isSwimming()) {
			double factor = speed / VANILLA_SPRINT_SWIM;
			return movement.multiply(factor);
		}

		speed *= PLAIN_SWIM;

		// Up and down are scaled separately: Minecraft rises and sinks faster than it swims
		// along, and Subnautica doesn't.
		double sideways = speed / VANILLA_SWIM;
		double vertical = speed / VANILLA_RISE;
		return new Vec3d(movement.x * sideways, movement.y * vertical, movement.z * sideways);
	}
}
