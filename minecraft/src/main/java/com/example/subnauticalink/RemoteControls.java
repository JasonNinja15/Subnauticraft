package com.example.subnauticalink;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

import net.minecraft.util.math.MathHelper;

/**
 * The controls being held in Subnautica, as last reported.
 *
 * <p>Subnautica is the window in front, so it receives the keyboard and mouse. It sends what
 * is being pressed, and Minecraft acts as if those keys were pressed here. This class is the
 * hand-over point: the network side writes the latest values, and Minecraft's own game loop
 * reads them (see SubnauticaLinkClient).
 *
 * <p>The fields are "volatile" because they are written and read by different threads.
 */
public final class RemoteControls {
	/** If nothing has arrived for this long, treat every key as released. */
	private static final long SILENCE_BEFORE_RELEASE_MS = 2000;

	/** -1 = back, 0 = neither, 1 = forward. */
	public static volatile float forward;
	/** -1 = left, 0 = neither, 1 = right. */
	public static volatile float strafe;
	public static volatile boolean jump;
	public static volatile boolean sneak;
	public static volatile boolean sprint;
	/** Facing direction, already converted to Minecraft's angles. */
	public static volatile float yaw;
	public static volatile float pitch;
	/** Left and right mouse buttons. */
	public static volatile boolean attack;
	public static volatile boolean use;

	/** A hotbar slot (0 to 8) picked with a number key and not yet acted on, or -1. */
	private static final AtomicInteger pickedSlot = new AtomicInteger(-1);
	/**
	 * What Subnautica's camera is pointed at, if it is solid scenery within reach: the point
	 * (first three numbers) and the direction the surface faces (last three), already in
	 * Minecraft's coordinates. Null when nothing is in reach.
	 */
	public static volatile double[] aim;

	/** Whether what the camera is pointed at is Subnautica's terrain (sea floor, rock), not something built or placed. */
	public static volatile boolean aimIsTerrain;

	/**
	 * Where the mouse pointer is in Subnautica's window while a Minecraft screen is open, as
	 * fractions of the window (0,0 is the top left corner, 1,1 the bottom right).
	 */
	/** Whether Shift was held in Subnautica at the last click on a Minecraft screen. */
	public static volatile boolean shiftHeld;

	/**
	 * True while Subnautica is stopped (its menu is up, playing alone) and says so ("HOLD 1"):
	 * the player is held exactly where they are until it starts again. Minecraft itself never
	 * stops while linked, so without this the player went on moving through a frozen world.
	 */
	public static volatile boolean held;

	public static volatile double pointerX = 0.5;
	public static volatile double pointerY = 0.5;

	/** "x y": the pointer moved. Called by the connection's own thread, the moment the line arrives. */
	public static void onPointerLine(String numbers) {
		String[] parts = numbers.trim().split(" ");

		if (parts.length != 2) {
			return;
		}

		try {
			pointerX = Math.max(0.0, Math.min(1.0, Double.parseDouble(parts[0])));
			pointerY = Math.max(0.0, Math.min(1.0, Double.parseDouble(parts[1])));
		} catch (NumberFormatException e) {
			// A garbled line; ignore it.
		}
	}

	/** Typing and screen clicks passed on from Subnautica ("CHAT /", "TYPE 97", "KEY 257"), waiting for the client to act on it. */
	private static final Queue<String> typing = new ConcurrentLinkedQueue<>();

	/** Left-button presses the server has not yet turned into attacks. */
	private static final AtomicInteger attackPresses = new AtomicInteger();

	/** Right-button presses not yet acted on (so a quick tap between two ticks still counts). */
	private static final AtomicInteger usePresses = new AtomicInteger();
	/** Scroll wheel clicks not yet acted on: positive moves right along the hotbar. */
	private static final AtomicInteger scrolled = new AtomicInteger();

	private static volatile long lastUpdateMs;

	private RemoteControls() {
	}

