package com.example.subnauticalink;

import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

import net.minecraft.block.Block;
import net.minecraft.block.BlockRenderType;
import net.minecraft.block.BlockState;
import net.minecraft.block.FluidBlock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.registry.Registries;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.World;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.WorldChunk;

/**
 * Tells this player's Subnautica about the blocks in the ocean void, so it can show them and
 * make them solid. Client only.
 *
 * <p>Every player's game holds its own copy of the world around that player, kept up to date
 * by the server. This reads that copy. So in multiplayer each player's Subnautica is told
 * about the blocks by that player's own game, and everyone ends up with the same blocks
 * without the server having to describe them to anybody.
 *
 * <p>Subnautica gets a running list: "BLOCK x y z ..." for a block that is there, "BLOCK x y z
 * air" for one that has gone. Two things feed it:
 * <ul>
 *   <li>every block change in this game's copy of the world, as it happens ({@code
 *       WorldMixin}, by way of {@link BlockSync});</li>
 *   <li>blocks already there: the chunks around the player are read through once each. When a
 *       chunk drops out of this game's copy (the player moved away), Subnautica is told to
 *       forget its blocks ("CHUNKGONE cx cz"), and it is read again if it comes back.</li>
 * </ul>
 *
 * <p>Each kind of block is described once: its shape ("MODEL id ...", see {@link
 * BlockModels}) and the light it gives off, if any ("LIGHT id ..."). While a block is being
 * broken, by anyone, the cracks on it are passed on too ("CRACK x y z stage who").
 */
public final class ClientBlocks {
	/** How many chunks (16 blocks each) around the player are read for existing blocks. */
	private static final int CHUNK_REACH = 4;

	/** The most lines sent in one tick, so a big build doesn't flood the connection. */
	private static final int MOST_LINES_PER_TICK = 2000;

	/** Used for blocks with no map colour of their own (glass, for example). */
	private static final int FALLBACK_COLOUR = 0xC0D8E8;

	private static final Queue<String> PENDING = new ConcurrentLinkedQueue<>();

	/** Chunks already read through since the games linked. */
	private static final Set<Long> READ_CHUNKS = new HashSet<>();

	/** The model text for each kind of block seen so far, by the block state's number. */
	private static final Map<Integer, String> MODELS = new ConcurrentHashMap<>();

	/** The kinds of block Subnautica has been sent the model of since the games linked. */
	private static final Set<Integer> SENT_MODELS = ConcurrentHashMap.newKeySet();

	/**
	 * Poured water and lava have no one shape per kind: each block of it is shaped by what is
	 * around it (see BlockModels.describeFluid). So each different shape met is given a
	 * number of its own, from here up (far above any kind of block's), and sent as a model
	 * under that number.
	 */
	private static final int FIRST_FLUID_MODEL = 0x20000000;
	private static final Map<String, Integer> FLUID_MODELS = new ConcurrentHashMap<>();
	private static int nextFluidModel = FIRST_FLUID_MODEL;

	/**
	 * Which shape the water or lava at each place was last sent with, how many places are
	 * using each shape, and each shape's text by its number. Flowing water goes through a
	 * great many shapes; once nothing is using one, Subnautica is told to forget it
	 * ("MODELGONE id").
	 */
	private static final Map<Long, Integer> FLUID_AT = new ConcurrentHashMap<>();
	private static final Map<Integer, Integer> FLUID_USES = new ConcurrentHashMap<>();
	private static final Map<Integer, String> FLUID_SHAPES = new ConcurrentHashMap<>();

	private ClientBlocks() {
	}

	/** Subnautica (re)connected or went away: it has no blocks, so start the list again. */
	public static void reset() {
		PENDING.clear();
		READ_CHUNKS.clear();
		SENT_MODELS.clear();

		// Nothing with a moving picture is being shown any more, so the atlas needn't be kept
		// up to date; it is switched on again when such a block is next described.
		OverlayFile.atlasAnimated = false;
		FLUID_MODELS.clear();
		FLUID_AT.clear();
		FLUID_USES.clear();
		FLUID_SHAPES.clear();
		nextFluidModel = FIRST_FLUID_MODEL;
	}

