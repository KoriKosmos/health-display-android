package dev.korikosmos.healthdisplay.health

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Shown by Health Connect when the user asks why the app wants health data. */
class PermissionsRationaleActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.safeDrawingPadding().padding(16.dp)) {
                        Text("Why Health Display reads health data", style = MaterialTheme.typography.titleLarge)
                        Text(
                            "Health Display reads your daily stats (steps, activity, sleep, resting heart " +
                                "rate, HRV and similar) from Health Connect only to show them in your own " +
                                "Discord Rich Presence, using the layout you configure. The data stays on " +
                                "this phone except for the text you choose to show on Discord. Nothing is " +
                                "written to Health Connect, stored elsewhere or shared with anyone else.",
                            Modifier.padding(top = 12.dp),
                        )
                    }
                }
            }
        }
    }
}
