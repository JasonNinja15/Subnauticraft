package com.example.subnauticalink;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Consumer;
import java.util.function.Function;

import net.minecraft.block.BlockState;
import net.minecraft.block.FallingBlock;
import net.minecraft.entity.Entity;
import net.minecraft.entity.FallingBlockEntity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.TntEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

/**
 * Dropped items in the ocean void: shown in Subnautica, and landing on its scenery.
 *
 * <p>A dropped item is still an ordinary Minecraft item, picked up the ordinary way by walking
 * into it. Two things are added.
 *
 * <p><b>Showing it.</b> Subnautica is told what each kind of item looks like once ("ITEMMODEL
 * kind scale colour quads", see {@code ItemModels}), then where each dropped item is ("ITEM id
 * kind x y z") whenever it moves, and "ITEMGONE id" when it is picked up or despawns.
 *
 * <p><b>Falling and landing.</b> Minecraft's own falling is switched off for these items (the
 * void has no ground, so they would fall forever). Instead this class moves them: each tick it
 * works out where the item wants to go (thrown speed, gravity, slower in water) and asks
 * Subnautica how far it gets, with the same "SWEEP" question the player's movement uses (see
 * {@link RemoteCollision}). Subnautica's scenery includes the cubes it builds for Minecraft's
 * blocks, so items land on the seabed, base floors and placed blocks alike. Unlike the
 * player, nothing waits for the answer: it is applied on the next tick.
 *
 * <p><b>Falling sand.</b> Sand, gravel and the other blocks that fall are moved the same way
 * while they are falling. Where one lands (on Subnautica's scenery or on a block) it becomes
 * a block again. One that lands on scenery has nothing under it as far as Minecraft can see,
 * and would be set falling again at once; the places of those are remembered, and they are
 * left where they lie (see {@code FallingBlockMixin}).
 */
public final class ItemSync {
	/** Items within this many blocks of the player are shown in Subnautica. */
	private static final double SHOWN_WITHIN = 48.0;

	/** Items within this many blocks are moved. Further off, Subnautica may not have loaded its scenery. */
	private static final double MOVED_WITHIN = 32.0;

	/** Item questions use numbers from here up, so their answers can be told from the player's. */
	public static final int FIRST_QUESTION = 1_000_000;

	/** A resting item checks this often (in ticks) that what it rests on is still there. */
	private static final int REST_CHECK_EVERY = 20;

	/** Give up on an unanswered question after this many ticks and ask again. */
	private static final int PATIENCE_TICKS = 10;

	/** What Minecraft knows about one dropped item being shown. */
	private static final class Tracked {
		Entity entity;

		/** Lit TNT rather than a dropped item: drawn full size and still, and never picked up. */
		boolean tnt;

		/** Falling sand (or the like): it falls as it does in air, and turns back into a block where it lands. */
		boolean block;
		Vec3d speed = Vec3d.ZERO;
		Vec3d asked = Vec3d.ZERO;
		boolean resting;
		int question;
		int lastAsked;
		int waited;
		int restTicks;
		int stuckAnswers;
	}

	private static final Map<Integer, Tracked> ITEMS = new HashMap<>();
	private static final Map<Integer, Tracked> QUESTIONS = new HashMap<>();

	/** Where fallen sand (or the like) is lying on Subnautica's scenery, with no Minecraft block under it. */
	private static final Set<Long> RESTING = ConcurrentHashMap.newKeySet();

	/** The speed each item was dropped or thrown with, kept from when it appeared until it is first moved. */
	private static final Map<Integer, Vec3d> THROWN = new ConcurrentHashMap<>();

	/** Answers from Subnautica, waiting for the next tick. */
	private static final Queue<String> ANSWERS = new ConcurrentLinkedQueue<>();

	private static int nextQuestion;

	/** Server ticks counted while anyone is linked. */
	private static int ticks;

	private ItemSync() {
	}

	/** Subnautica (re)connected or went away: it has no items, so start again. */
	public static void reset() {
		ITEMS.clear();
		QUESTIONS.clear();
		ANSWERS.clear();
	}

	/**
	 * An item appeared in the ocean void. Switch off Minecraft's own falling for it, and
	 * remember how fast it was thrown so that this class can carry the throw on.
	 */
	public static void onAppeared(Entity item) {
		if (THROWN.size() > 2000) {
			THROWN.clear();
		}

		THROWN.put(item.getId(), item.getVelocity());
		item.setNoGravity(true);
		item.setVelocity(Vec3d.ZERO);
	}

