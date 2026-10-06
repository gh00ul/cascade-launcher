package com.gh00ul.cascade.data

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.content.pm.LauncherApps
import android.content.pm.ShortcutInfo
import android.os.Process
import android.os.UserHandle
import android.os.UserManager
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowLauncherApps
import org.robolectric.shadows.ShadowUserManager.UserState

/** Search's shortcuts across profiles: one that can't answer (paused, still locked) never costs the others theirs. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], shadows = [ShortcutSearchTest.PausedWorkProfile::class])
class ShortcutSearchTest {
    /** The personal profile's shortcuts are [served]; the work profile's query throws, as a paused one's does. */
    @Implements(LauncherApps::class)
    class PausedWorkProfile : ShadowLauncherApps() {
        @Implementation
        override fun getShortcuts(query: LauncherApps.ShortcutQuery, user: UserHandle): List<ShortcutInfo> {
            asked += user
            if (user == WORK) throw IllegalStateException("User 10 is locked or not running")
            return served
        }

        companion object {
            /** User 10, as a work profile usually is. */
            val WORK: UserHandle = UserHandle.getUserHandleForUid(10 * 100_000)
            var served: List<ShortcutInfo> = emptyList()
            val asked = ArrayList<UserHandle>()
        }
    }

    private val context: Application = RuntimeEnvironment.getApplication()
    private val users get() = shadowOf(context.getSystemService(UserManager::class.java))
    private val component = ComponentName(context, "${context.packageName}.Main")
    private val app = AppEntry(
        key = "${component.flattenToString()}#0", label = "Chrome", originalLabel = "Chrome", component = component,
        user = Process.myUserHandle(), isWork = false, isManagedProfile = false, section = "C",
    )

    @Before
    fun serve() {
        Shadow.extract<ShadowLauncherApps>(context.getSystemService(LauncherApps::class.java)).setHasShortcutHostPermission(true)
        users.addUserProfile(PausedWorkProfile.WORK)
        users.setUserState(Process.myUserHandle(), UserState.STATE_RUNNING_UNLOCKED)
        PausedWorkProfile.asked.clear()
        PausedWorkProfile.served = listOf(
            ShortcutInfo.Builder(context, "new-tab").setShortLabel("New tab").setActivity(component).setIntent(Intent(Intent.ACTION_VIEW)).build(),
        )
    }

    /** Paused or not yet unlocked: the work profile isn't asked, and the personal shortcuts come back. */
    @Test fun aStoppedWorkProfileIsSkipped() {
        users.setUserState(PausedWorkProfile.WORK, UserState.STATE_SHUTDOWN)
        val found = loadSearchShortcuts(context, listOf(app))
        assertEquals(listOf("New tab"), found.map { it.label })
        assertEquals(app, found.single().app)
        assertEquals(listOf(Process.myUserHandle()), PausedWorkProfile.asked)
    }

    /** Asked anyway (it paused mid-search) and throwing: the personal shortcuts still come back. */
    @Test fun aWorkProfileThatThrowsCostsNothingElse() {
        users.setUserState(PausedWorkProfile.WORK, UserState.STATE_RUNNING_UNLOCKED)
        assertEquals(listOf("New tab"), loadSearchShortcuts(context, listOf(app)).map { it.label })
    }
}
