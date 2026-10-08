This is a note from Claude to me.

# Subnautica Link (multiplayer version)

This is the multiplayer version, being built in steps from the single-player one in
`subnauticaLink`. The Minecraft half here is version 2.x.

**Where it has got to (2.2.0):** each player's own Minecraft talks to their own Subnautica, and
the server keeps a separate link for every player (health, hunger, oxygen, death, tools,
attacks, where each player is put). Each player's game describes the world to its own
Subnautica from its own copy of it: blocks, cracks, lights, dropped items, lit TNT and
projectiles. The physics no longer depends on the hosting player: dropped items and TNT are
landed by asking the Subnautica of whichever linked player is nearest, and what a projectile
hits is found by the thrower's own game and reported to the server.

## What it does so far

- One shared health bar: any change to your health in one game, damage or healing, is matched
  in the other. Minecraft's natural regeneration heals you in Subnautica too.
- Hunger: Minecraft is in charge. Subnautica's food bar follows Minecraft's hunger bar and does
  not drain by itself. Eating in Subnautica adds to Minecraft's bar.
- No thirst: Subnautica's water bar is kept full, since Minecraft has no equivalent.
- Oxygen: Subnautica is in charge. Minecraft's bubble bar shows Subnautica's oxygen and nothing
  is sent back.
- Movement: Minecraft is in charge, as in the Skyrim project. The keys and mouse you use in
  Subnautica are sent to Minecraft, Minecraft's own movement moves the player, and the
  Subnautica character is carried to the matching spot. One metre is one block, and sea level
  is y = 0.
- While linked, Minecraft keeps you in the "ocean void", an empty, extra-tall dimension the
  mod adds (y = -1792 to 256). When Subnautica closes or returns to its menu, you are put back
  where you were.
- Collision: you bump into Subnautica's real scenery (seabed, wrecks, the lifepod, bases).
  Each tick, Minecraft asks Subnautica "the player's box is here and wants to move this far;
  how far does it get?" and Subnautica's own physics answers. Slopes are smooth and gaps are
  their true width. Creatures and loose items are ignored.
- Both games look out from the same point: Subnautica's camera is put where Minecraft's eyes are.
- The lifepod is held still and upright while linked (it normally bobs and drifts), so it
  doesn't move through placed blocks and its floor doesn't rock. It floats freely again when
  the link ends.
- If Subnautica moves you by itself (a hatch, a ladder, respawning), Minecraft restarts from
  the new spot.
- Water: everything below sea level counts as water in Minecraft, unless Subnautica says you
  are somewhere dry (a base, the lifepod, an alien building). Minecraft's own swimming applies:
  jump to rise, sneak to sink, sprint to sprint-swim. No fall damage in water; normal fall
  damage on land.
- Subnautica's own player movement is switched off while linked: its walking, jumping, gravity
  and swimming code is skipped, and its character is taken out of Unity's physics. Minecraft
  does all of it; Subnautica's character is only carried to where Minecraft's player is.
- Subnautica's own fall damage is turned away while linked: Minecraft decides falls. Other
  damage from Subnautica (bites, heat, and so on) still counts, and each is noted in
  Subnautica's log with its kind.
- Falls are measured as the highest point reached since you last stood on something (or were in
  the water) minus where you land, so small wobbles can't add up to a fall. Falls of 2 blocks
  or more, and getting in and out of the water, are noted in Minecraft's log ("Fall: ...").
- Swim speed follows Subnautica's (including fins and so on): sprint-swimming is a little
  faster than it (1.15 times), plain swimming is 0.7 of that, and walking along the seabed is
  slower again (0.65 of whichever applies).
- Keys while in Subnautica: Control sprints and Shift sneaks, as in Minecraft, whatever they
  are set to in Subnautica. Movement and jump use Subnautica's own bindings.
- Minecraft's hand and HUD (hearts, hunger, bubbles, hotbar, chat) are drawn over Subnautica.
  Minecraft draws them on a see-through picture and shares the pixels through a file both
  games keep open as memory (`subnautica_link_overlay.bin` in the temporary folder).
  Minecraft's window is resized to Subnautica's screen size while linked so it is sharp.
  Minecraft's crosshair is drawn over Subnautica's.
- Subnautica's own arms, tools, tool bar and health, food, water and oxygen bars are hidden,
  and any tool taken out is put straight back. Hatches, pickups and the PDA still work.
- Minecraft's hotbar: number keys 1-9 pick a slot and the scroll wheel moves along it. Left
  mouse button swings; right mouse button uses the held item (eating fills the shared hunger).
