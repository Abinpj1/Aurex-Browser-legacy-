# Changelog

Format: [Keep a Changelog](https://keepachangelog.com/en/1.1.0/). Versions follow [SemVer](https://semver.org/).

## [1.1.0] - 2026-11-16

### Added
- **App and website language** (Settings → Language): 36 languages, translated on-device. One-time ~30 MB pack per language, then works offline. Auto follows the phone.
- **Downloads in Gallery** (Android 10+): finished videos, music and images also appear under Movies / Music / Pictures → AurexBrowser.
- **ok.ru** video downloads.
- **Embedded players**: videos inside iframes and links without a file extension are now detected and can be saved.

### Fixed
- Downloads missing from Gallery, Music and Files on Android 11+.
- Download buttons missing on pages with an embedded player.
- Downloads failing on hosts that require the embed page as Referer.
- Crash in the updater when a download returned no data.

### Changed
- Faster page and download code paths; larger Gradle memory for builds.
- New dependency: ML Kit Translate 17.0.3.

## [1.0.0] - 2026-11-15

First public release: ad, tracker and popup blocking, private tabs with phone lock, built-in video player, universal video downloader (yt-dlp + FFmpeg), customisable glass UI, and in-app updates from GitHub Releases.

[1.1.0]: https://github.com/aman-nitb-cse/Aurex-Browser/compare/v1.0.0...v1.1.0
[1.0.0]: https://github.com/aman-nitb-cse/Aurex-Browser/releases/tag/v1.0.0
