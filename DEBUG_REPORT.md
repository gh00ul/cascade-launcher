# Debug and hardening report

Branch `hardening/debug-pass`, started 2026-10-06 from `main` at v0.18.0 (08731b0).

## Summary

- [x] **Phase 1, map:** one module, Compose, no DI/Room/network library. No secrets, no logging. Data map and
  dependency inventory below.
- [x] **Phase 2, build health:** lint went from 4 errors and 40 code warnings to 0 and 0. Gradle 8.14.5, AGP 8.13.2,
  Kotlin 2.2.21. Bigger upgrades are deferred, with reasons. R8 needs no new keep rules.
- [x] **Phase 3, crash hunt:** 6 audits, 16 fixes. The serious ones were an **ANR deadlock** (Prefs vs app list), the
  **listen-mode fallback opening an app over another app**, and a **widget lost across a process death**. No crash on
  a normal path was found.
- [ ] **Phase 4, on-device:** **skipped.** The S25 Ultra was never connected (only the Tab S7 briefly). Debug builds now
  log StrictMode. There is a 14-item phone checklist below.
- [x] **Phase 5, data and network:** 17 fixes. Top items: the **updater said "Up to date" on any HTTP error** (rate
  limits blocked updates for 6 h), **restoring an old backup deleted your folders**, an install could get **stuck on
  "Installing"**, and silent failures now say what went wrong.
- [x] **Phase 6, tests:** 424 → 521 unit tests, all passing. Regression tests were proven against the old code. Writing
  them caught 2 of our own mistakes, both fixed.
- [x] **Phase 7, UI polish:** large-font clipping fixed on home, widgets, menus, strip and Settings; no Settings flash.
  98 new 2x shots, and all 142 old shots byte-identical.
- [x] **Phase 8, review:** the independent reviewer found nothing critical or high. Its one medium finding (an
  unexpected HTTP-stack exception could crash home on every resume) and four low ones are fixed. Rules check: no
  logging added, every suppression has its reason, broad catches are justified, no functionality removed.
- **Needs you:** the Auto Backup scope decision, and a run of the phone checklist.

Every bug is listed below with severity, root cause, fix, files, the agent that found it, and how it was verified.


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

## Phase 3: crash hunt

Six read-only audit agents (Null-Safety, Lifecycle, Coroutines & Threading, Compose, Permissions & Intents, Resources)
each covered the whole codebase for one category. None found a crash on a normal path: `!!`, casts, platform nulls,
`coerceIn` bounds, lazy keys, saveable types, every external launch and every PendingIntent were already guarded. Five
fix agents with disjoint files fixed what they did find; the lead wired the cross-file parts.

