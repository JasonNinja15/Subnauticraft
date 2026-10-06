package com.example.subnauticalink;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.mojang.blaze3d.platform.GlStateManager;
import org.lwjgl.opengl.GL11;
import org.lwjgl.system.MemoryUtil;

import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.PistonBlockEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.block.entity.BlockEntityRenderer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.chunk.WorldChunk;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ExperienceOrbEntity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.TntEntity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.projectile.ProjectileEntity;
import net.minecraft.entity.passive.PigEntity;
import net.minecraft.client.option.Perspective;
import net.minecraft.client.render.LightmapTextureManager;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.texture.SpriteAtlasTexture;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;

/**
 * Shows the players' Minecraft characters in Subnautica. Client only.
 *
 * <p>Rather than describing a player piece by piece (a head here, an arm at this angle, a
 * sword in that hand), this asks Minecraft to draw each player exactly as it normally would,
 * and catches the drawing on its way to the screen. Minecraft draws everything as flat
 * four-cornered faces, each showing part of a picture. Catching those gives the whole
 * character as it stands this instant: the pose of every limb, the skin, armour, whatever is
 * in the hands, a cape, an arrow stuck in the back. Whatever Minecraft would have drawn.
 *
 * <p>That is done twenty times a second for every player nearby and sent to Subnautica, which
 * draws the same faces in its own world and glides between one pose and the next.
 *
 * <p>The lines sent:
 * <ul>
 *   <li>"SKIN n w h pixels": picture number n (a skin, a suit of armour). Sent once each.
 *       Picture 0 is the block atlas, which Subnautica already has; held items use it.</li>
 *   <li>"AVSHAPE key faces": the parts of a character that stay the same from one moment to
 *       the next: which picture each face shows, where on it, and its tint. Sent once for
 *       each different look (it changes when the player changes what they hold or wear).</li>
 *   <li>"AV id key x y z flags corners": player number id is at x y z, looks like shape
 *       "key", and the corners of the faces are here just now. Flags: 1 = flashing red from
 *       being hurt, 2 = this is the player whose game this is, 4 = a mob, not a player,
 *       8 = flashing white (a creeper about to explode).</li>
 *   <li>"AVGONE id": that player is no longer there to draw.</li>
 * </ul>
 * Everything else Minecraft draws by code of its own goes the same way: its mobs, and the
 * things that are neither mob nor block (item frames, paintings, armour stands, minecarts,
 * boats, falling sand). Those that mostly stand still are only sent again when they change,
 * and twice a second besides so Subnautica knows they are still there.
 * Pixels, faces and corners are packed numbers written as text ("base64"), which is far
 * shorter than writing each number out.
 */
public final class ClientAvatars {
	/** Players within this many blocks are shown. */
	private static final double SHOWN_WITHIN = 64.0;

	/** Corner positions are sent as whole numbers of this many to a block. */
	private static final float UNITS = 512.0F;

	/** A corner further than this from its player (in blocks) isn't part of the player; that face is left out. */
	private static final float FURTHEST = 60.0F;

	/** A beam is cut off this many blocks up (see FaceCollector). Subnautica looks for corners at this height: keep the two the same. */
	private static final float BEAM_CUT = 59.5F;

	/** Pictures bigger than this many pixels are not sent; faces showing them are left out. */
	private static final long MOST_PIXELS = 1024L * 1024L;

	/** How Minecraft writes a drawing style's picture when asked to describe the style. */
	private static final Pattern PICTURE = Pattern.compile("texture\\[Optional\\[([^\\]]+)\\]");

	/** One caught face: which picture, what tint, and four corners of x, y, z, u, v. */
	private record Face(int picture, int colour, float[] corners) {
	}

	/** The number each picture goes by, or -1 for one that can't be sent. */
	private static final Map<Identifier, Integer> PICTURES = new HashMap<>();
	private static final Map<Identifier, Integer> PICTURE_TRIES = new HashMap<>();

	/** For each of Minecraft's drawing styles met so far, the catcher that collects its faces. */
	private static final Map<RenderLayer, VertexConsumer> CATCHERS = new HashMap<>();

