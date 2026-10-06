package com.gh00ul.cascade

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.gh00ul.cascade.data.WidgetHost
import com.gh00ul.cascade.notifications.NotificationStore
import com.gh00ul.cascade.ui.home.LauncherScreen
import com.gh00ul.cascade.ui.theme.LauncherTheme
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow

class MainActivity : ComponentActivity() {
    // Buffered so a press that arrives before LauncherScreen starts collecting isn't lost: after a config change
    // the relaunched activity gets the Home intent in onNewIntent before its first composition.
    private val homePresses = Channel<Unit>(Channel.CONFLATED)
    private val homePressFlow = homePresses.receiveAsFlow()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        // Back never leaves the home screen, not even before the first frame. Added first, so the
        // screen's own back handlers (close search, scroll to top) still run ahead of this one.
        onBackPressedDispatcher.addCallback(this) { }
        // Widgets whose app went away while Cascade wasn't running leave the stack; off the main thread.
        WidgetHost.pruneOnce(this)
        setContent { LauncherTheme { LauncherScreen(homePressFlow) } }
    }

    // Nothing shows notifications while home is stopped: regroup once on return. ON_START reaches the collectors after
    // onStart returns, so they resubscribe to the fresh list. App widgets update only while home is visible too.
    override fun onStart() {
        super.onStart()
        NotificationStore.resume()
        WidgetHost.onStart(this)
    }

    override fun onStop() {
        NotificationStore.pause()
        WidgetHost.onStop()
        super.onStop()
    }

    // An app widget's setup screen answers here: the system starts it on Cascade's behalf (it needn't be exported), so
    // its result can't go through the Activity Result APIs. Delivered before onResume.
    @Deprecated("Deprecated in ComponentActivity")
    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (!WidgetHost.onActivityResult(this, requestCode, resultCode, data)) super.onActivityResult(requestCode, resultCode, data)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Home pressed while we're already the home screen: close whatever is open and go back to the top.
        if (intent.action == Intent.ACTION_MAIN && intent.hasCategory(Intent.CATEGORY_HOME)) homePresses.trySend(Unit)
    }
}
