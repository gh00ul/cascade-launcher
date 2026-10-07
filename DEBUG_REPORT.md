# Debug and hardening report

Branch `hardening/debug-pass`, started 2026-10-06 from `main` at v0.18.0 (08731b0).

<!-- SUMMARY: filled in at the end (Phase 8) -->

## Phase 1: project map

Found by the Architecture, Dependency and Data Layer agents (read-only), merged by the lead.

### Shape

One Gradle module (`:app`, package `com.gh00ul.cascade`), Kotlin 2.2.20 and Jetpack Compose, minSdk 26, target and
compile SDK 36. No DI framework, ViewModels, Room, DataStore, Retrofit/OkHttp or navigation library. The only service
locator is `Context.launcher`, which casts the application context to `LauncherApplication` for `prefs`, `repository`
and `scope`. Nothing in `app/src/main` logs (no `Log.*`, `println` or `printStackTrace`).

### Entry points

| Component | Started by | Notes |
|---|---|---|
| `LauncherApplication` | Every process start | Builds `Prefs` (reads SharedPreferences on main), `AppRepository` (first reload on workers), `LastPlayer.init` (second prefs read on main). Owns `scope` = `SupervisorJob + Dispatchers.Main.immediate`, no exception handler. |
| `MainActivity` | HOME / LAUNCHER intent | `singleTask`, `clearTaskOnLaunch`, `stateNotNeeded`, `excludeFromRecents`, portrait. Night mode, locale, font scale and density changes recreate it. `onStart`/`onStop` drive `NotificationStore.resume/pause` and `WidgetHost.onStart/onStop`; `onActivityResult` goes to `WidgetHost`; a HOME intent while on top goes through a CONFLATED `Channel` to `LauncherScreen`. |
| `SettingsActivity` | `SettingsActivity.open` (NEW_TASK, CLEAR_TASK) | Not exported, own task affinity. Page stack in `rememberSaveable`. |
| `InstallResultReceiver` | PackageInstaller session result | Not exported; explicit `FLAG_MUTABLE` PendingIntent (the installer fills in the result). |
| `NotificationListener` | System bind (also after process death) | Feeds `NotificationStore`; starts/stops `NowPlaying`; copies login codes. |
| `LockService` | Accessibility service (optional) | Requests no events, can't read windows; static `instance` for the double-tap lock. |
| `FakeMediaReceiver` | adb broadcast | Debug builds only, protected by `DUMP`. |

No WorkManager, JobScheduler, foreground service or alarms: timers are Handler delays and coroutine delays inside
`repeatOnLifecycle(STARTED)`. Runtime receivers: profile broadcasts (`AppRepository`, process lifetime) and time,
battery (`ClockHeader`, started only); all registered `NOT_EXPORTED`. Listeners registered in composables use
`LifecycleStartEffect` (BATTERY.md).

### Navigation

Home is one `LazyColumn` in `ui/home/LauncherScreen.kt`: the home page item (clock, chips, card slot, widget stack,
resume row, player, favorites), the header (search pill, gear) and the A–Z rows. "Home" and "list" are scroll positions
settled by `rememberHomeSnapFling`; the `AlphabetWave` strip jumps with `scrollToItem`. Overlays in the same window:
`SearchOverlay`, and `FolderPopup` / `HomeMenuPopup` / `ContextMenuPopup` through `PopupLayer`. Separate windows: the
rename and folder-name dialogs and `WidgetStackSheet`. Back order: pop-ups, then search, then `ListBackHandler`, then
MainActivity's no-op catch-all, so home never finishes. Settings keeps its own stack of `SettingsScreen` pages.

### State and threading

- Settings: one `MutableStateFlow<LauncherSettings>` in `Prefs`, read once at start; `update` is `synchronized` and
  rewrites the whole file with `apply()`.
- Process-wide objects: `NotificationStore` (main thread only, `live` gate), `NowPlaying`, `LastPlayer`, `Updater`
  (own `SupervisorJob + IO` scope), `Weather` (single-thread IO queue), `WidgetHost`, and caches (`MenuShortcuts`,
  `StackMemory`, section and search indexes, copied login codes).
- Main thread: service callbacks, `NotificationStore`, `NowPlaying`, `LastPlayer`, the LauncherApps callback,
  receivers, `WidgetHost` (except pruning) and Compose. `AppRepository` reloads on
  `Dispatchers.Default.limitedParallelism(3)`; providers, network and backups on `Dispatchers.IO`.

### Data layer

| Store | Format | Written by |
|---|---|---|
| `launcher` prefs | 47 keys: booleans, enum names, org.json strings (favorites, hidden, renames, folders, widget stack, weather place) | `Prefs.update` only (main, AppRepository workers, WidgetHost prune on IO, backup restore) |
| `weather` prefs | last reading as JSON | Weather's IO queue |
| `updater` prefs | `last_check`, `dismissed_tag` | IO and main |
| `last_player` prefs | last track JSON | main, on track change |
| `cacheDir/update.apk` | downloaded update | Updater, deleted in `finally` |

Enums are stored by name with a default for unknown names; no key has ever been renamed or retyped, so no migration
code exists or is needed yet. Backup/restore goes through the system file picker, capped at 1 MiB, merged onto current
settings in one atomic update. Network: `HttpURLConnection` only, to Open-Meteo (forecast, place search; 10 s timeouts,
10-minute backoff) and GitHub (latest release, APK download; 15/30 s timeouts). Plain HTTP is blocked by default at
target 36. The updater checks the APK's package name, version code and signer set before installing. Contacts and
calendar queries check the permission first, close cursors with `use {}` and run on IO. No secrets in tracked files or
history; the signing key lives only in the git-ignored `.signing/` and CI secrets.