	private static final Set<Long> SENT_SHAPES = new HashSet<>();
	private static final Set<Integer> SHOWN = new HashSet<>();

	/** For each thing that is only sent again when it changes: a number worked out from what was last sent. */
	private static final Map<Integer, Long> LAST_POSE = new HashMap<>();

	/**
	 * Pictures that Minecraft goes on adding to after they are first used: the sheets its
	 * letters are kept on, which fill up as new letters are needed. By number, with a number
	 * worked out from the pixels last sent, so a changed one can be told and sent again.
	 */
	private static final Map<Integer, Identifier> GROWING_PICTURES = new HashMap<>();
	private static final Map<Integer, Long> PICTURE_SUMS = new HashMap<>();

	/** The tick each of those was last looked at again, and how long (in ticks) before one in use is looked at once more. */
	private static final Map<Integer, Integer> PICTURE_CHECKED = new HashMap<>();
	private static final int PICTURES_CHECKED_EVERY = 20;

	/**
	 * How many different looks are sent before both sides forget them all and start again.
	 * Every different sign, framed item and painting is a look of its own, so this is well
	 * above what one place is likely to hold: starting again means sending everything afresh.
	 */
	private static final int MOST_LOOKS = 1500;

	/** How often (in ticks) something that hasn't changed is sent anyway. Subnautica forgets what it hasn't heard of for a while. */
	private static final int SENT_AT_LEAST_EVERY = 10;
	private static int ticks;
	private static final Catcher CATCHER = new Catcher();
	private static int stylesNoted;
	private static boolean failureNoted;

	private ClientAvatars() {
	}

	/** Subnautica (re)connected or went away: it has nothing, so start again. */
	public static void reset() {
		PICTURES.clear();
		PICTURE_TRIES.clear();
		CATCHERS.clear();
		SENT_SHAPES.clear();
		SHOWN.clear();
		LAST_POSE.clear();
		GROWING_PICTURES.clear();
		PICTURE_SUMS.clear();
		PICTURE_CHECKED.clear();
		BLOCKS_SHOWN.clear();
		BLOCKS_NEAR.clear();
		blockTicks = 0;
	}

	/** "VIEW n": F5 was pressed in Subnautica. 0 is through the eyes, 1 from behind, 2 from the front. */
	public static void setView(int view) {
		Perspective[] all = Perspective.values();
		MinecraftClient.getInstance().options.setPerspective(all[Math.floorMod(view, all.length)]);
	}

	/** Called every client tick, once everyone has moved. */
	public static void tick(MinecraftClient client) {
		if (client.world == null || client.player == null || !RemoteCollision.appliesTo(client.player)) {
			return;
		}

		// Minecraft's drawing code expects to have drawn at least one frame of this world.
		if (!client.gameRenderer.getCamera().isReady()) {
			return;
		}

		ticks++;
		boolean throughOwnEyes = client.options.getPerspective().isFirstPerson();
		Set<Integer> still = new HashSet<>();

		for (AbstractClientPlayerEntity player : client.world.getPlayers()) {
			boolean self = player == client.player;

			// Your own character is only drawn when you are looking at it from outside.
			if ((self && throughOwnEyes) || !player.isAlive() || player.isSpectator() || player.isInvisible()
					|| player.squaredDistanceTo(client.player) > SHOWN_WITHIN * SHOWN_WITHIN) {
				continue;
			}

			if (describe(client, player, self, false, true)) {
				still.add(player.getId());
			}
		}

		// Minecraft's mobs in the ocean void (the Drowned) are drawn the same way. A dying one
		// is kept until Minecraft removes it, so it is seen to fall over. So is everything else
		// Minecraft draws by code of its own: boats, item frames, paintings, armour stands,
		// minecarts, falling sand. (Dropped items, lit TNT and projectiles are shown another
		// way: see ClientEntities. Experience orbs aren't shown: they change colour every
		// tick, and each colour would have to be sent as a new look.)
		for (Entity entity : client.world.getEntities()) {
			if (entity instanceof PlayerEntity || entity.squaredDistanceTo(client.player) > SHOWN_WITHIN * SHOWN_WITHIN) {
				continue;
			}

			// The boat this player is sitting in is sent every tick, as the player is: Subnautica moves the two together.
			boolean always = entity == client.player.getVehicle() || Math.floorMod(ticks + entity.getId(), SENT_AT_LEAST_EVERY) == 0;

			if (entity instanceof MobEntity mob) {
				if (!mob.isInvisible() && describe(client, mob, false, true, always)) {
					still.add(mob.getId());
				}
			} else if (!(entity instanceof ItemEntity || entity instanceof TntEntity || entity instanceof ProjectileEntity || entity instanceof ExperienceOrbEntity)
					&& entity.isAlive() && describe(client, entity, false, true, always)) {
				still.add(entity.getId());
			}
		}

		for (int id : SHOWN) {
			if (!still.contains(id)) {
				ClientLink.toSubnautica("AVGONE " + id);
				LAST_POSE.remove(id);
			}
		}

		SHOWN.clear();
		SHOWN.addAll(still);

		describeBlockEntities(client);
	}

