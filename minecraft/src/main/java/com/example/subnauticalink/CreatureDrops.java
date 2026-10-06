package com.example.subnauticalink;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import net.minecraft.enchantment.Enchantment;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.enchantment.Enchantments;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;

/**
 * What Subnautica's creatures drop for Minecraft when a player kills them.
 *
 * <p>Subnautica says "KILLED name x y z" when a creature dies to damage this player dealt
 * (name is Subnautica's own name for the kind of creature). The drops appear at that spot.
 * Looting on the weapon in hand adds to the drops marked as such, as it does for mobs.
 *
 * <p>The one list below is used both for dropping and for the list shown beside the inventory
 * (see {@code ClientDropsList}), so the two always agree.
 */
public final class CreatureDrops {
	/** One thing dropped: between "least" and "most" of it, and whether Looting adds up to one more per level. */
	public record Drop(Item item, int least, int most, boolean looting, int chance) {
		/** A drop that always comes. */
		public Drop(Item item, int least, int most, boolean looting) {
			this(item, least, most, looting, 100);
		}
	}

	/**
	 * One line of the list: the creatures it covers (Subnautica's names for them, and how to
	 * show them to the player) and what they drop.
	 */
	public record Entry(String shownAs, List<String> creatures, List<Drop> drops) {
	}

	/** Out of a hundred kills of a creature that can't be eaten, this many leave a music disc. */
	private static final int DISC_CHANCE = 4;

	public static final List<Entry> TABLE = List.of(
			new Entry("Sand Shark, Crabsnake, Crabsquid, Ampeel, River Prowler, Jellyray, Ghostray",
					List.of("Sandshark", "Crabsnake", "CrabSquid", "Shocker", "SpineEel", "Jellyray", "GhostRayBlue"),
					List.of(new Drop(Items.BEEF, 1, 3, true))),
			new Entry("Stalker",
					List.of("Stalker"),
					List.of(new Drop(Items.BEEF, 1, 3, true), new Drop(Items.ARROW, 16, 16, false))),
			new Entry("Bone Shark",
					List.of("BoneShark"),
					List.of(new Drop(Items.BEEF, 1, 3, true), new Drop(Items.BONE, 4, 4, false))),
			new Entry("Rabbit Ray",
					List.of("RabbitRay"),
					List.of(new Drop(Items.BEEF, 1, 3, true), new Drop(Items.STRING, 3, 3, false))),
			new Entry("Gasopod",
					List.of("Gasopod"),
					List.of(new Drop(Items.BEEF, 1, 3, true), new Drop(Items.LEATHER, 4, 4, false), new Drop(Items.SLIME_BALL, 2, 5, false))),
			// The middle-sized creatures of the lava zones carry the Nether's metals; the
			// template that turns diamond gear into netherite comes far less often.
			new Entry("Lava Lizard",
					List.of("LavaLizard"),
					List.of(new Drop(Items.BEEF, 1, 3, true), new Drop(Items.GOLD_INGOT, 1, 3, true), new Drop(Items.BLAZE_ROD, 1, 2, true),
							new Drop(Items.NETHERITE_SCRAP, 1, 1, false, 25), new Drop(Items.NETHERITE_UPGRADE_SMITHING_TEMPLATE, 1, 1, false, 5))),
			new Entry("Crimson Ray",
					List.of("GhostRayRed"),
					List.of(new Drop(Items.BEEF, 1, 3, true), new Drop(Items.GOLD_INGOT, 1, 3, true),
							new Drop(Items.NETHERITE_SCRAP, 1, 1, false, 25), new Drop(Items.NETHERITE_UPGRADE_SMITHING_TEMPLATE, 1, 1, false, 5))),
			new Entry("Lava Larva",
					List.of("LavaLarva"),
					List.of(new Drop(Items.BLAZE_ROD, 1, 1, false, 50))),
			new Entry("Sea Dragon Leviathan",
					List.of("SeaDragon"),
					List.of(new Drop(Items.BEEF, 10, 36, false), new Drop(Items.BEACON, 1, 1, false), new Drop(Items.IRON_BLOCK, 20, 20, false), new Drop(Items.BLAZE_ROD, 4, 8, false))),
			new Entry("Reaper Leviathan",
					List.of("ReaperLeviathan"),
					List.of(new Drop(Items.BEEF, 10, 36, false), new Drop(Items.ENCHANTING_TABLE, 1, 1, false), new Drop(Items.ELYTRA, 1, 1, false, 95))),
			new Entry("Ghost Leviathan, Reefback, Sea Treader",
					List.of("GhostLeviathan", "GhostLeviathanJuvenile", "Reefback", "SeaTreader"),
					List.of(new Drop(Items.BEEF, 10, 36, false))),
				// Not looked up by name like the lines above: see DISC_CHANCE. It is here so the list shown to the player has it.
				new Entry("Any creature that can't be caught and eaten: a random music disc",
						List.of(),
						List.of(new Drop(Items.MUSIC_DISC_13, 1, 1, false, DISC_CHANCE))));

