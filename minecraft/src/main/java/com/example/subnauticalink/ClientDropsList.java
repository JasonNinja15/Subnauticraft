package com.example.subnauticalink;

import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.CreativeInventoryScreen;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;

/**
 * The list of what Subnautica's creatures drop, shown down the right of the screen while the
 * inventory is open. Client only. It is drawn from the same list the server drops from (see
 * {@link CreatureDrops}).
 */
public final class ClientDropsList {
	private static final int WIDTH = 150;
	private static final int MARGIN = 6;

	private ClientDropsList() {
	}

	public static void init() {
		// Whenever a screen opens: if it is the inventory, have this drawn on top of it.
		ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
			if (screen instanceof InventoryScreen || screen instanceof CreativeInventoryScreen) {
				ScreenEvents.afterRender(screen).register(ClientDropsList::draw);
			}
		});
	}

	private static void draw(Screen screen, DrawContext context, int mouseX, int mouseY, float tickDelta) {
		if (!OverlayShare.isActive()) {
			return;
		}

		TextRenderer text = MinecraftClient.getInstance().textRenderer;
		int inner = WIDTH - 2 * MARGIN;

		// First work out how tall it will be, for the dark panel behind it.
		int height = MARGIN + 12;

		for (CreatureDrops.Entry entry : CreatureDrops.TABLE) {
			height += rowsOf(text, entry, inner) * 18 + text.wrapLines(Text.literal(entry.shownAs()), inner).size() * 9 + 6;
		}

		// A long list is drawn smaller, so all of it fits down the side of the screen.
		float scale = Math.min(1.0F, (screen.height - 2.0F * MARGIN) / height);
		context.getMatrices().push();
		context.getMatrices().scale(scale, scale, 1.0F);

		int left = (int) ((screen.width - MARGIN) / scale) - WIDTH;
		int top = Math.max((int) (MARGIN / scale), (int) ((screen.height / scale - height) / 2.0F));
		context.fill(left, top, left + WIDTH, top + height, 0xB0000000);

		int x = left + MARGIN;
		int y = top + MARGIN;
		context.drawTextWithShadow(text, Text.literal("Subnautica drops"), x, y, 0xFFFFFF);
		y += 12;

		for (CreatureDrops.Entry entry : CreatureDrops.TABLE) {
			// The things dropped, each with how many; a new row when one is full.
			int at = x;

			for (CreatureDrops.Drop drop : entry.drops()) {
				String count = countOf(drop);
				int wide = 18 + text.getWidth(count) + 8;

				if (at > x && at + wide - 8 > x + inner) {
					at = x;
					y += 18;
				}

				context.drawItem(new ItemStack(drop.item()), at, y);
				context.drawTextWithShadow(text, Text.literal(count), at + 18, y + 5, 0xFFFFFF);
				at += wide;
			}

			y += 18;

			// And who drops them.
			Text names = Text.literal(entry.shownAs());
			context.drawTextWrapped(text, names, x, y, inner, 0xB0B0B0);
			y += text.wrapLines(names, inner).size() * 9 + 6;
		}

		context.getMatrices().pop();
	}

	/** How many of a thing is dropped, as shown: "3", "1-3", or with how often for one that only comes some of the time. */
	private static String countOf(CreatureDrops.Drop drop) {
		String count = drop.least() == drop.most() ? String.valueOf(drop.least()) : drop.least() + "-" + drop.most();
		return drop.chance() < 100 ? count + " (" + drop.chance() + "%)" : count;
	}

	/** How many rows one creature's drops take up (the same sums as the drawing does). */
	private static int rowsOf(TextRenderer text, CreatureDrops.Entry entry, int inner) {
		int rows = 1;
		int at = 0;

		for (CreatureDrops.Drop drop : entry.drops()) {
			int wide = 18 + text.getWidth(countOf(drop)) + 8;

			if (at > 0 && at + wide - 8 > inner) {
				at = 0;
				rows++;
			}

			at += wide;
		}

		return rows;
	}
}
