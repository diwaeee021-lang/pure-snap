package com.example

import androidx.media3.common.MimeTypes
import androidx.media3.common.Tracks
import com.example.player.AbLoopState
import com.example.player.AudioFxController
import com.example.player.EqPreset
import com.example.player.PlayerAspectMode
import com.example.player.PlayerTrackHelper
import com.example.player.SupportedFormatCatalog
import com.example.privacy.PrivacyShield
import com.example.ui.PlayerTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AllFormatPlayerTest {

    @Test
    fun supportedFormatCatalogDetectsAllMajorVideoAudioAdaptiveAndSubtitleFormats() {
        val adaptiveCases = listOf(
            "https://cdn.example.org/stream/master.m3u8" to MimeTypes.APPLICATION_M3U8,
            "https://cdn.example.org/dash/manifest.mpd" to MimeTypes.APPLICATION_MPD,
            "https://cdn.example.org/live.ism/manifest" to MimeTypes.APPLICATION_SS,
            "rtsp://cam.example.org:554/stream1" to MimeTypes.APPLICATION_RTSP
        )
        adaptiveCases.forEach { (url, expectedMime) ->
            assertEquals(expectedMime, SupportedFormatCatalog.resolveAdaptiveOrHintMimeType(url))
        }

        // Progressive containers let DefaultExtractorsFactory auto-sniff cleanly
        listOf("movie.mkv", "clip.avi", "video.flv", "video.webm", "song.flac", "voice.opus", "audio.mp3")
            .forEach { fileName ->
                assertNull(SupportedFormatCatalog.resolveAdaptiveOrHintMimeType("file:///sdcard/$fileName"))
            }

        // Audio vs video classification
        assertTrue(SupportedFormatCatalog.isAudioOnly("track.flac"))
        assertTrue(SupportedFormatCatalog.isAudioOnly("podcast.opus"))
        assertTrue(SupportedFormatCatalog.isAudioOnly("song.mp3"))
        assertTrue(SupportedFormatCatalog.isAudioOnly("stream", "audio/aac"))
        assertFalse(SupportedFormatCatalog.isAudioOnly("movie.mkv"))
        assertFalse(SupportedFormatCatalog.isAudioOnly("clip.avi"))
        assertFalse(SupportedFormatCatalog.isAudioOnly("movie.flv"))
        assertFalse(SupportedFormatCatalog.isAudioOnly("stream.m3u8"))

        // Subtitles
        assertEquals(MimeTypes.APPLICATION_SUBRIP, SupportedFormatCatalog.resolveSubtitleMimeType("sub.srt"))
        assertEquals(MimeTypes.TEXT_VTT, SupportedFormatCatalog.resolveSubtitleMimeType("captions.vtt"))
        assertEquals(MimeTypes.TEXT_SSA, SupportedFormatCatalog.resolveSubtitleMimeType("anime.ass"))
        assertEquals(MimeTypes.APPLICATION_TTML, SupportedFormatCatalog.resolveSubtitleMimeType("movie.ttml"))
    }

    @Test
    fun buildMediaItemAttachesMetadataAdaptiveMimeAndSideloadedSubtitles() {
        val target = PlayerTarget(
            title = "Cosmos Laundromat",
            mediaUri = "https://cdn.example.org/cosmos/manifest.mpd",
            isAudioOnly = false,
            subtitle = "DASH • 4K",
            mimeType = MimeTypes.APPLICATION_MPD,
            subtitleUris = listOf("content://subs/en.srt", "content://subs/es.vtt")
        )

        val item = SupportedFormatCatalog.buildMediaItem(target)
        assertEquals("Cosmos Laundromat", item.mediaMetadata.title.toString())
        assertEquals(MimeTypes.APPLICATION_MPD, item.localConfiguration?.mimeType)
        val subs = item.localConfiguration?.subtitleConfigurations.orEmpty()
        assertEquals(2, subs.size)
        assertEquals(MimeTypes.APPLICATION_SUBRIP, subs[0].mimeType)
        assertEquals(MimeTypes.TEXT_VTT, subs[1].mimeType)
    }

    @Test
    fun abLoopStateAdvancesAndLoopsCorrectly() {
        var loop = AbLoopState()
        assertFalse(loop.isActive)
        assertFalse(loop.shouldLoopBack(5_000L))

        loop = loop.advance(4_000L)
        assertEquals(4_000L, loop.pointA)
        assertNull(loop.pointB)
        assertFalse(loop.isActive)

        loop = loop.advance(12_000L)
        assertEquals(4_000L, loop.pointA)
        assertEquals(12_000L, loop.pointB)
        assertTrue(loop.isActive)
        assertFalse(loop.shouldLoopBack(8_000L))
        assertTrue(loop.shouldLoopBack(12_050L))

        loop = loop.advance(15_000L)
        assertFalse(loop.isActive)
        assertNull(loop.pointA)
        assertNull(loop.pointB)
    }

    @Test
    fun audioFxControllerAppliesPresetsManualBandsBassAndLoudnessSafely() {
        val controller = AudioFxController()
        controller.applyPreset(EqPreset.BASS_BOOST)
        var st = controller.state.value
        assertEquals(EqPreset.BASS_BOOST, st.preset)
        assertTrue(st.bandLevelsMb[0] > 0)
        assertEquals(650, st.bassStrength)
        assertEquals(250, st.loudnessGainMb)

        controller.setBandLevel(2, 900)
        st = controller.state.value
        assertEquals(EqPreset.CUSTOM, st.preset)
        assertEquals(900, st.bandLevelsMb[2])

        controller.setBassStrength(950)
        controller.setLoudnessGain(1200)
        st = controller.state.value
        assertEquals(950, st.bassStrength)
        assertEquals(1200, st.loudnessGainMb)

        controller.setEnabled(false)
        assertFalse(controller.state.value.enabled)
        controller.release()
    }

    @Test
    fun privacyShieldAndAspectModesCoverAllContainers() {
        assertTrue(PrivacyShield.isMediaStreamUrl("https://cdn.example.org/movie.mkv"))
        assertTrue(PrivacyShield.isMediaStreamUrl("https://cdn.example.org/stream.mpd"))
        assertTrue(PrivacyShield.isMediaStreamUrl("https://cdn.example.org/audio.opus"))
        assertTrue(PrivacyShield.isMediaStreamUrl("rtsp://live.example.org/stream"))

        assertEquals("mkv", PrivacyShield.inferExtensionAndMime("https://cdn.example.org/video.mkv").first)
        assertEquals("mpd", PrivacyShield.inferExtensionAndMime("https://cdn.example.org/video.mpd").first)
        assertEquals("opus", PrivacyShield.inferExtensionAndMime("https://cdn.example.org/audio.opus").first)

        val techInfo = PlayerTrackHelper.extractTechnicalInfo(
            tracks = Tracks.EMPTY,
            uri = "file:///movies/sample.mkv",
            explicitMime = null
        )
        assertEquals("MKV", techInfo.formatLabel)
        assertEquals(PlayerAspectMode.FILL, PlayerAspectMode.FIT.next())
        assertEquals(PlayerAspectMode.FIT, PlayerAspectMode.RATIO_16_9.next())
    }
}
