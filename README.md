# Cascade

A free, open-source, minimalist Android launcher inspired by Niagara Launcher. One vertical list, no
home-screen grid, everything reachable with one thumb.

## Features

- **One continuous list.** The home page shows a clock and your favorite apps near the bottom of the screen.
  Scroll down and the full A–Z app list continues below.
- **Alphabet wave.** Slide your thumb along the letters on the right edge. The letters near your finger
  swell and bulge out, and the list jumps to that letter. You get a haptic tick on each letter.
- **Notifications in the list.** Apps with notifications get a dot. Favorites also show the latest message
  underneath; tap it to open that notification.
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
./gradlew assembleRelease      # minified, ~1.3 MB
```

Install the debug build on a connected device:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Open Cascade and tap **Set as default**. You can also do this later in
*Settings → Apps → Default apps → Home app*.

Release builds are signed with your local debug key so they install right away. Before you publish them
anywhere, set up your own signing config in `app/build.gradle.kts`.

## Permissions

| Permission | Why |
| --- | --- |
| `QUERY_ALL_PACKAGES` | A launcher has to list every installed app. |
| Notification access (optional) | Powers the notification dots and previews. Nothing leaves the device. |
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
  notifications/   NotificationListenerService and the in-memory notification store
  ui/home/         home screen, alphabet wave, app rows, search overlay, long-press sheets
  settings/        settings screens
  util/            launching apps, shortcuts, system intents
```

## Ideas for next steps

- Widgets on the home page
- Icon pack support
- Double-tap to lock (needs an accessibility service)
- Swipe an app row sideways to expand its notifications
- Translations (strings are inline English for now)

## License

MIT. See [LICENSE](LICENSE).
