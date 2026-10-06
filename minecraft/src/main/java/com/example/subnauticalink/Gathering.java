package com.example.subnauticalink;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import net.minecraft.entity.ItemEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

/**
 * How Minecraft's materials are come by in Subnautica. Runs on the server, which is the only
 * place items can be made.
 *
 * <p>Two ways, both kept small so that Subnautica's own progression stays the main one:
 * <ul>
 *   <li><b>Digging.</b> Holding the attack button on Subnautica's terrain for half a second
 *       knocks one thing loose: the player's game says so with "DIG x y z nx ny nz" (the spot,
 *       and which way the surface faces), and it drops there. See {@link #DIG_CHANCES}.</li>
 *   <li><b>The Fabricator.</b> With Subnautica's Fabricator open, a small panel offers a few
 *       exchanges of a Subnautica material for a Minecraft one. Subnautica takes the material
 *       and says "TRADE which howMany"; the result goes into the player's inventory. See
 *       {@link #TRADES}. The same list, in the same order, is in the Subnautica mod.</li>
 * </ul>
 */
public final class Gathering {
	/** One thing digging can give, and its share of a hundred. */
	private record Find(Item item, int share) {
	}

	/** What digging gives where nothing more particular is listed: the Safe Shallows, and anywhere unknown. */
	private static final Find[] USUAL_FINDS = { new Find(Items.DIRT, 10), new Find(Items.FLINT, 10), new Find(Items.SAND, 15), new Find(Items.COBBLESTONE, 40), new Find(Items.COAL, 25) };

	/**
	 * What digging gives in each of Subnautica's regions. The first part of a region's name
	 * (Subnautica's own names, lower case) that matches decides; each list adds up to a hundred.
	 * Metals come out already smelted, as ingots. Everywhere still gives a little stone and coal, but less of them than of anything that
	 * belongs to the place. Iron and diamonds are left to the Fabricator, so Subnautica's own
	 * materials stay the way to them.
	 */
	private static final Map<String, Find[]> FINDS_BY_REGION = new java.util.LinkedHashMap<>();

