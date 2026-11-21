package com.example.network

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import com.example.data.AudioOutputFormat
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt

object AdtsHeaderWriter {
    private val SAMPLE_RATES = intArrayOf(
        96000, 88200, 64000, 48000, 44100, 32000,
        24000, 22050, 16000, 12000, 11025, 8000, 7350
    )

    fun samplingFrequencyIndex(sampleRate: Int): Int {
        val exact = SAMPLE_RATES.indexOf(sampleRate)
        if (exact >= 0) return exact
        return 4 // Fallback 44100 Hz
    }

    /**
     * Generates a standard 7-byte ADTS header for a raw AAC-LC frame.
     */
    fun buildHeader(
        aacFrameLength: Int,
        sampleRate: Int,
        channelCount: Int,
        aacObjectTypeProfile: Int = 2 // 2 = AAC-LC
    ): ByteArray {
        val fullLength = aacFrameLength + 7
        val freqIdx = samplingFrequencyIndex(sampleRate)
        val chanCfg = channelCount.coerceIn(1, 7)
        val profileMinusOne = (aacObjectTypeProfile - 1).coerceIn(0, 3)

        val header = ByteArray(7)
        header[0] = 0xFF.toByte()
        header[1] = 0xF9.toByte() // MPEG-2 / Layer 0 / protection_absent = 1
        header[2] = (((profileMinusOne) shl 6) or (freqIdx shl 2) or (chanCfg shr 2)).toByte()
        header[3] = (((chanCfg and 3) shl 6) or (fullLength shr 11)).toByte()
        header[4] = ((fullLength and 0x7FF) shr 3).toByte()
        header[5] = (((fullLength and 7) shl 5) or 0x1F).toByte()
        header[6] = 0xFC.toByte()
        return header
    }
}

/**
 * Pure-Kotlin MPEG-1 Layer III (.mp3) encoder using a 32-band polyphase filterbank,
 * 36-point MDCT, dynamic granule gain scaling, and ISO/IEC 11172-3 Layer III frame packetization.
 */
