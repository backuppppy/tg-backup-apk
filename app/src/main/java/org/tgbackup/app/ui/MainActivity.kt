@file:OptIn(ExperimentalMaterial3Api::class)

package org.tgbackup.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.core.content.ContextCompat
import kotlinx.coroutines.launch
import org.tgbackup.app.App
import org.tgbackup.app.BuildConfig

/** Shows a short message at the bottom of the screen. */
val LocalToast = staticCompositionLocalOf<(String) -> Unit> { {} }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        App.instance.jobs.restore()
        setContent { AppRoot() }
    }
}

private val colors = darkColorScheme(
    primary = Color(0xFF5AA9F0),
    secondary = Color(0xFF7FD1AE),
    background = Color(0xFF0F1720),
    surface = Color(0xFF16212D),
    surfaceVariant = Color(0xFF1E2C3A),
    error = Color(0xFFF07178),
)

@Composable
private fun AppRoot() {
    MaterialTheme(colorScheme = colors) {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
            val snackbar = remember { SnackbarHostState() }
            val scope = rememberCoroutineScope()
            val toast: (String) -> Unit = { msg -> scope.launch { snackbar.showSnackbar(msg) } }
            var tab by rememberSaveable { mutableIntStateOf(0) }
            CompositionLocalProvider(LocalToast provides toast) {
                Scaffold(
                    topBar = { TopAppBar(title = { Text("TG Backup  v${BuildConfig.VERSION_NAME}") }) },
                    snackbarHost = { SnackbarHost(snackbar) },
                    bottomBar = {
                        NavigationBar {
                            Tab(tab == 0, "משימות", Icons.Filled.PlaylistPlay) { tab = 0 }
                            Tab(tab == 1, "חשבונות", Icons.Filled.Person) { tab = 1 }
                            Tab(tab == 2, "לוג", Icons.Filled.Terminal) { tab = 2 }
                            Tab(tab == 3, "הגדרות", Icons.Filled.Info) { tab = 3 }
                        }
                    },
                ) { padding ->
                    Box(Modifier.padding(padding)) {
                        when (tab) {
                            0 -> JobsScreen()
                            1 -> AccountsScreen()
                            2 -> LogScreen()
                            else -> SettingsScreen()
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.Tab(
    selected: Boolean,
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
) {
    NavigationBarItem(
        selected = selected,
        onClick = onClick,
        icon = { Icon(icon, contentDescription = label) },
        label = { Text(label) },
    )
}
