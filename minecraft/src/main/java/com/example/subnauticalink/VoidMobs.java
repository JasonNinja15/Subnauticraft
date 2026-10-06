package com.example.subnauticalink;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;

import com.example.subnauticalink.mixin.GuardianInvoker;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.mob.CreeperEntity;
import net.minecraft.entity.mob.DrownedEntity;
import net.minecraft.entity.mob.EndermanEntity;
import net.minecraft.entity.mob.GhastEntity;
import net.minecraft.entity.mob.GuardianEntity;
import net.minecraft.entity.projectile.FireballEntity;
import net.minecraft.entity.projectile.TridentEntity;
import net.minecraft.world.WorldEvents;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.Hand;
import net.minecraft.util.TypeFilter;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.Difficulty;

/**
 * Minecraft's mobs in Subnautica's world: the Drowned in the sea, and creepers on dry land.
 *
 * <p>A Drowned here is a real Minecraft Drowned, so hitting it, being hit by it, its sounds,
 * its death and what it drops are all Minecraft's own. Three things are different, because
 * the ocean void has no blocks for it to stand on or find its way around:
 * <ul>
 *   <li><b>Its own thinking is switched off</b>, and this class steers it instead: it turns
 *       to the nearest player and swims straight at them, and strikes when it is in reach.</li>
 *   <li><b>It moves through Subnautica's scenery.</b> Each tick this class works out where it
 *       wants to go and asks Subnautica how far it gets, with the same "SWEEP" question that
 *       dropped items use (see {@link ItemSync}). So it slides along the seabed and around
 *       rocks, and can't come through a wall.</li>
 *   <li><b>They appear as players travel</b>: about one for every 200 blocks a player covers
 *       underwater, a little way off and out of their line of sight, never more than a few at
 *       once, and they are cleared away when everyone has left them far behind.</li>
 * </ul>
 * They are drawn in Subnautica the same way players are (see {@code ClientAvatars}).
 *
 * <p><b>Creepers</b> are handled the same way, on Subnautica's dry land (its islands).
 * Minecraft can't see that land, so it asks: every few seconds it picks a spot near a player
 * and has Subnautica look straight down there ("PROBE id x z"). If Subnautica finds open
 * terrain above the sea with nothing built on it ("PROBED id y"), and Minecraft has no block
 * there either, a creeper appears on it. A creeper walks at its prey, starts its fuse within
 * three blocks (flashing and swelling as it does in Minecraft), and explodes with Minecraft's
 * own explosion, which is shown in Subnautica the way TNT's is. Killed first, it drops four
 * gunpowder.
 *
 * <p><b>Endermen</b> come to the islands the same way (see further down for how they behave).
 */
public final class VoidMobs {
	/** Marks the mobs this class made, so it only ever steers and removes its own. */
	public static final String TAG = "subnautica_link_mob";

	/** One appears for about every this many blocks a player travels underwater. */
	private static final double BLOCKS_PER_MOB = 200.0;

	/** No more than this many Drowned near one player, or in all. */
	private static final int MOST_NEAR_PLAYER = 2;
	private static final int MOST_AT_ONCE = 8;

	/** The same for creepers. */
	private static final int MOST_CREEPERS_NEAR_PLAYER = 2;
	private static final int MOST_CREEPERS_AT_ONCE = 6;

	/** Ticks between looks for somewhere to put a creeper near each player, and the wait after one appears. */
	private static final int PROBE_EVERY = 100;
	private static final int PROBE_AFTER_ONE_APPEARS = 400;

	/** A creeper starts its fuse this close to its prey (in blocks), and gives up beyond the second. */
	private static final double FUSE_WITHIN = 3.0;
	private static final double FUSE_STOPS_BEYOND = 7.0;

	/** It notices players within this many blocks, and is removed when none is within the second. */
	private static final double NOTICES_WITHIN = 28.0;
	private static final double REMOVED_BEYOND = 96.0;

	/** Subnautica is only asked about mobs this close to the player asked; further off, its scenery may not be loaded. */
	private static final double MOVED_WITHIN = 40.0;

	/** Blocks a tick: swimming, and walking on dry land. */
	private static final double SWIM_SPEED = 0.14;
	private static final double WALK_SPEED = 0.1;

	/** Ticks between its strikes. */
	private static final int STRIKE_EVERY = 20;

	/** A Drowned carrying a trident throws it at prey between these distances (in blocks), this often (in ticks). */
	private static final double TRIDENT_FROM = 4.0;
	private static final double TRIDENT_WITHIN = 20.0;
	private static final int TRIDENT_EVERY = 40;

	/** Mob questions use numbers from here up, so their answers can be told from items' and the player's. */
	private static final int FIRST_QUESTION = 2_000_000;
	private static final int PATIENCE_TICKS = 10;

	// ---- Guardians ----
	//
	// Four hundred metres down and deeper, no Drowned come. Guardians do instead, a quarter as
	// often (one chance for every 800 blocks travelled, not 200) and then only one time in
	// four. A guardian swims up to about nine blocks off, fixes its beam on its prey, and after
	// three seconds of that the beam hurts, as in Minecraft.

	private static final double DEEP_BELOW = 400.0;
	private static final int GUARDIAN_CHANCE = 25;
	private static final double GUARDIAN_BEAM_WITHIN = 15.0;
	private static final int GUARDIAN_CHARGE = 60;
	private static final int GUARDIAN_REST = 60;
	private static final float GUARDIAN_DAMAGE = 6.0F;