| # | Severity | Bug | Root cause | Fix | Files | Found by | Verified |
|---|---|---|---|---|---|---|---|
| 1 | Medium (ANR) | Home could freeze when an icon picker swapped an app's alias while a setting changed, or during Restore | Lock-order inversion: main held Prefs' lock and (via Main.immediate collectors) took the app list's lock; a reload held the list lock across PackageManager calls and `prefs.update`. Restore parsed up to 1 MB of JSON on main inside Prefs' lock | Moves computed and stored outside the list lock, under a reload-only lock; Restore parses off main once, merges inside the update | `AppRepository.kt`, `Prefs.kt`, `SettingsBackup.kt`, `MorePages.kt` | Data Layer, Coroutines | New test: moves stored without the list lock held; lock graph reviewed by lead |
| 2 | Low (latent) | A nested settings update could be lost on restart | `Prefs.update` published before writing | Write, then publish | `Prefs.kt` | Data Layer | New test: nested update stored last |
| 3 | Medium | Listen mode's Resume could open the music app over another app or behind the lock screen | The 4 s fallback waited for a recomposition to see `resuming = false`; home doesn't recompose while stopped | The wait watches the flag (snapshotFlow) and the launch requires STARTED | `LauncherScreen.kt` | Lifecycle, Compose (independently) | `ListenModeTest` rewritten to pause recomposition like a device; lead confirmed it fails on the old code and passes on the fix |
| 4 | Medium | A widget whose setup screen was open across a process death was freed and lost | `pending`/`configuring` live in memory; the create-time prune freed the id before the setup's answer arrived | Setup id kept on disk until answered (max a day); prune reads in-flight ids before the stack; `configuring` volatile; allocate + mark pending atomically | `WidgetHost.kt`, `WidgetStack.kt` | Architecture, Coroutines | New test (one-day window); needs device |
| 5 | Low | Two widget prunes on every cold start; binder lookups inside the Prefs update; binder call while composing a widget page | `pruned` flag let the first start re-prune; `isAlive` looked up inside the transform | One prune per cold start; lookups before the update; page uses the prune's cached info | `WidgetHost.kt`, `WidgetStack.kt` | Architecture, Coroutines, Lifecycle | Review; BATTERY.md corrected |
| 6 | Low | Rename / new-folder dialogs and the widget sheet closed (and lost text) on a dark-mode or font change; the widget "Allow" answer was lost | Plain `remember` across activity recreation | `rememberSaveable` with app keys and `TextFieldValue.Saver` | `LauncherScreen.kt`, `Dialogs.kt` | Lifecycle | Needs device |
| 7 | Low | New folder / Rename folder, Settings search and the weather place dialog could open without the keyboard | Focus requested before the field's window (dialog) or slot existed; `runCatching` around a call that doesn't throw | Request focus from inside the slot, after the field | `Dialogs.kt`, `LookPages.kt`, `MainPage.kt` | Null-Safety, Coroutines | Needs device |
| 8 | Low | Notification dots, previews, player and chips could stay off after an update or force stop | The system rebinds a dead listener once; `requestRebind` alone is a no-op | 5 s after each home start, if access is granted but not connected: `requestUnbind` + `requestRebind` (Android 14+) | `NotificationListener.kt`, `MainActivity.kt` | Permissions, Lifecycle | Needs device |
| 9 | Low | A media browser that never answers stayed bound all day | Disconnect posted only from `onConnected` | 10 s timeout after `connect()` | `LastPlayer.kt` | Lifecycle, Coroutines | New tests (never answers; fails once) |
| 10 | Low | Letter strip (App names) jumped instead of gliding while held at an end | `scroll()` changed only plain fields; nothing invalidated the draw | Snapshot counter read in the draw | `AlphabetWave.kt` | Compose | Screenshots unchanged; needs device |
| 11 | Low | Battery chip made a binder call on main for every voltage/temperature broadcast while charging | `computeChargeTimeRemaining` on every broadcast | Asked on level/charging change or while unknown | `ClockHeader.kt` | Coroutines | Lifecycle tests pass |
| 12 | Low | Weather widget showed hours already past after time away with failed fetches | "Now" read only when a reading arrived | Re-read on each start | `WeatherWidget.kt` | Compose (also a deferred bug) | Needs device |
| 13 | Low | Update download progress recomposed all of home | Collected in the screen body | Collected inside the card slot | `LauncherScreen.kt` | Compose | Screenshots unchanged |
| 14 | Low | Deprecated background-start mode on Android 16, under an unexplained `@Suppress` | API 36 split ALLOWED into ALLOW_IF_VISIBLE / ALLOW_ALWAYS | ALLOW_IF_VISIBLE on 36+, narrowed suppression on 34–35 | `PendingIntents.kt` | Architecture, Dependency, Permissions | Needs device |
| 15 | Low | Predictive back animations didn't play on Android 13–15 | No `enableOnBackInvokedCallback` | Attribute added | `AndroidManifest.xml` | Dependency | Needs Android 15 device |
| 16 | Low (hardening) | Resume row allocated a brush and stroke per frame; letter alpha could exceed 1; unchecked `layoutId` cast; NaN launch bounds; empty Settings stack restore | — | `drawWithCache`, clamp, `as?`, finite check, `ifEmpty { null }` | `ResumeRow.kt`, `AlphabetWave.kt`, `FavoriteReorder.kt`, `LauncherActions.kt`, `SettingsActivity.kt` | Compose, Null-Safety | Build, tests |

Moved to later phases: updater network bugs (Phase 5), error messages and permission flows (Phase 5), large-font
clipping, the Settings background flash and touch targets (Phase 7).

