package dev.korikosmos.healthdisplay.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
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
import dev.korikosmos.healthdisplay.PresenceService
import dev.korikosmos.healthdisplay.discord.DiscordPresence

@Composable
fun StatusScreen() {
    val ctx = LocalContext.current
    val status by PresenceService.status.collectAsStateWithLifecycle()
    val log by AppLog.lines.collectAsStateWithLifecycle()
    LazyColumn(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (status.running) OutlinedButton(onClick = { PresenceService.stop(ctx) }) { Text("Stop") }
                else Button(onClick = { PresenceService.start(ctx) }) { Text("Start") }
                OutlinedButton(onClick = { PresenceService.start(ctx, test = true) }) { Text("Test presence") }
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (!DiscordPresence.available) Text("Discord Social SDK missing from this build", color = MaterialTheme.colorScheme.error)
                if (status.problem.isNotEmpty()) Text(status.problem, color = MaterialTheme.colorScheme.error)
                Text("Discord: ${DiscordPresence.lastResult}")
                Text(DiscordPresence.discordAppStatus(ctx), style = MaterialTheme.typography.bodySmall)
                if (status.pulsoid.isNotEmpty()) Text("Pulsoid: ${status.pulsoid}")
                if (status.lastPush.isNotEmpty()) Text("Last push: ${status.lastPush}")
                if (status.server.isNotEmpty()) Text("Server: ${status.server}")
            }
        }
        status.presence?.let { p ->
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text("Playing", style = MaterialTheme.typography.labelSmall)
                        if (p.details.isNotEmpty()) Text(p.details, style = MaterialTheme.typography.titleSmall)
                        if (p.state.isNotEmpty()) Text(p.state)
                        if (p.largeText.isNotEmpty()) Text("Image hover: ${p.largeText}", style = MaterialTheme.typography.bodySmall)
                    }
                }
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
