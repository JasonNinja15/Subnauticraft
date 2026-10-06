package com.example.subnauticalink;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.entity.event.v1.EntitySleepEvents;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.entity.ExperienceOrbEntity;
import net.minecraft.entity.FallingBlockEntity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.TntEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.entity.vehicle.BoatEntity;
import net.minecraft.entity.vehicle.ChestBoatEntity;
import net.minecraft.item.BoatItem;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.util.ActionResult;
import net.minecraft.util.TypedActionResult;
import net.minecraft.util.math.Vec3d;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The Minecraft half of the Minecraft-Subnautica link, multiplayer version.
 *
 * <p>Each player runs their own Subnautica, and it talks only to that player's own Minecraft
 * (see {@code ClientLink}). This class is the server's side of things:
 *
 * <ul>
 *   <li>it keeps one {@link LinkSession} per player (health, hunger, oxygen, death, tools,
 *       attacks, where the player is put), fed by lines each player's game passes on;</li>
 *   <li>it moves dropped items and lit TNT, asking the nearest player's Subnautica what is
 *       in the way ({@link ItemSync}), and makes projectiles hit what players' games report
 *       they are about to hit ({@link Projectiles}).</li>
 * </ul>
 *
 * <p>Nothing here may use the parts of Minecraft that only exist in a player's game (the
 * window, models, textures), so that the mod also loads on a server with no window.
 */
public final class SubnauticaLink implements ModInitializer {
	public static final String MOD_ID = "subnautica_link";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	/** The port both mods use. It must match the number in the Subnautica mod. */
	public static final int PORT = 25599;