- While Subnautica's PDA is open, Minecraft's hand and HUD step aside and Subnautica's arms return.
- Attacking: left click hits the first living thing in reach with whatever Minecraft's player
  is holding. Minecraft works out the damage (held item, and how recovered the swing is);
  Subnautica applies it. One Minecraft damage point is five in Subnautica, the same proportion
  as health. A hit wears the weapon by one and plays Minecraft's attack sound.
- Creatures are solid: you bump into them instead of passing through. Small catchable fish and
  loose items are not.
- Blocks: right click with a block places it, against Subnautica's scenery or against another
  block; holding left click on a block breaks it. They are ordinary Minecraft blocks, saved
  with the Minecraft world. Subnautica is sent the list and builds each one in its own world,
  solid, so you and creatures bump into them.
- Blocks look like Minecraft blocks: Minecraft sends each kind of block's shape (the flat faces
  it is drawn from) and its block atlas (every block texture in one picture, written to
  `subnautica_link_atlas.bin` in the temporary folder). Slabs, stairs, torches and flowers have
  their real shapes. Blocks drawn by special code (chests, signs, beds) are plain coloured
  cubes, and animated textures (water, fire) don't move.
- Inventory: press E in Subnautica to open Minecraft's inventory. The mouse pointer is freed
  and its position and clicks go to Minecraft; E or Escape closes it. While it is open,
  Subnautica's own controls (including Tab for the PDA) do nothing; while the PDA is open, E
  does not open Minecraft's inventory. Dragging across slots is not passed on.
- Commands: press "/" in Subnautica to open Minecraft's chat box and type a command. Enter
  sends it, Escape cancels. Subnautica's own controls are switched off while you type. (The
  Minecraft world needs cheats allowed for commands to work.)
- Things dropped in the ocean void are shown in Subnautica as small turning copies, as in
  Minecraft. They fall (slowly in water) and land on the seabed, base floors and placed blocks,
  and are picked up by walking into them. Minecraft moves them, asking Subnautica what is in
  the way with the same question the player's movement uses. Items with no ordinary model
  (chests, shields) show as a small plain cube.
- Shift-click works in Minecraft's inventory (each `CLICK` line carries whether Shift is held).
- Q drops the item in Minecraft's hand (Control + Q the whole pile). While a tool token is held, Q
  is left to Subnautica.
