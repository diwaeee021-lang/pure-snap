package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import com.example.data.AppDatabase
import com.example.data.DownloadEntity
import com.example.data.DownloadStatus
import com.example.data.MediaType
import com.example.data.MediaVaultRepository
import com.example.network.ByteRange
import com.example.network.ChunkDownloadPlanner
import com.example.network.MediaDownloadEngine
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class WorkManagerDownloadTest {

    private lateinit var context: Context

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        val config = Configuration.Builder().build()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, config)
    }

    @Test
    fun chunkPlannerSplitsLargeRangeSupportedFilesIntoFourContiguousChunks() {
        val total = 10_000_000L // 10 MB
        val chunks = ChunkDownloadPlanner.planChunks(
            totalBytes = total,
            supportsRange = true,
            maxThreads = 4
        )
        assertEquals(4, chunks.size)
        assertEquals(0L, chunks.first().startByte)
        assertEquals(total - 1L, chunks.last().endByte)
        assertEquals(total, chunks.sumOf { it.expectedLength })

        // Verify contiguous non-overlapping coverage
        for (i in 0 until chunks.size - 1) {
            assertEquals(chunks[i].endByte + 1L, chunks[i + 1].startByte)
        }
    }

    @Test
    fun chunkPlannerComputesExactResumeOffsetAndSkipsCompletedChunks() {
        val range = ByteRange(index = 1, startByte = 1000L, endByte = 1999L)
        assertEquals(1000L, ChunkDownloadPlanner.computeResumeOffset(range, 0L))
        assertEquals(1450L, ChunkDownloadPlanner.computeResumeOffset(range, 450L))
        assertNull(ChunkDownloadPlanner.computeResumeOffset(range, 1000L))
    }

    @Test
    fun chunkPlannerFallsBackToSingleStreamWhenRangeUnsupported() {
        val chunks = ChunkDownloadPlanner.planChunks(
            totalBytes = 10_000_000L,
            supportsRange = false,
            maxThreads = 4
        )
        assertEquals(1, chunks.size)
        assertEquals(0L, chunks[0].startByte)
        assertEquals(9_999_999L, chunks[0].endByte)
    }

    @Test
    fun resumePendingDownloadsReattachesWorkManagerJobsAfterProcessKill() = runBlocking {
        val db = AppDatabase.getInstance(context)
        val repo = MediaVaultRepository(db.downloadDao())
        val engine = MediaDownloadEngine(context, repo)

        // Simulate a download left in DOWNLOADING state in Room after app process was killed
        val interrupted = DownloadEntity(
            title = "Interrupted Video",
            sourceUrl = "https://example.org/watch",
            mediaUrl = "https://example.org/video.mp4",
            localFilePath = "${engine.getVaultDirectory().absolutePath}/interrupted.mp4",
            mimeType = "video/mp4",
            mediaType = MediaType.VIDEO,
            formatLabel = "MP4",
            qualityLabel = "720p",
            totalBytes = 8_000_000L,
            downloadedBytes = 3_200_000L,
            status = DownloadStatus.DOWNLOADING
        )
        val id = repo.insertDownload(interrupted)

        engine.resumePendingDownloadsIfNeeded(wifiOnly = false)

        // Wait briefly for coroutine on Dispatchers.IO to enqueue unique work
        var workInfos: List<WorkInfo> = emptyList()
        repeat(20) {
            workInfos = WorkManager.getInstance(context)
                .getWorkInfosForUniqueWork(MediaDownloadEngine.uniqueWorkName(id))
                .get()
            if (workInfos.isNotEmpty()) return@repeat
            delay(50L)
        }

        assertTrue(workInfos.isNotEmpty())
        assertTrue(
            workInfos.first().state == WorkInfo.State.ENQUEUED ||
                workInfos.first().state == WorkInfo.State.RUNNING
        )
    }
}
