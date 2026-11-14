# Server push: phone values → Health Display server

The [Health Display server](https://github.com/KoriKosmos/health-display) is
always on and owns the Discord Rich Presence. The Android app doesn't set a
presence itself. It collects the phone's data and sends it to the server:
live Pulsoid heart rate, the heart-rate fallback, and Google Health / Health
Connect stats.

Both sides are implemented: the phone in `push/ServerPush.kt` and
`PushService.kt`, the server in `health_display.py` (server commits 3dbe4d5,
b925423, da2cdd3, 751ac20).

## Configuration

Both apps read the same `config.env`, so importing the server's file on the
phone sets the phone up too. The phone ignores the presence keys in that file
(`DISCORD_APP_ID`, `DETAILS`, `STATE`, `IMG_*`).

| Key | Used by | Meaning |
|---|---|---|
| `PUSH_TOKEN` | both | Shared secret. Generate with `openssl rand -hex 32`. Push is off on both sides while empty. |
| `PUSH_URL` | phone | Where the phone posts, e.g. `https://health.korikosmos.dev/push`. Without it (or the token) the phone has nothing to do. |
| `PUSH_PORT` | server | Port the server listens on, default `8766`. |
| `PUSH_STALE_AFTER` | server | Seconds without a valid push before the phone counts as inactive, default `60`. |
| `UPDATE_INTERVAL` | phone | Seconds between pushes, default `15`. |

## Endpoint

`POST /push`

- Header `Authorization: Bearer <PUSH_TOKEN>`. Compare with
  `hmac.compare_digest`.
- `Content-Type: application/json`. Reject bodies over 64 KiB.

| Response | When |
|---|---|
| `204` | Accepted. |
| `400` | Not JSON, missing fields, or `v` is not `1`. |
| `401` | Token missing or wrong. |
| `404` | Any other path. |
| `405` | Any other method on `/push`. |

## Payload (v1)

```json
{
  "v": 1,
  "sent_at": 1762889400000,
  "live": true,
  "measured_at": 1762889398000,
  "values": {
    "bpm": "84", "min": "62", "avg": "79", "max": "112",
    "steps": "2,800", "steps_src": "google", "rhr": "64", "rhr_src": "watch",
    "sleep": "7h10", "azm": "13"
  }
}
```

- `sent_at`: the phone's clock when sent, Unix ms. For logging only; use the
  server's receive time for staleness, since the clocks may differ.
- `live`: whether the phone has a heart rate worth showing. That's either a
  live Pulsoid reading, or the phone's heart-rate fallback: a recent
  watch/ring sample from Health Connect, sent as `{bpm}` = `"~72"`.
- `measured_at`: Unix ms of that heart-rate reading: the latest Pulsoid
  reading, or the fallback sample's time (0 if none yet).
- `values`: placeholder name → display string, already formatted.
  - The names are the same as the server's placeholders, plus the phone-only
    `{mindful}`.
  - While the fallback is sent, `bpm` is `"~72"` and `bpm_age` / `bpm_src` are
    set, as on the server.
  - Missing keys simply have no value, so their ` · ` segments drop out as
    usual.

The phone pushes every `UPDATE_INTERVAL` seconds whether or not it's live; the
pushes double as the "phone is active" heartbeat. It also pushes immediately
when `live` changes.

## Server behaviour

1. Run a small HTTP listener (stdlib `http.server.ThreadingHTTPServer`, daemon
   thread) on `0.0.0.0:PUSH_PORT`, only when `PUSH_TOKEN` is set. Keep the
   latest valid payload and its receive time under a lock.
2. The phone is **active** while the last valid push arrived less than
   `PUSH_STALE_AFTER` seconds ago (server clock).
3. Active phone, `live` true: `values = {**own_stats, **payload["values"]}`.
   The phone wins on conflicts, and server-only values (e.g. Oura/Ultrahuman
   scores) stay visible (server commit da2cdd3). The BPM tile uses
   `values["bpm"]`.
4. Active phone, `live` false: the phone's values are still a data source and
   win over the server's own stats. Only its heart-rate keys (`bpm` `min`
   `avg` `max` `bpm_age` `bpm_src`) are dropped, so the server's own Pulsoid
   and heart-rate fallback decide the heart rate (server commit 751ac20).
5. Inactive phone (no push for `PUSH_STALE_AFTER`): the server uses only its
   own sources (Pulsoid, Google Health, Oura/Ultrahuman APIs, its heart-rate
   fallback).
6. All values are rendered with the server's own `DETAILS` / `STATE` /
   `IMG_HOVER` templates, and `timestamps.start` is the server's own start.
   `UPDATE_INTERVAL` and the unchanged-presence check apply as usual.
7. The server keeps polling its own sources while the phone is active, so
   falling back is instant.
8. Log transitions once: `phone: active (pushes from <ip>)` and
   `phone: inactive, using own sources`. Log rejected pushes once per cause.

## Deployment

- `compose.yaml`: publish the port, `"8766:8766"`.
- Public URL: `https://health.korikosmos.dev/push`. Cloudflare-proxied CNAME →
  Nginx Proxy Manager (host 24) → `192.168.0.57:8766`.

## Test

```sh
curl -i -X POST https://health.korikosmos.dev/push \
  -H "Authorization: Bearer $PUSH_TOKEN" -H 'Content-Type: application/json' \
  -d '{"v":1,"sent_at":0,"live":true,"measured_at":0,"values":{"bpm":"77","steps":"1,234"}}'
# expect 204; the server's presence shows 77 bpm / 1,234 steps for PUSH_STALE_AFTER seconds
# (with "live":false, 1,234 steps still shows, but the heart rate comes from the server's own sources)
```