	// ---- Endermen ----
	//
	// On the islands, like creepers, but found a little further off (1.3 times as far) and
	// looked for less often (0.6 times as often). An enderman stands and watches until someone
	// looks it in the face or hits it; then it comes for them, fast, and when they get away
	// from it, it teleports to them. It also teleports now and then for no reason, when it is
	// hurt, and out of the sea, which hurts it. Minecraft can't see where the land is, so for
	// each teleport it picks a spot and has Subnautica look there, exactly as it does before
	// putting a creeper down; only dry, open ground will do. Subnautica shows the Warper's
	// swirl where it left and where it arrived.

	private static final int ENDER_PROBE_EVERY = 167;
	private static final int ENDER_PROBE_AFTER_ONE_APPEARS = 667;
	private static final int MOST_ENDERMEN_NEAR_PLAYER = 2;
	private static final int MOST_ENDERMEN_AT_ONCE = 4;
	private static final double ENDER_NOTICES_WITHIN = 40.0;
	private static final double ENDER_GIVES_UP_BEYOND = 48.0;
	private static final double ENDER_SPEED = 0.2;

	/** Looked in the face for this many ticks, it turns on whoever is looking. */
	private static final int ENDER_STARE_TICKS = 5;

	// ---- Ghasts ----
	//
	// In the lava zones only: every five minutes, for each player there, there is a one in four
	// chance that a ghast comes.
	// Minecraft picks a few spots in the sea around the player, in any direction, and asks
	// Subnautica whether a ghast would fit in each ("SPACE id x y z half": is a box this big,
	// centred here, under water and clear of everything solid?). The first spot Subnautica
	// says yes to ("SPACED id 1") gets the ghast. It keeps its distance and spits fireballs,
	// which are Minecraft's own: they burst on Subnautica's scenery like any other projectile.

	private static final int GHAST_EVERY = 6000;
	private static final int GHAST_CHANCE = 25;
	private static final int GHAST_SPOTS = 4;
	private static final int MOST_GHASTS_NEAR_PLAYER = 1;
	private static final int MOST_GHASTS_AT_ONCE = 3;
	private static final double GHAST_NOTICES_WITHIN = 56.0;
	private static final double GHAST_SPEED = 0.1;
	private static final int GHAST_SHOOTS_EVERY = 80;

	/** A spot Subnautica has been asked about for a ghast: where, for which try of which player, and when. */
	private record Space(double x, double y, double z, UUID player, int round, int asked) {
	}

	private static final Map<Integer, Space> SPACES = new HashMap<>();
	private static int nextSpace;

	/** What is remembered for one mob. */
	private static final class Track {
		int question;
		int waited;
		int lastAsked;
		int strikeIn;
		int charge;
		boolean onGround;
		double fallSpeed;

		// For an enderman: who it is after, how long until it may next teleport, how long it
		// has been stared at, and its health and place a tick ago (to tell a hit and a teleport).
		UUID angryAt;
		int teleportIn;
		int stared;
		float lastHealth;

		/** Where an enderman has just teleported from, until Subnautica has been told to show it. */
		Vec3d warpedFrom;
	}

	/** How far one player has travelled underwater since the last mob appeared for them, and when to next look for creeper ground. */
	private static final class Travel {
		Vec3d last;
		double blocks;
		int probeIn = PROBE_EVERY;
		int enderIn = ENDER_PROBE_EVERY;
		int ghastIn = GHAST_EVERY;
		int ghastRound;
		int ghastRoundUsed;
	}

	/** A spot Subnautica has been asked to look at, and what for: 0 to put a creeper there, 1 an enderman, 2 to teleport this enderman there. */
	private record Probe(double x, double z, int asked, int kind, UUID mob) {
	}

	private static final Map<Integer, Probe> PROBES = new HashMap<>();
	private static int nextProbe;

	private static final Map<UUID, Track> TRACKS = new HashMap<>();
	private static final Map<Integer, UUID> QUESTIONS = new HashMap<>();
	private static final Map<UUID, Travel> TRAVELS = new HashMap<>();
	private static final Queue<String> ANSWERS = new ConcurrentLinkedQueue<>();
	private static int nextQuestion;
	private static int ticks;

	private VoidMobs() {
	}

	/** The server is stopping: everything remembered here belongs to the world that is closing. */
	public static void reset() {
		SPACES.clear();
		PROBES.clear();
		TRACKS.clear();
		QUESTIONS.clear();
		TRAVELS.clear();
		ANSWERS.clear();
	}

	/** An answer from Subnautica to a movement question; ones that aren't about a mob are ignored. */
	public static void onAnswer(String numbers) {
		ANSWERS.add(numbers);
	}

