package com.example.subnauticalink;

import java.util.Locale;
import java.util.UUID;
import java.util.function.Consumer;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.enchantment.Enchantments;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.item.ItemStack;
import net.minecraft.item.MiningToolItem;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.world.GameRules;

/**
 * One player's link, on the server: everything the server keeps track of for that player's
 * Subnautica. In single player there is just the one.
 *
 * <ul>
 *   <li>Health: whichever game it changes in, the other matches it. Sent as a fraction,
 *       because Minecraft counts 20 and Subnautica 100.</li>
 *   <li>Hunger: Minecraft is in charge; Subnautica's food bar follows, and eating there adds
 *       to Minecraft's bar.</li>
 *   <li>Oxygen: Subnautica is in charge; Minecraft's bubble bar just shows it.</li>
 *   <li>Death in either game is death in the other.</li>
 *   <li>Movement: where the player is put when the games link (see {@link MovementBridge}).</li>
 *   <li>Attacks on Subnautica's creatures, and which Subnautica tool is in hand.</li>
 * </ul>
 */
public final class LinkSession {
	/** If the player's game has said nothing for this long, their Subnautica is treated as gone. */
	private static final long SILENCE_BEFORE_INACTIVE_MS = 4000;

	/** Health changes smaller than this (as a fraction of full health) are not worth sending. */
	private static final float SMALLEST_CHANGE = 0.001F;

	/** One point of Minecraft damage is this much in Subnautica (100 health there, 20 here). */
	private static final double SUBNAUTICA_DAMAGE_PER_POINT = 5.0;

	/** Minecraft's hunger bar holds this many points (each drumstick is two). */
	private static final int MAX_FOOD = 20;

	/** Which player this is. */
	public final UUID id;

	private volatile boolean linked;
	private volatile long lastAlive;

	/** True when this player's Subnautica says they are somewhere with air around them. */
	public volatile boolean dry;

	/** How tall the player is just now in their own game (standing, crouched, swimming). The server lets them be no taller: see PlayerEntityMixin. */
	public volatile float poseHeight = 10.0F;

	/** Which of Subnautica's regions this player is in (its own name for it), or null if it hasn't said. Digging gives different things in each. */
	public volatile String biome;

	/** True when it is night in this player's Subnautica. */
	public volatile boolean night;

	/** Whether the player had been asleep in a bed long enough, last tick, for the night to be skipped. */
	private boolean sleptEnough;

	/** True when this player's Subnautica says they are aboard the Cyclops, where Minecraft's mobs can't get at them. */
	public volatile boolean aboard;

	/** Whether this player currently counts as in the water (see WaterState). */
	public boolean wet;

	/** The Subnautica tool this player's Subnautica was last told is in hand, or null. */
	public String equippedTool;

	private final MovementBridge movement = new MovementBridge();

	private boolean lastSwingWasStrong;
	private boolean wasInVoid;
	private int sentCheats = -1;
	private boolean applyingRemote;
	private float sharedFraction = -1.0F;
	private int sharedFoodLevel = -1;
	private float leftoverFood;
	private float oxygenFraction = -1.0F;

	LinkSession(UUID id) {
		this.id = id;
	}

	/** True while this player's Subnautica is linked and sending controls. */
	/** The player is leaving the server: put them back where they were before the ocean void. */
	public void goHome(MinecraftServer server, ServerPlayerEntity player) {
		this.movement.goHome(server, player);
	}

	public boolean isActive() {
		return this.linked && System.currentTimeMillis() - this.lastAlive < SILENCE_BEFORE_INACTIVE_MS;
	}

	/** Sends one line to this player's Subnautica, by way of their game. */
	public void send(MinecraftServer server, ServerPlayerEntity player, String line) {
		// A note for the game itself (it starts "@") rather than for Subnautica.
		boolean note = line.startsWith("@");
		Consumer<String> local = note ? Sessions.localNote : Sessions.localSink;

		if (local != null && server.isHost(player.getGameProfile())) {
			// The world is hosted from this player's own game: hand it straight over.
			local.accept(line);
		} else if (ServerPlayNetworking.canSend(player, LinePayload.ID)) {
			ServerPlayNetworking.send(player, new LinePayload(line));
		}
	}

