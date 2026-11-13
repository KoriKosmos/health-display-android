package dev.korikosmos.healthdisplay.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.korikosmos.healthdisplay.config.ConfigStore
import dev.korikosmos.healthdisplay.presence.Config
import kotlinx.coroutines.launch

private data class Field(val key: String, val label: String, val help: String = "", val secret: Boolean = false, val number: Boolean = false)

private val FIELDS = listOf(
    Field("PULSOID_TOKEN", "Pulsoid token", "scope data:heart_rate:read", secret = true),
    Field("DISCORD_APP_ID", "Discord application ID", "the app's name shows as \"Playing <name>\"", number = true),
    Field("DETAILS", "First line (DETAILS)"),
    Field("STATE", "Second line (STATE)"),
    Field("IMG_HOVER", "Image hover text (IMG_HOVER)"),
    Field("IMAGE", "BPM image tile (on/off)"),
    Field("IMG_TEXT", "Tile text (IMG_TEXT)"),
    Field("IMG_BG", "Tile background (hex)"),
    Field("IMG_FG", "Tile text colour (hex)"),
    Field("SOURCE_DEFAULT", "Source preference (ties only)", "watch, oura, ultrahuman, samsung, phone, <package>, *"),
    Field("STAT_MAX_AGE_DAYS", "Hide overnight stats older than (days)"),
    Field("POLL_INTERVAL", "Pulsoid poll fallback (s)", number = true),
    Field("UPDATE_INTERVAL", "Min gap between Discord updates (s)", number = true),
    Field("STALE_AFTER", "Clear after no heart rate for (s)", number = true),
    Field("STATS_INTERVAL", "Health Connect refresh (s)", number = true),
    Field("PUSH_URL", "Server push URL (optional)", "e.g. https://health.korikosmos.dev/push; empty = off"),
    Field("PUSH_TOKEN", "Server push token", "same PUSH_TOKEN as the server's config.env", secret = true),
)

private const val PLACEHOLDERS =
    "Placeholders: {bpm} {min} {max} {avg} · {steps} {distance} {calories} {active_kcal} {active} {active_mv} " +
        "{floors} {kcal_in} · {rhr} {hrv} {spo2} {resp} {temp_delta} {vo2max} · {sleep} {sleep_eff} {deep} {rem} " +
        "{light} {awake} · {workout} {workout_time} {weight} {glucose} {glucose_mgdl} · {steps_src} {sleep_src} " +
        "{rhr_src} {hrv_src} · {azm} {fitness} {ecg} (Google Health only) · {mindful} (Health Connect only). " +
        "A ' · ' segment is left out when any of its values is missing."

@Composable
fun SettingsScreen(store: ConfigStore) {
    val scope = rememberCoroutineScope()
    val edits = remember { mutableStateMapOf<String, String>() }
    var loaded by remember { mutableStateOf<Config?>(null) }
    var envText by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        val c = store.load(); loaded = c
        FIELDS.forEach { edits[it.key] = c[it.key] }
    }
    val cfg = loaded ?: return
    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FIELDS.forEach { f ->
            OutlinedTextField(
                value = edits[f.key] ?: "",
                onValueChange = { edits[f.key] = it },
                label = { Text(f.label) },
                supportingText = if (f.help.isNotEmpty()) ({ Text(f.help) }) else null,
                singleLine = true,
                visualTransformation = if (f.secret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
                keyboardOptions = if (f.number) KeyboardOptions(keyboardType = KeyboardType.Number) else KeyboardOptions.Default,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Text(PLACEHOLDERS, style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                scope.launch {
                    // Values equal to the default are stored as "unset", so defaults can change later.
                    store.save(edits.mapValues { (k, v) -> if (v == Config.DEFAULTS[k]) "" else v })
                    loaded = store.load(); message = "Saved"
                }
            }) { Text("Save") }
            OutlinedButton(onClick = { FIELDS.forEach { edits[it.key] = Config.DEFAULTS[it.key] ?: "" }; message = "Defaults loaded, not saved" }) {
                Text("Defaults")
            }
        }
        if (message.isNotEmpty()) Text(message)
        val overrides = cfg.values.keys.filter { it.startsWith("SOURCE_") && it != "SOURCE_DEFAULT" }
        if (overrides.isNotEmpty()) Text("Per-stat overrides: " + overrides.joinToString { "$it=${cfg[it]}" }, style = MaterialTheme.typography.bodySmall)

        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        Text("Import config.env", style = MaterialTheme.typography.titleSmall)
        Text(
            "Paste the server's config.env (or any KEY=VALUE lines, e.g. SOURCE_SLEEP=oura,watch,*). " +
                "Google OAuth keys are ignored.",
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedTextField(
            value = envText, onValueChange = { envText = it },
            modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp),
        )
        Button(onClick = {
            scope.launch {
                val n = store.importEnv(envText)
                val c = store.load(); loaded = c
                FIELDS.forEach { edits[it.key] = c[it.key] }
                envText = ""; message = "Imported $n settings"
            }
        }, enabled = envText.isNotBlank()) { Text("Import") }
    }
}
