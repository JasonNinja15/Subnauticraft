package com.example.subnauticalink;

import com.example.subnauticalink.mixin.MouseAccessor;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ChatScreen;
import net.minecraft.client.gui.screen.DownloadingTerrainScreen;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.client.option.GameOptions;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

/**
 * Client-only setup: the parts that concern the player's own game window and controls.
 */
public final class SubnauticaLinkClient implements ClientModInitializer {
	/** A stick or key has to be pushed this far to count as pressed. */
	private static final float PRESS_THRESHOLD = 0.3F;

	/** True while this class is holding Minecraft's movement keys on Subnautica's behalf. */
	private boolean holdingKeys;

	/** Whether the left mouse button was down last tick, to spot the moment it is pressed. */
	private boolean wasAttacking;

	/** Whether a Minecraft screen was open last tick, to report changes to Subnautica. */
	private boolean screenWasOpen;

	@Override
	public void onInitializeClient() {
		// Minecraft normally opens the pause menu the moment you click on another window, and
		// in single player that freezes the world. With Subnautica in front, Minecraft would be
		// frozen all the time. This switches that off (the same setting F3 + P toggles).
		ClientLifecycleEvents.CLIENT_STARTED.register(client -> {
			if (client.options.pauseOnLostFocus) {
				client.options.pauseOnLostFocus = false;
				SubnauticaLink.LOGGER.info("Turned off pause-on-lost-focus so Minecraft keeps running behind Subnautica");
			}
		});

		// This player's own connection to their own Subnautica.
		ClientLink.init();
		ClientDropsList.init();

		// Block changes in this game's copy of the world, and chunks leaving it.
		BlockSync.listener = ClientBlocks::onChanged;
		ClientChunkEvents.CHUNK_UNLOAD.register((world, chunk) -> ClientBlocks.onChunkUnload(chunk.getPos()));

		// 20 times a second, before Minecraft works out the player's movement for the tick.
		ClientTickEvents.START_CLIENT_TICK.register(client -> {
			ClientLink.tick(client);
			this.applyRemoteControls(client);
		});

		// And after it: tell Subnautica where the player has ended up.
		ClientTickEvents.END_CLIENT_TICK.register(ClientLink::afterTick);
	}