	/** Called every server tick. */
	public static void tick(MinecraftServer server) {
		ServerWorld world = server.getWorld(MovementBridge.OCEAN_VOID);
		List<ServerPlayerEntity> players = new ArrayList<>();

		if (world != null) {
			for (ServerPlayerEntity player : world.getPlayers()) {
				if (Sessions.isActive(player)) {
					players.add(player);
				}
			}
		}

		if (players.isEmpty()) {
			ANSWERS.clear();
			return;
		}

		ticks++;
		applyAnswers(world);

		List<? extends MobEntity> mobs = world.getEntitiesByType(TypeFilter.instanceOf(MobEntity.class), mob -> mob.getCommandTags().contains(TAG));

		for (MobEntity mob : mobs) {
			ServerPlayerEntity nearest = nearest(mob, players, false);

			// Left far behind by everyone.
			if (nearest == null || mob.squaredDistanceTo(nearest) > REMOVED_BEYOND * REMOVED_BEYOND) {
				forget(mob);
				mob.discard();
				continue;
			}

			// Daylight sets a Drowned alight unless it is in water, and Minecraft sees no
			// water here. Below the surface it is put out before the fire can show or hurt.
			if (mob instanceof DrownedEntity && mob.getY() < WaterState.SEA_LEVEL - 0.3) {
				mob.extinguish();
			}

			if (mob.isAlive()) {
				steer(server, mob, players, nearest);
			} else {
				forget(mob);
			}
		}

		// Count each player's travel, and when one has gone far enough, bring a mob.
		for (ServerPlayerEntity player : players) {
			Travel travel = TRAVELS.computeIfAbsent(player.getUuid(), id -> new Travel());
			Vec3d at = player.getPos();
			boolean underwater = at.y < WaterState.SEA_LEVEL - 2.0 && !Sessions.get(player).dry;

			if (travel.last != null && underwater) {
				double moved = at.distanceTo(travel.last);

				// A jump of many blocks in one tick is a teleport, not travelling.
				if (moved < 10.0) {
					travel.blocks += moved;
				}
			}

			travel.last = at;

			boolean deep = at.y <= WaterState.SEA_LEVEL - DEEP_BELOW;

			if (deep) {
				// Guardians: a quarter as often, and then one time in four.
				if (travel.blocks >= BLOCKS_PER_MOB * 4.0 && ticks % 20 == 0 && underwater) {
					travel.blocks = 0.0;

					if (player.getRandom().nextInt(100) < GUARDIAN_CHANCE) {
						appear(world, player, mobs, true);
					}
				}
			} else if (travel.blocks >= BLOCKS_PER_MOB && ticks % 20 == 0 && underwater && appear(world, player, mobs, false)) {
				travel.blocks = 0.0;
			}

			// Now and then, have Subnautica look for dry land near this player to put a creeper on.
			if (--travel.probeIn <= 0) {
				travel.probeIn = PROBE_EVERY;

				if (world.getDifficulty() != Difficulty.PEACEFUL && count(mobs, player, true) < MOST_CREEPERS_NEAR_PLAYER && count(mobs, null, true) < MOST_CREEPERS_AT_ONCE) {
					// Anywhere from 20 to 30 blocks away, in any direction.
					double angle = player.getRandom().nextDouble() * Math.PI * 2.0;
					double distance = 20.0 + player.getRandom().nextDouble() * 10.0;
					double x = player.getX() + Math.cos(angle) * distance;
					double z = player.getZ() + Math.sin(angle) * distance;

					probe(server, player, x, z, 0, null);
				}
			}

			// And, less often and further off, for somewhere to put an enderman.
			if (--travel.enderIn <= 0) {
				travel.enderIn = ENDER_PROBE_EVERY;

				if (world.getDifficulty() != Difficulty.PEACEFUL && count(mobs, player, EndermanEntity.class) < MOST_ENDERMEN_NEAR_PLAYER
						&& count(mobs, null, EndermanEntity.class) < MOST_ENDERMEN_AT_ONCE) {
					// Anywhere from 26 to 39 blocks away, in any direction.
					double angle = player.getRandom().nextDouble() * Math.PI * 2.0;
					double distance = 26.0 + player.getRandom().nextDouble() * 13.0;
					probe(server, player, player.getX() + Math.cos(angle) * distance, player.getZ() + Math.sin(angle) * distance, 1, null);
				}
			}
		}

		// Every five minutes, a one in four chance of a ghast for each player.
		for (ServerPlayerEntity player : players) {
			Travel travel = TRAVELS.get(player.getUuid());

			if (travel == null || --travel.ghastIn > 0) {
				continue;
			}

			travel.ghastIn = GHAST_EVERY;

			// Ghasts belong to the lava zones, and come nowhere else.
			if (!Gathering.isLavaZone(Sessions.get(player).biome)) {
				continue;
			}

			if (world.getDifficulty() == Difficulty.PEACEFUL || player.getRandom().nextInt(100) >= GHAST_CHANCE
					|| count(mobs, player, GhastEntity.class) >= MOST_GHASTS_NEAR_PLAYER || count(mobs, null, GhastEntity.class) >= MOST_GHASTS_AT_ONCE) {
				continue;
			}

			// A few spots in the sea, 24 to 40 blocks off in any direction, around the player's depth.
			travel.ghastRound++;

			for (int i = 0; i < GHAST_SPOTS; i++) {
				double angle = player.getRandom().nextDouble() * Math.PI * 2.0;
				double distance = 24.0 + player.getRandom().nextDouble() * 16.0;
				double x = player.getX() + Math.cos(angle) * distance;
				double z = player.getZ() + Math.sin(angle) * distance;
				double y = Math.min(player.getY() + player.getRandom().nextDouble() * 16.0 - 8.0, WaterState.SEA_LEVEL - 5.0);

				nextSpace = (nextSpace + 1) % 1_000_000;
				SPACES.put(nextSpace, new Space(x, y, z, player.getUuid(), travel.ghastRound, ticks));
				Sessions.get(player).send(server, player, String.format(Locale.ROOT, "SPACE %d %.2f %.2f %.2f 2.3", nextSpace, x, y, z));
			}
		}

		// Looks that were never answered are forgotten.
		PROBES.values().removeIf(probe -> ticks - probe.asked() > 200);
		SPACES.values().removeIf(space -> ticks - space.asked() > 200);
	}

	/** Has one player's Subnautica look straight down at a spot for dry, open ground (see Probe for what "kind" means). The answer comes to onProbed. */
	private static void probe(MinecraftServer server, ServerPlayerEntity asked, double x, double z, int kind, UUID mob) {
		nextProbe = (nextProbe + 1) % 1_000_000;
		PROBES.put(nextProbe, new Probe(x, z, ticks, kind, mob));

		// An enderman needs nearly three blocks of headroom; a creeper under two.
		Sessions.get(asked).send(server, asked, String.format(Locale.ROOT, kind == 0 ? "PROBE %d %.2f %.2f" : "PROBE %d %.2f %.2f 2.9", nextProbe, x, z));
	}

