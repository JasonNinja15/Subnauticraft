package com.example.subnauticalink;

import java.util.Locale;

import net.minecraft.client.MinecraftClient;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;

/**
 * Digging at Subnautica's terrain. Client only.
 *
 * <p>Subnautica says what its camera is pointed at (see {@link RemoteControls#aim}) and whether
 * that is terrain: the sea floor, a cliff, a cave wall, as opposed to the lifepod, a wreck or a
 * base. While the attack button is held on terrain, with no Minecraft block or creature in the
 * way, the hand keeps swinging; every half second the server is told ("DIG"), and it drops
 * something there. See {@link Gathering}.
 */
public final class ClientDigging {
	/** How long the button must be held for each thing dug up, in ticks. Twenty ticks are a second. */
	private static final int TICKS_PER_DIG = 10;

	private static int heldFor;

	private ClientDigging() {
	}

	/** Called every client tick while linked. "attacking" is whether Minecraft's attack button is being held. */
	public static void tick(MinecraftClient client, boolean attacking) {
		double[] aim = RemoteControls.aim;

		// When Subnautica's scenery is in reach and no Minecraft block is, Minecraft is pointed
		// at the empty space in front of the scenery (see aimAtSubnauticaScenery). So "aimed at
		// a block that is air" means exactly that: nothing of Minecraft's is in the way.
		boolean digging = attacking && aim != null && RemoteControls.aimIsTerrain
				&& client.player != null && client.world != null && client.currentScreen == null
				&& client.crosshairTarget instanceof BlockHitResult hit
				&& client.world.getBlockState(hit.getBlockPos()).isAir();

		if (!digging) {
			heldFor = 0;
			return;
		}

		// Keep the hand swinging and chips flying, as when breaking a block.
		if (heldFor % 4 == 0) {
			client.player.swingHand(Hand.MAIN_HAND);
			ClientLink.toSubnautica(String.format(Locale.ROOT, "FX impact %.3f %.3f %.3f %.3f %.3f %.3f", aim[0], aim[1], aim[2], aim[3], aim[4], aim[5]));
		}

		if (++heldFor >= TICKS_PER_DIG) {
			heldFor = 0;
			ClientLink.toServer(String.format(Locale.ROOT, "DIG %.3f %.3f %.3f %.3f %.3f %.3f", aim[0], aim[1], aim[2], aim[3], aim[4], aim[5]));

		}
	}

	public static void reset() {
		heldFor = 0;
	}
}