	/**
	 * Presses and releases Minecraft's own movement keys to match what is held in Subnautica,
	 * and points the player where Subnautica's camera is pointing. Minecraft then moves the
	 * player exactly as if you had pressed those keys in its own window.
	 */
	private void applyRemoteControls(MinecraftClient client) {
		GameOptions keys = client.options;

		boolean linked = client.player != null
				&& RemoteControls.isActive()
				&& client.player.getWorld().getRegistryKey() == MovementBridge.OCEAN_VOID;

		this.reportScreen(client, linked);

		if (!linked) {
			// Held still in the ocean void (see EntityMixin): no speed builds up meanwhile.
			if (client.player != null && RemoteCollision.strandedInVoid(client.player)) {
				client.player.setVelocity(Vec3d.ZERO);
			}

			if (this.holdingKeys) {
				// The link ended: let go of everything once.
				this.setKeys(keys, 0.0F, 0.0F, false, false, false);
				keys.attackKey.setPressed(false);
				keys.useKey.setPressed(false);
				this.wasAttacking = false;
				this.holdingKeys = false;
				ClientTools.release(client.player);
			}

			// Anything pressed in Subnautica meanwhile (E, a number key, a click) is let go of,
			// not saved up to happen all at once when the link comes back.
			while (RemoteControls.takeTyping() != null) {
				// Thrown away.
			}

			RemoteControls.takePickedSlot();
			RemoteControls.takeScroll();
			RemoteControls.takeAttackPresses();
			RemoteControls.takeUsePresses();
			return;
		}

		this.setKeys(keys, RemoteControls.forward, RemoteControls.strafe, RemoteControls.jump, RemoteControls.sneak, RemoteControls.sprint);
		this.holdingKeys = true;

		client.player.setYaw(RemoteControls.yaw);
		client.player.setPitch(RemoteControls.pitch);

		// An elytra is for the air: gliding stops on entering the water.
		if (client.player.isFallFlying() && WaterState.isTouching(client.player)) {
			client.player.stopFallFlying();
		}

		// Holding a Subnautica tool: both mouse buttons work that tool in Subnautica, so
		// Minecraft doesn't swing, attack or use anything.
		boolean toolInHand = ToolTokens.isHoldingTool(client.player);

		// What using that tool does here: the hand's movement, the air bladder's lift, the Seaglide holding its depth.
		ClientTools.tick(client, ToolTokens.toolOf(client.player.getMainHandStack()));

		// Right mouse button: use the held item (eat, for example) for as long as it is held.
		keys.useKey.setPressed(RemoteControls.use && !toolInHand);

		// A tap that went down and came up again between two ticks was never seen held: it is
		// reported as one press, so the block is still placed.
		if (RemoteControls.takeUsePresses() > 0 && !RemoteControls.use && !toolInHand) {
			KeyBinding.onKeyPressed(InputUtil.fromTranslationKey(keys.useKey.getBoundKeyTranslationKey()));
		}

		// Left mouse button. Holding a key down and the moment of pressing it are two separate
		// things to Minecraft, and a swing or a hit happens on the press. So besides holding
		// the key, report one press each time the button goes down.
		boolean attacking = RemoteControls.attack && !toolInHand;
		int attackPresses = RemoteControls.takeAttackPresses();

		// (Or a click so quick that the button is already up again by this tick.)
		if ((attacking && !this.wasAttacking) || (attackPresses > 0 && !attacking && !this.wasAttacking && !toolInHand)) {
			KeyBinding.onKeyPressed(InputUtil.fromTranslationKey(keys.attackKey.getBoundKeyTranslationKey()));
		}

		keys.attackKey.setPressed(attacking);
		this.wasAttacking = attacking;

		// Holding the button on Subnautica's terrain digs at it.
		ClientDigging.tick(client, attacking);

		// A press of the button may also be an attack on one of Subnautica's creatures. How
		// hard it hits is the server's business, so the server is told a swing was made. (The
		// presses are counted as they arrive, so a quick click between two ticks isn't missed.)
		//
		// Not when the crosshair is on one of Minecraft's own mobs (a Drowned, a creeper):
		// Minecraft deals that blow itself. Telling the server about a swing as well made it
		// count the weapon as only just swung, so the real blow landed at a fifth of its strength.
		// (A mob that can be seen, that is. The unseen stand-ins this mod uses, for a creature on
		// the grappling hook or a seat in a vehicle, don't count: a swing at one of those is a
		// swing at whatever Subnautica has there.)
		boolean onMinecraftMob = client.crosshairTarget instanceof EntityHitResult target
				&& target.getEntity() instanceof LivingEntity living && !living.isInvisible();

		if (attackPresses > 0 && !toolInHand && !onMinecraftMob) {
			ClientLink.toServer("SWING");
		}

		this.typeIntoMinecraft(client);

		// Hotbar: number keys pick a slot, the scroll wheel moves along it. Minecraft tells
		// the server about the change by itself.
		PlayerInventory inventory = client.player.getInventory();
		int picked = RemoteControls.takePickedSlot();

		if (picked >= 0) {
			inventory.selectedSlot = picked;
		}

		int scroll = RemoteControls.takeScroll();

		if (scroll != 0) {
			inventory.selectedSlot = Math.floorMod(inventory.selectedSlot + scroll, 9);
		}
	}

	/**
	 * Lets blocks be placed against Subnautica's scenery.
	 *
	 * <p>Minecraft places a block against whatever block you are looking at. Looking at
	 * Subnautica's seabed, Minecraft sees nothing there. So when Minecraft's own aim finds
	 * nothing but Subnautica reports scenery in reach, Minecraft is told it is looking at the
	 * empty space just in front of that scenery. Empty space can be "replaced" by a block, so
	 * right-clicking with a block puts it exactly there, through all of Minecraft's normal
	 * placing code. Aiming at a real Minecraft block is left to Minecraft.
	 *
	 * <p>Called straight after Minecraft works out what you are looking at (from
	 * {@code GameRendererMixin}), which it does at the start of every tick and every frame.
	 */
	public static void aimAtSubnauticaScenery(MinecraftClient client) {
		if (client.player == null || client.player.getWorld().getRegistryKey() != MovementBridge.OCEAN_VOID) {
			return;
		}

		double[] aim = RemoteControls.aim;
		HitResult current = client.crosshairTarget;

		if (aim == null || (current != null && current.getType() != HitResult.Type.MISS)) {
			return;
		}

		Vec3d point = new Vec3d(aim[0], aim[1], aim[2]);

		// Step a little way off the surface, to land in the space in front of it.
		BlockPos space = BlockPos.ofFloored(aim[0] + aim[3] * 0.02, aim[1] + aim[4] * 0.02, aim[2] + aim[5] * 0.02);
		Direction side = Direction.getFacing(aim[3], aim[4], aim[5]);
		client.crosshairTarget = new BlockHitResult(point, side, space, false);
	}