	static {
		Find[] lavaLakes = { new Find(Items.IRON_INGOT, 25), new Find(Items.NETHERRACK, 17), new Find(Items.BLACKSTONE, 14), new Find(Items.GLOWSTONE_DUST, 14), new Find(Items.GOLD_INGOT, 8), new Find(Items.MAGMA_BLOCK, 7), new Find(Items.QUARTZ, 7), new Find(Items.COBBLESTONE, 4), new Find(Items.COAL, 4) };
		Find[] lava = { new Find(Items.IRON_INGOT, 25), new Find(Items.NETHERRACK, 20), new Find(Items.BLACKSTONE, 15), new Find(Items.GLOWSTONE_DUST, 12), new Find(Items.BASALT, 8), new Find(Items.MAGMA_BLOCK, 6), new Find(Items.QUARTZ, 6), new Find(Items.COBBLESTONE, 4), new Find(Items.COAL, 4) };
		Find[] lostRiver = { new Find(Items.COBBLED_DEEPSLATE, 30), new Find(Items.BONE, 25), new Find(Items.REDSTONE, 15), new Find(Items.LAPIS_LAZULI, 10), new Find(Items.GLOWSTONE_DUST, 10), new Find(Items.COBBLESTONE, 5), new Find(Items.COAL, 5) };
		// Dry land: everything the Safe Shallows give, and turf.
		Find[] island = { new Find(Items.GRASS_BLOCK, 20), new Find(Items.DIRT, 10), new Find(Items.FLINT, 10), new Find(Items.SAND, 10), new Find(Items.COBBLESTONE, 30), new Find(Items.COAL, 20) };
		Find[] grandReef = { new Find(Items.PRISMARINE_SHARD, 30), new Find(Items.PRISMARINE_CRYSTALS, 25), new Find(Items.REDSTONE, 20), new Find(Items.LAPIS_LAZULI, 15), new Find(Items.COBBLESTONE, 5), new Find(Items.COAL, 5) };
		Find[] bloodKelp = { new Find(Items.BONE, 30), new Find(Items.SOUL_SAND, 25), new Find(Items.REDSTONE, 20), new Find(Items.GOLD_INGOT, 15), new Find(Items.COBBLESTONE, 5), new Find(Items.COAL, 5) };
		Find[] seaTreader = { new Find(Items.COBBLED_DEEPSLATE, 35), new Find(Items.GRAVEL, 25), new Find(Items.COPPER_INGOT, 20), new Find(Items.FLINT, 10), new Find(Items.COBBLESTONE, 5), new Find(Items.COAL, 5) };

		// The deep places first, so their names aren't taken for something shallower.
		FINDS_BY_REGION.put("lavalakes", lavaLakes);
		FINDS_BY_REGION.put("lavapit", lavaLakes);
		FINDS_BY_REGION.put("lavafalls", lavaLakes);
		FINDS_BY_REGION.put("lavacastle", lavaLakes);
		FINDS_BY_REGION.put("ilz", lava);
		FINDS_BY_REGION.put("lava", lava);
		FINDS_BY_REGION.put("lostriver", lostRiver);
		FINDS_BY_REGION.put("ghosttree", lostRiver);
		FINDS_BY_REGION.put("bonesfield", lostRiver);
		FINDS_BY_REGION.put("skeletoncave", lostRiver);
		FINDS_BY_REGION.put("underwaterislands", new Find[] { new Find(Items.CALCITE, 30), new Find(Items.TUFF, 30), new Find(Items.COPPER_INGOT, 20), new Find(Items.FLINT, 10), new Find(Items.COBBLESTONE, 5), new Find(Items.COAL, 5) });
		FINDS_BY_REGION.put("island", island);
		FINDS_BY_REGION.put("deepgrandreef", grandReef);
		FINDS_BY_REGION.put("grandreef", grandReef);
		FINDS_BY_REGION.put("bloodkelp", bloodKelp);
		FINDS_BY_REGION.put("kelp", new Find[] { new Find(Items.CLAY_BALL, 30), new Find(Items.KELP, 25), new Find(Items.DIRT, 20), new Find(Items.FLINT, 15), new Find(Items.COBBLESTONE, 5), new Find(Items.COAL, 5) });
		FINDS_BY_REGION.put("grassy", new Find[] { new Find(Items.RED_SAND, 30), new Find(Items.TERRACOTTA, 25), new Find(Items.COPPER_INGOT, 20), new Find(Items.FLINT, 15), new Find(Items.COBBLESTONE, 5), new Find(Items.COAL, 5) });
		FINDS_BY_REGION.put("jellyshroom", new Find[] { new Find(Items.AMETHYST_SHARD, 30), new Find(Items.GLOWSTONE_DUST, 25), new Find(Items.REDSTONE, 20), new Find(Items.FLINT, 15), new Find(Items.COBBLESTONE, 5), new Find(Items.COAL, 5) });
		FINDS_BY_REGION.put("mushroom", new Find[] { new Find(Items.MYCELIUM, 25), new Find(Items.COPPER_INGOT, 25), new Find(Items.RED_MUSHROOM, 20), new Find(Items.BROWN_MUSHROOM, 20), new Find(Items.COBBLESTONE, 5), new Find(Items.COAL, 5) });
		FINDS_BY_REGION.put("koosh", new Find[] { new Find(Items.AMETHYST_SHARD, 30), new Find(Items.CALCITE, 30), new Find(Items.COPPER_INGOT, 30), new Find(Items.COBBLESTONE, 5), new Find(Items.COAL, 5) });
		FINDS_BY_REGION.put("sparsereef", new Find[] { new Find(Items.GRAVEL, 35), new Find(Items.PRISMARINE_SHARD, 30), new Find(Items.FLINT, 25), new Find(Items.COBBLESTONE, 5), new Find(Items.COAL, 5) });
		FINDS_BY_REGION.put("dunes", new Find[] { new Find(Items.SAND, 40), new Find(Items.SANDSTONE, 30), new Find(Items.GOLD_INGOT, 20), new Find(Items.COBBLESTONE, 5), new Find(Items.COAL, 5) });
		FINDS_BY_REGION.put("mountains", new Find[] { new Find(Items.ANDESITE, 30), new Find(Items.COPPER_INGOT, 25), new Find(Items.GOLD_INGOT, 15), new Find(Items.FLINT, 12), new Find(Items.EMERALD, 8), new Find(Items.COBBLESTONE, 5), new Find(Items.COAL, 5) });
		FINDS_BY_REGION.put("seatreader", seaTreader);
		FINDS_BY_REGION.put("crag", seaTreader);
		FINDS_BY_REGION.put("crash", new Find[] { new Find(Items.COPPER_INGOT, 40), new Find(Items.SAND, 30), new Find(Items.FLINT, 20), new Find(Items.COBBLESTONE, 5), new Find(Items.COAL, 5) });
	}