Left on purpose:
- NotificationStore's gate starting open: closing it would blank Settings' preview chips in a fresh process and make a
  screenshot test order-dependent, for close to no battery gain.
- MediaRow's animated colors read in composition: Material's `Slider` takes colors as values; it recomposes only for the
  600 ms of a track change.
- Work-profile timer/live chips falling back to the personal app (no work profile on the target phone).

Verified: debug and release build, lint clean (only the dependency-version warnings left on purpose), 431 unit tests
pass (7 new), and all 142 Robolectric screenshots are byte-identical to v0.18.0's.

## Phase 4: runtime verification — skipped (no device)

The Device Agent ran `adb devices -l` twice. The first time, only the owner's Galaxy Tab S7 (`R52T105KRGA`, Android 13,
Cascade 0.9.0, not the default home) was attached. Per the owner's notes that tablet isn't the target and is left
alone. The second time nothing was attached. The S25 Ultra was never connected, and no emulator was running (the one
AVD stopped booting earlier today and wasn't started, per the rules). So nothing was installed, no logcat was captured,
and no rotation, background/foreground, process-death or permission-denial tests ran.

What stands in for it:
- Debug builds now log StrictMode violations (thread and VM policies, `penaltyLog`), ready for the next device run.
- Robolectric unit tests, including lifecycle-driven ones (`ClockHeaderLifecycleTest`, `ListenModeTest` with a paused
  recomposer, `MotionSettleTest`).
- The JVM screenshot harness: 142 shots before and after every phase, compared byte for byte.

The on-device checklist is under "Not verified on a device" in the summary.

## Phase 5: data and network

Three read-only audits (Persistence, Network, Error UX: 73 error paths walked), then four fix agents with disjoint
files: Network (updater, weather), Persistence (settings, restore, widget add), Settings/permission copy, and home/actions
copy. Cross-agent calls were fixed as exact signatures up front (`HttpStatusException`, `Updater.showPendingConfirm`,
`NotificationAccessHelp`).

| # | Severity | Bug | Root cause | Fix | Files | Found by | Verified |
|---|---|---|---|---|---|---|---|
| 1 | High | "Up to date" when GitHub rate-limits (403/429) or fails (5xx), and no check for 6 h | Android's HttpURLConnection throws FileNotFoundException for every status ≥ 400; the code took it for 404 and wrote the last-check time | Status read first; per-status messages and retry waits (rate limit 30–61 min from GitHub's headers); last-check written only after a parsed answer | `Updater.kt` | Data Layer, Network, Error UX | `UpdaterTest`: every status row, header parsing, intervals, a fake connection |
| 2 | Medium | Update stuck on "Installing" | Android can silently block the confirm screen started from the install broadcast while home is away | Confirmation held and reopened on home's next resume; only when Cascade wasn't in front | `Updater.kt`, `LauncherScreen.kt` | Network | Needs device |
| 3 | Medium-low | A cancelled or blocked install after a process restart showed nothing on home | The fresh process lost the release | The release rides on the install's result intent | `Updater.kt` | Architecture, Network | `UpdaterTest` (`releaseOf`); needs device |
| 4 | Low | Raw exception text on the update card ("Unable to resolve host…", "ENOSPC…"), a cut-off download called "not a valid APK", copy naming a "Update" button that doesn't exist, uninstall advice that would wipe the setup | Messages taken from exceptions; no size/space checks | Status-aware messages, free-space (allocatable) and completeness checks, size caps, "Try again" wording, Backup & restore before any uninstall | `Updater.kt` | Error UX, Network | `UpdaterTest` |
| 5 | Low | Connections left open; partial `update.apk` and uncommitted sessions left by a crash | No `disconnect()`; no cleanup | `finally { disconnect() }`; cleanup at the next check / install | `Updater.kt` | Data Layer, Network | Review |
| 6 | Medium | Restoring a backup from before folders (v0.6–v0.8) deleted the current folders | Favorites restored without folder keys while folders were kept; the next edit tidied the orphans away | Favorites and folders restore as one unit; summary names folders | `SettingsBackup.kt` | Data Layer, Persistence | `SettingsBackupTest` |
| 7 | Low | A restored field with only wrong-typed entries wiped the current value | Filtering left an empty list | Skip a field with entries but none valid; gesture apps of the wrong type keep the current app | `SettingsBackup.kt` | Data Layer, Persistence | `SettingsBackupTest` |
| 8 | Low-medium (latent) | One wrong-typed stored value would crash home on every launch | Typed getters throw ClassCastException in `Application.onCreate` | Type-checked reads via `sp.all` (Prefs, last player, widget setup, updater, weather) | `Prefs.kt`, `LastPlayer.kt`, `WidgetHost.kt`, `Updater.kt`, `Weather.kt` | Data Layer, Persistence | `PrefsTest` |
| 9 | Low | Weather widget said "shows here once it has loaded" forever when offline; place search said "check your connection" for server errors; a superseded search ran on for 20 s | Failure state was private; no status type; blocking read ignored cancellation | `Weather.failedFor`, `HttpStatusException`, disconnect on cancel | `Weather.kt`, `Http.kt`, `WeatherWidget.kt`, `LookPages.kt` | Network, Error UX | `WeatherTest` |
| 10 | Medium | Notification access greyed out as a "restricted setting" (sideloaded install) with no explanation | Only the lock dialog explained it | Back from Allow with access still off (13+): a dialog explaining App info > ⋮ > Allow restricted settings, on home and in Settings | `AccessHelp.kt`, `MainPage.kt`, `MorePages.kt`, `LauncherScreen.kt` | Permissions, Error UX | Needs device |
| 11 | Low | Closing the first permission dialog counted as "blocked" | No rationale before or after looks like a block | Blocked only after an earlier refusal (remembered per permission) or a second silent refusal | `PermissionPrompt.kt`, `LookPages.kt`, `MorePages.kt`, `CalendarWidget.kt` | Permissions, Error UX | Needs device |
| 12 | Low | Silent failures: App info, Uninstall, calendar, Accessibility, notification access, home-app setting; the role request could crash home if unhandled | `runCatching` / unchecked `start` results | A short message for each; Default apps fallback; guarded role request with a hint | `LauncherActions.kt`, `LauncherScreen.kt`, `MainPage.kt` | Permissions, Error UX | Test agent 3 |
| 13 | Low | Settings summaries promised the calendar without access and double-tap lock with the service off; a failed check read "update didn't finish" | Summaries read only the setting | Read access / service state / failure kind | `MainPage.kt` | Error UX | Test agent 3 |
| 14 | Low | A calendar read error looked like an empty calendar | `getOrNull().orEmpty()` | Null on failure; the widget says it couldn't read | `Calendar.kt`, `CalendarWidget.kt` | Error UX | Test agent 3 |
| 15 | Low | Adding a widget did nothing when no id could be had or the bind dialog wouldn't open | `begin()` returned null for both bound and failed | `Begin` result type; "Couldn't add the widget" | `WidgetHost.kt`, `WidgetPicker.kt` | Error UX | Review |
| 16 | Low | Backup save could fail on providers rejecting "wt"; an oversized restore file said "couldn't read" | — | "wt" then "w"; "isn't a Cascade settings backup" | `MorePages.kt` | Persistence, Error UX | Needs device (Drive) |
| 17 | Low | Search with nothing matching (web search off) showed a blank list | No empty state | "No apps match “…”." after a 300 ms spec delay | `SearchOverlay.kt` | Error UX | Test agent 3 |

