package com.example.subnauticalink;

import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;

import java.nio.file.StandardOpenOption;

import com.mojang.blaze3d.platform.GlStateManager;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryUtil;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.Framebuffer;
import net.minecraft.client.texture.SpriteAtlasTexture;
import net.minecraft.client.util.Window;

/**
 * Sends the picture of Minecraft's HUD to Subnautica. Client only.
 *
 * <p>While linked, three things happen every frame Minecraft draws:
 * <ol>
 *   <li>After the world is drawn and before the HUD, the picture is wiped to fully
 *       see-through ({@link #clearToTransparent}). So the finished frame holds only your hand
 *       and the HUD (hearts, hunger, bubbles, hotbar, chat) on a see-through background.</li>
 *   <li>When the frame is finished, its pixels are copied into the shared file
 *       ({@link #capture}). See {@link OverlayFile} for the layout.</li>
 *   <li>Subnautica reads them and draws them over its own picture.</li>
 * </ol>
 *
 * <p>Everything here runs on Minecraft's render thread.
 */
public final class OverlayShare {
	/** Copy at most this many pictures a second. Minecraft may draw far more when its window is hidden. */
	private static final long SHORTEST_GAP_NANOS = 1_000_000_000L / 60;

	private static MappedByteBuffer shared;
	private static boolean sharingFailed;
	private static int frameNumber;
	private static int currentSlot;
	private static long lastCaptureTime;
	private static boolean atlasWritten;
	private static boolean atlasFailed;
	private static long lastAtlasTime;
	private static int atlasNumber;
	private static ByteBuffer atlasPixels;
	private static FileChannel atlasOut;

	/** The atlas is written out again at most this often while something on it is moving. */
	private static final long ATLAS_GAP_NANOS = 100_000_000L;

	// The window size before it was changed to match Subnautica's screen, to put it back afterwards.
	private static int savedWidth;
	private static int savedHeight;
	private static int appliedWidth;
	private static int appliedHeight;

	private OverlayShare() {
	}

	/** True while the player is in the ocean void under Subnautica's controls. */
	public static boolean isActive() {
		MinecraftClient client = MinecraftClient.getInstance();

		return client.player != null
				&& RemoteControls.isActive()
				&& client.player.getWorld().getRegistryKey() == MovementBridge.OCEAN_VOID;
	}

