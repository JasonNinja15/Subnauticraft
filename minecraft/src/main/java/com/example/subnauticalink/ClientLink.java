package com.example.subnauticalink;

import java.util.Locale;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.passive.PigEntity;
import net.minecraft.text.Text;
import net.minecraft.util.UseAction;

/**
 * This player's connection to their own Subnautica. Client only.
 *
 * <p>Subnautica runs on the same computer as the player's Minecraft, and connects to it on a
 * port only that computer can reach. Whatever world the player is in (their own, a friend's,
 * a server), it is their own game that talks to their own Subnautica.
 *
 * <p>Lines from Subnautica are sorted three ways:
 * <ul>
 *   <li>things only this game needs, dealt with here: the controls being held, where the
 *       camera is aimed, hotbar keys, typing, the mouse pointer, screen size, swim speed,
 *       which view F5 has picked;</li>
 *   <li>things the server decides about, passed on to it (see {@link LinePayload}): health,
 *       eating, oxygen, death, tools, where the player starts;</li>
 *   <li>answers the game is waiting on, handed over the moment they arrive (collision).</li>
 * </ul>
 *
 * <p>Lines from the server for Subnautica arrive the same way and are written straight out.
 */
public final class ClientLink {
	/** While linked, the server is told "still here" this often, in ticks. */
	private static final int ALIVE_EVERY = 10;

	private static volatile LinkConnection connection;
	private static boolean atlasAnnounced;

	/** What Subnautica was last told about food in the hand: 1 yes, 0 no, -1 nothing yet. */
	private static int sentEdible = -1;

	/** The power left in the Subnautica vehicle being ridden, from 0 to 1, and when it was last heard. */
	private static volatile float vehicleCharge;
	private static volatile long vehicleHeardMs;

	/** The power left in the vehicle this player is riding in Subnautica, from 0 to 1; or -1 if they aren't in one. */
	public static float vehicleCharge() {
		return System.currentTimeMillis() - vehicleHeardMs < 1000 ? vehicleCharge : -1.0F;
	}

	/** The PRAWN suit's jump-jet reserve, from 0 to 1 (-1: none), and when it last changed. */
	private static volatile float vehicleBoost = -1.0F;
	private static volatile long boostChangedMs;

	/** The jump-jet reserve of the vehicle being ridden, from 0 to 1, while it is being used up or is refilling; otherwise -1. */
	public static float vehicleBoost() {
		long now = System.currentTimeMillis();
		return now - vehicleHeardMs < 1000 && now - boostChangedMs < 600 ? vehicleBoost : -1.0F;
	}

	/** Whether the player was sitting on something last tick. */
	private static boolean wasSeated;
	private static int ticks;

	private ClientLink() {
	}

