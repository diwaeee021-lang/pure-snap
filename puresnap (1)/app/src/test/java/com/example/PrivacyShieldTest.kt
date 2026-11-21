package com.example

import com.example.privacy.PrivacyShield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PrivacyShieldTest {

    @Test
    fun blocksAdAndAnalyticsTrackers() {
        val doubleclick = PrivacyShield.matchBlockedHost("https://securepubads.g.doubleclick.net/gampad/ads")
        assertNotNull(doubleclick)
        assertEquals("doubleclick.net", doubleclick?.host)

        val mixpanel = PrivacyShield.matchBlockedHost("https://api.mixpanel.com/track")
        assertNotNull(mixpanel)
        assertEquals("Analytics", mixpanel?.category)

        val cleanMedia = PrivacyShield.matchBlockedHost("https://archive.org/download/sample/video.mp4")
        assertNull(cleanMedia)
    }

    @Test
    fun stripsTrackingQueryParametersWhilePreservingContentParams() {
        val result = PrivacyShield.sanitizeUrl(
            "https://example.org/watch?v=12345&utm_source=promo&fbclid=xyz&gclid=abc"
        )
        assertEquals("https://example.org/watch?v=12345", result.cleanUrl)
        assertEquals(listOf("utm_source", "fbclid", "gclid"), result.strippedParams)
    }

    @Test
    fun detectsMediaStreamUrls() {
        assertTrue(PrivacyShield.isMediaStreamUrl("https://cdn.example.org/clips/intro.mp4?token=abc"))
        assertTrue(PrivacyShield.isMediaStreamUrl("https://audio.example.org/podcast.m4a"))
        assertTrue(PrivacyShield.isMediaStreamUrl("https://stream.example.org/master.m3u8"))
        assertFalse(PrivacyShield.isMediaStreamUrl("https://example.org/article.html"))
    }
}
