package com.example.subnauticalink;

import net.minecraft.block.BlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

/**
 * The hand-over point for block changes.
 *
 * <p>Blocks are described to Subnautica by each player's own game (see ClientBlocks), which
 * only exists where there is a game window. But the place block changes are noticed
 * ({@code WorldMixin}) is shared with servers that have no window. So that place calls this,
 * and this passes the change on to whoever is listening: the player's game sets {@link
 * #listener} when it starts; on a server nothing does, and nothing happens.
 */
public final class BlockSync {
	/** Something to tell about block changes in a player's own copy of the world. */
	public interface Listener {
		void changed(World world, BlockPos pos, BlockState state);
	}

	public static volatile Listener listener;

	private BlockSync() {
	}

	/** A block changed somewhere. Only changes in a player's own copy of the world are passed on. */
	public static void onChanged(World world, BlockPos pos, BlockState state) {
		Listener current = listener;

		if (current != null && world.isClient) {
			current.changed(world, pos, state);
		}

		// And one thing on the server's side: fallen sand lying on Subnautica's scenery (see ItemSync).
		if (!world.isClient) {
			ItemSync.onBlockChanged(world, pos, state);
		}
	}
}
