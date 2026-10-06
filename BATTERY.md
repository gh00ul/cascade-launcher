# Battery

Cascade is the home screen, so its process lives all day: behind every other app and under a screen that's off. Idle cost has to be zero. While home is visible and nothing changes, the only work is what's on screen ticking. While home is stopped (another app in front, or the screen off), nothing runs except callbacks the system delivers anyway, and those do the least they can and catch up on start. Compose pauses recomposition and frames at ON_STOP, but coroutines, receivers, listeners and Handler callbacks keep going unless they are scoped to the lifecycle, so those are what this file tracks.

## What runs when

| What | Trigger | Home visible and idle | Stopped or screen off |
|---|---|---|---|
| Clock receiver (`ClockHeader`) | TIME_TICK, TIME_CHANGED, TIMEZONE_CHANGED, NEXT_ALARM_CLOCK_CHANGED | Registered ON_START to ON_STOP with `LifecycleStartEffect`. Each start re-reads `now`. `AlarmManager.nextAlarmClock` is read only on start and on the broadcasts other than TIME_TICK (the `alarmChecks` counter), never once a minute. | Unregistered |
| Battery chip | ACTION_BATTERY_CHANGED | Registered with `LifecycleStartEffect` only while started and the setting is on. `Battery` is a data class, so a broadcast with the same reading doesn't recompose. | Unregistered. Registering returns the sticky intent, so each start catches up. |
| Timer chips | A chronometer notification | 1 s ticker in `repeatOnLifecycle(STARTED)`, only while such a notification exists | Nothing |
| Calendar chip | Each return home, then each new 5-minute bucket of `now` | Provider query on `Dispatchers.IO`, only with the setting on and only while started. `now` only advances while the clock receiver is registered, so the 5-minute re-query stops when home does. Each resume bumps a counter key, so leaving home (pause, then stop) changes no key and doesn't query. | Never |
| Wallpaper colors listener (`ui/theme/Wallpaper.kt`) | Wallpaper change | `OnColorsChangedListener` registered with `LifecycleStartEffect` only while started. Each start re-reads `getWallpaperColors` once, synchronously, so a wallpaper set while away is right on the first frame. | Unregistered |
| Music progress (`MediaRow`) | A playing session | Ticker at 0.5–4 Hz, only while started and playing | Nothing |
| `NowPlaying` session callbacks | Media session changes | Publishes to the player row. Album art is thumbnailed once per track change. | Stay registered while notification access is connected (needed to date pauses). With no subscriber to `NowPlaying.state`, position-only playback updates skip `publish()` entirely, and state-code and metadata changes run only the cheap O(sessions) pause and hide bookkeeping. The 30-minute retire recheck is no longer forced and is dropped while unobserved. `NowPlaying.refresh()` on ON_RESUME catches up and reschedules it. |
| `NotificationListener` and `NotificationStore` | Notification posts, removals, ranking changes | Unlisted ongoing updates (navigation, downloads, media) and unknown removals return in O(1) without regrouping. Unchanged rankings and identical reposts don't emit, since StateFlow drops equal values. Timers emit immediately (rare). | The system keeps the listener bound, so callbacks still arrive. `MainActivity.onStop` calls `NotificationStore.pause()`: callbacks only update the entry map, O(1) each, and keep the latest RankingMap. `onStart` calls `resume()`, which regroups once before ON_START reaches the collectors. The gate is open until the first `onStop`, so a listener that connects before home starts still fills `byApp`. Timers keep emitting immediately. |
| App list (`AppRepository`) | LauncherApps callback, profile broadcasts; icon settings, locale and density changes | Registered for the process lifetime; fire only on package or profile changes. A burst of package or profile events coalesces into one reload after about 250 ms. Only changed packages' icons are re-rendered. | Same |
| `Updater` | Home resume, with auto-check on | At most once per 6 h after a completed check (a 404 counts) and once per 30 min after any attempt. Downloads only on a tap. | Nothing |
| `InstallResultReceiver` (manifest) | PackageInstaller result | Fires only during an install | Same |
| `FakeMediaReceiver` (debug builds only) | adb broadcast | Only when triggered from adb | Same |
| Compose | State changes | No infinite transitions or animation loops. The only indeterminate indicators are the buffering ring (stops after 10 s) and the download bar while an update the user started has no size yet. | Recomposition and frames pause at ON_STOP. Coroutines, receivers and Handler callbacks don't. |

## Rules for new code

- In composables, register receivers and listeners with `LifecycleStartEffect`, never `DisposableEffect`.
- Put loops and tickers in `repeatOnLifecycle(STARTED)`. No polling while stopped.
- Gate derived-state work while nothing observes it, and catch up on start or resume. Any new notification-derived computation sits behind `NotificationStore`'s live gate or runs only on collection. Any new `NowPlaying` callback checks `subscriptionCount`.
- Don't emit for no-op updates.
- Coalesce bursty system events.
- Use the network only on explicit triggers, with backoff.
- Every tick redraws the list's offscreen fade layer, so keep tickers rare.

## Tests

- `ClockHeaderLifecycleTest`: the clock and battery receivers unregister on stop, the time catches up on start, the timer chips don't tick below STARTED, and the calendar isn't queried while stopped or on the way out.
- `NotificationStoreTest`: a paused store regroups once on resume, timers still emit while paused, and no-op callbacks don't emit.
- `UpdaterTest`: the 6-hour and 30-minute check intervals.
