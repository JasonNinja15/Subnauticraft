package com.example.subnauticalink;

import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.example.subnauticalink.mixin.PersistentProjectileAccessor;
import com.example.subnauticalink.mixin.ProjectileInvoker;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.attribute.EntityAttributeInstance;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.passive.PigEntity;
import net.minecraft.entity.projectile.FishingBobberEntity;
import net.minecraft.entity.projectile.PersistentProjectileEntity;
import net.minecraft.entity.projectile.ProjectileEntity;
import net.minecraft.entity.projectile.thrown.EnderPearlEntity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

/**
 * Projectiles in the ocean void: arrows, snowballs, tridents, thrown knives, grappling hooks
 * and anything else built on Minecraft's projectile classes. This is the server's part.
 *
 * <p>A projectile is an ordinary Minecraft one. It flies by Minecraft's own code and hits
 * Minecraft's blocks by itself. What it can't see is Subnautica's scenery and creatures. Those
 * are found by a player's own game, which asks its own Subnautica what lies ahead of each
 * projectile and reports any hit to the server (see ClientPhysics): usually the game of
 * whoever threw it.
 *
 * <p>A report says "this projectile will hit here". It is made a little ahead of time, to
 * allow for the trip to the server, so the server keeps it until the projectile actually gets
 * there. Then:
 *
 * <ul>
 *   <li>for scenery, the projectile is put at that spot and told "you hit a block here". Its
 *       own code does the rest: an arrow or knife sticks in, a snowball bursts, a grappling
 *       hook anchors;</li>
 *   <li>for a creature, it is told "you hit this mob", and the mob it is handed is a stand-in
 *       that is never put in the world. Whatever damage the projectile's own code deals to
 *       the stand-in is passed to the Subnautica of the player who reported the hit ("HURT x y
 *       z damage"), five for one like every other damage that crosses over.</li>
 * </ul>
 */
public final class Projectiles {
	/** One point of Minecraft damage is this much in Subnautica (100 health there, 20 here). */
	private static final double SUBNAUTICA_DAMAGE_PER_POINT = 5.0;

	/** A report is only believed from a player this close to the projectile, in blocks. */
	private static final double REPORT_WITHIN = 96.0;

	/** A report not acted on after this many ticks is thrown away. */
	private static final int REPORT_LASTS = 12;

	/** A hit some player's game has reported, waiting for the projectile to get there. */
	private static final class Report {
		Vec3d point;
		Vec3d normal;
		/** 0 scenery, 1 a creature, 2 a creature too big for a grappling hook to drag. */
		int kind;
		ServerPlayerEntity reporter;
		int age;
	}

	private static final Map<Integer, Report> REPORTS = new ConcurrentHashMap<>();

	/**
	 * Arrows (and knives, hooks...) stuck in Subnautica's scenery, and where, as the server
	 * knows it. A stuck arrow checks there is still a block around it and drops if not; there
	 * is no Minecraft block there, so that check looks here (see PersistentProjectileMixin).
	 */
	private static final Map<Integer, Vec3d> STUCK = new ConcurrentHashMap<>();

	/**
	 * The same list as each player's game knows it. The server tells every player's game when
	 * a projectile sticks or comes loose (see {@link #onNote}), because a game's own copy of
	 * the projectile can't see Subnautica's scenery and would fly on through it.
	 */
	private static final Map<Integer, Vec3d> STUCK_HERE = new ConcurrentHashMap<>();

	/** The stand-in mob handed to a projectile that hit a Subnautica creature. */
	private static LivingEntity proxy;
	private static float proxyDamage;

	private Projectiles() {
	}

	/** The server is stopping: forget everything. */
	public static void reset() {
		for (int id : STUCK.keySet()) {
			Sessions.note("@UNSTUCK " + id);
		}

		STUCK.clear();
		REPORTS.clear();

		// (The stand-in mob belongs to the world that is closing.)
		proxy = null;
	}

	/** Where a projectile is stuck in Subnautica's scenery, or null if it isn't. */
	public static Vec3d stuckAt(int entityId) {
		return STUCK.get(entityId);
	}

