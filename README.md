# 🚫 ShortsBlocker — block Instagram Reels, TikTok, Facebook Reels, Snap Stories, and X Explore on Android

[![Build & Release APK](https://github.com/undisputedP/ShortsBlocker/actions/workflows/release.yml/badge.svg)](https://github.com/undisputedP/ShortsBlocker/actions/workflows/release.yml)
[![Latest Release](https://img.shields.io/github/v/release/undisputedP/ShortsBlocker?label=latest&logo=android&color=2D2A5C)](https://github.com/undisputedP/ShortsBlocker/releases/latest)
[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](https://www.gnu.org/licenses/gpl-3.0)
[![Platform](https://img.shields.io/badge/platform-Android%205.0%2B-brightgreen)](https://android.com)

**ShortsBlocker** is a free, open‑source Android app that blocks **Instagram Reels**, **TikTok**, **Facebook Reels**, **Snapchat Stories**, and the **Twitter/X explore feed** at the network layer. **No root required. No accounts. No data collection. No ads.** Install the APK and toggle it on.

If you've tried every focus app, screen‑time limit, and "digital wellbeing" feature and the algorithm still wins, this is the simplest hammer: cut off the network calls those feeds rely on so the apps just see "host not found".

> ℹ️ **Why no YouTube Shorts?** YouTube Shorts share `www.youtube.com` with regular YouTube videos, so on-device DNS can't tell them apart. v1 shipped an accessibility-based dismisser that watched YouTube's UI; after several iterations chasing YouTube's frequent UI changes the approach proved too brittle to ship reliably, and was **removed in v2.0**. For YouTube Shorts the recommended tool is a YouTube client like [ReVanced](https://revanced.app/) — its YouTube patches include built-in toggles to hide the Shorts shelf and Shorts navigation tab, which doesn't depend on detection at all.

---

## 📲 Install

1. Open the **[latest release](https://github.com/undisputedP/ShortsBlocker/releases/latest)** on your Android phone
2. Download `ShortsBlocker-vX.Y.Z.apk`
3. Allow your browser to install unknown apps when prompted (`Settings → Security → Install unknown apps`)
4. Open the APK → Install
5. Launch **ShortsBlocker** → tap **Start Blocking** → accept the VPN permission prompt

The "VPN" permission is required because Android exposes per‑app traffic interception only via `VpnService`. ShortsBlocker isn't a real VPN — it doesn't tunnel your traffic anywhere. See *How it works* below.

---

## Why use ShortsBlocker

- **It works at the network layer.** No accessibility services, no fragile per‑app hacks, no rooting. The blocked apps just see "host not found" and give up.
- **It's local.** No account to create, no server to subscribe to, no telemetry. The code is GPL‑3.0 and ~700 lines — you can read it in one sitting.
- **It's surgical.** Only DNS to a tunnel‑local IP goes through the app. Your normal traffic — banking apps, Maps, video calls — flows over your real network unchanged.
- **It survives reboots.** A boot receiver re‑arms the VPN automatically (you can disable this).

---

## ✨ Features

| Feature | Status | How |
|---------|--------|-----|
| Instagram Reels blocking | ✅ | DNS |
| Facebook Reels blocking | ✅ | DNS |
| TikTok blocking (full domain set) | ✅ | DNS |
| Snapchat Stories blocking | ✅ | DNS |
| Twitter/X Explore feed blocking | ✅ partial | DNS |
| YouTube Shorts blocking | ❌ removed in v2.0 | use [ReVanced](https://revanced.app/) — see callout above |
| Per‑platform toggle | ✅ | |
| Daily / all‑time block counters | ✅ | |
| Auto‑restart on phone reboot | ✅ | |
| Dark mode UI | ✅ | |
| Zero data collection | ✅ | |
| No root / no Magisk module | ✅ | |

---

## 🏗️ How it works

```
Your apps → DNS Query → tunnel‑local resolver (10.99.0.2) →
  ├── Blocked host? → NXDOMAIN reply (the app gives up)
  └── Allowed host? → forwarded to 1.1.1.1 / 8.8.8.8 / 9.9.9.9
                       via a VpnService.protect()‑ed UDP socket
```

ShortsBlocker creates a **narrow Android VPN** that only attracts traffic to a tunnel‑local DNS address. Everything else (TCP, HTTP, ICMP, DNS to other servers) flows over your real network untouched. For each DNS query that does come through, the app either replies with NXDOMAIN (for blocked hostnames) or forwards the query to a public DNS resolver and writes the answer back into the tunnel. The forwarding socket uses `VpnService.protect()` so it bypasses the VPN itself and reaches the upstream server normally.

This is fundamentally different from a typical VPN app: ShortsBlocker has no remote server, no tunnel, no shared infrastructure, and zero ability to exfiltrate your traffic even if it wanted to. The whole "VPN" surface is just Android's hook for letting an app see and answer DNS queries.

---

## 📋 What's blocked

| Platform | Mechanism | Targets |
|----------|-----------|---------|
| Instagram Reels | DNS | `i.instagram.com`, `graph.instagram.com`, `edge-chat.instagram.com` |
| Facebook Reels | DNS | `reels.facebook.com`, `graph.facebook.com` |
| TikTok | DNS | `tiktok.com`, `*.tiktokv.com`, `analytics.tiktok.com` |
| Snapchat | DNS | `ads.snapchat.com`, `sc-cdn.net`, `feelinsonice-hrd.appspot.com` |
| Twitter/X | DNS | `api.twitter.com`, `abs.twimg.com` (Explore feed) |

You can also add custom hostnames at runtime — they're stored in `BlockingRules.customDomains` (in‑memory for now; persistence is on the roadmap).

---

## ❓ FAQ

**Does it work without root?**
Yes. ShortsBlocker is a normal Android app that uses the `VpnService` API — works on any device running Android 5.0+ with no root, ADB tricks, or Magisk modules.

**Does it slow down my phone?**
No. Only DNS queries to one tunnel‑local IP go through the app. Non‑DNS traffic flows over your real network at full speed. The app itself uses ~50 MB RAM and negligible CPU.

**Can I use it alongside another VPN?**
Android only allows one `VpnService` at a time. If you start a different VPN, ShortsBlocker stops, and vice‑versa.

**What about YouTube Shorts?**
Not blocked by ShortsBlocker. YouTube Shorts share `www.youtube.com` with regular videos so DNS can't separate them, and v1's accessibility-based dismisser was too brittle across YouTube redesigns to ship reliably (see the callout near the top of this README). For Shorts, install [ReVanced](https://revanced.app/) and turn on its "Hide Shorts shelf" / "Hide Shorts navigation tab" patches — that's a UI removal rather than runtime detection, so it doesn't break when YouTube ships a new layout.

**Will it block Instagram entirely / TikTok entirely?**
Yes — the blocked hostnames are the API endpoints those apps depend on, so the apps fail to load any content. If you want to *use* Instagram for messaging while blocking Reels, this isn't the right tool (DNS can't see what kind of content the app is requesting). Use Instagram Web in a browser, or a third‑party client.

**Does it cost anything? Will it ever?**
No. GPL‑3.0, no telemetry, no in‑app purchases, no plans to add any.

**Why isn't it on the Play Store / F‑Droid?**
Sideload‑only for now. F‑Droid submission is on the roadmap.

---

## 🔨 Build from source

### Prerequisites
- JDK 17+
- Android SDK API 34
- (Optional) Android Studio Hedgehog or newer

### Steps
```bash
git clone https://github.com/undisputedP/ShortsBlocker.git
cd ShortsBlocker/ShortsBlocker   # the Android project lives in a subdirectory
./gradlew assembleDebug
```

The unsigned debug APK lands at `ShortsBlocker/app/build/outputs/apk/debug/app-debug.apk`.

For a signed release build, see [`ShortsBlocker/scripts/setup-signing.sh`](ShortsBlocker/scripts/setup-signing.sh) (one‑shot keystore + GitHub Actions secret bootstrapper).

---

## 🚀 Cutting a release

```bash
git tag v2.0.1
git push origin v2.0.1
# GitHub Actions builds, signs, and publishes the APK to a Release.
```

---

## 🤝 Contributing

PRs welcome — for new platforms, additional hostnames, or tests on the DNS packet builder.

1. Fork the repo
2. Create a feature branch: `git checkout -b feat/block-pinterest-reels`
3. Commit and push
4. Open a Pull Request

---

## 🔒 Privacy

ShortsBlocker collects **no** personal data, sends **no** telemetry, and contacts **no** server it doesn't have to. Allowed DNS queries are forwarded to public resolvers (Cloudflare's `1.1.1.1`, Google's `8.8.8.8`, Quad9's `9.9.9.9`) — those operators can see hostnames you look up, exactly the same as if your phone were configured to use them directly. Block decisions, counters, and per‑platform stats are kept in local `SharedPreferences` and never leave the device.

[Full privacy policy](https://github.com/undisputedP/ShortsBlocker/blob/main/ShortsBlocker/docs/privacy-policy.md)

---

## 📄 License

GPL‑3.0 © 2026 — see [LICENSE](LICENSE).

Any derivative work must also be open source under the same license.

---

## ⭐ If this helped your focus

A star is the easiest way to help others find this. Sharing the repo link with friends who are also algorithm‑hostage helps too: `https://github.com/undisputedP/ShortsBlocker`

**Keywords:** android short video blocker, instagram reels blocker, tiktok blocker android, no root reels blocker, free social media blocker, open source android focus app, foss reels blocker, dns blocker android, digital wellbeing app, snapchat stories blocker, x explore feed blocker.