	@Override
	public void onInitialize() {
		ToolTokens.register();

		// The envelope the link's lines travel in between a player's game and the server.
		PayloadTypeRegistry.playC2S().register(LinePayload.ID, LinePayload.CODEC);
		PayloadTypeRegistry.playS2C().register(LinePayload.ID, LinePayload.CODEC);

		// A line from a player's game: something their Subnautica said.
		ServerPlayNetworking.registerGlobalReceiver(LinePayload.ID,
				(payload, context) -> Sessions.onLine(context.server(), context.player(), payload.line()));

		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> Sessions.onJoin(server, handler.getPlayer()));
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> Sessions.onLeave(handler.getPlayer()));
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
			// (The notes Projectiles sends go out through the sessions, so those are cleared last.)
			ItemSync.reset();
			ItemSync.forgetResting();
			Projectiles.reset();
			VoidMobs.reset();
			Gathering.reset();
			Sessions.clear();
		});

		// Before anything moves each tick: act on the hits players' games have reported for
		// projectiles that are about to reach Subnautica's scenery or creatures.
		ServerTickEvents.START_SERVER_TICK.register(Projectiles::beforeTick);

		// The stand-in mob that projectiles "hit" when they hit a Subnautica creature: note
		// the damage so it can be passed on.
		ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) -> {
			if (Projectiles.isProxy(entity)) {
				Projectiles.onProxyDamage(amount);
			}

			// In one of Subnautica's vehicles, a mob's blow lands on the vehicle, not the player.
			return Riding.allowDamage(entity, source, amount);
		});

		// 20 times a second: every player's own link, then the falling of dropped items.
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			Sessions.tick(server);
			ItemSync.tick(server);
			Projectiles.collectReturning(server);
			VoidMobs.tick(server);
			HookDrag.tick(server);
			Riding.tick(server);
			ToolTokens.clearStrays();
		});

		// Things dropped in the ocean void (a broken block, say) would fall forever, because
		// Subnautica's seabed doesn't exist for Minecraft. ItemSync takes over their falling
		// and lands them on Subnautica's scenery instead.
		ServerEntityEvents.ENTITY_LOAD.register((entity, world) -> {
			// A tool token never lies about as a dropped item (after dying, or dragged out of
			// the inventory): it is removed, and a fresh one is handed out if it is still due.
			if (entity instanceof ItemEntity dropped && ToolTokens.toolOf(dropped.getStack()) != null) {
				ToolTokens.noteStray(entity);
				return;
			}

			if ((entity instanceof ItemEntity || entity instanceof TntEntity || entity instanceof FallingBlockEntity) && world.getRegistryKey() == MovementBridge.OCEAN_VOID) {
				ItemSync.onAppeared(entity);
			}

			// Experience orbs would fall out of the world too. Without gravity they hang where
			// the mob died and drift to a player who comes near, as they normally do.
			if (entity instanceof ExperienceOrbEntity && world.getRegistryKey() == MovementBridge.OCEAN_VOID) {
				entity.setNoGravity(true);
			}
		});

		// Boats on Subnautica's sea. Minecraft only launches a boat onto water or a block it can
		// see, and the ocean void has neither. So there, using a boat while looking down at the
		// sea within reach puts it on the surface at that spot.
		// An empty bucket fills from Subnautica's sea, and from its lava (see SeaBucket).
		UseItemCallback.EVENT.register(SeaBucket::onUse);

		UseItemCallback.EVENT.register((player, world, hand) -> {
			ItemStack stack = player.getStackInHand(hand);

			if (!(stack.getItem() instanceof BoatItem) || world.getRegistryKey() != MovementBridge.OCEAN_VOID) {
				return TypedActionResult.pass(stack);
			}

			Vec3d eye = player.getEyePos();
			Vec3d look = player.getRotationVec(1.0F);

			if (look.y > -0.05 || eye.y <= WaterState.SEA_LEVEL) {
				return TypedActionResult.pass(stack);
			}

			double reach = (WaterState.SEA_LEVEL - eye.y) / look.y;

			if (reach > 6.0) {
				return TypedActionResult.pass(stack);
			}

			if (!world.isClient) {
				Vec3d at = eye.add(look.multiply(reach));

				// Which wood, and whether it has a chest, from the item's own name ("oak_chest_boat", "bamboo_raft").
				String name = Registries.ITEM.getId(stack.getItem()).getPath();
				boolean chest = name.contains("_chest_");
				String wood = name.replace("_chest_boat", "").replace("_chest_raft", "").replace("_boat", "").replace("_raft", "");
				BoatEntity boat = chest ? new ChestBoatEntity(world, at.x, WaterState.SEA_LEVEL - 0.1, at.z) : new BoatEntity(world, at.x, WaterState.SEA_LEVEL - 0.1, at.z);
				boat.setVariant(BoatEntity.Type.getType(wood));
				boat.setYaw(player.getYaw());
				world.spawnEntity(boat);
				stack.decrementUnlessCreative(1, player);
			}

			return TypedActionResult.success(stack, world.isClient);
		});

		// Beds in the ocean void. The time that counts there is Subnautica's: a linked player
		// can sleep while it is night in their Subnautica, whatever Minecraft's own clock says.
		// And a bed there never becomes where the player comes back to life: Subnautica decides that.
		EntitySleepEvents.ALLOW_SLEEP_TIME.register((player, sleepingPos, vanillaResult) -> {
			if (player instanceof ServerPlayerEntity sleeper && sleeper.getServerWorld().getRegistryKey() == MovementBridge.OCEAN_VOID && Sessions.isActive(sleeper)) {
				return Sessions.get(sleeper).night ? ActionResult.SUCCESS : ActionResult.FAIL;
			}

			return ActionResult.PASS;
		});
		EntitySleepEvents.ALLOW_SETTING_SPAWN.register((player, sleepingPos) -> player.getWorld().getRegistryKey() != MovementBridge.OCEAN_VOID);

		// A player died in Minecraft: tell their Subnautica.
		ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
			VoidMobs.onDeath(entity);

			if (entity instanceof ServerPlayerEntity player && player.getServer() != null) {
				LinkSession session = Sessions.of(player);

				if (session != null) {
					session.onDeath(player.getServer(), player);
				}
			}
		});

		LOGGER.info("Subnautica Link (multiplayer) loaded");
	}
}