	/** Where a projectile is stuck, as this player's own game has been told. */
	public static Vec3d stuckHere(int entityId) {
		return STUCK_HERE.get(entityId);
	}

	/**
	 * A note from the server to a player's game (not for Subnautica): "@STUCK id x y z" when a
	 * projectile sticks into Subnautica's scenery, "@UNSTUCK id" when it comes loose.
	 */
	public static void onNote(String note) {
		// (Not about projectiles, but notes all arrive here: what light another player's held thing gives.)
		if (note.startsWith("@LIGHT ")) {
			HeldLights.onNote(note.substring("@LIGHT ".length()));
			return;
		}

		String[] parts = note.split(" ");

		try {
			if (parts[0].equals("@STUCK") && parts.length == 5) {
				STUCK_HERE.put(Integer.parseInt(parts[1]),
						new Vec3d(Double.parseDouble(parts[2]), Double.parseDouble(parts[3]), Double.parseDouble(parts[4])));
			} else if (parts[0].equals("@UNSTUCK") && parts.length == 2) {
				STUCK_HERE.remove(Integer.parseInt(parts[1]));
			}
		} catch (NumberFormatException e) {
			// A garbled note; ignore it.
		}
	}

	/** This player's game left the world: forget what the server said. */
	public static void forgetNotes() {
		STUCK_HERE.clear();
	}

	public static boolean isProxy(Entity entity) {
		return entity != null && entity == proxy;
	}

	/** Called when the stand-in mob is damaged: remember how much, to pass on. */
	public static void onProxyDamage(float amount) {
		proxyDamage += amount;
	}

	/** Whether a projectile is in flight, as opposed to stuck in something or being pulled back through everything. */
	public static boolean isFlying(ProjectileEntity entity) {
		if (!entity.isAlive() || entity instanceof FishingBobberEntity || entity.getVelocity().lengthSquared() < 1.0E-6) {
			return false;
		}

		if (entity instanceof PersistentProjectileEntity arrow) {
			return !arrow.isNoClip() && !((PersistentProjectileAccessor) arrow).subnauticaLink$inGround();
		}

		return true;
	}

	/**
	 * "id x y z nx ny nz creature": a player's game says this projectile is about to hit
	 * Subnautica's scenery (or a creature, if the last number is 1, or 2 for a big one) at this point.
	 */
	public static void onReport(ServerPlayerEntity reporter, String text) {
		String[] parts = text.trim().split(" ");

		if (parts.length != 8 || reporter.getServerWorld().getRegistryKey() != MovementBridge.OCEAN_VOID) {
			return;
		}

		try {
			int id = Integer.parseInt(parts[0]);
			Entity entity = reporter.getServerWorld().getEntityById(id);

			if (!(entity instanceof ProjectileEntity projectile) || !isFlying(projectile)
					|| entity.squaredDistanceTo(reporter) > REPORT_WITHIN * REPORT_WITHIN) {
				return;
			}

			Report report = REPORTS.get(id);

			// The thrower's own game is the one to believe; anyone else's is a stand-by.
			if (report != null && report.reporter != reporter && projectile.getOwner() == report.reporter) {
				return;
			}

			if (report == null) {
				report = new Report();
				REPORTS.put(id, report);
			}

			report.point = new Vec3d(Double.parseDouble(parts[1]), Double.parseDouble(parts[2]), Double.parseDouble(parts[3]));
			report.normal = new Vec3d(Double.parseDouble(parts[4]), Double.parseDouble(parts[5]), Double.parseDouble(parts[6]));
			report.kind = Integer.parseInt(parts[7]);
			report.reporter = reporter;
			report.age = 0;
		} catch (NumberFormatException e) {
			// A garbled report; ignore it.
		}
	}

