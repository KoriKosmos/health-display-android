package dev.korikosmos.healthdisplay

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.korikosmos.healthdisplay.discord.DiscordPresence

class MainActivity : ComponentActivity() {
    private val askNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DiscordPresence.attachActivity(this)
        askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(Modifier.fillMaxSize()) { SpikeScreen() }
            }
        }
    }

    @androidx.compose.runtime.Composable
    private fun SpikeScreen() {
        var appId by remember { mutableStateOf(Prefs.appIdText(this)) }
        val log by AppLog.lines.collectAsStateWithLifecycle()
        Column(
            Modifier.safeDrawingPadding().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Health Display: presence spike", style = MaterialTheme.typography.titleLarge)
            Text(
                if (DiscordPresence.available) "Discord Social SDK loaded"
                else "Discord Social SDK missing from this build",
            )
            OutlinedTextField(
                value = appId,
                onValueChange = { appId = it; Prefs.setAppId(this@MainActivity, it) },
                label = { Text("Discord application ID") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { PresenceService.start(this@MainActivity) }) { Text("Start") }
                OutlinedButton(onClick = { PresenceService.stop(this@MainActivity) }) { Text("Stop") }
            }
            LazyColumn(Modifier.fillMaxWidth()) {
                items(log.reversed()) {
                    Text(it, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                }
            }
        }
    }
}
