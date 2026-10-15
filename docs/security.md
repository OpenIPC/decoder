# Security

- [Summary](#summary)
- [Network](#network)
- [Credential store](#credential-store)
- [Debug server](#debug-server)
- [Log redaction](#log-redaction)
- [WebUI](#webui)
- [App permissions](#app-permissions)
- [Reporting](#reporting)

## Summary

The app is a viewer for cameras on a network you trust. Nothing it sends or receives is encrypted. Passwords are kept encrypted on the device and are not shown on the debug page or in the log.

## Network

- RTSP, RTP over UDP and multicast, HTTP MJPEG and the debug page are plain text. RTSPS, HTTPS and SRTP are not supported, and RTSPS is not planned ([#16](https://github.com/OpenIPC/decoder/issues/16)).
- Credentials go in the `Authorization` header: Basic (the password readable by anyone on the path) unless the camera asks for Digest. They are not put in the RTSP request line.
- Credentials that a camera expects in the URL path or query (for example `user=…_password=…`) are sent with every request.
- A `udp://` slot accepts RTP from any sender unless the URL names one (`udp://<sender>@…`); the sender filter compares addresses, which can be spoofed on the same network.
- The app allows clear-text traffic (`usesCleartextTraffic="true"`), which cameras need.

Use the app on trusted networks only, or inside a secure channel of your own (a VPN to the camera network).

## Credential store

- Camera URLs, passwords included, are stored in the app's private `SharedPreferences`, sealed with **AES-256-GCM** (12-byte IV, 16-byte tag) under a key that is generated in and never leaves the **Android Keystore** (alias `camera_urls`, Android 6.0+). Sealed values start with `enc1:`.
- On Android 5.x, or when the Keystore is unavailable or fails, URLs are stored in plain text, as before encryption existed. Plain URLs from older versions are re-saved encrypted on the next start.
- A stored URL that cannot be decrypted (for example after the Keystore lost its key) is kept as it is, not overwritten, until the slot is set again.
- Backup is disabled (`allowBackup="false"`): the settings do not leave the device through Android backup.
- **Reset** removes the stored URLs and deletes the Keystore key, so old values can never be read again; uninstalling removes both too.

## Debug server

The [debug server](debug.md) on port 8085 has no password and no encryption. It is always on while the app runs, and:

- answers only local-network clients: loopback, link-local (169.254/16, fe80::/10), the IPv4 private ranges 10/8, 172.16/12, 192.168/16, and IPv6 unique-local fc00::/7. Every other address gets `403 Forbidden: local network only`;
- is read-only (`GET` only) and has no setting that changes the app;
- never shows a login or password: URLs, statuses and log lines are [redacted](#log-redaction), and the snapshot shows the picture and the cell texts only.

Anyone on the same LAN can see the camera pictures and hosts through it. On a network you do not trust, close the app when you do not need it.

## Log redaction

Applied to every line before it is kept for `/log` and to every string in `/stats.json`:

| Pattern | Becomes |
|---|---|
| `rtsp://user:pass@`, `rtsps://`, `http://`, `https://` userinfo | `rtsp://***@` |
| `pass=`, `password=`, `pwd=` parameters | `<name>=***` |
| `user=`, `username=`, `login=`, `usr=` parameters (debug server and `/log`) | `<name>=***` |
| `Authorization: <scheme> <value>` | `Authorization: <scheme> ***` |

The app's own log calls redact URLs the same way, without the login rule ([#55](https://github.com/OpenIPC/decoder/issues/55)), and a shared link is logged by its host only: `adb logcat` shows no passwords, but may show a login that the camera expects in the URL path. Check reports before posting anyway.

## WebUI

The built-in browser opens the camera's web interface with JavaScript enabled. HTTP authentication requests are answered with the credentials of the slot URL only for the camera's own host; a page reached by a redirect or a link to another host gets none.

## App permissions

| Permission | Why |
|---|---|
| `INTERNET` | streams, WebUI, the debug server |
| `CHANGE_WIFI_MULTICAST_STATE` | the multicast lock for multicast RTP on Wi‑Fi |
| `WRITE_EXTERNAL_STORAGE` (Android ≤ 9), `READ_EXTERNAL_STORAGE` (≤ 12) | screenshots to `Pictures/OpenIPC` on older Android versions |

No location, camera, microphone or account permissions. No analytics and no Google services.

## Reporting

Report security problems as a [GitHub issue](https://github.com/OpenIPC/decoder/issues), without real credentials or public addresses, or through the [OpenIPC channels](https://openipc.org/our-channels) if the details should not be public.