	// ---- Chests, beds, signs and the like -------------------------------------------------------
	//
	// Most blocks are drawn from a fixed list of faces (see BlockModels). A few are drawn the way
	// creatures are, by code of their own: chests (the lid swings), beds, signs, banners, shulker
	// boxes, heads, the book over an enchanting table, a bell, and a block being pushed by a
	// piston. Those are caught exactly as players and mobs are, and sent the same way, so they
	// look as they do in Minecraft.
	//
	// The ones near the player are caught every tick, so anything that moves is seen moving as
	// smoothly as it does in Minecraft, but one is only sent when it has changed since it was
	// last sent (and twice a second regardless). Further off they are caught twice a second.

	/** Blocks of this kind within this many blocks are shown, and within the second are watched every tick. */
	private static final double BLOCKS_WITHIN = 64.0;
	private static final double BLOCKS_WATCHED_WITHIN = 32.0;

	/** That distance in chunks, rounded up. */
	private static final int BLOCK_CHUNKS = 4;

	/** The number each such block goes by in what is sent: far above any creature's. */
	private static final Map<BlockPos, Integer> BLOCK_NUMBERS = new HashMap<>();
	private static final List<BlockEntity> BLOCKS_NEAR = new ArrayList<>();
	private static final Set<Integer> BLOCKS_SHOWN = new HashSet<>();
	private static int nextBlockNumber = 1_000_000_000;
	private static int blockTicks;

	private static void describeBlockEntities(MinecraftClient client) {
		boolean all = blockTicks++ % SENT_AT_LEAST_EVERY == 0;

		// Which ones are there is looked up afresh every tick: a block pushed by a piston is
		// only "being pushed" for two or three ticks.
		BLOCKS_NEAR.clear();
		int chunkX = client.player.getChunkPos().x;
		int chunkZ = client.player.getChunkPos().z;

		for (int x = chunkX - BLOCK_CHUNKS; x <= chunkX + BLOCK_CHUNKS; x++) {
			for (int z = chunkZ - BLOCK_CHUNKS; z <= chunkZ + BLOCK_CHUNKS; z++) {
				WorldChunk chunk = client.world.getChunkManager().getWorldChunk(x, z);

				if (chunk == null) {
					continue;
				}

				for (BlockEntity block : chunk.getBlockEntities().values()) {
					if (!block.isRemoved() && block.getWorld() == client.world
							&& block.getPos().getSquaredDistance(client.player.getPos()) <= BLOCKS_WITHIN * BLOCKS_WITHIN
							&& client.getBlockEntityRenderDispatcher().get(block) != null) {
						BLOCKS_NEAR.add(block);
					}
				}
			}
		}

		if (all && BLOCK_NUMBERS.size() > 4000) {
			BLOCK_NUMBERS.clear();
		}

		Set<Integer> still = new HashSet<>();

		for (BlockEntity block : BLOCKS_NEAR) {
			int number = BLOCK_NUMBERS.computeIfAbsent(block.getPos().toImmutable(), pos -> nextBlockNumber++);
			boolean watched = block.getPos().getSquaredDistance(client.player.getPos()) <= BLOCKS_WATCHED_WITHIN * BLOCKS_WATCHED_WITHIN;

			// Each is sent at least twice a second, but not all in the same tick: a room full of chests would arrive as one lump.
			boolean due = Math.floorMod(blockTicks + number, SENT_AT_LEAST_EVERY) == 0;

			if (!due && !watched) {
				// Not looked at this tick; it stays as it was.
				if (BLOCKS_SHOWN.contains(number)) {
					still.add(number);
				}

				continue;
			}

			// A block a piston has just started to push is first caught where it started from
			// (Minecraft has already moved it on half a block by now), so Subnautica shows
			// the whole of the push and not just the second half of it.
			if (block instanceof PistonBlockEntity && !BLOCKS_SHOWN.contains(number)) {
				describeBlock(client, block, number, true, 0.0F);
			}

			if (describeBlock(client, block, number, due, 1.0F)) {
				still.add(number);
			}
		}

		// One that has gone (a chest broken, a piston's push finished) is taken away at once.
		for (int number : BLOCKS_SHOWN) {
			if (!still.contains(number)) {
				ClientLink.toSubnautica("AVGONE " + number);
				LAST_POSE.remove(number);
			}
		}

		BLOCKS_SHOWN.clear();
		BLOCKS_SHOWN.addAll(still);
	}

