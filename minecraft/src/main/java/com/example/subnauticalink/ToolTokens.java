package com.example.subnauticalink;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.CustomModelDataComponent;
import net.minecraft.component.type.NbtComponent;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

/**
 * Subnautica's tools, as Minecraft items.
 *
 * <p>Each tool in Subnautica's inventory (knife, scanner, habitat builder, Seaglide...) gets a
 * matching "token" in Minecraft's inventory: one kind of item, labelled with the tool's name
 * and carrying which tool it stands for. Tokens are handed out and taken away automatically
 * as Subnautica's inventory changes; you still make the tools at Subnautica's fabricator.
 *
 * <p>Holding a token in the main hand tells Subnautica to take the real tool out. The tool
 * stays invisible (Minecraft's hand shows the token instead), but it works as normal, and
 * while it is held the mouse buttons belong to Subnautica rather than to Minecraft.
 */
public final class ToolTokens {
	/** Where the token keeps which Subnautica tool it stands for. */
	private static final String TOOL_KEY = "subnautica_tool";

	/** The one item every token is made of. Registered when the mod starts. */
	public static Item TOKEN;

	/**
	 * Which picture each tool's token shows. Each number is a model in the mod's resources
	 * (models/item/tool_N.json) that lays two of Minecraft's own item pictures one over the
	 * other: a sword with a feather over it for the knife, a book behind iron bars for the
	 * scanner, and so on. A tool not listed here shows the plain token.
	 */
	private static final Map<String, Integer> PICTURES = Map.ofEntries(
			Map.entry("Knife", 1),
			Map.entry("HeatBlade", 2),
			Map.entry("Scanner", 3),
			Map.entry("Builder", 4),
			Map.entry("Welder", 5),
			Map.entry("LaserCutter", 6),
			Map.entry("Flashlight", 7),
			Map.entry("Seaglide", 8),
			Map.entry("StasisRifle", 9),
			Map.entry("PropulsionCannon", 10),
			Map.entry("RepulsionCannon", 11),
			Map.entry("AirBladder", 12),
			Map.entry("Flare", 13),
			Map.entry("FireExtinguisher", 14),
			Map.entry("DiveReel", 15),
			Map.entry("Beacon", 16),
			Map.entry("LEDLight", 17),
			Map.entry("Gravsphere", 18),
			Map.entry("Constructor", 19),
			Map.entry("SmallStorage", 20));

	/** What one player's Subnautica last said it is carrying: for each tool, the name to show and how many. */
	private record Carried(String shownAs, int count) {
	}

	private static final Map<UUID, Map<String, Carried>> CARRIED = new HashMap<>();

	private ToolTokens() {
	}

	public static void register() {
		TOKEN = Registry.register(Registries.ITEM, Identifier.of(SubnauticaLink.MOD_ID, "tool"), new Item(new Item.Settings().maxCount(1)));
	}

	/** Which Subnautica tool this item stands for (Subnautica's own name for it, such as "Knife"), or null if it isn't a token. */
	public static String toolOf(ItemStack stack) {
		if (stack.isEmpty() || TOKEN == null || !stack.isOf(TOKEN)) {
			return null;
		}

		NbtComponent data = stack.get(DataComponentTypes.CUSTOM_DATA);

		if (data == null) {
			return null;
		}

		String tool = data.copyNbt().getString(TOOL_KEY);
		return tool.isEmpty() ? null : tool;
	}

	public static boolean isHoldingTool(PlayerEntity player) {
		return toolOf(player.getMainHandStack()) != null;
	}

	/**
	 * "Knife:Survival Knife:2|Scanner:Scanner:1|...": every tool now in Subnautica's inventory,
	 * as its internal name, its display name and how many are carried. The list is kept, and
	 * the tokens in Minecraft's inventory are brought into line with it now and once a second
	 * from then on (see {@link #reconcile}).
	 */
	public static void onToolsLine(ServerPlayerEntity player, String text) {
		if (player == null) {
			return;
		}

		Map<String, Carried> carried = new LinkedHashMap<>();

		for (String entry : text.split("\\|")) {
			String[] parts = entry.split(":");

			if (parts.length < 2 || parts[0].trim().isEmpty()) {
				continue;
			}

			int count = 1;

			if (parts.length >= 3) {
				try {
					count = Math.max(1, Math.min(64, Integer.parseInt(parts[2].trim())));
				} catch (NumberFormatException e) {
					// No count given: one.
				}
			}

			carried.put(parts[0].trim(), new Carried(parts[1].trim(), count));
		}

		CARRIED.put(player.getUuid(), carried);
		reconcile(player);
	}