	/** Whether a region, by Subnautica's name for it, is one of the lava zones (the Inactive Lava Zone, the Lava Lakes and what lies in them). */
	public static boolean isLavaZone(String region) {
		String name = region == null ? "" : region.toLowerCase(java.util.Locale.ROOT).replace("_", "");
		return name.contains("lava") || name.contains("ilz");
	}

	/** What digging gives in the region with this name. */
	private static Find[] findsIn(String region) {
		String name = region == null ? "" : region.toLowerCase(java.util.Locale.ROOT).replace("_", "");

		for (Map.Entry<String, Find[]> entry : FINDS_BY_REGION.entrySet()) {
			if (name.contains(entry.getKey())) {
				return entry.getValue();
			}
		}

		return USUAL_FINDS;
	}

	/** What each Fabricator exchange gives for one of the Subnautica material, in the order Subnautica numbers them. */
	private static final Item[] TRADES = { Items.OAK_LOG, Items.GUNPOWDER, Items.IRON_INGOT, Items.DIAMOND, Items.SUGAR_CANE, Items.LAPIS_LAZULI, Items.OAK_SAPLING };
	private static final int[] TRADE_COUNTS = { 4, 3, 1, 1, 8, 3, 1 };

	/**
	 * The exchanges that go the other way: a Minecraft material handed over for a Subnautica
	 * one (Subnautica's own name for it), one for one. Subnautica asks with "BUY which howMany";
	 * however many the player really has are taken, and it is told "GIVE name howMany".
	 */
	private static final Item[] BUYS_WITH = { Items.IRON_INGOT };
	private static final String[] BUYS = { "Titanium" };

	/** The game sends a dig every ten ticks; one arriving sooner than this after the last is ignored. */
	private static final int SHORTEST_DIG_GAP = 8;

	/** A dig further than this from the player (in blocks) is ignored. */
	private static final double REACH = 7.0;

	private static final Map<UUID, Integer> LAST_DIG = new HashMap<>();

	private Gathering() {
	}

	/** "x y z nx ny nz": this player has dug at Subnautica's terrain for half a second, here. */
	public static void onDig(MinecraftServer server, ServerPlayerEntity player, String numbers) {
		String[] parts = numbers.trim().split(" ");

		if (parts.length != 6 || !player.isAlive() || player.getWorld().getRegistryKey() != MovementBridge.OCEAN_VOID) {
			return;
		}

		double[] v = new double[6];

		try {
			for (int i = 0; i < 6; i++) {
				v[i] = Double.parseDouble(parts[i]);
			}
		} catch (NumberFormatException e) {
			return;
		}

		Vec3d spot = new Vec3d(v[0], v[1], v[2]);
		Integer last = LAST_DIG.get(player.getUuid());
		int now = server.getTicks();

		if ((last != null && now - last >= 0 && now - last < SHORTEST_DIG_GAP) || spot.squaredDistanceTo(player.getEyePos()) > REACH * REACH) {
			return;
		}

		LAST_DIG.put(player.getUuid(), now);

		// Pick one by its share of a hundred, from what this part of Subnautica's world gives.
		Find[] finds = findsIn(Sessions.get(player).biome);
		int roll = player.getRandom().nextInt(100);
		Item found = finds[finds.length - 1].item();

		for (Find find : finds) {
			if (roll < find.share()) {
				found = find.item();
				break;
			}

			roll -= find.share();
		}

		// It drops just off the surface and is nudged away from it, like a block's drop.
		ServerWorld world = player.getServerWorld();
		ItemEntity drop = new ItemEntity(world, v[0] + v[3] * 0.3, v[1] + v[4] * 0.3, v[2] + v[5] * 0.3, new ItemStack(found));
		drop.setVelocity(v[3] * 0.1, v[4] * 0.1 + 0.1, v[5] * 0.1);
		drop.setToDefaultPickupDelay();
		world.spawnEntity(drop);
		world.playSound(null, BlockPos.ofFloored(spot), SoundEvents.BLOCK_STONE_BREAK, SoundCategory.BLOCKS, 0.8F, 0.9F + player.getRandom().nextFloat() * 0.2F);
	}

