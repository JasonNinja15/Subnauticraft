package com.example.subnauticalink;

import java.util.Locale;
import java.util.function.Consumer;

import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

/**
 * Collision against Subnautica's real scenery.
 *
 * <p>Minecraft still works out the player's movement (walking speed, jumping, gravity). But at
 * the point where it would normally check for blocks in the way, it asks Subnautica instead:
 * "the player's box is here and wants to move this far; how far does it get?". Subnautica
 * slides that box through its own scenery, exactly as shaped, and answers. Because the answer
 * comes from Subnautica's physics, slopes are smooth and gaps are their true width.
 *
 * <p>The question goes out as "SWEEP ..." and the answer comes back as "SWEPT ...". Minecraft
 * waits for the answer before carrying on with the tick. Subnautica answers twice per frame,
 * so the wait is a few thousandths of a second. If no answer comes in time (Subnautica is
 * loading, say), the player simply doesn't move that tick.
 */
public final class RemoteCollision {
	/** The longest Minecraft waits for one answer, in milliseconds. A tick is 50. */
	private static final long PATIENCE_MS = 35;

	/** After this many unanswered questions in a row, stop waiting properly until answers return. */
	private static final int GIVE_UP_AFTER = 3;

	/** Answers this close to what was asked for count as "nothing in the way". */
	private static final double SAME = 1.0E-5;

	private static final Object LOCK = new Object();

	/** Who to hand answers about dropped items to. Set by the player's own game, which passes them to the server. */
	public static volatile Consumer<String> itemAnswers;

	private static volatile LinkConnection link;
	private static volatile int missedInARow;

	// Guarded by LOCK: the question being waited on, and its answer once it arrives.
	private static int lastId;
	private static int waitingFor = -1;
	private static String answer;

	// Only used on the client thread: what to restore after Minecraft finishes the move.
	private static Entity movedEntity;
	private static Vec3d speedBefore;
	private static double[] walls;

	private RemoteCollision() {
	}

	/** Called when a world opens (with its connection) and when it closes (with null). */
	public static void setLink(LinkConnection connection) {
		link = connection;
		missedInARow = 0;
	}

	/** Sends one line to this computer's Subnautica, from the player's own game. Does nothing if it isn't connected. */
	public static void sendLine(String line) {
		LinkConnection connection = link;

		if (connection != null) {
			connection.send(line);
		}
	}

	/** True for the player's own character while it is in the ocean void under Subnautica's controls. */
	public static boolean appliesTo(Entity entity) {
		LinkConnection connection = link;

		// Only the player's own character: other players seen in this game move by themselves.
		return entity instanceof PlayerEntity player
				&& player.isMainPlayer()
				&& entity.getWorld().isClient
				&& connection != null
				&& connection.isConnected()
				&& RemoteControls.isActive()
				&& entity.getWorld().getRegistryKey() == MovementBridge.OCEAN_VOID;
	}

	/**
	 * True for a boat this game is steering (its own player is at the oars) in the ocean void:
	 * it runs into Subnautica's scenery just as the player does on foot.
	 */
	public static boolean appliesToBoat(Entity entity) {
		return entity instanceof net.minecraft.entity.vehicle.BoatEntity boat
				&& entity.getWorld().isClient
				&& boat.getControllingPassenger() instanceof PlayerEntity rower
				&& appliesTo(rower);
	}

	/** True for the player's own character while it is in the ocean void but Subnautica isn't sending controls. */
	public static boolean strandedInVoid(Entity entity) {
		return entity instanceof PlayerEntity player
				&& player.isMainPlayer()
				&& entity.getWorld().isClient
				&& entity.getWorld().getRegistryKey() == MovementBridge.OCEAN_VOID
				&& !entity.hasVehicle()
				&& !appliesTo(entity);
	}

	/**
	 * True for the linked player as the server sees them. The server re-runs each of the
	 * player's moves to check it; for the linked player that check must not use Minecraft's
	 * blocks, because the real collision has already been done by Subnautica.
	 */
	public static boolean isLinkedOnServer(Entity entity) {
		return entity instanceof PlayerEntity
				&& !entity.getWorld().isClient
				&& Sessions.isActive(entity)
				&& entity.getWorld().getRegistryKey() == MovementBridge.OCEAN_VOID;
	}

