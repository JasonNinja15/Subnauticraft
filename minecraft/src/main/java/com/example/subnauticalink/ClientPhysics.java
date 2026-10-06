package com.example.subnauticalink;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.projectile.ProjectileEntity;
import net.minecraft.util.math.Vec3d;

/**
 * Finds out what projectiles are about to hit in Subnautica, and tells the server. Client only.
 *
 * <p>Minecraft's projectiles can't see Subnautica's scenery or creatures. Each player's
 * Subnautica can. So each player's game looks after the projectiles that are theirs (the
 * ones they threw, plus any nobody threw that are near them): every tick it asks its own
 * Subnautica what lies along the next stretch of each one's path ("RAYS ..."), and when the
 * answer ("RAYHIT ...") says something is in the way, it reports the spot to the server
 * ("PHIT id x y z nx ny nz creature"). The server then makes the hit happen for everyone (see
 * {@link Projectiles}).
 *
 * <p>The stretch asked about is a few ticks long, not one. The answer and the report both take
 * a moment to travel, and looking ahead means the server hears about a hit before the
 * projectile gets there rather than after it has flown through.
 */
public final class ClientPhysics {
	/** Projectiles within this many blocks of the player are looked after. */
	private static final double WITHIN = 64.0;

	/** How many ticks of flight ahead of each projectile are checked. */
	private static final double TICKS_AHEAD = 3.0;

	/** One question in flight: which projectiles it asked about, where each was and how far ahead it looked. */
	private static final class Question {
		int[] ids;
		Vec3d[] from;
		Vec3d[] stretch;
	}

	private static final Map<Integer, Question> QUESTIONS = new ConcurrentHashMap<>();
	private static int lastId;

	private ClientPhysics() {
	}

	public static void reset() {
		QUESTIONS.clear();
	}

	/** Called every client tick. */
	public static void tick(MinecraftClient client) {
		if (client.world == null || client.player == null || !RemoteCollision.appliesTo(client.player)) {
			return;
		}

		List<ProjectileEntity> mine = new ArrayList<>();

		for (Entity entity : client.world.getEntities()) {
			if (entity instanceof ProjectileEntity projectile
					&& entity.squaredDistanceTo(client.player) < WITHIN * WITHIN
					&& Projectiles.isFlying(projectile)
					&& Projectiles.stuckHere(entity.getId()) == null
					&& isMine(client, projectile)) {
				mine.add(projectile);
			}
		}

		// Unanswered questions don't pile up for ever.
		if (QUESTIONS.size() > 40) {
			QUESTIONS.clear();
		}

		if (mine.isEmpty()) {
			return;
		}

		Question question = new Question();
		question.ids = new int[mine.size()];
		question.from = new Vec3d[mine.size()];
		question.stretch = new Vec3d[mine.size()];

		lastId = (lastId + 1) % 1_000_000;
		StringBuilder line = new StringBuilder("RAYS ").append(lastId).append(' ').append(mine.size());

		for (int i = 0; i < mine.size(); i++) {
			ProjectileEntity projectile = mine.get(i);
			Vec3d from = projectile.getPos();
			Vec3d stretch = projectile.getVelocity().multiply(TICKS_AHEAD);
			question.ids[i] = projectile.getId();
			question.from[i] = from;
			question.stretch[i] = stretch;
			line.append(String.format(Locale.ROOT, " %.4f %.4f %.4f %.5f %.5f %.5f", from.x, from.y, from.z, stretch.x, stretch.y, stretch.z));
		}

		QUESTIONS.put(lastId, question);
		ClientLink.toSubnautica(line.toString());
	}

	/**
	 * Whether this player's game should look after a projectile: they threw it, or no player
	 * did (a dispenser, say). Another player's projectile is left to that player's game.
	 */
	private static boolean isMine(MinecraftClient client, ProjectileEntity projectile) {
		Entity owner = projectile.getOwner();
		return owner == client.player || !(owner instanceof PlayerEntity);
	}

	/**
	 * "id" then five numbers for each projectile asked about: how far along its stretch the
	 * hit is (0 to 1, or -1 for nothing), which way the surface faces, and 1 for a creature.
	 * Called by the connection's own thread, the moment the answer arrives.
	 */
	public static void onAnswer(String numbers) {
		String[] parts = numbers.trim().split(" ");

		try {
			Question question = QUESTIONS.remove(Integer.parseInt(parts[0]));

			if (question == null || parts.length != 1 + question.ids.length * 5) {
				return;
			}

			for (int i = 0; i < question.ids.length; i++) {
				double along = Double.parseDouble(parts[1 + i * 5]);

				if (along < 0.0) {
					continue;
				}

				Vec3d point = question.from[i].add(question.stretch[i].multiply(Math.min(along, 1.0)));
				String report = String.format(Locale.ROOT, "PHIT %d %.4f %.4f %.4f %s %s %s %s", question.ids[i],
						point.x, point.y, point.z, parts[2 + i * 5], parts[3 + i * 5], parts[4 + i * 5], parts[5 + i * 5]);

				// Handed to the game's own thread to send.
				MinecraftClient.getInstance().execute(() -> ClientLink.toServer(report));
			}
		} catch (NumberFormatException e) {
			// A garbled answer; these projectiles are asked about again next tick.
		}
	}
}
