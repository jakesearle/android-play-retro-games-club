# Emulator reference

Which consoles and emulators RetroAchievements supports, which Android emulators
exist (counted as the apps in the Obtainium Emulation Pack), and how the two
overlap. The app finds club picks by RetroAchievements hash and launches them
through the Cocoon catalogue, so this shows what it can match and launch.

**Sources, as of November 16, 2026:**

- **RetroAchievements:** [Emulator Support](https://docs.retroachievements.org/general/emulator-support-and-issues.html)
  ([source](https://github.com/RetroAchievements/docs/blob/main/docs/general/emulator-support-and-issues.md)).
  It lists hardcore-compliant emulators only, and doesn't say which run on Android.
- **Android emulators:** [Obtainium Emulation Pack](https://github.com/RJNY/Obtainium-Emulation-Pack)
  (`src/applications.json`, last pushed October 20, 2026, Unlicense). We treat
  this as the list of emulators Android supports, though more exist.
- **What the app can launch:** the [Cocoon catalogue](https://github.com/KansoMirai/CocoonFE)
  (`android.json` version 3).

The tables were generated from those files, so regenerate them rather than
editing by hand when the sources change.

## Summary

- **54 consoles** have RetroAchievements emulator support.
- **49 of them** can be played on Android with an
  RA-supported emulator: a RetroArch core the catalogue launches, or a standalone app in the pack.
- **No RA-supported Android option (in the pack):** Apple II, Arcadia 2001, Atari Jaguar CD, Elektor TV Games Computer, Interton VC 4000.
- **The app hash-matches cartridge consoles, plus PlayStation and PlayStation 2
  discs** (CHD, CUE/BIN, ISO and M3U; PS2 DVD images stored as CHD aren't read yet).
  Other disc consoles (Saturn, Dreamcast, GameCube, Sega CD…) and Arduboy aren't
  hashed yet; for those you choose the ROM file once.

## RetroAchievements consoles on Android

"RetroArch cores" lists only cores RetroAchievements supports for that console
*and* the catalogue launches on Android. "Standalone apps" lists only apps
RetroAchievements supports that are in the pack and that the catalogue launches
for that console. "Other pack emulators" can play the console but aren't on
RetroAchievements' list, so achievements may not work in them.

| RetroAchievements console | ID | App can hash-match | RetroArch cores (RA-supported, in catalogue) | Standalone apps (RA-supported, in pack) | Other pack emulators (not on RA's list) |
|---|---:|:---:|---|---|---|
| 32X | 10 | yes | PicoDrive | — | — |
| 3DO Interactive Multiplayer | 43 | no (disc) | Opera | — | — |
| Amstrad CPC | 37 | yes | Caprice32 | — | — |
| Apple II | 38 | yes | — | — | — |
| Arcade | 27 | yes | FinalBurn Neo, Flycast | Flycast | — |
| Arcadia 2001 | 73 | yes | — | — | — |
| Arduboy | 71 | no | Ardens, Arduous | — | — |
| Atari 2600 | 25 | yes | Stella, Stella 2014 | — | — |
| Atari 7800 | 51 | yes | ProSystem | — | — |
| Atari Jaguar | 17 | yes | Virtual Jaguar | — | — |
| Atari Jaguar CD | 77 | no (disc) | — | — | — |
| Atari Lynx | 13 | yes | Handy, Beetle Lynx | — | — |
| ColecoVision | 44 | yes | blueMSX | — | — |
| Dreamcast | 40 | no (disc) | Flycast | Flycast | — |
| Elektor TV Games Computer | 75 | yes | — | — | — |
| Fairchild Channel F | 57 | yes | FreeChaF | — | — |
| Famicom Disk System | 81 | yes | Mesen | — | — |
| Game Boy | 4 | yes | Gambatte, Gearboy, mGBA | — | — |
| Game Boy Color | 6 | yes | Gambatte, Gearboy, mGBA | — | — |
| Game Boy Advance | 5 | yes | mGBA, VBA-M, Beetle GBA, VBA Next | — | — |
| GameCube | 16 | no (disc) | — | Dolphin Emulator | Dolphin-MMJR2-VBI |
| Game Gear | 15 | yes | Genesis Plus GX, Gearsystem | — | — |
| Genesis/Mega Drive | 1 | yes | Genesis Plus GX, PicoDrive | — | — |
| Intellivision | 45 | yes | FreeIntv | — | — |
| Interton VC 4000 | 74 | yes | — | — | — |
| Magnavox Odyssey2/Philips Videopac+ | 23 | yes | O2EM | — | — |
| Master System/Mark III | 11 | yes | Genesis Plus GX, Gearsystem | — | — |
| Mega Duck | 69 | yes | SameDuck | — | — |
| MSX | 29 | yes | blueMSX | — | — |
| Neo Geo CD | 56 | no (disc) | NeoCD | — | — |
| Neo Geo Pocket (Color) | 14 | yes | Beetle NeoPop | — | — |
| NES/Famicom | 7 | yes | FCEUmm, Mesen, QuickNES | — | — |
| Nintendo 64 | 2 | yes | Mupen64Plus-Next, ParaLLEl N64 | — | Gopher64 |
| Nintendo DS | 18 | yes | DeSmuME, melonDS, melonDS DS | MelonDS | WatermelonDS |
| Nintendo DSi | 78 | yes | melonDS DS | MelonDS | WatermelonDS |
| PC Engine/TurboGrafx-16/SuperGrafx | 8 | yes | Beetle PCE Fast | — | — |
| PC Engine CD/TurboGrafx-CD | 76 | no (disc) | Beetle PCE Fast | — | — |
| PC-8000/8800 | 47 | yes | QUASI88 | — | — |
| PC-FX | 49 | no (disc) | Beetle PC-FX | — | — |
| PlayStation | 12 | yes | Beetle PSX HW, Beetle PSX, SwanStation | DuckStation | ARMSX1 |
| PlayStation 2 | 21 | yes | — | ARMSX2 | NetherSX2-Classic, NetherSX2-Patch, NetherSX2-Turnip |
| PlayStation Portable | 41 | no (disc) | PPSSPP | PPSSPP | — |
| Pokémon Mini | 24 | yes | PokeMini | — | — |
| Saturn | 39 | no (disc) | Beetle Saturn | — | — |
| Sega CD | 9 | no (disc) | Genesis Plus GX, PicoDrive | — | — |
| SG-1000 | 33 | yes | blueMSX | — | — |
| SNES/Super Famicom/Satellaview/Sufami Turbo | 3 | yes | Snes9X, Mesen-S | — | — |
| Uzebox | 80 | yes | Uzem | — | — |
| Vectrex | 46 | yes | vecx | — | — |
| Virtual Boy | 28 | yes | Beetle VB | — | — |
| WASM-4 | 72 | yes | WASM-4 | — | — |
| Watara Supervision | 63 | yes | Potator | — | — |
| Wii | 19 | no (disc) | — | Dolphin Emulator | Dolphin-MMJR2-VBI |
| WonderSwan (Color) | 53 | yes | Beetle Wonderswan | — | — |

## All RetroAchievements emulators, by console

Everything on RetroAchievements' list, including desktop-only and iOS-only
emulators. libretro cores run in RetroArch (Android included, where the core is
built for it). BizHawk is Windows and Linux only.

| Console | libretro cores | Standalone emulators | Other |
|---|---|---|---|
| 32X | PicoDrive | — | PicoDrive (BizHawk) |
| 3DO Interactive Multiplayer | Opera | — | — |
| Amstrad CPC | Caprice32 | — | — |
| Apple II | — | RAppleWin | — |
| Arcade | FinalBurn Neo, Flycast | Flycast | — |
| Arcadia 2001 | AmiArcadia | WinArcadia, DroidArcadia | — |
| Arduboy | Ardens, Arduous | — | — |
| Atari 2600 | Stella, Stella 2014 | — | Atari2600Hawk (BizHawk) |
| Atari 7800 | ProSystem | — | A7800Hawk (BizHawk) |
| Atari Jaguar | Virtual Jaguar | — | Virtual Jaguar (BizHawk) |
| Atari Jaguar CD | — | — | Virtual Jaguar (BizHawk) |
| Atari Lynx | Handy, Beetle Lynx | — | Handy (BizHawk) |
| ColecoVision | blueMSX | RAMeka | ColecoHawk (BizHawk) |
| Dreamcast | Flycast | Flycast | — |
| Elektor TV Games Computer | AmiArcadia | WinArcadia, DroidArcadia | — |
| Fairchild Channel F | FreeChaF | — | — |
| Famicom Disk System | Mesen | RANes | — |
| Game Boy | Gambatte, Gearboy, mGBA, VBA-M | RAVBA, Pizza Boy C Basic & Pro, SkyEmu, Playback, Linkboy | Gambatte (BizHawk), GBHawk (BizHawk) |
| Game Boy Color | Gambatte, Gearboy, mGBA, VBA-M | RAVBA, Pizza Boy C Basic & Pro, SkyEmu, Playback, Linkboy | Gambatte (BizHawk), GBHawk (BizHawk) |
| Game Boy Advance | mGBA, VBA-M, Beetle GBA, VBA Next | RAVBA, Pizza Boy A Basic & Pro, SkyEmu, Playback, Linkboy | mGBA (BizHawk) |
| GameCube | — | Dolphin, DolphinUWP | — |
| Game Gear | Genesis Plus GX, Gearsystem | RAMeka, Pizza Boy SC Basic & Pro | — |
| Genesis/Mega Drive | Genesis Plus GX, PicoDrive | Pizza Boy SC Basic & Pro | Genplus-gx (BizHawk) |
| Intellivision | FreeIntv | — | IntelliHawk (BizHawk) |
| Interton VC 4000 | AmiArcadia | WinArcadia, DroidArcadia | — |
| Magnavox Odyssey2/Philips Videopac+ | O2EM | — | — |
| Master System/Mark III | Genesis Plus GX, Gearsystem | RAMeka, Pizza Boy SC Basic & Pro | — |
| Mega Duck | SameDuck | — | — |
| MSX | blueMSX | — | — |
| Neo Geo CD | NeoCD | — | — |
| Neo Geo Pocket (Color) | Beetle NeoPop | — | NeoPop (BizHawk) |
| NES/Famicom | FCEUmm, Mesen, QuickNES | RANes | NES RA Adapter (Console adapter) |
| Nintendo 64 | Mupen64Plus-Next, ParaLLEl N64 | RAProject64, Luna's Project64 | — |
| Nintendo DS | DeSmuME, melonDS, melonDS DS | melonDS Android | melonDS (BizHawk) |
| Nintendo DSi | melonDS DS | melonDS Android | melonDS (BizHawk) |
| PC Engine/TurboGrafx-16/SuperGrafx | Beetle SuperGrafx, Beetle PCE Fast | — | PCEHawk (BizHawk) |
| PC Engine CD/TurboGrafx-CD | Beetle SuperGrafx, Beetle PCE Fast | — | PCEHawk (BizHawk) |
| PC-8000/8800 | QUASI88 | RAQUASI88 | — |
| PC-FX | Beetle PC-FX | — | T.S.T. (BizHawk) |
| PlayStation | Beetle PSX HW, Beetle PSX, SwanStation | DuckStation | — |
| PlayStation 2 | — | ARMSX2, PCSX2, XBSX2 | — |
| PlayStation Portable | PPSSPP | PPSSPP | — |
| Pokémon Mini | PokeMini | — | — |
| Saturn | Beetle Saturn | Yaba Sanshiro | Saturnus (BizHawk) |
| Sega CD | Genesis Plus GX, PicoDrive | — | — |
| SG-1000 | Genesis Plus GX, blueMSX | RAMeka | — |
| SNES/Super Famicom/Satellaview/Sufami Turbo | Snes9X, Mesen-S | RASnes9x | RA2Snes (Console adapter) |
| Uzebox | Uzem | — | Uzem (BizHawk) |
| Vectrex | vecx | — | — |
| Virtual Boy | Beetle VB | — | Virtual Boyee (BizHawk) |
| WASM-4 | WASM-4 | — | — |
| Watara Supervision | Potator | — | — |
| Wii | — | Dolphin, DolphinUWP | — |
| WonderSwan (Color) | Beetle Wonderswan | — | Cygne (BizHawk) |

## Android emulators (Obtainium Emulation Pack)

Emulator and PC-emulation apps in the pack. "Optional" apps are listed in the
pack's README but not in its default import. The last column shows whether the
Cocoon catalogue has a launch entry for the app's package, which is what this
app needs to launch it.

| App | Package | Consoles (pack description) | In default pack | Launchable via Cocoon catalogue |
|---|---|---|:---:|---|
| aPS3e | `aenu.aps3e` | PlayStation 3 emulator | yes | yes: Sony PlayStation 3 |
| ARMSX1 | `com.nanodata.armsx` | PlayStation 1 emulator | yes | yes: Sony PlayStation |
| ARMSX2 | `com.armsx2` | PlayStation 2 emulator | yes | yes: Sony PlayStation 2 |
| ARMSX2 Nightly | `com.armsx2.nightly` | PlayStation 2 emulator (nightly builds) | optional | yes: Sony PlayStation 2 |
| ARMSX3 | `com.armsx3` | PlayStation 3 emulator (RPCS3 port) | yes | yes: Sony PlayStation 3 |
| Azahar | `org.azahar_emu.azahar` | Nintendo 3DS emulator | yes | yes: Nintendo 3DS |
| Cemu | `info.cemu.cemu` | Wii U emulator (upstream Android port) | optional | yes: Nintendo Wii U |
| Cemu (DS) | `info.cemu.cemu` | Wii U emulator (dual-screen fork) | yes | yes: Nintendo Wii U |
| Citra MMJ | `org.citra.emu` | Nintendo 3DS emulator (MMJ fork) | yes | yes: Nintendo 3DS |
| Citron Neo | `org.citron.citron_emu` | Nintendo Switch emulator | yes | yes: Nintendo Switch |
| Dolphin Emulator | `org.dolphinemu.dolphinemu` | GameCube and Wii emulator | yes | yes: GameCube, Nintendo Wii, Nintendo WiiWare |
| Dolphin Emulator (Dev build) | `org.dolphinemu.dolphinemu` | GameCube and Wii emulator (dev builds) | yes | yes: GameCube, Nintendo Wii, Nintendo WiiWare |
| Dolphin-MMJR2-VBI | `org.dolphinemu.mmjr` | GameCube and Wii emulator (MMJR2 fork) | optional | yes: GameCube, Nintendo Wii, Nintendo WiiWare |
| DuckStation | `com.github.stenzek.duckstation` | PlayStation 1 emulator | yes | yes: Sony PlayStation |
| Eden | `dev.eden.eden_emulator` | Nintendo Switch emulator | yes | yes: Nintendo Switch |
| Eden (Nightly) | `dev.eden.eden_nightly` | Nintendo Switch emulator (nightly builds) | optional | **no** |
| Flycast | `com.flycast.emulator` | Dreamcast, NAOMI, and Atomiswave emulator | yes | yes: Atomiswave, Dreamcast, Sega Naomi |
| Gopher64 | `io.github.gopher64.gopher64` | Nintendo 64 emulator | yes | yes: Nintendo 64 |
| Hakux | `com.rfandango.haku_x` | Original Xbox emulator (Adreno fork) | yes | yes: Microsoft Xbox |
| MelonDS | `me.magnum.melonds` | Nintendo DS emulator | yes | yes: Nintendo DS, Nintendo DSi |
| MelonDS Nightly | `me.magnum.melonds.nightly` | Nintendo DS emulator (nightly builds) | yes | yes: Nintendo DS, Nintendo DSi |
| NetherSX2-Classic | `xyz.aethersx2.android` | PlayStation 2 emulator (AetherSX2 classic patch) | optional | yes: Sony PlayStation 2 |
| NetherSX2-Patch | `xyz.aethersx2.android` | PlayStation 2 emulator (AetherSX2 patch) | yes | yes: Sony PlayStation 2 |
| NetherSX2-Turnip | `xyz.aethersx2.tturnip` | PlayStation 2 emulator (Turnip driver build) | yes | yes: Sony PlayStation 2 |
| Pico8 Android | `io.wip.pico8` | PICO-8 fantasy console frontend | yes | yes: PICO-8 |
| Play! | `com.virtualapplications.play` | PlayStation 2 emulator | yes | **no** |
| PPSSPP | `org.ppsspp.ppsspp` | PlayStation Portable emulator | yes | yes: PlayStation Portable, PlayStation Portable Minis |
| RetroArch (AArch64) | `com.retroarch.aarch64` | Multi-system emulator (stable builds) | yes | yes (102 platforms, see below) |
| RetroArch Nightly (AArch64) | `com.retroarch.aarch64` | Multi-system emulator (nightly builds) | optional | yes (102 platforms, see below) |
| RPCSX | `net.rpcsx` | PlayStation 4 emulator | yes | yes: Sony PlayStation 3 |
| ScummVM | `org.scummvm.scummvm` | Classic adventure and RPG interpreter | yes | yes: ScummVM |
| Swiff | `io.navivani.swiff` | Flash game player for Android | yes | yes: Adobe Flash |
| Vita3K | `org.vita3k.emulator` | PlayStation Vita emulator | yes | yes: Sony PS Vita |
| WatermelonDS | `me.magnum.melondualds` | Nintendo DS emulator (melonDS fork) | yes | yes: Nintendo DS, Nintendo DSi |
| X1 BOX | `com.izzy2lost.x1box` | Original Xbox emulator | yes | yes: Microsoft Xbox |
| X360 Mobile | `emu.x360mobile.com` | Xbox 360 emulator (experimental) | yes | yes: Microsoft - XBox 360 |
| Xendroid | `xendroid.compose` | Xbox 360 emulator | yes | yes: Microsoft - XBox 360 |
| Droiddeck | `com.droiddeck.launcher` | Steam client with Proton for Android | yes | yes: Windows |
| GameHub Lite | `gamehub.lite` | Windows game launcher for Android | yes | yes: Windows |
| GameHub Lite (pre-release) | `gamehub.lite` | Windows game launcher for Android (pre-release builds) | yes | yes: Windows |
| GameNative | `app.gamenative` | Native Windows game compatibility layer | yes | yes: Windows |
| Starboard | `org.force9.starboard` | PortMaster game runner for Android | yes | yes: Ports |
| Winlator | `com.winlator` | Windows app and game emulator for Android | yes | yes: Windows |
| Winlator CMod | `com.winlator.cmod` | Winlator community mod | yes | yes: Windows |
| Winlator-Ludashi | `com.winlator.ludashi` | Winlator fork with Ludashi optimizations | yes | **no** |

### RetroArch (AArch64) consoles

RetroArch's console support depends on its cores. These are the platforms and
cores the Cocoon catalogue launches with the AArch64 build (`com.retroarch.aarch64`),
the build in the pack and the one the Retroid Pocket 5 uses.

| Platform | Cores the catalogue launches |
|---|---|
| 3DO | opera |
| Acorn Computers BBC Micro | b2 |
| Amstrad CPC | cap32, crocods |
| Apple II | mamemess |
| Arcade (FinalBurn Neo) | fbalpha2012, fbneo |
| Arcade (MAME) | mame2000, mame2003, mame2003_midway, mame2003_plus, mame2010, mamearcade |
| Arcadia 2001 | mamemess |
| Arduboy | ardens, arduous |
| Atari 2600 | stella, stella2014, stella2023 |
| Atari 5200 | a5200, atari800 |
| Atari 7800 | prosystem |
| Atari Jaguar | virtualjaguar |
| Atari Jaguar CD | virtualjaguar |
| Atari Lynx | handy, holani, mednafen_lynx |
| Atari ST | hatari |
| Atomiswave | flycast |
| Cannonball OutRun Engine | cannonball |
| Cave Story Game Engine | doukutsu_rs, nxengine |
| CHIP-8 | jaxe |
| ColecoVision | bluemsx, gearcoleco |
| Commodore - PET | vice_xpet |
| Commodore - PLUS/4 | vice_xplus4 |
| Commodore 64 | frodo, vice_x64, vice_x64sc, x64sdl |
| Commodore Amiga | puae, puae2021 |
| CP System I | fbalpha2012, fbalpha2012_cps1, fbneo |
| CP System II | fbalpha2012, fbalpha2012_cps2, fbneo |
| CP System III | fbalpha2012, fbalpha2012_cps3, fbneo |
| DOOM Game Engine | prboom |
| DOS | dosbox_core, dosbox_pure, dosbox_svn |
| Dreamcast | flycast |
| Fairchild Channel F | freechaf |
| Famicom Disk System | fceumm, mesen, nestopia |
| Flashback Game Engine | reminiscence |
| Game & Watch | gw, mamemess |
| Game Boy | gambatte, gearboy, mesen-s, mgba, sameboy, tgbdual |
| Game Boy Advance | gpsp, mednafen_gba, meteor, mgba, vba_next, vbam |
| Game Boy Color | gambatte, gearboy, mesen-s, mgba, sameboy, tgbdual |
| GameCube | dolphin |
| Intellivision | freeintv |
| Interton VC 4000 | mamemess |
| Java Me | squirreljme |
| LowRes NX | lowresnx |
| Magnavox Odyssey 2 | o2em |
| Mega Duck | sameduck |
| MSX | bluemsx, fmsx |
| NEC PC-88 | quasi88 |
| NEC PC-98 | nekop2, np2kai |
| NEC PC-FX | mednafen_pcfx |
| Neo Geo | fbalpha2012, fbneo, geolith |
| Neo Geo CD | fbneo, neocd |
| NeoGeo Pocket | mednafen_ngp, race |
| NeoGeo Pocket Color | mednafen_ngp, race |
| Nintendo 3DS | citra, citra2018, citra_canary |
| Nintendo 64 | mupen64plus_next_gles2, mupen64plus_next_gles3, parallel_n64 |
| Nintendo DS | desmume, desmume2015, melonds, melondsds |
| Nintendo DSi | desmume, desmume2015, melonds, melondsds |
| Nintendo Entertainment System | bnes, fceumm, mesen, nestopia, quicknes |
| Nintendo Satellaview | bsnes, mednafen_snes, mednafen_supafaust, mesen-s, snes9x, snes9x2002, snes9x2005, snes9x2005_plus, snes9x2010 |
| Nintendo Wii | dolphin |
| Nintendo WiiWare | dolphin |
| Palm OS | mu |
| Philips CD-i | same_cdi |
| Philips Videopac+ G7400 | o2em |
| PICO-8 | fake08, retro8 |
| PlayStation Portable | ppsspp |
| PlayStation Portable Minis | ppsspp |
| Pokemon Mini | pokemini |
| Quake Game Engine | tyrquake |
| Quake II Game Engine | vitaquake2, vitaquake2-rogue, vitaquake2-xatrix, vitaquake2-zaero |
| RPG Maker | easyrpg |
| ScummVM | scummvm |
| Sega 32X | picodrive |
| Sega CD | genesis_plus_gx, genesis_plus_gx_wide, picodrive |
| Sega Game Gear | gearsystem, genesis_plus_gx, genesis_plus_gx_wide, picodrive, smsplus |
| Sega Genesis | genesis_plus_gx, genesis_plus_gx_wide, picodrive |
| Sega Genesis - MSU | genesis_plus_gx, genesis_plus_gx_wide |
| Sega Master System | gearsystem, genesis_plus_gx, genesis_plus_gx_wide, picodrive, smsplus |
| Sega Naomi | flycast |
| Sega Pico | picodrive |
| Sega Saturn | mednafen_saturn, yabasanshiro, yabause |
| Sega SG-1000 | bluemsx, gearsystem |
| Sharp X1 | x1 |
| Sharp X68000 | px68k |
| Sony PlayStation | duckstation, mednafen_psx, mednafen_psx_hw, pcsx_rearmed, swanstation |
| Sony PlayStation 2 | play |
| Super Nintendo Entertainment System | bsnes, bsnes2014_accuracy, bsnes2014_balanced, bsnes2014_performance, bsnes_cplusplus98, bsnes_hd_beta, bsnes_mercury_accuracy, bsnes_mercury_balanced, bsnes_mercury_performance, higan_sfc, higan_sfc_balanced, mednafen_snes, mednafen_supafaust, mesen-s, snes9x, snes9x2002, snes9x2005, snes9x2005_plus, snes9x2010 |
| Super Nintendo Entertainment System - MSU-1 | bsnes, bsnes_hd_beta, mesen-s, snes9x |
| SuperGrafx | mednafen_pce, mednafen_supergrafx |
| TIC-80 | tic80 |
| TurboGrafx-16 | mednafen_pce, mednafen_pce_fast |
| TurboGrafx-CD | mednafen_pce, mednafen_pce_fast |
| Uzebox | uzem |
| Vectrex | vecx |
| VIC-20 | vice_xvic |
| Video Player | ffmpeg |
| Virtual Boy | mednafen_vb |
| WASM-4 | wasm4 |
| Watara Supervision | potator |
| WonderSwan | mednafen_wswan |
| WonderSwan Color | mednafen_wswan |
| ZX 81 | 81 |
| ZX Spectrum | fuse |

## ROM folder names (ES-DE)

ES-DE's folder layout is the default for many Android frontends, so the app
recognizes all of its folder names that have a catalogue platform. Source: ES-DE's
[`resources/systems/android/es_systems.xml`](https://gitlab.com/es-de/emulationstation-de/-/blob/master/resources/systems/android/es_systems.xml)
(commit `886945e1`, October 28, 2026).

- **98** folders match a catalogue platform's name or alias directly.
- **36** are mapped by the app (`FolderAliases.ES_DE`), mostly regional variants
  (`snesna`, `megadrivejp`, `saturnjp`) and arcade names (`arcade` → MAME, `fba` and `cps` → FinalBurn Neo).
- **61** have no catalogue platform, so nothing could launch them anyway. Their
  games are skipped.

Folders are matched ignoring case. Subfolders of a platform folder are searched
too, and NextUI/MinUI names are read by their tag, so `Super Nintendo Entertainment System (SFC)` counts as SNES.
`pc` (IBM PC in ES-DE) follows the catalogue, which lists it as an alias of Windows.

| ES-DE folder | ES-DE system | Catalogue platform | How |
|---|---|---|---|
| `3do` | 3DO Interactive Multiplayer | 3DO (`3do`) | catalogue name |
| `adam` | Coleco Adam | none | — |
| `ags` | Adventure Game Studio Game Engine | none | — |
| `amiga` | Commodore Amiga | Commodore Amiga (`amiga`) | catalogue name |
| `amiga1200` | Commodore Amiga 1200 | Commodore Amiga (`amiga`) | app alias |
| `amiga600` | Commodore Amiga 600 | Commodore Amiga (`amiga`) | app alias |
| `amigacd32` | Commodore Amiga CD32 | Commodore Amiga (`amiga`) | app alias |
| `amstradcpc` | Amstrad CPC | Amstrad CPC (`amstradcpc`) | catalogue name |
| `android` | Google Android | none | — |
| `androidapps` | Android Apps | none | — |
| `androidgames` | Android Games | none | — |
| `apple2` | Apple II | Apple II (`apple2`) | catalogue name |
| `apple2gs` | Apple IIGS | none | — |
| `arcade` | Arcade | Arcade (MAME) (`mame`) | app alias |
| `arcadia` | Emerson Arcadia 2001 | Arcadia 2001 (`arcadia`) | catalogue name |
| `archimedes` | Acorn Archimedes | none | — |
| `arduboy` | Arduboy Miniature Game System | Arduboy (`arduboy`) | catalogue name |
| `astrocde` | Bally Astrocade | none | — |
| `atari2600` | Atari 2600 | Atari 2600 (`atari2600`) | catalogue name |
| `atari5200` | Atari 5200 | Atari 5200 (`atari5200`) | catalogue name |
| `atari7800` | Atari 7800 ProSystem | Atari 7800 (`atari7800`) | catalogue name |
| `atari800` | Atari 800 | none | — |
| `atarijaguar` | Atari Jaguar | Atari Jaguar (`atarijaguar`) | catalogue name |
| `atarijaguarcd` | Atari Jaguar CD | Atari Jaguar CD (`atarijaguarcd`) | catalogue name |
| `atarilynx` | Atari Lynx | Atari Lynx (`atarilynx`) | catalogue name |
| `atarist` | Atari ST | Atari ST (`atarist`) | catalogue name |
| `atarixe` | Atari XE | none | — |
| `atomiswave` | Sammy Corporation Atomiswave | Atomiswave (`atomiswave`) | catalogue name |
| `bbcmicro` | Acorn Computers BBC Micro | Acorn Computers BBC Micro (`bbcmicro`) | catalogue name |
| `c64` | Commodore 64 | Commodore 64 (`c64`) | catalogue name |
| `cdimono1` | Philips CD-i | Philips CD-i (`cdimono1`) | catalogue name |
| `cdtv` | Commodore CDTV | Commodore Amiga (`amiga`) | app alias |
| `chailove` | ChaiLove Game Engine | none | — |
| `channelf` | Fairchild Channel F | Fairchild Channel F (`channelf`) | catalogue name |
| `coco` | Tandy Color Computer | none | — |
| `colecovision` | Coleco ColecoVision | ColecoVision (`colecovision`) | catalogue name |
| `consolearcade` | Console Arcade Systems | none | — |
| `cps` | Capcom Play System | Arcade (FinalBurn Neo) (`fbneo`) | app alias |
| `cps1` | Capcom Play System I | CP System I (`cps1`) | catalogue name |
| `cps2` | Capcom Play System II | CP System II (`cps2`) | catalogue name |
| `cps3` | Capcom Play System III | CP System III (`cps3`) | catalogue name |
| `crvision` | VTech CreatiVision | none | — |
| `daphne` | Daphne Arcade LaserDisc Emulator | none | — |
| `desktop` | Desktop Applications | none | — |
| `doom` | Doom | DOOM Game Engine (`doom`) | catalogue name |
| `dos` | DOS (PC) | DOS (`dos`) | catalogue name |
| `dragon32` | Dragon Data Dragon 32 | none | — |
| `dreamcast` | Sega Dreamcast | Dreamcast (`dreamcast`) | catalogue name |
| `easyrpg` | EasyRPG Game Engine | RPG Maker (`rpgmaker`) | app alias |
| `electron` | Acorn Electron | none | — |
| `emulators` | Emulators | none | — |
| `epic` | Epic Games Store | none | — |
| `famicom` | Nintendo Family Computer | Nintendo Entertainment System (`nes`) | app alias |
| `fba` | FinalBurn Alpha | Arcade (FinalBurn Neo) (`fbneo`) | app alias |
| `fbneo` | FinalBurn Neo | Arcade (FinalBurn Neo) (`fbneo`) | catalogue name |
| `fds` | Nintendo Famicom Disk System | Famicom Disk System (`fds`) | catalogue name |
| `flash` | Adobe Flash | Adobe Flash (`adobeflash`) | app alias |
| `fm7` | Fujitsu FM-7 | none | — |
| `fmtowns` | Fujitsu FM Towns | none | — |
| `fpinball` | Future Pinball | none | — |
| `gamate` | Bit Corporation Gamate | none | — |
| `gameandwatch` | Nintendo Game and Watch | Game & Watch (`gameandwatch`) | catalogue name |
| `gamecom` | Tiger Electronics Game.com | none | — |
| `gamegear` | Sega Game Gear | Sega Game Gear (`gamegear`) | catalogue name |
| `gb` | Nintendo Game Boy | Game Boy (`gb`) | catalogue name |
| `gba` | Nintendo Game Boy Advance | Game Boy Advance (`gba`) | catalogue name |
| `gbc` | Nintendo Game Boy Color | Game Boy Color (`gbc`) | catalogue name |
| `gc` | Nintendo GameCube | GameCube (`gc`) | catalogue name |
| `genesis` | Sega Genesis | Sega Genesis (`genesis`) | catalogue name |
| `gmaster` | Hartung Game Master | none | — |
| `gx4000` | Amstrad GX4000 | Amstrad CPC (`amstradcpc`) | app alias |
| `intellivision` | Mattel Electronics Intellivision | Intellivision (`intellivision`) | catalogue name |
| `j2me` | Java 2 Micro Edition (J2ME) | Java Me (`j2me`) | catalogue name |
| `kodi` | Kodi Home Theatre Software | none | — |
| `laserdisc` | LaserDisc Games | none | — |
| `lcdgames` | LCD Handheld Games | Game & Watch (`gameandwatch`) | app alias |
| `lowresnx` | LowRes NX Fantasy Console | LowRes NX (`lowresnx`) | catalogue name |
| `lutris` | Lutris Open Gaming Platform | none | — |
| `lutro` | Lutro Game Engine | none | — |
| `macintosh` | Apple Macintosh | none | — |
| `mame` | Multiple Arcade Machine Emulator | Arcade (MAME) (`mame`) | catalogue name |
| `mame-advmame` | AdvanceMAME | Arcade (MAME) (`mame`) | app alias |
| `mark3` | Sega Mark III | Sega Master System (`mastersystem`) | app alias |
| `mastersystem` | Sega Master System | Sega Master System (`mastersystem`) | catalogue name |
| `megacd` | Sega Mega-CD | Sega CD (`segacd`) | app alias |
| `megacdjp` | Sega Mega-CD | Sega CD (`segacd`) | app alias |
| `megadrive` | Sega Mega Drive | Sega Genesis (`genesis`) | app alias |
| `megadrivejp` | Sega Mega Drive | Sega Genesis (`genesis`) | app alias |
| `megaduck` | Creatronic Mega Duck | Mega Duck (`megaduck`) | catalogue name |
| `mess` | Multi Emulator Super System | none | — |
| `model2` | Sega Model 2 | none | — |
| `model3` | Sega Model 3 | none | — |
| `moto` | Thomson MO/TO Series | none | — |
| `msx` | MSX | MSX (`msx`) | catalogue name |
| `msx1` | MSX1 | MSX (`msx`) | app alias |
| `msx2` | MSX2 | MSX (`msx`) | app alias |
| `msxturbor` | MSX Turbo R | MSX (`msx`) | app alias |
| `mugen` | M.U.G.E.N Game Engine | none | — |
| `multivision` | Othello Multivision | Sega SG-1000 (`sg-1000`) | app alias |
| `n3ds` | Nintendo 3DS | Nintendo 3DS (`n3ds`) | catalogue name |
| `n64` | Nintendo 64 | Nintendo 64 (`n64`) | catalogue name |
| `n64dd` | Nintendo 64DD | Nintendo 64 (`n64`) | app alias |
| `naomi` | Sega NAOMI | Sega Naomi (`naomi`) | catalogue name |
| `naomi2` | Sega NAOMI 2 | Sega Naomi (`naomi`) | app alias |
| `naomigd` | Sega NAOMI GD-ROM | Sega Naomi (`naomi`) | app alias |
| `nds` | Nintendo DS | Nintendo DS (`nds`) | catalogue name |
| `neogeo` | SNK Neo Geo | Neo Geo (`neogeo`) | catalogue name |
| `neogeocd` | SNK Neo Geo CD | Neo Geo CD (`neogeocd`) | catalogue name |
| `neogeocdjp` | SNK Neo Geo CD | Neo Geo CD (`neogeocd`) | app alias |
| `nes` | Nintendo Entertainment System | Nintendo Entertainment System (`nes`) | catalogue name |
| `ngage` | Nokia N-Gage | Nokia N-Gage (`ngage`) | catalogue name |
| `ngp` | SNK Neo Geo Pocket | NeoGeo Pocket (`ngp`) | catalogue name |
| `ngpc` | SNK Neo Geo Pocket Color | NeoGeo Pocket Color (`ngpc`) | catalogue name |
| `odyssey2` | Magnavox Odyssey 2 | Magnavox Odyssey 2 (`odyssey2`) | catalogue name |
| `openbor` | OpenBOR Game Engine | none | — |
| `oric` | Tangerine Computer Systems Oric | none | — |
| `palm` | Palm OS | Palm OS (`palm`) | catalogue name |
| `pc` | IBM PC | Windows (`windows`) | catalogue name |
| `pc88` | NEC PC-8800 Series | NEC PC-88 (`pc88`) | catalogue name |
| `pc98` | NEC PC-9800 Series | NEC PC-98 (`pc98`) | catalogue name |
| `pcarcade` | PC Arcade Systems | none | — |
| `pcengine` | NEC PC Engine | TurboGrafx-16 (`tg16`) | app alias |
| `pcenginecd` | NEC PC Engine CD | TurboGrafx-CD (`tg-cd`) | app alias |
| `pcfx` | NEC PC-FX | NEC PC-FX (`pcfx`) | catalogue name |
| `pico8` | PICO-8 Fantasy Console | PICO-8 (`pico8`) | catalogue name |
| `plus4` | Commodore Plus/4 | Commodore - PLUS/4 (`plus4`) | catalogue name |
| `pokemini` | Nintendo Pokémon Mini | Pokemon Mini (`pokemini`) | catalogue name |
| `ports` | Ports | Ports (`ports`) | catalogue name |
| `ps2` | Sony PlayStation 2 | Sony PlayStation 2 (`ps2`) | catalogue name |
| `ps3` | Sony PlayStation 3 | Sony PlayStation 3 (`ps3`) | catalogue name |
| `ps4` | Sony PlayStation 4 | none | — |
| `psp` | Sony PlayStation Portable | PlayStation Portable (`psp`) | catalogue name |
| `psvita` | Sony PlayStation Vita | Sony PS Vita (`psvita`) | catalogue name |
| `psx` | Sony PlayStation | Sony PlayStation (`psx`) | catalogue name |
| `pv1000` | Casio PV-1000 | none | — |
| `quake` | Quake | Quake Game Engine (`quake`) | catalogue name |
| `samcoupe` | MGT SAM Coupé | none | — |
| `satellaview` | Nintendo Satellaview | Nintendo Satellaview (`satellaview`) | catalogue name |
| `saturn` | Sega Saturn | Sega Saturn (`saturn`) | catalogue name |
| `saturnjp` | Sega Saturn | Sega Saturn (`saturn`) | app alias |
| `scummvm` | ScummVM Game Engine | ScummVM (`scummvm`) | catalogue name |
| `scv` | Epoch Super Cassette Vision | none | — |
| `sega32x` | Sega Mega Drive 32X | Sega 32X (`sega32x`) | catalogue name |
| `sega32xjp` | Sega Super 32X | Sega 32X (`sega32x`) | app alias |
| `sega32xna` | Sega Genesis 32X | Sega 32X (`sega32x`) | app alias |
| `segacd` | Sega CD | Sega CD (`segacd`) | catalogue name |
| `sfc` | Nintendo SFC (Super Famicom) | Super Nintendo Entertainment System (`snes`) | app alias |
| `sg-1000` | Sega SG-1000 | Sega SG-1000 (`sg-1000`) | catalogue name |
| `sgb` | Nintendo Super Game Boy | none | — |
| `snes` | Nintendo SNES (Super Nintendo) | Super Nintendo Entertainment System (`snes`) | catalogue name |
| `snesna` | Nintendo SNES (Super Nintendo) | Super Nintendo Entertainment System (`snes`) | app alias |
| `solarus` | Solarus Game Engine | none | — |
| `spectravideo` | Spectravideo | none | — |
| `steam` | Valve Steam | Windows (`windows`) | catalogue name |
| `stv` | Sega Titan Video Game System | Arcade (MAME) (`mame`) | app alias |
| `sufami` | Bandai SuFami Turbo | Super Nintendo Entertainment System (`snes`) | app alias |
| `supergrafx` | NEC SuperGrafx | SuperGrafx (`supergrafx`) | catalogue name |
| `supervision` | Watara Supervision | Watara Supervision (`supervision`) | catalogue name |
| `supracan` | Funtech Super A'Can | none | — |
| `switch` | Nintendo Switch | Nintendo Switch (`switch`) | catalogue name |
| `symbian` | Symbian | Nokia N-Gage (`ngage`) | app alias |
| `tanodragon` | Tano Dragon | none | — |
| `tg16` | NEC TurboGrafx-16 | TurboGrafx-16 (`tg16`) | catalogue name |
| `tg-cd` | NEC TurboGrafx-CD | TurboGrafx-CD (`tg-cd`) | catalogue name |
| `ti99` | Texas Instruments TI-99 | none | — |
| `tic80` | TIC-80 Fantasy Computer | TIC-80 (`tic80`) | catalogue name |
| `to8` | Thomson TO8 | none | — |
| `triforce` | Namco-Sega-Nintendo Triforce | none | — |
| `trs-80` | Tandy TRS-80 | none | — |
| `type-x` | Taito Type X | none | — |
| `uzebox` | Uzebox Open Source Console | Uzebox (`uzebox`) | catalogue name |
| `vectrex` | GCE Vectrex | Vectrex (`vectrex`) | catalogue name |
| `vic20` | Commodore VIC-20 | VIC-20 (`vic20`) | catalogue name |
| `videopac` | Philips Videopac G7000 | Philips Videopac+ G7400 (`videopac`) | catalogue name |
| `vircon32` | Vircon32 Virtual Console | none | — |
| `virtualboy` | Nintendo Virtual Boy | Virtual Boy (`virtualboy`) | catalogue name |
| `vpinball` | Visual Pinball | none | — |
| `vsmile` | VTech V.Smile | none | — |
| `wasm4` | WASM-4 Fantasy Console | WASM-4 (`wasm4`) | catalogue name |
| `wii` | Nintendo Wii | Nintendo Wii (`wii`) | catalogue name |
| `wiiu` | Nintendo Wii U | Nintendo Wii U (`wiiu`) | catalogue name |
| `windows` | Microsoft Windows | Windows (`windows`) | catalogue name |
| `windows3x` | Microsoft Windows 3.x | none | — |
| `windows9x` | Microsoft Windows 9x | none | — |
| `wonderswan` | Bandai WonderSwan | WonderSwan (`wonderswan`) | catalogue name |
| `wonderswancolor` | Bandai WonderSwan Color | WonderSwan Color (`wonderswancolor`) | catalogue name |
| `x1` | Sharp X1 | Sharp X1 (`x1`) | catalogue name |
| `x68000` | Sharp X68000 | Sharp X68000 (`x68000`) | catalogue name |
| `xbox` | Microsoft Xbox | Microsoft Xbox (`xbox`) | catalogue name |
| `xbox360` | Microsoft Xbox 360 | Microsoft - XBox 360 (`xbox360`) | catalogue name |
| `xboxone` | Microsoft Xbox One | none | — |
| `zmachine` | Infocom Z-machine | none | — |
| `zx81` | Sinclair ZX81 | ZX 81 (`zx81`) | catalogue name |
| `zxnext` | Sinclair ZX Spectrum Next | none | — |
| `zxspectrum` | Sinclair ZX Spectrum | ZX Spectrum (`zxspectrum`) | catalogue name |

## Notes and gaps

- **RA-supported Android apps outside the pack.** RetroAchievements lists
  Pizza Boy A/C/SC (GBA, GB/GBC, Genesis/Master System/Game Gear) and
  DroidArcadia (Arcadia 2001, Elektor, Interton VC 4000). Neither is in the pack.
- **Pack apps the catalogue can't launch:** Play! (`com.virtualapplications.play`),
  Eden Nightly (`dev.eden.eden_nightly`) and Winlator-Ludashi (`com.winlator.ludashi`).
  This app can't start them until the catalogue adds them.
- **RPCSX** is described by the pack as a PlayStation 4 emulator, but the
  catalogue launches it for PlayStation 3.
- **Catalogue quirks that affect matching:**
  - The `fds` platform carries NES's RetroAchievements ID (7) instead of
    Famicom Disk System's (81).
  - `wii` and `wiiu` each carry both 19 (Wii) and 20 (Wii U).

  The app picks candidate folders by these IDs, so it corrects them in
  `PlatformConsoles` (the `fds` folder now holds FDS games, and Wii and Wii U are
  kept apart). These tables correct for it too.
- **Not RetroAchievements consoles:** Switch, 3DS, Wii U, PS3, PS4, Vita, Xbox,
  Xbox 360, PICO-8, Flash, ScummVM and Windows have Android emulators in the
  pack but no RetroAchievements support, so they can't be club picks matched by hash.
- **Streaming apps** in the pack (Moonlight, Artemis, PXPlay) aren't emulators
  and are left out.