	/** How many of this class's creepers (or Drowned) there are, near one player or, with no player given, in all. */
	private static int count(List<? extends MobEntity> mobs, ServerPlayerEntity near, boolean creepers) {
		return count(mobs, near, creepers ? CreeperEntity.class : DrownedEntity.class);
	}

	/** How many of this class's mobs of one kind there are, near one player or, with no player given, in all. */
	private static int count(List<? extends MobEntity> mobs, ServerPlayerEntity near, Class<? extends MobEntity> kind) {
		int count = 0;

		for (MobEntity mob : mobs) {
			if (mob.isAlive() && kind.isInstance(mob) && (near == null || mob.squaredDistanceTo(near) < 64.0 * 64.0)) {
				count++;
			}
		}

		return count;
	}

	/**
	 * "id y": Subnautica found open dry land at the spot it was asked to look at, at this
	 * height. "id -": it didn't. On land that Minecraft has no block on either, a creeper appears.
	 */
	public static void onProbed(ServerPlayerEntity player, String text) {
		String[] parts = text.trim().split(" ");

		try {
			Probe probe = PROBES.remove(Integer.parseInt(parts[0]));

			if (probe == null || parts.length != 2 || parts[1].equals("-") || player.getWorld().getRegistryKey() != MovementBridge.OCEAN_VOID) {
				return;
			}

			double y = Double.parseDouble(parts[1]);
			ServerWorld world = player.getServerWorld();
			BlockPos feet = BlockPos.ofFloored(probe.x(), y + 0.1, probe.z());

			// Dry land, and not on (or in) Minecraft's blocks.
			if (y < WaterState.SEA_LEVEL + 1.0 || !world.getBlockState(feet.down()).isAir() || !world.getBlockState(feet).isAir() || !world.getBlockState(feet.up()).isAir()
					|| (probe.kind() != 0 && !world.getBlockState(feet.up(2)).isAir())) {
				return;
			}

			if (probe.kind() == 2) {
				// An enderman of this class's was waiting to teleport there.
				Entity found = probe.mob() == null ? null : world.getEntity(probe.mob());
				Track track = probe.mob() == null ? null : TRACKS.get(probe.mob());

				if (found instanceof EndermanEntity enderman && enderman.isAlive() && track != null) {
					world.playSound(null, enderman.getX(), enderman.getY(), enderman.getZ(), SoundEvents.ENTITY_ENDERMAN_TELEPORT, SoundCategory.HOSTILE, 1.0F, 1.0F);
					track.warpedFrom = enderman.getPos();
					enderman.setPosition(probe.x(), y + 0.05, probe.z());
					enderman.setVelocity(Vec3d.ZERO);
					enderman.velocityDirty = true;
					world.playSound(null, enderman.getX(), enderman.getY(), enderman.getZ(), SoundEvents.ENTITY_ENDERMAN_TELEPORT, SoundCategory.HOSTILE, 1.0F, 1.0F);

					// Whatever it was in the middle of asking Subnautica about its old place no longer matters.
					if (track.question != 0) {
						QUESTIONS.remove(track.question);
						track.question = 0;
					}

					track.onGround = true;
					track.fallSpeed = 0.0;
				}

				return;
			}

			if (probe.kind() == 1) {
				EndermanEntity enderman = EntityType.ENDERMAN.create(world);

				if (enderman == null) {
					return;
				}

				enderman.refreshPositionAndAngles(probe.x(), y + 0.05, probe.z(), player.getRandom().nextFloat() * 360.0F, 0.0F);
				enderman.setAiDisabled(true);
				enderman.setNoGravity(true);
				enderman.setPersistent();
				enderman.addCommandTag(TAG);
				world.spawnEntity(enderman);

				Track track = TRACKS.computeIfAbsent(enderman.getUuid(), id -> new Track());
				track.onGround = true;
				track.teleportIn = 100;

				Travel travel = TRAVELS.get(player.getUuid());

				if (travel != null) {
					travel.enderIn = ENDER_PROBE_AFTER_ONE_APPEARS;
				}

				SubnauticaLink.LOGGER.info("An enderman appeared near {} at ({}, {}, {})", player.getName().getString(),
						String.format("%.0f", probe.x()), String.format("%.0f", y), String.format("%.0f", probe.z()));
				return;
			}

			CreeperEntity creeper = EntityType.CREEPER.create(world);

			if (creeper == null) {
				return;
			}

			creeper.refreshPositionAndAngles(probe.x(), y + 0.05, probe.z(), player.getRandom().nextFloat() * 360.0F, 0.0F);
			creeper.setAiDisabled(true);
			creeper.setNoGravity(true);
			creeper.setPersistent();
			creeper.addCommandTag(TAG);

			// It drops exactly four gunpowder (see onDeath), so its usual drops are switched off.
			NbtCompound saved = creeper.writeNbt(new NbtCompound());
			saved.putString("DeathLootTable", "minecraft:empty");
			creeper.readNbt(saved);

			world.spawnEntity(creeper);
			TRACKS.computeIfAbsent(creeper.getUuid(), id -> new Track()).onGround = true;

			Travel travel = TRAVELS.get(player.getUuid());

			if (travel != null) {
				travel.probeIn = PROBE_AFTER_ONE_APPEARS;
			}

			SubnauticaLink.LOGGER.info("A creeper appeared near {} at ({}, {}, {})", player.getName().getString(),
					String.format("%.0f", probe.x()), String.format("%.0f", y), String.format("%.0f", probe.z()));
		} catch (NumberFormatException e) {
			// A garbled answer; ignore it.
		}
	}