	/** One place fewer is using this shape of water or lava. With none left, it is forgotten on both sides. */
	private static void doneWithFluidShape(int id) {
		Integer left = FLUID_USES.merge(id, -1, Integer::sum);

		if (left != null && left > 0) {
			return;
		}

		FLUID_USES.remove(id);
		String shape = FLUID_SHAPES.remove(id);

		if (shape != null) {
			FLUID_MODELS.remove(shape);
		}

		SENT_MODELS.remove(id);
		PENDING.add("MODELGONE " + id);
	}

	private static boolean linked() {
		MinecraftClient client = MinecraftClient.getInstance();
		return client.player != null && RemoteCollision.appliesTo(client.player);
	}

	/** A block changed in this game's copy of the world. */
	public static void onChanged(World world, BlockPos pos, BlockState state) {
		if (!world.isClient || world.getRegistryKey() != MovementBridge.OCEAN_VOID || !linked()) {
			return;
		}

		// Only inside chunks already read: the rest are read whole when their turn comes.
		if (READ_CHUNKS.contains(ChunkPos.toLong(pos.getX() >> 4, pos.getZ() >> 4))) {
			queue(world, pos, state);
		}

		// Water and lava next to the change (corner to corner counts) are shaped by it, so
		// they are looked at again.
		BlockPos.Mutable near = new BlockPos.Mutable();
		lookingAgain = true;

		for (int dx = -1; dx <= 1; dx++) {
			for (int dy = -1; dy <= 1; dy++) {
				for (int dz = -1; dz <= 1; dz++) {
					if (dx == 0 && dy == 0 && dz == 0) {
						continue;
					}

					near.set(pos.getX() + dx, pos.getY() + dy, pos.getZ() + dz);

					if (!READ_CHUNKS.contains(ChunkPos.toLong(near.getX() >> 4, near.getZ() >> 4))) {
						continue;
					}

					BlockState beside = world.getBlockState(near);

					if (beside.getBlock() instanceof FluidBlock) {
						queue(world, near.toImmutable(), beside);
					}
				}
			}
		}

		lookingAgain = false;
	}

	/** True while water or lava is being looked at again because something next to it changed, not because it did. */
	private static boolean lookingAgain;

	/** A chunk dropped out of this game's copy of the world. */
	public static void onChunkUnload(ChunkPos chunk) {
		if (READ_CHUNKS.remove(chunk.toLong())) {
			PENDING.add("CHUNKGONE " + chunk.x + " " + chunk.z);

			// Any water or lava that was in it is no longer using its shapes.
			FLUID_AT.entrySet().removeIf(entry -> {
				if ((BlockPos.unpackLongX(entry.getKey()) >> 4) != chunk.x || (BlockPos.unpackLongZ(entry.getKey()) >> 4) != chunk.z) {
					return false;
				}

				doneWithFluidShape(entry.getValue());
				return true;
			});
		}
	}

	/** Someone (this player or another) is breaking a block: the cracks have reached this stage, or -1 for none. */
	public static void onCrack(int breakerId, BlockPos pos, int progress) {
		if (!linked()) {
			return;
		}

		int stage = progress >= 0 && progress <= 9 ? progress : -1;
		ClientLink.toSubnautica(String.format(Locale.ROOT, "CRACK %d %d %d %d %d", pos.getX(), pos.getY(), pos.getZ(), stage, breakerId));
	}

	/** The world the chunks were read from, to notice when it is replaced by another. */
	private static java.lang.ref.WeakReference<ClientWorld> lastWorld;

