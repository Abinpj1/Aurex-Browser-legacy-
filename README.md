# Aurex Browser

A fast, privacy-focused Android web browser built with Kotlin and Jetpack Compose, with a built-in
ad/tracker blocker, a media player, and a video/audio downloader.

> **Status:** active development. Issues and pull requests are welcome.

## Features

- **Ad & tracker blocking** - EasyList / EasyPrivacy / uBlock Origin / AdGuard-style filters and host lists, with a token-indexed filter engine
- **Pop-up and cookie guards** for cleaner browsing
- **Adult-site filter** protected by your phone's own lock (fingerprint / face / PIN / pattern)
- **Private tabs** that ask for your phone lock before opening
- **Built-in video player** (Media3 / ExoPlayer) with Picture-in-Picture and a media notification
- **Downloader** for video and audio powered by yt-dlp + FFmpeg
- **Dark mode engine**, long (scrolling) screenshots, gesture and toolbar customisation
- **In-app updates** from GitHub Releases

## Requirements

| | |
|---|---|
| Android | 7.0+ (minSdk 24) |
| Build tools | JDK 17, Android SDK Platform 35, Android Studio (recent) or Gradle 8.14+ |

## Build

```bash
git clone https://github.com/aman-nitb-cse/Aurex-Browser.git
cd Aurex-Browser
./gradlew assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/`. You can also just open the folder in
Android Studio and press **Run**.

> If `gradle/wrapper/gradle-wrapper.jar` is missing, run `gradle wrapper --gradle-version 8.14.6` once.

Without any extra setup, builds are signed with Android's default **debug key**.

## Signing your own release builds

Release signing keys are **never** stored in this repository.

1. Create a key: `keytool -genkeypair -v -keystore aurex.keystore -alias aurex -keyalg RSA -keysize 2048 -validity 10000`
2. Create a file named `keystore.properties` in the project root (it is git-ignored):

   ```properties
   storeFile=aurex.keystore
   storePassword=YOUR_PASSWORD
   keyAlias=aurex
   keyPassword=YOUR_PASSWORD
   ```
3. Run `./gradlew assembleRelease`. The APK is in `app/build/outputs/apk/release/`.

APKs signed with a different key can't update an installation signed with another key.

## Publishing a release (maintainers)

The in-app updater reads GitHub Releases. For each new version:

1. Increase `versionCode` and update `versionName` in `app/build.gradle.kts`
2. Build the signed APK (see above)
3. On GitHub: **Releases -> Draft a new release**, create a tag equal to `v` + `versionName`
   (for example `v1.0.1`), attach the `.apk`, and publish

## Contributing

1. Fork the repo and create a branch: `git checkout -b my-feature`
2. Make your change and make sure `./gradlew assembleDebug` succeeds
3. Open a pull request describing what and why

Please never commit keystores, passwords or `local.properties`.

## Responsible use

The downloader relies on yt-dlp. You are responsible for respecting copyright law and the terms of
service of the sites you use it with.

## License

Aurex Browser is licensed under the **GNU General Public License v3.0 or later** - see [LICENSE](LICENSE).
It bundles or builds on GPL-licensed components (youtubedl-android, FFmpeg builds and several
filter lists); see **Settings -> About -> Open-source credits** in the app for the full list.

Third-party filter lists are downloaded at runtime from their respective authors.