	public static void update(float forward, float strafe, boolean jump, boolean sneak, boolean sprint, float yaw, float pitch, boolean attack, boolean use) {
		// The button going down is one press, however long it is then held.
		if (attack && !RemoteControls.attack) {
			attackPresses.incrementAndGet();
		}

		if (use && !RemoteControls.use) {
			usePresses.incrementAndGet();
		}

		RemoteControls.attack = attack;
		RemoteControls.use = use;
		RemoteControls.forward = forward;
		RemoteControls.strafe = strafe;
		RemoteControls.jump = jump;
		RemoteControls.sneak = sneak;
		RemoteControls.sprint = sprint;
		RemoteControls.yaw = yaw;
		RemoteControls.pitch = pitch;
		lastUpdateMs = System.currentTimeMillis();
	}

	/** "INPUT forward strafe jump sneak sprint yaw pitch attack use": the controls held in Subnautica. */
	public static void onInputLine(String numbers) {
		String[] parts = numbers.trim().split(" ");

		if (parts.length != 9) {
			return;
		}

		try {
			float forward = Float.parseFloat(parts[0]);
			float strafe = Float.parseFloat(parts[1]);
			boolean jump = parts[2].equals("1");
			boolean sneak = parts[3].equals("1");
			boolean sprint = parts[4].equals("1");
			float yaw = MathHelper.wrapDegrees(Float.parseFloat(parts[5]) + 180.0F);
			float pitch = MathHelper.clamp(MathHelper.wrapDegrees(Float.parseFloat(parts[6])), -90.0F, 90.0F);
			boolean attack = parts[7].equals("1");
			boolean use = parts[8].equals("1");
			update(forward, strafe, jump, sneak, sprint, yaw, pitch, attack, use);
		} catch (NumberFormatException e) {
			// A garbled line; ignore it.
		}
	}

	/**
	 * "AIM x y z nx ny nz terrain" or "AIM -": the point on Subnautica's scenery the camera is looking
	 * at, which way that surface faces, and 1 if it is terrain (which can be dug at). Used to place blocks against Subnautica's scenery.
	 * As everywhere, z is flipped between the two games.
	 */
	public static void onAimLine(String numbers) {
		String[] parts = numbers.trim().split(" ");

		if (parts.length != 6 && parts.length != 7) {
			aim = null;
			aimIsTerrain = false;
			return;
		}

		try {
			double[] point = new double[6];

			for (int i = 0; i < 6; i++) {
				point[i] = Double.parseDouble(parts[i]);
			}

			point[2] = -point[2];
			point[5] = -point[5];
			aimIsTerrain = parts.length == 7 && parts[6].equals("1");
			aim = point;
		} catch (NumberFormatException e) {
			aim = null;
		}
	}

	/** True while Subnautica is actively sending controls. */
	public static boolean isActive() {
		return System.currentTimeMillis() - lastUpdateMs < SILENCE_BEFORE_RELEASE_MS;
	}

	/** "SLOT n": a number key was pressed. */
	public static void pickSlot(int slot) {
		if (slot >= 0 && slot <= 8) {
			pickedSlot.set(slot);
		}
	}

	/** "SCROLL d": the scroll wheel moved one click. */
	public static void scroll(int direction) {
		scrolled.addAndGet(Integer.signum(direction));
	}

	public static void addTyping(String event) {
		typing.add(event);
	}

	/** Hands over the next piece of typing, or null if there is none. */
	public static String takeTyping() {
		return typing.poll();
	}

	/** Hands over how many times the left button was pressed since last time, and forgets them. */
	public static int takeAttackPresses() {
		return attackPresses.getAndSet(0);
	}

	/** Hands over how many times the right button was pressed since last time, and forgets them. */
	public static int takeUsePresses() {
		return usePresses.getAndSet(0);
	}

	/** Hands over the picked slot (or -1) and forgets it. */
	public static int takePickedSlot() {
		return pickedSlot.getAndSet(-1);
	}

	/** Hands over the scroll clicks since last time and forgets them. */
	public static int takeScroll() {
		return scrolled.getAndSet(0);
	}

	public static void clear() {
		lastUpdateMs = 0;
		attack = false;
		use = false;
		pickedSlot.set(-1);
		attackPresses.set(0);
		usePresses.set(0);
		shiftHeld = false;
		held = false;
		aim = null;
		aimIsTerrain = false;
		typing.clear();
		scrolled.set(0);
	}
}