- Projectiles (arrows, snowballs, tridents, the throwing knives, the grappling hook and anything
  else built on Minecraft's projectile classes) fly by Minecraft's own code, are shown in
  Subnautica as their item, and hit Subnautica's scenery and creatures. Each tick Minecraft asks
  Subnautica what lies along each one's path; on a hit the projectile is told "you hit a block"
  or "you hit a mob" and its own code does the rest (stick in, burst, anchor, deal damage).
  Damage to creatures is passed on five for one. They fly as in air everywhere, underwater too.
  The grappling hook anchors in scenery; hooking a creature just brings the hook back. Its rope
  is not drawn.
- See-through blocks: every face is cut along the pixels of its picture and the see-through
  ones are left out, so leaves have gaps, glass has a clear middle, and flowers and torches have
  their outline. (Part-see-through pixels, as in stained glass, are drawn solid.)
- Moving pictures (fire, sea lanterns, magma): once such a block is shown, Minecraft writes the
  atlas out again ten times a second and Subnautica reloads it, so they animate. Lava and water
  themselves are not shown.
- Breaking a block shows Minecraft's spreading cracks on it.
- Lit TNT is shown, falls and lands like a dropped item, and its explosion (any Minecraft
  explosion) hurts Subnautica's creatures in range by Minecraft's formula, five for one. See "Particles" for how the explosion looks.
- Particles. Where Minecraft would make particles, Subnautica is told and shows something:
  - breaking or digging a block throws small pieces of that block's own texture, drawn by the mod;
  - explosions show the burst of a Seamoth being destroyed, with its sound taken out (Minecraft
    plays its own);
  - arrows and knives striking scenery, things landing, and big falls kick up Subnautica's own
    surface effect for whatever was hit (sand, rock, metal sparks);
  - underwater, thrown things burst into bubbles and fast projectiles trail them; crossing the
    surface makes a splash; an ender pearl lands with the Warper's swirl;
  - in air, Minecraft smoke shows as Subnautica smoke; redstone dust shows as sparks anywhere.
  Each effect is only used in the medium it belongs in. The borrowed ones are listed in
  Subnautica's log ("Subnautica effects found: ...").
- Light: blocks that give off light in Minecraft light up Subnautica's world with ordinary point
  lights. The light level sets the reach (a level is about a metre), and the kind of block sets
  the look: flames are warm, short and flicker; sea lanterns, beacons and end rods are a cold
  white that reaches further; soul fire is blue; redstone is red. Only the nearest 24 are on.
- Subnautica's footstep sounds are off while linked.
- Subnautica's tools: each tool in Subnautica's inventory (knife, scanner, habitat builder,
  Seaglide and so on) has a matching token item in Minecraft's inventory, given and taken away
  automatically. Holding a token takes the real tool out in Subnautica. It stays invisible
  (Minecraft's hand shows the token) but works as normal, and its effects still show. While
  one is held, the mouse buttons work the tool instead of Minecraft's attack and use.
- Planned next: real icons for the tool tokens, and handing control to Subnautica while in a vehicle.
- Minecraft respawns you automatically while linked (the doImmediateRespawn game rule is set).
- Minecraft no longer pauses when you click away from it (the F3 + P setting is turned off).
- Die in one game: you die in the other.
- When the two games link, Subnautica takes on Minecraft's current health and hunger.
- Each game shows a message when the link connects ("Subnautica linked" in Minecraft chat,
  "Minecraft linked" in Subnautica).

Attacks and blocks are not shared yet.

### Players and views (multiplayer version)

- **F5** steps through Minecraft's three views: through the eyes, from behind, from the front.
  Subnautica's camera is moved back (stopping short of walls) only for the instant each picture is drawn.
- **Minecraft characters** are shown in place of Subnautica's divers: yours in the outside views, and
  every other linked player's all the time. Minecraft is asked to draw each player as it normally
  would and the drawing is caught and sent to Subnautica, so the pose, skin, armour and held items
  are whatever Minecraft would show. See `ClientAvatars.java`.
- **Nitrox's divers** for other players are hidden while a Minecraft character stands in the same place,
  and players no longer bump into each other.
- **For friends installing BepInEx**: the plugin now protects itself from Subnautica clearing it away, so
  nobody needs to change `HideManagerGameObject` in `BepInEx.cfg` any more.

### Getting Minecraft materials (multiplayer version)

Kept small on purpose, so Subnautica's own progression stays the main one.

- **Digging**: hold the attack button on Subnautica's terrain (sea floor, rock; not the lifepod, wrecks
  or bases) for half a second and one thing drops: cobblestone 50%, coal 30%, dirt 10%, flint 10%.
  The chances are at the top of `Gathering.java`.
- **The Fabricator**: with Subnautica's Fabricator open, a panel at the right of the screen turns
  1 Creepvine Seed Cluster into 4 oak logs, 1 Cave Sulfur into 3 gunpowder, 1 Titanium into 1 iron
  ingot, 1 Ruby into 1 diamond, 1 Creepvine Sample into 8 sugar cane, and 1 Quartz into 3 lapis lazuli. Click a row or press its number (1 to 6); hold Shift to turn all you have. The list is in `Gathering.java`
  and, in the same order, in the plugin (`TradeTakes`).

- **Creature drops**: a creature that dies to damage you dealt drops Minecraft items where it died. Stalker-sized
  creatures drop 1 to 3 raw beef; the Gasopod drops beef and exactly 4 leather; the Reaper Leviathan drops an
  enchanting table. Looting adds to the beef. The one list is in `CreatureDrops.java`, and it is also shown down
  the right of the screen while Minecraft's inventory is open.

### Fighting Subnautica's creatures

- Sharpness adds its usual damage; Impaling counts against everything in Subnautica; Fire Aspect burns a creature
  for four seconds a level; Knockback (and a sprinting hit) shoves it, less the heavier it is. Smite and Bane of
  Arthropods do nothing here.
- A hit that lands wears the held item: one point for a weapon, two for a tool (nothing wears in Creative).
- Lit TNT flashes white in step with its fuse and swells just before it goes off.
- Landing from an elytra glide no longer counts the whole height as a fall; only a steep dive does, as in Minecraft.
- A projectile flying back to its thrower (roped knife, grappling hook, Loyalty trident) is collected when it gets near.

### Movement feel

- Minecraft's own blocks are now checked by Minecraft itself, exactly as in any world; Subnautica is only asked
  about Subnautica's scenery. Walking across a floor of blocks no longer catches on the joins.
- Between Minecraft's 20 position reports a second, Subnautica's camera travels in a straight line at a steady
  speed, so fast movement (elytra, sprint-swimming) is smooth.
- Dropped items' moves are sent to every game as they happen, not once a second, so they fall smoothly.

### Minecraft's mobs: the Drowned

- A Drowned appears for about every 200 blocks a player travels underwater: 23 to 31 blocks away, behind rather
  than in front, at most two near a player. They are removed when everyone is more than 96 blocks away.
- It is a real Minecraft Drowned (its damage, sounds, death and drops are Minecraft's), but steered by `VoidMobs.java`:
  it swims straight at the nearest Survival player and strikes in reach. Subnautica's scenery stops it, the same way
  it stops dropped items. It is drawn in Subnautica the way players are.
- Experience orbs no longer fall out of the world; they hang where the mob died.
- None appear on Peaceful. The numbers are at the top of `VoidMobs.java`.

### Creepers

- Creepers appear on Subnautica's dry land (its islands). Every few seconds Minecraft has Subnautica look straight
  down at a spot 20 to 30 blocks from a player; open terrain above the sea with nothing built on it, and no
  Minecraft block there, gets a creeper. At most two near a player.
- They walk at the nearest Survival player, start their fuse within three blocks (flashing and swelling as in
  Minecraft) and explode with Minecraft's own explosion, shown in Subnautica like TNT's. Killed first, one drops
  exactly four gunpowder.

### Grappling hook and creatures

- A hook that hits a creature up to about a Gasopod's size drags it to you (`HookDrag.java`). One that hits
  something bigger (leviathans, Reefback, Sea Treader, Ampeel, Crabsquid) holds fast and pulls you to it.

### Elytra

- Gliding stops on entering the water.

### Tool tokens

- There are exactly as many tokens of each kind as there are tools of that kind in Subnautica's inventory; this is
  checked once a second, and a token dropped on the ground is removed.
- Each token shows two of Minecraft's item pictures laid one over the other (a sword and feather for the knife, a book
  behind iron bars for the scanner...). The list is `PICTURES` in `ToolTokens.java`; the models are `tool_N.json`.

### Using Subnautica's tools

- A tool used in one go swings Minecraft's hand; one held on its work (laser cutter, repair tool, scanner, habitat
  builder, the cannons, stasis rifle, fire extinguisher) brings the arm in toward the middle of the screen while held.
