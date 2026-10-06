# JVM screenshots

Renders Cascade's Compose UI to PNG files on this machine, with no emulator or device. It uses Robolectric 4.17 (native graphics, SDK 36) and Roborazzi 1.76 inside the `app` unit test source set. The harness is test-only and adds nothing to the APK.

## Run

From Git Bash in the repo root:

```sh
./gradlew.bat testDebugUnitTest -PcascadeScreenshots --console=plain
```

- **Only some shots:** add `-PcascadeScreenshotFilter=Home,Media_Live`. Each comma-separated part is a case-insensitive regex matched against shot names, and a shot matching any part is rendered. Use commas rather than `|`, which `gradlew.bat` reads as a pipe. A run narrowed with `-PcascadeScreenshotFilter` or Gradle's `--tests` leaves the other PNGs in place; a full run clears the folder first.
- **Output:** `app/build/screenshots/<Area>_<Thing>_<variant>.png` (gitignored). The run ends by printing the folder and the PNG count, and each test logs `Screenshot written: <path>`.
- **Speed:** about 20 s once the build is warm.
- **Plain runs:** `./gradlew.bat testDebugUnitTest` skips the screenshot classes and runs only the unit tests (search, prefs, notification store, player state, A–Z sections, updater, clock text, clock lifecycle, calendar choice, row recomposition, motion settling).
- **Use `testDebugUnitTest`, not `test`:** `test` also runs the release unit tests, which would render everything twice.

## Variants and canvas

- The canvas is 412x915 dp at xxhdpi (1236x2745 px). `Frame.Screen` fills it. `Frame.Component` is full width, and its height wraps the content.
- Launcher shots come in `whiteText` and `darkText` (`LauncherStyle`), drawn over a generated sample wallpaper: a dusk sky with hills, or a day sky with clouds. Component shots show the lower part of the same wallpaper, where favorites sit.
- Settings shots come in `light` and `dark`. Sheet shots come in `dark` only, since sheets on the home screen are always dark.
- **Time:** every shot is taken on Monday, October 5 2026 at 9:41 AM in `America/Los_Angeles` (`FIXED_NOW` and `FIXED_ZONE` in `testing/FixedClock.kt`). The home clock reads it through `LocalNow`, Robolectric's clock is set to it, and fixture times are relative to it. The PNGs come out byte-identical from run to run and in any host time zone, so a changed PNG means changed UI.
- **Version:** the Settings About row shows a fixed `1.0.0`, set by `SettingsScreenshots`. Real builds take their version from `git describe`.
- **Motion:** each shot is captured after `waitForIdle`, which runs every animation to its end, so shots show resting states (a chip that arrives late has faded in, and `AlphabetWave_Dragging`'s wave has settled under the held finger).

## Shots

| Area | Shots |
|---|---|
| Home | `Home_Classic_Chips` (timer, event, alarm and charging chips), `Home_Bold`, `Home_Stacked`, `Home_IconsSmall`, `Home_IconsXL`, `Home_MusicPlaying`, `Home_NotificationsExpanded`, `Home_Onboarding`, `Home_UpdateCard` (an update on offer), `Home_UpdateDownloading` (at 42%), `Home_Empty` (no favorites yet: the first-run hint) |
| Rows | `AppRow_FavoritePreview`, `AppRow_Expanded`, `AppRow_List` (with the work-profile twin), `Media_Playing`, `Media_PausedResting`, `Media_NoArt`, `Media_Live`, `Media_Notifications` (the player's notifications swiped open) |
| List | `Search_Results` ("ca" typed), `List_Top` (the search pill, the gear and the first sections), `AlphabetWave_Idle` (scrolled to the A–Z list), `AlphabetWave_Dragging` (a finger held on M: the wave, the accent letter and the list jumped to M) |
| Sheets | `Sheet_AppActions` (long-press on Messages: notifications, shortcuts and actions), `Sheet_HomeMenu` (long-press on the home screen) |
| Settings | `Settings_Main`, `Settings_MainMiddle` (the Appearance section), `Settings_MainBottom` (everything granted, so Setup sits above About), `Settings_SetupNeeded` (notification access off, so Setup comes first) |

## Add a shot

Add a `@Test` to a class in `app/src/test/java/com/gh00ul/cascade/screenshots/` that extends `ScreenshotTest`. Each `@Test` calls `snap` once:

```kotlin
@Test fun mine() = snap("Home_Mine") {
    HomeScreen(LauncherSettings(favorites = favorites.map { it.key }), apps, favorites, icons, FakeNotifications.byApp())
}

@Test fun row() = snap("AppRow_Mine", Frame.Component) { RowBackdrop { AppRow(...) } }
```

- **Names:** use `<Area>_<Thing>`, so a filter can pick out a whole area.
- **Fake data:**
  - `apps`, `favorites` and `icons` come from `testing/FakeApps.kt`: about 30 apps with generated monogram icons.
  - `FakeNotifications` provides notifications, posted a few minutes to hours before `FIXED_NOW`.
  - `media.playing()`, `pausedResting()`, `noArt()` and `live()` are backed by a real Robolectric `MediaSession`.
  - `ClockFixtures.install(compose.activity)` sets up an alarm, a timer, a calendar event and charging. Pass `afterContent = ClockFixtures.awaitEventChip` so the capture waits for the event chip.
- **`HomeScreen`:** this is `LauncherScreen`'s layout with its state passed in. `LauncherScreen` itself needs `LauncherApplication`'s repository. The scrim (`homeScrim`) and the A–Z rows (`ListAppRow`) are `LauncherScreen`'s own; the frame hands them the icon and notification maps as states, as `LauncherScreen` does. `onboarding` is the card under the clock, drawn as given; `LauncherScreen` picks the card and cross-fades between them, which looks the same at rest. Search runs on the same transition as in `LauncherScreen`: the overlay, the list's alpha and the alphabet strip fade, and at rest they draw as before. `onSearchDismiss` (search's back and taps outside) lets a test close search the way a user would.
- **Update card:** build an `Updater.State` (`Available`, `Downloading`, …) and pass `UpdateCard` as `onboarding`. Never call `Updater.check` or `install` from a shot.
- **`afterContent`:** runs before each capture. Use it to type, scroll, touch or wait. A finger held down with `performTouchInput { down(…) }` is still down when the next variant runs, so lift it (`up()`) before pressing again.
- **Visibility:** test code can call `internal` composables, but not `private` ones.

## Limitations

- **Insets** are zero, so the canvas has no status bar or navigation bar.
- **Colors:** Material You colors come from Robolectric's default system palette, not a real wallpaper.
- **Fonts:** text uses Roboto.
- **Sheets and dialogs** (`AppActionsSheet`, `RenameDialog`, `HomeMenuSheet`) open in their own window, so a node capture misses them, and they need `LauncherApplication`. The sheet shots render the sheets' stateless content (`AppActionsContent`, `HomeMenuContent`) inline instead, on a surface shaped like the sheet with its drag handle. `RenameDialog` is still not shot.
- **Application class:** screenshots run on a plain `Application`, so the app repository and the notification listener never start. `SettingsScreenshots` switches to `LauncherApplication`, where Robolectric lists no apps, so its counts come from the stored prefs.
- **JDK:** the tests need a JDK 21 runtime for SDK 36. CI does not run them.
