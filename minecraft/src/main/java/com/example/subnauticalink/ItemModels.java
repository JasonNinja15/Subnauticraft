package com.example.subnauticalink;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import net.fabricmc.fabric.api.client.rendering.v1.ColorProviderRegistry;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.color.item.ItemColorProvider;
import net.minecraft.client.render.model.BakedModel;
import net.minecraft.client.render.model.BakedQuad;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.random.Random;

/**
 * Describes what a dropped item looks like, so Subnautica can draw it. Client only.
 *
 * <p>Items are drawn from the same kind of model as blocks (see {@link BlockModels}): a list
 * of flat four-cornered faces, each showing part of the block atlas. A block lying on the
 * ground is its block model, shrunk. A flat item such as a stick or an apple is a thin slab:
 * its picture on the front and back, and a strip along every edge of the picture to give it
 * thickness.
 *
 * <p>The answer is "scale colour quads". Scale is how big to draw it (a quarter of a block for
 * blocks, half for flat items, as Minecraft does). Colour is for items with no model to send
 * (chests, shields and the like are drawn by special code): Subnautica shows those as a small
 * plain cube of that colour.
 */
public final class ItemModels {
	private static final Direction[] FACES = { Direction.DOWN, Direction.UP, Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST, null };

	/** How far apart, in model units, faces in exactly the same place are moved so they don't flicker. */
	private static final float LAYER_GAP = 0.004F;

	private ItemModels() {
	}

	public static String describe(ItemStack stack) {
		MinecraftClient client = MinecraftClient.getInstance();

		// The model the item uses in the inventory and on the ground.
		BakedModel model = client.getItemRenderer().getModels().getModel(stack);

		int colour = 0xB0B0B0;

		if (stack.getItem() instanceof BlockItem blockItem) {
			int mapColour = blockItem.getBlock().getDefaultMapColor().color;

			if (mapColour != 0) {
				colour = mapColour & 0xFFFFFF;
			}
		}

		boolean isBlockShaped = model.hasDepth();
		String start = String.format(Locale.ROOT, "%.2f %06X ", isBlockShaped ? 0.25 : 0.5, colour);

		if (model.isBuiltin()) {
			return start;
		}

		ItemColorProvider tints = ColorProviderRegistry.ITEM.get(stack.getItem());
		Random random = Random.create(42L);
		StringBuilder text = new StringBuilder();
		Map<String, Integer> placesUsed = new HashMap<>();

		for (Direction side : FACES) {
			random.setSeed(42L);

			for (BakedQuad quad : model.getQuads(null, side, random)) {
				int tint = 0xFFFFFF;

				if (quad.hasColor() && tints != null) {
					tint = tints.getColor(stack, quad.getColorIndex()) & 0xFFFFFF;
				}

				int[] data = quad.getVertexData();
				float[] numbers = new float[20];
				StringBuilder place = new StringBuilder();

				for (int corner = 0; corner < 4; corner++) {
					int at = corner * 8;

					for (int i = 0; i < 3; i++) {
						numbers[corner * 5 + i] = Float.intBitsToFloat(data[at + i]);
						place.append(String.format(Locale.ROOT, "%.4f,", numbers[corner * 5 + i]));
					}

					numbers[corner * 5 + 3] = Float.intBitsToFloat(data[at + 4]);
					numbers[corner * 5 + 4] = Float.intBitsToFloat(data[at + 5]);
				}

				// Some items are several pictures laid exactly on top of each other (a potion
				// is its bottle and, separately, its coloured contents). Each one after the
				// first is moved out by a hair, so they don't flicker where they overlap.
				int layer = placesUsed.merge(place.toString(), 1, Integer::sum) - 1;

				if (layer > 0) {
					Direction facing = quad.getFace();

					for (int corner = 0; corner < 4; corner++) {
						numbers[corner * 5] += facing.getOffsetX() * LAYER_GAP * layer;
						numbers[corner * 5 + 1] += facing.getOffsetY() * LAYER_GAP * layer;
						numbers[corner * 5 + 2] += facing.getOffsetZ() * LAYER_GAP * layer;
					}
				}

				if (text.length() > 0) {
					text.append(';');
				}

				text.append(String.format(Locale.ROOT, "%06X", tint));

				for (int corner = 0; corner < 4; corner++) {
					text.append(String.format(Locale.ROOT, ",%.4f,%.4f,%.4f,%.6f,%.6f",
							numbers[corner * 5], numbers[corner * 5 + 1], numbers[corner * 5 + 2],
							numbers[corner * 5 + 3], numbers[corner * 5 + 4]));
				}
			}
		}

		return start + text;
	}
}
