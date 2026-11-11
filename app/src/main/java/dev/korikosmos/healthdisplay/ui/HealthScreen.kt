package dev.korikosmos.healthdisplay.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import dev.korikosmos.healthdisplay.config.ConfigStore
import dev.korikosmos.healthdisplay.health.HealthStats
import kotlinx.coroutines.launch

@Composable
fun HealthScreen(store: ConfigStore) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val sdk = remember { HealthConnectClient.getSdkStatus(ctx) }
    if (sdk != HealthConnectClient.SDK_AVAILABLE) {
        Text("Health Connect is not available on this device (status $sdk).", Modifier.padding(16.dp)); return
    }
    val client = remember { HealthConnectClient.getOrCreate(ctx) }
    var granted by remember { mutableIntStateOf(-1) }
    var output by remember { mutableStateOf("") }
    val request = rememberLauncherForActivityResult(PermissionController.createRequestPermissionResultContract()) {
        granted = it.intersect(HealthStats.PERMISSIONS).size
    }
    LaunchedEffect(Unit) {
        granted = client.permissionController.getGrantedPermissions().intersect(HealthStats.PERMISSIONS).size
    }
    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Permissions granted: ${if (granted < 0) "…" else granted} of ${HealthStats.PERMISSIONS.size}")
        Button(onClick = { request.launch(HealthStats.PERMISSIONS) }) { Text("Grant Health Connect access") }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                scope.launch {
                    output = "Reading…"
                    val hs = HealthStats(ctx, store.load())
                    val v = hs.refresh()
                    output = v.entries.sortedBy { it.key }.joinToString("\n") { "{${it.key}} = ${it.value}" }.ifEmpty { "No stats" } +
                        "\n\nHow today's totals were picked:\n" + hs.todayBreakdown()
                }
            }) { Text("Current stats") }
            OutlinedButton(onClick = {
                scope.launch { output = "Probing…"; output = HealthStats(ctx, store.load()).probe() }
            }) { Text("Probe sources (7 days)") }
        }
        Text(
            "Probe lists every record type with the apps and devices that wrote it, " +
                "including mindfulness sessions. Share the output to check source naming.",
            style = MaterialTheme.typography.bodySmall,
        )
        SelectionContainer { Text(output, fontFamily = FontFamily.Monospace, fontSize = 11.sp) }
    }
}