	/** Called at the start of every server tick, before anything moves. */
	/**
	 * Called at the end of every server tick. A projectile that flies back to its thrower (a
	 * roped knife, a grappling hook, a trident with Loyalty) is normally collected when it
	 * touches them. In the ocean void that touch wasn't being noticed, and it circled the
	 * thrower's head instead. So: any such projectile within reach of its own thrower is handed
	 * to its normal "touched a player" code, which collects it.
	 */
	public static void collectReturning(MinecraftServer server) {
		ServerWorld world = server.getWorld(MovementBridge.OCEAN_VOID);

		if (world == null) {
			return;
		}

		for (ServerPlayerEntity player : world.getPlayers()) {
			if (!player.isAlive() || player.isSpectator() || !Sessions.isActive(player)) {
				continue;
			}

			for (PersistentProjectileEntity projectile : world.getEntitiesByClass(PersistentProjectileEntity.class,
					player.getBoundingBox().expand(1.5), found -> found.isNoClip() && found.getOwner() == player)) {
				projectile.onPlayerCollision(player);
			}
		}
	}

	public static void beforeTick(MinecraftServer server) {
		ServerWorld world = server.getWorld(MovementBridge.OCEAN_VOID);

		if (world == null) {
			return;
		}

		// Forget projectiles that are no longer stuck: collected, pulled back, or moved.
		if (!STUCK.isEmpty()) {
			STUCK.entrySet().removeIf(entry -> {
				Entity entity = world.getEntityById(entry.getKey());

				boolean loose = !(entity instanceof PersistentProjectileEntity arrow)
						|| !arrow.isAlive()
						|| arrow.isNoClip()
						|| !((PersistentProjectileAccessor) arrow).subnauticaLink$inGround()
						|| arrow.getPos().squaredDistanceTo(entry.getValue()) > 1.0E-4;

				if (loose) {
					Sessions.note("@UNSTUCK " + entry.getKey());
				}

				return loose;
			});
		}

		Iterator<Map.Entry<Integer, Report>> reports = REPORTS.entrySet().iterator();

		while (reports.hasNext()) {
			Map.Entry<Integer, Report> entry = reports.next();
			Report report = entry.getValue();
			Entity entity = world.getEntityById(entry.getKey());

			if (!(entity instanceof ProjectileEntity projectile) || !isFlying(projectile) || ++report.age > REPORT_LASTS) {
				reports.remove();
				continue;
			}

			// How far ahead of the projectile the reported point is, along the way it is
			// going. It is acted on in the tick the projectile would reach it (or if it has
			// already gone past, which happens when the report took a while to arrive).
			Vec3d step = projectile.getVelocity();
			double speed = step.length();
			Vec3d direction = step.multiply(1.0 / speed);
			double ahead = report.point.subtract(projectile.getPos()).dotProduct(direction);

			if (ahead > speed + 0.05) {
				continue;
			}

			reports.remove();

			try {
				hit(server, world, projectile, report);
			} catch (RuntimeException e) {
				SubnauticaLink.LOGGER.warn("A projectile's hit on Subnautica went wrong", e);
			}
		}
	}

