package com.example.subnauticalink;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;

/**
 * Every player's link, as the server sees it: one {@link LinkSession} each.
 *
 * <p>"The host" below is the player whose own game is running the world, if there is one. Lines
 * for that player's Subnautica can be handed straight over instead of going by the network.
 */
public final class Sessions {
	private static final Map<UUID, LinkSession> ALL = new ConcurrentHashMap<>();

	/**
	 * A direct line to the Subnautica on this computer, when there is one. Set by the client
	 * side of the mod. When the world is hosted from this game, the server half and the
	 * client half are the same program, and using this skips a trip through the network: it
	 * matters for the questions the server waits on the answer to.
	 */
	public static volatile Consumer<String> localSink;

	/** The same, for notes meant for the game itself rather than for Subnautica (lines starting "@"). */
	public static volatile Consumer<String> localNote;

	private static volatile MinecraftServer server;
	private static volatile LinkSession host;

	private Sessions() {
	}

	/** The session for a player, made the first time it is asked for. */
	public static LinkSession get(ServerPlayerEntity player) {
		return ALL.computeIfAbsent(player.getUuid(), LinkSession::new);
	}

	/** The session of the player this entity is, or null if it isn't a player with one. */
	public static LinkSession of(Entity entity) {
		return entity instanceof PlayerEntity player ? ALL.get(player.getUuid()) : null;
	}

	/** True for a player whose Subnautica is linked and sending controls. */
	public static boolean isActive(Entity entity) {
		LinkSession session = of(entity);
		return session != null && session.isActive();
	}

	public static boolean anyActive() {
		for (LinkSession session : ALL.values()) {
			if (session.isActive()) {
				return true;
			}
		}

		return false;
	}

	/** Sends a line to every linked player's Subnautica: things everyone should see or hear about. */
	public static void broadcast(String line) {
		MinecraftServer current = server;

		if (current == null) {
			return;
		}

		for (LinkSession session : ALL.values()) {
			if (session.isActive()) {
				ServerPlayerEntity player = current.getPlayerManager().getPlayer(session.id);

				if (player != null) {
					session.send(current, player, line);
				}
			}
		}
	}

	/** Sends a note (a line starting "@") to every player's game, linked or not. It is for the game itself, not for Subnautica. */
	public static void note(String line) {
		MinecraftServer current = server;

		if (current == null) {
			return;
		}

		for (ServerPlayerEntity player : current.getPlayerManager().getPlayerList()) {
			get(player).send(current, player, line);
		}
	}

	/**
	 * Something happened at a spot that one Subnautica should act on and the others only show
	 * (an explosion: only one should hurt the creatures, or with Subnautica's own multiplayer
	 * they would be hurt once for every player). The linked player nearest the spot gets the
	 * first line; every other linked player gets the second.
	 */
	public static void sendNearest(net.minecraft.world.World world, net.minecraft.util.math.Vec3d spot, String forNearest, String forOthers) {
		MinecraftServer current = server;

		if (current == null) {
			return;
		}

		ServerPlayerEntity nearest = null;
		double nearestDistance = Double.MAX_VALUE;

		for (ServerPlayerEntity player : current.getPlayerManager().getPlayerList()) {
			if (player.getWorld() == world && isActive(player) && player.squaredDistanceTo(spot) < nearestDistance) {
				nearestDistance = player.squaredDistanceTo(spot);
				nearest = player;
			}
		}

		for (ServerPlayerEntity player : current.getPlayerManager().getPlayerList()) {
			if (isActive(player)) {
				get(player).send(current, player, player == nearest ? forNearest : forOthers);
			}
		}
	}

	/** A line arrived from a player's game: something their Subnautica said. */
	public static void onLine(MinecraftServer current, ServerPlayerEntity player, String line) {
		server = current;
		LinkSession session = get(player);
		noteHost(current, player, session);
		session.onLine(current, player, line);
	}

	public static void onJoin(MinecraftServer current, ServerPlayerEntity player) {
		server = current;
		noteHost(current, player, get(player));
	}

	private static void noteHost(MinecraftServer current, ServerPlayerEntity player, LinkSession session) {
		if (current.isHost(player.getGameProfile())) {
			host = session;
		}
	}

	public static void onLeave(ServerPlayerEntity player) {
		// Everyone else's game is told this player's light has gone (while they still have a session to be told through).
		note("@LIGHT " + player.getId() + " 0");

		LinkSession session = ALL.remove(player.getUuid());

		if (session != null && session == host) {
			host = null;
		}

		// Off any vehicle seat first: Minecraft saves whatever a leaving player is riding along
		// with them, and the unseen mount would come back when they do.
		Riding.end(player);

		// And back to where they were before the ocean void, so that is where Minecraft saves
		// them. (Otherwise they would be saved in the void, the way back forgotten, and put
		// at the world's spawn point next time.)
		MinecraftServer current = server;

		if (session != null && current != null) {
			try {
				session.goHome(current, player);
			} catch (RuntimeException e) {
				SubnauticaLink.LOGGER.warn("Could not send {} home before they left", player.getName().getString(), e);
			}
		}

		FallTracker.forget(player);
		ToolTokens.forget(player.getUuid());
		Gathering.forget(player.getUuid());
	}

	public static void clear() {
		ALL.clear();
		host = null;
		server = null;
	}

	/** Called at the end of every server tick. */
	public static void tick(MinecraftServer current) {
		server = current;

		for (LinkSession session : ALL.values()) {
			ServerPlayerEntity player = current.getPlayerManager().getPlayer(session.id);

			if (player != null) {
				session.tick(current, player);
			}
		}
	}
}
