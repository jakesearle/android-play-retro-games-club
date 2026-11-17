package app.retrolauncher.catalogue

/**
 * `amStartArguments` copied from the live catalogue (version 3, November 2026), so the parser
 * tests use real entries. The full catalogue is checked by [LiveCatalogueTest].
 */
object RealEntries {
    /** gba.it.dbtecno.pizzaboygba: content URI in a string extra, relative activity. */
    const val PIZZA_BOY_GBA = "-n it.dbtecno.pizzaboygba/.MainActivity\n-e rom_uri {file.uri}\n--activity-clear-task\n--activity-clear-top"

    /** gb.ra64.mgba: real path plus a pile of RetroArch extras. */
    const val RETROARCH_MGBA =
        "-n com.retroarch.aarch64/com.retroarch.browser.retroactivity.RetroActivityFuture\n" +
            "-e ROM {file.path}\n" +
            "-e LIBRETRO /data/data/com.retroarch.aarch64/cores/mgba_libretro_android.so\n" +
            "-e CONFIGFILE /storage/emulated/0/Android/data/com.retroarch.aarch64/files/retroarch.cfg\n" +
            "-e IME com.android.inputmethod.latin/.LatinIME\n" +
            "-e DATADIR /data/data/com.retroarch.aarch64\n" +
            "-e APK /data/app/com.retroarch.aarch64-1/base.apk\n" +
            "-e SDCARD /storage/emulated/0\n" +
            "-e EXTERNAL /storage/emulated/0/Android/data/com.retroarch.aarch64/files\n" +
            "--activity-clear-task\n" +
            "--activity-clear-top"

    /** gba.com.fastemulator.gbafree: VIEW with the path as data. */
    const val MY_BOY_FREE = "-n com.fastemulator.gbafree/com.fastemulator.gba.EmulatorActivity\n-a android.intent.action.VIEW\n-d {file.path}"

    /** 3ds.azahar: VIEW with a content URI as data. */
    const val AZAHAR = "-n io.github.lime3ds.android/org.citra.citra_emu.activities.EmulationActivity\n-a android.intent.action.VIEW\n-d {file.uri}\n--activity-clear-task\n--activity-clear-top"

    /** ebook.com.github.axet.bookreader: category plus {file.mime}. */
    const val BOOK_READER = "-n com.github.axet.bookreader/.activities.MainActivity\n-a android.intent.action.VIEW\n-c android.intent.category.DEFAULT\n-d {file.uri}\n-t {file.mime}"

    /** psx.com.github.stenzek.duckstation.legacy: --ez. */
    const val DUCKSTATION = "-n com.github.stenzek.duckstation/.EmulationActivity\n-e bootPath {file.uri}\n--ez resumeState false\n--activity-clear-task\n--activity-clear-top"

    /** windows.app.gamenative: --ei filled in from a tag. */
    const val GAMENATIVE = "-n app.gamenative/.MainActivity\n-a app.gamenative.LAUNCH_GAME\n--ei app_id {tags.app_id}\n-e game_source {tags.game_source}"

    /** psv.vita3k: --esa with a tag inside. */
    const val VITA3K = "-n org.vita3k.emulator/.Emulator\n--esa AppStartParameters -r,{tags.vita_game_id}"

    /** ps3.com.armsx3: --eu with a path. */
    const val ARMSX3 = "-n com.armsx3/com.armsx2.MainActivity\n-a android.intent.action.VIEW\n--eu android.intent.extra.STREAM {file.path}\n--activity-clear-task\n--activity-clear-top"
}
