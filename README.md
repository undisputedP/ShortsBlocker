# 🚫 ShortsBlocker

[![Build & Release APK](https://github.com/undisputedP/ShortsBlocker/actions/workflows/release.yml/badge.svg)](https://github.com/undisputedP/ShortsBlocker/actions/workflows/release.yml)
[![Download APK](https://img.shields.io/github/v/release/undisputedP/ShortsBlocker?label=Download%20APK&logo=android&color=green)](https://github.com/undisputedP/ShortsBlocker/releases/latest)
[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](https://www.gnu.org/licenses/gpl-3.0)
[![Platform](https://img.shields.io/badge/platform-Android%205.0%2B-brightgreen)](https://android.com)

> Block YouTube Shorts, Instagram Reels, TikTok, and Facebook Reels using a local DNS VPN.  
> **No root required. No data leaves your device. Completely free and open source.**

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
| YouTube Shorts blocking | ✅ |
| Instagram Reels blocking | ✅ |
| Facebook Reels blocking | ✅ |
| TikTok blocking | ✅ |
| Snapchat Stories blocking | ✅ |
| Per-platform toggle | ✅ |
| Daily block stats | ✅ |
| Auto-start on boot | ✅ |
| Dark mode UI | ✅ |
| Zero data collection | ✅ |
| No root required | ✅ |

---

## 🏗️ How It Works

```
Your App → DNS Query → Local VPN (ShortsBlocker) → 
  ├── Blocked domain? → NXDOMAIN response (silently dropped)
  └── Safe domain?    → Forward to real DNS (8.8.8.8)
```

ShortsBlocker creates a **local VPN tunnel** that intercepts DNS queries. When your device tries to reach a blocked domain, the app returns an NXDOMAIN response — making it appear the server doesn't exist. No packets are sent externally.

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

| Platform | Domains |
|----------|---------|
| YouTube Shorts | `reel.youtube.com`, `shorts.youtube.com` |
| Instagram Reels | `i.instagram.com`, `graph.instagram.com` |
| Facebook Reels | `reels.facebook.com`, `graph.facebook.com` |
| TikTok | `tiktok.com`, `*.tiktokv.com`, `analytics.tiktok.com` |
| Snapchat | `ads.snapchat.com`, `sc-cdn.net` |
| Twitter/X | `api.twitter.com` (Explore feed) |

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
