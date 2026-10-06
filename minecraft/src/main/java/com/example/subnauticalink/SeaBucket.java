package com.example.subnauticalink;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ItemUsage;
import net.minecraft.item.Items;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.Hand;
import net.minecraft.util.TypedActionResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import net.minecraft.world.World;

/**
 * Filling a bucket from Subnautica's sea, and from its lava.
 *
 * <p>Minecraft fills a bucket from a block of water, and the ocean void has none: the sea is
 * Subnautica's. So an empty bucket used while under the sea, or while looking down at its
 * surface from within reach, simply comes back full. Nothing is taken out of the sea in either
 * game; there is always more.
 *
 * <p>Lava is the same idea, but only Subnautica knows where its lava is. In the lava zones,
 * using an empty bucket asks this player's Subnautica whether it is lava they are looking at
 * ("LAVACHECK"). The answer ("LAVASCOOP 1" or "LAVASCOOP 0") goes to the server, which fills
 * the bucket with lava, or with water if it wasn't lava and they are in the sea.
 *
 * <p>Water or lava the player has poured out themselves is still picked up the usual way, and
 * a block in the way is still just a block.
 */
public final class SeaBucket {
	private SeaBucket() {
	}

	/** An item was used (in the player's own game, and again on the server). */
	public static TypedActionResult<ItemStack> onUse(PlayerEntity player, World world, Hand hand) {
		ItemStack stack = player.getStackInHand(hand);

		if (!stack.isOf(Items.BUCKET) || world.getRegistryKey() != MovementBridge.OCEAN_VOID
				|| !(RemoteCollision.appliesTo(player) || RemoteCollision.isLinkedOnServer(player))) {
			return TypedActionResult.pass(stack);
		}

		// Looking at one of Minecraft's own blocks, or at water or lava someone poured: that is Minecraft's business.
		if (lookingAtMinecraft(player, world)) {
			return TypedActionResult.pass(stack);
		}

		LinkSession session = world.isClient ? null : Sessions.of(player);
		String biome = world.isClient ? WaterState.clientBiome : session == null ? null : session.biome;

		if (Gathering.isLavaZone(biome)) {
			// It might be lava: this player's Subnautica is asked, and the bucket is filled
			// (or not) when its answer reaches the server. See onVerdict.
			if (world.isClient) {
				RemoteCollision.sendLine("LAVACHECK");
			}

			return TypedActionResult.pass(stack);
		}

		boolean dry = world.isClient ? WaterState.isDry() : session != null && session.dry;

		if (!seaInReach(player, dry)) {
			return TypedActionResult.pass(stack);
		}

		// (With the player named, the server plays the sound to everyone else and the player's own game plays it to them.)
		ItemStack filled = fill(player, world, hand, stack, Items.WATER_BUCKET, SoundEvents.ITEM_BUCKET_FILL, player);
		return TypedActionResult.success(filled, world.isClient);
	}

	/** "LAVASCOOP 1" or "0": this player's Subnautica says whether they were looking at its lava when they used a bucket. */
	public static void onVerdict(ServerPlayerEntity player, LinkSession session, boolean lava) {
		if (player.getWorld().getRegistryKey() != MovementBridge.OCEAN_VOID) {
			return;
		}

		Hand hand = player.getMainHandStack().isOf(Items.BUCKET) ? Hand.MAIN_HAND : player.getOffHandStack().isOf(Items.BUCKET) ? Hand.OFF_HAND : null;

		if (hand == null) {
			return;
		}

		ItemStack stack = player.getStackInHand(hand);

		if (lava && Gathering.isLavaZone(session.biome)) {
			fill(player, player.getWorld(), hand, stack, Items.LAVA_BUCKET, SoundEvents.ITEM_BUCKET_FILL_LAVA, null);
		} else if (!lava && seaInReach(player, session.dry)) {
			fill(player, player.getWorld(), hand, stack, Items.WATER_BUCKET, SoundEvents.ITEM_BUCKET_FILL, null);
		}
	}

	/** Swaps one empty bucket in this hand for a full one (a pile of empty ones loses one, and the full one goes into the inventory). */
	private static ItemStack fill(PlayerEntity player, World world, Hand hand, ItemStack stack, Item full, SoundEvent sound, PlayerEntity heardAlready) {
		world.playSound(heardAlready, player.getX(), player.getY(), player.getZ(), sound, SoundCategory.PLAYERS, 1.0F, 1.0F);

		ItemStack filled = ItemUsage.exchangeStack(stack, player, new ItemStack(full));
		player.setStackInHand(hand, filled);
		return filled;
	}

	/** Whether the player is in the sea, or looking down at its surface from within reach. */
	private static boolean seaInReach(PlayerEntity player, boolean dry) {
		Vec3d eye = player.getEyePos();

		// Under the surface: in the sea, unless Subnautica says they are somewhere with air (a base, a vehicle).
		if (eye.y < WaterState.SEA_LEVEL) {
			return !dry;
		}

		Vec3d look = player.getRotationVec(1.0F);

		if (look.y > -0.01) {
			return false;
		}

		// How far along the line of sight the surface is.
		return (WaterState.SEA_LEVEL - eye.y) / look.y <= player.getBlockInteractionRange();
	}

	/** Whether one of Minecraft's blocks, or water or lava that was poured out, is in the line of sight within reach. */
	private static boolean lookingAtMinecraft(PlayerEntity player, World world) {
		Vec3d eye = player.getEyePos();
		Vec3d end = eye.add(player.getRotationVec(1.0F).multiply(player.getBlockInteractionRange()));
		return world.raycast(new RaycastContext(eye, end, RaycastContext.ShapeType.OUTLINE, RaycastContext.FluidHandling.SOURCE_ONLY, player)).getType() != HitResult.Type.MISS;
	}
}