	/** Catches one such block as Minecraft would draw it this instant and sends the result. */
	private static boolean describeBlock(MinecraftClient client, BlockEntity block, int number, boolean always, float tickDelta) {
		BlockEntityRenderer<BlockEntity> renderer = client.getBlockEntityRenderDispatcher().get(block);

		if (renderer == null) {
			return false;
		}

		// The writing on a sign is wanted; nothing else written is.
		CATCHER.begin(1);

		try {
			// Drawn at 0, 0, 0, so every corner comes out measured from the block's low corner.
			// "tickDelta" is how far through the tick to draw it: 1 is as it stands now, 0 as it stood a tick ago.
			renderer.render(block, tickDelta, new MatrixStack(), CATCHER, LightmapTextureManager.MAX_LIGHT_COORDINATE, OverlayTexture.DEFAULT_UV);
		} catch (RuntimeException e) {
			if (!blockFailureNoted) {
				blockFailureNoted = true;
				SubnauticaLink.LOGGER.warn("Could not catch the drawing of the block at {}", block.getPos(), e);
			}

			CATCHER.end();
			return false;
		}

		List<Face> faces = CATCHER.end();

		if (faces.isEmpty()) {
			return false;
		}

		// 4: not a player (so nothing is done about divers or seats for it). 64: it moves at an
		// even pace from one tick's pose to the next, as Minecraft draws these, not easing in
		// and out as a creature's limbs do. 128: it has a beam (a beacon's) that goes on up
		// out of sight, further than can be sent; Subnautica carries it on.
		send(number, block.getPos().getX(), block.getPos().getY(), block.getPos().getZ(), 4 | 64 | (CATCHER.beam ? 128 : 0), faces, always);
		return true;
	}

	private static boolean blockFailureNoted;

	/** Catches one player as Minecraft would draw them this instant and sends the result. */
	private static boolean describe(MinecraftClient client, Entity player, boolean self, boolean mob, boolean always) {
		// Nothing written is wanted over anything alive (that would be its name). On a thing,
		// all of it is: the map in an item frame is drawn as writing is.
		CATCHER.begin(player instanceof LivingEntity ? 0 : 2);

		try {
			// Drawn at 0, 0, 0, so every corner comes out measured from the player's feet.
			client.getEntityRenderDispatcher().render(player, 0.0, 0.0, 0.0, player.getYaw(1.0F), 1.0F,
					new MatrixStack(), CATCHER, LightmapTextureManager.MAX_LIGHT_COORDINATE);
		} catch (RuntimeException e) {
			if (!failureNoted) {
				failureNoted = true;
				SubnauticaLink.LOGGER.warn("Could not catch the drawing of {}", player.getName().getString(), e);
			}

			CATCHER.end();
			return false;
		}

		List<Face> faces = CATCHER.end();

		if (faces.isEmpty()) {
			return false;
		}

		// 16: sitting on something (in one of Subnautica's vehicles), so Subnautica puts them on its seat.
		int flags = (CATCHER.hurt ? 1 : 0) | (self ? 2 : 0) | (mob ? 4 : 0) | (CATCHER.white ? 8 : 0) | (!mob && player.getVehicle() instanceof PigEntity ? 16 : 0)
				// 32: the boat this game's own player is sitting in, which Subnautica moves in step with the camera.
				| (client.player != null && player == client.player.getVehicle() ? 32 : 0);
		send(player.getId(), player.getX(), player.getY(), player.getZ(), flags, faces, always);
		return true;
	}

