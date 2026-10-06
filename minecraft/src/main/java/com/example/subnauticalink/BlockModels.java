package com.example.subnauticalink;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

import net.minecraft.block.BlockRenderType;
import net.minecraft.block.BlockState;
import net.minecraft.block.FluidBlock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.model.BakedModel;
import net.minecraft.client.render.model.BakedQuad;
import net.minecraft.client.texture.Sprite;
import net.minecraft.client.texture.SpriteAtlasTexture;
import net.minecraft.fluid.FluidState;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.BlockRenderView;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.random.Random;

/**
 * Describes what a block looks like, so Subnautica can build the same shape. Client only.
 *
 * <p>Minecraft draws a block from a "model": a list of flat four-cornered faces ("quads").
 * Each corner has a position inside the block (0 to 1 along each side) and a position on the
 * block atlas, the one big picture that holds every block texture side by side. A stone block
 * is six quads; stairs, slabs, torches and flowers are just different lists of quads. Sending
 * the quads, plus the atlas picture once (see {@link OverlayShare}), is enough for Subnautica
 * to draw any of them.
 *
 * <p>Some faces are tinted: grass and leaves are stored grey and coloured when drawn. The tint
 * is sent with each quad.
 */
public final class BlockModels {
	private static final Direction[] FACES = { Direction.DOWN, Direction.UP, Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST, null };

	private BlockModels() {
	}

	/**
	 * The quads of a block, as text: quads separated by ";", each one its tint (six hex digits)
	 * followed by four corners of "x,y,z,u,v". Empty if the block isn't drawn from a model
	 * (chests and signs are drawn by special code, and water by its own).
	 */
	public static String describe(BlockState state) {
		// Water and lava are drawn place by place: see describeFluid.
		if (state.getBlock() instanceof FluidBlock) {
			return "";
		}

		if (state.getRenderType() != BlockRenderType.MODEL) {
			return "";
		}

		MinecraftClient client = MinecraftClient.getInstance();
		BakedModel model = client.getBlockRenderManager().getModel(state);
		Random random = Random.create(42L);
		StringBuilder text = new StringBuilder();
		Set<String> placesUsed = new HashSet<>();
		boolean animated = false;

		// A model keeps the quads on each of its six sides separately (so hidden sides can be
		// skipped when drawing), plus those that belong to no side, listed under "null".
		for (Direction face : FACES) {
			random.setSeed(42L);

			for (BakedQuad quad : model.getQuads(state, face, random)) {
				int tint = 0xFFFFFF;

				if (quad.hasColor()) {
					// No particular place in the world is given, so this is the default tint
					// (the standard grass green, for example).
					int colour = client.getBlockColors().getColor(state, null, null, quad.getColorIndex());

					if (colour != -1) {
						tint = colour & 0xFFFFFF;
					}
				}

				// Each corner is stored as 8 whole numbers: x, y, z, colour, u, v, light, normal.
				// The positions and texture positions are decimals packed into whole numbers.
				int[] data = quad.getVertexData();
				StringBuilder place = new StringBuilder();
				StringBuilder corners = new StringBuilder();

				for (int corner = 0; corner < 4; corner++) {
					int at = corner * 8;
					String position = String.format(Locale.ROOT, ",%.4f,%.4f,%.4f",
							Float.intBitsToFloat(data[at]), Float.intBitsToFloat(data[at + 1]), Float.intBitsToFloat(data[at + 2]));
					place.append(position);
					corners.append(position).append(String.format(Locale.ROOT, ",%.6f,%.6f",
							Float.intBitsToFloat(data[at + 4]), Float.intBitsToFloat(data[at + 5])));
				}

				// Some models lay a second, mostly see-through face exactly over the first: the
				// side of a grass block is dirt with a green fringe laid on top. Subnautica's
				// shader draws the see-through part solid, which would hide the dirt, so a face
				// in exactly the same place as an earlier one is left out.
				if (!placesUsed.add(place.toString())) {
					continue;
				}

				// A picture that moves (fire, a sea lantern)?
				if (quad.getSprite() != null && quad.getSprite().getContents().getDistinctFrameCount().count() > 1) {
					animated = true;
				}

				if (text.length() > 0) {
					text.append(';');
				}

				text.append(String.format(Locale.ROOT, "%06X", tint)).append(corners);
			}
		}

		if (animated && text.length() > 0) {
			// Marked with "~" so Subnautica knows to keep this model's picture up to date.
			// (ClientBlocks, seeing the mark, starts the atlas being sent again as it changes.)
			return "~" + text;
		}

		return text.toString();
	}