### Dependencies (checked 2026-10-06)

| Dependency | Was | Latest stable | Decision |
|---|---|---|---|
| Gradle wrapper | 8.14.3 | 8.14.5 (9.8 with AGP 9) | **Upgraded to 8.14.5** (patch) |
| Android Gradle Plugin | 8.13.0 | 8.13.2 (9.4.1) | **Upgraded to 8.13.2** (patch; AGP 9 drops the Kotlin Android plugin and changes R8 defaults: a project of its own) |
| Kotlin + Compose compiler plugin | 2.2.20 | 2.2.21 (2.4.20) | **Upgraded to 2.2.21** (patch) |
| Compose BOM | 2025.10.01 (Compose 1.9.4, Material3 1.4.0) | 2026.09.00 (Compose 1.12.1) | Left: three Compose minors change gesture, focus and lazy-list behavior, and there's no phone to check them on |
| activity-compose | 1.11.0 | 1.13.0 | Left: 1.12 rebuilt the back dispatcher (NavigationEvent) and changed callback order, which MainActivity's catch-all depends on |
| core-ktx | 1.17.0 | 1.19.1 | Left: 1.18 needs compileSdk 36.1; 1.19 empties core-ktx into `androidx.core:core` |
| lifecycle-runtime-compose | 2.9.4 | 2.11.0 | Left: 2.11 needs compileSdk 37 and AGP 9.2 |
| kotlinx-coroutines | 1.10.2 | 1.11.0 | Left: no fix this app needs; best moved with Compose, with on-device testing |
| junit 4.13.2, Robolectric 4.17, Roborazzi 1.76.0, Material3 1.4.0, icons-core 1.7.8 | | | Current |

No conflicts or unexpected resolutions. The only native library (`libandroidx.graphics.path.so`, from Compose) is
16 KB-page aligned. Robolectric's SDK 36 tests need JDK 21 (local JDK is 21; CI uses 17 and doesn't run tests).

## Phase 2: build health

Baseline (before any change): `clean assembleDebug assembleRelease` passed, 424 unit tests passed, lint failed with
4 errors, 50 warnings and 2 hints (both variants), plus one Kotlin warning.

Fixed (commit "Build health: clear lint's errors and code warnings"):

| Issue | Where | Fix |
|---|---|---|
| NewApi (error ×3): `WallpaperColors` getters outside the API 27 check | `settings/HomePreview.kt` | Read the colors inside the check |
| LocalContextConfigurationRead (error): the preview clock read the locale from `LocalContext`, so it didn't follow a configuration change | `settings/HomePreview.kt` | `LocalConfiguration`, and the locale is a `remember` key |
| Kotlin: condition always true | `ui/home/LauncherScreen.kt` | Dropped the redundant check |
| LocalContextResourcesRead | `ui/home/SearchExtras.kt` | `densityDpi` from `LocalConfiguration` |
| ModifierParameter | `settings/SettingsKit.kt` `SettingRow` | `modifier` is now the first optional parameter (every caller names its arguments) |
| ObsoleteSdkInt | `res/mipmap-anydpi-v26` | Renamed to `mipmap-anydpi` |
| UseKtx ×25 (fix agent) | 13 files | core-ktx `edit {}`, `toUri`, `createBitmap`, `scale`, `toDrawable`; same behavior (all `apply()`, filter true, ARGB_8888) |
| ComposableNaming | `FolderScreenshots.kt` (test) | Renamed |

Suppressed with the reason written beside it (lint false positives):

| Check | Where | Why it's safe |
|---|---|---|
| InlinedApi | `util/Clipboard.kt` | `EXTRA_IS_SENSITIVE` is just an extras key; older Androids ignore it |
| InlinedApi | `data/SystemSettings.kt` | Newer Settings actions sit first in a fallback list; an unresolvable one falls through |
| StaticFieldLeak | `data/WidgetHost.kt` | The host holds the application context; the activity and its views are dropped in its onDestroy |
| ClickableViewAccessibility | `data/WidgetHost.kt` | `onTouchEvent` performs no click; it only swallows the rest of a long-pressed gesture |
| ConfigurationScreenWidthHeight | `SettingsKit.kt`, `WidgetStack.kt` | The activity's own Configuration already reports its window size |
| ModifierParameter | `SettingsKit.kt` `SettingsGroup` | Every caller passes the title positionally |

Left as is: the two `ModifierNodeInspectableProperties` hints (only Layout Inspector's display of two modifiers);
test-only Robolectric deprecations (`addUserProfile`, `hasReceiverForIntent`).

R8/ProGuard: no keep rules needed. The only reflection targets a framework class (`StatusBarManager`, never renamed),
JSON goes through `org.json` with hand-written field names, and enums are matched by `name`, which R8 keeps.

Toolchain (commit "Build health: Gradle 8.14.5, AGP 8.13.2, Kotlin 2.2.21"): the three patch upgrades above, and the
build now warns when `git describe` finds no release tag instead of silently building 0.0.1, which `adb install -r`
can't put over a release.

After: debug and release build, lint 0 errors and 0 code warnings (only the dependency-version warnings left on purpose
above), 424 tests pass.
