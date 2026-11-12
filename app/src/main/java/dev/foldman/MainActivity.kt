package dev.foldman

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.window.layout.WindowInfoTracker
import androidx.window.layout.WindowLayoutInfo
import dev.foldman.deck.DeckViewModel
import dev.foldman.deck.Source
import dev.foldman.deck.Transport
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import dev.foldman.ui.FoldmanScreen

class MainActivity : ComponentActivity() {
    private val vm by lazy { ViewModelProvider(this)[DeckViewModel::class.java] }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            // Keep the screen awake only while a tape is playing; the flag also lapses whenever
            // Foldman isn't the visible window.
            val playing by remember { vm.ui.map { it.transport == Transport.PLAYING }.distinctUntilChanged() }
                .collectAsStateWithLifecycle(initialValue = false)
            LaunchedEffect(playing) {
                if (playing) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
            val windowLayout by remember { WindowInfoTracker.getOrCreate(this).windowLayoutInfo(this) }
                .collectAsStateWithLifecycle<WindowLayoutInfo?>(initialValue = null)
            val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
                if (uri != null) {
                    contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    vm.setFolder(uri)
                }
            }
            val appleSignIn = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
                vm.onAppleSignInResult(result.data)
            }
            // Only for the media notification; playback works without it.
            val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
            LaunchedEffect(Unit) {
                if (Build.VERSION.SDK_INT >= 33 &&
                    ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                ) notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
            MaterialTheme(colorScheme = darkColorScheme()) {
                FoldmanScreen(
                    vm, windowLayout,
                    onPickFolder = { folderPicker.launch(null) },
                    onInstallApp = ::openStoreListing,
                    onAppleSignIn = { vm.appleSignInIntent()?.let(appleSignIn::launch) },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Notification access may have just been granted in Settings, or the streaming app started.
        vm.refresh()
    }

    private fun openStoreListing(source: Source) {
        val pkg = source.packageName ?: return
        val market = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$pkg"))
        runCatching { startActivity(market) }.onFailure {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$pkg")))
        }
    }
}