	/** Something this player's Subnautica said, passed on by their game. */
	void onLine(MinecraftServer server, ServerPlayerEntity player, String line) {
		if (line.equals("LINKED")) {
			this.linked = true;
			this.lastAlive = System.currentTimeMillis();
			player.sendMessage(Text.literal("Subnautica linked"), false);
			// With shared death, nobody is at the Minecraft window to click "Respawn".
			server.getGameRules().get(GameRules.DO_IMMEDIATE_RESPAWN).set(true, server);
			// Minecraft's health and hunger win at the moment of linking: send them afresh.
			this.sharedFraction = -1.0F;
			this.sharedFoodLevel = -1;
			this.equippedTool = null;
			this.sentCheats = -1;
		} else if (line.equals("UNLINKED")) {
			this.linked = false;
			player.sendMessage(Text.literal("Subnautica link lost"), false);
			// Give the bubble bar back to Minecraft; the next tick sends the player home.
			this.oxygenFraction = -1.0F;
			this.dry = false;
			this.aboard = false;
			this.poseHeight = 10.0F;
			Sessions.note("@LIGHT " + player.getId() + " 0");
			Riding.end(player);
			this.movement.onDisconnected();
		} else if (line.equals("ALIVE")) {
			this.linked = true;
			this.lastAlive = System.currentTimeMillis();
		} else if (line.startsWith("SWEPT ")) {
			// Where a dropped item got to: the answer to a question the server asked this
			// player's Subnautica.
			ItemSync.onAnswer(line.substring("SWEPT ".length()));
			VoidMobs.onAnswer(line.substring("SWEPT ".length()));
		} else if (line.startsWith("PHIT ")) {
			// This player's game says one of the projectiles is about to hit something.
			Projectiles.onReport(player, line.substring("PHIT ".length()));
		} else if (line.startsWith("DIG ")) {
			if (this.isActive()) {
				Gathering.onDig(server, player, line.substring("DIG ".length()));
			}
		} else if (line.startsWith("TRADE ")) {
			if (this.isActive()) {
				Gathering.onTrade(player, line.substring("TRADE ".length()));
			}
		} else if (line.startsWith("HELD ")) {
			HookDrag.onHeld(line.substring("HELD ".length()));
		} else if (line.startsWith("PROBED ")) {
			VoidMobs.onProbed(player, line.substring("PROBED ".length()));
		} else if (line.startsWith("KILLED ")) {
			if (this.isActive()) {
				CreatureDrops.onKilled(player, line.substring("KILLED ".length()));
			}
		} else if (line.equals("SWING")) {
			this.attack(server, player);
		} else if (line.startsWith("DRY ")) {
			this.dry = line.substring("DRY ".length()).trim().equals("1");
		} else if (line.startsWith("MYLIGHT ")) {
			// What light the thing in this player's hand gives: passed on to everyone's game.
			String light = line.substring("MYLIGHT ".length()).trim();

			if (HeldLights.isLight(light)) {
				Sessions.note("@LIGHT " + player.getId() + " " + light);
			}
		} else if (line.startsWith("LAVASCOOP ")) {
			if (this.isActive()) {
				SeaBucket.onVerdict(player, this, line.trim().endsWith("1"));
			}
		} else if (line.startsWith("POSEH ")) {
			try {
				this.poseHeight = Float.parseFloat(line.substring("POSEH ".length()).trim());
			} catch (NumberFormatException e) {
				// A garbled line; ignore it.
			}
		} else if (line.startsWith("BIOME ")) {
			this.biome = line.substring("BIOME ".length()).trim();
		} else if (line.startsWith("BUY ")) {
			if (this.isActive()) {
				Gathering.onBuy(server, player, line.substring("BUY ".length()));
			}
		} else if (line.startsWith("SPACED ")) {
			VoidMobs.onSpaced(player, line.substring("SPACED ".length()));
		} else if (line.startsWith("NIGHT ")) {
			this.night = line.substring("NIGHT ".length()).trim().equals("1");
		} else if (line.startsWith("ABOARD ")) {
			this.aboard = line.substring("ABOARD ".length()).trim().equals("1");
		} else if (line.startsWith("TOOLS")) {
			ToolTokens.onToolsLine(player, line.substring("TOOLS".length()).trim());
		} else if (line.equals("HIT")) {
			this.onAttackLanded(player);
		} else if (line.startsWith("RIDE ")) {
			// In a vehicle in Subnautica: sit where it says (see Riding).
			if (this.isActive() && this.movement.inVoid()) {
				// A vehicle's cabin is dry (Subnautica says so too, a moment later; pigs get off underwater).
				this.dry = true;
				Riding.onRide(player, line.substring("RIDE ".length()));
			}
		} else if (line.startsWith("SPAWN ")) {
			// Out of any vehicle first: Minecraft moves the player again from the new spot.
			Riding.end(player);
			this.movement.onSpawnLine(line.substring("SPAWN ".length()));
		} else if (line.startsWith("HEALTH ")) {
			this.applyRemoteHealth(player, parseFraction(line.substring("HEALTH ".length())));
		} else if (line.startsWith("ATE ")) {
			this.applyRemoteMeal(player, parseFraction(line.substring("ATE ".length())));
		} else if (line.startsWith("OXYGEN ")) {
			this.oxygenFraction = parseFraction(line.substring("OXYGEN ".length()));
		} else if (line.equals("DEATH")) {
			this.applyRemoteDeath(player);
		}
	}