- The air bladder lifts you, faster and faster up to a limit, until you leave the water or bump into something;
  using it again stops it.
- With the Seaglide in hand you don't sink in the water; sneak still goes down.

### Light

- Blocks that give light are drawn glowing, and all Minecraft lights are 1.5 times as bright as Minecraft's numbers say.
- What you hold gives light: a torch or any other glowing block as it would when placed, the Seaglide like a sea
  lantern, the flashlight the same but further.

### Subnautica's console and prompts

- F9 opens and closes Subnautica's command console, but only for a player the Minecraft world allows cheats. While it
  is open, nothing typed or clicked reaches Minecraft.
- While Subnautica's battery chooser is up (R with a tool in hand), the scroll wheel and number keys don't move
  Minecraft's hotbar.
- Subnautica's line saying which buttons work the held tool is moved up clear of Minecraft's hotbar.
- The Seaglide's and flashlight's light, and the air bladder's lift, follow what the real tool is doing in Subnautica
  (`TOOLSTATE light lift`).

### Vehicles (the Seamoth)

In a vehicle, Subnautica is in charge of where the player is, and Minecraft follows:

- Subnautica says where the seat is ("RIDE x y z yaw", twenty times a second). Minecraft sits
  its player on an invisible mount kept at that spot (`Riding`), so the Minecraft character is
  drawn sitting, bumps into nothing, takes no fall damage, and breathes whenever Subnautica
  says the vehicle has air.
- In Subnautica the sitting character is put exactly on the seat every frame: your own where
  your eyes are, another player's where Nitrox has their diver sitting. So it never trails
  behind a fast vehicle.
- Getting out, Subnautica puts the player beside the vehicle and says where ("SPAWN");
  Minecraft's movement takes over again from there.

- While piloting, the mount's hearts show the vehicle's hull (ten hearts, a tenth each) and
  the experience bar shows its power, with "Charge" where the level is written. A blow from
  one of Minecraft's mobs (a Drowned, a creeper) lands on the hull, not the player. The cabin
  counts as dry, so Minecraft doesn't drown its player; with no power, Subnautica's own oxygen
  runs down as usual.

Controls while piloting:

| Key | Does |
| --- | --- |
| Movement keys, left mouse button | The vehicle's (steering, the chosen module) |
| Right mouse button | The vehicle's lights; with food or drink in Minecraft's hand, eats it |
| **Alt** (a tap) | Get out. The prompt on screen says so too |
| E | Minecraft's inventory, as always |
| Scroll wheel | Minecraft's hotbar |
| Number keys | The vehicle's modules |

