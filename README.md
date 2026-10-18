# Restv

A personal IPTV player for Google TV, built for Xtream Codes subscriptions: fast live TV, 4K first,
categories you can actually sort, and a player that reconnects on its own.

## Install on the TV

1. Install **Downloader** from the Google TV Play Store.
2. Open the latest release on this repo's Releases page and copy the `.apk` link into Downloader.
3. Allow Downloader to install unknown apps when the TV asks.

After the first install, open **Settings → Check for updates** inside Restv to update in place.

## What works now (milestone 1)

- Account + Xtream login, catalogue sync into a local database
- Live TV: categories grouped by country, channels sorted 4K first, now/next from the panel's guide
- Category manager: hold OK on a category → Move, Pin to top, Hide
- Favourites: hold OK on a channel
- Player: auto-reconnect with backoff, stall watchdog, automatic ts → m3u8 fallback remembered per channel,
  exact resolution / fps / codec in the info bar, aspect modes (Fit, Stretch, Zoom)
- Self-update from GitHub Releases

## Remote

| Where | Key | Action |
| --- | --- | --- |
| Lists | OK | Open / play |
| Lists | Hold OK | Category options, or add/remove favourite |
| Player | ▲ ▼ | Previous / next channel |
| Player | ▶ or Menu | Change aspect |
| Player | OK | Show / hide info |
| Anywhere | Back | Go back |

## Building

Every push to `main` builds a signed APK on GitHub Actions and publishes it as a release (`v1.0.<run>`).
Local builds: `./gradlew assembleRelease` with an Android SDK configured in `local.properties`.

The signing key in `signing/` is committed for convenience so every build can update the last one.
Move it to CI secrets before distributing this app to anyone else.