	/** Called at the end of every server tick. */
	void tick(MinecraftServer server, ServerPlayerEntity player) {
		Consumer<String> send = line -> this.send(server, player, line);

		this.reportHealth(player, send);
		this.reportFood(player, send);
		this.showOxygen(player);
		this.movement.tick(server, player, this.isActive());

		// Linked and sending controls, but the player isn't in the ocean void: Subnautica's
		// word on where to start was lost, or the player was sent home when Subnautica went
		// quiet for a moment (it does while loading). Ask it again, once a second, where the
		// player is. Before this, the two stayed "linked" with nothing happening until
		// something (dying, usually) made Subnautica say where the player was.
		if (this.isActive() && !this.movement.inVoid() && player.isAlive() && player.age % 20 == 0) {
			send.accept("WHERE");
		}

		// Newly in the ocean void: Subnautica may have missed the health and hunger sent when
		// the link was made (it has no player while at its menu), so they are sent afresh.
		if (this.movement.inVoid() && !this.wasInVoid) {
			this.sharedFraction = -1.0F;
			this.sharedFoodLevel = -1;
		}

		this.wasInVoid = this.movement.inVoid();

		// Subnautica's command console (F9 there) is only for players this world allows cheats:
		// the same test Minecraft uses for its own cheat commands. Told at linking and on any change.
		int cheats = player.hasPermissionLevel(2) ? 1 : 0;

		if (this.linked && cheats != this.sentCheats) {
			this.sentCheats = cheats;
			send.accept("CHEATS " + cheats);
		}

		// An elytra is for the air: gliding stops on entering the water.
		if (player.isFallFlying() && WaterState.isTouching(player)) {
			player.stopFallFlying();
		}
		ToolTokens.tick(player, this, send);

		// A night's sleep in a Minecraft bed: once the player has slept as long as Minecraft
		// asks before skipping the night, and then gets up, Subnautica skips its night too.
		boolean enough = player.isSleeping() && player.canResetTimeBySleeping();

		if (this.sleptEnough && this.night && !player.isSleeping() && this.movement.inVoid() && player.isAlive()) {
			send.accept("SLEPT");
		}

		this.sleptEnough = enough;
	}