	/**
	 * Makes the tokens in the player's inventory match what their Subnautica is carrying:
	 * exactly as many of each as there are tools of that kind, no more and no fewer. Tokens
	 * for tools that are gone, and spare copies (picked back up after dying, say), are
	 * removed; missing ones are given. A token held on the mouse pointer in an open inventory
	 * counts too.
	 */
	private static void reconcile(ServerPlayerEntity player) {
		Map<String, Carried> carried = CARRIED.get(player.getUuid());

		if (carried == null) {
			return;
		}

		Map<String, Integer> have = new HashMap<>();
		ItemStack onPointer = player.currentScreenHandler.getCursorStack();
		String pointerTool = toolOf(onPointer);

		if (pointerTool != null) {
			if (carried.containsKey(pointerTool)) {
				have.merge(pointerTool, 1, Integer::sum);
			} else {
				player.currentScreenHandler.setCursorStack(ItemStack.EMPTY);
			}
		}

		PlayerInventory inventory = player.getInventory();

		for (int slot = 0; slot < inventory.size(); slot++) {
			ItemStack stack = inventory.getStack(slot);
			String tool = toolOf(stack);

			if (tool == null) {
				continue;
			}

			Carried wanted = carried.get(tool);

			if (wanted == null || have.getOrDefault(tool, 0) >= wanted.count()) {
				inventory.removeStack(slot);
			} else {
				have.merge(tool, 1, Integer::sum);
				// Tokens made by an earlier version get their picture too.
				dress(stack, tool, wanted.shownAs());
			}
		}

		for (Map.Entry<String, Carried> entry : carried.entrySet()) {
			for (int i = have.getOrDefault(entry.getKey(), 0); i < entry.getValue().count(); i++) {
				ItemStack token = new ItemStack(TOKEN);
				NbtCompound data = new NbtCompound();
				data.putString(TOOL_KEY, entry.getKey());
				token.set(DataComponentTypes.CUSTOM_DATA, NbtComponent.of(data));
				dress(token, entry.getKey(), entry.getValue().shownAs());

				// If the inventory is full the token can't be given; it is tried again in a second.
				if (!inventory.insertStack(token)) {
					break;
				}
			}
		}
	}

	/** Gives a token its name and its picture. */
	private static void dress(ItemStack token, String tool, String shownAs) {
		Text name = Text.literal(shownAs.isEmpty() ? tool : shownAs);

		if (!name.equals(token.get(DataComponentTypes.ITEM_NAME))) {
			token.set(DataComponentTypes.ITEM_NAME, name);
		}

		Integer picture = PICTURES.get(tool);
		CustomModelDataComponent wanted = picture == null ? null : new CustomModelDataComponent(picture);

		if (wanted == null ? token.get(DataComponentTypes.CUSTOM_MODEL_DATA) != null : !wanted.equals(token.get(DataComponentTypes.CUSTOM_MODEL_DATA))) {
			token.set(DataComponentTypes.CUSTOM_MODEL_DATA, wanted);
		}
	}

	/** Tokens found lying about as dropped items, waiting to be removed at the end of the tick. */
	private static final List<Entity> STRAYS = new ArrayList<>();

	/** A token has turned up as a dropped item. It is removed at the end of the tick (not on the spot: the world is still in the middle of adding it). */
	public static void noteStray(Entity dropped) {
		STRAYS.add(dropped);
	}

	public static void clearStrays() {
		for (Entity stray : STRAYS) {
			stray.discard();
		}

		STRAYS.clear();
	}

	/** The player has left: forget what their Subnautica was carrying. */
	public static void forget(UUID player) {
		CARRIED.remove(player);
	}

	/** Called every server tick for each player: tells their Subnautica when the tool in hand changes ("EQUIP Knife", or "EQUIP -" for none). */
	public static void tick(ServerPlayerEntity player, LinkSession session, Consumer<String> send) {
		if (!session.isActive()) {
			return;
		}

		// Once a second, check the tokens still match what Subnautica is carrying.
		if (player.age % 20 == 0) {
			reconcile(player);
		}

		// A token only works in the main hand: one that reaches the other hand (by the swap
		// key, or by being put there in the inventory) goes straight back into the inventory.
		ItemStack offHand = player.getOffHandStack();

		if (toolOf(offHand) != null) {
			player.getInventory().offHand.set(0, ItemStack.EMPTY);
			player.getInventory().offerOrDrop(offHand);
		}

		String held = toolOf(player.getMainHandStack());

		if (held == null ? session.equippedTool != null : !held.equals(session.equippedTool)) {
			session.equippedTool = held;
			send.accept("EQUIP " + (held == null ? "-" : held));
		}
	}
}