	/** "id 1": Subnautica says a ghast fits in the sea at the spot it was asked about. "id 0": it doesn't. */
	public static void onSpaced(ServerPlayerEntity player, String text) {
		String[] parts = text.trim().split(" ");

		try {
			Space space = SPACES.remove(Integer.parseInt(parts[0]));
			Travel travel = TRAVELS.get(player.getUuid());

			if (space == null || travel == null || parts.length != 2 || !parts[1].equals("1") || !space.player().equals(player.getUuid())
					|| travel.ghastRoundUsed == space.round() || player.getWorld().getRegistryKey() != MovementBridge.OCEAN_VOID) {
				return;
			}

			ServerWorld world = player.getServerWorld();
			GhastEntity ghast = EntityType.GHAST.create(world);

			if (ghast == null) {
				return;
			}

			// One ghast for each try, however many of its spots turn out to be clear.
			travel.ghastRoundUsed = space.round();

			// The spot asked about was where its middle goes; a ghast is four blocks tall.
			ghast.refreshPositionAndAngles(space.x(), space.y() - 2.0, space.z(), player.getRandom().nextFloat() * 360.0F, 0.0F);
			ghast.setAiDisabled(true);
			ghast.setNoGravity(true);
			ghast.setPersistent();
			ghast.addCommandTag(TAG);
			world.spawnEntity(ghast);
			TRACKS.computeIfAbsent(ghast.getUuid(), id -> new Track()).strikeIn = GHAST_SHOOTS_EVERY;

			SubnauticaLink.LOGGER.info("A ghast appeared near {} at ({}, {}, {})", player.getName().getString(),
					String.format("%.0f", space.x()), String.format("%.0f", space.y()), String.format("%.0f", space.z()));
		} catch (NumberFormatException e) {
			// A garbled answer; ignore it.
		}
	}

	/** One of this class's creepers was killed (not blown up): it leaves four gunpowder. */
	public static void onDeath(LivingEntity entity) {
		if (entity instanceof CreeperEntity && entity.getCommandTags().contains(TAG) && entity.getWorld() instanceof ServerWorld world) {
			ItemEntity drop = new ItemEntity(world, entity.getX(), entity.getY() + 0.5, entity.getZ(), new ItemStack(Items.GUNPOWDER, 4));
			drop.setToDefaultPickupDelay();
			world.spawnEntity(drop);
		}
	}

