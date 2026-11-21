package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import com.example.data.AppDatabase
import com.example.data.AudioConversionEntity
import com.example.data.AudioOutputFormat
import com.example.data.ConversionStatus
import com.example.data.MediaType
import com.example.data.MediaVaultRepository
import com.example.network.AdtsHeaderWriter
import com.example.network.BatchConversionEngine
import com.example.network.MediaDownloadEngine
import com.example.network.MediaStreamVariant
import com.example.network.Mp3FrameEncoder
import com.example.network.StagedVideoInput
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class BatchAudioExtractionTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var context: Context

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        val config = Configuration.Builder().build()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, config)
    }

    @Test
    fun adtsHeaderEncodesValidSyncAndFrameLength() {
        val payloadLen = 412
        val header = AdtsHeaderWriter.buildHeader(
            aacFrameLength = payloadLen,
            sampleRate = 44100,
            channelCount = 2
        )
        assertEquals(7, header.size)
        assertEquals(0xFF, header[0].toInt() and 0xFF)
        assertEquals(0xF9, header[1].toInt() and 0xFF)

        val encodedFullLen = ((header[3].toInt() and 0x03) shl 11) or
            ((header[4].toInt() and 0xFF) shl 3) or
            ((header[5].toInt() and 0xE0) ushr 5)
        assertEquals(payloadLen + 7, encodedFullLen)
    }

    @Test
    fun mp3FrameEncoderProducesValidMpeg1Layer3Frames() {
        val sampleRate = 44100
        val channels = 2
        val encoder = Mp3FrameEncoder(
            inputSampleRate = sampleRate,
            inputChannels = channels,
            requestedBitrateKbps = 192
        )

        // Generate 0.1s of 440 Hz stereo 16-bit PCM sine wave (4410 samples)
        val totalSamples = 4410
        val pcmBuf = ByteBuffer.allocate(totalSamples * channels * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until totalSamples) {
            val s = (sin(2.0 * PI * 440.0 * i / sampleRate) * 16000.0).toInt().toShort()
            pcmBuf.putShort(s)
            pcmBuf.putShort(s)
        }
        val pcmBytes = pcmBuf.array()

        val out = ByteArrayOutputStream()
        encoder.encodePcm16(pcmBytes, pcmBytes.size, channels, out)
        encoder.flush(out)

        val mp3 = out.toByteArray()
        val expectedFrameBytes = (144 * 192 * 1000) / 44100 // 626 bytes per 1152-sample frame
        assertEquals(4 * expectedFrameBytes, mp3.size) // ceil(4410 / 1152) = 4 frames

        // Check MPEG-1 Layer III sync headers on every frame boundary
        for (frameIdx in 0 until 4) {
            val off = frameIdx * expectedFrameBytes
            assertEquals(0xFF, mp3[off].toInt() and 0xFF)
            assertEquals(0xFB, mp3[off + 1].toInt() and 0xFF)
            assertEquals(0xB0, mp3[off + 2].toInt() and 0xFF) // bitrateIdx=11 (192k), srIdx=0 (44.1k)
        }
    }

    @Test
    fun batchConversionEngineEnqueuesMultipleLocalVideosAndResumesAfterKill() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val conversionDao = db.conversionDao()
            val engine = BatchConversionEngine(context, conversionDao)

            val staged = listOf(
                StagedVideoInput("clip_alpha.mp4", "content://media/external/video/101"),
                StagedVideoInput("clip_beta.mkv", "content://media/external/video/102"),
                StagedVideoInput("clip_gamma.webm", "${tempFolder.root.absolutePath}/gamma.webm", isVaultItem = true)
            )

            engine.enqueueBatchConversions(
                items = staged,
                targetFormat = AudioOutputFormat.MP3,
                bitrateKbps = 320
            )

            // Wait for async Room inserts and WorkManager enqueues
            var rows: List<AudioConversionEntity> = emptyList()
            val wm = WorkManager.getInstance(context)
            var allEnqueued = false
            repeat(40) {
                org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
                rows = conversionDao.observeAll().first()
                if (rows.size == 3 && rows.all { !it.workId.isNullOrBlank() }) {
                    allEnqueued = rows.all { row ->
                        wm.getWorkInfosForUniqueWork(BatchConversionEngine.uniqueWorkName(row.id)).get().isNotEmpty()
                    }
                    if (allEnqueued) return@repeat
                }
                delay(40L)
            }

            assertEquals(3, rows.size)
            assertTrue(rows.all { it.targetFormat == AudioOutputFormat.MP3 && it.bitrateKbps == 320 })
            assertTrue(rows.all { it.outputFilePath.endsWith(".mp3") })

            rows.forEach { row ->
                val infos = wm.getWorkInfosForUniqueWork(BatchConversionEngine.uniqueWorkName(row.id)).get()
                assertTrue(infos.isNotEmpty())
                assertTrue(
                    infos.first().state == WorkInfo.State.ENQUEUED ||
                        infos.first().state == WorkInfo.State.RUNNING
                )
            }

            // Simulate an interrupted AAC extraction left in EXTRACTING state after process kill
            val interruptedId = conversionDao.insert(
                AudioConversionEntity(
                    sourceName = "killed_movie.mp4",
                    sourceUri = "content://media/external/video/999",
                    outputFilePath = "${tempFolder.root.absolutePath}/killed_movie.aac",
                    targetFormat = AudioOutputFormat.AAC,
                    bitrateKbps = 192,
                    status = ConversionStatus.EXTRACTING,
                    progressPercent = 45
                )
            )
            engine.resumePendingConversionsIfNeeded()

            var resumedInfos: List<WorkInfo> = emptyList()
            repeat(30) {
                org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
                resumedInfos = wm.getWorkInfosForUniqueWork(BatchConversionEngine.uniqueWorkName(interruptedId)).get()
                if (resumedInfos.isNotEmpty()) return@repeat
                delay(40L)
            }
            assertTrue(resumedInfos.isNotEmpty())
            assertTrue(
                resumedInfos.first().state == WorkInfo.State.ENQUEUED ||
                    resumedInfos.first().state == WorkInfo.State.RUNNING
            )
        } finally {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            db.close()
        }
    }

    @Test
    fun batchDownloadSchedulesVideoAndAudioVariantsWithAutoExtractMetadata() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val downloadDao = db.downloadDao()
            val repo = MediaVaultRepository(downloadDao)
            val engine = MediaDownloadEngine(context, repo)

            val batch = listOf(
                MediaDownloadEngine.BatchDownloadItem(
                    title = "Lecture Part 1 (MP4 1080p)",
                    sourceUrl = "https://example.org/watch/1",
                    variant = MediaStreamVariant(
                        id = "v1",
                        mediaUrl = "https://cdn.example.org/part1_1080.mp4",
                        mediaType = MediaType.VIDEO,
                        formatLabel = "MP4",
                        qualityLabel = "1080p",
                        mimeType = "video/mp4"
                    ),
                    thumbnailUrl = null
                ),
                MediaDownloadEngine.BatchDownloadItem(
                    title = "Lecture Part 1 (M4A Audio)",
                    sourceUrl = "https://example.org/watch/1",
                    variant = MediaStreamVariant(
                        id = "a1",
                        mediaUrl = "https://cdn.example.org/part1.m4a",
                        mediaType = MediaType.AUDIO,
                        formatLabel = "M4A",
                        qualityLabel = "192 kbps",
                        mimeType = "audio/mp4"
                    ),
                    thumbnailUrl = null
                )
            )

            engine.startBatchDownloads(
                items = batch,
                autoExtractFormat = AudioOutputFormat.AAC,
                extractBitrateKbps = 192,
                wifiOnly = false
            )

            val wm = WorkManager.getInstance(context)
            var downloads = downloadDao.getIncompleteDownloads()
            repeat(40) {
                org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
                downloads = downloadDao.getIncompleteDownloads()
                if (downloads.size == 2 && downloads.all { !it.workId.isNullOrBlank() }) {
                    val ready = downloads.all { d ->
                        wm.getWorkInfosForUniqueWork(MediaDownloadEngine.uniqueWorkName(d.id)).get().isNotEmpty()
                    }
                    if (ready) return@repeat
                }
                delay(40L)
            }
            assertEquals(2, downloads.size)

            downloads.forEach { d ->
                val infos = wm.getWorkInfosForUniqueWork(MediaDownloadEngine.uniqueWorkName(d.id)).get()
                assertTrue(infos.isNotEmpty())
            }
        } finally {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            db.close()
        }
    }
}
