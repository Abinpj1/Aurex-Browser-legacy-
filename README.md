<div align="center">

<img src="docs/banner.png" alt="Aurex Browser - Fast. Private. Powerful." width="100%">

<br>

[![Release](https://img.shields.io/github/v/release/aman-nitb-cse/Aurex-Browser?color=7C4DFF&label=Release)](https://github.com/aman-nitb-cse/Aurex-Browser/releases/latest)
![Android](https://img.shields.io/badge/Android-7.0%2B-3DDC84?logo=android&logoColor=white)
![Kotlin](https://img.shields.io/badge/Kotlin-Jetpack%20Compose-7F52FF?logo=kotlin&logoColor=white)
![License](https://img.shields.io/badge/License-GPL%20v3-blue)
[![Stars](https://img.shields.io/github/stars/aman-nitb-cse/Aurex-Browser?style=flat&color=F5B301)](https://github.com/aman-nitb-cse/Aurex-Browser/stargazers)

[![Download](https://img.shields.io/badge/Download-Latest%20APK-3DDC84?style=for-the-badge&logo=android&logoColor=white)](https://github.com/aman-nitb-cse/Aurex-Browser/releases/latest)
[![Telegram](https://img.shields.io/badge/Telegram-Join%20Community-26A5E4?style=for-the-badge&logo=telegram&logoColor=white)](https://t.me/aurex_oss)

[Features](#-features) · [Install](#-install) · [Build](#-build-from-source) · [Permissions](#-permissions-explained) · [Contributing](#-contributing) · [License](#-license)

</div>

---

## 👋 About

**Aurex Browser** is a fast, privacy-focused web browser for Android, written in Kotlin with Jetpack Compose. It brings an ad blocker, a smart popup guard, a built-in media player and a universal video downloader into one clean, glass-style interface, with no account and no server of its own.

<!--
SCREENSHOTS (uncomment after adding your images to docs/screenshots/)

## 📸 Screenshots

<div align="center">
<table>
  <tr>
    <td><img src="docs/screenshots/home.png" width="230" alt="Home"></td>
    <td><img src="docs/screenshots/player.png" width="230" alt="Video player"></td>
    <td><img src="docs/screenshots/downloads.png" width="230" alt="Downloads"></td>
  </tr>
  <tr>
    <td align="center">Browsing</td>
    <td align="center">Built-in player</td>
    <td align="center">Downloads</td>
  </tr>
</table>
</div>
-->

## ✨ Features

### 🛡️ Ad, tracker and popup blocking

| Feature | What it does |
|---|---|
| **Multi-source ad blocker** | Uses real Adblock-Plus-style rules from EasyList, EasyPrivacy, uBlock Origin (including its badware and unbreak lists) and AdGuard Base and Mobile Ads. Rules are cached on your phone and refreshed every 2 days. |
| **Fast filter engine** | Rules are token-indexed (like uBlock and Adblock Plus), so a request only checks the few rules that could match it. |
| **Host blocklists** | Bundled hosts plus the StevenBlack and Peter Lowe ad-server lists, refreshed weekly. |
| **Cosmetic filtering** | Hides the empty boxes and banners that ads leave behind. |
| **Tracker rules** | URL pattern rules for common trackers and analytics. |
| **YouTube ad skipping** | Network and page rules that skip and hide pre-roll and mid-roll ads. |
| **Smart popup and redirect guard** | Judges *behaviour*, not only a list: did you tap the link, is the target another site, did this tab already visit it (login or payment round-trips are allowed), does the address look like an ad (click-id parameters, abused domain endings, raw IP addresses, random names). Pages that try `window.open()` are checked before the popup appears. |
| **Trusted sites** | Mark a site as trusted so the popup guard leaves it alone. |
| **Third-party cookie blocking** | Optional switch to stop cross-site cookies. |
| **Live counters** | See threats blocked, popups and redirects stopped, and how many filter rules are active. |

### 🔒 Privacy and safety

| Feature | What it does |
|---|---|
| **Private tabs with a lock** | Opening a private tab asks for your phone's own lock (fingerprint, face, PIN, pattern or password). Aurex stores no password of its own, so there is nothing to forget. On by default. |
| **Adult-content filter** | Five layers, cheapest first: a domain list (bundled plus a large downloadable list, refreshed weekly), domain-name checks that catch mirrors and adult endings, address and search words (matched word by word, so "essex" is never blocked), forced SafeSearch on Google, Bing, DuckDuckGo and Yahoo, and an on-page scan. It is context-aware, so help pages, news and art history are not blocked by mistake. Switching it off requires your phone lock. |
| **Clear data** | One-tap clearing of cache, cookies and site data, or all browsing data. |
| **Privacy-safe log** | A small private event log for bug reports. It never stores cookies or URL query strings. |

### 🎬 Built-in video player

- **Full player screen** with gestures, seek bar, playback speed, screen lock and aspect-ratio control
- **Picture-in-Picture**, with optional automatic PiP when you leave the app
- **Background playback**, a floating media button and a media notification with controls
- **One shared timeline** between the video on a web page and the built-in player: open the player and it starts exactly where the page was, close it and the page video continues from the player's position
- **Smooth high-resolution YouTube playback.** Aurex requests YouTube video in chunks (the way yt-dlp does) so 2K and 4K do not stall on fast connections
- **Honest quality list.** Qualities above 1080p (2K, 4K, 8K) are offered only when your phone's hardware decoder can really play them
- **Seekable downloads.** Downloaded HLS videos get a seek index built in memory, so seeking works instantly without re-encoding or changing the file

### 🧩 Media dock

One panel for everything media on the current page:

- **Now playing** card with artwork, live scrubber, transport buttons and speed
- **Save page** card for the universal downloader
- **Downloads** with an overall progress ring and live rows (pause, resume, retry)
- **On this page**: detected streams, each with Play and Download

### ⬇️ Downloader

| Feature | What it does |
|---|---|
| **Universal video and audio downloader** | Powered by yt-dlp and FFmpeg, bundled inside the app. |
| **Quality choice** | Pick a quality for each download, or turn on *Always best quality*. |
| **Download manager** | Pause, resume and retry, pause all, clear finished. |
| **Regular files** | APK, PDF, ZIP and other plain file downloads work too. |
| **Swipe actions** | Swipe a download right to delete or left to share (both can be turned off). |
| **Range select** | Press and hold a download, then drag to select many at once. The list scrolls by itself near the edges. |
| **Media visibility** | Choose whether finished videos, music and images appear in your Gallery, Music and Files apps. |
| **Engine control** | A master switch for video downloading (when off, nothing of the video engine runs in the background), automatic engine updates, one-tap update and *Repair engine*. |

### 🎨 Appearance and reading

- 🌙 **Dark websites engine** (Dark Reader style) that works on any site, even when your phone is in light mode. Colours keep their hue, photos and videos stay natural, pages that are already dark are detected and left alone, and infinite-scroll pages are handled live
- 💎 **Glass-style interface** with light and dark themes and an adjustable transparency slider
- 🔤 **Text size** control
- 🖥️ **Desktop site** mode and 📉 **data saver**
- 📸 **Long screenshots** of a whole web page. Auto-scroll captures the page screen by screen and waits for lazy images to load. Touch the page to take over manually. Sticky bars are hidden so they do not repeat down the picture

### ⚙️ Customisation

- 🧰 **Toolbar** and **menu**: choose which controls are shown
- 👋 **Gestures**: swipe the address bar, swipe up on Tabs or Downloads, hold Tabs for a new tab, hold the menu for Settings
- ⬆️ **Auto-hide** toolbar on scroll
- ⭐ **Favorites** and **recent sites**
- 📳 **Haptic feedback** (can be turned off)
- 🔎 **Seven search engines**: Google, DuckDuckGo, Brave, Bing, Startpage, Ecosia, Yahoo
- 🔍 **Searchable settings**, grouped into Appearance, Toolbar, Menu, Gestures, Privacy, Downloads, Media, Search engine, General and About

### 🔄 Updates

Aurex updates itself from this repository's [GitHub Releases](https://github.com/aman-nitb-cse/Aurex-Browser/releases). There is no account and no update server.

- Checks automatically (optional) or on demand
- One release behind: a polite dialog where **Later** works. Two or more behind: the update can no longer be postponed
- The APK is checked against GitHub's SHA-256 when one is published, then handed to Android's installer, which only accepts an update signed with the same key as the installed app

## 📲 Install

1. Open the [latest release](https://github.com/aman-nitb-cse/Aurex-Browser/releases/latest) and download the `.apk` file from **Assets**
2. Open it on your phone. If asked, allow installation from your browser or file manager
3. Tap **Install**

**Requires** Android 7.0 (API 24) or newer.

## 🛠️ Build from source

You need **JDK 17**, **Android SDK Platform 35** and a recent **Android Studio** (or Gradle 8.14+).

```bash
git clone https://github.com/aman-nitb-cse/Aurex-Browser.git
cd Aurex-Browser
./gradlew assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/`. The easiest way is to open the folder in Android Studio and press **Run**.

> If `gradle/wrapper/gradle-wrapper.jar` is missing, run `gradle wrapper --gradle-version 8.14.6` once, or just use Android Studio.

Without extra setup, builds are signed with Android's default **debug key**.

### Signing your own release builds

Signing keys are **never** stored in this repository.

1. Create a key: `keytool -genkeypair -v -keystore aurex.keystore -alias aurex -keyalg RSA -keysize 2048 -validity 10000`
2. Create `keystore.properties` in the project root (it is git-ignored):

   ```properties
   storeFile=aurex.keystore
   storePassword=YOUR_PASSWORD
   keyAlias=aurex
   keyPassword=YOUR_PASSWORD
   ```
3. Run `./gradlew assembleRelease`. The APK is in `app/build/outputs/apk/release/`

An APK signed with a different key cannot update an app installed with another key.

### Publishing a release (maintainers)

1. Increase `versionCode` and update `versionName` in `app/build.gradle.kts`
2. Build the signed APK
3. On GitHub: **Releases → Draft a new release**, create the tag `v` + `versionName` (for example `v1.0.1`), attach the `.apk` and publish

## 🔐 Permissions explained

<details>
<summary>Why Aurex asks for each permission</summary>

| Permission | Why |
|---|---|
| Internet, network state | Browsing, filter list updates, downloads |
| Notifications | Download progress and media controls |
| Foreground service (media playback, data sync) | Keeps playback and downloads running in the background |
| Wake lock | Keeps a download or video going while the screen is off |
| Biometric | Phone-lock check for private tabs and the adult filter |
| Install packages | Installs updates downloaded by the in-app updater |
| Camera, microphone, location | Used only when a website you visit asks for them (for example video calls or maps) |
| Vibrate | Haptic feedback |

</details>

## 🧱 Tech stack

| | |
|---|---|
| Language and UI | Kotlin, Jetpack Compose, Material 3 |
| Web engine | Android System WebView (Chromium) |
| Media | AndroidX Media3 (ExoPlayer, HLS, DASH, MediaSession) |
| Networking | OkHttp |
| Downloader | yt-dlp and FFmpeg through youtubedl-android |
| Async | Kotlin Coroutines |
| Build | Gradle (Kotlin DSL), Android Gradle Plugin 8.5.2, Kotlin 2.0 |

<details>
<summary>Project layout</summary>

All code lives in `app/src/main/java/com/aurex/browser/`:

| File | Purpose |
|---|---|
| `AdBlocker.kt`, `FilterEngine.kt` | Ad and tracker blocking, filter list engine |
| `PopupGuard.kt`, `CookieGuard.kt` | Popup and redirect judgement, third-party cookie handling |
| `AdultFilter.kt`, `DeviceAuth.kt` | Adult-content filter and phone-lock authentication |
| `DarkEngine.kt` | Dark mode for websites |
| `LongShot.kt` | Long screenshots |
| `Downloader.kt`, `YtDlpEngine.kt`, `VideoDl.kt` | Download manager and video engine |
| `PlayerActivity.kt`, `WebVideoPlayer.kt`, `PlayerSync.kt`, `PipSupport.kt` | Built-in player, page-to-player sync, Picture-in-Picture |
| `MediaDock.kt`, `BrowserMediaService.kt`, `MediaNotif.kt` | Media dock and notifications |
| `BrowserState.kt`, `AurexWebView.kt`, `MainActivity.kt` | Core browser state and WebView |
| `SettingsPage.kt`, `Ui.kt`, `Theme.kt`, `Components.kt` | Interface |
| `Updater.kt` | In-app updates from GitHub Releases |

</details>

## 🤝 Contributing

Contributions are welcome.

1. Fork the repo and create a branch: `git checkout -b my-feature`
2. Make your change and confirm `./gradlew assembleDebug` succeeds
3. Open a pull request that explains what changed and why

Found a bug? [Open an issue](https://github.com/aman-nitb-cse/Aurex-Browser/issues). Settings → General → **Copy log** gives you useful details to paste into it. Please never commit keystores, passwords or `local.properties`.

## ⚖️ Responsible use

The downloader relies on yt-dlp. You are responsible for respecting copyright law and the terms of service of the sites you use it with.

## 🙏 Credits

Aurex is built on the work of many open-source projects: [yt-dlp](https://github.com/yt-dlp/yt-dlp), [youtubedl-android](https://github.com/JunkFood02/youtubedl-android), [FFmpeg](https://ffmpeg.org), [AndroidX Media3](https://github.com/androidx/media), [Jetpack Compose](https://developer.android.com/jetpack), [OkHttp](https://github.com/square/okhttp), [Kotlin Coroutines](https://github.com/Kotlin/kotlinx.coroutines), [EasyList](https://easylist.to), [uBlock Origin filters](https://github.com/uBlockOrigin/uAssets), [AdGuard filters](https://github.com/AdguardTeam/AdguardFilters), [StevenBlack hosts](https://github.com/StevenBlack/hosts) and [Peter Lowe's ad-server list](https://pgl.yoyo.org/adservers/). Filter lists are downloaded at runtime from their authors. The full list with licenses is in the app under **Settings → About → Open-source credits**.

## 📄 License

Aurex Browser is free software, licensed under the **GNU General Public License v3.0**. See [LICENSE](LICENSE).

---

<div align="center">

Made with ❤️ by **AMAN**

[![Telegram](https://img.shields.io/badge/Telegram-@aurex__oss-26A5E4?logo=telegram&logoColor=white)](https://t.me/aurex_oss)
[![GitHub](https://img.shields.io/badge/GitHub-aman--nitb--cse-181717?logo=github&logoColor=white)](https://github.com/aman-nitb-cse)

⭐ If you like Aurex, please star the repo and share it with friends.

</div>