	/**
	 * Asks Subnautica how far the entity can move, and returns the answer in the form
	 * Minecraft's movement code expects: the movement that is actually possible.
	 */
	public static Vec3d resolve(Entity entity, Vec3d movement) {
		movedEntity = null;
		walls = null;

		Box box = entity.getBoundingBox();
		String reply = ask(box, movement, entity.getStepHeight(), entity.isOnGround());

		if (reply == null) {
			// No answer in time. Staying put is the safe choice.
			return Vec3d.ZERO;
		}

		// "id rx ry rz ground wallCount [nx nz]..."
		String[] parts = reply.split(" ");

		try {
			double x = Double.parseDouble(parts[1]);
			double y = Double.parseDouble(parts[2]);
			double z = Double.parseDouble(parts[3]);
			boolean ground = parts[4].equals("1");
			int wallCount = Integer.parseInt(parts[5]);

			// Minecraft decides "did I hit something?" by checking whether the answer differs
			// from what it asked for, so tiny rounding differences must not count.
			if (Math.abs(x - movement.x) < SAME) {
				x = movement.x;
			}

			if (Math.abs(y - movement.y) < SAME) {
				y = movement.y;
			}

			if (Math.abs(z - movement.z) < SAME) {
				z = movement.z;
			}

			// Likewise, Minecraft decides "am I standing on something?" by seeing a fall cut
			// short. If Subnautica says there is ground, make sure it reads that way.
			if (ground && movement.y < 0.0 && y == movement.y) {
				y = Math.nextUp(y);
			}

			if (wallCount > 0) {
				double[] normals = new double[wallCount * 2];

				for (int i = 0; i < normals.length; i++) {
					normals[i] = Double.parseDouble(parts[6 + i]);
				}

				// Remembered for afterMove().
				movedEntity = entity;
				speedBefore = entity.getVelocity();
				walls = normals;
			}

			return new Vec3d(x, y, z);
		} catch (NumberFormatException | ArrayIndexOutOfBoundsException e) {
			return Vec3d.ZERO;
		}
	}

	// Only used on the client thread: what has been found out this tick about room overhead.
	// Anything up to "roomFits" tall is known to fit; anything from "roomTooTall" up is known not to.
	private static int roomAge = -1;
	private static float roomFits;
	private static float roomTooTall;

	/**
	 * Whether the linked player has room to be this tall where they are: to stand up from a
	 * crouch or a swim. Being no taller than they already are always fits. Otherwise the space
	 * that would be added above their head has to be clear of Minecraft's blocks, and their
	 * box has to be able to rise that far through Subnautica's scenery (which is asked, with
	 * the same question as for moving).
	 */
	public static boolean hasRoomFor(Entity entity, float tall) {
		Box box = entity.getBoundingBox();
		double extra = tall - box.getLengthY();

		if (extra <= 0.01) {
			return true;
		}

		// Minecraft asks this several times a tick, about crouching and about standing, before
		// the player moves and after. What one answer settles isn't asked again that tick: if
		// crouching doesn't fit, standing doesn't either.
		if (entity.age != roomAge) {
			roomAge = entity.age;
			roomFits = 0.0F;
			roomTooTall = Float.MAX_VALUE;
		}

		if (tall <= roomFits) {
			return true;
		}

		if (tall >= roomTooTall) {
			return false;
		}

		// Minecraft's blocks: just the added space, drawn in a little so a wall being leaned on doesn't count.
		Box added = new Box(box.minX + 0.05, box.maxY + 0.02, box.minZ + 0.05, box.maxX - 0.05, box.minY + tall - 0.02, box.maxZ - 0.05);
		boolean room = entity.getWorld().isSpaceEmpty(added);

		if (room) {
			String reply = ask(box, new Vec3d(0.0, extra, 0.0), 0.0F, false);

			// No answer (Subnautica is busy): don't keep the player down on a guess.
			if (reply != null) {
				try {
					// "id rx ry rz ...": it has to get all the way up, and straight up. (Under a
					// slanting roof the box slides along it; that isn't room to stand.)
					String[] parts = reply.split(" ");
					double sideways = Math.abs(Double.parseDouble(parts[1])) + Math.abs(Double.parseDouble(parts[3]));
					room = Double.parseDouble(parts[2]) >= extra - 0.03 && sideways < 0.1;
				} catch (NumberFormatException | ArrayIndexOutOfBoundsException e) {
					// A garbled answer: likewise.
				}
			}
		}

		if (room) {
			roomFits = tall;
		} else {
			roomTooTall = tall;
		}

		return room;
	}