	/** The answer to one of this class's questions, passed on by the game of the player whose Subnautica was asked. */
	public static void onAnswer(String numbers) {
		ANSWERS.add(numbers);
	}

	/** Called every server tick. */
	public static void tick(MinecraftServer server) {
		ServerWorld world = server.getWorld(MovementBridge.OCEAN_VOID);

		// The linked players in the ocean void: the ones whose Subnautica can be asked.
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
		applyAnswers();

		// Take charge of any dropped item or lit TNT near any of them.
		for (ServerPlayerEntity player : players) {
			List<Entity> nearby = world.getEntitiesByClass(Entity.class, player.getBoundingBox().expand(SHOWN_WITHIN),
					entity -> entity.isAlive() && (entity instanceof ItemEntity || entity instanceof TntEntity || entity instanceof FallingBlockEntity));

			for (Entity entity : nearby) {
				if (!ITEMS.containsKey(entity.getId()) && entity.squaredDistanceTo(player) <= SHOWN_WITHIN * SHOWN_WITHIN) {
					Tracked item = new Tracked();
					item.entity = entity;
					item.tnt = entity instanceof TntEntity;
					item.block = entity instanceof FallingBlockEntity;
					Vec3d thrown = THROWN.remove(entity.getId());
					item.speed = thrown != null ? thrown : Vec3d.ZERO;
					item.lastAsked = ticks - 1;
					ITEMS.put(entity.getId(), item);
				}
			}
		}

		Iterator<Tracked> all = ITEMS.values().iterator();

		while (all.hasNext()) {
			Tracked item = all.next();
			Entity entity = item.entity;

			// The nearest linked player: theirs is the Subnautica asked about this item.
			ServerPlayerEntity nearest = null;
			double nearestDistance = Double.MAX_VALUE;

			for (ServerPlayerEntity player : players) {
				double distance = entity.squaredDistanceTo(player);

				if (distance < nearestDistance) {
					nearestDistance = distance;
					nearest = player;
				}
			}

			// Picked up, despawned, merged into another pile, or left behind by everyone.
			if (!entity.isAlive() || entity.getWorld() != world || nearest == null || nearestDistance > (SHOWN_WITHIN + 8.0) * (SHOWN_WITHIN + 8.0)) {
				QUESTIONS.remove(item.question);
				all.remove();
				continue;
			}

			// Only this class moves these items. Clear any speed Minecraft itself gave one.
			if (entity.getVelocity().lengthSquared() > 0.0) {
				entity.setVelocity(Vec3d.ZERO);
			}

			if (nearestDistance < MOVED_WITHIN * MOVED_WITHIN) {
				ServerPlayerEntity asked = nearest;
				LinkSession session = Sessions.get(asked);
				move(item, asked, session.dry, line -> session.send(server, asked, line));
			}

			// Pick it up from where it is shown. Minecraft's own pick-up check normally does
			// this, but it was missing items lying on blocks; asking directly makes sure that
			// walking into the item you can see is what collects it. (The item itself still
			// decides: not while it was only just thrown, and not if the inventory is full.)
			if (!item.tnt && !item.block) {
				for (ServerPlayerEntity player : players) {
					if (entity.isAlive() && player.isAlive() && !player.isSpectator()
							&& player.getBoundingBox().expand(1.0, 0.5, 1.0).intersects(entity.getBoundingBox())) {
						entity.onPlayerCollision(player);
					}
				}
			}
		}
	}

