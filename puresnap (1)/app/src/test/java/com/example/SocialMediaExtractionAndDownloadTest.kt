package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.ForegroundUpdater
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import androidx.work.workDataOf
import com.example.data.AppDatabase
import com.example.data.DownloadEntity
import com.example.data.DownloadStatus
import com.example.data.MediaType
import com.example.data.MediaVaultRepository
import com.example.network.MediaDownloadWorker
import com.example.network.MediaExtractor
import com.google.common.util.concurrent.Futures
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.util.concurrent.Executors

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SocialMediaExtractionAndDownloadTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var server: HttpServer
    private lateinit var baseUrl: String

    // Deterministic synthetic video & audio payloads
    private val reelHdVideoBytes = ByteArray(2_400_000) { idx -> ((idx * 31 + 7) and 0xFF).toByte() }
    private val reelSdVideoBytes = ByteArray(320_000) { idx -> ((idx * 17 + 3) and 0xFF).toByte() }
    private val socialAudioMp3Bytes = ByteArray(180_000) { idx -> ((idx * 13 + 9) and 0xFF).toByte() }
    private val socialAudioM4aBytes = ByteArray(210_000) { idx -> ((idx * 23 + 5) and 0xFF).toByte() }
    private val hlsSeg0Bytes = ByteArray(64_000) { idx -> (idx and 0xFF).toByte() }
    private val hlsSeg1Bytes = ByteArray(64_000) { idx -> ((idx + 64) and 0xFF).toByte() }
    private val hlsSeg2Bytes = ByteArray(48_000) { idx -> ((idx + 128) and 0xFF).toByte() }

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder().build()
        )

        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.executor = Executors.newCachedThreadPool()
        val port = server.address.port
        baseUrl = "http://127.0.0.1:$port"

        registerSocialPlatformEndpoints()
        server.start()
    }

    @After
    fun tearDown() {
        server.stop(0)
        val exec = Executors.newSingleThreadExecutor()
        try {
            exec.submit { AppDatabase.getInstance(context).clearAllTables() }.get()
        } finally {
            exec.shutdown()
        }
    }

    private fun registerSocialPlatformEndpoints() {
        // 1. Short-Video Social Reel Page (escaped JSON URLs + OpenGraph + blocked tracker link filtered)
        server.createContext("/social/reel") { ex ->
            val escapedBase = baseUrl.replace("/", "\\/")
            val html = """
                <!doctype html>
                <html>
                <head>
                  <meta property="og:title" content="Street Skate Clip &amp; Beats" />
                  <meta property="og:image" content="$baseUrl/thumb.jpg" />
                </head>
                <body>
                  <script>
                    window.__REEL_DATA__ = {
                      "playable_url_quality_hd": "$escapedBase\/cdn\/reel_hd.mp4?token=abc\u0026quality=hd",
                      "playable_url": "$escapedBase\/cdn\/reel_sd.mp4?token=abc\u0026quality=sd",
                      "audio_url": "$escapedBase\/cdn\/reel_audio.m4a?rate=192",
                      "telemetry": "https:\/\/doubleclick.net\/pixel.mp4"
                    };
                  </script>
                </body>
                </html>
            """.trimIndent()
            sendText(ex, 200, "text/html; charset=utf-8", html)
        }

        // 2. Microblog / Status Video + Voice Post (Twitter Card + HLS Master Playlist + MP3 Voice Note)
        server.createContext("/social/microblog") { ex ->
            val html = """
                <!doctype html>
                <html>
                <head>
                  <meta property="og:title" content="Live Launch Highlight" />
                  <meta name="twitter:player:stream" content="$baseUrl/cdn/microblog_hls_master.m3u8" />
                </head>
                <body>
                  <audio src="$baseUrl/cdn/voice_note.mp3" controls></audio>
                </body>
                </html>
            """.trimIndent()
            sendText(ex, 200, "text/html; charset=utf-8", html)
        }

        // 3. Community Feed Post (separate Video MP4 + Audio M4A via JSON fallback_url + audioUrl)
        server.createContext("/social/community") { ex ->
            val html = """
                <!doctype html>
                <html>
                <head>
                  <title>Drone Sunset Over Coast</title>
                  <meta property="og:video:secure_url" content="$baseUrl/cdn/reel_sd.mp4" />
                </head>
                <body>
                  <script type="application/json">
                    {
                      "fallback_url": "$baseUrl/cdn/reel_hd.mp4",
                      "audioUrl": "$baseUrl/cdn/reel_audio.m4a"
                    }
                  </script>
                </body>
                </html>
            """.trimIndent()
            sendText(ex, 200, "text/html; charset=utf-8", html)
        }

        // 4. Podcast / Music Social Share Page (og:audio + mp3-128 + Opus source)
        server.createContext("/social/music") { ex ->
            val html = """
                <!doctype html>
                <html>
                <head>
                  <meta property="og:title" content="Midnight Synth Session" />
                  <meta property="og:audio" content="$baseUrl/cdn/voice_note.mp3" />
                </head>
                <body>
                  <audio controls>
                    <source src="$baseUrl/cdn/session.opus" type="audio/opus" />
                  </audio>
                  <script>var streams = {"mp3-128": "$baseUrl/cdn/voice_note.mp3"};</script>
                </body>
                </html>
            """.trimIndent()
            sendText(ex, 200, "text/html; charset=utf-8", html)
        }

        // Media payloads with full Range 206 support
        server.createContext("/cdn/reel_hd.mp4") { ex ->
            sendBinaryWithRange(ex, "video/mp4", reelHdVideoBytes, "etag-hd-v1")
        }
        server.createContext("/cdn/reel_sd.mp4") { ex ->
            sendBinaryWithRange(ex, "video/mp4", reelSdVideoBytes, "etag-sd-v1")
        }
        server.createContext("/cdn/reel_audio.m4a") { ex ->
            sendBinaryWithRange(ex, "audio/mp4", socialAudioM4aBytes, "etag-m4a-v1")
        }
        server.createContext("/cdn/voice_note.mp3") { ex ->
            sendBinaryWithRange(ex, "audio/mpeg", socialAudioMp3Bytes, "etag-mp3-v1")
        }
        server.createContext("/cdn/session.opus") { ex ->
            sendBinaryWithRange(ex, "audio/opus", socialAudioM4aBytes, "etag-opus-v1")
        }

        // HLS Master + Media playlist + segments
        server.createContext("/cdn/microblog_hls_master.m3u8") { ex ->
            val m3u8 = """
                #EXTM3U
                #EXT-X-STREAM-INF:BANDWIDTH=2800000,RESOLUTION=1920x1080
                $baseUrl/cdn/hls_1080p.m3u8
                #EXT-X-STREAM-INF:BANDWIDTH=1200000,RESOLUTION=1280x720
                $baseUrl/cdn/hls_720p.m3u8
            """.trimIndent()
            sendText(ex, 200, "application/vnd.apple.mpegurl", m3u8)
        }
        server.createContext("/cdn/hls_1080p.m3u8") { ex ->
            val m3u8 = """
                #EXTM3U
                #EXT-X-VERSION:3
                #EXTINF:2.0,
                $baseUrl/cdn/seg0.ts
                #EXTINF:2.0,
                $baseUrl/cdn/seg1.ts
                #EXTINF:2.0,
                $baseUrl/cdn/seg2.ts
                #EXT-X-ENDLIST
            """.trimIndent()
            sendText(ex, 200, "application/vnd.apple.mpegurl", m3u8)
        }
        server.createContext("/cdn/hls_720p.m3u8") { ex ->
            val m3u8 = """
                #EXTM3U
                #EXT-X-VERSION:3
                #EXTINF:2.0,
                $baseUrl/cdn/seg0.ts
                #EXT-X-ENDLIST
            """.trimIndent()
            sendText(ex, 200, "application/vnd.apple.mpegurl", m3u8)
        }
        server.createContext("/cdn/seg0.ts") { ex -> sendBinaryWithRange(ex, "video/mp2t", hlsSeg0Bytes, "s0") }
        server.createContext("/cdn/seg1.ts") { ex -> sendBinaryWithRange(ex, "video/mp2t", hlsSeg1Bytes, "s1") }
        server.createContext("/cdn/seg2.ts") { ex -> sendBinaryWithRange(ex, "video/mp2t", hlsSeg2Bytes, "s2") }
    }

    @Test
    fun extractsVideoAndAudioStreamsFromMultipleSocialPlatformsAndSanitizesTrackers() = runBlocking {
        val extractor = MediaExtractor()

        // 1. Reel page with tracking query params stripped + escaped JSON URLs decoded
        val reelResult = extractor.inspectUrl(
            rawUrl = "$baseUrl/social/reel?utm_source=ig&igshid=track123&fbclid=fb999",
            sanitizeEnabled = true
        ).getOrThrow()

        assertEquals("$baseUrl/social/reel", reelResult.cleanUrl)
        assertEquals(listOf("utm_source", "igshid", "fbclid"), reelResult.strippedParams)
        assertEquals("Street Skate Clip & Beats", reelResult.title)
        assertTrue(reelResult.variants.any { it.mediaType == MediaType.VIDEO && it.mediaUrl.contains("reel_hd.mp4") })
        assertTrue(reelResult.variants.any { it.mediaType == MediaType.VIDEO && it.mediaUrl.contains("reel_sd.mp4") })
        assertTrue(reelResult.variants.any { it.mediaType == MediaType.AUDIO && it.mediaUrl.contains("reel_audio.m4a") })
        assertTrue(reelResult.variants.none { it.mediaUrl.contains("doubleclick.net") })

        // 2. Microblog post with multi-bitrate HLS + MP3 voice clip
        val microblogResult = extractor.inspectUrl("$baseUrl/social/microblog", sanitizeEnabled = true).getOrThrow()
        assertTrue(microblogResult.variants.any { it.isHls && it.qualityLabel == "1080p HLS" })
        assertTrue(microblogResult.variants.any { it.isHls && it.qualityLabel == "720p HLS" })
        assertTrue(microblogResult.variants.any { it.mediaType == MediaType.AUDIO && it.formatLabel == "MP3" })

        // 3. Community feed post with separate MP4 + M4A
        val communityResult = extractor.inspectUrl("$baseUrl/social/community", sanitizeEnabled = true).getOrThrow()
        assertEquals("Drone Sunset Over Coast", communityResult.title)
        assertTrue(communityResult.variants.any { it.mediaType == MediaType.VIDEO && it.mediaUrl.endsWith("reel_hd.mp4") })
        assertTrue(communityResult.variants.any { it.mediaType == MediaType.AUDIO && it.mediaUrl.endsWith("reel_audio.m4a") })

        // 4. Music / Podcast share page with MP3 & Opus
        val musicResult = extractor.inspectUrl("$baseUrl/social/music", sanitizeEnabled = true).getOrThrow()
        assertTrue(musicResult.variants.any { it.mediaType == MediaType.AUDIO && it.formatLabel == "MP3" })
        assertTrue(musicResult.variants.any { it.mediaType == MediaType.AUDIO && it.formatLabel == "OPUS" })
    }

    @Test
    fun endToEndDownloadsSocialHdVideoAudioAndHlsSegmentsWithByteIntegrity() = runBlocking {
        val extractor = MediaExtractor()
        val db = AppDatabase.getInstance(context)
        val repo = MediaVaultRepository(db.downloadDao())
        val outDir = tempFolder.newFolder("vault_social")

        // A. Extract Reel HD Video & Audio and download both via MediaDownloadWorker
        val reelResult = extractor.inspectUrl("$baseUrl/social/reel", sanitizeEnabled = true).getOrThrow()
        val hdVideoVariant = reelResult.variants.first { it.mediaUrl.contains("reel_hd.mp4") }
        val m4aAudioVariant = reelResult.variants.first { it.mediaUrl.contains("reel_audio.m4a") }

        val videoFile = File(outDir, "reel_hd.mp4")
        val videoId = repo.insertDownload(
            DownloadEntity(
                title = reelResult.title,
                sourceUrl = reelResult.cleanUrl,
                mediaUrl = hdVideoVariant.mediaUrl,
                thumbnailUrl = reelResult.thumbnailUrl,
                localFilePath = videoFile.absolutePath,
                mimeType = hdVideoVariant.mimeType,
                mediaType = MediaType.VIDEO,
                formatLabel = hdVideoVariant.formatLabel,
                qualityLabel = hdVideoVariant.qualityLabel,
                status = DownloadStatus.QUEUED
            )
        )
        runWorkerForDownload(videoId)

        val savedVideoEntity = repo.getById(videoId)!!
        assertEquals(DownloadStatus.COMPLETED, savedVideoEntity.status)
        assertEquals(4, savedVideoEntity.threadCount)
        assertTrue(savedVideoEntity.supportsRange)
        assertEquals(reelHdVideoBytes.size.toLong(), videoFile.length())
        assertEquals(sha256(reelHdVideoBytes), sha256(videoFile.readBytes()))

        // Download extracted M4A audio track
        val audioFile = File(outDir, "reel_audio.m4a")
        val audioId = repo.insertDownload(
            DownloadEntity(
                title = "${reelResult.title} Audio",
                sourceUrl = reelResult.cleanUrl,
                mediaUrl = m4aAudioVariant.mediaUrl,
                localFilePath = audioFile.absolutePath,
                mimeType = m4aAudioVariant.mimeType,
                mediaType = MediaType.AUDIO,
                formatLabel = m4aAudioVariant.formatLabel,
                qualityLabel = m4aAudioVariant.qualityLabel,
                status = DownloadStatus.QUEUED
            )
        )
        runWorkerForDownload(audioId)

        val savedAudioEntity = repo.getById(audioId)!!
        assertEquals(DownloadStatus.COMPLETED, savedAudioEntity.status)
        assertArrayEquals(socialAudioM4aBytes, audioFile.readBytes())

        // B. Extract Microblog HLS 1080p stream + MP3 voice note and download both
        val microblogResult = extractor.inspectUrl("$baseUrl/social/microblog", sanitizeEnabled = true).getOrThrow()
        val hls1080 = microblogResult.variants.first { it.isHls && it.qualityLabel == "1080p HLS" }
        val voiceMp3 = microblogResult.variants.first { it.formatLabel == "MP3" }

        val hlsFile = File(outDir, "microblog_1080p.ts")
        val hlsId = repo.insertDownload(
            DownloadEntity(
                title = microblogResult.title,
                sourceUrl = microblogResult.cleanUrl,
                mediaUrl = hls1080.mediaUrl,
                localFilePath = hlsFile.absolutePath,
                mimeType = "video/mp2t",
                mediaType = MediaType.VIDEO,
                formatLabel = "TS",
                qualityLabel = hls1080.qualityLabel,
                status = DownloadStatus.QUEUED
            )
        )
        runWorkerForDownload(hlsId)

        val expectedHlsBytes = hlsSeg0Bytes + hlsSeg1Bytes + hlsSeg2Bytes
        assertEquals(DownloadStatus.COMPLETED, repo.getById(hlsId)!!.status)
        assertArrayEquals(expectedHlsBytes, hlsFile.readBytes())

        val mp3File = File(outDir, "voice_note.mp3")
        val mp3Id = repo.insertDownload(
            DownloadEntity(
                title = "Voice Note",
                sourceUrl = microblogResult.cleanUrl,
                mediaUrl = voiceMp3.mediaUrl,
                localFilePath = mp3File.absolutePath,
                mimeType = voiceMp3.mimeType,
                mediaType = MediaType.AUDIO,
                formatLabel = "MP3",
                qualityLabel = voiceMp3.qualityLabel,
                status = DownloadStatus.QUEUED
            )
        )
        runWorkerForDownload(mp3Id)

        assertEquals(DownloadStatus.COMPLETED, repo.getById(mp3Id)!!.status)
        assertArrayEquals(socialAudioMp3Bytes, mp3File.readBytes())
    }

    private suspend fun runWorkerForDownload(downloadId: Long) {
        val worker = TestListenableWorkerBuilder<MediaDownloadWorker>(context)
            .setInputData(workDataOf(MediaDownloadWorker.KEY_DOWNLOAD_ID to downloadId))
            .setForegroundUpdater(ForegroundUpdater { _, _, _ -> Futures.immediateVoidFuture() })
            .build()
        val result = worker.doWork()
        assertTrue("Expected ListenableWorker.Result.Success but got $result", result is androidx.work.ListenableWorker.Result.Success)
    }

    private fun sendText(ex: com.sun.net.httpserver.HttpExchange, code: Int, contentType: String, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        ex.responseHeaders.set("Content-Type", contentType)
        if (ex.requestMethod.equals("HEAD", ignoreCase = true)) {
            ex.responseHeaders.set("Content-Length", bytes.size.toString())
            ex.sendResponseHeaders(code, -1)
            ex.close()
            return
        }
        ex.sendResponseHeaders(code, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }

    private fun sendBinaryWithRange(
        ex: com.sun.net.httpserver.HttpExchange,
        contentType: String,
        payload: ByteArray,
        etag: String
    ) {
        ex.responseHeaders.set("Content-Type", contentType)
        ex.responseHeaders.set("Accept-Ranges", "bytes")
        ex.responseHeaders.set("ETag", etag)

        if (ex.requestMethod.equals("HEAD", ignoreCase = true)) {
            ex.responseHeaders.set("Content-Length", payload.size.toString())
            ex.sendResponseHeaders(200, -1)
            ex.close()
            return
        }

        val rangeHeader = ex.requestHeaders.getFirst("Range")
        if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
            val spec = rangeHeader.removePrefix("bytes=").split('-')
            val start = spec.getOrNull(0)?.toIntOrNull() ?: 0
            val requestedEnd = spec.getOrNull(1)?.toIntOrNull() ?: (payload.size - 1)
            val end = requestedEnd.coerceAtMost(payload.size - 1)
            val sliceLen = (end - start + 1).coerceAtLeast(0)
            ex.responseHeaders.set("Content-Range", "bytes $start-$end/${payload.size}")
            ex.sendResponseHeaders(206, sliceLen.toLong())
            ex.responseBody.use { it.write(payload, start, sliceLen) }
        } else {
            ex.sendResponseHeaders(200, payload.size.toLong())
            ex.responseBody.use { it.write(payload) }
        }
    }

    private fun sha256(bytes: ByteArray): String {
        return MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }
    }
}