	/** This player died in Minecraft: tell their Subnautica (unless that is where the death came from). */
	void onDeath(MinecraftServer server, ServerPlayerEntity player) {
		if (!this.applyingRemote && this.linked) {
			this.send(server, player, "DEATH");
		}
	}

	// ---- Attacking Subnautica's creatures ---------------------------------------------------

	/**
	 * Left click in Subnautica is an attack with whatever Minecraft's player is holding.
	 * Minecraft works out how hard the hit is, exactly as it would for a mob: the held item's
	 * attack damage, weakened if you swing again before the weapon has recovered. Subnautica
	 * works out whether anything was in reach, and takes the damage off it.
	 *
	 * <p>Damage crosses over in proportion, the same way health does: one point here is five
	 * there. An iron sword's 6 becomes 30, a little more than Subnautica's knife (20).
	 */
	private void attack(MinecraftServer server, ServerPlayerEntity player) {
		if (!player.isAlive() || !this.isActive() || player.getServerWorld().getRegistryKey() != MovementBridge.OCEAN_VOID) {
			return;
		}

		// Holding a Subnautica tool: the click is Subnautica's (its knife does its own damage).
		if (ToolTokens.isHoldingTool(player)) {
			return;
		}

		double damage = player.getAttributeValue(EntityAttributes.GENERIC_ATTACK_DAMAGE);

		// 0 just after a swing, rising to 1 once the weapon has recovered. Minecraft's own
		// formula turns that into between a fifth of the damage and all of it.
		float recovered = player.getAttackCooldownProgress(0.5F);
		damage *= 0.2 + recovered * recovered * 0.8;

		// Enchantments on the weapon. Sharpness adds what it adds against any mob. Impaling is
		// for things that live in the sea, which is everything in Subnautica. Smite and Bane of
		// Arthropods have nothing here to work on. Like Minecraft, the extra is weakened by a
		// hasty swing.
		ItemStack held = player.getMainHandStack();
		int sharpness = CreatureDrops.level(player, Enchantments.SHARPNESS, held);
		int impaling = CreatureDrops.level(player, Enchantments.IMPALING, held);
		damage += ((sharpness > 0 ? 0.5 * sharpness + 0.5 : 0.0) + 2.5 * impaling) * recovered;

		// Fire Aspect sets the creature alight for four seconds a level. Knockback shoves it,
		// and so does a sprinting hit, as in Minecraft.
		int burnSeconds = 4 * CreatureDrops.level(player, Enchantments.FIRE_ASPECT, held);
		int knockback = CreatureDrops.level(player, Enchantments.KNOCKBACK, held) + (player.isSprinting() && recovered > 0.9F ? 1 : 0);
		player.resetLastAttackedTicks();

		// Remembered for the sound, if this swing lands.
		this.lastSwingWasStrong = recovered > 0.9F;

		double reach = player.getEntityInteractionRange();
		this.send(server, player, String.format(Locale.ROOT, "ATTACK %.2f %.2f %d %d", damage * SUBNAUTICA_DAMAGE_PER_POINT, reach, burnSeconds, knockback));
	}

	/** "HIT": the attack landed on something in Subnautica. Wear the weapon and tire the player, as a hit on a mob would. */
	private void onAttackLanded(ServerPlayerEntity player) {
		if (!player.isAlive()) {
			return;
		}

		player.getWorld().playSound(null, player.getX(), player.getY(), player.getZ(),
				this.lastSwingWasStrong ? SoundEvents.ENTITY_PLAYER_ATTACK_STRONG : SoundEvents.ENTITY_PLAYER_ATTACK_WEAK,
				SoundCategory.PLAYERS, 1.0F, 1.0F);

		// Minecraft's own wear: one point for a weapon, two for a tool used as one. (Nothing wears out in Creative mode.)
		ItemStack held = player.getMainHandStack();
		held.damage(held.getItem() instanceof MiningToolItem ? 2 : 1, player, EquipmentSlot.MAINHAND);
		player.addExhaustion(0.1F);
	}

