# Privacy Policy — ShortsBlocker

**Last updated:** 2025

## Summary
ShortsBlocker does **not** collect, store, or transmit any user data. Period.

## How It Works
ShortsBlocker creates a local VPN tunnel on your device to intercept DNS queries. When your device requests to connect to a blocked domain (e.g., `shorts.youtube.com`), the app silently drops the request. All processing happens **entirely on your device**.

## What We Do NOT Collect
- No personal information
- No browsing history
- No DNS query logs stored remotely
- No analytics or crash reporting
- No third-party SDKs
- No advertising

## Local Stats
The app stores a simple counter (number of blocked requests) in your device's **local SharedPreferences storage only**. This data never leaves your device and can be cleared from within the app at any time.

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