Lead review changes: removed the network agent's confirm-intent action check (it adds nothing, since the receiver isn't
exported and only Android holds the PendingIntent, and it would silently break self-update on an OEM that used another
action name); made the permission helper treat a second silent refusal as a block (so a permission permanently denied
before this build still leads to App info).

Left on purpose, with reasons:
- Setting aside unparseable JSON values (Persistence option 2): adds keys and complexity to every settings write, for a
  corruption only this code could produce; the Backup page is the safety net.
- Logging persistence failures: the app deliberately has no logging; added nothing.
- "Android may hide some login codes" note on the setting: true on Pixel with Android 15+, unverified on One UI;
  no copy added until it's confirmed on the phone.
- Auto Backup scope: the owner's privacy decision (see "Decisions for you").

## Phase 6: tests

Unit tests went from 424 to 521, all passing (`./gradlew testDebugUnitTest`, run by the lead only). They came from three
test-writing agents (by module) and from the fix agents' own focused tests:

| Area | Tests added | Highlights |
|---|---|---|
| Home UI (test agent 1) | 14 | Dialogs and the widget sheet through activity recreation; the folder dialog's focus; a keyless favorites child; weather hours on return; the battery estimate's binder calls; the Settings stack restore |
| Widgets, notifications, intents (test agent 2) | 13 | Widget setups across a process death (record, answer, cancel, one prune per cold start, allocation race) with a custom widget-host shadow; the listener rebind; background-start mode per Android version; NaN launch bounds |
| Phase 5 fixes (test agent 3) | ~35 | The permission prompt (truth table and real dialog flows); every action that can't open says so; Settings summaries; calendar read errors; "No apps match"; wrong-typed stored values; "Couldn't add the widget" |
| Fix agents | ~35 | Updater status mapping, rate-limit waits, last-check writes and a fake connection; weather failure state and cancellation; restore folder/type rules; every setting differs from its default in the round-trip fixtures (reflection check); the lock-order and nested-update regressions |