	/**
	 * Water or lava that someone has poured out, at one particular place. Minecraft draws
	 * these by code of its own, not from a model: the surface slopes to meet whatever is next
	 * to it, sides against more of the same liquid are left out, and the picture runs the way
	 * the liquid is flowing. So Minecraft is asked to draw this one block of it, and what it
	 * draws is caught and written out in the same form as a block's model. The same shape in
	 * another place comes out as the same text, so Subnautica is only sent each shape once.
	 * Empty if nothing of it shows (it is in the middle of a pool).
	 */
	public static String describeFluid(BlockRenderView world, BlockPos pos, BlockState state) {
		FluidState fluid = state.getFluidState();

		if (fluid.isEmpty()) {
			return "";
		}

		// Minecraft draws it measured from the corner of its chunk section; the model wants it from the block's own corner.
		QuadWriter quads = new QuadWriter(pos.getX() & 15, pos.getY() & 15, pos.getZ() & 15);
		MinecraftClient.getInstance().getBlockRenderManager().renderFluid(pos, world, quads, state, fluid);
		String text = quads.finish();

		if (text.isEmpty()) {
			return "";
		}

		// The picture moves, so the atlas has to be kept up to date. But there is no "~" in
		// front as there is for fire (see describe): that mark has Subnautica cut the shape
		// out afresh ten times a second to follow the gaps in a moving picture, and these
		// pictures have no gaps. (With the mark, every shape of water ever seen was being cut
		// out again ten times a second for ever after: that was the lag in 2.17.0.)
		// "%": drawn see-through. Water is; lava isn't.
		return (fluid.isIn(FluidTags.WATER) ? "%" : "") + text;
	}

	/** Takes what Minecraft draws (corners, four to a face) and writes it out as model text. */
	private static final class QuadWriter implements VertexConsumer {
		private final float offsetX, offsetY, offsetZ;
		private final StringBuilder text = new StringBuilder();
		private final StringBuilder corners = new StringBuilder();
		private int count;
		private boolean pending;
		private int tint = 0xFFFFFF;
		private float x, y, z, u, v;

		QuadWriter(float offsetX, float offsetY, float offsetZ) {
			this.offsetX = offsetX;
			this.offsetY = offsetY;
			this.offsetZ = offsetZ;
		}

		/** A corner is given as its position first, then its other details; a new position means the last corner is complete. */
		@Override
		public VertexConsumer vertex(float x, float y, float z) {
			this.commit();
			this.pending = true;
			this.x = x - this.offsetX;
			this.y = y - this.offsetY;
			this.z = z - this.offsetZ;
			return this;
		}

		@Override
		public VertexConsumer color(int red, int green, int blue, int alpha) {
			// The tint of the face is taken from its first corner.
			if (this.count == 0) {
				this.tint = ((red & 0xFF) << 16) | ((green & 0xFF) << 8) | (blue & 0xFF);
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
			this.corners.append(String.format(Locale.ROOT, ",%.4f,%.4f,%.4f,%.6f,%.6f", this.x, this.y, this.z, this.u, this.v));

			if (++this.count < 4) {
				return;
			}

			if (this.text.length() > 0) {
				this.text.append(';');
			}

			this.text.append(String.format(Locale.ROOT, "%06X", this.tint)).append(this.corners);
			this.corners.setLength(0);
			this.count = 0;
		}

		String finish() {
			this.commit();
			return this.text.toString();
		}
	}

	/**
	 * Where on the block atlas the ten block-breaking pictures are (the cracks that spread
	 * over a block as you break it): four numbers each, left top right bottom.
	 */
	public static String crackSpots() {
		SpriteAtlasTexture atlas = MinecraftClient.getInstance().getBakedModelManager().getAtlas(SpriteAtlasTexture.BLOCK_ATLAS_TEXTURE);
		StringBuilder text = new StringBuilder();

		for (int stage = 0; stage < 10; stage++) {
			Sprite sprite = atlas.getSprite(Identifier.ofVanilla("block/destroy_stage_" + stage));

			if (stage > 0) {
				text.append(' ');
			}

			text.append(String.format(Locale.ROOT, "%.6f %.6f %.6f %.6f", sprite.getMinU(), sprite.getMinV(), sprite.getMaxU(), sprite.getMaxV()));
		}

		return text.toString();
	}
}
