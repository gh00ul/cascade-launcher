# Cascade

A free, open-source, minimalist Android launcher inspired by Niagara Launcher. One vertical list, no
home-screen grid, everything reachable with one thumb.

## Download

Get the latest APK from [Releases](https://github.com/gh00ul/cascade-launcher/releases/latest), install it,
open Cascade and tap **Set as default**. Requires Android 8.0 or newer.

If you installed 0.1.0, uninstall it first. Releases from 0.2.0 on are signed with the project's own key,
and Android won't update an app across a key change.

## Features

- **One continuous list.** The home page shows a clock and your favorite apps near the bottom of the screen.
  Scroll down and the full A–Z app list continues below.
- **Alphabet wave.** Slide your thumb along the letters on the right edge. The letters near your finger
  swell and bulge out, and the list jumps to that letter. You get a haptic tick on each letter.
- **Notifications in the list.** Apps with notifications get a dot. Favorites also show the latest message
  underneath; tap it to open that notification.
- **Swipe an app to see its notifications.** Swipe a row to the right to expand all of that app's
  notifications in place. Tap one to open it, swipe it sideways to dismiss it, or clear them all.
- **Music controls.** While something plays, a card above your favorites shows the album art, title and
  artist, with previous, play/pause and next buttons and a progress bar. Tap the card to open the player.
- **Dark text on light wallpapers.** Text, icons and the status bar switch to dark automatically when your
  wallpaper is light. You can also force white or dark text in settings.
- **Long-press menu.** Shows the app's notifications and shortcuts, plus actions to favorite, rename, hide
  from the list, open app info, or uninstall.
- **Search.** Matches app names by prefix, word start, initials ("gm" finds Google Maps), or fuzzy match.
  Enter opens the top result; when no app matches, it searches the web. Hidden apps still show up in search.
- **Gestures.**
  - Swipe down on the home page to open the notification shade (or search; you can change this in settings).
  - Press Home to jump back to the top.
  - Long-press empty space for wallpaper and settings.
- **Clock.** Tap the time to open your alarms, or tap the date to open your calendar. The next alarm is
  shown underneath.
- **Monochrome icons.** Uses Android 13+ themed icons where apps provide them. Other icons turn grayscale.
  You can also turn icons off for a text-only list.
- **Material You.** Accent colors come from your wallpaper on Android 12+.
- **Work profile.** Work apps appear with the work badge.

## Build

Requirements: JDK 17+ and the Android SDK (API 36).

```bash
./gradlew assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease      # minified, ~1.4 MB
```

Install the debug build on a connected device:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Open Cascade and tap **Set as default**. You can also do this later in
*Settings → Apps → Default apps → Home app*.

### Trying the music card without a music app

Debug builds include a fake media session:

```bash
adb shell am broadcast -n com.gh00ul.cascade/.FakeMediaReceiver --es cmd start
```

Send `--es cmd stop` to end it.

## Releases

GitHub Actions ([build.yml](.github/workflows/build.yml)) builds a signed APK on every push to `main` and
keeps it as a run artifact. Pushing a version tag also publishes a GitHub Release:

```bash
git tag v0.3.0
git push origin v0.3.0
```

The signing key comes from two repository secrets, `KEYSTORE_BASE64` (the base64-encoded `release.jks`)
and `KEYSTORE_PASSWORD`. For signed local builds, put the same files in `.signing/` as `release.jks` and
`keystore.properties` (containing `password=...`). Both are git-ignored. Without them, local release builds
fall back to the debug key.

Keep a backup of the key. If it's lost, existing installs can't be updated.

## Permissions

| Permission | Why |
| --- | --- |
| `QUERY_ALL_PACKAGES` | A launcher has to list every installed app. |
| Notification access (optional) | Powers the notification dots and previews, and the music controls. Nothing leaves the device. |
| `EXPAND_STATUS_BAR` | Lets swipe-down open the notification shade. |
| `REQUEST_DELETE_PACKAGES` | Powers "Uninstall" in the app menu. |

There is no internet permission, so Cascade can't send anything anywhere.

On Android 13+, if you install the APK from a browser or file manager (not `adb` or an app store), Android
may block notification access as a "restricted setting". To allow it, go to *App info → ⋮ → Allow
restricted settings*, then grant access.

## Project layout

```
app/src/main/java/com/gh00ul/cascade/
  data/            app list + icons (AppRepository), settings (Prefs), search
  notifications/   NotificationListenerService, the notification store, and media sessions (NowPlaying)
  ui/home/         home screen, alphabet wave, app rows, music card, search overlay, long-press sheets
  ui/theme/        colors, text styles, and the wallpaper brightness check
  settings/        settings screens
  util/            launching apps, shortcuts, system intents
```

## Ideas for next steps

- Widgets on the home page
- Icon pack support
- Double-tap to lock (needs an accessibility service)
- In-app updates from GitHub Releases
- Translations (strings are inline English for now)

## License

MIT. See [LICENSE](LICENSE).