	/**
	 * Packs a set of caught faces and sends them: the look first if Subnautica hasn't had it,
	 * then where everything is. Unless "always" is set, nothing is sent if it would only
	 * repeat what was last sent for this thing.
	 */
	private static void send(int id, double x, double y, double z, int flags, List<Face> faces, boolean always) {
		// The lasting part (pictures, tints, places on the pictures) and the passing part
		// (where the corners are just now), packed separately.
		ByteBuffer shape = ByteBuffer.allocate(faces.size() * 38).order(ByteOrder.LITTLE_ENDIAN);
		ByteBuffer places = ByteBuffer.allocate(faces.size() * 24).order(ByteOrder.LITTLE_ENDIAN);

		for (Face face : faces) {
			shape.putShort((short) face.picture());
			shape.put((byte) (face.colour() >> 16));
			shape.put((byte) (face.colour() >> 8));
			shape.put((byte) face.colour());
			shape.put((byte) 0);

			for (int corner = 0; corner < 4; corner++) {
				int at = corner * 5;
				shape.putFloat(face.corners()[at + 3]);
				shape.putFloat(face.corners()[at + 4]);
				places.putShort((short) Math.round(face.corners()[at] * UNITS));
				places.putShort((short) Math.round(face.corners()[at + 1] * UNITS));
				places.putShort((short) Math.round(face.corners()[at + 2] * UNITS));
			}
		}

		// The lasting part is named by a number worked out from its contents, so the same
		// look always gets the same name and is only ever sent once.
		long hash = 0xCBF29CE484222325L;

		for (byte b : shape.array()) {
			hash = (hash ^ (b & 0xFF)) * 0x100000001B3L;
		}

		if (SENT_SHAPES.size() > MOST_LOOKS) {
			// Enough looks to be worth forgetting the old ones, on both sides.
			SENT_SHAPES.clear();
			LAST_POSE.clear();
			ClientLink.toSubnautica("AVCLEAR");

			// And every chest and bed is described afresh on the next tick, not up to half a second on.
			blockTicks = 0;
		}

		// The same again as last time? (The look, every corner, the place and the flags all go into one number.)
		long pose = hash;

		for (byte b : places.array()) {
			pose = (pose ^ (b & 0xFF)) * 0x100000001B3L;
		}

		pose = (pose ^ Double.doubleToLongBits(x)) * 0x100000001B3L;
		pose = (pose ^ Double.doubleToLongBits(y)) * 0x100000001B3L;
		pose = (pose ^ Double.doubleToLongBits(z)) * 0x100000001B3L;
		pose = (pose ^ flags) * 0x100000001B3L;

		Long last = LAST_POSE.put(id, pose);

		if (!always && last != null && last == pose && SENT_SHAPES.contains(hash)) {
			return;
		}

		String key = Long.toHexString(hash);

		// Something drawn with a picture Minecraft goes on changing (a sheet of letters, a map
		// being filled in): the picture is looked at again, and sent again if it has changed.
		// Always before a new look is sent, since that may use letters added a moment ago;
		// otherwise once a second for as long as the picture is in use.
		if (!GROWING_PICTURES.isEmpty()) {
			boolean newLook = !SENT_SHAPES.contains(hash);

			for (Face face : faces) {
				Identifier picture = GROWING_PICTURES.get(face.picture());

				if (picture == null) {
					continue;
				}

				Integer checked = PICTURE_CHECKED.get(face.picture());

				if (checked == null || (checked != ticks && (newLook || ticks - checked >= PICTURES_CHECKED_EVERY))) {
					PICTURE_CHECKED.put(face.picture(), ticks);
					sendPicture(picture, face.picture(), true);
				}
			}
		}

		if (SENT_SHAPES.add(hash)) {
			ClientLink.toSubnautica("AVSHAPE " + key + " " + Base64.getEncoder().encodeToString(shape.array()));
		}

		ClientLink.toSubnautica(String.format(Locale.ROOT, "AV %d %s %.3f %.3f %.3f %d %s", id, key,
				x, y, z, flags, Base64.getEncoder().encodeToString(places.array())));
	}

