package dev.korikosmos.healthdisplay

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/** In-memory log shown in the app (last 200 lines), mirrored to logcat. */
object AppLog {
    private val fmt = DateTimeFormatter.ofPattern("HH:mm:ss")
    private val _lines = MutableStateFlow<List<String>>(emptyList())
    val lines: StateFlow<List<String>> = _lines

    fun log(tag: String, msg: String) {
        Log.i("HealthDisplay", "$tag: $msg")
        val line = "${LocalTime.now().format(fmt)} $tag: $msg"
        _lines.update { (it + line).takeLast(200) }
    }
}