	/** Tells one projectile it has hit something of Subnautica's. */
	private static void hit(MinecraftServer server, ServerWorld world, ProjectileEntity entity, Report report) {
		Vec3d from = entity.getPos();
		Vec3d point = report.point;
		Vec3d direction = entity.getVelocity().normalize();

		// A Minecraft block in the way wins: Minecraft's own code handles that hit properly.
		// (Subnautica has a cube for every Minecraft block, so it reports those too.)
		if (from.squaredDistanceTo(point) > 1.0E-6) {
			BlockHitResult block = world.raycast(new RaycastContext(from, point.add(direction.multiply(0.06)),
					RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, entity));

			if (block.getType() != HitResult.Type.MISS) {
				return;
			}
		}

		// Put the projectile just short of what it hit, so whatever it does next (stick in,
		// burst, drop as an item) happens there and not where it was a tick ago.
		Vec3d shortOf = point.subtract(direction.multiply(0.05));
		entity.setPosition(shortOf.x, shortOf.y, shortOf.z);

		ProjectileInvoker projectile = (ProjectileInvoker) entity;

		// A grappling hook drags a creature in if it is small enough (see HookDrag). One too
		// big holds the hook as scenery does, further down, and the player is pulled in.
		boolean hook = HookDrag.isHook(entity);

		if (report.kind == 1 && hook) {
			HookDrag.latch(server, world, entity, point, report.reporter);
			return;
		}

		if (report.kind != 0 && !hook) {
			LivingEntity standIn = standIn(world);

			if (standIn == null) {
				return;
			}

			standIn.setPosition(point.x, point.y, point.z);
			standIn.setHealth(standIn.getMaxHealth());
			standIn.timeUntilRegen = 0;
			standIn.extinguish();
			proxyDamage = 0.0F;

			projectile.subnauticaLink$onCollision(new EntityHitResult(standIn, point));

			// The Subnautica that saw the creature is the one that hurts it.
			if (proxyDamage > 0.0F && report.reporter.isAlive()) {
				Sessions.get(report.reporter).send(server, report.reporter,
						String.format(Locale.ROOT, "HURT %.3f %.3f %.3f %.2f", point.x, point.y, point.z, proxyDamage * SUBNAUTICA_DAMAGE_PER_POINT));
			}

			return;
		}

		// Something for everyone's Subnautica to show at the spot: chips or dust off the
		// surface for an arrow or knife, a swirl for an ender pearl, a burst for anything else.
		Vec3d normal = report.normal;
		String effect = entity instanceof PersistentProjectileEntity ? "impact" : entity instanceof EnderPearlEntity ? "warp" : "poof";
		Sessions.broadcast(String.format(Locale.ROOT, "FX %s %.3f %.3f %.3f %.3f %.3f %.3f", effect, point.x, point.y, point.z, normal.x, normal.y, normal.z));

		// Scenery. There is no Minecraft block there, so the "block" named is the empty space
		// the projectile itself is in.
		Direction side = Direction.getFacing(normal.x, normal.y, normal.z);

		// An ender pearl takes its thrower to wherever it is when it lands. Left a hair's
		// breadth short of a wall, that would put half of them inside it. So the pearl is
		// first moved to where a player fits: standing on a floor, half a block out from a
		// wall or slope, and a body's height below a ceiling.
		if (entity instanceof EnderPearlEntity) {
			Vec3d unit = normal.lengthSquared() > 1.0E-6 ? normal.normalize() : new Vec3d(0.0, 1.0, 0.0);
			Vec3d stand = unit.y > 0.7 ? point.add(0.0, 0.05, 0.0)
					: unit.y < -0.7 ? point.add(0.0, -1.9, 0.0)
					: point.add(unit.multiply(0.5));
			entity.setPosition(stand.x, stand.y, stand.z);
		}

		projectile.subnauticaLink$onCollision(new BlockHitResult(point, side, BlockPos.ofFloored(shortOf), false));

		if (entity.isAlive() && entity instanceof PersistentProjectileEntity arrow
				&& ((PersistentProjectileAccessor) arrow).subnauticaLink$inGround()) {
			STUCK.put(entity.getId(), entity.getPos());
			Sessions.note(String.format(Locale.ROOT, "@STUCK %d %.4f %.4f %.4f", entity.getId(), entity.getX(), entity.getY(), entity.getZ()));
		}
	}

	/**
	 * The stand-in mob: a pig that is never added to the world, with so much health that no
	 * hit can kill it. Made once per world.
	 */
	private static LivingEntity standIn(ServerWorld world) {
		if (proxy == null || proxy.getWorld() != world) {
			PigEntity pig = EntityType.PIG.create(world);

			if (pig == null) {
				return null;
			}

			pig.setSilent(true);
			pig.setInvisible(true);
			pig.setNoGravity(true);
			pig.setAiDisabled(true);

			EntityAttributeInstance health = pig.getAttributeInstance(EntityAttributes.GENERIC_MAX_HEALTH);

			if (health != null) {
				health.setBaseValue(1024.0);
			}

			proxy = pig;
		}

		return proxy;
	}
}