	/**
	 * The fish Subnautica lets you catch and eat, by its own names for them. Any other
	 * creature, whether or not it has a line of its own above, has a small chance of leaving a
	 * music disc: any one of Minecraft's, picked at random.
	 */
	private static final Set<String> EDIBLE = Set.of("Peeper", "Bladderfish", "Boomerang", "GarryFish", "HoleFish", "Hoopfish", "Hoverfish",
			"Spadefish", "Reginald", "Eyeye", "Oculus", "Spinefish", "LavaBoomerang", "LavaEyeye");

	/** The creatures whose drops are worth the most, which can't be claimed in quick succession (see onKilled). */
	private static final Set<String> GIANTS = Set.of("ReaperLeviathan", "SeaDragon", "GhostLeviathan", "GhostLeviathanJuvenile", "Reefback", "SeaTreader");

	/** The server tick of each player's last kill of a giant. */
	private static final java.util.Map<java.util.UUID, Integer> LAST_GIANT = new java.util.concurrent.ConcurrentHashMap<>();

	/** Every music disc in the game, found the first time one is wanted. */
	private static List<Item> discs;

	/** A kill further than this from the player (in blocks) is ignored. */
	private static final double FURTHEST = 96.0;

	private CreatureDrops() {
	}

	/** "name x y z": this player killed one of Subnautica's creatures there (Subnautica's coordinates). */
	public static void onKilled(ServerPlayerEntity player, String text) {
		String[] parts = text.trim().split(" ");

		if (parts.length != 4 || player.getWorld().getRegistryKey() != MovementBridge.OCEAN_VOID || !player.isAlive()) {
			return;
		}

		// Kills are taken on the player's game's word, so the ones worth most are at least
		// kept to a pace nobody really manages: one of the giants every half minute.
		if (GIANTS.contains(parts[0])) {
			int tick = player.getServer().getTicks();
			Integer lastGiant = LAST_GIANT.get(player.getUuid());

			if (lastGiant != null && tick - lastGiant >= 0 && tick - lastGiant < 600) {
				return;
			}

			LAST_GIANT.put(player.getUuid(), tick);
		}

		double x, y, z;

		try {
			x = Double.parseDouble(parts[1]);
			y = Double.parseDouble(parts[2]);
			// As everywhere, z is flipped between the two games.
			z = -Double.parseDouble(parts[3]);
		} catch (NumberFormatException e) {
			return;
		}

		if (player.squaredDistanceTo(x, y, z) > FURTHEST * FURTHEST) {
			return;
		}

		ServerWorld world = player.getServerWorld();

		// A music disc, now and then, from anything that isn't one of the fish you can eat.
		if (!EDIBLE.contains(parts[0]) && player.getRandom().nextInt(100) < DISC_CHANCE) {
			if (discs == null) {
				discs = new ArrayList<>();

				for (Item item : Registries.ITEM) {
					if (Registries.ITEM.getId(item).getPath().startsWith("music_disc_")) {
						discs.add(item);
					}
				}
			}

			if (!discs.isEmpty()) {
				ItemEntity disc = new ItemEntity(world, x, y, z, new ItemStack(discs.get(player.getRandom().nextInt(discs.size()))));
				disc.setToDefaultPickupDelay();
				world.spawnEntity(disc);
				SubnauticaLink.LOGGER.info("{} killed a {}, which left a music disc", player.getName().getString(), parts[0]);
			}
		}

		for (Entry entry : TABLE) {
			if (!entry.creatures().contains(parts[0])) {
				continue;
			}

			int looting = level(player, Enchantments.LOOTING, player.getMainHandStack());

			for (Drop drop : entry.drops()) {
				// One that only comes some of the time.
				if (drop.chance() < 100 && player.getRandom().nextInt(100) >= drop.chance()) {
					continue;
				}

				int count = drop.least() + player.getRandom().nextInt(drop.most() - drop.least() + 1);

				if (drop.looting() && looting > 0) {
					count += player.getRandom().nextInt(looting + 1);
				}

				if (count <= 0) {
					continue;
				}

				ItemEntity item = new ItemEntity(world, x, y, z, new ItemStack(drop.item(), count));
				item.setToDefaultPickupDelay();
				world.spawnEntity(item);
			}

			SubnauticaLink.LOGGER.info("{} killed a {}; its drops are at ({}, {}, {})", player.getName().getString(), parts[0],
					String.format("%.1f", x), String.format("%.1f", y), String.format("%.1f", z));
			return;
		}
	}

	/** The level of an enchantment on an item, or 0. */
	public static int level(LivingEntity holder, RegistryKey<Enchantment> enchantment, ItemStack stack) {
		if (stack.isEmpty()) {
			return 0;
		}

		Optional<RegistryEntry.Reference<Enchantment>> entry = holder.getWorld().getRegistryManager().get(RegistryKeys.ENCHANTMENT).getEntry(enchantment);
		return entry.map(found -> EnchantmentHelper.getLevel(found, stack)).orElse(0);
	}
}
