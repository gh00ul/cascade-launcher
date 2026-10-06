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
- **Plain runs:** `./gradlew.bat testDebugUnitTest` skips the screenshot classes and runs only the unit tests (search, prefs, notification store, player state, A–Z sections, updater, clock text, clock lifecycle, calendar choice, row recomposition, motion settling, home and list snapping, moving favorites on home, notification previews, login codes, search commands, Settings pages and shortcuts in search, settings backup, folders, widget stack, forecast parsing, calendar agenda).
- **Use `testDebugUnitTest`, not `test`:** `test` also runs the release unit tests, which would render everything twice.

## Variants and canvas

- The canvas is 412x915 dp at xxhdpi (1236x2745 px). `Frame.Screen` fills it. `Frame.Component` is full width, and its height wraps the content.
- Launcher shots come in `whiteText` and `darkText` (`LauncherStyle`), drawn over a generated sample wallpaper: a dusk sky with hills, or a day sky with clouds. Component shots show the lower part of the same wallpaper, where favorites sit.
- Settings shots come in `light` and `dark`. Sheet shots come in `dark` only, since sheets on the home screen are always dark.
- **Time:** every shot is taken on Monday, October 5 2026 at 9:41 AM in `America/Los_Angeles` (`FIXED_NOW` and `FIXED_ZONE` in `testing/FixedClock.kt`). The home clock reads it through `LocalNow`, Robolectric's clock is set to it, and fixture times are relative to it. The PNGs come out byte-identical from run to run and in any host time zone, so a changed PNG means changed UI.
- **Version:** the Settings About page shows a fixed `1.0.0`, set by `SettingsScreenshots`. Real builds take their version from `git describe`.
- **Motion:** each shot is captured after `waitForIdle`, which runs every animation to its end, so shots show resting states (a chip that arrives late has faded in, and `AlphabetWave_Dragging`'s wave has settled under the held finger).

## Shots

| Area | Shots |
|---|---|
| Home | `Home_Classic_Chips` (timer, event, alarm and charging chips), `Home_Glance` (weather beside the date, a 24-hour clock, the battery level always shown), `Home_Bold`, `Home_Stacked`, `Home_IconsSmall`, `Home_IconsXL`, `Home_MusicPlaying`, `Home_NotificationsExpanded` (Messages' notifications open, with Clear all and Show less), `Home_Reorder` (Mail held up and dragged most of a row: it rides on its pill and Messages slides into its place), `Home_LiveChips` (a route in Maps, a download filling its chip, and a ride's Live Update with its short status), `Home_Menu` (a long press on empty space: the home menu's tiles pop from the finger), `Home_Onboarding`, `Home_UpdateCard` (an update on offer), `Home_UpdateDownloading` (at 42%), `Home_Empty` (no favorites yet: the first-run hint) |
| Folders | `Home_Folders` (two folders among the favorites; Work previews Mail's notification), `Home_FoldersNoIcons`, `Home_FolderOpen` (a tap on Work: its pop-up above the row), `Home_FolderOpenLarge` (Utilities, large icons) |
| Widgets | `Home_WidgetCalendar` (the stack under the clock on its calendar page: today's all-day birthday, a meeting under way, lunch, then tomorrow), `Home_WidgetWeather` (the weather page first, with the readout beside the date off), `Widget_Calendar`, `Widget_Weather` (the next five hours, rain chances), `Widget_WeatherWide` (the Galaxy Tab's 753 dp width: a 560 dp card with the next four days beside the hours), `Widget_CalendarNoAccess` (calendar access off: Allow), `Widget_WeatherNoPlace` (no place picked), `Sheet_WidgetStack` (the stack's edit sheet), `Sheet_WidgetPicker` (Add a widget: Cascade's two, then apps' widgets by app), `Settings_Widgets` |
| Rows | `AppRow_FavoritePreview`, `AppRow_Expanded`, `AppRow_LoginCode` (a code's copy pill beside "+1"), `AppRow_List` (with the work-profile twin), `Media_Playing`, `Media_PausedResting`, `Media_NoArt`, `Media_Live`, `Media_Notifications` (the player's notifications swiped open) |
| List | `Search_Results` ("ca" typed, with the Cast settings page after the apps), `Search_Timer` ("10m": a timer to start, tinted for Go), `Search_Directions` ("nav pike place market"), `Search_Settings` ("hotspot"), `List_Top` (the search pill, the gear and the first sections), `AlphabetWave_Idle` (scrolled to the A–Z list), `AlphabetWave_Dragging` (a finger held on M: the wave, the accent letter and the list jumped to M) |
| Sheets | `Sheet_AppActions` (long-press on Messages: its buttons, then notifications, shortcuts and more in cards), `Sheet_AppActionsInFolder` (long-press on an app in a folder, the other folders open), `Sheet_FolderActions` (long-press on a folder) |
| Settings | `Settings_Main` (search, the live home preview, the pages), `Settings_MainBottom`, `Settings_SetupNeeded` (notification access off: the setup card), `Settings_Home`, `Settings_Favorites` (drag handles), `Settings_FavoritesFolders` (a folder among them), `Settings_Folder` (one folder's page), `Settings_AddToFolder`, `Settings_Hidden`, `Settings_Appearance`, `Settings_AppearanceBottom`, `Settings_Clock`, `Settings_ClockBottom`, `Settings_Gestures` (double-tap set to lock, service off), `Settings_Search`, `Settings_Backup`, `Settings_About`, `Settings_Find` (settings search for "icon") |

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
  - `WidgetFixtures.installAgenda()` grants calendar access and answers the agenda's query with colored events (`AgendaProvider`), and `Weather.showForTest(WidgetFixtures.reading(place))` shows a reading with its forecast, so no shot goes to the network. Wait with `WidgetFixtures.awaitAgenda` or `awaitWeather`. Call `resetWidgetStackMemory()` first: the stack remembers its page for the process.
- **`HomeScreen`:** this is `LauncherScreen`'s layout with its state passed in. `LauncherScreen` itself needs `LauncherApplication`'s repository. The scrim (`homeScrim`) and the A–Z rows (`ListAppRow`) are `LauncherScreen`'s own; the frame hands them the icon and notification maps as states, as `LauncherScreen` does. `onboarding` is the card under the clock, drawn as given; `LauncherScreen` picks the card and cross-fades between them, which looks the same at rest. Search runs on the same transition as in `LauncherScreen`: the overlay, the list's alpha and the alphabet strip fade, and at rest they draw as before. `onSearchDismiss` (search's back and taps outside) lets a test close search the way a user would. `items` takes home rows with folders (`homeItems(settings, …)`); a tap on a folder row (`onNodeWithText("Work").performClick()`) pops it open as in `LauncherScreen`.
- **Widget stack:** pass `widgets = { WidgetStack(settings, onEdit = {}) }` to `HomeScreen`, as `LauncherScreen` fills `HomePage`'s slot, or draw it alone in `RowBackdrop` for a `Frame.Component` shot. A tablet shot takes `@Config(qualifiers = …)` on the test, as `Widget_WeatherWide` does.
- **Update card:** build an `Updater.State` (`Available`, `Downloading`, …) and pass `UpdateCard` as `onboarding`. Never call `Updater.check` or `install` from a shot.
- **`afterContent`:** runs before each capture. Use it to type, scroll, touch or wait. A finger held down with `performTouchInput { down(…) }` is still down when the next variant runs, so lift it (`up()`) before pressing again.
- **Visibility:** test code can call `internal` composables, but not `private` ones.

## Limitations

- **Insets** are zero, so the canvas has no status bar or navigation bar.
- **Colors:** Material You colors come from Robolectric's default system palette, not a real wallpaper.
- **Fonts:** text uses Roboto.
- **App widgets** can't be hosted under Robolectric, so the stack's shots use Cascade's own calendar and weather widgets; the picker's app widgets have no preview images.
- **Sheets and dialogs** (`AppActionsSheet`, `FolderActionsSheet`, `RenameDialog`, `FolderNameDialog`) open in their own window, so a node capture misses them, and they need `LauncherApplication`. The sheet shots render the sheets' stateless content (`AppActionsContent`, `FolderActionsContent`) inline instead, on a surface shaped like the sheet with its drag handle. The dialogs are still not shot.
- **Application class:** screenshots run on a plain `Application`, so the app repository and the notification listener never start. `SettingsScreenshots` and `FolderSettingsScreenshots` switch to `LauncherApplication` and installs ten fake apps (plain colour icons) through `FakeLauncherApps`, which the real repository loads. Its home preview draws over the stand-in gradient, since Robolectric reports no wallpaper colors.
- **JDK:** the tests need a JDK 21 runtime for SDK 36. CI does not run them.