class Mp3FrameEncoder(
    inputSampleRate: Int,
    inputChannels: Int,
    requestedBitrateKbps: Int = 192
) {
    val sampleRate: Int = when {
        inputSampleRate >= 46000 -> 48000
        inputSampleRate >= 38000 -> 44100
        else -> 32000
    }
    val channels: Int = if (inputChannels <= 1) 1 else 2
    val bitrateKbps: Int = when {
        requestedBitrateKbps >= 256 -> 320
        requestedBitrateKbps >= 160 -> 192
        else -> 128
    }

    private val srIndex: Int = when (sampleRate) {
        44100 -> 0
        48000 -> 1
        else -> 2
    }
    private val bitrateIndex: Int = when (bitrateKbps) {
        128 -> 9
        192 -> 11
        else -> 14 // 320 kbps
    }

    private val frameBytesExact: Int = (144 * bitrateKbps * 1000) / sampleRate
    private val sideInfoBytes: Int = if (channels == 1) 17 else 32

    // Polyphase history buffer & overlap-add state per channel
    private val pcmHistory = Array(channels) { DoubleArray(512) }
    private val prevSubband = Array(channels) { Array(32) { DoubleArray(18) } }
    private val subbandSamples = Array(channels) { Array(2) { Array(18) { DoubleArray(32) } } }

    // Accumulation buffer for 1152 samples per channel per MPEG-1 Layer III frame
    private val sampleAccum = Array(channels) { ShortArray(1152) }
    private var accumPos = 0

    fun encodePcm16(
        pcmBytes: ByteArray,
        length: Int,
        srcChannels: Int,
        out: OutputStream
    ): Long {
        var writtenBytes = 0L
        val safeSrcChannels = srcChannels.coerceAtLeast(1)
        val bytesPerSampleFrame = safeSrcChannels * 2
        var offset = 0
        while (offset + bytesPerSampleFrame <= length) {
            val left = ((pcmBytes[offset].toInt() and 0xFF) or (pcmBytes[offset + 1].toInt() shl 8)).toShort()
            val right = if (safeSrcChannels >= 2) {
                ((pcmBytes[offset + 2].toInt() and 0xFF) or (pcmBytes[offset + 3].toInt() shl 8)).toShort()
            } else {
                left
            }

            if (channels == 1) {
                val mono = if (safeSrcChannels >= 2) ((left.toInt() + right.toInt()) / 2).toShort() else left
                sampleAccum[0][accumPos] = mono
            } else {
                sampleAccum[0][accumPos] = left
                sampleAccum[1][accumPos] = right
            }
            accumPos++

            if (accumPos == 1152) {
                val frame = encodeFullFrame()
                out.write(frame)
                writtenBytes += frame.size
                accumPos = 0
            }
            offset += bytesPerSampleFrame
        }
        return writtenBytes
    }

    fun flush(out: OutputStream): Long {
        if (accumPos == 0) return 0L
        for (ch in 0 until channels) {
            for (i in accumPos until 1152) {
                sampleAccum[ch][i] = 0
            }
        }
        accumPos = 0
        val frame = encodeFullFrame()
        out.write(frame)
        return frame.size.toLong()
    }

    private fun encodeFullFrame(): ByteArray {
        val frame = ByteArray(frameBytesExact)

        // 1. Write 4-byte MPEG-1 Layer III Header (no CRC)
        val channelModeBits = if (channels == 1) 0b11 else 0b00
        frame[0] = 0xFF.toByte()
        frame[1] = 0xFB.toByte()
        frame[2] = ((bitrateIndex shl 4) or (srIndex shl 2)).toByte()
        frame[3] = ((channelModeBits shl 6) or 0x04).toByte()

        // 2. Run 32-band polyphase analysis + 36-point MDCT for 2 granules x 18 time slots
        for (ch in 0 until channels) {
            val src = sampleAccum[ch]
            for (gr in 0 until 2) {
                val grOffset = gr * 576
                for (slot in 0 until 18) {
                    val slotOffset = grOffset + slot * 32
                    analyzePolyphase32(ch, src, slotOffset, subbandSamples[ch][gr][slot])
                }
            }
        }

        // 3. Quantize & pack main_data (starting right after sideInfoBytes)
        val maxMainDataBits = (frameBytesExact - 4 - sideInfoBytes) * 8
        val maxBitsPerGranule = (maxMainDataBits / (2 * channels)).coerceAtLeast(16)
        val mainWriter = BitWriter(frameBytesExact - 4 - sideInfoBytes)

        val part23Lengths = Array(2) { IntArray(channels) }
        val globalGains = Array(2) { IntArray(channels) }

        for (gr in 0 until 2) {
            for (ch in 0 until channels) {
                val mdct = DoubleArray(576)
                var maxAbs = 0.0
                for (sb in 0 until 32) {
                    computeMdct36(
                        prev = prevSubband[ch][sb],
                        curr = Array(18) { s -> subbandSamples[ch][gr][s][sb] }.toDoubleArray(),
                        out = mdct,
                        outOffset = sb * 18
                    )
                    for (t in 0 until 18) {
                        prevSubband[ch][sb][t] = subbandSamples[ch][gr][t][sb]
                        val v = abs(mdct[sb * 18 + t])
                        if (v > maxAbs) maxAbs = v
                    }
                }

                // Choose global_gain so peak spectral coefficient quantizes near 1.0
                val gainVal = if (maxAbs > 1e-6) {
                    val targetStep = maxAbs / 1.5
                    val g = (210.0 + 4.0 * (ln(targetStep) / ln(2.0))).roundToInt()
                    g.coerceIn(60, 245)
                } else {
                    120
                }
                globalGains[gr][ch] = gainVal
                val step = 2.0.pow((gainVal - 210.0) / 4.0)

                // Find highest non-zero quad (count1) within 576 lines
                val quantized = IntArray(576)
                var lastNonZero = -1
                for (i in 0 until 576) {
                    val mag = abs(mdct[i]) / step
                    val q = if (mag >= 0.45) 1 else 0
                    quantized[i] = if (q == 0) 0 else if (mdct[i] < 0) -1 else 1
                    if (q != 0) lastNonZero = i
                }

                val quadCount = if (lastNonZero < 0) 0 else ((lastNonZero / 4) + 1).coerceAtMost(144)
                val bitsBefore = mainWriter.bitPosition
                for (qIdx in 0 until quadCount) {
                    if ((mainWriter.bitPosition - bitsBefore) + 8 > maxBitsPerGranule ||
                        mainWriter.bitPosition + 8 > maxMainDataBits
                    ) {
                        break
                    }
                    val base = qIdx * 4
                    val v = quantized[base]
                    val w = quantized[base + 1]
                    val x = quantized[base + 2]
                    val y = quantized[base + 3]

                    val nzMask = ((if (v != 0) 8 else 0) or
                        (if (w != 0) 4 else 0) or
                        (if (x != 0) 2 else 0) or
                        (if (y != 0) 1 else 0))
                    // ISO 11172-3 count1 Table B: code = nzMask XOR 0xF (4 bits)
                    mainWriter.writeBits(nzMask xor 0x0F, 4)
                    if (v != 0) mainWriter.writeBits(if (v < 0) 1 else 0, 1)
                    if (w != 0) mainWriter.writeBits(if (w < 0) 1 else 0, 1)
                    if (x != 0) mainWriter.writeBits(if (x < 0) 1 else 0, 1)
                    if (y != 0) mainWriter.writeBits(if (y < 0) 1 else 0, 1)
                }
                part23Lengths[gr][ch] = mainWriter.bitPosition - bitsBefore
            }
        }

        // 4. Write exact MPEG-1 Layer III Side Information (17B mono / 32B stereo)
        val sideWriter = BitWriter(sideInfoBytes)
        sideWriter.writeBits(0, 9) // main_data_begin = 0 (self-contained frame)
        sideWriter.writeBits(0, if (channels == 1) 5 else 3) // private_bits
        for (ch in 0 until channels) {
            sideWriter.writeBits(0, 4) // scfsi
        }
        for (gr in 0 until 2) {
            for (ch in 0 until channels) {
                sideWriter.writeBits(part23Lengths[gr][ch], 12)
                sideWriter.writeBits(0, 9) // big_values = 0
                sideWriter.writeBits(globalGains[gr][ch], 8)
                sideWriter.writeBits(0, 4) // scalefac_compress
                sideWriter.writeBits(0, 1) // window_switching_flag
                sideWriter.writeBits(0, 5) // table_select[0]
                sideWriter.writeBits(0, 5) // table_select[1]
                sideWriter.writeBits(0, 5) // table_select[2]
                sideWriter.writeBits(0, 4) // region0_count
                sideWriter.writeBits(0, 3) // region1_count
                sideWriter.writeBits(0, 1) // preflag
                sideWriter.writeBits(0, 1) // scalefac_scale
                sideWriter.writeBits(1, 1) // count1table_select = 1 (Table B)
            }
        }

        val sideBytes = sideWriter.toByteArray()
        val mainBytes = mainWriter.toByteArray()
        System.arraycopy(sideBytes, 0, frame, 4, sideInfoBytes)
        System.arraycopy(mainBytes, 0, frame, 4 + sideInfoBytes, minOf(mainBytes.size, frame.size - 4 - sideInfoBytes))
        return frame
    }

    private fun analyzePolyphase32(ch: Int, src: ShortArray, offset: Int, outBands: DoubleArray) {
        val hist = pcmHistory[ch]
        System.arraycopy(hist, 0, hist, 32, 480)
        for (i in 0 until 32) {
            hist[31 - i] = src[offset + i] / 32768.0
        }
        val y = DoubleArray(64)
        for (i in 0 until 64) {
            var sum = 0.0
            for (j in 0 until 8) {
                val idx = i + 64 * j
                sum += hist[idx] * ANALYSIS_WINDOW[idx]
            }
            y[i] = sum
        }
        for (k in 0 until 32) {
            var sum = 0.0
            for (i in 0 until 64) {
                sum += FILTER_MATRIX[k][i] * y[i]
            }
            outBands[k] = sum
        }
    }

    private fun computeMdct36(prev: DoubleArray, curr: DoubleArray, out: DoubleArray, outOffset: Int) {
        val xin = DoubleArray(36)
        for (i in 0 until 18) {
            xin[i] = prev[i] * MDCT_WINDOW[i]
            xin[i + 18] = curr[i] * MDCT_WINDOW[i + 18]
        }
        for (k in 0 until 18) {
            var sum = 0.0
            val cosRow = MDCT_COS[k]
            for (n in 0 until 36) {
                sum += xin[n] * cosRow[n]
            }
            out[outOffset + k] = sum
        }
    }

    private class BitWriter(capacityBytes: Int) {
        private val buf = ByteArray(capacityBytes.coerceAtLeast(1))
        var bitPosition: Int = 0
            private set

        fun writeBits(value: Int, count: Int) {
            for (b in count - 1 downTo 0) {
                val byteIdx = bitPosition ushr 3
                if (byteIdx >= buf.size) return
                val bitShift = 7 - (bitPosition and 7)
                if (((value ushr b) and 1) != 0) {
                    buf[byteIdx] = (buf[byteIdx].toInt() or (1 shl bitShift)).toByte()
                }
                bitPosition++
            }
        }

        fun toByteArray(): ByteArray = buf
    }

    companion object {
        // Lightweight Hann-shaped 512-tap prototype window normalized for polyphase energy conservation
        private val ANALYSIS_WINDOW = DoubleArray(512) { i ->
            val x = (i - 256.0) / 32.0
            if (abs(x) < 1e-6) 1.0 / 8.0 else (kotlin.math.sin(PI * x) / (PI * x)) *
                (0.5 - 0.5 * cos(2.0 * PI * i / 511.0)) / 4.0
        }

        private val FILTER_MATRIX = Array(32) { k ->
            DoubleArray(64) { i ->
                cos((2 * k + 1) * (i - 16) * PI / 64.0)
            }
        }

        private val MDCT_WINDOW = DoubleArray(36) { i ->
            kotlin.math.sin(PI / 36.0 * (i + 0.5))
        }

        private val MDCT_COS = Array(18) { k ->
            DoubleArray(36) { n ->
                cos(PI / 72.0 * (2 * n + 1 + 18) * (2 * k + 1))
            }
        }
    }
}