	private static float parseFraction(String text) {
		try {
			return Math.max(0.0F, Math.min(1.0F, Float.parseFloat(text.trim())));
		} catch (NumberFormatException e) {
			return 0.0F;
		}
	}

	// ---- Health, hunger, oxygen, death --------------------------------------------------------

	/** If the player's health is not what the two games last agreed on, tell Subnautica. */
	private void reportHealth(ServerPlayerEntity player, Consumer<String> send) {
		if (!player.isAlive() || !this.linked) {
			// Dead (death is sent separately) or not linked. Send afresh once that changes.
			this.sharedFraction = -1.0F;
			return;
		}

		float fraction = player.getHealth() / player.getMaxHealth();

		if (this.sharedFraction < 0.0F || Math.abs(fraction - this.sharedFraction) > SMALLEST_CHANGE) {
			send.accept("HEALTH " + String.format(Locale.ROOT, "%.4f", fraction));
			this.sharedFraction = fraction;
		}
	}

	/** Subnautica's health changed: match it. */
	private void applyRemoteHealth(ServerPlayerEntity player, float fraction) {
		if (!player.isAlive() || fraction <= 0.0F) {
			return;
		}

		float target = fraction * player.getMaxHealth();

		if (target < player.getHealth()) {
			// Going down: deal it as damage first so you get the red flash and the hurt sound.
			// Minecraft ignores hits for half a second after the last one; clear that.
			player.timeUntilRegen = 0;
			player.damage(player.getDamageSources().generic(), player.getHealth() - target);
		}

		if (player.isAlive()) {
			// Then set the exact value, so armour or potion effects can't leave the bars apart.
			player.setHealth(target);
			this.sharedFraction = player.getHealth() / player.getMaxHealth();
		}
	}

	/** Minecraft is in charge of hunger: whenever the bar changes, Subnautica is told the new level. */
	private void reportFood(ServerPlayerEntity player, Consumer<String> send) {
		if (!player.isAlive() || !this.linked) {
			this.sharedFoodLevel = -1;
			return;
		}

		int level = player.getHungerManager().getFoodLevel();

		if (level != this.sharedFoodLevel) {
			send.accept("FOOD " + String.format(Locale.ROOT, "%.4f", level / (float) MAX_FOOD));
			this.sharedFoodLevel = level;
		}
	}

	/**
	 * The player ate something in Subnautica. The amount arrives as a fraction of a full bar.
	 * Minecraft counts hunger in whole points, so anything left over is kept for the next meal.
	 */
	private void applyRemoteMeal(ServerPlayerEntity player, float fraction) {
		if (!player.isAlive() || fraction <= 0.0F) {
			return;
		}

		this.leftoverFood += fraction * MAX_FOOD;
		int wholePoints = (int) Math.floor(this.leftoverFood);
		this.leftoverFood -= wholePoints;

		if (wholePoints > 0) {
			int level = Math.min(MAX_FOOD, player.getHungerManager().getFoodLevel() + wholePoints);
			player.getHungerManager().setFoodLevel(level);
		}
	}

	/**
	 * Makes the bubble bar show Subnautica's oxygen. On land Minecraft refills air every tick,
	 * so the value has to be put back every tick.
	 */
	private void showOxygen(ServerPlayerEntity player) {
		if (this.oxygenFraction < 0.0F || !player.isAlive()) {
			return;
		}

		if (this.oxygenFraction >= 0.999F) {
			// Full: hide the bar and stop overriding until Subnautica reports a drop.
			player.setAir(player.getMaxAir());
			this.oxygenFraction = -1.0F;
		} else {
			player.setAir(Math.round(this.oxygenFraction * player.getMaxAir()));
		}
	}

	private void applyRemoteDeath(ServerPlayerEntity player) {
		this.applyingRemote = true;

		try {
			if (player.isAlive()) {
				player.kill();
			}
		} finally {
			this.applyingRemote = false;
		}
	}
}
