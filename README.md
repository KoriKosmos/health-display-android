# Health Display for Android

Android app that shows live heart rate and daily health stats as Discord Rich
Presence, straight from the phone. Android counterpart of
[Health Display](https://github.com/KoriKosmos/health-display); same presence
layout, placeholders and config.env keys.

- **Live BPM:** Pulsoid real-time WebSocket (REST polling while it's down).
  When Pulsoid has no live reading, the newest Health Connect heart-rate sample
  from any device (at most `HR_FALLBACK_MAX_AGE` seconds old, default 900;
  `0` = off; checked every `HR_FALLBACK_INTERVAL`, default 60) is shown as
  `{bpm}` = `~72` with `{bpm_age}` ("8 min ago") and `{bpm_src}`. Newest wins;
  `SOURCE_HR` (else `SOURCE_DEFAULT`) breaks ties within 60 s. Only apps that
  write heart rate to Health Connect count (Ultrahuman, Oura with daytime heart
  rate on; the Google Health app doesn't export the Pixel Watch's).
- **Daily stats:** Google Health API (main source, same numbers as the Google
  Health app), with Health Connect filling the gaps and providing stats Google
  doesn't have ({mindful})
- **Discord:** official Discord Social SDK Rich Presence, no auth (needs the
  Discord app installed and signed in). No user token, no selfbot.

Status: working on a Pixel 9 Pro XL with ShiggyCord (a Discord client mod; the
app uses whichever installed Discord build offers the presence service).

## Discord Social SDK

The SDK can only be downloaded from the Discord developer portal and isn't
redistributable, so it's not in this repo.

1. <https://discord.com/developers/applications>, open the application whose ID
   you use as `DISCORD_APP_ID`.
2. **Social SDK → Getting Started**: enable the Social SDK for the app.
3. **Social SDK → Downloads**: download the **Android** build, version
   1.10.19337 or newer (1.10 is the first with unauthenticated Rich Presence
   on Android).
4. Put `discord_partner_sdk.aar` (or the unpacked download containing it)
   under `third_party/discord_social_sdk/`. That folder is gitignored.

Without the SDK the app still builds, with presence disabled.

## Build

Needs JDK 21 and an Android SDK with platform 37, NDK 28.2.13676358 and
CMake 3.31.6.

```sh
echo "sdk.dir=$HOME/opt/android-sdk" > local.properties
./gradlew testDebugUnitTest assembleDebug
# app/build/outputs/apk/debug/app-debug.apk
```

## Settings

All config.env keys from the server work the same; paste the whole file into
Settings → Import config.env (the server's Google OAuth client keys are ignored). The Pulsoid token
is stored encrypted with an Android Keystore key.

Phone-only keys: `GOOGLE_HEALTH` (set by signing in), `GOOGLE_TOTALS`,
`STATS_INTERVAL`. The server's `GOOGLE_CLIENT_ID`/`GOOGLE_CLIENT_SECRET` and
`ROLLUP_SOURCES` are ignored on import.

### Google Health (main source)

Health tab → Sign in to Google Health. Sign-in goes through Google Play
services; it needs an **Android** OAuth client in the same Google Cloud project
as the server's, with package `dev.korikosmos.healthdisplay` and the SHA-1 of
the signing key (`keytool -list -v -keystore ~/.android/debug.keystore
-storepass android`). No client secret is stored on the phone. If the project's
OAuth consent screen is in Testing mode, Google expires the sign-in after 7
days.

- **Today's totals** (steps, distance, calories, active kcal, active minutes,
  active zone minutes, floors): Google's daily rollups for `GOOGLE_TOTALS`,
  default `all-sources` (watch and phone merged and de-duplicated by Google:
  the numbers the Google Health app shows). `google-wearables` = watch only.
- **Overnight stats, sleep, workout, weight, food, glucose, ECG**: as on the
  server: the most recent day or night from any source, `SOURCE_DEFAULT` /
  `SOURCE_<GROUP>` breaking ties, hidden when older than `STAT_MAX_AGE_DAYS`.
- Refreshed every `GOOGLE_INTERVAL` seconds (default 300). After three failed
  refreshes (offline), Health Connect values are used instead.

### Health Connect (fallback)

Used for anything Google Health doesn't return, and for `{mindful}` (today's
mindfulness minutes). Refreshed every `STATS_INTERVAL` seconds (default 900).

- **Today's totals**: total per data feed (app + device), the feed with the
  highest count wins. Never summed across feeds.
- **Overnight stats** and **sleep**: the most recent night from any source;
  sleep sessions ending within 4 h of each other count as the same night. HRV,
  SpO₂ and respiration only count readings taken during a recorded sleep.
- Rings (Oura, Ultrahuman) only show up, in either source, if their app writes
  to Health Connect.

### Not available

`{azm}`, `{fitness}` and `{ecg}` need Google Health. App-specific scores such
as sleep score, stress and readiness aren't available from either source.

Health → Current stats shows each value with its source; Probe Health Connect
lists every record type with the apps and devices that wrote it over the last
7 days.