	/** Called every client tick. */
	public static void tick(MinecraftClient client) {
		if (client.world == null || !linked()) {
			return;
		}

		// A different world from last time (after dying, or coming back into the ocean void):
		// what was read was the old world's. Subnautica is told to forget those chunks, and
		// they are read afresh.
		if (lastWorld == null || lastWorld.get() != client.world) {
			if (lastWorld != null) {
				for (long chunk : READ_CHUNKS) {
					PENDING.add("CHUNKGONE " + ChunkPos.getPackedX(chunk) + " " + ChunkPos.getPackedZ(chunk));
				}

				READ_CHUNKS.clear();

				for (int id : FLUID_USES.keySet()) {
					SENT_MODELS.remove(id);
					PENDING.add("MODELGONE " + id);
				}

				FLUID_AT.clear();
				FLUID_USES.clear();
				FLUID_SHAPES.clear();
				FLUID_MODELS.clear();
			}

			lastWorld = new java.lang.ref.WeakReference<>(client.world);
		}

		readNearbyChunks(client);

		String line;
		int sent = 0;

		while (sent < MOST_LINES_PER_TICK && (line = PENDING.poll()) != null) {
			ClientLink.toSubnautica(line);
			sent++;
		}
	}

	private static void readNearbyChunks(MinecraftClient client) {
		ClientWorld world = client.world;
		ChunkPos centre = client.player.getChunkPos();

		for (int cx = centre.x - CHUNK_REACH; cx <= centre.x + CHUNK_REACH; cx++) {
			for (int cz = centre.z - CHUNK_REACH; cz <= centre.z + CHUNK_REACH; cz++) {
				long key = ChunkPos.toLong(cx, cz);

				if (READ_CHUNKS.contains(key)) {
					continue;
				}

				// Null means this game hasn't been sent the chunk yet: try again on a later tick.
				WorldChunk chunk = world.getChunkManager().getWorldChunk(cx, cz);

				if (chunk == null || chunk.isEmpty()) {
					continue;
				}

				READ_CHUNKS.add(key);
				ChunkSection[] sections = chunk.getSectionArray();

				// A chunk is stored as a stack of 16-block-tall sections. Empty ones (nearly
				// all of them here) are skipped in one step.
				for (int i = 0; i < sections.length; i++) {
					ChunkSection section = sections[i];

					if (section == null || section.isEmpty()) {
						continue;
					}

					int baseY = chunk.sectionIndexToCoord(i) << 4;

					for (int y = 0; y < 16; y++) {
						for (int z = 0; z < 16; z++) {
							for (int x = 0; x < 16; x++) {
								BlockState state = section.getBlockState(x, y, z);

								if (!state.isAir()) {
									BlockPos pos = new BlockPos((cx << 4) + x, baseY + y, (cz << 4) + z);

									if (isShown(world, pos, state)) {
										queue(world, pos, state);
									}
								}
							}
						}
					}
				}
			}
		}
	}

	/** The text describing a block's model (see BlockModels), worked out once per kind of block. Empty for none. */
	private static String modelOf(BlockState state, int id) {
		return MODELS.computeIfAbsent(id, key -> {
			try {
				return BlockModels.describe(state);
			} catch (RuntimeException e) {
				SubnauticaLink.LOGGER.warn("Could not read the model of {}", state, e);
				return "";
			}
		});
	}

