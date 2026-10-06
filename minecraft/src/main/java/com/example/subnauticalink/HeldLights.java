package com.example.subnauticalink;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What light each player's held thing gives, as the server has passed it round.
 *
 * <p>Each player's own game knows what light the thing in their hand gives (a torch; a
 * Seaglide, if its lamp is on), and tells its own Subnautica (see {@code ClientHeldLight}). So
 * that everyone else sees that light too, it also tells the server ("MYLIGHT range strength
 * colour flicker", or "MYLIGHT 0"), and the server passes it to every player's game as a note
 * ("@LIGHT playerNumber ..."). This class is where a game keeps those notes; each game then
 * tells its own Subnautica about the players near it.
 */
public final class HeldLights {
	/** The light each player's held thing gives, by the player's number in the world. Players with none aren't listed. */
	public static final Map<Integer, String> BY_PLAYER = new ConcurrentHashMap<>();

	private HeldLights() {
	}

	/** Whether this is a light as ClientHeldLight writes one: numbers, hex digits, spaces and points, and nothing else. */
	public static boolean isLight(String light) {
		return !light.isEmpty() && light.length() <= 40 && light.matches("[0-9A-Fa-f. ]+");
	}

	/** "playerNumber light": the server says what this player's held thing gives. */
	public static void onNote(String text) {
		int space = text.indexOf(' ');

		if (space <= 0) {
			return;
		}

		try {
			int player = Integer.parseInt(text.substring(0, space));
			String light = text.substring(space + 1).trim();

			if (light.equals("0") || !isLight(light)) {
				BY_PLAYER.remove(player);
			} else {
				BY_PLAYER.put(player, light);
			}
		} catch (NumberFormatException e) {
			// A garbled note; ignore it.
		}
	}
}
