package lab.luxi.tingmo

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.AutoFixHigh
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.ModelTraining
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import lab.luxi.tingmo.ui.navigation.Route
import lab.luxi.tingmo.ui.screens.AboutScreen
import lab.luxi.tingmo.ui.screens.AiSettingsScreen
import lab.luxi.tingmo.ui.screens.CorrectionsScreen
import lab.luxi.tingmo.ui.screens.ModelsScreen
import lab.luxi.tingmo.ui.screens.RecordingsScreen
import lab.luxi.tingmo.ui.screens.TranscribeScreen
import lab.luxi.tingmo.ui.theme.TingmoTheme

class MainActivity : ComponentActivity() {
    private val micPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* checked at start */ }

    private val notifPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        micPermission.launch(Manifest.permission.RECORD_AUDIO)
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        val app = application as TingmoApp
        setContent {
            TingmoTheme {
                TingmoRoot(app.container)
            }
        }
    }
}

@Composable
private fun TingmoRoot(container: AppContainer) {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val current = backStack?.destination?.route

    Scaffold(
        bottomBar = {
            NavigationBar {
                Route.bottom.forEach { route ->
                    NavigationBarItem(
                        selected = current == route.path,
                        onClick = {
                            nav.navigate(route.path) {
                                popUpTo(nav.graph.startDestinationId) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(route.icon(), contentDescription = route.label) },
                        label = { Text(route.label) },
                    )
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = nav,
            startDestination = Route.Transcribe.path,
            modifier = Modifier.padding(padding),
        ) {
            composable(Route.Transcribe.path) { TranscribeScreen(container) }
            composable(Route.Recordings.path) { RecordingsScreen(container) }
            composable(Route.Models.path) { ModelsScreen(container) }
            composable(Route.Corrections.path) { CorrectionsScreen(container) }
            composable(Route.Ai.path) { AiSettingsScreen(container) }
            composable(Route.About.path) { AboutScreen() }
        }
    }
}

private fun Route.icon(): ImageVector = when (this) {
    Route.Transcribe -> Icons.Outlined.Mic
    Route.Recordings -> Icons.Outlined.LibraryMusic
    Route.Models -> Icons.Outlined.ModelTraining
    Route.Corrections -> Icons.Outlined.AutoFixHigh
    Route.Ai -> Icons.Outlined.AutoAwesome
    Route.About -> Icons.Outlined.Info
}