	/** Turns a mob to its prey, strikes if in reach, and asks Subnautica how far it can move. */
	private static void steer(MinecraftServer server, MobEntity mob, List<ServerPlayerEntity> players, ServerPlayerEntity nearest) {
		Track track = TRACKS.computeIfAbsent(mob.getUuid(), id -> new Track());
		ServerPlayerEntity prey = nearest(mob, players, true);
		// (A ghast floats wherever it is, as it does in the air.)
		boolean inWater = mob.getY() < WaterState.SEA_LEVEL - 0.3 || mob instanceof GhastEntity;
		Vec3d wanted = Vec3d.ZERO;

		if (track.strikeIn > 0) {
			track.strikeIn--;
		}

		double notices = mob instanceof GhastEntity ? GHAST_NOTICES_WITHIN : mob instanceof EndermanEntity ? ENDER_NOTICES_WITHIN : NOTICES_WITHIN;

		if (prey != null && mob.squaredDistanceTo(prey) > notices * notices) {
			prey = null;
		}

		// An enderman only goes for whoever provoked it; anyone else nearby it just watches.
		boolean enderman = mob instanceof EndermanEntity;
		boolean provoked = false;

		if (mob instanceof EndermanEntity tended) {
			ServerPlayerEntity after = tendEnderman(server, tended, track, players, prey, nearest);

			if (after != null) {
				prey = after;
				provoked = true;
			}
		}

		if (prey == null && mob instanceof GhastEntity idle) {
			idle.setShooting(false);
		}

		// A guardian with nobody to go for, or whose prey is behind a hull, lets its beam drop.
		if (mob instanceof GuardianEntity && (prey == null || Sessions.get(prey).aboard)) {
			((GuardianInvoker) mob).subnauticaLink$setBeamTarget(0);
			track.charge = 0;
		}

		if (prey != null) {
			Vec3d to = prey.getPos().add(0.0, prey.getHeight() * 0.5, 0.0).subtract(mob.getPos().add(0.0, mob.getHeight() * 0.5, 0.0));
			double distance = to.length();

			// Face them.
			float yaw = (float) (MathHelper.atan2(to.z, to.x) * (180.0 / Math.PI)) - 90.0F;
			float pitch = (float) -(MathHelper.atan2(to.y, Math.sqrt(to.x * to.x + to.z * to.z)) * (180.0 / Math.PI));
			mob.setYaw(yaw);
			mob.setBodyYaw(yaw);
			mob.setHeadYaw(yaw);
			mob.setPitch(MathHelper.clamp(pitch, -60.0F, 60.0F));
		} else if (mob instanceof CreeperEntity creeper) {
			// Nobody to go for: the fuse runs back down.
			creeper.setFuseSpeed(-1);
		}

		if (prey != null) {
			Vec3d to = prey.getPos().add(0.0, prey.getHeight() * 0.5, 0.0).subtract(mob.getPos().add(0.0, mob.getHeight() * 0.5, 0.0));
			double distance = to.length();

			// Someone aboard the Cyclops has its hull around them: a mob outside can come up
			// against it (Subnautica stops it there) but can't strike or blow up through it.
			boolean sheltered = Sessions.get(prey).aboard;

			if (enderman && !provoked) {
				// It stands where it is and watches.
			} else if (sheltered) {
				if (mob instanceof CreeperEntity creeper) {
					creeper.setFuseSpeed(-1);
				}

				if (distance > 0.01) {
					wanted = inWater
							? to.multiply(SWIM_SPEED / distance)
							: new Vec3d(to.x, 0.0, to.z).normalize().multiply(WALK_SPEED);
				}
			} else if (mob instanceof GuardianEntity guardian) {
				// Swim up to about nine blocks off, then hold there.
				if (distance > 9.0) {
					wanted = to.multiply(SWIM_SPEED / distance);
				}

				if (distance < GUARDIAN_BEAM_WITHIN && track.strikeIn <= 0) {
					// The beam is fixed on the prey; held there long enough, it hurts.
					((GuardianInvoker) guardian).subnauticaLink$setBeamTarget(prey.getId());

					if (++track.charge >= GUARDIAN_CHARGE) {
						track.charge = 0;
						track.strikeIn = GUARDIAN_REST;
						prey.damage(mob.getDamageSources().indirectMagic(guardian, guardian), GUARDIAN_DAMAGE);
						((GuardianInvoker) guardian).subnauticaLink$setBeamTarget(0);
					}
				} else {
					((GuardianInvoker) guardian).subnauticaLink$setBeamTarget(0);
					track.charge = 0;
				}
			} else if (mob instanceof GhastEntity ghast) {
				// A ghast keeps its distance: closer if far off, away if crowded, else it hangs
				// where it is. Every four seconds it opens its mouth and spits a fireball.
				if (distance > 22.0) {
					wanted = to.multiply(GHAST_SPEED / distance);
				} else if (distance < 12.0 && distance > 0.01) {
					wanted = to.multiply(-GHAST_SPEED / distance);
				}

				ghast.setShooting(track.strikeIn <= 12);

				if (track.strikeIn <= 0 && distance > 0.01) {
					track.strikeIn = GHAST_SHOOTS_EVERY;

					Vec3d aim = to.multiply(1.0 / distance);
					FireballEntity ball = new FireballEntity(mob.getWorld(), ghast, aim, ghast.getFireballStrength());
					ball.setPosition(ghast.getX() + aim.x * 3.0, ghast.getBodyY(0.5) + 0.5, ghast.getZ() + aim.z * 3.0);
					mob.getWorld().spawnEntity(ball);
					mob.getWorld().syncWorldEvent(null, WorldEvents.GHAST_SHOOTS, ghast.getBlockPos(), 0);
				}
			} else if (mob instanceof CreeperEntity creeper) {
				// A creeper doesn't strike. Close enough, it stands still and its fuse runs
				// (Minecraft's own: it flashes, swells and explodes); if its prey gets well
				// away in time, the fuse runs back down.
				if (distance < FUSE_WITHIN) {
					creeper.setFuseSpeed(1);
				} else if (distance > FUSE_STOPS_BEYOND) {
					creeper.setFuseSpeed(-1);
				}

				if (creeper.getFuseSpeed() <= 0 && distance > 0.01) {
					wanted = inWater
							? to.multiply(SWIM_SPEED / distance)
							: new Vec3d(to.x, 0.0, to.z).normalize().multiply(WALK_SPEED);
				}
			} else if (enderman && !mob.getBoundingBox().expand(0.7).intersects(prey.getBoundingBox())) {
				// An enderman after someone comes at twice the pace of anything else here.
				if (distance > 0.01) {
					wanted = inWater
							? to.multiply(SWIM_SPEED / distance)
							: new Vec3d(to.x, 0.0, to.z).normalize().multiply(ENDER_SPEED);
				}
			} else if (mob instanceof DrownedEntity && mob.getMainHandStack().isOf(Items.TRIDENT) && distance > TRIDENT_FROM && distance < TRIDENT_WITHIN) {
				// A Drowned with a trident throws it, as in Minecraft, and keeps coming meanwhile.
				if (track.strikeIn <= 0) {
					track.strikeIn = TRIDENT_EVERY;

					TridentEntity trident = new TridentEntity(mob.getWorld(), mob, new ItemStack(Items.TRIDENT));
					double flat = Math.sqrt(to.x * to.x + to.z * to.z);

					// Minecraft's own aim: at the prey, a little high for the drop on the way.
					trident.setVelocity(to.x, to.y + flat * 0.2, to.z, 1.6F, 14 - mob.getWorld().getDifficulty().getId() * 4);
					mob.swingHand(Hand.MAIN_HAND);
					mob.playSound(SoundEvents.ENTITY_DROWNED_SHOOT, 1.0F, 1.0F / (mob.getRandom().nextFloat() * 0.4F + 0.8F));
					mob.getWorld().spawnEntity(trident);
				}

				wanted = inWater
						? to.multiply(SWIM_SPEED / distance)
						: new Vec3d(to.x, 0.0, to.z).normalize().multiply(WALK_SPEED);
			} else if (mob.getBoundingBox().expand(0.7).intersects(prey.getBoundingBox())) {
				// In reach: strike, with Minecraft's own damage for a Drowned.
				if (track.strikeIn <= 0) {
					track.strikeIn = STRIKE_EVERY;
					mob.swingHand(Hand.MAIN_HAND);
					mob.tryAttack(prey);
				}
			} else if (distance > 0.01) {
				// Swim straight at them; on dry land, walk.
				wanted = inWater
						? to.multiply(SWIM_SPEED / distance)
						: new Vec3d(to.x, 0.0, to.z).normalize().multiply(WALK_SPEED);
			}
		}

		// Being hit knocks it back: Minecraft gives it a speed, which is carried out here and fades.
		Vec3d pushed = mob.getVelocity();

		if (pushed.lengthSquared() > 1.0E-4) {
			wanted = wanted.add(pushed);
			mob.setVelocity(pushed.multiply(0.6));
		} else if (pushed.lengthSquared() > 0.0) {
			mob.setVelocity(Vec3d.ZERO);
		}

		// Out of the water it falls like anything else.
		if (inWater) {
			track.fallSpeed = 0.0;
		} else {
			track.fallSpeed = track.onGround ? -0.08 : Math.max(-2.0, (track.fallSpeed - 0.08) * 0.98);
			wanted = wanted.add(0.0, track.fallSpeed, 0.0);
		}

		if (track.question != 0) {
			// Still waiting for the last answer.
			if (++track.waited <= PATIENCE_TICKS) {
				return;
			}

			QUESTIONS.remove(track.question);
			track.question = 0;
		}

		if (wanted.lengthSquared() < 1.0E-6 || mob.squaredDistanceTo(nearest) > MOVED_WITHIN * MOVED_WITHIN) {
			track.lastAsked = ticks;
			return;
		}

		// When answers take a while to come back, each question covers the ticks gone by.
		int steps = Math.max(1, Math.min(4, ticks - track.lastAsked));
		wanted = wanted.multiply(steps);
		track.lastAsked = ticks;

		nextQuestion = (nextQuestion + 1) % 1_000_000;
		track.question = FIRST_QUESTION + nextQuestion;
		track.waited = 0;
		QUESTIONS.put(track.question, mob.getUuid());

		Box box = mob.getBoundingBox();
		Vec3d centre = box.getCenter();

		// The question the player's movement asks: it can step up a little, as a mob does.
		Sessions.get(nearest).send(server, nearest, String.format(Locale.ROOT, "SWEEP %d %.4f %.4f %.4f %.4f %.4f %.4f %.6f %.6f %.6f 0.6 %d",
				track.question, centre.x, centre.y, centre.z,
				box.getLengthX() / 2.0, box.getLengthY() / 2.0, box.getLengthZ() / 2.0,
				wanted.x, wanted.y, wanted.z, track.onGround ? 1 : 0));
	}