	/** Works out where an item wants to go this tick and asks Subnautica how far it gets. */
	private static void move(Tracked item, ServerPlayerEntity player, boolean playerIsDry, Consumer<String> send) {
		if (item.question != 0) {
			// Still waiting for the last answer.
			if (++item.waited <= PATIENCE_TICKS) {
				return;
			}

			QUESTIONS.remove(item.question);
			item.question = 0;
		}

		Entity entity = item.entity;
		Vec3d wanted;

		if (item.resting) {
			if (++item.restTicks % REST_CHECK_EVERY != 0) {
				return;
			}

			// Lying still: just feel for the ground, in case the block underneath was broken.
			wanted = new Vec3d(0.0, -0.1, 0.0);
		} else {
			// Below sea level is water, unless the player is somewhere dry (a base, the
			// lifepod) and the item is in there with them.
			// (Falling sand drops through water as it does through air, as in Minecraft.)
			boolean inWater = !item.block && entity.getY() < WaterState.SEA_LEVEL
					&& !(playerIsDry && entity.squaredDistanceTo(player) < 12.0 * 12.0);

			// Normally a question goes out every tick. When the answers take longer to come
			// back (the player asked is far away over the network), each question covers the
			// ticks that have gone by since the last one, so the item still falls at its
			// proper speed, just in bigger steps.
			int steps = Math.max(1, Math.min(4, ticks - item.lastAsked));
			Vec3d speed = item.speed;
			Vec3d total = Vec3d.ZERO;

			for (int step = 0; step < steps; step++) {
				if (inWater) {
					// Sinks gently, and a throw doesn't carry far.
					speed = new Vec3d(speed.x * 0.9, (speed.y - 0.008) * 0.92, speed.z * 0.9);
				} else {
					// Minecraft's own numbers for a falling item.
					speed = new Vec3d(speed.x * 0.98, (speed.y - 0.04) * 0.98, speed.z * 0.98);
				}

				total = total.add(speed);
			}

			item.speed = speed;
			wanted = total;
		}

		item.lastAsked = ticks;

		nextQuestion = (nextQuestion + 1) % 1_000_000;
		item.question = FIRST_QUESTION + nextQuestion;
		item.waited = 0;
		item.asked = wanted;
		QUESTIONS.put(item.question, item);

		Box box = entity.getBoundingBox();
		Vec3d centre = box.getCenter();

		// The same question the player's movement asks. No stepping up, and "not on the ground".
		send.accept(String.format(Locale.ROOT, "SWEEP %d %.4f %.4f %.4f %.4f %.4f %.4f %.6f %.6f %.6f 0 0",
				item.question, centre.x, centre.y, centre.z,
				box.getLengthX() / 2.0, box.getLengthY() / 2.0, box.getLengthZ() / 2.0,
				wanted.x, wanted.y, wanted.z));
	}

	/** Turns falling sand (or the like) back into a block where it has come to rest; if there is no room for it there, it drops as an item. */
	private static void land(Tracked item) {
		if (!(item.entity instanceof FallingBlockEntity falling) || !(falling.getWorld() instanceof ServerWorld world)) {
			return;
		}

		BlockState state = falling.getBlockState();

		// The block whose middle is nearest: sitting on a block, that is the space above it.
		BlockPos pos = BlockPos.ofFloored(falling.getX(), falling.getY() + 0.5, falling.getZ());

		if (!world.getBlockState(pos).isReplaceable() && world.getBlockState(pos.up()).isReplaceable()) {
			pos = pos.up();
		}

		// (A few things fall without being the kind of block that lies where it lands, scaffolding
		// for one: put back as blocks they would only fall again, so those drop as items.)
		if (state.getBlock() instanceof FallingBlock && world.getBlockState(pos).isReplaceable()) {
			// Nothing of Minecraft's under it: it is lying on Subnautica's scenery, and stays.
			if (FallingBlock.canFallThrough(world.getBlockState(pos.down()))) {
				RESTING.add(pos.asLong());
			}

			world.setBlockState(pos, state, 3);
			Sessions.broadcast(String.format(Locale.ROOT, "FX land %.3f %.3f %.3f 0 1 0", falling.getX(), falling.getY(), falling.getZ()));
		} else {
			falling.dropItem(state.getBlock());
		}

		falling.discard();
	}

	/** The server is stopping: the places where fallen sand lies belong to the world that is closing. */
	public static void forgetResting() {
		RESTING.clear();
		THROWN.clear();
	}

	/** Whether the block here is fallen sand (or the like) lying on Subnautica's scenery, which must not be set falling again. */
	public static boolean restsOnScenery(World world, BlockPos pos) {
		return !RESTING.isEmpty() && world.getRegistryKey() == MovementBridge.OCEAN_VOID && RESTING.contains(pos.asLong());
	}

	/** A block changed on the server. If fallen sand was lying there and it is no longer sand, the place is forgotten. */
	public static void onBlockChanged(World world, BlockPos pos, BlockState state) {
		if (!RESTING.isEmpty() && !(state.getBlock() instanceof FallingBlock) && world.getRegistryKey() == MovementBridge.OCEAN_VOID) {
			RESTING.remove(pos.asLong());
		}
	}