object AudioTranscoder {

    data class ExtractionSummary(
        val outputFile: File,
        val outputBytes: Long,
        val streamCopied: Boolean
    )

    fun extractAndConvert(
        context: Context,
        sourceUriString: String,
        outputFile: File,
        targetFormat: AudioOutputFormat,
        bitrateKbps: Int,
        isCancelled: () -> Boolean,
        onProgress: (percent: Int, bytesWritten: Long) -> Unit
    ): ExtractionSummary {
        outputFile.parentFile?.mkdirs()
        if (outputFile.exists()) outputFile.delete()

        val extractor = MediaExtractor()
        try {
            val localFile = File(sourceUriString)
            if (localFile.exists() && localFile.isFile) {
                extractor.setDataSource(localFile.absolutePath)
            } else {
                val uri = Uri.parse(sourceUriString)
                if (uri.scheme == "file" && uri.path != null) {
                    extractor.setDataSource(uri.path!!)
                } else {
                    extractor.setDataSource(context, uri, null)
                }
            }

            var audioTrackIdx = -1
            var audioFormat: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val fmt = extractor.getTrackFormat(i)
                val mime = fmt.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) {
                    audioTrackIdx = i
                    audioFormat = fmt
                    break
                }
            }

            require(audioTrackIdx >= 0 && audioFormat != null) {
                "No audio track found in selected video file."
            }

