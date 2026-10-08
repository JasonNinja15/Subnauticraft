package com.example.subnauticalink;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.impl.resource.loader.ModResourcePackUtil;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.AccessibilityOnboardingScreen;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.world.Difficulty;
import net.minecraft.world.GameMode;
import net.minecraft.world.GameRules;
import net.minecraft.world.gen.GeneratorOptions;
import net.minecraft.world.gen.WorldPresets;
import net.minecraft.world.level.LevelInfo;

/**
 * Opens a Survival world by itself when Minecraft reaches its title screen, so a launcher that
 * starts Minecraft next to Subnautica (Melty's one-click Play) needs nobody to click through
 * Minecraft's menus. The world is created the first time and opened again after that.
 *
 * <p>Only on when Minecraft is started with {@code -Dsubnautica_link.autoWorld=<world name>}
 * (the bundled Prism instance sets this). Started any other way, Minecraft behaves as before.
 * It happens once per start: back at the title screen later, nothing reopens.
 */
public final class AutoWorld {
	private static final String PROPERTY = "subnautica_link.autoWorld";

	/** Whether this start has already opened (or tried to open) the world. */
	private static boolean done;

	private AutoWorld() {
	}

	public static void init() {
		String name = System.getProperty(PROPERTY);
		if (name == null || name.isBlank()) {
			return;
		}
		String worldName = name.trim();
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			// A brand-new Minecraft folder first asks about the Narrator. Answered as "no" (the
			// same as pressing Continue), so the world can open with nobody at the keyboard.
			if (!done && client.currentScreen instanceof AccessibilityOnboardingScreen) {
				client.options.setAccessibilityOnboarded();
				client.setScreen(new TitleScreen());
			}
			if (!done && client.currentScreen instanceof TitleScreen && client.world == null) {
				done = true;
				open(client, worldName);
			}
		});
	}

	private static void open(MinecraftClient client, String name) {
		TitleScreen back = new TitleScreen();
		try {
			if (client.getLevelStorage().levelExists(name)) {
				SubnauticaLink.LOGGER.info("Auto world: opening \"{}\"", name);
				client.createIntegratedServerLoader().start(name, () -> client.setScreen(back));
				return;
			}
			SubnauticaLink.LOGGER.info("Auto world: creating \"{}\" (Survival)", name);
			// The same as Create New World with its defaults, in Survival, with this mod's data
			// (the ocean void dimension) switched on as Fabric does for that screen.
			LevelInfo info = new LevelInfo(name, GameMode.SURVIVAL, false, Difficulty.NORMAL, false,
					new GameRules(), ModResourcePackUtil.createDefaultDataConfiguration());
			GeneratorOptions options = new GeneratorOptions(GeneratorOptions.getRandomSeed(), true, false);
			client.createIntegratedServerLoader().createAndStart(name, info, options,
					registries -> registries.get(RegistryKeys.WORLD_PRESET).entryOf(WorldPresets.DEFAULT)
							.value().createDimensionsRegistryHolder(),
					back);
		} catch (Exception e) {
			SubnauticaLink.LOGGER.error("Auto world: couldn't open \"{}\"; open a world by hand", name, e);
		}
	}
}