	/**
	 * Typing commands from Subnautica. Pressing "/" there opens Minecraft's chat box with a
	 * "/" in it, and each key typed after that is passed to the chat box exactly as if it had
	 * been typed in Minecraft's own window. Enter sends, Escape cancels; Minecraft's chat box
	 * handles both itself, along with Tab to complete and the up arrow for earlier commands.
	 */
	private void typeIntoMinecraft(MinecraftClient client) {
		String event;

		while ((event = RemoteControls.takeTyping()) != null) {
			if (event.startsWith("CHAT ")) {
				client.setScreen(new ChatScreen(event.substring("CHAT ".length())));
			} else if (event.equals("INVENTORY")) {
				// "E" in Subnautica: open the inventory, unless some screen is already open.
				// (In Creative mode, Minecraft swaps this for the Creative inventory itself.)
				if (client.currentScreen == null) {
					client.setScreen(new InventoryScreen(client.player));
				}
			} else if (event.startsWith("DROP ")) {
				// "Q" in Subnautica: drop the held item ("DROP 1" for the whole pile), exactly
				// as Minecraft's own drop key does. Tool tokens can't be dropped: they stand
				// for tools in Subnautica's inventory.
				if (client.currentScreen == null && !client.player.isSpectator()
						&& ToolTokens.toolOf(client.player.getMainHandStack()) == null
						&& client.player.dropSelectedItem(event.endsWith("1"))) {
					client.player.swingHand(Hand.MAIN_HAND);
				}
			} else if (event.equals("SWAP")) {
				// "F" in Subnautica with no screen open: swap the two hands, as Minecraft's own
				// key does. Not with a tool token: those only work in the main hand.
				if (client.currentScreen == null && !client.player.isSpectator() && client.getNetworkHandler() != null
						&& ToolTokens.toolOf(client.player.getMainHandStack()) == null) {
					client.getNetworkHandler().sendPacket(new PlayerActionC2SPacket(PlayerActionC2SPacket.Action.SWAP_ITEM_WITH_OFFHAND, BlockPos.ORIGIN, Direction.DOWN));
				}
			} else if (event.startsWith("CLICK ") && client.currentScreen != null) {
				this.clickOnScreen(client, event.substring("CLICK ".length()));
			} else if (event.startsWith("WHEEL ")) {
				// The scroll wheel turned over a screen (the Creative inventory's lists scroll).
				if (client.currentScreen != null) {
					try {
						String[] turned = event.substring("WHEEL ".length()).trim().split(" ");
						double[] at = pointerOf(turned);
						client.currentScreen.mouseScrolled(at[0] * client.getWindow().getScaledWidth(), at[1] * client.getWindow().getScaledHeight(),
								0.0, Math.max(-3.0, Math.min(3.0, Double.parseDouble(turned[0]))));
					} catch (NumberFormatException e) {
						// A garbled line; ignore it.
					}
				}
			} else if (event.startsWith("KEY ") && client.currentScreen != null && !(client.currentScreen instanceof ChatScreen)) {
				// A key for a screen other than chat: Escape (256) closes it, and F (70) swaps the
				// item under the pointer with the off hand.
				try {
					client.currentScreen.keyPressed(Integer.parseInt(event.substring(4).trim()), 0, 0);
				} catch (NumberFormatException e) {
					// A garbled line; ignore it.
				}
			} else if (client.currentScreen instanceof ChatScreen chat) {
				try {
					int code = Integer.parseInt(event.substring(event.indexOf(' ') + 1).trim());

					if (event.startsWith("TYPE ")) {
						// A character, by its number (97 is "a").
						chat.charTyped((char) code, 0);
					} else if (event.startsWith("KEY ")) {
						// A key that isn't a character (Enter, Backspace, arrows), by the number
						// Minecraft's window system uses for it.
						chat.keyPressed(code, 0, 0);
					}
				} catch (NumberFormatException e) {
					// A garbled line; ignore it.
				}
			}
		}
	}