Minecraft attacks, digging and placing are off until you get out. The PRAWN suit works the
same way; while its jump jets are draining or refilling, the bar turns blue and reads "Boost".
F5 looks at the vehicle from outside, from further back than on foot; the camera passes
through the vehicle itself but stops at anything else solid. The Cyclops is not handled yet.

### The Cyclops

- **At the helm** it is a vehicle like the others: Subnautica holds the player at the wheel,
  Alt lets go of it, the hearts show the hull and the bar shows the power cells. F5 from behind
  stays inside the sub; F5 from the front looks in through the glass from outside.
- **Walking about aboard**, Minecraft still moves the player, and is told how far the sub has
  carried them ("CARRY n dx dy dz", answered "ACK n") so they move with it. Other players
  aboard a sub under way are drawn where Nitrox has their diver, so they don't trail behind.
- Punches don't damage vehicles, the Cyclops or bases.
- Minecraft's mobs are stopped by the hull like any scenery, and can't strike, light a fuse
  or blast through it while the player is aboard ("ABOARD 1").
- In the Seamoth and PRAWN suit, Subnautica's own hull and power read-out is hidden (the
  hearts and the bar show them), and the module bar is shown on the left of the screen.

### Chests, beds and reach

- Blocks Minecraft draws by special code (chests, beds, signs, banners, shulker boxes, heads)
  are caught and sent the way players and mobs are, so they look as they do in Minecraft, lids
  and all.
- Beds work in the ocean void. A linked player can sleep while it is night in their Subnautica
  ("NIGHT 1"); once Minecraft's usual sleep is done ("SLEPT"), Subnautica skips ahead exactly
  as its own beds do. A bed there doesn't change where the player respawns.
- While linked, the player reaches 1.25 times as far, for blocks and mobs alike.

### More from the sea

- **Drops:** Stalkers leave 16 arrows, Bone Sharks 4 bones, Rabbit Rays 3 string, and a Reaper
  Leviathan an elytra 95 times in 100 (as well as its enchanting table).
- **Fabricator:** a Creepvine Seed Cluster can also become 1 oak sapling, and a Minecraft iron
  ingot can be handed over for 1 Titanium ("BUY which howMany", answered "GIVE name howMany").
- **Digging** gives different things in each of Subnautica's regions ("BIOME name"; the lists
  are in `Gathering`): clay and kelp in the Kelp Forest, sand in the Dunes, netherrack and
  obsidian in the lava zones, and so on.
- **Fishing rods and boats** work on Subnautica's sea: the bobber and the boat are told there
  is water below sea level. Use a boat while looking down at the surface to launch it.
- **Habitats can be built on Minecraft's blocks**, which count as terrain to Subnautica.
- **Guardians** take the place of the Drowned 400 metres down and deeper: a quarter as often,
  and then one time in four.
- **Ghasts:** every five minutes there is a one in four chance of one appearing in the sea
  near each player, wherever Subnautica says there is room ("SPACE" / "SPACED").

### Things that move (2.16)

- **Blocks drawn by code of their own** (chests, signs, the enchanting table's book, bells,
  banners, a block being pushed by a piston) are caught every tick near the player, so they
  move as smoothly as in Minecraft; one is only sent again when it has changed. The writing
  on signs is sent too.
- **Everything that is neither mob nor block** is drawn the same way: item frames (with what
  is in them), paintings, armour stands, minecarts, boats, falling sand.
- **Poured water and lava** are shown, each block of it as a box as deep as the liquid.
- **Habitat legs** are measured again a moment after Minecraft's blocks arrive or change, so
  a habitat standing on blocks keeps its legs on them after a save is loaded.
- **Music discs:** any creature that isn't one of the fish you can catch and eat has a 4 in
  100 chance of leaving a random disc. Gasopods also leave 2 to 5 slime balls.
- **Endermen** come to the islands as creepers do, 1.3 times as far off and 0.6 times as
  often. One stands and watches until it is looked in the face or hit; then it comes for
  whoever did it, and teleports to them if they get away. It teleports now and then anyway,
  when hurt, and out of the sea (which hurts it). Each teleport asks Subnautica for dry, open
  ground first ("PROBE id x z 2.9"), and Subnautica shows the Warper's swirl at both ends
  ("FX warpout" / "FX warpin"). Killed, it leaves Minecraft's usual ender pearl.
- **Ender pearls** land on Subnautica's terrain like any other projectile; the thrower is put
  where they fit (on a floor, half a block out from a wall), and the fall counted so far is
  forgotten.

### 2.17

- **Standing up needs room.** Before the player's box grows (from a swim or crouch to
  standing) the space it would add is checked, in Minecraft's blocks and by asking Subnautica
  to sweep the box upward. The player's game tells the server how tall they are ("POSEH h").