	/**
	 * The light a kind of block gives off, as "range strength RRGGBB flicker", or null for none.
	 *
	 * <p>Minecraft only has one number for this: a level from 1 to 15, which is also how many
	 * blocks the light reaches. Subnautica's lights have a reach, a strength and a colour, so
	 * the level sets the reach (a block is a metre) and the kind of block sets the rest, to
	 * look like what it is in Minecraft: flames are warm, dim-edged and flicker; a sea lantern
	 * is a cold white that carries further; soul fire is blue; redstone is red.
	 */
	static String lightOf(BlockState state) {
		int level = state.getLuminance();

		if (level <= 0) {
			return null;
		}

		String name = Registries.BLOCK.getId(state.getBlock()).getPath();
		int colour = 0xFFF1D6;   // a soft warm white, for anything not listed
		double reach = 1.0;      // metres of reach for each level of light
		double strength = 1.0;
		boolean flicker = false;

		if (name.contains("soul")) {
			// Soul torches, lanterns, fire and campfires: blue.
			colour = 0x6FD8FF;
			flicker = !name.contains("lantern");
		} else if (name.contains("redstone_torch") || name.contains("redstone_wall_torch") || name.contains("redstone_ore")) {
			colour = 0xFF3B28;
			strength = 0.8;
		} else if (name.contains("sea_lantern") || name.contains("beacon") || name.contains("conduit") || name.contains("end_rod")) {
			// Cold white, and the furthest reaching.
			colour = 0xE4F3FF;
			reach = 1.4;
			strength = 1.3;
		} else if (name.contains("torch") || name.contains("fire") || name.contains("candle") || name.contains("jack_o")) {
			// Open flames: yellow-orange, close, flickering.
			colour = 0xFFB454;
			reach = 0.8;
			flicker = true;
		} else if (name.contains("lantern") || name.contains("furnace") || name.contains("smoker")) {
			// Flames behind glass or iron: the same colour, steadier.
			colour = 0xFFBE6A;
			reach = 0.85;
		} else if (name.contains("lava") || name.contains("magma")) {
			colour = 0xFF6A1E;
		} else if (name.contains("glowstone") || name.contains("shroomlight") || name.contains("redstone_lamp") || name.contains("ochre")) {
			colour = 0xFFD98A;
			reach = 1.1;
			strength = 1.1;
		} else if (name.contains("verdant") || name.contains("glow_lichen") || name.contains("sea_pickle")) {
			colour = 0xB8FFB0;
		} else if (name.contains("pearlescent") || name.contains("amethyst") || name.contains("portal") || name.contains("crying")) {
			colour = 0xD9A8FF;
		} else if (name.contains("sculk") || name.contains("respawn_anchor")) {
			colour = 0x58C8E8;
		}

		return String.format(Locale.ROOT, "%.1f %.2f %06X %d", level * reach, strength, colour, flicker ? 1 : 0);
	}

	/** Whether Subnautica should be told about this block: anything solid, or with a shape to draw. */
	private static boolean isShown(World world, BlockPos pos, BlockState state) {
		if (state.isAir()) {
			return false;
		}

		// Water and lava: whether any of it shows is found out when it is drawn (see queueFluid).
		if (state.getBlock() instanceof FluidBlock) {
			return true;
		}

		return !state.getCollisionShape(world, pos).isEmpty() || !modelOf(state, Block.getRawIdFromState(state)).isEmpty();
	}

	/** Queues the line for one block of poured water or lava, preceded by its shape if Subnautica hasn't been sent that yet. */
	private static void queueFluid(World world, BlockPos pos, BlockState state) {
		String shape = "";

		try {
			shape = BlockModels.describeFluid(world, pos, state);
		} catch (RuntimeException e) {
			SubnauticaLink.LOGGER.warn("Could not read the shape of {} at {}", state, pos, e);
		}

		long place = pos.asLong();
		Integer was = FLUID_AT.get(place);

		// None of it shows (it is in the middle of a pool).
		if (shape.isEmpty()) {
			// Subnautica is told there is nothing to draw here, unless it already knows: this
			// place had no shape before, and it is only being looked at again for a neighbour's
			// sake. (When the place itself has just changed, whatever block was here has to go.)
			if (was != null || !lookingAgain) {
				PENDING.add(String.format(Locale.ROOT, "BLOCK %d %d %d air", pos.getX(), pos.getY(), pos.getZ()));
			}

			if (was != null) {
				FLUID_AT.remove(place);
				doneWithFluidShape(was);
			}

			return;
		}

		int id = FLUID_MODELS.computeIfAbsent(shape, key -> nextFluidModel++);

		// The same shape as it already has there (something changed next to it, but nothing
		// that shows): nothing to send.
		if (was != null && was == id) {
			return;
		}

		FLUID_SHAPES.put(id, shape);
		FLUID_USES.merge(id, 1, Integer::sum);
		FLUID_AT.put(place, id);

		if (SENT_MODELS.add(id)) {
			PENDING.add("MODEL " + id + " " + shape);

			// Water's and lava's pictures move: from now on the atlas is sent again as it changes.
			OverlayFile.atlasAnimated = true;

			// Lava glows.
			String light = lightOf(state);

			if (light != null) {
				PENDING.add("LIGHT " + id + " " + light);
			}
		}

		int colour = state.getMapColor(world, pos).color;
		PENDING.add(String.format(Locale.ROOT, "BLOCK %d %d %d %d %06X -", pos.getX(), pos.getY(), pos.getZ(), id, (colour == 0 ? FALLBACK_COLOUR : colour) & 0xFFFFFF));

		// Only now is the shape it had before finished with: the block has been given its new one.
		if (was != null) {
			doneWithFluidShape(was);
		}
	}

