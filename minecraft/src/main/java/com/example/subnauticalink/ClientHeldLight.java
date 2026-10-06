package com.example.subnauticalink;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;

/**
 * Light from what the player is holding. Client only.
 *
 * <p>Subnautica is told what light the held item gives ("HELDLIGHT range strength colour
 * flicker", in the same terms as a block's light, or "HELDLIGHT 0" for none) and keeps a
 * light at the player's eyes to match. A torch, lantern, glowstone or any other block that
 * glows gives the light that block gives when placed. Of Subnautica's tools, the Seaglide
 * lights the water like a sea lantern, and the flashlight does the same but further, each
 * only while its own light is switched on in Subnautica and it has charge.
 *
 * <p>The same light is also told to the server ("MYLIGHT ..."), which passes it to every
 * other player's game (see {@link HeldLights}). Each game then tells its own Subnautica about
 * the lights of the players near it ("PLIGHT playerNumber range strength colour flicker", or
 * "PLIGHT playerNumber 0"), and Subnautica keeps a light on each of them.
 */
public final class ClientHeldLight {
	/** A sea lantern's light, and the same reaching half as far again. */
	private static final String SEAGLIDE = "21.0 1.30 E4F3FF 0";
	private static final String FLASHLIGHT = "32.0 1.60 E4F3FF 0";

	private static String sent;

	/** What the server was last told, and when it is next told regardless (for players who have joined since). */
	private static String told;
	private static int tellAgainIn;

	/** What this player's Subnautica was last told about each other player's light. */
	private static final Map<Integer, String> SENT_OTHERS = new HashMap<>();

	/** Other players' lights are shown when they are within this many blocks. */
	private static final double OTHERS_WITHIN = 64.0;

	private ClientHeldLight() {
	}

	/** Subnautica (re)connected: tell it afresh. */
	public static void reset() {
		sent = null;
		told = null;
		SENT_OTHERS.clear();
	}

	/** Called every client tick while linked. */
	public static void tick(MinecraftClient client) {
		if (client.player == null || !RemoteCollision.appliesTo(client.player)) {
			return;
		}

		// The main hand first; the other hand if that gives no light.
		String light = lightOf(client.player.getMainHandStack());

		if (light == null) {
			light = lightOf(client.player.getOffHandStack());
		}

		if (light == null) {
			light = "0";
		}

		if (!light.equals(sent)) {
			sent = light;
			ClientLink.toSubnautica("HELDLIGHT " + light);
		}

		// The server too, when it changes, and every five seconds for anyone who has joined since.
		if (!light.equals(told) || --tellAgainIn <= 0) {
			told = light;
			tellAgainIn = 100;
			ClientLink.toServer("MYLIGHT " + light);
		}

		// And the lights of the other players nearby, for this player's Subnautica.
		for (Map.Entry<Integer, String> other : HeldLights.BY_PLAYER.entrySet()) {
			Entity entity = client.world.getEntityById(other.getKey());
			boolean shown = entity instanceof PlayerEntity && entity != client.player && entity.isAlive()
					&& entity.squaredDistanceTo(client.player) <= OTHERS_WITHIN * OTHERS_WITHIN;

			if (shown && !other.getValue().equals(SENT_OTHERS.get(other.getKey()))) {
				SENT_OTHERS.put(other.getKey(), other.getValue());
				ClientLink.toSubnautica("PLIGHT " + other.getKey() + " " + other.getValue());
			}
		}

		// Any that has gone out, or whose player has gone away, is put out.
		Iterator<Map.Entry<Integer, String>> lit = SENT_OTHERS.entrySet().iterator();

		while (lit.hasNext()) {
			Map.Entry<Integer, String> other = lit.next();
			Entity entity = client.world.getEntityById(other.getKey());

			if (!HeldLights.BY_PLAYER.containsKey(other.getKey()) || !(entity instanceof PlayerEntity) || !entity.isAlive()
					|| entity.squaredDistanceTo(client.player) > OTHERS_WITHIN * OTHERS_WITHIN) {
				ClientLink.toSubnautica("PLIGHT " + other.getKey() + " 0");
				lit.remove();
			}
		}
	}

	private static String lightOf(ItemStack stack) {
		if (stack.isEmpty()) {
			return null;
		}

		String tool = ToolTokens.toolOf(stack);

		if (tool != null) {
			// Only while the tool's own light is switched on in Subnautica and has charge left.
			if (!ClientTools.lightOn) {
				return null;
			}

			return tool.equals("Seaglide") ? SEAGLIDE : tool.equals("Flashlight") ? FLASHLIGHT : null;
		}

		return stack.getItem() instanceof BlockItem block ? ClientBlocks.lightOf(block.getBlock().getDefaultState()) : null;
	}
}