	/**
	 * Called when Minecraft has finished moving the entity.
	 *
	 * <p>Minecraft's walls only ever face north, south, east or west, so when it hits one it
	 * simply stops all speed in that direction. Subnautica's walls face any way at all, and
	 * that rule would stop the player dead against a slanted one. This puts back the speed the
	 * player had, minus only the part heading into the wall, so they slide along it.
	 */
	public static void afterMove(Entity entity) {
		if (entity != movedEntity || walls == null) {
			return;
		}

		double x = speedBefore.x;
		double z = speedBefore.z;

		for (int i = 0; i < walls.length; i += 2) {
			double into = x * walls[i] + z * walls[i + 1];

			if (into < 0.0) {
				x -= into * walls[i];
				z -= into * walls[i + 1];
			}
		}

		// In a corner, sliding off one wall can push into the other. Then there is nowhere to go.
		for (int i = 0; i < walls.length; i += 2) {
			if (x * walls[i] + z * walls[i + 1] < -1.0E-6) {
				x = 0.0;
				z = 0.0;
			}
		}

		entity.setVelocity(x, entity.getVelocity().y, z);
		movedEntity = null;
		walls = null;
	}

	/** Called by the connection's own thread for every "SWEPT ..." line, the moment it arrives. */
	public static void onAnswer(String numbers) {
		int space = numbers.indexOf(' ');

		if (space < 0) {
			return;
		}

		int id;

		try {
			id = Integer.parseInt(numbers.substring(0, space));
		} catch (NumberFormatException e) {
			return;
		}


		// Not the player's: an answer about a dropped item. That question came from the
		// server, so the answer goes back to it.
		if (id >= ItemSync.FIRST_QUESTION) {
			Consumer<String> listener = itemAnswers;

			if (listener != null) {
				listener.accept(numbers);
			}

			return;
		}

		// Any answer to one of the player's own questions, even a late one, shows Subnautica
		// is answering again, so the next question is waited on properly. (Answers about
		// items, above, say nothing about that: they aren't being waited on.)
		missedInARow = 0;

		synchronized (LOCK) {
			if (id == waitingFor) {
				answer = numbers;
				LOCK.notifyAll();
			}
		}
	}

	/** Sends one question and waits for its answer. Returns null if none came in time. */
	private static String ask(Box box, Vec3d movement, float stepHeight, boolean onGround) {
		LinkConnection connection = link;

		if (connection == null) {
			return null;
		}

		int id;

		synchronized (LOCK) {
			// Kept small: Subnautica reads every number on the line the same way, as a decimal.
			lastId = (lastId + 1) % 1_000_000;
			id = lastId;
			waitingFor = id;
			answer = null;
		}

		Vec3d centre = box.getCenter();

		// "SWEEP id cx cy cz hx hy hz dx dy dz step ground": the box's centre and half its
		// size in each direction, the movement wanted, how high the entity can step up, and
		// whether it is standing on the ground.
		connection.send(String.format(Locale.ROOT, "SWEEP %d %.4f %.4f %.4f %.4f %.4f %.4f %.6f %.6f %.6f %.3f %d",
				id, centre.x, centre.y, centre.z,
				box.getLengthX() / 2.0, box.getLengthY() / 2.0, box.getLengthZ() / 2.0,
				movement.x, movement.y, movement.z,
				stepHeight, onGround ? 1 : 0));

		// If Subnautica has stopped answering, don't hold every tick up for it.
		long patience = missedInARow >= GIVE_UP_AFTER ? 5 : PATIENCE_MS;
		long deadline = System.nanoTime() + patience * 1_000_000L;
		String result;

		synchronized (LOCK) {
			while (answer == null) {
				long left = (deadline - System.nanoTime()) / 1_000_000L;

				if (left <= 0) {
					break;
				}

				try {
					LOCK.wait(left);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					break;
				}
			}

			result = answer;
			waitingFor = -1;
			answer = null;
		}

		if (result == null) {
			missedInARow++;
		}

		return result;
	}
}
