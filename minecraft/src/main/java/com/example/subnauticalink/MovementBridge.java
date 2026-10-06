package com.example.subnauticalink;

import java.util.List;

import net.minecraft.entity.attribute.EntityAttribute;
import net.minecraft.entity.attribute.EntityAttributeInstance;
import net.minecraft.entity.attribute.EntityAttributeModifier;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

/**
 * Movement, the way the Skyrim project does it: Minecraft moves the player, and Subnautica's
 * character is carried along.
 *
 * <ol>
 *   <li>When the games link, Subnautica says where its player is ("SPAWN"). Minecraft puts its
 *       player at the matching spot in the ocean void.</li>
 *   <li>From then on Subnautica only sends which controls are held and where the camera is
 *       pointing ("INPUT"). Minecraft's own movement code does the moving.</li>
 *   <li>Every tick Minecraft sends back where its player now is ("MCPOS"), and Subnautica
 *       moves its character there.</li>
 * </ol>
 *
 * <p>The ocean void is an empty dimension this mod adds, tall enough for Subnautica's full
 * depth (y = -1792 to 256). One metre is one block and sea level is y = 0 in both games.
 *
 * <p>Subnautica's scenery is solid here too (see {@link RemoteCollision}), and everything below
 * sea level counts as water unless Subnautica says the player is somewhere dry (see
 * {@link WaterState}).
 */
public final class MovementBridge {
	/** The dimension the player is kept in while linked. Defined by the mod's data files. */
	public static final RegistryKey<World> OCEAN_VOID = RegistryKey.of(RegistryKeys.WORLD, Identifier.of(SubnauticaLink.MOD_ID, "ocean_void"));

	/** The ocean void's floor and ceiling, with a little room to spare. */
	private static final double LOWEST_Y = -1790.0;
	private static final double HIGHEST_Y = 250.0;

	/** Where Subnautica wants the player placed, waiting until the player is alive to do it. */
	private Vec3d pendingSpawn;

	/** True while the player is in the ocean void under Subnautica's controls. */
	private boolean active;
	private boolean warnedMissingDimension;

	// Where the player was before being taken to the ocean void, to put them back afterwards.
	private RegistryKey<World> returnWorld;
	private Vec3d returnPos;
	private float returnYaw;
	private float returnPitch;

	// ---- Converting between the two games ----------------------------------------------------
	// Subnautica's engine (Unity) and Minecraft lay out the world differently:
	//   - x and y are the same in both;
	//   - z is flipped: Unity's "forward" axis points the opposite way to Minecraft's;
	//   - because of that flip, facing direction is turned by 180 degrees;
	//   - looking up/down means the same in both, but Unity counts 0-360 and Minecraft -90 to 90.

	/** "SPAWN x y z": Subnautica's player is here; start (or restart) from the matching spot. */
	public void onSpawnLine(String numbers) {
		String[] parts = numbers.trim().split(" ");

		if (parts.length != 3) {
			return;
		}

		try {
			double x = Double.parseDouble(parts[0]);
			double y = MathHelper.clamp(Double.parseDouble(parts[1]), LOWEST_Y, HIGHEST_Y);
			double z = -Double.parseDouble(parts[2]);
			this.pendingSpawn = new Vec3d(x, y, z);
		} catch (NumberFormatException e) {
			// A garbled line; ignore it.
		}
	}

	/** True while the player is in the ocean void under Subnautica's controls. */
	public boolean inVoid() {
		return this.active;
	}

	/** Subnautica disconnected: let go of the controls; the next tick sends the player home. */
	public void onDisconnected() {
		this.pendingSpawn = null;
	}

	/**
	 * Called every tick for one player.
	 *
	 * @param linked whether that player's Subnautica is linked and sending controls
	 */
	public void tick(MinecraftServer server, ServerPlayerEntity player, boolean linked) {
		boolean inOceanVoid = player.getServerWorld().getRegistryKey() == OCEAN_VOID;

		// Subnautica asked for the player to be placed, and the player is alive to be placed.
		if (this.pendingSpawn != null && player.isAlive()) {
			this.enter(server, player, this.pendingSpawn);
			this.pendingSpawn = null;
			return;
		}

		// Somewhere other than the ocean void while supposedly in it (respawned after dying, or
		// moved by a command): no longer in it, so Subnautica is asked afresh where to start.
		if (this.active && !inOceanVoid && player.isAlive()) {
			this.active = false;
		}

		if (this.active && !linked) {
			// Subnautica has gone quiet (closed, or back at its menu).
			if (player.isAlive()) {
				this.leave(server, player);
			}

			return;
		}

		if (!this.active && inOceanVoid && player.isAlive()) {
			// Left in the ocean void by an earlier session, with nothing to stand on.
			this.leave(server, player);
		}

		// Where the player now is ("MCPOS") is told to Subnautica by the player's own game,
		// which knows it soonest (see ClientLink).
	}

