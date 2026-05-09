# Privacy Policy — ShortsBlocker

**Last updated:** 2026-05-09

## Summary
ShortsBlocker does **not** collect, store, or transmit any personal data. The app has no account, no telemetry, no analytics, no advertising, and no remote server it operates. Allowed DNS queries are forwarded to a public DNS resolver (see *Network behavior* below) — that's the only network activity caused by the app.

## How It Works
ShortsBlocker creates a **narrow local VPN** on your device that captures only DNS queries directed at a tunnel‑local address. For each DNS query the app sees, it does one of two things:

1. **Blocked hostname:** the app immediately replies with `NXDOMAIN` (host not found). The blocked app gives up and the request never leaves your device.
2. **Allowed hostname:** the app forwards the query to a public DNS resolver (Cloudflare `1.1.1.1`, Google `8.8.8.8`, or Quad9 `9.9.9.9` as fallbacks) over a `VpnService.protect()`‑ed socket and writes the answer back to the tunnel.

Non‑DNS traffic (HTTP, video calls, banking apps, etc.) does **not** go through ShortsBlocker — it flows over your real network unchanged. ShortsBlocker has no infrastructure to send your traffic to and never sees it.

## Network Behavior
Because allowed DNS queries are sent to Cloudflare / Google / Quad9, those operators can observe which hostnames your device looks up — exactly the same as if your phone were configured to use them directly without ShortsBlocker. Their respective privacy policies apply for the queries they receive. ShortsBlocker itself does not log, store, or transmit those queries.

## What We Do NOT Collect
- No personal information
- No browsing history
- No DNS query logs (locally or remotely)
- No analytics or crash reporting
- No third‑party SDKs
- No advertising

## Local Stats
The app stores a small set of counters (today's block count, all‑time block count, per‑platform tallies) in your device's local `SharedPreferences`. This data never leaves your device and can be cleared by uninstalling the app or clearing its data from Android settings.

## Permissions Used
| Permission | Why |
|------------|-----|
| `BIND_VPN_SERVICE` | To create the local DNS filter |
| `FOREGROUND_SERVICE` | To keep the VPN active while using other apps |
| `RECEIVE_BOOT_COMPLETED` | Optional auto-start after phone reboot |
| `POST_NOTIFICATIONS` | To show the persistent "blocking active" notification |

## Open Source
The complete source code is available at:
`https://github.com/undisputedP/ShortsBlocker`

You can audit every line of code.

## Contact
For questions or concerns: open an issue on GitHub.
