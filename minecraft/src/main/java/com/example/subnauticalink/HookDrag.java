package com.example.subnauticalink;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;

import com.example.subnauticalink.mixin.ProjectileInvoker;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.passive.PigEntity;
import net.minecraft.entity.projectile.PersistentProjectileEntity;
import net.minecraft.entity.projectile.ProjectileEntity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.math.Vec3d;

/**
 * The grappling hook dragging Subnautica's creatures in.
 *
 * <p>The grappling hook mod knows how to latch onto a Minecraft mob and haul it toward its
 * thrower. A Subnautica creature isn't a Minecraft mob, so it is given a stand-in: an
 * invisible pig, put where the creature is, for the hook to latch onto. From then on the two
 * are kept in step:
 * <ul>
 *   <li>the hook pulls on the pig by giving it a speed. The pig never moves by itself; that
 *       speed is passed to Subnautica ("PULL id vx vy vz"), which moves the creature;</li>
 *   <li>Subnautica says where the creature now is ("HELD id x y z"), and the pig is put
 *       there, so the hook (which rides on the pig) and its rope follow the creature.</li>
 * </ul>
 * When the hook lets go or the creature is gone, the pig is removed.
 *
 * <p>Only creatures up to about a Gasopod's size are dragged. Subnautica says which are too
 * big; for those the hook behaves as if it had hit scenery, and pulls the player in instead
 * (see {@link Projectiles}).
 *
 * <p>The hook mod is recognised by the name of its hook, so this mod works without it too.
 */
public final class HookDrag {
	private static final String TAG = "subnautica_link_anchor";

	/** With no word from Subnautica about the creature for this many ticks, let go. */
	private static final int SILENCE_BEFORE_LETTING_GO = 40;

	private static final class Hold {
		PigEntity anchor;
		Entity hook;
		ServerPlayerEntity player;
		int silentFor;
	}

	/** The holds in progress, by the stand-in's number. */
	private static final Map<Integer, Hold> HOLDS = new HashMap<>();

	private HookDrag() {
	}

	public static boolean isHook(Entity entity) {
		return entity.getClass().getSimpleName().equals("GrapplingHookEntity");
	}

	/** A hook has hit a creature small enough to drag: give it a stand-in to latch onto. */
	public static void latch(MinecraftServer server, ServerWorld world, ProjectileEntity hook, Vec3d point, ServerPlayerEntity reporter) {
		PigEntity anchor = EntityType.PIG.create(world);

		if (anchor == null) {
			return;
		}

		anchor.setSilent(true);
		anchor.setInvisible(true);
		anchor.setNoGravity(true);
		anchor.setAiDisabled(true);
		anchor.setInvulnerable(true);

		// Minecraft only keeps a mob invisible while it has the invisibility effect, so it is
		// given one that never runs out (and shows no swirls).
		anchor.addStatusEffect(new StatusEffectInstance(StatusEffects.INVISIBILITY, StatusEffectInstance.INFINITE, 0, false, false));
		anchor.addCommandTag(TAG);
		anchor.refreshPositionAndAngles(point.x, point.y - anchor.getHeight() / 2.0, point.z, 0.0F, 0.0F);
		world.spawnEntity(anchor);

		Hold hold = new Hold();
		hold.anchor = anchor;
		hold.hook = hook;
		hold.player = reporter;
		HOLDS.put(anchor.getId(), hold);

		// "You hit this mob": the hook's own code latches on.
		((ProjectileInvoker) hook).subnauticaLink$onCollision(new EntityHitResult(anchor, point));

		// The Subnautica that saw the creature is the one that moves it.
		Sessions.get(reporter).send(server, reporter, String.format(Locale.ROOT, "GRAB %d %.3f %.3f %.3f", anchor.getId(), point.x, point.y, point.z));
	}

	/** "id x y z": where the held creature now is (Subnautica's coordinates). "id -": it is gone. */
	public static void onHeld(String text) {
		String[] parts = text.trim().split(" ");

		try {
			Hold hold = HOLDS.get(Integer.parseInt(parts[0]));

			if (hold == null) {
				return;
			}

			if (parts.length != 4) {
				// Dead, or never found: removing the stand-in makes the hook let go.
				hold.anchor.discard();
				return;
			}

			// As everywhere, z is flipped between the two games.
			hold.anchor.setPosition(Double.parseDouble(parts[1]), Double.parseDouble(parts[2]) - hold.anchor.getHeight() / 2.0, -Double.parseDouble(parts[3]));
			hold.anchor.velocityDirty = true;
			hold.silentFor = 0;
		} catch (NumberFormatException e) {
			// A garbled line; ignore it.
		}
	}

	/** Called every server tick. */
	public static void tick(MinecraftServer server) {
		ServerWorld world = server.getWorld(MovementBridge.OCEAN_VOID);

		if (world == null) {
			return;
		}

		Iterator<Hold> holds = HOLDS.values().iterator();

		while (holds.hasNext()) {
			Hold hold = holds.next();
			boolean hookGone = !hold.hook.isAlive() || (hold.hook instanceof PersistentProjectileEntity arrow && arrow.isNoClip());

			if (hookGone || !hold.anchor.isAlive() || !hold.player.isAlive() || hold.player.isDisconnected() || ++hold.silentFor > SILENCE_BEFORE_LETTING_GO) {
				if (!hold.player.isDisconnected()) {
					Sessions.get(hold.player).send(server, hold.player, "LETGO " + hold.anchor.getId());
				}

				hold.anchor.discard();
				holds.remove();
				continue;
			}

			// Whatever speed the hook has given the stand-in is what the creature should do.
			// It fades, as a real mob's would from the drag of the water.
			Vec3d pull = hold.anchor.getVelocity();
			Sessions.get(hold.player).send(server, hold.player, String.format(Locale.ROOT, "PULL %d %.4f %.4f %.4f", hold.anchor.getId(), pull.x, pull.y, pull.z));
			hold.anchor.setVelocity(pull.multiply(0.7));
		}

		// A stand-in left over from before (the game was closed mid-drag) is cleared away.
		for (PigEntity stray : world.getEntitiesByType(EntityType.PIG, pig -> pig.getCommandTags().contains(TAG) && !HOLDS.containsKey(pig.getId()))) {
			stray.discard();
		}
	}
}