	/**
	 * The number a picture goes by, sending the picture to Subnautica the first time it is
	 * met. Returns -1 for one that can't be sent.
	 */
	private static int pictureNumber(Identifier id) {
		if (id.equals(SpriteAtlasTexture.BLOCK_ATLAS_TEXTURE)) {
			return 0;
		}

		Integer known = PICTURES.get(id);

		if (known != null) {
			return known;
		}

		int number = PICTURES.size() + 1;
		int sent = sendPicture(id, number, false);

		if (sent > 0) {
			PICTURES.put(id, number);
			return number;
		}

		// Not loaded yet (a skin still being fetched, say)? Try again a few times.
		if (sent < 0 || PICTURE_TRIES.merge(id, 1, Integer::sum) > 2000) {
			PICTURES.put(id, -1);
		}

		return -1;
	}

	/**
	 * Sends a picture to Subnautica as "SKIN number w h pixels". Minecraft keeps its pictures
	 * on the graphics card, so it is read back from there. With "onlyIfChanged", nothing is
	 * sent if the pixels are the same as the last time this was called for the picture.
	 * Returns 1 if it was sent (or needn't be), 0 if the picture isn't loaded yet, -1 if it
	 * can't be sent at all.
	 */
	private static int sendPicture(Identifier id, int number, boolean onlyIfChanged) {
		ByteBuffer pixels = null;

		try {
			int texture = MinecraftClient.getInstance().getTextureManager().getTexture(id).getGlId();
			GlStateManager._bindTexture(texture);
			int width = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_WIDTH);
			int height = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_HEIGHT);

			if (width <= 0 || height <= 0) {
				return 0;
			}

			if ((long) width * height > MOST_PIXELS) {
				SubnauticaLink.LOGGER.info("The picture {} is too big to send ({} x {}); things drawn with it are left out", id, width, height);
				return -1;
			}