	public static void init() {
		// A line from the server, for this player's Subnautica.
		ClientPlayNetworking.registerGlobalReceiver(LinePayload.ID, (payload, context) -> {
			// Lines starting "@" are notes for this game itself; the rest are for Subnautica.
			if (payload.line().startsWith("@")) {
				Projectiles.onNote(payload.line());
			} else {
				toSubnautica(payload.line());
			}
		});

		// Joined a world (any kind): start waiting for Subnautica. Left it: hang up.
		ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> start());
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> stop());
	}

	private static void start() {
		stop();

		LinkConnection fresh = new LinkConnection(SubnauticaLink.PORT);
		fresh.start();
		connection = fresh;
		atlasAnnounced = false;
		// Answers Subnautica gives straight away: what a projectile will hit goes to
		// ClientPhysics, and where an item landed goes back to the server, which asked.
		fresh.rayAnswers = ClientPhysics::onAnswer;
		RemoteCollision.itemAnswers = numbers -> MinecraftClient.getInstance().execute(() -> toServer("SWEPT " + numbers));
		ClientPhysics.reset();
		RemoteCollision.setLink(fresh);
		Sessions.localSink = fresh::send;
		Sessions.localNote = Projectiles::onNote;
		ClientBlocks.reset();
		ClientEntities.reset();
		ClientAvatars.reset();
		SubnauticaLink.LOGGER.info("Waiting for Subnautica on port {}", SubnauticaLink.PORT);
	}

	private static void stop() {
		LinkConnection old = connection;
		connection = null;
		Sessions.localSink = null;
		Sessions.localNote = null;
		RemoteCollision.setLink(null);
		Projectiles.forgetNotes();

		HeldLights.BY_PLAYER.clear();

		if (old != null) {
			old.stop();

			// Back to looking through the eyes, if Subnautica had the view from outside.
			ClientAvatars.setView(0);
			RemoteControls.clear();
			WaterState.reset();
			OverlayFile.reset();
		}
	}

	/** Sends one line to this computer's Subnautica. */
	public static void toSubnautica(String line) {
		LinkConnection current = connection;

		if (current != null) {
			current.send(line);
		}
	}

	/** Passes one line on to the server, if it is a server that has this mod. */
	public static void toServer(String line) {
		if (ClientPlayNetworking.canSend(LinePayload.ID)) {
			ClientPlayNetworking.send(new LinePayload(line));
		}
	}

	/** Called at the start of every client tick: deal with whatever Subnautica has sent. */
	public static void tick(MinecraftClient client) {
		LinkConnection current = connection;

		if (current == null || client.player == null) {
			return;
		}

		String line;

		while ((line = current.poll()) != null) {
			handle(line);
		}

		if (!current.isConnected()) {
			return;
		}

		// While Subnautica is sending controls, keep telling the server this link is live.
		if (RemoteControls.isActive() && ++ticks % ALIVE_EVERY == 0) {
			toServer("ALIVE");
		}

		// Once the block atlas has been written out, tell Subnautica where it is, and where
		// on it the ten block-breaking pictures are.
		if (!atlasAnnounced && OverlayFile.atlasWidth > 0 && RemoteControls.isActive()) {
			atlasAnnounced = true;
			current.send("ATLAS " + OverlayFile.atlasPath());

			try {
				current.send("CRACKS " + BlockModels.crackSpots());
			} catch (RuntimeException e) {
				SubnauticaLink.LOGGER.warn("Could not find the block-breaking pictures", e);
			}
		}
	}

	/** How tall the server was last told the player is. */
	private static float sentHeight = -1.0F;

	/** Called at the end of every client tick, once the player has moved: tell Subnautica where they now are. */
	public static void afterTick(MinecraftClient client) {
		LinkConnection current = connection;
		ClientPlayerEntity player = client.player;

		if (current == null || player == null || !RemoteCollision.appliesTo(player)) {
			// Whenever the link next comes up, the server is told the height afresh.
			sentHeight = -1.0F;
			return;
		}

		// The fourth number is how high the eyes are above the feet (it changes when sneaking
		// or swimming), so Subnautica can put its camera exactly there.
		// The fifth is 1 while sitting on the unseen mount that stands for a Subnautica vehicle's seat
		// (not in a Minecraft boat, which Minecraft moves): Subnautica is in charge of
		// the position then, and must not take this one as the player's own.
		current.send(String.format(Locale.ROOT, "MCPOS %.3f %.3f %.3f %.2f %d", player.getX(), player.getY(), player.getZ(),
				player.getStandingEyeHeight(), player.getVehicle() instanceof PigEntity ? 1 : 0));

		// And the server is told how tall they are just now (standing, crouched, swimming), so
		// it doesn't stand them up where their own game has found there is no room to.
		float tall = player.getHeight();

		if (tall != sentHeight || player.age % 100 == 0) {
			sentHeight = tall;
			toServer(String.format(Locale.ROOT, "POSEH %.2f", tall));
		}

		// And about the blocks and loose things around them.
		ClientBlocks.tick(client);
		ClientEntities.tick(client);

		// And what the players around them (and they themselves, seen from outside) look like.
		ClientAvatars.tick(client);

		// And what light the thing in their hand gives.
		ClientHeldLight.tick(client);

		// Sitting down in one of Subnautica's vehicles, Minecraft says which of its own keys
		// gets you off again. That key isn't the one here (it is Alt, in Subnautica), so the
		// message is wiped.
		boolean seated = player.hasVehicle();

		if (seated && !wasSeated) {
			client.inGameHud.setOverlayMessage(Text.empty(), false);
		}

		wasSeated = seated;

		// And whether it is something to eat or drink: in a vehicle, the right mouse button is
		// the vehicle's unless it is.
		UseAction action = player.getMainHandStack().getUseAction();
		int edible = action == UseAction.EAT || action == UseAction.DRINK ? 1 : 0;

		if (edible != sentEdible) {
			sentEdible = edible;
			current.send("EDIBLE " + edible);
		}

		// And ask what this player's projectiles are about to hit.
		ClientPhysics.tick(client);
	}

	private static void handle(String line) {
		if (line.equals(LinkConnection.CONNECTED)) {
			atlasAnnounced = false;
			// Subnautica starts with none of Minecraft's blocks or items: describe them afresh.
			ClientBlocks.reset();
			ClientEntities.reset();
			ClientAvatars.reset();
			ClientHeldLight.reset();
			sentEdible = -1;
			// Tell Subnautica where to find the picture of Minecraft's HUD.
			toSubnautica("OVERLAY " + OverlayFile.path());
			toServer("LINKED");
		} else if (line.equals("RESYNC")) {
			// Subnautica's world was closed and opened again (back to its menu, then a save
			// loaded) with the link still up: it has lost everything it was told about the
			// world, so it is all described afresh.
			atlasAnnounced = false;
			ClientBlocks.reset();
			ClientEntities.reset();
			ClientAvatars.reset();
			ClientHeldLight.reset();
		} else if (line.equals(LinkConnection.DISCONNECTED)) {
			RemoteControls.clear();
			WaterState.reset();
			OverlayFile.reset();
			ClientBlocks.reset();
			ClientEntities.reset();
			ClientAvatars.reset();
			ClientAvatars.setView(0);
			toServer("UNLINKED");
		} else if (line.startsWith("INPUT ")) {
			RemoteControls.onInputLine(line.substring("INPUT ".length()));
		} else if (line.startsWith("AIM ")) {
			RemoteControls.onAimLine(line.substring("AIM ".length()));
		} else if (line.startsWith("CHAT ") || line.startsWith("TYPE ") || line.startsWith("KEY ")
				|| line.equals("INVENTORY") || line.equals("SWAP") || line.startsWith("CLICK ") || line.startsWith("DROP ") || line.startsWith("WHEEL ")) {
			RemoteControls.addTyping(line);
		} else if (line.startsWith("RIDE ")) {
			// In one of Subnautica's vehicles. Where to sit is the server's business; how much
			// power the vehicle has left is shown by this game (see InGameHudMixin).
			String[] parts = line.split(" ");

			if (parts.length >= 7) {
				try {
					vehicleCharge = Math.max(0.0F, Math.min(1.0F, Float.parseFloat(parts[6])));
					vehicleHeardMs = System.currentTimeMillis();

					// The PRAWN suit's jump jets: shown for as long as their reserve is going down or coming back.
					float boost = parts.length >= 8 ? Float.parseFloat(parts[7]) : -1.0F;

					if (boost >= 0.0F && vehicleBoost >= 0.0F && Math.abs(boost - vehicleBoost) > 0.0015F) {
						boostChangedMs = vehicleHeardMs;
					}

					vehicleBoost = boost;
				} catch (NumberFormatException e) {
					// A garbled line; the bar keeps its last value.
				}
			}

			toServer(line);
		} else if (line.startsWith("CARRY ")) {
			// Aboard a moving Cyclops: the sub has carried the player this much further. Move
			// the player to match, and say so, so Subnautica knows which carries are in the
			// positions it is sent from here on.
			String[] parts = line.split(" ");
			MinecraftClient client = MinecraftClient.getInstance();

			if (parts.length == 5) {
				try {
					double dx = Double.parseDouble(parts[2]);
					double dy = Double.parseDouble(parts[3]);
					double dz = -Double.parseDouble(parts[4]);

					if (client.player != null && !client.player.hasVehicle() && RemoteCollision.appliesTo(client.player)) {
						client.player.setPosition(client.player.getX() + dx, client.player.getY() + dy, client.player.getZ() + dz);
					}
				} catch (NumberFormatException e) {
					// A garbled line: acknowledged all the same, so the two sides stay in step.
				}

				toSubnautica("ACK " + parts[1]);
			}
		} else if (line.startsWith("SLOT ")) {
			RemoteControls.pickSlot(parseWhole(line.substring("SLOT ".length()), -1));
		} else if (line.startsWith("SCROLL ")) {
			RemoteControls.scroll(parseWhole(line.substring("SCROLL ".length()), 0));
		} else if (line.startsWith("TOOLSTATE ")) {
			// What the tool in Subnautica's hand is doing: its light, and an air bladder's lift.
			ClientTools.onToolState(line.substring("TOOLSTATE ".length()));
		} else if (line.startsWith("VIEW ")) {
			// F5 was pressed in Subnautica: look through the eyes, from behind, or from the front.
			ClientAvatars.setView(parseWhole(line.substring("VIEW ".length()), 0));
		} else if (line.startsWith("SCREEN ")) {
			OverlayFile.onScreenLine(line.substring("SCREEN ".length()));
		} else if (line.startsWith("SWIMSPEED ")) {
			WaterState.onSwimSpeedLine(line.substring("SWIMSPEED ".length()));
		} else if (line.startsWith("BIOME ")) {
			// Which of Subnautica's regions the player is in. Both this game and the server need to know.
			WaterState.clientBiome = line.substring("BIOME ".length()).trim();
			toServer(line);
		} else if (line.startsWith("DRY ")) {
			// Both this game and the server need to know.
			WaterState.onDryLine(line.substring("DRY ".length()));
			toServer(line);
		} else {
			// Health, eating, oxygen, death, tools, a landed hit, where to start: the server's.
			toServer(line);
		}
	}

	/** Reads a whole number, or gives the fallback if the text isn't one. */
	private static int parseWhole(String text, int fallback) {
		try {
			return Integer.parseInt(text.trim());
		} catch (NumberFormatException e) {
			return fallback;
		}
	}
}