Proving the regression tests catch the bugs: `ListenModeTest.leavingHomeCancelsTheFallback` was run against the old
LauncherScreen code and failed, and all six `HomeDialogsTest` dialog tests were run against the old `Dialogs.kt` and
failed with exactly the bugs (typed text lost, no focus). Writing the tests also found two bugs the fixes had missed:
- `SettingsStackRestoreTest` showed that Phase 3's Settings stack fix crashed with an NPE instead of starting over.
- `PermissionPromptTest`'s author showed that a quick double tap on Allow loses the user's answer.

Both are fixed.

Instrumented tests: the project has no `androidTest` source set, no instrumentation runner and no Test Orchestrator /
`clearPackageData` setting. `connectedAndroidTest` has nothing to run, and no device was connected anyway.

## Phase 7: UI polish

Three UI agents with disjoint screens: home cards and widgets; the list, search, strip and menus; Settings and themes.
Each fixed issues the Resources audit listed and checked its own screens at One UI's largest font size. Nothing was
redesigned. The proof is the screenshot harness: 98 new shots at 1.5x and 2x, rendered with Android's own nonlinear font
scaling, and all 142 existing shots still byte-identical to v0.18.0 at the default size. One change that moved a
checkmark by a few pixels was caught that way and reworked.

| # | Severity | Issue | Fix | Files | Verified |
|---|---|---|---|---|---|
| 1 | Medium | Settings flashed the window colour (a grey lift in dark mode) between pages and on open; it opened on an icon splash | Page-colour background under the cross-fade; window background = the page colour (exact dynamic colour on 34+); solid-colour splash on 33+ | `SettingsActivity.kt`, `themes.xml` (+ `-v34`) | Reasoned from timings; needs device |
| 2 | Medium | Widget cards at large font: the "Allow"/"Pick a place" button fell off the card; weather hours and rain clipped; "10 PM" showed as "10" | Text gives way to the button; the weather card drops the rain line, then the hours, when they don't fit; hour labels don't wrap | `WidgetStack.kt`, `WeatherWidget.kt` | `Widget_*_Font1_5x/2x` |
| 3 | Medium | Home long-press menu names cut mid-word ("Wallpap") | One shared size for the four names with a gap | `HomeMenu.kt` | `Home_Menu_Font1_5x/2x` |
| 4 | Medium | Segmented buttons ("Automatic") and tile labels clipped | Shrink to fit; line height scales with the label above 1x; checked label kept clear of the outline | `SettingsKit.kt` | `Settings_ClockFormat_Font2x`, `Settings_AppearanceTiles_Font2x` |
| 5 | Low-medium | Settings search text clipped in the 64 dp bar at 2x | Bar height follows the field | `MainPage.kt` | `Settings_Find_Font2x` |
| 6 | Low | Clock-style samples broke "13:45" over two lines; the home preview clipped favorites | Samples drawn at font scale 1; preview height has a minimum, not a fixed value | `LookPages.kt`, `HomePreview.kt` | `Settings_Clock_Font2x` |
| 7 | Low | Long "until" times crowded a chip's title out | The time moves to a second line when it would | `ClockHeader.kt` | `Home_EventUntil_Font2x` |
| 8 | Low | Letter strip at large font: names overlapped and long ones ran off screen | Option swell bounded by its slot; names capped and ellipsized | `AlphabetWave.kt` | `AlphabetWave_*_Font2x` |
| 9 | Low | App menu button labels clipped or ran together; section names broke mid-word | Shrink to fit with a gap; one line | `AppMenu.kt` | `Menu_*_Font2x` |
| 10 | Low | List pages and Find could hide their last rows under the keyboard (edge-to-edge) | IME insets in the Scaffold | `SettingsKit.kt`, `MainPage.kt` | Needs device |
| 11 | Low | The lock-service dialog and the home message called the service by the wrong name | Read from the service's label string | `AccessHelp.kt`, `LauncherScreen.kt` | Review |

