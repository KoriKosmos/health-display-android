# Health Display for Android

Android companion for [Health Display](https://github.com/KoriKosmos/health-display).
The server is always on and owns the Discord Rich Presence. This app collects
the phone's health data and sends it to the server, which shows it in the
presence with the layout from its own `config.env`. When the phone stops
sending, the server falls back to its own sources.

What the phone sends:

- **Heart rate:** Pulsoid real-time WebSocket, with REST polling while the
  socket is down. Optional: without a Pulsoid token, only stats are sent.
- **Heart-rate fallback:** when Pulsoid has no live reading, the newest Health
  Connect heart-rate sample from any device is sent as `{bpm}` = `~72`, with
  `{bpm_age}` ("8 min ago") and `{bpm_src}`.
  - It must be at most `HR_FALLBACK_MAX_AGE` seconds old (default 900; `0` =
    off), and is checked every `HR_FALLBACK_INTERVAL` seconds (default 60).
  - Newest wins; `SOURCE_HR` (else `SOURCE_DEFAULT`) breaks ties within 60 s.
- **Daily stats:** Google Health API first (same numbers as the Google Health
  app). Health Connect fills the gaps and provides stats Google doesn't have
  (`{mindful}`).

How it sends: a foreground service (`PushService`) posts the values every
`UPDATE_INTERVAL` seconds, and immediately when the heart rate goes live or
drops out. Wire format and server behaviour: [docs/server-push.md](docs/server-push.md).

The service uses foreground-service type `specialUse`. `dataSync` is capped at
6 hours a day on Android 15+ and can't start at boot. `health` would require a
granted body-sensor or heart-rate permission before every start.

## Build

Needs JDK 21 and an Android SDK with platform 37.

```sh
echo "sdk.dir=$HOME/opt/android-sdk" > local.properties
./gradlew testDebugUnitTest assembleDebug
# app/build/outputs/apk/debug/app-debug.apk
```

## Setup

1. Settings → Import config.env: paste the server's `config.env`.
   - The phone uses `PUSH_URL`, `PUSH_TOKEN`, `PULSOID_TOKEN`, the
     `SOURCE_*`, `HR_FALLBACK_*` and interval keys.
   - It ignores the presence layout and Discord keys (`DISCORD_APP_ID`,
     `DETAILS`, `STATE`, `IMG_*`) and the server's Google OAuth client.
   - Tokens are stored encrypted with an Android Keystore key.
2. Health tab: Sign in to Google Health, and grant Health Connect access.
3. Status tab: Start. Status shows the result of the last push (HTTP code and
   time). The service restarts at boot if it was running.

Phone-only keys: `GOOGLE_HEALTH` (set by signing in), `GOOGLE_TOTALS`,
`STATS_INTERVAL`.

### Google Health (main source)

Sign-in goes through Google Play services. It needs an **Android** OAuth
client in the same Google Cloud project as the server's, with package
`dev.korikosmos.healthdisplay` and the SHA-1 of the signing key
(`keytool -list -v -keystore ~/.android/debug.keystore -storepass android`).
No client secret is stored on the phone. If the project's OAuth consent screen
is in Testing mode, Google expires the sign-in after 7 days.

- **Today's totals** (steps, distance, calories, active kcal, active minutes,
  active zone minutes, floors) are Google's daily rollups for `GOOGLE_TOTALS`.
  - The default, `all-sources`, is watch and phone merged and de-duplicated by
    Google: the numbers the Google Health app shows.
  - `google-wearables` is the watch only.
- **Overnight stats, sleep, workout, weight, food, glucose, ECG** follow the
  server's rules:
  - the most recent day or night from any source wins;
  - `SOURCE_DEFAULT` / `SOURCE_<GROUP>` break ties;
  - values older than `STAT_MAX_AGE_DAYS` are ignored.
- **Refresh:** every `GOOGLE_INTERVAL` seconds (default 300). After three
  failed refreshes (offline), Health Connect values are used instead.

### Health Connect (fallback)

Used for anything Google Health doesn't return, for `{mindful}` (today's
mindfulness minutes) and for the heart-rate fallback. Refreshed every
`STATS_INTERVAL` seconds (default 900).

- **Today's totals:** total per data feed (app + device); the feed with the
  highest count wins. Never summed across feeds.
- **Overnight stats** and **sleep:** the most recent night from any source.
  - Sleep sessions ending within 4 h of each other count as the same night.
  - HRV, SpO₂ and respiration only count readings taken during a recorded
    sleep.
- **Rings** (Oura, Ultrahuman) only show up, in either source, if their app
  writes to Health Connect.
- **Heart-rate fallback:** only apps that write heart rate to Health Connect
  count, e.g. Ultrahuman, or Oura with daytime heart rate on. Health → Probe
  Health Connect shows which apps write heart rate and how often.

### Not available on the phone

App-specific scores aren't in Google Health or Health Connect. The server gets
these from the Oura and Ultrahuman APIs and merges them under the phone's
values:

- Oura: `{sleep_score}` `{readiness}` `{stress}` `{stress_high}`
  `{recovery_high}` `{resilience}` `{activity_score}`.
- Ultrahuman: `{recovery}` `{movement}`.

Health → Current stats shows each value with its source. Probe Health Connect
lists every record type with the apps and devices that wrote it over the last
7 days, plus heart rate over the last 24 h.
