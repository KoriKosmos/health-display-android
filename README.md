# Health Display for Android

Android app that shows live heart rate and daily health stats as Discord Rich
Presence, straight from the phone. Android counterpart of
[Health Display](https://github.com/KoriKosmos/health-display); same presence
layout, placeholders and config.env keys.

- **Live BPM:** Pulsoid real-time WebSocket (REST polling while it's down)
- **Daily stats:** Health Connect: every app/device that writes there (Pixel
  Watch, Oura, Ultrahuman, phone, anything new) is a fallback for the others
- **Discord:** official Discord Social SDK Rich Presence, no auth (needs the
  Discord app installed and signed in). No user token, no selfbot.

Status: feature-complete first version. The presence path has not been tested
on a device yet.

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
Settings → Import config.env (Google OAuth keys are ignored). The Pulsoid token
is stored encrypted with an Android Keystore key.

Phone-only key: `STATS_INTERVAL` (seconds between Health Connect reads,
default 900).

### Source selection

Same rules as the server:

- **Today's totals** (steps, distance, active minutes, active/total kcal,
  floors, food kcal): total per data feed (app + device), the feed with the
  highest count wins. Never summed across feeds.
- **Overnight stats** (resting HR, HRV, SpO₂, respiration, skin temperature)
  and **sleep**: the most recent night from any source. Sleep sessions ending
  within 4 h of each other count as the same night. HRV, SpO₂ and respiration
  only count readings taken during a recorded sleep.
- `SOURCE_DEFAULT=watch,oura,ultrahuman,*,samsung,phone` and
  `SOURCE_<GROUP>` only break ties. `*` covers any other app.
- Overnight stats older than `STAT_MAX_AGE_DAYS` are hidden.

### Not available from Health Connect

Health Connect has no data types for these, so they're never shown (their
` · ` segment is dropped): `{azm}` (active zone minutes), `{fitness}` (cardio
fitness level), `{ecg}`, and app-specific scores such as sleep score, stress
and readiness. Health Connect only records moderate/vigorous activity, so
`{active}` equals `{active_mv}`.

Health → Probe sources lists every record type with the apps and devices that
wrote it over the last 7 days.