	/**
	 * An enderman's own business, every tick: who it is after (the answer, or null for
	 * nobody), and when it teleports. "watched" is the nearest player it could go for, if any
	 * is close enough to notice; "nearest" is the nearest player of all, whose Subnautica is
	 * the one asked about the ground.
	 */
	private static ServerPlayerEntity tendEnderman(MinecraftServer server, EndermanEntity enderman, Track track, List<ServerPlayerEntity> players,
			ServerPlayerEntity watched, ServerPlayerEntity nearest) {
		// It has just teleported (see onProbed): Subnautica shows the swirl at both ends.
		Vec3d at = enderman.getPos();

		if (track.warpedFrom != null) {
			Sessions.broadcast(String.format(Locale.ROOT, "FX warpout %.3f %.3f %.3f 0 1 0", track.warpedFrom.x, track.warpedFrom.y + 1.4, track.warpedFrom.z));
			Sessions.broadcast(String.format(Locale.ROOT, "FX warpin %.3f %.3f %.3f 0 1 0", at.x, at.y + 1.4, at.z));
			track.warpedFrom = null;
		}

		if (track.teleportIn > 0) {
			track.teleportIn--;
		}

		// Whoever it is after, if they are still there to be after.
		ServerPlayerEntity target = null;

		if (track.angryAt != null) {
			for (ServerPlayerEntity player : players) {
				if (player.getUuid().equals(track.angryAt)) {
					target = player;
				}
			}

			if (target == null || !target.isAlive() || target.isCreative() || target.isSpectator()
					|| enderman.squaredDistanceTo(target) > ENDER_GIVES_UP_BEYOND * ENDER_GIVES_UP_BEYOND) {
				target = null;
				track.angryAt = null;
				enderman.setTarget(null);
			}
		}

		float health = enderman.getHealth();
		boolean hurt = track.lastHealth > 0.0F && health < track.lastHealth;
		track.lastHealth = health;

		if (target == null) {
			// Hit by a player, or looked in the face for a moment, it turns on them.
			ServerPlayerEntity provoker = null;

			if (hurt && enderman.getAttacker() instanceof ServerPlayerEntity attacker && players.contains(attacker)
					&& attacker.isAlive() && !attacker.isCreative() && !attacker.isSpectator()) {
				provoker = attacker;
			} else if (watched != null && isStaring(watched, enderman)) {
				// Minecraft plays its "you looked at me" sound when it turns on someone it was
				// already marked as provoked by, so the mark goes on at the first tick of the look.
				if (track.stared == 0) {
					enderman.setProvoked();
				}

				if (++track.stared >= ENDER_STARE_TICKS) {
					provoker = watched;
				}
			} else if (track.stared > 0) {
				// They looked away in time: the mark comes off again.
				track.stared = 0;
				enderman.setTarget(null);
			}

			if (provoker != null) {
				target = provoker;
				track.angryAt = provoker.getUuid();
				track.stared = 0;

				// Minecraft's own: its jaw drops and it shakes.
				enderman.setTarget(provoker);
			}
		}

		// The sea hurts it, as water does in Minecraft.
		boolean inSea = enderman.getY() < WaterState.SEA_LEVEL - 0.3;

		if (inSea && ticks % 20 == 0) {
			enderman.damage(enderman.getDamageSources().drown(), 1.0F);
		}

		// Teleporting. Subnautica has to be able to see the ground, so only near the player it is asked.
		if (track.teleportIn <= 0 && enderman.squaredDistanceTo(nearest) < MOVED_WITHIN * MOVED_WITHIN) {
			Vec3d around = null;
			double least = 0.0;
			double most = 0.0;

			if (inSea || (hurt && enderman.getRandom().nextInt(3) == 0)) {
				// Out of the water, or away from what hurt it.
				around = at;
				least = 8.0;
				most = 16.0;
				track.teleportIn = 30;
			} else if (target != null && enderman.squaredDistanceTo(target) > 10.0 * 10.0 && !Sessions.get(target).aboard) {
				// Its prey has got away from it: to just beside them.
				around = target.getPos();
				least = 3.0;
				most = 6.0;
				track.teleportIn = 60;
			} else if (target == null && enderman.getRandom().nextInt(300) == 0) {
				// For no reason at all, every quarter of a minute or so.
				around = at;
				least = 8.0;
				most = 16.0;
				track.teleportIn = 100;
			}

			if (around != null) {
				double angle = enderman.getRandom().nextDouble() * Math.PI * 2.0;
				double distance = least + enderman.getRandom().nextDouble() * (most - least);
				probe(server, nearest, around.x + Math.cos(angle) * distance, around.z + Math.sin(angle) * distance, 2, enderman.getUuid());
			}
		}

		return target;
	}

