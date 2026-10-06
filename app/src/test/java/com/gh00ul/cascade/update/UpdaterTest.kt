package com.gh00ul.cascade.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UpdaterTest {
    private val apk = "https://example.com/Cascade.apk"

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
}