Checked and left alone:
- **Touch targets:** Compose 1.9 already widens any tap target under 48 dp to 48 dp, unless a neighbour is hit
  directly. The remaining small ones (list rows with icons off, expanded notification items, agenda rows) sit edge to
  edge and can't grow without a new layout.
- **Search and dialogs above the keyboard:** search already pads for it (`imePadding`), and dialogs are left to the
  system.
- **The widget sheet's bar icons:** follow the sheet's own colours in Material3 1.4.
- **Dark-text contrast:** fine in every shot reviewed.
- **Possible pre-existing tap issue (needs device):** the favorite's preview line and its pills get that automatic
  widening too, and it reaches ~12 dp up into the app's name. So a tap on the lower half of a favorite's name may open
  its latest notification instead of the app. The fix (consume pointer input on the name row) changes tap behaviour,
  so it waits for the owner.

## Phase 8: review

An independent Reviewer Agent (read-only) audited `git diff 08731b0..HEAD`: all main code end to end, tests more
lightly. Nothing critical or high.

| # | Severity | Finding | Resolution |
|---|---|---|---|
| 1 | Medium | The rewritten update check caught only network exceptions; an unchecked one from the platform's HTTP stack would crash home from a handler-less scope, and again on every resume (nothing was written to back off) | A last `catch (RuntimeException)` maps it to "Couldn't check for updates (…)" with the 30-minute retry, justified in a comment; `busy` is released in `finally` |
| 2 | Low (latent) | `ChipText` would throw on an intrinsic (unbounded-width) measure | Unbounded width places both parts side by side |
| 3 | Low | A held install confirmation could reappear once when Android hadn't blocked it, and opened in home's own task (a Home press there cancels the install) | Always started in its own task, as the receiver does; the trade-off is documented |
| 5 | Nit | A failed free-space query read as a network failure, which would block updates for good if it always failed | Treated as unknown; the download just tries |
| 6 | Nit | `catch (RuntimeException)` around starting the confirmation, without a reason | Narrowed to `ActivityNotFoundException` / `SecurityException` |
| 7 | Nit | `tools:targetApi` on `<application>` unexplained | Commented: it covers only `enableOnBackInvokedCallback` |
| 8 | Nit | The access-help flag stayed set when notification-access settings didn't open | Set only when the page opened (home and Settings) |
| 4 | Low | The listener rebind can cycle once a listener whose connection is still queued at boot | Accepted: it ends connected; needs a device to see |
| 9 | Nit | `widget_setup`'s tiny file can be first read on main | Accepted: once per process, a few bytes |

Rules check from the review:
- **Logging:** no `Log`/`println` added, and the only StrictMode logging is debug-only.
- **Error text:** no URLs, tokens or message contents in user-visible errors; the updater never shows the signed
  download link.
- **Suppressions:** every one carries its reason.
- **Broad catches:** each justified.
- **Removed functionality:** none.
- **Destructive changes:** none beyond Cascade's own leftover `update.apk` and uncommitted install sessions.

## Not verified on a device (S25 Ultra checklist)

No phone was connected during the pass. On the phone, after installing (commands below), check:

1. **No freeze:** switch an icon pack / icon style in Telegram or Signal (an activity-alias swap) while toggling
   monochrome icons; restore a large backup in Settings > Backup & restore.
