**Melty doesn't detect the Xbox app / PC Game Pass copy of Subnautica**

Hi! I'm publishing a Subnautica + Minecraft mashup (Subnauticraft, melty.gg/m/subnauticraft-2, still a draft). My Subnautica is the Xbox app copy, and the Melty app doesn't see it. The mashup page shows "Get Subnautica 2/2" with a Steam logo instead of "Play to publish", so I can't do the test run that publishes it.

**My setup**
- Windows 11, Melty app installed in `%LOCALAPPDATA%\Programs\melty`
- Subnautica (the first game) installed through the Xbox app
- Game files: `C:\XboxGames\Subnautica\Content\Subnautica.exe`
- Minecraft: Java Edition is detected fine

**What Windows reports** (`Get-AppxPackage`)
- Name: `UnknownWorldsEntertainmen.GAMEPREVIEWSubnautica`
- PackageFamilyName: `UnknownWorldsEntertainmen.GAMEPREVIEWSubnautica_bh1f6rvenfkm2`
- Version: 1.22.17495.0
- InstallLocation: `C:\Program Files\WindowsApps\UnknownWorldsEntertainmen.GAMEPREVIEWSubnautica_1.22.17495.0_x64__bh1f6rvenfkm2`

**What seems to go wrong**
1. Because the InstallLocation is under `WindowsApps` and not `\XboxGames\`, the app's Xbox scan doesn't treat the package as a game. No Xbox games at all appear in `installed-games.json`.
2. The catalog's Subnautica entry seems to have only a Steam ID, so even when found, the package wouldn't match the `subnautica` game.
3. For installs, `{game}` needs to be the moddable folder `C:\XboxGames\Subnautica\Content` (BepInEx works there; I've been modding it this way), not the WindowsApps path.

**Request**
Please add the Xbox app package `UnknownWorldsEntertainmen.GAMEPREVIEWSubnautica` to the Subnautica catalog entry, with `{game}` resolving to the `XboxGames\...\Content` folder. Or add a way to choose a game's folder by hand. Then Xbox / Game Pass players (and I) can press Play.

Thanks! JasonNinja15
