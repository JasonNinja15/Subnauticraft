package com.example.subnauticalink;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.example.subnauticalink.mixin.FishingBobberAccessor;
import com.example.subnauticalink.mixin.PersistentProjectileAccessor;

import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.entity.FlyingItemEntity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.TntEntity;
import net.minecraft.entity.projectile.FishingBobberEntity;
import net.minecraft.entity.projectile.PersistentProjectileEntity;
import net.minecraft.entity.projectile.ProjectileEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.util.math.Vec3d;

/**
 * Tells this player's Subnautica about the loose things in the ocean void: dropped items, lit
 * TNT and projectiles. Client only.
 *
 * <p>Like blocks (see {@link ClientBlocks}), these are read from this game's own copy of the
 * world, so every player's Subnautica is told by that player's own game. The server only
 * decides where the things are; it no longer describes them to anyone.
 *
 * <ul>
 *   <li>"ITEMMODEL kind scale colour quads": what one kind of item looks like, once per kind
 *       (see {@link ItemModels});</li>
 *   <li>"ITEM id kind x y z": a dropped item is here;</li>
 *   <li>"SHOT id kind style x y z vx vy vz": a projectile (style 0 faces the camera, 1 points
 *       along its flight) or lit TNT (style 2) is here, moving this far a tick;</li>
 *   <li>"ITEMGONE id": that one has gone.</li>
 * </ul>
 */
public final class ClientEntities {
	/** Things within this many blocks of the player are shown in Subnautica. */
	private static final double SHOWN_WITHIN = 48.0;

	/** What Subnautica was last told about one thing. */
	private static final class Shown {
		Vec3d at;
		boolean moving;
		int kind;
	}

	private static final Map<Integer, Shown> SHOWN = new HashMap<>();
	private static final Set<Integer> SENT_MODELS = new HashSet<>();

	private ClientEntities() {
	}

	/** Subnautica (re)connected or went away: it has nothing, so start again. */
	public static void reset() {
		SHOWN.clear();
		SENT_MODELS.clear();
	}

	/** Called every client tick. */
	public static void tick(MinecraftClient client) {
		if (client.world == null || client.player == null || !RemoteCollision.appliesTo(client.player)) {
			return;
		}

		Map<Integer, Shown> still = new HashMap<>();

		for (Entity entity : client.world.getEntities()) {
			if (!entity.isAlive() || entity.squaredDistanceTo(client.player) > SHOWN_WITHIN * SHOWN_WITHIN) {
				continue;
			}

			// What it is drawn as, and how: -1 is a dropped item, which turns and bobs.
			ItemStack stack;
			int style;

			if (entity instanceof ItemEntity item) {
				stack = item.getStack();
				style = -1;
			} else if (entity instanceof TntEntity) {
				stack = new ItemStack(Items.TNT);
				style = 2;
			} else if (entity instanceof FishingBobberEntity) {
				// A fishing rod's float: shown as a small brown cap, facing the camera. With
				// something on the line it turns red: the moment to reel in.
				stack = new ItemStack(((FishingBobberAccessor) entity).subnauticaLink$caughtFish() ? Items.RED_MUSHROOM : Items.BROWN_MUSHROOM);
				style = 0;
			} else if (!(entity instanceof ProjectileEntity)) {
				continue;
			} else if (entity instanceof PersistentProjectileEntity arrow) {
				// The arrow, knife or hook itself, pointed along its flight.
				stack = ((PersistentProjectileAccessor) arrow).subnauticaLink$itemStack();
				style = 1;
			} else if (entity instanceof FlyingItemEntity thrown) {
				// The snowball (or whatever) being thrown, facing the camera.
				stack = thrown.getStack();
				style = 0;
			} else {
				continue;
			}

			if (stack == null || stack.isEmpty()) {
				continue;
			}

			int kind = describe(stack);
			int id = entity.getId();
			Shown shown = SHOWN.get(id);

			// Something that has changed what it looks like (a float with a bite) is taken away and shown afresh.
			if (shown != null && shown.kind != kind) {
				ClientLink.toSubnautica("ITEMGONE " + id);
				shown = null;
			}

			boolean isNew = shown == null;

			if (isNew) {
				shown = new Shown();
				shown.kind = kind;
			}

			if (style < 0) {
				// A dropped item: where its feet are, whenever it moves.
				Vec3d at = entity.getPos();

				if (isNew || at.squaredDistanceTo(shown.at) > 1.0E-5) {
					ClientLink.toSubnautica(String.format(Locale.ROOT, "ITEM %d %d %.3f %.3f %.3f", id, kind, at.x, at.y, at.z));
					shown.at = at;
				}
			} else {
				// A projectile or TNT: its middle, and how far it moved this tick, so
				// Subnautica can keep it moving smoothly between ticks.
				Vec3d at = entity.getPos().add(0.0, entity.getHeight() / 2.0, 0.0);
				Vec3d step = isNew ? (style == 2 ? Vec3d.ZERO : entity.getVelocity()) : at.subtract(shown.at);
				boolean moving = step.lengthSquared() > 1.0E-6;

				if (isNew || moving || shown.moving) {
					Vec3d sent = moving ? step : Vec3d.ZERO;
					// Lit TNT also says how many ticks its fuse has left, so Subnautica can flash it in time.
					String fuse = entity instanceof TntEntity tnt ? " " + tnt.getFuse() : "";
					ClientLink.toSubnautica(String.format(Locale.ROOT, "SHOT %d %d %d %.3f %.3f %.3f %.4f %.4f %.4f",
							id, kind, style, at.x, at.y, at.z, sent.x, sent.y, sent.z) + fuse);
				}

				shown.at = at;
				shown.moving = moving;
			}

			still.put(id, shown);
		}

		for (int id : SHOWN.keySet()) {
			if (!still.containsKey(id)) {
				ClientLink.toSubnautica("ITEMGONE " + id);
			}
		}

		SHOWN.clear();
		SHOWN.putAll(still);
	}

	/** Makes sure Subnautica has been told what this kind of item looks like, and returns the number that kind goes by. */
	private static int describe(ItemStack stack) {
		int kind = Registries.ITEM.getRawId(stack.getItem());

		if (SENT_MODELS.add(kind)) {
			String model = "0.25 B0B0B0 ";

			try {
				model = ItemModels.describe(stack);
			} catch (RuntimeException e) {
				SubnauticaLink.LOGGER.warn("Could not read the model of {}", stack, e);
			}

			ClientLink.toSubnautica("ITEMMODEL " + kind + " " + model);
		}

		return kind;
	}
}
