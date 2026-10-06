package com.example.subnauticalink;

import java.nio.file.Path;

/**
 * Where the picture of Minecraft's HUD is shared with Subnautica, and how it is laid out.
 *
 * <p>The picture is far too big to send as text 60 times a second, so it goes through a
 * "memory-mapped file": a file that both games open as if it were a block of their own memory.
 * Minecraft writes the pixels into it and Subnautica reads them straight back out. Windows
 * keeps the file's contents in memory, so nothing waits on the disk.
 *
 * <p>Layout of the file (all numbers are 4 bytes, low byte first):
 * <pre>
 *   byte  0: a fixed marker, so Subnautica can tell the file is really ours
 *   byte  4: picture width in pixels
 *   byte  8: picture height in pixels
 *   byte 12: frame number, raised by one each time a new picture is complete
 *   byte 16: which of the two picture slots holds that complete picture (0 or 1)
 *   byte 64: slot 0
 *   then:    slot 1
 * </pre>
 * There are two slots so that Minecraft always writes into the one Subnautica is not reading.
 * Each pixel is 4 bytes (red, green, blue, opacity), rows running from the bottom up.
 *
 * <p>This class holds only the plain facts both the server and client parts need. The drawing
 * itself is in {@code OverlayShare}.
 */
public final class OverlayFile {
	public static final int MARKER = 0x4B4C4E53;
	public static final int HEADER_BYTES = 64;

	/** The biggest picture there is room for (a 4K screen). */
	public static final int MAX_WIDTH = 3840;
	public static final int MAX_HEIGHT = 2160;
	public static final int SLOT_BYTES = MAX_WIDTH * MAX_HEIGHT * 4;
	public static final long FILE_BYTES = HEADER_BYTES + 2L * SLOT_BYTES;

	/** The block atlas (every block texture in one picture), once the client has written it out: its size, or 0. */
	public static volatile int atlasWidth;
	public static volatile int atlasHeight;

	/**
	 * True once a block with a moving picture (fire, say) has been shown. From then on the
	 * client keeps writing the atlas out, so Subnautica's copy moves too.
	 */
	public static volatile boolean atlasAnimated;

	/** Subnautica's screen size, once it has told us ("SCREEN w h"), or 0. */
	public static volatile int screenWidth;
	public static volatile int screenHeight;

	private OverlayFile() {
	}

	/** The shared file. It lives in Windows' temporary folder. */
	public static Path path() {
		return Path.of(System.getProperty("java.io.tmpdir"), "subnautica_link_overlay.bin");
	}

	/**
	 * The file the block atlas is written to. It starts with three numbers of 4 bytes each (a
	 * marker, the width and the height) and one spare, then the pixels: 4 bytes each (red,
	 * green, blue, opacity).
	 */
	public static Path atlasPath() {
		return Path.of(System.getProperty("java.io.tmpdir"), "subnautica_link_atlas.bin");
	}

	/** "SCREEN w h": the size of Subnautica's screen, so Minecraft can draw its HUD at the same size. */
	public static void onScreenLine(String numbers) {
		String[] parts = numbers.trim().split(" ");

		if (parts.length != 2) {
			return;
		}

		try {
			int width = Integer.parseInt(parts[0]);
			int height = Integer.parseInt(parts[1]);

			if (width >= 320 && width <= MAX_WIDTH && height >= 240 && height <= MAX_HEIGHT) {
				screenWidth = width;
				screenHeight = height;
			}
		} catch (NumberFormatException e) {
			// A garbled line; ignore it.
		}
	}

	public static void reset() {
		screenWidth = 0;
		screenHeight = 0;
	}
}
