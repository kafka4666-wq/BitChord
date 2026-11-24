package com.music.bitchord.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdateCheckerTest {
    @Test
    fun parsesAndroidVersionCodeFromReleaseTitle() {
        assertEquals(47, AppUpdateChecker.releaseVersionCode("BitChord v1.15 (Android versionCode 47)"))
        assertEquals(null, AppUpdateChecker.releaseVersionCode("BitChord v1.14"))
    }

    @Test
    fun higherAndroidCodeIsOfferedEvenWhenDisplayVersionWasReset() {
        assertTrue(AppUpdateChecker.shouldOfferUpdate("1.15", 47, "1.14", 46))
    }

    @Test
    fun olderAndroidCodeIsNeverOfferedAsAnUpdate() {
        assertFalse(AppUpdateChecker.shouldOfferUpdate("1.14", 46, "1.11", 47))
    }

    @Test
    fun monotonicReleaseTagWithSameCodeDoesNotLoopAsAnUpdate() {
        assertFalse(AppUpdateChecker.shouldOfferUpdate("1.15", 47, "1.11", 47))
    }

    @Test
    fun equalCodeStillOffersTheMatchingStableReleaseToABeta() {
        assertTrue(AppUpdateChecker.shouldOfferUpdate("1.15", 47, "1.15-beta2", 47))
    }

    @Test
    fun releasesWithoutCodeMetadataKeepTheExistingNameComparison() {
        assertTrue(AppUpdateChecker.shouldOfferUpdate("1.12", null, "1.11", 47))
    }
}