			int bytes = width * height * 4;
			pixels = MemoryUtil.memAlloc(bytes);
			GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 1);
			GL11.glPixelStorei(GL11.GL_PACK_ROW_LENGTH, 0);
			GL11.glPixelStorei(GL11.GL_PACK_SKIP_ROWS, 0);
			GL11.glPixelStorei(GL11.GL_PACK_SKIP_PIXELS, 0);
			GL11.glGetTexImage(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixels);

			byte[] copy = new byte[bytes];
			pixels.get(0, copy);

			long sum = 0xCBF29CE484222325L;

			for (byte b : copy) {
				sum = (sum ^ (b & 0xFF)) * 0x100000001B3L;
			}

			Long before = PICTURE_SUMS.put(number, sum);

			if (onlyIfChanged && before != null && before == sum) {
				return 1;
			}

			ClientLink.toSubnautica("SKIN " + number + " " + width + " " + height + " " + Base64.getEncoder().encodeToString(copy));

			if (before == null) {
				SubnauticaLink.LOGGER.info("Sent the picture {} ({} x {}) to Subnautica as number {}", id, width, height, number);
			}

			return 1;
		} catch (RuntimeException e) {
			SubnauticaLink.LOGGER.warn("Could not read the picture {}", id, e);
			return -1;
		} finally {
			if (pixels != null) {
				MemoryUtil.memFree(pixels);
			}
		}
	}

	/**
	 * What Minecraft's drawing code is handed in place of the screen. Minecraft asks it for
	 * somewhere to draw each style of thing (see {@link #getBuffer}); it hands back a
	 * collector that keeps the faces, or one that throws them away for styles that aren't
	 * wanted (name tags, shadows, the shimmer on enchanted things).
	 */
	private static final class Catcher implements VertexConsumerProvider {
		private final List<Face> faces = new ArrayList<>();
		private final List<FaceCollector> used = new ArrayList<>();
		boolean hurt;
		boolean white;

		/** How much of what is drawn as writing is wanted: 0 none, 1 the writing on signs, 2 all of it. */
		int text;

		/** Whether part of a beam reached further than can be sent, and was cut short. */
		boolean beam;

		void begin(int text) {
			this.text = text;
			this.beam = false;
			this.faces.clear();
			this.used.clear();
			this.hurt = false;
			this.white = false;
		}

		List<Face> end() {
			for (FaceCollector collector : this.used) {
				collector.finish();
			}

			this.used.clear();
			return this.faces;
		}

		@Override
		public VertexConsumer getBuffer(RenderLayer layer) {
			VertexConsumer catcher = CATCHERS.get(layer);

			if (catcher == null) {
				if (CATCHERS.size() > 512) {
					CATCHERS.clear();
				}

				catcher = this.catcherFor(layer);

				// A picture that isn't loaded yet is asked for again next time.
				if (catcher != null) {
					CATCHERS.put(layer, catcher);
				} else {
					catcher = Discard.INSTANCE;
				}
			}

			if (catcher instanceof FaceCollector collector) {
				// Writing, where it isn't wanted (a name over a head).
				if (collector.text > this.text) {
					return Discard.INSTANCE;
				}

				if (!collector.inUse) {
					collector.inUse = true;
					this.used.add(collector);
				}
			}

			return catcher;
		}

		/** Decides what to do with one drawing style. Returns null for "not ready; ask again". */
		private VertexConsumer catcherFor(RenderLayer layer) {
			// Minecraft describes a style like this:
			// RenderType[entity_translucent:CompositeState[[texture[Optional[minecraft:skins/abc]...
			String description = layer.toString();

			if (stylesNoted < 16) {
				stylesNoted++;
				SubnauticaLink.LOGGER.info("Player drawing style: {}", description.length() > 160 ? description.substring(0, 160) : description);
			}

			int colon = description.indexOf(':');
			String name = colon > 0 ? description.substring(0, colon) : description;

			// Writing comes in several styles. Two are kept: the one signs are written in (1),
			// and the plain one (2), which maps in item frames are drawn in, and names. The
			// rest are backgrounds and see-through-walls copies.
			int text = name.endsWith("text_polygon_offset") ? 1 : name.equals("text") || name.endsWith("[text") ? 2 : 0;

			// (The shimmer on enchanted things gets a thrower-away of its own: Minecraft draws
			// the shimmer and the thing itself as a pair, and refuses a pair that is one and the same.)
			if (name.contains("glint")) {
				return Discard.GLINT;
			}

			if (layer.getDrawMode() != VertexFormat.DrawMode.QUADS || name.contains("shadow")
					|| (text == 0 && name.contains("text")) || name.contains("outline") || name.contains("swirl")) {
				return Discard.INSTANCE;
			}

			Matcher picture = PICTURE.matcher(description);
			Identifier id = picture.find() ? Identifier.tryParse(picture.group(1)) : null;

			if (id == null) {
				return Discard.INSTANCE;
			}

			int number = pictureNumber(id);

			if (number >= 0) {
				if (text != 0 && number > 0) {
					GROWING_PICTURES.put(number, id);
				}

				// A beam (a guardian's) slides its picture along and changes colour every tick.
				// Sent as it is, that would be a new look every tick; see FaceCollector.
				return new FaceCollector(this, number, text, id.getPath().contains("_beam"));
			}

			return PICTURES.containsKey(id) ? Discard.INSTANCE : null;
		}
	}

	/** Collects the corners Minecraft draws in one style, four at a time, into faces. */
	private static final class FaceCollector implements VertexConsumer {
		private final Catcher catcher;
		private final int picture;
		private final float[] corners = new float[20];
		private int count;
		private boolean pending;
		private int colour = 0xFFFFFFFF;
		private float x, y, z, u, v;
		boolean inUse;

		/** 0 for anything but writing; otherwise which style of writing (see Catcher.catcherFor). */
		final int text;

		/**
		 * For a beam: its picture is laid over each face whole instead of sliding along, and
		 * its colour is kept to a few steps, so the look only changes now and then.
		 */
		final boolean steady;

		FaceCollector(Catcher catcher, int picture, int text, boolean steady) {
			this.catcher = catcher;
			this.picture = picture;
			this.text = text;
			this.steady = steady;
		}

		/** Minecraft gives each corner as its position first, then its other details. A new position means the last corner is complete. */
		@Override
		public VertexConsumer vertex(float x, float y, float z) {
			this.commit();
			this.pending = true;
			this.x = x;
			this.y = y;
			this.z = z;
			this.u = 0.0F;
			this.v = 0.0F;
			return this;
		}

		@Override
		public VertexConsumer color(int red, int green, int blue, int alpha) {
			// The tint of the face is taken from its first corner.
			if (this.count == 0) {
				this.colour = ((alpha & 0xFF) << 24) | ((red & 0xFF) << 16) | ((green & 0xFF) << 8) | (blue & 0xFF);
			}

			return this;
		}

		@Override
		public VertexConsumer texture(float u, float v) {
			this.u = u;
			this.v = v;
			return this;
		}

		@Override
		public VertexConsumer overlay(int u, int v) {
			// Minecraft marks a creature that was just hurt this way, to flash it red.
			if (v == 3) {
				this.catcher.hurt = true;
			}

			// And one that should be drawn whitened (a creeper about to go off) this way.
			if (u > 0) {
				this.catcher.white = true;
			}

			return this;
		}

		@Override
		public VertexConsumer light(int u, int v) {
			return this;
		}

		@Override
		public VertexConsumer normal(float x, float y, float z) {
			return this;
		}

		private void commit() {
			if (!this.pending) {
				return;
			}

			this.pending = false;
			int at = this.count * 5;
			this.corners[at] = this.x;
			this.corners[at + 1] = this.y;
			this.corners[at + 2] = this.z;
			this.corners[at + 3] = this.steady ? (this.count == 1 || this.count == 2 ? 0.5F : 0.0F) : this.u;
			this.corners[at + 4] = this.steady ? (this.count >= 2 ? 1.0F : 0.0F) : this.v;

			if (++this.count < 4) {
				return;
			}

			this.count = 0;

			// Nearly see-through faces, and ones that aren't really part of the player, are left out.
			if ((this.colour >>> 24) < 26) {
				return;
			}

			if (this.steady) {
				// A beam is drawn twice: itself, and a wide faint glow round it. Subnautica would
				// draw the glow solid, hiding the beam, so the glow is left out.
				if ((this.colour >>> 24) < 128) {
					return;
				}

				// And a beacon's beam goes up a thousand blocks. It is cut off at the furthest
				// that can be sent, and Subnautica is told to carry it on up (see describeBlock).
				for (int corner = 0; corner < 4; corner++) {
					if (this.corners[corner * 5 + 1] > BEAM_CUT) {
						this.corners[corner * 5 + 1] = BEAM_CUT;
						this.catcher.beam = true;
					}
				}
			}

			for (int corner = 0; corner < 4; corner++) {
				for (int i = 0; i < 3; i++) {
					float value = this.corners[corner * 5 + i];

					if (!(Math.abs(value) < FURTHEST)) {
						return;
					}
				}
			}

			this.catcher.faces.add(new Face(this.picture, (this.steady ? this.colour | 0x3F3F3F : this.colour) & 0xFFFFFF, this.corners.clone()));
		}

		void finish() {
			this.commit();
			this.count = 0;
			this.pending = false;
			this.inUse = false;
		}
	}

	/** Takes whatever Minecraft draws and throws it away. */
	private static final class Discard implements VertexConsumer {
		static final Discard INSTANCE = new Discard();
		static final Discard GLINT = new Discard();

		@Override
		public VertexConsumer vertex(float x, float y, float z) {
			return this;
		}

		@Override
		public VertexConsumer color(int red, int green, int blue, int alpha) {
			return this;
		}

		@Override
		public VertexConsumer texture(float u, float v) {
			return this;
		}

		@Override
		public VertexConsumer overlay(int u, int v) {
			return this;
		}

		@Override
		public VertexConsumer light(int u, int v) {
			return this;
		}

		@Override
		public VertexConsumer normal(float x, float y, float z) {
			return this;
		}
	}
}