            extractor.selectTrack(audioTrackIdx)
            val srcMime = audioFormat.getString(MediaFormat.KEY_MIME).orEmpty()
            val sampleRate = audioFormat.takeIf { it.containsKey(MediaFormat.KEY_SAMPLE_RATE) }
                ?.getInteger(MediaFormat.KEY_SAMPLE_RATE) ?: 44100
            val channelCount = audioFormat.takeIf { it.containsKey(MediaFormat.KEY_CHANNEL_COUNT) }
                ?.getInteger(MediaFormat.KEY_CHANNEL_COUNT) ?: 2
            val durationUs = audioFormat.takeIf { it.containsKey(MediaFormat.KEY_DURATION) }
                ?.getLong(MediaFormat.KEY_DURATION) ?: 0L

            // Fast zero-loss stream-copy paths when source codec already matches target
            if (targetFormat == AudioOutputFormat.AAC && srcMime == MediaFormat.MIMETYPE_AUDIO_AAC) {
                val bytes = copyAacWithAdtsHeaders(
                    extractor = extractor,
                    sampleRate = sampleRate,
                    channelCount = channelCount,
                    durationUs = durationUs,
                    outputFile = outputFile,
                    isCancelled = isCancelled,
                    onProgress = onProgress
                )
                return ExtractionSummary(outputFile, bytes, streamCopied = true)
            }

            if (targetFormat == AudioOutputFormat.MP3 && srcMime == MediaFormat.MIMETYPE_AUDIO_MPEG) {
                val bytes = copyRawMpegFrames(
                    extractor = extractor,
                    durationUs = durationUs,
                    outputFile = outputFile,
                    isCancelled = isCancelled,
                    onProgress = onProgress
                )
                return ExtractionSummary(outputFile, bytes, streamCopied = true)
            }