- **Eating and drinking** lift the hand to the mouth when seen from outside.
- **Pistons, lids and the like** move at an even pace between ticks (AV flag 64), and a push
  is caught from where it starts. A **beacon's beam** is carried on upward (AV flag 128).
- **Poured water and lava** are drawn as Minecraft draws them, shape by shape. Water can't be
  poured out under the sea, and water resting on the sea's surface spreads as on ground.
- **Falling sand** is moved like a dropped item and becomes a block again where it lands.
- **Drowned with tridents** throw them. **Ghasts** only come in the lava zones.
- **Drops:** Lava Lizards and Crimson Rays leave gold ingots, sometimes netherite scrap, and
  rarely the netherite upgrade template; Lava Lizards and Lava Larvae leave blaze rods; a Sea
  Dragon leaves a beacon, 20 iron blocks and blaze rods.

### 2.17.1

- **Lag after pouring water, fixed.** Every shape of flowing water was marked as "picture
  moves", which has Subnautica cut the shape out again ten times a second, and the shapes
  were never forgotten. Water and lava shapes are no longer marked that way, a shape nothing
  is using is dropped ("MODELGONE id"), and a block of water whose shape hasn't changed isn't
  sent again.
- **Water is see-through** ("%" in front of its model): drawn with a copy of one of
  Subnautica's own glass materials, once one is loaded to copy.
- **Buckets fill from the sea** (under it, or looking down at the surface within reach), and
  in the lava zones **from Subnautica's lava** ("LAVACHECK", answered "LAVASCOOP 1/0").
  Nothing is taken from either.

### 2.18

- **Blocks and the Cyclops** pass through each other as far as physics goes, so a block put
  down aboard no longer throws the sub about. (Blocks stay where they are in the world.)
- **Hunger is Minecraft's alone:** while linked, Subnautica's own starving and well-fed
  healing are switched off (its `freezeStats`), as its thirst and hunger drain already were.
- **Water** is drawn with Unity's plain see-through shader, dimmed to the time of day.
- **Held lights are shared:** each game tells the server what its player's held thing gives
  ("MYLIGHT ..."), the server passes it round ("@LIGHT player ..."), and each Subnautica keeps
  a light on the other players near it ("PLIGHT player ...").
- **Screens:** holding a mouse button and moving drags (right-drag puts one item in each
  slot), and the scroll wheel scrolls ("WHEEL amount").

### 2.18.1 (the audit)

A read-through of the whole mod, both halves, with these put right:

- **Subnautica plays normally with Minecraft closed.** Hunger and thirst were being held
  still whenever the plugin was installed, linked or not; now only while linked. And a link
  that is cut off (Minecraft crashing or being shut) is noticed like one that is closed
  properly, so the player is no longer left frozen.
- **Falls:** a ladder, vine, Slow Falling, Levitation, flying, or poured water or lava break
  a fall as they do in Minecraft. (Climbing down a tall ladder used to count as falling off it.)
- **Leaving the game while linked** puts the player back where they were before the ocean
  void first, so that is where they are when they come back.
- **Quitting to Subnautica's menu and loading again** has Minecraft describe its blocks
  afresh ("RESYNC"); so does dying, for the chunks around where it happened.
- **Clicks:** a click lands where the pointer was when it was made; a quick tap of either
  button is no longer missed; the button that opened a chest isn't taken as a click inside it.
- Damage taken in Subnautica is no longer lost when another line arrives in the same frame.
- A vehicle being piloted when the link starts is picked up straight away.
- Creepers and endermen no longer appear on top of the player's own builds.
- Kills are kept to a believable pace (a few a second; one giant per half minute), and
  Fabricator trades only count in the ocean void.
- Smaller things: shapes and pictures are freed on reconnect, the atlas stops being sent
  once nothing with a moving picture is shown, block entities are sent spread over ticks.

Known and left as they are:

