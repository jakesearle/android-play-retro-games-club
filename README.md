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
  counts. Cartridge ROMs can be plain, zipped or in a .7z. Once a game has
  launched, its file is remembered.
- **Launching** uses the open emulator catalogue published by
  [CocoonFE](https://github.com/KansoMirai/CocoonFE), downloaded and cached at
  runtime, to build each emulator's launch intent.
- **Your RetroAchievements progress** shows once you sign in (Settings ›
  RetroAchievements) with your username and Web API key, kept encrypted on the
  device. Counts are hardcore; softcore-only progress is marked. Beaten and
  mastered games get a trophy on their card.
- **Your own marks** cover what RetroAchievements can't know: mark a game
  *Completed somewhere else* (counts as beaten) or *Did not finish* from its
  game view. A beaten or mastered award on RetroAchievements takes precedence.

## Reference

- [Emulator reference](docs/EMULATORS.md): consoles and emulators RetroAchievements
  supports, the Android emulators in the Obtainium Emulation Pack, how they overlap,
  and which ES-DE ROM folder names the app recognizes.

- [Third-party notices](THIRD-PARTY-NOTICES.md): code ported from rcheevos and libchdr,
  and the libraries the app uses.

## Roadmap

### Before release (sharing on the club's Discord)

**Features**
- **Game manuals.** Link to the pick's manual, if a source can be matched reliably (check
  whether RetroAchievements links manuals; else archive.org or Retro Game Manuals).
- **RetroAchievements in first-run setup.** Signing in (username and Web API key) is in
  Settings › RetroAchievements; offer it as an optional setup step too.
- **Preferred emulators.** Find which emulators Joey's Retro Handhelds recommends and prefer them.

**Release basics**
- **App icon**, final **app name**, and final **application ID**. `app.retrolauncher` is a
  placeholder, and it can't change after people install without losing their settings.
- **Signed release build** and a place for the APK (the repo is private), plus an in-app
  "new version available" check.
- **"Add to Obtainium" button** at the top of this README, using Obtainium's badge and its
  `obtainium://add/<repo URL>` link (through Obtainium's web redirect, since GitHub won't
  link a custom scheme). Needs the repo public and the APK attached to GitHub releases, so
  Obtainium can see new versions.
- **Licences in the app.** Settings › About with the version and third-party notices: the
  libchdr port's BSD licence requires its notice to ship with the app, not only the repo.
- **Testing beyond the Retroid Pocket 5:** 4:3 and near-square screens, small 3.5-4" displays,
  Android 10-15, and handhelds with A/B swapped.
- **A heads-up to Joey's Retro Handhelds** that the app reads the club page (and maybe ask
  for a small JSON feed, which wouldn't break when the site is redesigned).

**ROM matching**
- **More disc consoles.** Prioritise the ones in the club's history (Saturn, Dreamcast,
  Sega CD, PS2 DVD images as CHD…).
- **Progress during the first scan,** with a way to cancel: it reads every ROM for a console.

**Nice to have**
- **Share logs** from Settings for bug reports (Discord users can't run adb).
- **A countdown** on the home screen ("New Game of the Week in 2 days").
- **Links to the club's Discord and leaderboard** from Settings.
- **A privacy line:** the app sends no analytics.

## Later

Ideas we've looked into and set aside for now.

- **Start from the auto save state (Resume).** Like the NextUI Pak's X, offer to carry on
  from where you quit. It depends on the emulator:
  - **Joey64** only loads a state when its own library asks: that extra
    (`com.joeysretro.joey64.LOAD_STATE`) is accepted only through its non-exported
    `.GameStart` alias, and its "Continue on Play" setting (Settings › Saves) is applied by its
    library, so games other apps start always begin from power-on. This needs a change in
    Joey64 first, for example applying "Continue on Play" to games other apps start, or a
    public extra for the auto state only. Then the club app can pass it.
  - **DuckStation** takes a `resumeState` boolean extra; the catalogue's entry passes
    `false`, so a Resume button could pass `true`.
  - **RetroArch** has no per-launch option, but its own *Settings › Saving › Auto Load State*
    applies to every launch, including from this app.

## Out of scope

- **Being a Home app (launcher replacement).** This app opens from the app
  drawer like any other app. It won't register as a Home app or try to replace
  the device's launcher, and that isn't planned.
- **Copying RetroAchievements' own screens.** No achievement lists, badges,
  leaderboards or profile pages. The game view already links to each game's
  RetroAchievements page, and RetroArch shows achievements in-game. The app uses
  RetroAchievements only for what helps with the club's picks: a trophy for
  beaten and mastered games, a one-line progress summary, the next achievement
  towards beating the game, and History's progress filter and popularity sort.

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