	/** Whether a player is looking an enderman in the face, by Minecraft's own rule (and a carved pumpkin on the head hides the look). */
	private static boolean isStaring(ServerPlayerEntity player, EndermanEntity enderman) {
		if (player.getEquippedStack(EquipmentSlot.HEAD).isOf(Items.CARVED_PUMPKIN)) {
			return false;
		}

		Vec3d look = player.getRotationVec(1.0F).normalize();
		Vec3d to = new Vec3d(enderman.getX() - player.getX(), enderman.getEyeY() - player.getEyeY(), enderman.getZ() - player.getZ());
		double distance = to.length();

		return distance > 0.01 && look.dotProduct(to.multiply(1.0 / distance)) > 1.0 - 0.025 / distance && player.canSee(enderman);
	}

	/** Moves each mob by however far Subnautica said it got. */
	private static void applyAnswers(ServerWorld world) {
		String answer;

		while ((answer = ANSWERS.poll()) != null) {
			// "id rx ry rz ground wallCount [nx nz]..."
			String[] parts = answer.split(" ");

			try {
				UUID id = QUESTIONS.remove(Integer.parseInt(parts[0]));
				Entity mob = id == null ? null : world.getEntity(id);
				Track track = id == null ? null : TRACKS.get(id);

				if (mob == null || track == null || !mob.isAlive()) {
					continue;
				}

				track.question = 0;
				mob.setPosition(mob.getX() + Double.parseDouble(parts[1]), mob.getY() + Double.parseDouble(parts[2]), mob.getZ() + Double.parseDouble(parts[3]));
				track.onGround = parts[4].equals("1");

				// Tell every game straight away, so it is seen to move smoothly.
				mob.velocityDirty = true;
			} catch (NumberFormatException | ArrayIndexOutOfBoundsException e) {
				// A garbled answer; ignore it.
			}
		}
	}

	/** Brings one Drowned into the sea near a player: some way off, and behind them rather than in front. */
	private static boolean appear(ServerWorld world, ServerPlayerEntity player, List<? extends MobEntity> mobs, boolean guardian) {
		Class<? extends MobEntity> kind = guardian ? GuardianEntity.class : DrownedEntity.class;

		if (world.getDifficulty() == Difficulty.PEACEFUL || count(mobs, null, kind) >= MOST_AT_ONCE || count(mobs, player, kind) >= MOST_NEAR_PLAYER) {
			return false;
		}

		MobEntity mob = guardian ? EntityType.GUARDIAN.create(world) : EntityType.DROWNED.create(world);

		if (mob == null) {
			return false;
		}

		// Somewhere in the wide arc behind the player (up to 110 degrees either side of
		// straight behind), 23 to 31 blocks away, near their depth, and under the surface.
		double angle = Math.toRadians(player.getYaw() + 180.0 + (player.getRandom().nextDouble() * 220.0 - 110.0));
		double distance = 23.0 + player.getRandom().nextDouble() * 8.0;
		double x = player.getX() - Math.sin(angle) * distance;
		double z = player.getZ() + Math.cos(angle) * distance;
		double y = Math.min(player.getY() + player.getRandom().nextDouble() * 8.0 - 4.0, WaterState.SEA_LEVEL - 3.0);

		mob.refreshPositionAndAngles(x, y, z, player.getRandom().nextFloat() * 360.0F, 0.0F);
		mob.setAiDisabled(true);
		mob.setNoGravity(true);
		mob.setPersistent();
		mob.addCommandTag(TAG);

		// Now and then one carries a trident, as in Minecraft, and throws it (see steer).
		if (!guardian && player.getRandom().nextInt(10) == 0) {
			mob.equipStack(EquipmentSlot.MAINHAND, new ItemStack(Items.TRIDENT));
		}

		world.spawnEntity(mob);
		SubnauticaLink.LOGGER.info("A {} appeared near {} at ({}, {}, {})", guardian ? "guardian" : "Drowned", player.getName().getString(),
				String.format("%.0f", x), String.format("%.0f", y), String.format("%.0f", z));
		return true;
	}

	/** The nearest linked player to a mob; with "asPrey", only those a mob would go for (alive, not in Creative or Spectator). */
	private static ServerPlayerEntity nearest(Entity mob, List<ServerPlayerEntity> players, boolean asPrey) {
		ServerPlayerEntity nearest = null;
		double nearestDistance = Double.MAX_VALUE;

		for (ServerPlayerEntity player : players) {
			if (asPrey && (!player.isAlive() || player.isCreative() || player.isSpectator())) {
				continue;
			}

			double distance = mob.squaredDistanceTo(player);

			if (distance < nearestDistance) {
				nearestDistance = distance;
				nearest = player;
			}
		}

		return nearest;
	}

	private static void forget(Entity mob) {
		Track track = TRACKS.remove(mob.getUuid());

		if (track != null && track.question != 0) {
			QUESTIONS.remove(track.question);
		}
	}
}