            // General transcoding path: decode source audio -> PCM -> encode to MP3 or AAC
            val bytes = transcodeViaPcm(
                extractor = extractor,
                sourceFormat = audioFormat,
                sourceMime = srcMime,
                sampleRate = sampleRate,
                channelCount = channelCount,
                durationUs = durationUs,
                outputFile = outputFile,
                targetFormat = targetFormat,
                bitrateKbps = bitrateKbps,
                isCancelled = isCancelled,
                onProgress = onProgress
            )
            return ExtractionSummary(outputFile, bytes, streamCopied = false)
        } finally {
            runCatching { extractor.release() }
        }
    }

    private fun copyAacWithAdtsHeaders(
        extractor: MediaExtractor,
        sampleRate: Int,
        channelCount: Int,
        durationUs: Long,
        outputFile: File,
        isCancelled: () -> Boolean,
        onProgress: (Int, Long) -> Unit
    ): Long {
        val buf = ByteBuffer.allocate(256 * 1024)
        var totalWritten = 0L
        BufferedOutputStream(FileOutputStream(outputFile)).use { out ->
            while (!isCancelled()) {
                val sampleSize = extractor.readSampleData(buf, 0)
                if (sampleSize < 0) break
                val raw = ByteArray(sampleSize)
                buf.position(0)
                buf.get(raw)
                val adts = AdtsHeaderWriter.buildHeader(sampleSize, sampleRate, channelCount)
                out.write(adts)
                out.write(raw)
                totalWritten += adts.size + sampleSize
                if (durationUs > 0L) {
                    val pct = ((extractor.sampleTime * 100L) / durationUs).toInt().coerceIn(0, 99)
                    onProgress(pct, totalWritten)
                }
                extractor.advance()
            }
            out.flush()
        }
        return totalWritten
    }

    private fun copyRawMpegFrames(
        extractor: MediaExtractor,
        durationUs: Long,
        outputFile: File,
        isCancelled: () -> Boolean,
        onProgress: (Int, Long) -> Unit
    ): Long {
        val buf = ByteBuffer.allocate(256 * 1024)
        var totalWritten = 0L
        BufferedOutputStream(FileOutputStream(outputFile)).use { out ->
            while (!isCancelled()) {
                val sampleSize = extractor.readSampleData(buf, 0)
                if (sampleSize < 0) break
                val raw = ByteArray(sampleSize)
                buf.position(0)
                buf.get(raw)
                out.write(raw)
                totalWritten += sampleSize
                if (durationUs > 0L) {
                    val pct = ((extractor.sampleTime * 100L) / durationUs).toInt().coerceIn(0, 99)
                    onProgress(pct, totalWritten)
                }
                extractor.advance()
            }
            out.flush()
        }
        return totalWritten
    }

    private fun transcodeViaPcm(
        extractor: MediaExtractor,
        sourceFormat: MediaFormat,
        sourceMime: String,
        sampleRate: Int,
        channelCount: Int,
        durationUs: Long,
        outputFile: File,
        targetFormat: AudioOutputFormat,
        bitrateKbps: Int,
        isCancelled: () -> Boolean,
        onProgress: (Int, Long) -> Unit
    ): Long {
        val decoder = MediaCodec.createDecoderByType(sourceMime)
        var encoder: MediaCodec? = null
        var activeSampleRate = sampleRate
        var activeChannels = channelCount
        var totalWritten = 0L

        val mp3Encoder = if (targetFormat == AudioOutputFormat.MP3) {
            Mp3FrameEncoder(sampleRate, channelCount, bitrateKbps)
        } else {
            null
        }

        try {
            decoder.configure(sourceFormat, null, null, 0)
            decoder.start()

            if (targetFormat == AudioOutputFormat.AAC) {
                val encFormat = MediaFormat.createAudioFormat(
                    MediaFormat.MIMETYPE_AUDIO_AAC,
                    sampleRate,
                    channelCount.coerceIn(1, 2)
                ).apply {
                    setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                    setInteger(MediaFormat.KEY_BIT_RATE, bitrateKbps * 1000)
                    setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 64 * 1024)
                }
                encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
                encoder.configure(encFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                encoder.start()
            }

            BufferedOutputStream(FileOutputStream(outputFile)).use { out ->
                val decInfo = MediaCodec.BufferInfo()
                val encInfo = MediaCodec.BufferInfo()
                var inputDone = false
                var decodeDone = false
                var encodeDone = (encoder == null)

                while ((!decodeDone || !encodeDone) && !isCancelled()) {
                    if (!inputDone) {
                        val inIdx = decoder.dequeueInputBuffer(5000L)
                        if (inIdx >= 0) {
                            val inBuf = decoder.getInputBuffer(inIdx)!!
                            val sampleSize = extractor.readSampleData(inBuf, 0)
                            if (sampleSize < 0) {
                                decoder.queueInputBuffer(inIdx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                val pts = extractor.sampleTime
                                decoder.queueInputBuffer(inIdx, 0, sampleSize, pts, 0)
                                if (durationUs > 0L) {
                                    val pct = ((pts * 100L) / durationUs).toInt().coerceIn(0, 99)
                                    onProgress(pct, totalWritten)
                                }
                                extractor.advance()
                            }
                        }
                    }

                    if (!decodeDone) {
                        val outIdx = decoder.dequeueOutputBuffer(decInfo, 5000L)
                        when {
                            outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                                val newFmt = decoder.outputFormat
                                if (newFmt.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                                    activeSampleRate = newFmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                                }
                                if (newFmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                                    activeChannels = newFmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                                }
                            }
                            outIdx >= 0 -> {
                                val pcmBuf = decoder.getOutputBuffer(outIdx)
                                val isEos = (decInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                                if (pcmBuf != null && decInfo.size > 0) {
                                    pcmBuf.position(decInfo.offset)
                                    pcmBuf.limit(decInfo.offset + decInfo.size)
                                    val pcmBytes = ByteArray(decInfo.size)
                                    pcmBuf.order(ByteOrder.LITTLE_ENDIAN).get(pcmBytes)

                                    if (mp3Encoder != null) {
                                        totalWritten += mp3Encoder.encodePcm16(pcmBytes, pcmBytes.size, activeChannels, out)
                                    } else if (encoder != null) {
                                        feedEncoder(encoder, pcmBytes, decInfo.presentationTimeUs, false)
                                    }
                                }
                                decoder.releaseOutputBuffer(outIdx, false)
                                if (isEos) {
                                    decodeDone = true
                                    if (encoder != null) {
                                        feedEncoder(encoder, ByteArray(0), decInfo.presentationTimeUs, true)
                                    }
                                }
                            }
                        }
                    }

                    if (encoder != null && !encodeDone) {
                        val encOutIdx = encoder.dequeueOutputBuffer(encInfo, 5000L)
                        if (encOutIdx >= 0) {
                            val encBuf = encoder.getOutputBuffer(encOutIdx)
                            val isConfig = (encInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                            val isEos = (encInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                            if (encBuf != null && encInfo.size > 0 && !isConfig) {
                                val aacPayload = ByteArray(encInfo.size)
                                encBuf.position(encInfo.offset)
                                encBuf.get(aacPayload)
                                val adts = AdtsHeaderWriter.buildHeader(
                                    aacFrameLength = aacPayload.size,
                                    sampleRate = activeSampleRate,
                                    channelCount = activeChannels
                                )
                                out.write(adts)
                                out.write(aacPayload)
                                totalWritten += adts.size + aacPayload.size
                            }
                            encoder.releaseOutputBuffer(encOutIdx, false)
                            if (isEos) encodeDone = true
                        }
                    }
                }

                if (mp3Encoder != null && !isCancelled()) {
                    totalWritten += mp3Encoder.flush(out)
                }
                out.flush()
            }
        } finally {
            runCatching {
                decoder.stop()
                decoder.release()
            }
            runCatching {
                encoder?.stop()
                encoder?.release()
            }
        }
        return totalWritten
    }

    private fun feedEncoder(encoder: MediaCodec, pcmBytes: ByteArray, ptsUs: Long, eos: Boolean) {
        var offset = 0
        while (offset < pcmBytes.size || eos) {
            val inIdx = encoder.dequeueInputBuffer(10_000L)
            if (inIdx < 0) continue
            val inBuf = encoder.getInputBuffer(inIdx)!!
            if (eos && offset >= pcmBytes.size) {
                encoder.queueInputBuffer(inIdx, 0, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                return
            }
            val chunk = minOf(inBuf.capacity(), pcmBytes.size - offset)
            inBuf.clear()
            inBuf.put(pcmBytes, offset, chunk)
            encoder.queueInputBuffer(inIdx, 0, chunk, ptsUs, 0)
            offset += chunk
            if (offset >= pcmBytes.size && !eos) return
        }
    }
}