2. **Listen mode:** with headphones, tap Resume on an app that ignores it, then lock the phone or open another app
   within 4 s. The music app must not open.
3. **Widget setup:** add a widget that has a setup screen, `adb shell am kill com.gh00ul.cascade` while it's up, finish
   it. The widget is placed. Cancel instead: no leftover id in `adb shell dumpsys appwidget`.
4. **Notification listener:** after an update (`adb install -r`), dots, previews and the player come back within about
   5 s of home showing.
5. **Updater:** a rate-limited or offline check says so in About (not "Up to date"). A real update installs, and the
   confirm screen reappears on return home if the screen was off when it was due.
6. **Restricted setting:** on a fresh sideloaded install, notification access is greyed out, and the help dialog
   appears after Allow.
7. **Permission dialogs:** Back on the first calendar/contacts dialog keeps asking; a second refusal goes to App info.
8. **Dark mode / font size change:** with the rename or new-folder dialog open (text typed) or the widget sheet open,
   everything stays.
9. **Large font (One UI max):** home menu, widget cards, chips, the app menu, Settings segmented buttons and the Find
   bar look like the `_Font2x` shots. The keyboard doesn't cover Add favorite's last rows.
10. **Settings:** no grey flash between pages in dark mode; a plain splash on open.
11. **Predictive back** on Android 15 (if the phone is still on it): the list and search follow the gesture.
12. **Login codes on Android 15/16:** whether One UI hides one-time codes from Cascade ("Sensitive notification
    content hidden").
13. **Logcat:** debug builds log StrictMode violations:
    `adb logcat --pid=$(adb shell pidof com.gh00ul.cascade) StrictMode:D *:S`.
14. **Tap targets:** a tap on the lower half of a favorite's name opens the app, not its notification.

## Remaining risks and next steps (by impact)

1. **Nothing ran on hardware.** The checklist above, especially the self-update path (5): the updater is how every later
   fix arrives.
2. **CI runs no tests or lint.** The workflow only builds release APKs on JDK 17, and the Robolectric tests need JDK 21.
   Add a separate JDK 21 job (`testDebugUnitTest lintDebug`) that doesn't gate releases.
3. **Library upgrades left behind** (Compose BOM 2025.10 → 2026.09, activity 1.11 → 1.13, core 1.17 → 1.19,
   lifecycle 2.9 → 2.11, coroutines 1.10 → 1.11; AGP 9 later). Do them in one pass with a phone in hand: activity's
   back-dispatcher rewrite affects MainActivity's catch-all.
4. **Auto Backup scope** is unscoped (decision below).
5. **Deferred from earlier hunts, still open:** notifications capped at 8 in the expanded row; album art thumbnailed
   on the main thread; favorites overlapping if one's height changes mid-drag; "open single match" firing mid-command;
   home menu clipping under ~350 dp; RTL/zh-TW nits; work-profile chips and player falling back to the personal app.
6. **No logging at all:** data loss or an updater failure in the field leaves nothing to diagnose. Consider a few
   privacy-safe `Log.w` lines (key names, exception classes only).

## Decisions for you

- **Auto Backup** (`allowBackup="true"` with no rules sends all prefs, including the weather place and last track, to
  Google backup and device transfer). Options: keep as is; back up only `launcher.xml`, encrypted only (rules XML
  drafted by the Persistence agent); or turn it off and rely on the Backup page.
- **The possible name-tap issue** above (needs a look on the phone first).

## Build and install

```bash
./gradlew clean assembleRelease testDebugUnitTest lintRelease
adb devices
adb -s <serial> install -r app/build/outputs/apk/release/app-release.apk
```

Use the release APK: the local `.signing/release.jks` is the same key as GitHub's releases, so `install -r` updates
the installed app in place. The debug APK is debug-signed and would fail with a signature mismatch (don't uninstall to
get around it: that wipes the launcher's setup). After an `adb install`, adb is the installer of record, so the next
self-update asks for confirmation once. JVM screenshots:
`./gradlew.bat testDebugUnitTest -PcascadeScreenshots` (240 PNGs in `app/build/screenshots`).
