# 🚫 ShortsBlocker

[![Build & Release APK](https://github.com/undisputedP/ShortsBlocker/actions/workflows/release.yml/badge.svg)](https://github.com/undisputedP/ShortsBlocker/actions/workflows/release.yml)
[![Download APK](https://img.shields.io/github/v/release/undisputedP/ShortsBlocker?label=Download%20APK&logo=android&color=green)](https://github.com/undisputedP/ShortsBlocker/releases/latest)
[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](https://www.gnu.org/licenses/gpl-3.0)
[![Platform](https://img.shields.io/badge/platform-Android%205.0%2B-brightgreen)](https://android.com)

> Block Instagram Reels, TikTok, Facebook Reels, Snapchat Stories, and the Twitter/X explore feed using a local DNS VPN.  
> **No root required. No data leaves your device. Completely free and open source.**
>
> ⚠️ **Note on YouTube Shorts:** DNS-based blocking can't separate Shorts from regular YouTube — Shorts are served from `www.youtube.com/shorts/*`, the same hostname as normal videos, and DNS only sees hostnames. The "YouTube Shorts" platform toggle is shipped **disabled by default** and is effectively inert. Use a Shorts-specific tool (ReVanced, browser extensions) if you need that.

---

## 📲 Install

1. **[Download the latest APK](https://github.com/undisputedP/ShortsBlocker/releases/latest)**
2. On your phone: `Settings → Security → Install unknown apps` → allow your browser
3. Open the downloaded APK → Install
4. Launch **ShortsBlocker** → tap **▶ Start Blocking** → accept the VPN permission

---

## ✨ Features

| Feature | Status |
|---------|--------|
| Instagram Reels blocking | ✅ |
| Facebook Reels blocking | ✅ |
| TikTok blocking | ✅ |
| Snapchat Stories blocking | ✅ |
| Twitter/X Explore blocking | ✅ |
| YouTube Shorts blocking | ⚠️ Not possible via DNS (see note above) |
| Per-platform toggle | ✅ |
| Daily block stats | ✅ |
| Auto-start on boot | ✅ |
| Dark mode UI | ✅ |
| Zero data collection | ✅ |
| No root required | ✅ |

---

## 🏗️ How It Works

```
Your App → DNS Query → Local VPN (10.99.0.2) →
  ├── Blocked domain? → NXDOMAIN response (silently dropped)
  └── Safe domain?    → Forwarded to upstream resolver (1.1.1.1 / 8.8.8.8)
                        via a VpnService.protect()-ed socket
```

ShortsBlocker creates a **narrow local VPN tunnel** that captures only DNS traffic to a tunnel-local address (`10.99.0.2`). Non-DNS traffic flows over your real network normally — the app is not a general-purpose VPN. Allowed DNS queries are forwarded to a public resolver via a socket that bypasses the VPN itself; blocked queries get an NXDOMAIN reply so the app trying to reach the host fails fast.

---

## 🔨 Build from Source

### Prerequisites
- Android Studio Hedgehog or newer
- JDK 17
- Android SDK API 34

### Steps
```bash
git clone https://github.com/undisputedP/ShortsBlocker.git
cd ShortsBlocker/ShortsBlocker   # the Android project lives in a subdirectory

# Open in Android Studio and click Run
# OR build via command line:
./gradlew assembleDebug
```

APK will be at: `ShortsBlocker/app/build/outputs/apk/debug/app-debug.apk` (relative to the repo root).

---

## 🚀 Release Process

```bash
git tag v1.0.1
git push origin v1.0.1
# GitHub Actions automatically builds + signs + publishes APK
```

---

## 🤝 Contributing

Pull requests are welcome!

1. Fork the repo
2. Create your branch: `git checkout -b feature/add-reddit-blocker`
3. Commit: `git commit -m 'feat: block Reddit feed'`
4. Push: `git push origin feature/add-reddit-blocker`
5. Open a Pull Request

---

## 📋 Blocked Domains

| Platform | Domains | Effective? |
|----------|---------|------------|
| Instagram Reels | `i.instagram.com`, `graph.instagram.com`, `edge-chat.instagram.com` | ✅ |
| Facebook Reels | `reels.facebook.com`, `graph.facebook.com` | ✅ |
| TikTok | `tiktok.com`, `*.tiktokv.com`, `analytics.tiktok.com` | ✅ |
| Snapchat | `ads.snapchat.com`, `sc-cdn.net` | ✅ |
| Twitter/X | `api.twitter.com`, `abs.twimg.com` | ✅ partial |
| YouTube Shorts | `reel.youtube.com`, `shorts.youtube.com` | ⚠️ inert (real Shorts traffic uses `www.youtube.com`) |

---

## 🔒 Privacy

ShortsBlocker **never** collects or transmits any data.  
[Full Privacy Policy](https://undisputedP.github.io/ShortsBlocker/privacy-policy)

---

## 📄 License

GPL-3.0 © 2025 — see [LICENSE](LICENSE)

> Any derivative work must also be open source under the same license.

---

## ⭐ Support

If this helped your focus — leave a ⭐ on GitHub!  
Share with others: `https://github.com/undisputedP/ShortsBlocker`