	private void enter(MinecraftServer server, ServerPlayerEntity player, Vec3d spot) {
		ServerWorld oceanVoid = server.getWorld(OCEAN_VOID);

		if (oceanVoid == null) {
			if (!this.warnedMissingDimension) {
				this.warnedMissingDimension = true;
				SubnauticaLink.LOGGER.warn("The ocean void dimension is not loaded, so movement cannot be linked");
				player.sendMessage(Text.literal("Subnautica Link: the ocean void dimension is missing, so movement is not linked. Close and reopen the world."));
			}

			return;
		}

		if (player.getServerWorld() != oceanVoid) {
			// Remember where to come back to.
			this.returnWorld = player.getServerWorld().getRegistryKey();
			this.returnPos = player.getPos();
			this.returnYaw = player.getYaw();
			this.returnPitch = player.getPitch();
		}

		player.teleport(oceanVoid, spot.x, spot.y, spot.z, player.getYaw(), player.getPitch());

		// Flying is whatever the game mode normally allows (earlier versions of this mod
		// switched it on, so make sure it is back to normal).
		player.interactionManager.getGameMode().setAbilities(player.getAbilities());
		player.sendAbilitiesUpdate();

		// Being put somewhere new is not a fall.
		FallTracker.reset(player);
		setReach(player, true);

		this.active = true;
	}

	/** While linked, the player reaches a quarter further than usual, for blocks and for mobs alike. */
	private static final Identifier REACH = Identifier.of(SubnauticaLink.MOD_ID, "linked_reach");
	private static final double EXTRA_REACH = 0.25;

	private static void setReach(ServerPlayerEntity player, boolean longer) {
		for (RegistryEntry<EntityAttribute> kind : List.of(EntityAttributes.PLAYER_BLOCK_INTERACTION_RANGE, EntityAttributes.PLAYER_ENTITY_INTERACTION_RANGE)) {
			EntityAttributeInstance reach = player.getAttributeInstance(kind);

			if (reach == null) {
				continue;
			}

			reach.removeModifier(REACH);

			if (longer) {
				reach.addTemporaryModifier(new EntityAttributeModifier(REACH, EXTRA_REACH, EntityAttributeModifier.Operation.ADD_MULTIPLIED_BASE));
			}
		}
	}

	/** The player is leaving the server: if they are in the ocean void, put them back where they came from first. */
	public void goHome(MinecraftServer server, ServerPlayerEntity player) {
		if (this.active && player.isAlive() && player.getServerWorld().getRegistryKey() == OCEAN_VOID) {
			this.leave(server, player);
		}
	}

	private void leave(MinecraftServer server, ServerPlayerEntity player) {
		// Asleep in a bed there: up first, so the bed isn't left marked as taken.
		if (player.isSleeping()) {
			player.wakeUp(true, true);
		}

		// Put flight back to whatever the player's game mode normally allows.
		player.interactionManager.getGameMode().setAbilities(player.getAbilities());
		player.sendAbilitiesUpdate();
		player.fallDistance = 0.0F;
		setReach(player, false);

		ServerWorld home = this.returnWorld != null ? server.getWorld(this.returnWorld) : null;

		if (home != null && this.returnPos != null) {
			player.teleport(home, this.returnPos.x, this.returnPos.y, this.returnPos.z, this.returnYaw, this.returnPitch);
		} else {
			// No remembered spot (for example the game was closed while linked): use the world spawn.
			ServerWorld overworld = server.getOverworld();
			BlockPos spawn = overworld.getSpawnPos();
			player.teleport(overworld, spawn.getX() + 0.5, spawn.getY(), spawn.getZ() + 0.5, player.getYaw(), player.getPitch());
		}

		this.active = false;
		this.returnWorld = null;
		this.returnPos = null;
	}
}
