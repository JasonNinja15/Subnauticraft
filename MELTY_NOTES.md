# Publishing Subnauticraft on Melty: what is already settled

Notes for whichever Claude session does the publishing, so it doesn't ask Jason again. Written 2026-10-07. No token or other secret belongs in this file.

## Already established

- **The mashup:** Subnauticraft. Play through Subnautica as your Minecraft character, with both games running at once. Full feature list: `DEV_NOTES.md`. Player-facing summary: `README.md`.
- **Games players need:** Minecraft Java Edition 1.21.1 (Fabric) and Subnautica (the first game). Made and tested on the Microsoft Store / Xbox app copy of Subnautica; Steam is untested.
- **Loaders and helpers:** Fabric Loader and Fabric API for Minecraft; BepInEx 5 (x64) for Subnautica. None of these are in the project; check which ones Melty installs for each game.
- **The two files that make up a release:**
  - `minecraft\build\libs\subnautica_link-<version>.jar` goes in the Minecraft instance's `mods` folder.
  - `MinecraftLink.dll` goes in `<Subnautica>\BepInEx\plugins\MinecraftLink\`. The build copies it there; take it from that folder.
- **Version:** 2.19.1 is the current source. Jason says he has built and tested. Confirm which version his built files are before uploading (the jar's file name carries it; both halves must be the same version).
- **How it runs:** start Minecraft into a Survival world and Subnautica into a save, in either order; the two find each other on this PC by themselves (port 25599, local only). The player then plays in Subnautica's window with Minecraft left running behind it.
- **Update 2026-10-07: listed as multiplayer, joined by hand.** Melty won't take a multiplayer-capable project listed as single player, so Jason agreed to `recipe.multiplayer` with `maxPlayers: 8` (he recommends 4-8; tested with 2) and no `connect` until Nitrox can be started in one go. The note below is superseded.
- **(Superseded) Listing as single player for now.** Jason's decision. It does work in multiplayer (Nitrox for Subnautica, Essential or a server for Minecraft), but there is no way yet to open Nitrox and start the game in one go, so multiplayer is to be added to the listing later. Do not set `recipe.multiplayer` yet, and say in the description that multiplayer exists but is set up by hand for now.
- **One click:** Jason says "Skycraft", already on Melty, works the same way (Minecraft plus a second game with a mod in each). Look at how its listing and recipe launch the two games and follow that.
- **Screenshot and videos:** Jason already has a real screenshot and several videos of it running. Ask him where they are; don't capture new ones.
- **No game content is in the project.** It reads everything from the player's own copies.

## Still to settle with Jason

- Content licence. The mod's metadata says "All Rights Reserved", which was a placeholder, not a decision.
- Whether others may remix it.
- Credits. So far: made by Jason, code written by Claude.
- Whether the GitHub repository is published yet, and its link.
- Title, tagline and description: suggestions were given in chat; he hasn't chosen.

## Things a player should be told

- Typing only works in Minecraft's chat; signs, anvils and search boxes can't be typed into from Subnautica.
- Very large builds lower Subnautica's frame rate.
- A dedicated Minecraft server needs `allow-flight=true`.
- **Listing text chosen 2026-10-07:** title Subnauticraft; tagline "Play through Subnautica as your Minecraft character."; description in `listing-description.md`.
- **Licence 2026-10-07:** MIT, remixes allowed (LICENSE added; fabric.mod.json updated). Release 2.19.2 adds auto-open world for the bundled Prism instance.
- **Packaging 2026-10-07:** `tools/package.ps1` writes `dist/Subnauticraft-Subnautica-<v>.zip` (→ `{game}/BepInEx`) and `dist/Subnauticraft-Minecraft-<v>.zip` (portable Prism 11.1.1 + instance "Subnauticraft" with Fabric Loader 0.16.14, Fabric API 0.116.17, the jar → `{localappdata}/Subnauticraft`). Melty starts Prism with `--launch Subnauticraft` alongside Subnautica. The jar opens/creates the "Subnauticraft" world itself when started with `-Dsubnautica_link.autoWorld=Subnauticraft` (tested: fresh folder creates and joins; second start reopens). validate_recipe and one_click_check: one click yes, multiplayer up to 8 (join by hand). Not yet uploaded.
- **Melty draft:** modId a5267b80-c715-4972-b457-e7691c839d58 (slug subnauticraft-2), linked to JasonNinja15/Subnauticraft, MIT, remix on. Credits: JasonNinja15, code by Claude (in description).
- **Release 2.19.2 submitted** as a draft (uploads d6b1a779… Subnautica zip, 04c1fd7d… Minecraft zip); one click: yes. Still to do: gameplay screenshot/video upload, Jason's test via Play in the Melty app, publish, melty.json commit.
- **Media:** trailer uploaded (H.264 re-encode of Videos/Subnauticraft Trailer.mp4, media 8c29793a…); Jason added an image himself in Studio.
- **Blocked 2026-10-07:** publish requested, but the Melty app doesn't detect Xbox-app Subnautica (package UnknownWorldsEntertainmen.GAMEPREVIEWSubnautica), so no Play-to-publish button. Report for Melty in `melty-xbox-report.md` (to send via discord.com/invite/meltygg). Alternative: Steam copy.
- **LIVE 2026-10-07:** 2.19.2 published at melty.gg/m/subnauticraft-2 after Jason's Play run on a Steam copy of Subnautica (Steam version works: plugin loaded, auto world created, "Subnautica linked"). install_outcomes: 1 installed, 1 launched, 0 failed. Xbox-app Subnautica still undetected by Melty (see melty-xbox-report.md); description says Steam needed for now. Not yet done: git commit/push + melty.json.
