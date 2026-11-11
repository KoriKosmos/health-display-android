package dev.korikosmos.healthdisplay.config

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.korikosmos.healthdisplay.presence.Config
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore("config")

/**
 * Settings in DataStore, one entry per config.env key. Secrets (PULSOID_TOKEN) are stored
 * encrypted with a Keystore key, never in plain text.
 */
class ConfigStore(private val context: Context) {
    val config: Flow<Config> = context.dataStore.data.map(::toConfig)

    /** Whether the user left the presence service running (restarted at boot). */
    val running: Flow<Boolean> = context.dataStore.data.map { it[RUNNING] ?: false }

    suspend fun load(): Config = config.first()

    /** Set keys; an empty value resets a key to its default. */
    suspend fun save(changes: Map<String, String>) {
        context.dataStore.edit { p ->
            for ((k, v) in changes) {
                val key = if (k in SECRETS) stringPreferencesKey("$k$ENC") else stringPreferencesKey(k)
                if (v.isEmpty()) p.remove(key)
                else p[key] = if (k in SECRETS) TokenCipher.encrypt(v) else v
            }
        }
    }

    /** Import a pasted config.env; keys only the server uses (Google OAuth etc.) are skipped. */
    suspend fun importEnv(text: String): Int {
        val values = Config.parseEnv(text).filterKeys { k -> SERVER_ONLY.none { k.startsWith(it) } }
        save(values)
        return values.size
    }

    suspend fun setRunning(on: Boolean) = context.dataStore.edit { it[RUNNING] = on }

    private fun toConfig(p: Preferences): Config {
        val values = mutableMapOf<String, String>()
        for ((k, v) in p.asMap()) {
            val name = k.name
            when {
                k == RUNNING -> Unit
                name.endsWith(ENC) -> TokenCipher.decrypt(v as String)?.let { values[name.removeSuffix(ENC)] = it }
                v is String -> values[name] = v
            }
        }
        return Config(values)
    }

    companion object {
        private const val ENC = "__enc"
        private val RUNNING = booleanPreferencesKey("__running")
        val SECRETS = setOf("PULSOID_TOKEN")
        private val SERVER_ONLY = listOf("GOOGLE_", "ROLLUP_SOURCES")
    }
}
