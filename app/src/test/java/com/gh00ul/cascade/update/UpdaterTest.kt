package com.gh00ul.cascade.update

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class UpdaterTest {
    private val apk = "https://example.com/Cascade.apk"
    private val minute = 60_000L
    private val hour = 60 * minute
    private val t0 = 1_791_000_000_000L
    /** The retry interval after an ordinary failure. */
    private val retry = Updater.RETRY_INTERVAL_MS

    @Test fun autoCheckIsDueSixHoursAfterACompletedCheck() {
        // A completed check also counts as an attempt.
        assertFalse(Updater.autoCheckDue(t0, t0, t0, retry))
        assertFalse(Updater.autoCheckDue(t0 + hour, t0, t0, retry))
        assertFalse(Updater.autoCheckDue(t0 + 6 * hour - 1, t0, t0, retry))
        assertTrue(Updater.autoCheckDue(t0 + 6 * hour, t0, t0, retry))
    }

    @Test fun failedAttemptHoldsOffRetriesForThirtyMinutes() {
        // Offline or rate-limited: LAST_CHECK is old (or never written), only the attempt is recent.
        for (lastCheck in listOf(0L, t0 - 2 * 24 * hour)) {
            assertFalse(Updater.autoCheckDue(t0, lastCheck, t0, retry))
            assertFalse(Updater.autoCheckDue(t0 + 29 * minute, lastCheck, t0, retry))
            assertFalse(Updater.autoCheckDue(t0 + 30 * minute - 1, lastCheck, t0, retry))
        }
    }

    @Test fun failedAttemptIsRetriedAfterThirtyMinutes() {
        assertTrue(Updater.autoCheckDue(t0 + 30 * minute, 0L, t0, retry))
        assertTrue(Updater.autoCheckDue(t0 + 30 * minute, t0 - 2 * 24 * hour, t0, retry))
        // But not before six hours have passed since the last completed check.
        assertFalse(Updater.autoCheckDue(t0 + 30 * minute, t0 - hour, t0, retry))
    }

    @Test fun aLongerRetryIntervalHoldsOffTheNextCheckThatLong() {
        // GitHub rate-limited the last attempt until 50 minutes later.
        assertFalse(Updater.autoCheckDue(t0 + 30 * minute, 0L, t0, 50 * minute))
        assertFalse(Updater.autoCheckDue(t0 + 50 * minute - 1, 0L, t0, 50 * minute))
        assertTrue(Updater.autoCheckDue(t0 + 50 * minute, 0L, t0, 50 * minute))
        // The clock moved back past the attempt: due, however long the wait was.
        assertTrue(Updater.autoCheckDue(t0, 0L, t0 + minute, 61 * minute))
    }

    @Test fun timestampsInTheFutureMeanTheClockMovedBackAndCountAsDue() {
        assertTrue(Updater.autoCheckDue(t0, t0 + hour, 0L, retry))
        assertTrue(Updater.autoCheckDue(t0, t0 - 7 * hour, t0 + minute, retry))
        assertTrue(Updater.autoCheckDue(t0, t0 + 1, t0 + 1, retry))
    }

    @Test fun firstRunIsDue() {
        assertTrue(Updater.autoCheckDue(t0, 0L, 0L, retry))
    }

    // checkFailure: what an HTTP error from GitHub tells the user, and how long the next automatic check waits

    private val nowSeconds = t0 / 1000
    private fun failure(code: Int, remaining: String? = null, reset: String? = null, retryAfter: String? = null) =
        Updater.checkFailure(code, remaining, reset, retryAfter, t0)
    private fun limited(about: String, retryMs: Long) =
        Updater.CheckFailure("GitHub is limiting update checks from this network. Try again in $about.", retryMs)

    @Test fun rateLimitWaitsUntilTheReset() {
        assertEquals(limited("about 45 minutes", 45 * minute), failure(403, remaining = "0", reset = "${nowSeconds + 45 * 60}"))
        assertEquals(limited("about 45 minutes", 45 * minute), failure(429, remaining = "0", reset = "${nowSeconds + 45 * 60}"))
        // Part of a minute counts as one.
        assertEquals(limited("about 41 minutes", 40 * minute + 10_000), failure(403, remaining = "0", reset = "${nowSeconds + 40 * 60 + 10}"))
    }

    @Test fun rateLimitWaitIsKeptBetweenThirtyAndSixtyOneMinutes() {
        // Soon: the message says so, but automatic checks still wait the usual 30 minutes.
        assertEquals(limited("about 5 minutes", retry), failure(403, remaining = "0", reset = "${nowSeconds + 5 * 60}"))
        // A reset already past (the phone's clock is off) is now.
        assertEquals(limited("about a minute", retry), failure(403, remaining = "0", reset = "${nowSeconds - 600}"))
        // Further off than GitHub's hourly window: at most 61 minutes.
        assertEquals(limited("about 180 minutes", 61 * minute), failure(403, remaining = "0", reset = "${nowSeconds + 3 * 60 * 60}"))
    }

    @Test fun retryAfterAlsoMeansARateLimit() {
        assertEquals(limited("about 2 minutes", retry), failure(403, retryAfter = "120"))
        assertEquals(limited("about 34 minutes", 34 * minute), failure(429, retryAfter = " 2040 "))
        assertEquals(limited("about 67 minutes", 61 * minute), failure(403, retryAfter = "4000"))
        // Both: the longer wait.
        assertEquals(limited("about 40 minutes", 40 * minute), failure(403, remaining = "0", reset = "${nowSeconds + 40 * 60}", retryAfter = "600"))
        assertEquals(limited("about 50 minutes", 50 * minute), failure(403, remaining = "0", reset = "${nowSeconds + 40 * 60}", retryAfter = "3000"))
    }

    @Test fun forbiddenWithoutRateLimitHeadersIsARefusal() {
        for (refused in listOf(
            failure(403),
            // Requests remain, so the reset doesn't matter.
            failure(403, remaining = "12", reset = "${nowSeconds + 45 * 60}"),
            failure(403, remaining = "0"),
            failure(403, remaining = "0", reset = "soon", retryAfter = "Wed, 21 Oct 2026 07:28:00 GMT"),
            failure(403, retryAfter = "-5"),
        )) {
            assertEquals(Updater.CheckFailure("GitHub refused the update check (HTTP 403).", retry), refused)
        }
        assertEquals(Updater.CheckFailure("GitHub refused the update check (HTTP 429).", retry), failure(429))
    }

    @Test fun serverErrorsAndOtherCodesRetryInThirtyMinutes() {
        for (code in listOf(500, 502, 503, 504)) {
            assertEquals(Updater.CheckFailure("GitHub isn't answering right now (HTTP $code). Try again later.", retry), failure(code))
        }
        for (code in listOf(301, 400, 401, 410, 422)) {
            assertEquals(Updater.CheckFailure("Couldn't check for updates (HTTP $code).", retry), failure(code))
        }
    }

    // readLatest: GitHub's answer, without the network

    /** GitHub's latest-release JSON for [tag] with [assets] (file names), trimmed to what Cascade reads. */
    private fun latest(tag: String, vararg assets: String): String {
        val list = assets.joinToString(",") { "{\"name\":\"$it\",\"browser_download_url\":\"${link(tag, it)}\"}" }
        return "{\"tag_name\":\"$tag\",\"name\":\"Cascade $tag\",\"assets\":[$list]}"
    }

    private fun link(tag: String, name: String) = "https://github.com/gh00ul/cascade-launcher/releases/download/$tag/$name"

    private fun read(connection: FakeConnection) = Updater.readLatest(connection, installedCode = 600_000, now = t0)

    @Test fun newerReleaseWithItsApkIsOfferedAndSettlesTheCheck() {
        val connection = FakeConnection(200, latest("v0.7.0", "cascade-0.7.0.apk"))
        val offer = Updater.Release("v0.7.0", "0.7.0", 700_000, link("v0.7.0", "cascade-0.7.0.apk"))
        assertEquals(Updater.CheckAnswer(offer, settled = true), read(connection))
        assertTrue(connection.disconnected)
    }

    @Test fun sameOrOlderReleaseIsUpToDateAndSettlesTheCheck() {
        assertEquals(Updater.CheckAnswer(null, settled = true), read(FakeConnection(200, latest("v0.6.0", "cascade-0.6.0.apk"))))
        // Without an APK too: there's nothing to wait for.
        assertEquals(Updater.CheckAnswer(null, settled = true), read(FakeConnection(200, latest("v0.5.0"))))
        // A tag that doesn't fit won't fit in half an hour either.
        assertEquals(Updater.CheckAnswer(null, settled = true), read(FakeConnection(200, latest("nightly", "cascade.apk"))))
    }

    @Test fun newerReleaseWithoutItsApkYetIsUpToDateWithoutSettlingTheCheck() {
        // The CI may still be uploading it: LAST_CHECK isn't written, so it's asked about again in 30 minutes.
        for (assets in listOf(emptyArray<String>(), arrayOf("checksums.txt", "cascade-0.7.0.apk.sha256"))) {
            assertEquals(Updater.CheckAnswer(null, settled = false), read(FakeConnection(200, latest("v0.7.0", *assets))))
        }
    }

    @Test fun noReleaseYetIsUpToDateAndSettlesTheCheck() {
        val connection = FakeConnection(404)
        assertEquals(Updater.CheckAnswer(null, settled = true), read(connection))
        assertFalse(connection.bodyRead)
        assertTrue(connection.disconnected)
    }

    @Test fun anAnswerThatIsntAReleaseFailsWithoutSettlingTheCheck() {
        val unreadable = Updater.CheckFailure("Couldn't read GitHub's answer.", retry)
        for (body in listOf(
            "",
            "<html>Sign in to this Wi-Fi</html>",
            "{}",
            """{"tag_name":"v0.7.0"}""",
            """{"assets":[]}""",
            """{"tag_name":"v0.7.0","assets":[{"name":"cascade-0.7.0.apk"}]}""",
            """{"tag_name":"v0.7.0","assets":"none"}""",
        )) {
            val connection = FakeConnection(200, body)
            assertEquals(body, unreadable, read(connection))
            assertTrue(connection.disconnected)
        }
    }

    @Test fun anAnswerTooLongToBeGitHubsIsNotRead() {
        val padding = " ".repeat(Updater.MAX_ANSWER_BYTES)
        assertEquals(Updater.CheckFailure("Couldn't read GitHub's answer.", retry), read(FakeConnection(200, latest("v0.7.0", "cascade-0.7.0.apk") + padding)))
        // Just under the cap still reads.
        val fits = latest("v0.7.0", "cascade-0.7.0.apk").let { it + " ".repeat(Updater.MAX_ANSWER_BYTES - it.length) }
        assertTrue(read(FakeConnection(200, fits)) is Updater.CheckAnswer)
    }

    @Test fun errorStatusIsReadBeforeTheBodyAndHeadersInAnyCase() {
        // Android throws a FileNotFoundException for an error status's body; that must never read as "no release".
        val connection = FakeConnection(403, headers = mapOf("X-RateLimit-Remaining" to "0", "X-RATELIMIT-RESET" to "${nowSeconds + 45 * 60}"))
        assertEquals(limited("about 45 minutes", 45 * minute), read(connection))
        assertFalse(connection.bodyRead)
        assertTrue(connection.disconnected)
        assertEquals(limited("about 2 minutes", retry), read(FakeConnection(429, headers = mapOf("retry-after" to "120"))))
        assertEquals(
            Updater.CheckFailure("GitHub isn't answering right now (HTTP 503). Try again later.", retry),
            read(FakeConnection(503)),
        )
    }

    @Test fun aConnectionFailureIsThrownAfterDisconnecting() {
        val connection = FakeConnection(200, failure = SocketTimeoutException("timeout"))
        assertThrows(IOException::class.java) { read(connection) }
        assertTrue(connection.disconnected)
    }

    @Test fun cascadeNamedApkWinsAndCaseDoesntMatter() {
        assertEquals("v0.7.0" to link("v0.7.0", "cascade-0.7.0.apk"), Updater.parseLatest(latest("v0.7.0", "other.apk", "cascade-0.7.0.apk")))
        assertEquals("v0.7.0" to link("v0.7.0", "Cascade-0.7.0.APK"), Updater.parseLatest(latest("v0.7.0", "notes.txt", "Cascade-0.7.0.APK")))
        // Without a cascade-* one, any APK.
        assertEquals("v0.7.0" to link("v0.7.0", "app-release.apk"), Updater.parseLatest(latest("v0.7.0", "app-release.apk")))
        assertEquals("v0.7.0" to null, Updater.parseLatest(latest("v0.7.0", "cascade-0.7.0.aab")))
    }

    // The install

    @Test fun releaseIsRebuiltFromTheResultsExtras() {
        // A new process gets the release back from the tag and link the install's result carries.
        assertEquals(Updater.Release("v0.7.0", "0.7.0", 700_000, apk), Updater.releaseOf("v0.7.0", apk))
        assertNull(Updater.releaseOf(null, apk))
        assertNull(Updater.releaseOf("v0.7.0", null))
        assertNull(Updater.releaseOf("nightly", apk))
    }

    @Test fun runningOutOfStorageIsFoundAnywhereInTheCauses() {
        val full = IOException("write failed: ENOSPC (No space left on device)")
        assertTrue(Updater.outOfSpace(full))
        assertTrue(Updater.outOfSpace(IllegalStateException("Couldn't copy the APK", IOException("wrapped", full))))
        assertFalse(Updater.outOfSpace(IOException("unexpected end of stream")))
        assertFalse(Updater.outOfSpace(IllegalStateException()))
    }

    @Test fun versionCodesMatchBuildGradle() {
        // app/build.gradle.kts: (major * 10000 + minor * 100 + patch) * 1000, plus the dev build count.
        assertEquals(200_000L, Updater.versionCode("v0.2.0"))
        assertEquals(201_000L, Updater.versionCode("v0.2.1"))
        assertEquals(500_000L, Updater.versionCode("v0.5.0"))
        assertEquals(600_000L, Updater.versionCode("v0.6.0"))
        assertEquals(10_000_000L, Updater.versionCode("v1.0.0"))
        assertEquals(123_456_000L, Updater.versionCode("v12.34.56"))
    }

    @Test fun acceptsTagsWithoutPrefixOrWithSurroundingWhitespace() {
        assertEquals(600_000L, Updater.versionCode("0.6.0"))
        assertEquals(600_000L, Updater.versionCode("  v0.6.0\n"))
    }

    @Test fun rejectsTagsThatDontFit() {
        for (tag in listOf("v0.6", "v0.6.0-rc1", "V0.6.0", "release", "", "v0.6.0.1", "vv0.6.0", "v 0.6.0", "v0.-6.0")) {
            assertNull(tag, Updater.versionCode(tag))
        }
    }

    @Test fun oversizedNumbersAreRejectedInsteadOfThrowing() {
        // 20 digits don't fit in a Long.
        assertNull(Updater.versionCode("v99999999999999999999.0.0"))
        assertNull(Updater.versionCode("v0.99999999999999999999.0"))
        assertNull(Updater.newerRelease("v99999999999999999999.0.0", apk, installedCode = 600_000))
    }

    @Test fun devBuildIsOfferedTheNextReleaseOnly() {
        // 0.5.0-dev.3 is 500003: above v0.5.0, below v0.5.1.
        assertNull(Updater.newerRelease("v0.5.0", apk, installedCode = 500_003))
        assertEquals(501_000L, Updater.newerRelease("v0.5.1", apk, installedCode = 500_003)?.versionCode)
        // Dev builds stop counting at 999 commits, still below the next release.
        assertEquals(501_000L, Updater.newerRelease("v0.5.1", apk, installedCode = 500_999)?.versionCode)
    }

    @Test fun sameOrOlderReleaseIsNotOffered() {
        assertNull(Updater.newerRelease("v0.6.0", apk, installedCode = 600_000))
        assertNull(Updater.newerRelease("v0.5.1", apk, installedCode = 600_000))
    }

    @Test fun releaseWithoutApkOrUnparseableTagIsNotOffered() {
        assertNull(Updater.newerRelease("v0.7.0", null, installedCode = 600_000))
        assertNull(Updater.newerRelease("nightly", apk, installedCode = 0))
    }

    @Test fun offeredReleaseCarriesTagVersionNameCodeAndApk() {
        assertEquals(Updater.Release("v0.6.0", "0.6.0", 600_000, apk), Updater.newerRelease("v0.6.0", apk, installedCode = 500_003))
        assertEquals("0.6.0", Updater.newerRelease("0.6.0", apk, installedCode = 500_003)?.versionName)
    }

    @Test fun dismissWinsOverAStoredTagReadBeforeIt() {
        val context = RuntimeEnvironment.getApplication()
        // Home's first composition started reading the stored tag off the main thread; "Not now" was tapped before
        // that read landed.
        Updater.dismiss(context, Updater.Release("v0.6.0", "0.6.0", 600_000, apk))
        Updater.storedDismissed("v0.5.0")
        assertEquals("v0.6.0", Updater.dismissed(context).value)
    }

    /** GitHub's answer without the network. Like Android's, it throws a FileNotFoundException for an error status's body. */
    private class FakeConnection(
        private val code: Int,
        private val body: String = "",
        private val headers: Map<String, String> = emptyMap(),
        private val failure: IOException? = null,
    ) : HttpURLConnection(URL("https://api.github.com/repos/gh00ul/cascade-launcher/releases/latest")) {
        var disconnected = false
        var bodyRead = false

        override fun getResponseCode(): Int {
            if (failure != null) throw failure
            return code
        }

        override fun getInputStream(): InputStream {
            if (code >= 400) throw FileNotFoundException("https://objects.example.com/signed")
            bodyRead = true
            return body.byteInputStream()
        }

        override fun getHeaderFields(): Map<String, List<String>> = headers.mapValues { listOf(it.value) }

        override fun connect() = Unit

        override fun disconnect() {
            disconnected = true
        }

        override fun usingProxy() = false
    }
}
