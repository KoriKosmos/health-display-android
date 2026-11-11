package dev.korikosmos.healthdisplay.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
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
import dev.korikosmos.healthdisplay.google.GoogleAuth
import dev.korikosmos.healthdisplay.google.GoogleHealthApi
import dev.korikosmos.healthdisplay.google.GoogleStats
import dev.korikosmos.healthdisplay.health.HealthStats
import dev.korikosmos.healthdisplay.presence.StatsMerge
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun HealthScreen(store: ConfigStore) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var output by remember { mutableStateOf("") }
    var googleOn by remember { mutableStateOf(false) }
    var googleMsg by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { googleOn = store.load().googleOn }

    val consent = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { res ->
        scope.launch {
            if (GoogleAuth.tokenFromIntent(ctx, res.data) != null) {
                store.save(mapOf("GOOGLE_HEALTH" to "on")); googleOn = true; googleMsg = "Signed in"
            } else googleMsg = "Sign-in cancelled or failed"
        }
    }

    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Google Health (main source)", style = MaterialTheme.typography.titleSmall)
        Text(
            if (googleOn) "On: today's totals, sleep and overnight stats come from your Google Health account; " +
                "Health Connect fills any gaps."
            else "Off: using Health Connect only.",
            style = MaterialTheme.typography.bodySmall,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                scope.launch {
                    googleMsg = "Signing in…"
                    try {
                        when (val r = GoogleAuth.authorize(ctx)) {
                            is GoogleAuth.Result.Token -> { store.save(mapOf("GOOGLE_HEALTH" to "on")); googleOn = true; googleMsg = "Signed in" }
                            is GoogleAuth.Result.NeedsUser -> consent.launch(IntentSenderRequest.Builder(r.intent).build())
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        googleMsg = "Sign-in failed: $e"
                    }
                }
            }) { Text(if (googleOn) "Sign in again" else "Sign in to Google Health") }
            if (googleOn) OutlinedButton(onClick = {
                scope.launch { store.save(mapOf("GOOGLE_HEALTH" to "")); googleOn = false; googleMsg = "Google Health off" }
            }) { Text("Turn off") }
        }
        if (googleMsg.isNotEmpty()) Text(googleMsg, style = MaterialTheme.typography.bodySmall)

        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        HealthConnectSection()

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                scope.launch {
                    output = "Reading…"
                    val cfg = store.load()
                    val google = if (cfg.googleOn) try {
                        GoogleStats(GoogleHealthApi { GoogleAuth.token(ctx) }, cfg).refresh()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        output = "Google Health: $e\n"; emptyMap()
                    } else emptyMap()
                    val hc = if (HealthConnectClient.getSdkStatus(ctx) == HealthConnectClient.SDK_AVAILABLE) HealthStats(ctx, cfg) else null
                    val hcValues = hc?.refresh().orEmpty()
                    val merged = StatsMerge.merge(google, hcValues)
                    output = (if (output.startsWith("Google")) output else "") +
                        merged.entries.sortedBy { it.key }.joinToString("\n") { (k, v) ->
                            "{$k} = $v   (${if (k in google || k.removeSuffix("_src") in google) "Google Health" else "Health Connect"})"
                        }.ifEmpty { "No stats" } +
                        (hc?.let { "\n\nHealth Connect, how today's totals were picked:\n" + it.todayBreakdown() } ?: "")
                }
            }) { Text("Current stats") }
            OutlinedButton(onClick = {
                scope.launch { output = "Probing…"; output = HealthStats(ctx, store.load()).probe() }
            }) { Text("Probe Health Connect") }
        }
        SelectionContainer { Text(output, fontFamily = FontFamily.Monospace, fontSize = 11.sp) }
    }
}

@Composable
private fun HealthConnectSection() {
    val ctx = LocalContext.current
    val sdk = remember { HealthConnectClient.getSdkStatus(ctx) }
    Text("Health Connect (fallback, and stats Google Health doesn't have)", style = MaterialTheme.typography.titleSmall)
    if (sdk != HealthConnectClient.SDK_AVAILABLE) {
        Text("Health Connect is not available on this device (status $sdk)."); return
    }
    val client = remember { HealthConnectClient.getOrCreate(ctx) }
    var granted by remember { mutableIntStateOf(-1) }
    val request = rememberLauncherForActivityResult(PermissionController.createRequestPermissionResultContract()) {
        granted = it.intersect(HealthStats.PERMISSIONS).size
    }
    LaunchedEffect(Unit) {
        granted = client.permissionController.getGrantedPermissions().intersect(HealthStats.PERMISSIONS).size
    }
    Text("Permissions granted: ${if (granted < 0) "…" else granted} of ${HealthStats.PERMISSIONS.size}")
    Button(onClick = { request.launch(HealthStats.PERMISSIONS) }) { Text("Grant Health Connect access") }
}
