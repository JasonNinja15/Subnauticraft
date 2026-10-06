package com.example.subnauticalink;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.particle.ParticleEffect;
import net.minecraft.particle.ParticleType;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

/**
 * Passes Minecraft's particles on to Subnautica. Client only.
 *
 * <p>Minecraft's own particles are drawn in Minecraft's world, which isn't shown. So at the
 * points where Minecraft makes them (see {@code ParticleManagerMixin}), Subnautica is told
 * instead, and makes something of its own:
 *
 * <ul>
 *   <li>"DEBRIS x y z id face": bits of a block. Face -1 is the burst when a block breaks;
 *       0 to 5 is the trickle from the face being dug at. Subnautica draws small pieces of
 *       that block's own texture.</li>
 *   <li>"FX name x y z": a named effect at a spot. From here: "smoke" and "spark". Subnautica
 *       plays one of its own effects for each.</li>
 * </ul>
 */
public final class ClientEffects {
	/** The same named effect isn't sent again this soon (in milliseconds) within a couple of blocks. */
	private static final long SHORTEST_GAP_MS = 250;

	private static final Map<String, double[]> LAST = new HashMap<>();

	private ClientEffects() {
	}

	private static boolean linked() {
		MinecraftClient client = MinecraftClient.getInstance();
		return client.player != null && RemoteCollision.appliesTo(client.player);
	}

	/** A block broke: Minecraft is about to make its burst of bits. */
	public static void onBlockBroken(BlockPos pos, BlockState state) {
		if (state.isAir() || !linked()) {
			return;
		}

		RemoteCollision.sendLine(String.format(Locale.ROOT, "DEBRIS %d %d %d %d -1", pos.getX(), pos.getY(), pos.getZ(), Block.getRawIdFromState(state)));
	}

	/** A block is being dug at: Minecraft is about to make one bit fly off the face being hit. */
	public static void onBlockDug(BlockPos pos, Direction face) {
		MinecraftClient client = MinecraftClient.getInstance();

		if (client.world == null || !linked()) {
			return;
		}

		BlockState state = client.world.getBlockState(pos);

		if (state.isAir()) {
			return;
		}

		RemoteCollision.sendLine(String.format(Locale.ROOT, "DEBRIS %d %d %d %d %d", pos.getX(), pos.getY(), pos.getZ(), Block.getRawIdFromState(state), face.getId()));
	}

	/** Minecraft is about to make some other particle. A few kinds have a Subnautica stand-in. */
	public static void onParticle(ParticleEffect particle, double x, double y, double z) {
		ParticleType<?> type = particle.getType();
		String name;

		if (type == ParticleTypes.SMOKE || type == ParticleTypes.LARGE_SMOKE
				|| type == ParticleTypes.CAMPFIRE_COSY_SMOKE || type == ParticleTypes.CAMPFIRE_SIGNAL_SMOKE) {
			name = "smoke";
		} else if (type == ParticleTypes.DUST || type == ParticleTypes.ELECTRIC_SPARK) {
			name = "spark";
		} else {
			return;
		}

		if (!linked()) {
			return;
		}

		// Minecraft makes these one tiny puff at a time, many times a second. One of
		// Subnautica's effects is a whole puff, so they are thinned out.
		long now = System.currentTimeMillis();
		double[] last = LAST.get(name);

		if (last != null && now - last[0] < SHORTEST_GAP_MS
				&& Math.abs(x - last[1]) + Math.abs(y - last[2]) + Math.abs(z - last[3]) < 2.0) {
			return;
		}

		LAST.put(name, new double[] { now, x, y, z });
		RemoteCollision.sendLine(String.format(Locale.ROOT, "FX %s %.2f %.2f %.2f", name, x, y, z));
	}
}
