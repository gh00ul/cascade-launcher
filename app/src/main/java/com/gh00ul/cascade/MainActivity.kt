package com.gh00ul.cascade

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
        setContent { LauncherTheme { LauncherScreen(homePressFlow) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Home pressed while we're already the home screen: close whatever is open and go back to the top.
        if (intent.action == Intent.ACTION_MAIN && intent.hasCategory(Intent.CATEGORY_HOME)) homePresses.trySend(Unit)
    }
}
