package dev.korikosmos.healthdisplay

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import dev.korikosmos.healthdisplay.config.ConfigStore
import dev.korikosmos.healthdisplay.ui.HealthScreen
import dev.korikosmos.healthdisplay.ui.SettingsScreen
import dev.korikosmos.healthdisplay.ui.StatusScreen

class MainActivity : ComponentActivity() {
    private val askNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        val store = ConfigStore(this)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(Modifier.fillMaxSize()) {
                    var tab by rememberSaveable { mutableIntStateOf(0) }
                    Column(Modifier.safeDrawingPadding()) {
                        PrimaryTabRow(selectedTabIndex = tab) {
                            listOf("Status", "Settings", "Health").forEachIndexed { i, t ->
                                Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t) })
                            }
                        }
                        when (tab) {
                            0 -> StatusScreen()
                            1 -> SettingsScreen(store)
                            else -> HealthScreen(store)
                        }
                    }
                }
            }
        }
    }
}
