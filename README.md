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
- **Music in the row.** The playing app's favorite row turns into the player, with album art, title,
  play/pause, previous/next and a seek bar you can drag. Its colors come from the album art and stay readable
  in both text modes. Swipe the row left to skip to the next track. If the app isn't a favorite, a temporary
  player row appears above your favorites. A player that's paused when you come home folds down to one line,
  and it steps aside after 30 minutes paused, or when you choose "Hide player" from the long-press menu.
- **Resume with headphones.** With headphones (wired, USB or Bluetooth) connected and nothing playing, a
  "Resume Spotify" row (or whichever app played last) shows above your favorites, with the track it stopped on.
  Tap it to pick up where you left off; long-press for "Not now" until the next time headphones connect.
- **Dark text on light wallpapers.** Text, icons and the status bar switch to dark automatically when your
  wallpaper is light. You can also force white or dark text in settings.
- **Long-press menu.** Shows the app's notifications and shortcuts, plus actions to favorite, rename, hide
  from the list, open app info, or uninstall.
- **Search.** Matches app names by prefix, word start, initials ("gm" finds Google Maps), or fuzzy match.
  Enter opens the top result; when no app matches, it searches the web. Hidden apps still show up in search.
  In settings you can turn web search off, leave hidden apps out, or have an app open by itself once it's the
  only match.
- **Gestures.**
  - Swipe down on the home page to open the notification shade (or quick settings, search, or nothing).
  - Double-tap empty space to lock the screen, open notifications or search (off by default). Locking uses
    an accessibility service you turn on once; it reads nothing on screen.
  - Press Home to jump back to the top.
  - Long-press empty space for wallpaper and settings.
- **Clock.** Pick Classic, Bold or Stacked, and 12- or 24-hour time (or follow the phone). Tap the time to open
  your alarms, or tap the date to open your calendar. Weather can sit beside the date (from Open-Meteo, for a
  place you pick; tap it for the forecast). Chips under the date show what's next, each one optional:
  - running timers, stopwatches and calls, ticking live (from apps that use Android's standard notification
    chronometer)
  - the next alarm, with a countdown when it's less than a day away
  - the next calendar event (optional; asks for calendar access)
  - charging progress with "full in…", or a warning when the battery is low (or the level all the time)
- **Icon size.** Small, Medium, Large or XL for the home screen and the A–Z list. The music player row
  scales with them.
- **Monochrome icons.** Uses Android 13+ themed icons where apps provide them. Other icons turn grayscale.
  You can also turn icons off for a text-only list.
- **Self-update.** When home opens (at most every 6 hours), Cascade checks GitHub Releases. If there's a newer
  version, an update card offers it. Cascade downloads the APK, makes sure it's Cascade signed with the same
  key, and hands it to Android's installer. Android restarts the home screen on the new version. You can
  also check from *Settings → About*, or turn automatic checks off.
- **Settings backup.** *Settings → Backup* saves your favorites, hidden apps, renames and every setting to a
  JSON file you choose. *Restore settings* loads one back after showing what it will replace.
- **Settings.** Pages for the home screen, appearance, clock, gestures, search, backup and updates, with a live
  preview of your home screen, search across every setting, and drag-to-reorder for favorites. Also: wallpaper
  dimming, hiding the status bar, and turning vibration off.
- **Material You.** Accent colors come from your wallpaper on Android 12+.
- **Work profile.** Work apps appear with the work badge.

## Build

Requirements: JDK 17+ and the Android SDK (API 36).

```bash
./gradlew assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease      # minified, ~1.4 MB
```

Add `-PcascadeComposeReports` to a build to also write the Compose compiler's stability reports to `app/build/compose_compiler`.

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

A tag builds exactly that version. Every other build (pushes to `main`, local builds) is `X.Y.Z-dev.N`, N commits
after the last `v*` tag, so it installs over that release and the next tag installs over it.

The signing key comes from two repository secrets, `KEYSTORE_BASE64` (the base64-encoded `release.jks`)
and `KEYSTORE_PASSWORD`. For signed local builds, put the same files in `.signing/` as `release.jks` and
`keystore.properties` (containing `password=...`; write a backslash as `\\`). Both are git-ignored. Without
`.signing/release.jks`, local release builds use the debug key and print a warning; with `release.jks` but no
password, release builds fail.

Keep a backup of the key. If it's lost, existing installs can't be updated.

## Permissions

| Permission | Why |
| --- | --- |
| `QUERY_ALL_PACKAGES` | A launcher has to list every installed app. |
| Notification access (optional) | Powers the notification dots and previews, and the music controls. Nothing leaves the device. |
| `EXPAND_STATUS_BAR` | Lets swipe-down open the notification shade. |
| `REQUEST_DELETE_PACKAGES` | Powers "Uninstall" in the app menu. |
| `SET_ALARM` | Lets a tap on the clock open the alarm list; clock apps require it. Cascade never sets alarms. |
| `READ_CALENDAR` (optional) | Shows the next event under the clock. Only requested when you turn that on. |
| `INTERNET` | Only for self-update: asking GitHub for the latest release and downloading its APK. |
| `REQUEST_INSTALL_PACKAGES`, `UPDATE_PACKAGES_WITHOUT_USER_ACTION` | Installing those updates; the second lets Android skip the confirmation once Cascade installed itself. |

The only network traffic is the update check and download, both with GitHub. Nothing else leaves the device.

On Android 13+, if you install the APK from a browser or file manager (not `adb` or an app store), Android
may block notification access as a "restricted setting". To allow it, go to *App info → ⋮ → Allow
restricted settings*, then grant access.

## Project layout

```
app/src/main/java/com/gh00ul/cascade/
  data/            app list + icons (AppRepository), settings (Prefs), search
  notifications/   NotificationListenerService, the notification store, and media sessions (NowPlaying)
  ui/home/         home screen, clock header, alphabet wave, app rows, music player, search, sheets, update card
  ui/theme/        colors, text styles, and the wallpaper brightness check
  settings/        settings screens
  update/          self-update from GitHub Releases (Updater, install result receiver)
  util/            launching apps, shortcuts, system intents, calendar
```

## Ideas for next steps

- Widgets on the home page
- Icon pack support
- Double-tap to lock (needs an accessibility service)
- Translations (strings are inline English for now)

## License

MIT. See [LICENSE](LICENSE).