	/** "button down shift": a mouse button was pressed (1) or let go (0) over the open screen, with Shift held (1) or not. Button 0 is left, 1 is right. */
	private void clickOnScreen(MinecraftClient client, String text) {
		String[] parts = text.trim().split(" ");

		if (parts.length < 2) {
			return;
		}

		// Whether Shift is held in Subnautica. Minecraft asks its own keyboard about Shift when
		// a slot is clicked (shift-click moves the whole pile across), and its own keyboard
		// isn't being used, so the answer is supplied from here (see ScreenMixin).
		RemoteControls.shiftHeld = parts.length >= 3 && parts[2].equals("1");

		try {
			int button = Integer.parseInt(parts[0]);
			boolean down = parts[1].equals("1");

			// Screens work in "GUI pixels", which are bigger than real pixels by the GUI scale.
			// (Where the pointer was at the moment of the click, which came with it: by now it may have moved on.)
			double[] at = pointerOf(parts);
			double x = at[0] * client.getWindow().getScaledWidth();
			double y = at[1] * client.getWindow().getScaledHeight();

			if (down) {
				client.currentScreen.mouseClicked(x, y, button);

				// Held from here: moving the pointer now drags (see placePointer).
				heldButton = button;
				dragX = x;
				dragY = y;
			} else {
				client.currentScreen.mouseReleased(x, y, button);

				if (heldButton == button) {
					heldButton = -1;
				}
			}
		} catch (NumberFormatException e) {
			// A garbled line; ignore it.
		}
	}

	/**
	 * Called just before each frame is drawn. While a Minecraft screen is open under
	 * Subnautica's control, tells Minecraft the mouse pointer is where it is in Subnautica's
	 * window, so the right slot lights up and a picked-up item follows the pointer.
	 */
	public static void placePointer(MinecraftClient client) {
		if (client.currentScreen == null || !OverlayShare.isActive()) {
			heldButton = -1;
			return;
		}

		MouseAccessor mouse = (MouseAccessor) client.mouse;
		mouse.subnauticaLink$setX(RemoteControls.pointerX * client.getWindow().getWidth());
		mouse.subnauticaLink$setY(RemoteControls.pointerY * client.getWindow().getHeight());

		// With a button held, moving the pointer is a drag: that is how Minecraft spreads a
		// pile of items over the slots the pointer passes (one each with the right button, an
		// even share with the left), and how a scroll bar is pulled along.
		if (heldButton >= 0) {
			double x = RemoteControls.pointerX * client.getWindow().getScaledWidth();
			double y = RemoteControls.pointerY * client.getWindow().getScaledHeight();

			if (x != dragX || y != dragY) {
				client.currentScreen.mouseDragged(x, y, heldButton, x - dragX, y - dragY);
				dragX = x;
				dragY = y;
			}
		}
	}

	/**
	 * Where the pointer was when a click or a turn of the wheel was made, as fractions of the
	 * window: the two numbers after an "@" among the parts of the line (put there as the line
	 * arrived, see LinkConnection). Where the pointer is now, if there are none.
	 */
	private static double[] pointerOf(String[] parts) {
		for (int i = 0; i + 2 < parts.length; i++) {
			if (parts[i].equals("@")) {
				try {
					return new double[] { Double.parseDouble(parts[i + 1]), Double.parseDouble(parts[i + 2]) };
				} catch (NumberFormatException e) {
					break;
				}
			}
		}

		return new double[] { RemoteControls.pointerX, RemoteControls.pointerY };
	}

	/** The mouse button being held down over a screen (0 left, 1 right), or -1, and where the pointer was when a drag was last passed on. */
	private static int heldButton = -1;
	private static double dragX;
	private static double dragY;

	/**
	 * Tells Subnautica when a Minecraft screen opens or closes ("MCSCREEN 1" / "MCSCREEN 0"),
	 * so it knows when to free the mouse pointer and hold its own controls back. Minecraft
	 * can close a screen by itself (when you die, say), which is why Minecraft is the one to
	 * report it.
	 */
	private void reportScreen(MinecraftClient client, boolean linked) {
		// (Minecraft's own "loading terrain" screen, shown for a moment on arriving in the ocean void, isn't one to click on.)
		boolean open = linked && client.currentScreen != null && !(client.currentScreen instanceof DownloadingTerrainScreen);

		if (open != this.screenWasOpen) {
			this.screenWasOpen = open;

			// Shift held at the last click on one screen says nothing about the next.
			RemoteControls.shiftHeld = false;
			RemoteCollision.sendLine(open ? "MCSCREEN 1" : "MCSCREEN 0");
		}
	}

	private void setKeys(GameOptions keys, float forward, float strafe, boolean jump, boolean sneak, boolean sprint) {
		keys.forwardKey.setPressed(forward > PRESS_THRESHOLD);
		keys.backKey.setPressed(forward < -PRESS_THRESHOLD);
		keys.rightKey.setPressed(strafe > PRESS_THRESHOLD);
		keys.leftKey.setPressed(strafe < -PRESS_THRESHOLD);
		keys.jumpKey.setPressed(jump);
		keys.sneakKey.setPressed(sneak);
		keys.sprintKey.setPressed(sprint);
	}
}