	/** The item an entity is drawn as: the dropped item itself, or a TNT block for lit TNT. */
	private static ItemStack stackOf(Entity entity) {
		return entity instanceof ItemEntity item ? item.getStack() : new ItemStack(Items.TNT);
	}

	private static String fmt(double value) {
		return String.format(Locale.ROOT, "%.3f", value);
	}

	/** Moves each item by however far Subnautica said it got. */
	private static void applyAnswers() {
		String answer;

		while ((answer = ANSWERS.poll()) != null) {
			// "id rx ry rz ground wallCount [nx nz]..."
			String[] parts = answer.split(" ");

			try {
				Tracked item = QUESTIONS.remove(Integer.parseInt(parts[0]));

				if (item == null || !item.entity.isAlive()) {
					continue;
				}

				item.question = 0;

				double x = Double.parseDouble(parts[1]);
				double y = Double.parseDouble(parts[2]);
				double z = Double.parseDouble(parts[3]);
				boolean ground = parts[4].equals("1");
				boolean hitWall = Integer.parseInt(parts[5]) > 0;

				Entity entity = item.entity;
				entity.setPosition(entity.getX() + x, entity.getY() + y, entity.getZ() + z);

				// Minecraft only tells players' games where an item is once a second, relying on
				// them to work out its fall themselves. They can't here, so each move is sent at
				// once; without this the item was seen to jump from place to place.
				entity.velocityDirty = true;

				if (item.resting) {
					if (!ground) {
						// Whatever it was lying on has gone: start falling again.
						item.resting = false;
						item.speed = Vec3d.ZERO;
					}

					continue;
				}

				Vec3d speed = item.speed;

				if (hitWall) {
					speed = new Vec3d(0.0, speed.y, 0.0);
				}

				// Pushed back up while trying to fall: it has sunk a little into whatever is
				// under it and been lifted out again. That counts as having landed. Without
				// this, such an item bobs up and down for ever and never comes to rest.
				boolean liftedOut = item.asked.y < 0.0 && y > item.asked.y + 0.02;

				if (liftedOut && !ground) {
					ground = true;
					speed = new Vec3d(0.0, 0.0, 0.0);
				}

				// Falling sand that has landed (or is wedged and going nowhere) becomes a block there.
				if (item.block) {
					boolean wedged = Math.abs(x) + Math.abs(y) + Math.abs(z) < 1.0E-4;

					if (ground || (wedged && ++item.stuckAnswers >= 3)) {
						land(item);
					} else {
						if (!wedged) {
							item.stuckAnswers = 0;
						}

						item.speed = speed;
					}

					continue;
				}

				// Landing with a bump kicks up whatever it landed on (sand, mostly).
				if (ground && item.asked.y < -0.08) {
					Sessions.broadcast(String.format(Locale.ROOT, "FX land %.3f %.3f %.3f 0 1 0", entity.getX(), entity.getY(), entity.getZ()));
				}

				if (ground) {
					// Landed: stop falling, and skid to a halt.
					speed = new Vec3d(speed.x * 0.6, 0.0, speed.z * 0.6);

					if (speed.x * speed.x + speed.z * speed.z < 1.0E-4) {
						item.resting = true;
						speed = Vec3d.ZERO;
						SubnauticaLink.LOGGER.info("Item {} ({}) came to rest at ({}, {}, {})", entity.getId(),
								stackOf(entity).getItem(), fmt(entity.getX()), fmt(entity.getY()), fmt(entity.getZ()));
					}
				} else if (item.asked.y > 0.0 && y < item.asked.y - 1.0E-4) {
					// Thrown up into a ceiling.
					speed = new Vec3d(speed.x, 0.0, speed.z);
				}

				// Wedged somewhere it can neither fall nor count as landed: let it lie.
				if (!ground && Math.abs(x) + Math.abs(y) + Math.abs(z) < 1.0E-4) {
					if (++item.stuckAnswers >= 20) {
						item.resting = true;
						speed = Vec3d.ZERO;
					}
				} else {
					item.stuckAnswers = 0;
				}

				item.speed = speed;
			} catch (NumberFormatException | ArrayIndexOutOfBoundsException e) {
				// A garbled answer; the item asks again next tick.
			}
		}
	}
}
