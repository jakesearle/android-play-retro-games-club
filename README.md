# PRGC Launcher

An Android app that shows what the
[Play Retro Games Club](https://www.joeysretrohandhelds.com/play-retro-games-club/)
is playing right now (Game of the Week, Game of the Month, Romhack of the Month,
Game of the Season and any specials) and launches each game from your own ROMs in
an installed emulator. It's built for controller-first handhelds such as the
Retroid Pocket 5 (Android 13), and is the Android counterpart of the
[NextUI Pak](https://github.com/jakesearle/nextui-play-retro-games-club).

Not affiliated with Joey’s Retro Handhelds, the Play Retro Games Club or RetroAchievements.

## How it works

- **The club's picks** come from the club's page, read the same way as the
  NextUI Pak. The page is cached, and the app asks the site again only after 6
  hours or the club's next announced change. Offline, it shows the saved picks.
- **Finding a pick among your ROMs** works by RetroAchievements hash. The app
  checks the ROMs in your folders for that console against RetroAchievements'
  list of known ROMs, so any file name works but only a recognized version
  counts. Once a game has launched, its file is remembered.
- **Launching** uses the open emulator catalogue published by
  [CocoonFE](https://github.com/KansoMirai/CocoonFE), downloaded and cached at
  runtime, to build each emulator's launch intent.

## Reference

- [Emulator reference](docs/EMULATORS.md): consoles and emulators RetroAchievements
  supports, the Android emulators in the Obtainium Emulation Pack, how they overlap,
  and which ES-DE ROM folder names the app recognizes.

- [Third-party notices](THIRD-PARTY-NOTICES.md): code ported from rcheevos and libchdr,
  and the libraries the app uses.

## Out of scope

- **Being a Home app (launcher replacement).** This app opens from the app
  drawer like any other app. It won't register as a Home app or try to replace
  the device's launcher, and that isn't planned.

## Development

```bash
./gradlew test                    # unit tests
./gradlew :app:installDebug       # install on a connected device
```

Add `-PcatalogueLive=true` to check every entry of the live emulator catalogue,
or `-PclubPage=/path/to/saved.html` to check the parser against a saved copy of
the club's page.

## Licence

[MIT](LICENSE). Code ported from rcheevos (MIT) and libchdr (BSD-3-Clause) keeps its own
licence; see [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md).
