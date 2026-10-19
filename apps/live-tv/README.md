# Live TV (Android TV)

A fork of the `tv/master` branch of
[kananinirav/Indian-IPTV-App](https://github.com/kananinirav/Indian-IPTV-App) (MIT, see `LICENSE`),
reworked for TV remotes and low-memory TVs.

It's a player for public M3U playlists from [iptv-org](https://github.com/iptv-org/iptv). It doesn't
host any content, and channels can go offline at any time.

## What changed from upstream

- **Categories in a sidebar:** All, News, Entertainment, Movies, Music, Sports, Kids, Infotainment,
  Business, Devotional, Other, with counts. Categories and logos come from the iptv-org India index
  playlist, matched by channel ID, then by name.
- **1080p only:** only streams whose name contains `(1080p)` are kept. Several 1080p streams of the
  same channel become one tile with backup streams. Change the check in `_buildChannels` in
  `lib/provider/channels_provider.dart`.
- **Faster:** playlists download in parallel and are parsed off the UI thread; the result is cached so
  the app opens instantly and refreshes in the background; the grid builds lazily and logos are decoded
  small.
- **Remote-friendly UI:** dark theme, small logo tiles, deterministic D-pad movement (Right into the
  grid, Left from the first column back to the sidebar).
- **Player:** full screen with a channel banner; Up/Down or Channel +/- changes channel; a stream that
  errors or doesn't start within 12 seconds falls back to a backup stream; the screen stays awake while
  playing.
- **Icons:** light logos go on dark cards and dark logos on light cards; channels without a logo get a
  generated icon (colour from the name plus initials).
- **App icon and TV banner:** drawn by `../make_live_tv_icons.py`. The manifest uses a real 16:9 banner.
- **Build:** Gradle 8.14.3, Android Gradle Plugin 8.11.1, Kotlin 2.2.20 (needed by current Flutter).
  Plugins are pinned to NDK 27 in `android/build.gradle` so only one NDK is downloaded.

The channel catalog (`data/streams.csv`) is still read from the upstream repository.

## Build

```bash
flutter build apk --release --target-platform android-arm --android-skip-build-dependency-validation
```

Use `android-arm64` for 64-bit TVs. `../../build_live_tv.sh` builds and installs it in one step.
