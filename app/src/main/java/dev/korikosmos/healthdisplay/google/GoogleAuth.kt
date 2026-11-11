package dev.korikosmos.healthdisplay.google

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.tasks.await

/**
 * Google sign-in through Play services (no client secret on the phone). Google matches the
 * app to the Android OAuth client registered with this package name and signing key.
 */
object GoogleAuth {
    /** Read-only scopes for the data the stats use. */
    val SCOPES = listOf("activity_and_fitness", "health_metrics_and_measurements", "sleep", "nutrition", "ecg")
        .map { "https://www.googleapis.com/auth/googlehealth.$it.readonly" }

    private fun request() = AuthorizationRequest.builder().setRequestedScopes(SCOPES.map(::Scope)).build()

    sealed interface Result {
        data class Token(val accessToken: String) : Result
        /** The user has to act (consent / choose account): launch from an Activity. */
        data class NeedsUser(val intent: PendingIntent) : Result
    }

    suspend fun authorize(context: Context): Result {
        val r = Identity.getAuthorizationClient(context).authorize(request()).await()
        val pi = r.pendingIntent
        return when {
            r.hasResolution() && pi != null -> Result.NeedsUser(pi)
            r.accessToken != null -> Result.Token(r.accessToken!!)
            else -> throw GoogleAuthException("no access token returned")
        }
    }

    /** Access token without any UI, for the background service. */
    suspend fun token(context: Context): String = when (val r = authorize(context)) {
        is Result.Token -> r.accessToken
        is Result.NeedsUser -> throw GoogleAuthException("Google Health sign-in needed: open the app, Health tab")
    }

    /** Result of the consent screen launched from [Result.NeedsUser]. */
    fun tokenFromIntent(context: Context, data: Intent?): String? =
        runCatching { Identity.getAuthorizationClient(context).getAuthorizationResultFromIntent(data).accessToken }.getOrNull()
}
