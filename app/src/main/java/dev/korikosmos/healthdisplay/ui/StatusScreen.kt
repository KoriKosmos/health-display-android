package dev.korikosmos.healthdisplay.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.korikosmos.healthdisplay.AppLog
import dev.korikosmos.healthdisplay.PushService

@Composable
fun StatusScreen() {
    val ctx = LocalContext.current
    val status by PushService.status.collectAsStateWithLifecycle()
    val log by AppLog.lines.collectAsStateWithLifecycle()
    LazyColumn(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (status.running) OutlinedButton(onClick = { PushService.stop(ctx) }) { Text("Stop") }
                else Button(onClick = { PushService.start(ctx) }) { Text("Start") }
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (status.problem.isNotEmpty()) Text(status.problem, color = MaterialTheme.colorScheme.error)
                Text("Server: ${status.server.ifEmpty { if (status.running) "no push yet" else "not running" }}")
                if (status.heartRate.isNotEmpty()) Text("Heart rate: ${status.heartRate}")
                if (status.pulsoid.isNotEmpty()) Text("Pulsoid: ${status.pulsoid}")
                Text(
                    "The Health Display server shows these values in your Discord presence.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        if (status.values.isNotEmpty()) item {
            Text(
                status.values.entries.sortedBy { it.key }.joinToString("  ") { "{${it.key}} ${it.value}" },
                style = MaterialTheme.typography.bodySmall,
            )
        }
        item { Text("Log", style = MaterialTheme.typography.titleSmall) }
        items(log.reversed()) { Text(it, fontFamily = FontFamily.Monospace, fontSize = 11.sp) }
    }
}