	/** "which howMany": this player's Subnautica has taken that many of a material at the Fabricator. */
	public static void onTrade(ServerPlayerEntity player, String numbers) {
		String[] parts = numbers.trim().split(" ");
		int which;
		int times = 1;

		try {
			which = Integer.parseInt(parts[0]);

			if (parts.length > 1) {
				times = Integer.parseInt(parts[1]);
			}
		} catch (NumberFormatException e) {
			return;
		}

		// Only for a living player in the ocean void: anywhere else there is no Fabricator to have traded at.
		if (!player.isAlive() || player.getWorld().getRegistryKey() != MovementBridge.OCEAN_VOID) {
			return;
		}

		if (which < 0 || which >= TRADES.length || times < 1 || times > 64) {
			return;
		}

		// Handed over a pile at a time; whatever doesn't fit in the inventory is dropped at the player's feet.
		int left = TRADE_COUNTS[which] * times;
		int pile = new ItemStack(TRADES[which]).getMaxCount();

		while (left > 0) {
			int count = Math.min(left, pile);
			player.getInventory().offerOrDrop(new ItemStack(TRADES[which], count));
			left -= count;
		}

		SubnauticaLink.LOGGER.info("{} made {} x {} at the Fabricator", player.getName().getString(), TRADE_COUNTS[which] * times, TRADES[which]);
	}

	/** "which howMany": this player asked at the Fabricator to hand over that many of a Minecraft material for a Subnautica one. */
	public static void onBuy(MinecraftServer server, ServerPlayerEntity player, String numbers) {
		String[] parts = numbers.trim().split(" ");
		int which;
		int wanted = 1;

		try {
			which = Integer.parseInt(parts[0]);

			if (parts.length > 1) {
				wanted = Integer.parseInt(parts[1]);
			}
		} catch (NumberFormatException e) {
			return;
		}

		if (which < 0 || which >= BUYS.length || wanted < 1 || wanted > 64 || !player.isAlive()) {
			return;
		}

		// Take as many as there are, up to what was asked for.
		int taken = 0;

		for (int slot = 0; slot < player.getInventory().size() && taken < wanted; slot++) {
			ItemStack stack = player.getInventory().getStack(slot);

			if (stack.isOf(BUYS_WITH[which])) {
				int take = Math.min(stack.getCount(), wanted - taken);
				stack.decrement(take);
				taken += take;
			}
		}

		if (taken > 0) {
			Sessions.get(player).send(server, player, "GIVE " + BUYS[which] + " " + taken);
			SubnauticaLink.LOGGER.info("{} handed over {} x {} for {}", player.getName().getString(), taken, BUYS_WITH[which], BUYS[which]);
		} else {
			player.sendMessage(net.minecraft.text.Text.literal("You have none of that to hand over."), true);
		}
	}

	public static void forget(UUID player) {
		LAST_DIG.remove(player);
	}

	/** The server is stopping: forget everything. */
	public static void reset() {
		LAST_DIG.clear();
	}
}