	/** Wipes the frame so far (the sky of the ocean void) to fully see-through, ready for the hand and HUD. */
	public static void clearToTransparent() {
		GL11.glClearColor(0.0F, 0.0F, 0.0F, 0.0F);
		GL11.glClear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);
	}

	/** Called when Minecraft has finished drawing a frame. */
	public static void capture() {
		MinecraftClient client = MinecraftClient.getInstance();

		if (!isActive()) {
			restoreWindowSize(client);
			return;
		}

		matchSubnauticaScreen(client);
		writeBlockAtlas(client);

		long now = System.nanoTime();

		if (now - lastCaptureTime < SHORTEST_GAP_NANOS) {
			return;
		}

		Framebuffer frame = client.getFramebuffer();
		int width = frame.textureWidth;
		int height = frame.textureHeight;

		if (width <= 0 || height <= 0 || width > OverlayFile.MAX_WIDTH || height > OverlayFile.MAX_HEIGHT) {
			return;
		}

		MappedByteBuffer file = open();

		if (file == null) {
			return;
		}

		lastCaptureTime = now;

		// Write into the slot Subnautica is not reading from.
		int slot = 1 - currentSlot;
		int bytes = width * height * 4;
		ByteBuffer pixels = file.slice(OverlayFile.HEADER_BYTES + slot * OverlayFile.SLOT_BYTES, bytes).order(ByteOrder.LITTLE_ENDIAN);

		// Ask the graphics card for the finished frame, written straight into the shared file.
		GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, frame.fbo);
		GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 1);
		GL11.glPixelStorei(GL11.GL_PACK_ROW_LENGTH, 0);
		GL11.glPixelStorei(GL11.GL_PACK_SKIP_ROWS, 0);
		GL11.glPixelStorei(GL11.GL_PACK_SKIP_PIXELS, 0);
		GL11.glReadPixels(0, 0, width, height, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixels);

		fixSeeThroughPixels(pixels, bytes);

		// Publish: fill in the details, and raise the frame number last so Subnautica only
		// ever sees a complete picture.
		file.putInt(0, OverlayFile.MARKER);
		file.putInt(4, width);
		file.putInt(8, height);
		file.putInt(16, slot);
		file.putInt(12, ++frameNumber);
		currentSlot = slot;
	}

	/**
	 * Partly see-through pixels (the hotbar's background, chat's backdrop) come out of
	 * Minecraft with their colour already dimmed by how see-through they are. Subnautica dims
	 * them again when it draws them, which would make them too dark. This undoes Minecraft's
	 * dimming. Fully solid and fully see-through pixels, which is nearly all of them, are
	 * left alone.
	 */
	private static void fixSeeThroughPixels(ByteBuffer pixels, int bytes) {
		for (int i = 0; i < bytes; i += 4) {
			// One pixel as a single number: opacity in the top byte, then blue, green, red.
			int pixel = pixels.getInt(i);
			int opacity = pixel >>> 24;

			if (opacity == 0 || opacity == 255) {
				continue;
			}

			int red = Math.min(255, (pixel & 0xFF) * 255 / opacity);
			int green = Math.min(255, ((pixel >>> 8) & 0xFF) * 255 / opacity);
			int blue = Math.min(255, ((pixel >>> 16) & 0xFF) * 255 / opacity);
			pixels.putInt(i, (opacity << 24) | (blue << 16) | (green << 8) | red);
		}
	}

	/**
	 * Writes the block atlas to a file for Subnautica to texture blocks with. Minecraft keeps
	 * the atlas on the graphics card, so it is read back from there.
	 *
	 * <p>Normally once is enough. But some pictures on the atlas move (fire, lava, sea
	 * lanterns): Minecraft redraws those parts of the atlas as time passes. Once a block with
	 * a moving picture is being shown, the atlas is written out again ten times a second, with
	 * a counter in its heading so Subnautica can tell there is a new one.
	 */
	private static void writeBlockAtlas(MinecraftClient client) {
		long now = System.nanoTime();

		if (atlasFailed || (atlasWritten && (!OverlayFile.atlasAnimated || now - lastAtlasTime < ATLAS_GAP_NANOS))) {
			return;
		}

		boolean first = !atlasWritten;
		atlasWritten = true;
		lastAtlasTime = now;

		try {
			int texture = client.getTextureManager().getTexture(SpriteAtlasTexture.BLOCK_ATLAS_TEXTURE).getGlId();
			GlStateManager._bindTexture(texture);
			int width = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_WIDTH);
			int height = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_HEIGHT);

			if (width <= 0 || height <= 0 || width > 16384 || height > 16384) {
				SubnauticaLink.LOGGER.warn("The block atlas has an unexpected size ({} x {}); blocks stay untextured", width, height);
				atlasFailed = true;
				return;
			}

			int bytes = 16 + width * height * 4;

			// The same memory is used every time; it is only replaced if the atlas changes size.
			if (atlasPixels == null || atlasPixels.capacity() != bytes) {
				if (atlasPixels != null) {
					MemoryUtil.memFree(atlasPixels);
				}

				atlasPixels = MemoryUtil.memAlloc(bytes).order(ByteOrder.LITTLE_ENDIAN);
			}

			atlasPixels.putInt(0, OverlayFile.MARKER);
			atlasPixels.putInt(4, width);
			atlasPixels.putInt(8, height);
			atlasPixels.putInt(12, ++atlasNumber);

			GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 1);
			GL11.glPixelStorei(GL11.GL_PACK_ROW_LENGTH, 0);
			GL11.glPixelStorei(GL11.GL_PACK_SKIP_ROWS, 0);
			GL11.glPixelStorei(GL11.GL_PACK_SKIP_PIXELS, 0);
			GL11.glGetTexImage(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, atlasPixels.slice(16, width * height * 4));

			if (atlasOut == null) {
				atlasOut = FileChannel.open(OverlayFile.atlasPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
			}

			ByteBuffer whole = atlasPixels.duplicate();
			whole.position(0).limit(bytes);
			long at = 0;

			while (whole.hasRemaining()) {
				at += atlasOut.write(whole, at);
			}

			OverlayFile.atlasWidth = width;
			OverlayFile.atlasHeight = height;

			if (first) {
				SubnauticaLink.LOGGER.info("Wrote the block atlas ({} x {}) to {}", width, height, OverlayFile.atlasPath());
			}
		} catch (Exception e) {
			atlasFailed = true;
			SubnauticaLink.LOGGER.error("Could not write the block atlas; blocks stay untextured or stop moving", e);
		}
	}

	/** Opens the shared file the first time it is needed. Returns null if that isn't possible. */
	private static MappedByteBuffer open() {
		if (shared != null || sharingFailed) {
			return shared;
		}

		try (RandomAccessFile file = new RandomAccessFile(OverlayFile.path().toFile(), "rw")) {
			file.setLength(OverlayFile.FILE_BYTES);
			MappedByteBuffer buffer = file.getChannel().map(FileChannel.MapMode.READ_WRITE, 0, OverlayFile.FILE_BYTES);
			buffer.order(ByteOrder.LITTLE_ENDIAN);
			shared = buffer;
			SubnauticaLink.LOGGER.info("Sharing the HUD picture through {}", OverlayFile.path());
		} catch (Exception e) {
			sharingFailed = true;
			SubnauticaLink.LOGGER.error("Could not open the shared HUD file {}", OverlayFile.path(), e);
		}

		return shared;
	}

	/**
	 * Makes Minecraft's window the same size as Subnautica's screen, so the HUD is drawn at
	 * exactly the size it will be shown and stays sharp. Done once per size; if you resize the
	 * window yourself afterwards it is left alone.
	 */
	private static void matchSubnauticaScreen(MinecraftClient client) {
		int width = OverlayFile.screenWidth;
		int height = OverlayFile.screenHeight;
		Window window = client.getWindow();

		if (width <= 0 || height <= 0 || window.isFullscreen() || (width == appliedWidth && height == appliedHeight)) {
			return;
		}

		appliedWidth = width;
		appliedHeight = height;

		if (window.getFramebufferWidth() != width || window.getFramebufferHeight() != height) {
			if (savedWidth == 0) {
				savedWidth = window.getWidth();
				savedHeight = window.getHeight();
			}

			GLFW.glfwSetWindowSize(window.getHandle(), width, height);
		}
	}

	private static void restoreWindowSize(MinecraftClient client) {
		appliedWidth = 0;
		appliedHeight = 0;

		if (savedWidth > 0) {
			if (!client.getWindow().isFullscreen()) {
				GLFW.glfwSetWindowSize(client.getWindow().getHandle(), savedWidth, savedHeight);
			}

			savedWidth = 0;
			savedHeight = 0;
		}
	}
}
