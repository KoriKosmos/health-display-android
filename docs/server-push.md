# Server push: phone values → Health Display server

While the Android app's presence service runs, it sends its current values to
the [Health Display server](https://github.com/KoriKosmos/health-display). The
server then shows **the phone's values** in its own Rich Presence, so both
show identical numbers. When the pushes stop, the server goes back to its own
sources (Pulsoid, Google Health).

The phone side is implemented (`push/ServerPush.kt`). This document is the
spec for the server side (`health_display.py`).

## Configuration

Both apps read the same `config.env`, so importing the server's file on the
phone sets the phone up too.

| Key | Used by | Meaning |
|---|---|---|
| `PUSH_TOKEN` | both | Shared secret. Generate with `openssl rand -hex 32`. Push is off on both sides while empty. |
| `PUSH_URL` | phone | Where the phone posts, e.g. `https://health.korikosmos.dev/push`. Push is off on the phone while empty. |
| `PUSH_PORT` | server | Port the server listens on, default `8766`. |
| `PUSH_STALE_AFTER` | server | Seconds without a valid push before the phone counts as inactive, default `60`. |

Add these keys, commented, to `config.env.example`.

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
- `live`: whether the phone has a live heart rate. `false` means the phone
  cleared its own presence.
- `measured_at`: Unix ms of the phone's latest BPM reading (0 if none yet).
- `values`: placeholder name → display string, already formatted. These are
  the same names as the server's placeholders, plus the phone-only
  `{mindful}`. Missing keys simply have no value, so their ` · ` segments drop
  out as usual.

The phone sends a push every `UPDATE_INTERVAL` seconds (default 15) as a
heartbeat, and immediately when `live` changes.

## Server behaviour

1. Run a small HTTP listener (stdlib `http.server.ThreadingHTTPServer`, daemon
   thread) on `0.0.0.0:PUSH_PORT`, only when `PUSH_TOKEN` is set. Keep the
   latest valid payload and its receive time under a lock.
2. The phone is **active** while the last valid push arrived less than
   `PUSH_STALE_AFTER` seconds ago (server clock).
3. While the phone is active, `build_activity` uses the phone's data instead
   of `hr`/`stats`:
   - `live` false: no activity (presence cleared), exactly like the phone.
   - `live` true: `values = payload["values"]`, rendered with the server's own
     `DETAILS` / `STATE` / `IMG_HOVER` templates. The BPM tile uses
     `values["bpm"]`, and `timestamps.start` stays the server's own start.
4. While the phone is inactive, behaviour is unchanged.
5. Keep polling Pulsoid and Google Health meanwhile, so falling back is
   instant.
6. `UPDATE_INTERVAL` and the unchanged-presence check apply as now.
7. Log transitions once: `phone: active (pushes from <ip>)` and
   `phone: inactive, using own sources`. Log rejected pushes once per cause.

## Deployment

- `compose.yaml`: publish the port, `"8766:8766"`.
- Public URL: `https://health.korikosmos.dev/push`. Cloudflare-proxied CNAME →
  Nginx Proxy Manager → `192.168.0.57:8766`.

## Test

```sh
curl -i -X POST https://health.korikosmos.dev/push \
  -H "Authorization: Bearer $PUSH_TOKEN" -H 'Content-Type: application/json' \
  -d '{"v":1,"sent_at":0,"live":true,"measured_at":0,"values":{"bpm":"77","steps":"1,234"}}'
# expect 204; the server's presence shows 77 bpm / 1,234 steps for PUSH_STALE_AFTER seconds
```
