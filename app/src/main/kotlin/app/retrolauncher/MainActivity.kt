package app.retrolauncher

import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.Settings
import android.view.KeyEvent
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color
import app.retrolauncher.library.RomSource
import app.retrolauncher.library.SafPaths
import androidx.lifecycle.lifecycleScope
import app.retrolauncher.ui.ChooseOutcome
import app.retrolauncher.ui.ClubScreen
import app.retrolauncher.ui.ClubViewModel
import app.retrolauncher.ui.PickRow
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val vm: ClubViewModel by viewModels()

    /** The pick waiting for a hand-picked ROM file. */
    private var choosingFor: PickRow? = null

    private val pickFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val row = choosingFor ?: return@registerForActivityResult
        choosingFor = null
        if (uri == null) return@registerForActivityResult
        // Keep access so the linked file can be launched after a reboot.
        runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        lifecycleScope.launch {
            when (val outcome = vm.chooseFile(row, uri)) {
                ChooseOutcome.Linked -> toast("${row.pick.displayTitle} is ready to play.")
                is ChooseOutcome.Problem -> toast(outcome.message)
                ChooseOutcome.Ask -> Unit // the home screen asks whether to use it anyway
            }
        }
    }

    private val pickFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            // Keep access across reboots. Write access lets emulators save next to the ROM.
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            try {
                contentResolver.takePersistableUriPermission(uri, flags)
            } catch (_: SecurityException) {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            vm.addSource(RomSource.Saf(uri.toString()))
        }
    }

    private val requestStorage = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        vm.onResume()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleDebugExtras(intent)
        setContent {
            MaterialTheme(colorScheme = LauncherColors) {
                // Surface sets the content colour, so text is light on the dark background.
                Surface(color = MaterialTheme.colorScheme.background) {
                    ClubScreen(
                        vm = vm,
                        onAddFolder = { pickFolder.launch(null) },
                        onOpenFolder = ::openFolder,
                        onRequestAllFiles = ::requestAllFilesAccess,
                        onChooseFile = { row -> choosingFor = row; pickFile.launch(arrayOf("*/*")) },
                        onOpenUrl = ::openUrl,
                        onOpenLink = ::openLink,
                        showMessage = ::toast,
                        modifier = Modifier.windowInsetsPadding(WindowInsets.safeDrawing),
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleDebugExtras(intent)
    }

    /**
     * Debug builds only: add test picks from adb, e.g.
     * `adb shell am start -n garden.searle.prgc/app.retrolauncher.MainActivity --es debug_pick "Castlevania: Symphony of the Night (PS1)|https://retroachievements.org/game/11240"`,
     * optionally with "|Event label" at the end to pose as that kind of pick (a special, say),
     * or remove them with `--ez debug_clear_picks true`. `--ei debug_make_hack <RA game ID>` runs
     * Make hack for that pick and logs each step (tag PRGC); `--ei debug_supported <RA game ID>` logs
     * the files RetroAchievements supports for it. Release builds ignore these extras.
     */
    private fun handleDebugExtras(intent: Intent?) {
        if (intent == null || applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) return
        val current = vm.debugPickEntries()
        when {
            intent.getBooleanExtra("debug_clear_picks", false) -> vm.setDebugPicks(emptyList())
            intent.hasExtra("debug_pick") -> vm.setDebugPicks((current + intent.getStringExtra("debug_pick").orEmpty()).distinct())
            intent.hasExtra("debug_supported") -> {
                val id = intent.getIntExtra("debug_supported", 0)
                val row = (vm.state.value.rows + vm.state.value.past).firstOrNull { it.pick.gameId == id }
                if (row != null) {
                    vm.loadSupported(row.pick)
                    window.decorView.postDelayed({ android.util.Log.i("PRGC", "supported $id: ${vm.state.value.ra.supported[id]}") }, 8000)
                }
            }
            intent.hasExtra("debug_make_hack") -> {
                val id = intent.getIntExtra("debug_make_hack", 0)
                val state = vm.state.value
                val row = (state.rows + state.past).firstOrNull { it.pick.gameId == id }
                android.util.Log.i("PRGC", "make hack $id: ${row?.state} base=${row?.patchBase?.fileName}")
                if (row != null) vm.makeHack(row, status = { android.util.Log.i("PRGC", "status: $it") }, done = { android.util.Log.i("PRGC", "done: $it") })
            }
        }
    }

    override fun onResume() {
        super.onResume()
        vm.onResume()
    }

    /**
     * At launch the ComposeView doesn't have Android view focus yet. A D-pad press would get
     * it by focus search, but a face button (A, Start, ...) would be dropped. Make sure the
     * content has focus before any key is dispatched.
     *
     * Lint flags this as RestrictedApi because ComponentActivity's own override is marked
     * restricted, but overriding Activity.dispatchKeyEvent is public, ordinary Android.
     */
    @SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val content = findViewById<ViewGroup>(android.R.id.content)?.getChildAt(0)
        if (content != null && !content.hasFocus()) content.requestFocus()
        return super.dispatchKeyEvent(event)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // Full-screen like a console. Bars come back with a swipe from the edge.
        if (hasFocus) {
            WindowCompat.getInsetsController(window, window.decorView).apply {
                systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                hide(WindowInsetsCompat.Type.systemBars())
            }
        }
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    /**
     * Opens an app link (Obtainium's `obtainium://app/...`), or [fallbackUrl] in the browser if
     * nothing handles it.
     */
    private fun openLink(link: String, fallbackUrl: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: ActivityNotFoundException) {
            toast("Obtainium isn't installed, so opening the download page.")
            openUrl(fallbackUrl)
        }
    }

    private fun openUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: ActivityNotFoundException) {
            toast("No browser is installed to open $url")
        }
    }

    /**
     * Opens a ROM folder in the file manager: VIEW on the folder's document URI with the directory
     * type, which Android sends to the default file manager (or asks which one). Picker folders
     * already have a document URI; raw paths are mapped back to shared-storage document IDs.
     */
    private fun openFolder(source: RomSource) {
        val uri = when (source) {
            is RomSource.Saf -> {
                val tree = Uri.parse(source.treeUri)
                DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
            }
            is RomSource.RawPath -> SafPaths.pathToDocumentId(source.path)
                ?.let { DocumentsContract.buildDocumentUri(SafPaths.EXTERNAL_STORAGE, it) }
        }
        if (uri == null) {
            toast("Can't open ${source.label} in a file manager.")
            return
        }
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, DocumentsContract.Document.MIME_TYPE_DIR)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            toast("No file manager here can open folders. The folder is ${source.label}")
        }
    }

    private fun requestAllFilesAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val perApp = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName"))
            try {
                startActivity(perApp)
            } catch (_: ActivityNotFoundException) {
                // Some handheld firmwares only ship the generic screen.
                startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }
        } else {
            requestStorage.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }
}

private val LauncherColors = darkColorScheme(
    primary = Color(0xFF8AB4F8),
    onPrimary = Color(0xFF0B1A33),
    background = Color(0xFF101114),
    onBackground = Color(0xFFE6E6EA),
    surface = Color(0xFF17181C),
    onSurface = Color(0xFFE6E6EA),
    surfaceVariant = Color(0xFF24262C),
    onSurfaceVariant = Color(0xFFC4C6CE),
)
