package dev.korikosmos.healthdisplay

import android.content.Context

/** SPIKE-only settings; replaced by the DataStore config. */
object Prefs {
    private fun sp(ctx: Context) = ctx.getSharedPreferences("spike", Context.MODE_PRIVATE)
    fun appId(ctx: Context): Long? = sp(ctx).getString("app_id", "")?.trim()?.toLongOrNull()
    fun appIdText(ctx: Context): String = sp(ctx).getString("app_id", "") ?: ""
    fun setAppId(ctx: Context, v: String) = sp(ctx).edit().putString("app_id", v.trim()).apply()
}