	/** Queues the line for one block, preceded by its model if Subnautica hasn't been sent that yet. */
	private static void queue(World world, BlockPos pos, BlockState state) {
		if (state.getBlock() instanceof FluidBlock) {
			queueFluid(world, pos, state);
			return;
		}

		// Whatever is here now, it isn't water or lava any more. (The shape it had is only
		// finished with once Subnautica has been told what is here instead.)
		Integer was = FLUID_AT.remove(pos.asLong());
		queueOther(world, pos, state);

		if (was != null) {
			doneWithFluidShape(was);
		}
	}

	/** Queues the line for one block that isn't water or lava. */
	private static void queueOther(World world, BlockPos pos, BlockState state) {
		if (!isShown(world, pos, state)) {
			PENDING.add(String.format(Locale.ROOT, "BLOCK %d %d %d air", pos.getX(), pos.getY(), pos.getZ()));
			return;
		}

		int id = Block.getRawIdFromState(state);

		if (SENT_MODELS.add(id)) {
			String model = modelOf(state, id);
			PENDING.add("MODEL " + id + " " + model);

			// Its picture moves (fire, a sea lantern): from now on the atlas is sent again as it changes.
			if (model.startsWith("~")) {
				OverlayFile.atlasAnimated = true;
			}

			// And, once per kind of block, the light it gives off, if any.
			String light = lightOf(state);

			if (light != null) {
				PENDING.add("LIGHT " + id + " " + light);
			}
		}

		// The colour is the fallback for blocks with no model, and until the textures arrive.
		int colour = state.getMapColor(world, pos).color;

		if (colour == 0) {
			colour = FALLBACK_COLOUR;
		}

		// A block drawn by code of its own (a chest, a bed, a sign, a block a piston is
		// pushing) is caught and sent as it really looks (see ClientAvatars), so Subnautica
		// mustn't also draw a plain cube for it. Nor for a block Minecraft doesn't draw at all
		// (a barrier). A colour beyond the six digits a colour can have says so.
		if (state.getRenderType() != BlockRenderType.MODEL && modelOf(state, id).isEmpty()) {
			colour = 0x1000000;
		}

		// The solid part of the block, as a box: a full cube for most, half height for a slab.
		// "-" for blocks you can walk through.
		VoxelShape shape = state.getCollisionShape(world, pos);
		String solid = "-";

		if (!shape.isEmpty()) {
			Box box = shape.getBoundingBox();
			solid = String.format(Locale.ROOT, "%.3f %.3f %.3f %.3f %.3f %.3f", box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ);
		}

		PENDING.add(String.format(Locale.ROOT, "BLOCK %d %d %d %d %06X %s", pos.getX(), pos.getY(), pos.getZ(), id, colour & 0x1FFFFFF, solid));
	}
}
