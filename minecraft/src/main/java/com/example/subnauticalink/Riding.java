package com.example.subnauticalink;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

import java.util.Locale;

import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.attribute.EntityAttributeInstance;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.passive.PigEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

/**
 * Riding in one of Subnautica's vehicles (the Seamoth).
 *
 * <p>Everywhere else, Minecraft moves the player and Subnautica's character is carried along.
 * In a vehicle it is the other way round: Subnautica drives, and says twenty times a second
 * where the seat is ("RIDE x y z yaw", the spot for the player's feet). Minecraft sits its
 * player on an unseen mount and keeps the mount there. A sitting player:
 * <ul>
 *   <li>is drawn sitting, for everyone;</li>
 *   <li>doesn't move by itself, so it bumps into nothing and can't be left behind;</li>
 *   <li>takes no fall damage;</li>
 *   <li>can still open the inventory and eat.</li>
 * </ul>
 * Getting out, Subnautica says where it has put the player ("SPAWN"), the player gets off the
 * mount, and Minecraft is in charge of movement again from that spot.
 *
 * <p>The mount is an invisible pig, the same stand-in the grappling hook uses.
 */
public final class Riding {
	private static final String TAG = "subnautica_link_seat";

	/** With no word from Subnautica for this many ticks, the player gets off. */
	private static final int SILENCE_BEFORE_GETTING_OFF = 20;

	/** How far a sitting player's feet are above a pig's: where a pig carries its rider, less where a player sits from. */
	private static final Vec3d FEET_ABOVE_MOUNT = new Vec3d(0.0, 0.86875 - 0.6, 0.0);

	/** The mount's health when the vehicle's hull is whole: ten hearts. */
	private static final float MOUNT_HEALTH = 20.0F;

	/** One point of Minecraft damage is this much to a vehicle's hull, the same rate as for health. */
	private static final double SUBNAUTICA_DAMAGE_PER_POINT = 5.0;

	private static final class Seat {
		PigEntity mount;
		Vec3d feet;
		float yaw;
		int silentFor;
		int settledFor;
	}

	private static final Map<UUID, Seat> SEATS = new HashMap<>();

	private Riding() {
	}

	/** "x y z yaw": this player is in a vehicle in Subnautica, and their feet belong here (Subnautica's coordinates). */
	public static void onRide(ServerPlayerEntity player, String numbers) {
		String[] parts = numbers.trim().split(" ");

		if ((parts.length != 4 && parts.length != 6 && parts.length != 7) || !player.isAlive() || player.getServerWorld().getRegistryKey() != MovementBridge.OCEAN_VOID) {
			return;
		}

		try {
			// As everywhere, z is flipped between the two games, which turns facing by half a turn.
			Vec3d feet = new Vec3d(Double.parseDouble(parts[0]), Double.parseDouble(parts[1]), -Double.parseDouble(parts[2]));
			float yaw = MathHelper.wrapDegrees(Float.parseFloat(parts[3]) + 180.0F);
			Seat seat = SEATS.get(player.getUuid());

			if (seat == null || !seat.mount.isAlive() || seat.mount.getWorld() != player.getServerWorld()) {
				if (seat != null) {
					seat.mount.discard();
				}

				PigEntity mount = makeMount(player.getServerWorld(), feet.subtract(FEET_ABOVE_MOUNT), yaw);

				if (mount == null) {
					return;
				}

				seat = new Seat();
				seat.mount = mount;
				SEATS.put(player.getUuid(), seat);
				SubnauticaLink.LOGGER.info("{} is in a Subnautica vehicle", player.getName().getString());
			}

			seat.feet = feet;
			seat.yaw = yaw;

			// The vehicle's hull, from 0 to 1, shown as the mount's hearts: ten of them, each a
			// tenth of the hull. (Never none at all: a mount with no health dies.)
			if (parts.length >= 6) {
				float hull = MathHelper.clamp(Float.parseFloat(parts[4]), 0.0F, 1.0F);
				float hearts = Math.max(1.0F, Math.round(hull * MOUNT_HEALTH));

				if (seat.mount.getHealth() != hearts) {
					seat.mount.setHealth(hearts);
				}
			}
			seat.silentFor = 0;
		} catch (NumberFormatException e) {
			// A garbled line; ignore it.
		}
	}

	private static PigEntity makeMount(ServerWorld world, Vec3d at, float yaw) {
		PigEntity mount = EntityType.PIG.create(world);

		if (mount == null) {
			return null;
		}

		mount.setSilent(true);
		mount.setInvisible(true);
		mount.setNoGravity(true);
		mount.setAiDisabled(true);
		mount.setInvulnerable(true);

		// Ten hearts, to show the vehicle's hull (see onRide). Nothing in Minecraft can hurt it.
		EntityAttributeInstance health = mount.getAttributeInstance(EntityAttributes.GENERIC_MAX_HEALTH);

		if (health != null) {
			health.setBaseValue(MOUNT_HEALTH);
		}

		mount.setHealth(MOUNT_HEALTH);

		// Minecraft only keeps a mob invisible while it has the invisibility effect, so it is
		// given one that never runs out (and shows no swirls).
		mount.addStatusEffect(new StatusEffectInstance(StatusEffects.INVISIBILITY, StatusEffectInstance.INFINITE, 0, false, false));
		mount.addCommandTag(TAG);
		mount.refreshPositionAndAngles(at.x, at.y, at.z, yaw, 0.0F);
		world.spawnEntity(mount);
		return mount;
	}