- **A dedicated server needs `allow-flight=true`** in `server.properties`. The server sees a
  linked player standing on nothing, and would otherwise kick them for flying. (Worlds
  hosted from a player's own game, as with Essential, are not affected.)
- The server takes each player's game at its word for kills, trades, digging and where
  things land. That is fine among friends and not safe on a public server.
- Text can't be typed into anvils, signs, books or the Creative search box from Subnautica;
  only into chat.
- Every Minecraft block is one object in Subnautica. Builds of a few thousand blocks in view
  will cost frame rate.

### 2.18.2

- **No "experimental settings" warning.** Minecraft stopped at that screen whenever a world
  was opened or made with the mod installed, because the mod adds a dimension. Worlds now
  always count as "stable" (`LevelPropertiesMixin`, `IntegratedServerLoaderMixin`), so a
  launcher can open a world with nobody there to press the button. This is so for every world
  opened with the mod installed. The Subnautica half is unchanged apart from its version.

### 2.19.0

- **Both games' menus.** With Subnautica's own menu up (Escape), a small panel at the left of
  the screen has two buttons, "Subnautica" and "Minecraft"; the one showing is greyed out.
  "Minecraft" closes Subnautica's menu and has Minecraft open its game menu ("MENU 1"), which
  is drawn and clicked on like the inventory is. "Subnautica" has Minecraft close whatever it
  has open ("MENU 0") and opens Subnautica's menu again once it has. Escape closes either.
  See `DrawMenuSwap` in the plugin.
- **Buttons other mods add to Minecraft's menus work** (Essential's, for hosting and inviting).
  A click on a menu now goes in through Minecraft's own mouse handling (`Mouse.onMouseButton`)
  instead of straight to the screen, because that is where other mods listen. Screens of item
  slots (the inventory, chests) are clicked as before.
- **No Minecraft screen pauses the game while linked** (`ScreenMixin`, `MinecraftClientMixin`).
  If Subnautica's menu had the game stopped, it stays stopped under Minecraft's menu.

### 2.19.1

- **Clicking beside Subnautica's menu no longer closes it** while linked (`KeepGameMenu`).
  Subnautica closes its menu on a press on empty screen, and this mod's panel beside the menu
  counted as that. The menu's own buttons and Escape close it as before.
- **Held still while Subnautica is stopped.** Minecraft never stops while linked, so with
  Subnautica's menu up (playing alone) the player could still walk about a frozen world.
  Subnautica now says when it is stopped ("HOLD 1" / "HOLD 0"); Minecraft's player doesn't
  move meanwhile, and with nobody else about its mobs stop too. With Subnautica's menu up
  the movement keys are not passed on either way.

### Inventory keys

- Shift-click moves a whole pile across the inventory.
- F swaps the two hands, or with the inventory open, swaps the item under the pointer with the off hand.
  A Subnautica tool token never stays in the off hand: it goes straight back into the inventory.

## The two halves

| Folder | Game | Language | Built with | Ends up in |
| --- | --- | --- | --- | --- |
| `minecraft` | Minecraft | Java | `build.bat` (Gradle, JDK 21) | `minecraft\build\libs\subnautica_link-2.2.0.jar`, which you add in Prism |
| `subnautica` | Subnautica | C# | `build.bat` (.NET SDK 8) | copied automatically into `Subnautica\BepInEx\plugins\MinecraftLink` |

## How they talk

Minecraft waits on port 25599 on this PC only. Subnautica connects to it and retries every
two seconds until it succeeds. They send each other lines of text:

- `HEALTH 0.75` - the player is now at three quarters of full health
- `FOOD 0.8` - Minecraft's hunger bar is now at this level
- `ATE 0.13` - the player ate in Subnautica, worth this fraction of a full bar
- `OXYGEN 0.4` - Subnautica's oxygen supply is this full
- `SPAWN x y z` - where the Subnautica player is; Minecraft starts from the matching spot
- `INPUT forward strafe jump sneak sprint yaw pitch attack use` - the controls held in Subnautica
- `MCPOS x y z eye` - where Minecraft's movement has put the player, and its eye height
- `DRY 1` - Subnautica's player is somewhere with air around them (0 when not)
- `SWIMSPEED 5.0` - how fast the player swims in Subnautica, in metres per second
- `ATTACK 30 3` - Minecraft's player swung: damage (in Subnautica points) and reach in metres
- `HIT` - that attack landed on something
- `INVENTORY` - open Minecraft's inventory; `MCSCREEN 1` / `MCSCREEN 0` - a Minecraft screen opened / closed
- `POINTER 0.5 0.5` - where the mouse pointer is in Subnautica's window; `CLICK 0 1` - a mouse button went down (or `0` up)
- `CHAT /` - open Minecraft's chat box with this text in it
- `TYPE 97` - a character was typed (by its number); `KEY 257` - a key such as Enter was pressed
- `AIM x y z nx ny nz` - the point on Subnautica's scenery the camera is looking at (`AIM -` for none)
- `MODEL id quads` - what one kind of block looks like
- `BLOCK x y z id A0A0A0 box` - a block is here: which model, a fallback colour, and its solid box
  (`BLOCK x y z air` when it has gone)