	/**
	 * Something in Minecraft is about to hurt this entity. If it is a player in a vehicle and
	 * the harm comes from a mob or another player (a Drowned's blow, a creeper's blast), the
	 * vehicle's hull takes it instead.
	 *
	 * @return whether the entity should take the damage itself
	 */
	public static boolean allowDamage(LivingEntity entity, DamageSource source, float amount) {
		if (!(entity instanceof ServerPlayerEntity player) || source.getAttacker() == null) {
			return true;
		}

		// Aboard the Cyclops, the hull is between the player and Minecraft's mobs, and it
		// shrugs them off: a creeper's blast outside does nothing to the player or the sub.
		// (Only while the link is live and the player is really in the ocean void: the word
		// "aboard" can outlast both, and must not make anyone mob-proof back in their own world.)
		LinkSession session = Sessions.of(player);

		if (session != null && session.aboard && session.isActive()
				&& player.getServerWorld().getRegistryKey() == MovementBridge.OCEAN_VOID
				&& !(source.getAttacker() instanceof PlayerEntity)) {
			return false;
		}

		if (!isSeated(player)) {
			return true;
		}

		MinecraftServer server = player.getServer();

		if (server != null && amount > 0.0F) {
			Sessions.get(player).send(server, player, String.format(Locale.ROOT, "VHURT %.2f", amount * SUBNAUTICA_DAMAGE_PER_POINT));
		}

		return false;
	}

	/** True while this player is sitting in a Subnautica vehicle. */
	public static boolean isSeated(ServerPlayerEntity player) {
		return SEATS.containsKey(player.getUuid());
	}

	/** The player is out of the vehicle (or the link is over): off the mount, which is removed. */
	public static void end(ServerPlayerEntity player) {
		Seat seat = SEATS.remove(player.getUuid());

		if (seat == null) {
			return;
		}

		if (player.getVehicle() == seat.mount) {
			player.stopRiding();
		}

		seat.mount.discard();

		// Being let out somewhere is not a fall.
		FallTracker.reset(player);
	}

	/** Called every server tick. */
	public static void tick(MinecraftServer server) {
		Iterator<Map.Entry<UUID, Seat>> seats = SEATS.entrySet().iterator();

		while (seats.hasNext()) {
			Map.Entry<UUID, Seat> entry = seats.next();
			Seat seat = entry.getValue();
			ServerPlayerEntity player = server.getPlayerManager().getPlayer(entry.getKey());

			if (player == null || !player.isAlive() || player.isDisconnected() || !seat.mount.isAlive()
					|| player.getServerWorld() != seat.mount.getWorld() || ++seat.silentFor > SILENCE_BEFORE_GETTING_OFF) {
				if (player != null && player.getVehicle() == seat.mount) {
					player.stopRiding();
				}

				if (player != null) {
					FallTracker.reset(player);
				}

				seat.mount.discard();
				seats.remove();
				continue;
			}

			// Knocked off somehow (Minecraft's own sneak-to-dismount, say): back on.
			if (player.getVehicle() != seat.mount) {
				player.startRiding(seat.mount, true);
				seat.settledFor = 0;
			} else {
				seat.settledFor++;
			}

			// Put the mount where it carries the player's feet to the seat. How far above the
			// mount the feet ride is measured, once the player has settled on it.
			Vec3d above = player.getPos().subtract(seat.mount.getPos());

			if (seat.settledFor < 3 || above.lengthSquared() > 4.0) {
				above = FEET_ABOVE_MOUNT;
			}

			seat.mount.setPosition(seat.feet.subtract(above));
			seat.mount.setYaw(seat.yaw);
			seat.mount.setBodyYaw(seat.yaw);
			seat.mount.setHeadYaw(seat.yaw);
			seat.mount.setVelocity(Vec3d.ZERO);

			// Going up and down in a vehicle is not falling.
			FallTracker.reset(player);
		}

		// A mount left over from before (the game was closed mid-ride) is cleared away.
		if (server.getTicks() % 100 == 0) {
			ServerWorld world = server.getWorld(MovementBridge.OCEAN_VOID);

			if (world != null) {
				for (PigEntity stray : world.getEntitiesByType(EntityType.PIG, pig -> pig.getCommandTags().contains(TAG))) {
					boolean inUse = false;

					for (Seat seat : SEATS.values()) {
						inUse |= seat.mount == stray;
					}

					if (!inUse) {
						stray.discard();
					}
				}
			}
		}
	}
}