- `ATLAS path` - where Minecraft wrote its block atlas
- `TOOLS Knife:Survival Knife|Scanner:Scanner` - the tools in Subnautica's inventory
- `EQUIP Knife` - the token for this tool is in Minecraft's hand (`EQUIP -` for none)
- `DROP 0` - Q was pressed: drop the held item (`DROP 1` with Control: the whole pile)
- `ITEMMODEL kind scale colour quads` - what one kind of dropped item looks like (quads as for `MODEL`)
- `ITEM id kind x y z` - a dropped item is here; `ITEMGONE id` - it was picked up or despawned
- `SWEEP`/`SWEPT` with an id of 1000000 or more are about a dropped item, not the player
- `RAYS id n` + six numbers each (start, movement) - what would these projectiles hit?
  `RAYHIT id` + five numbers each (how far along, 0 to 1 or -1; surface direction; 1 for a creature)
- `SHOT id kind style x y z vx vy vz` - a projectile is here, moving this much a tick
  (style 1 points along its flight); removed with `ITEMGONE id`
- `HURT x y z damage` - a projectile hit the creature at this point
- `MODEL id ~quads` - a "~" in front of the quads means the picture moves; the atlas file's
  fourth heading number goes up each time Minecraft rewrites it
- `CRACKS` + forty numbers - where the ten block-breaking pictures are on the atlas
- `CRACK x y z stage` - cracks on that block, stage 0 to 9, or -1 for none
- `BLAST x y z power` - an explosion went off
- `SHOT` style 2 is lit TNT: upright and full size
- `DEBRIS x y z id face` - bits of that kind of block; face -1 for the burst when it breaks,
  0 to 5 for one bit off the face being dug
- `FX name x y z [nx ny nz]` - an effect at a spot: impact, land, poof, warp, smoke, spark
- `LIGHT id range strength RRGGBB flicker` - blocks of this kind give off light
- `CRACK x y z stage who` - multiplayer version: the last number says which player is breaking
- `CHUNKGONE cx cz` - forget the blocks in that 16-by-16 column (the player moved away)
- Multiplayer version only, between a player's game and the server (never Subnautica):
  `LINKED` / `UNLINKED` (that player's Subnautica connected or went away), `ALIVE` (still
  sending controls, twice a second), `SWING` (the attack button was pressed), `PHIT id x y z nx ny
  nz creature` (this player's game says that projectile is about to hit Subnautica's scenery or
  a creature there), `SWEPT ...` (the answer to an item `SWEEP` the server asked), and notes from the
  server to a game, starting "@": `@STUCK id x y z` / `@UNSTUCK id` (a projectile stuck in
  Subnautica's scenery, or came loose)
- `VIEW n` - F5 was pressed in Subnautica: 0 through the eyes, 1 from behind, 2 from the front
- `SKIN n w h pixels` - picture number n (a skin, armour) for drawing players; picture 0 is the block atlas
- `AVSHAPE key faces` - the lasting part of a player's look: each face's picture, tint and place on it
- `AV id key x y z flags corners` - where a player is and how they are posed just now (flags: 1 hurt, 2 yourself)
- `AVGONE id` / `AVCLEAR` - that player is no longer there to draw / forget the looks sent so far
- `AIM x y z nx ny nz terrain` - now ends with 1 if the camera is pointed at terrain
- `DIG x y z nx ny nz` - (game to server) the player dug at terrain there for half a second
- `TRADE which howMany` - Subnautica took that many of a material at the Fabricator
- `KILLED name x y z` - a creature of that kind died there to damage this player dealt
- `ATTACK damage reach burn knock` - now also carries seconds of burning and levels of knockback
- `SHOT ...` for lit TNT ends with the ticks left on its fuse
- `SLOT 3` - a number key was pressed (slots count from 0)
- `SCROLL 1` - the scroll wheel moved one click (-1 for the other way)
- `OVERLAY path` - where Minecraft's shared HUD picture is
- `SCREEN w h` - the size of Subnautica's screen
- `SWEEP id cx cy cz hx hy hz dx dy dz step ground` - Minecraft asks how far a box can move
- `SWEPT id rx ry rz ground walls ...` - Subnautica's answer
- `DEATH` - the player died

## Running it

1. Start Minecraft and open a world in Survival. Minecraft only listens while a world is open.
2. Start Subnautica and load a save.
3. Wait for the "linked" message in both games.

The order doesn't matter; whichever starts second connects within a couple of seconds.

## Logs

- Minecraft: the Prism instance's `logs\latest.log` (search for "Subnautica").
- Subnautica: `C:\XboxGames\Subnautica\Content\BepInEx\LogOutput.log` (search for "Minecraft Link").
