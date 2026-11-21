package com.example.player

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import com.example.ui.PlayerTarget
import java.util.Locale

enum class PlayerAspectMode(val label: String) {
    FIT("Fit"),
    FILL("Stretch"),
    ZOOM("Zoom"),
    RATIO_16_9("16:9");

    fun next(): PlayerAspectMode {
        val entries = entries
        return entries[(ordinal + 1) % entries.size]
    }

    @OptIn(UnstableApi::class)
    fun toResizeMode(): Int = when (this) {
        FIT -> AspectRatioFrameLayout.RESIZE_MODE_FIT
        FILL -> AspectRatioFrameLayout.RESIZE_MODE_FILL
        ZOOM -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
        RATIO_16_9 -> AspectRatioFrameLayout.RESIZE_MODE_FIXED_WIDTH
    }
}

data class AbLoopState(
    val pointA: Long? = null,
    val pointB: Long? = null
) {
    val isActive: Boolean
        get() = pointA != null && pointB != null && pointB > pointA

    fun advance(currentPositionMs: Long): AbLoopState {
        val pos = currentPositionMs.coerceAtLeast(0L)
        return when {
            pointA == null -> AbLoopState(pointA = pos, pointB = null)
            pointB == null -> {
                if (pos > pointA + 400L) {
                    AbLoopState(pointA = pointA, pointB = pos)
                } else {
                    AbLoopState(pointA = pos, pointB = null)
                }
            }
            else -> AbLoopState(pointA = null, pointB = null)
        }
    }

    fun shouldLoopBack(currentPositionMs: Long): Boolean {
        val a = pointA ?: return false
        val b = pointB ?: return false
        return b > a && currentPositionMs >= b
    }
}

data class MediaTrackOption(
    val groupIndex: Int,
    val trackIndex: Int,
    val trackType: Int,
    val label: String,
    val language: String?,
    val mimeType: String?,
    val bitrate: Int,
    val isSelected: Boolean,
    val isSupported: Boolean
)

data class MediaTechnicalInfo(
    val formatLabel: String,
    val videoCodec: String,
    val resolution: String,
    val frameRate: String,
    val audioCodec: String,
    val audioSpec: String,
    val totalBitrate: String
)

object SupportedFormatCatalog {

    val supportedVideoExtensions = setOf(
        "mp4", "m4v", "mkv", "webm", "mov", "avi", "flv", "3gp", "3g2", "ts", "m2ts", "mts",
        "mpg", "mpeg", "vob", "ogv"
    )

    val supportedAudioExtensions = setOf(
        "mp3", "m4a", "aac", "flac", "ogg", "oga", "opus", "wav", "amr", "awb", "ac3", "ec3",
        "alac", "wma", "mid", "midi"
    )

    val supportedAdaptiveExtensions = setOf("m3u8", "mpd", "ism", "isml")

    val supportedSubtitleExtensions = setOf("srt", "vtt", "ass", "ssa", "ttml", "dfxp")

    fun extractExtension(uriOrName: String): String {
        val lower = uriOrName.lowercase(Locale.US)
        val clean = lower.substringBefore('?').substringBefore('#')
        val segment = clean.substringAfterLast('/')
        return if ('.' in segment) segment.substringAfterLast('.') else ""
    }

    fun isAudioOnly(uriOrName: String, explicitMime: String? = null): Boolean {
        val mime = explicitMime?.lowercase(Locale.US) ?: ""
        if (mime.startsWith("audio/")) return true
        if (mime.startsWith("video/")) return false
        val ext = extractExtension(uriOrName)
        return ext in supportedAudioExtensions
    }

    /**
     * Resolves an explicit Media3 MIME type when needed for adaptive manifests (HLS/DASH/SmoothStreaming/RTSP)
     * or extensionless URIs, while letting progressive containers auto-sniff via DefaultExtractorsFactory.
     */
    fun resolveAdaptiveOrHintMimeType(uriString: String, explicitMime: String? = null): String? {
        val lower = uriString.lowercase(Locale.US)
        if (lower.startsWith("rtsp://")) return MimeTypes.APPLICATION_RTSP

        val ext = extractExtension(uriString)
        return when {
            ext == "m3u8" || explicitMime.equals(MimeTypes.APPLICATION_M3U8, true) ||
                explicitMime.equals("application/x-mpegurl", true) -> MimeTypes.APPLICATION_M3U8
            ext == "mpd" || explicitMime.equals(MimeTypes.APPLICATION_MPD, true) -> MimeTypes.APPLICATION_MPD
            ext == "ism" || ext == "isml" || lower.endsWith("/manifest") ||
                explicitMime.equals(MimeTypes.APPLICATION_SS, true) -> MimeTypes.APPLICATION_SS
            explicitMime != null && explicitMime.isNotBlank() && ext.isBlank() -> explicitMime
            else -> null
        }
    }

    fun resolveSubtitleMimeType(uriString: String, displayName: String? = null): String {
        val ext = extractExtension(displayName ?: uriString).ifBlank { extractExtension(uriString) }
        return when (ext) {
            "vtt" -> MimeTypes.TEXT_VTT
            "ass", "ssa" -> MimeTypes.TEXT_SSA
            "ttml", "dfxp", "xml" -> MimeTypes.APPLICATION_TTML
            else -> MimeTypes.APPLICATION_SUBRIP
        }
    }

    fun detectDisplayFormatLabel(uriString: String, explicitMime: String? = null): String {
        val lower = uriString.lowercase(Locale.US)
        if (lower.startsWith("rtsp://")) return "RTSP"
        val ext = extractExtension(uriString)
        if (ext.isNotBlank()) return ext.uppercase(Locale.US)
        return when (explicitMime?.lowercase(Locale.US)) {
            MimeTypes.APPLICATION_M3U8, "application/x-mpegurl" -> "HLS"
            MimeTypes.APPLICATION_MPD -> "DASH"
            MimeTypes.APPLICATION_SS -> "ISM"
            MimeTypes.VIDEO_MATROSKA -> "MKV"
            MimeTypes.VIDEO_WEBM -> "WEBM"
            MimeTypes.VIDEO_MP4 -> "MP4"
            MimeTypes.AUDIO_MPEG -> "MP3"
            MimeTypes.AUDIO_FLAC -> "FLAC"
            MimeTypes.AUDIO_OPUS -> "OPUS"
            MimeTypes.AUDIO_OGG -> "OGG"
            MimeTypes.AUDIO_AAC -> "AAC"
            MimeTypes.AUDIO_WAV -> "WAV"
            else -> "MEDIA"
        }
    }

    fun buildMediaItem(target: PlayerTarget): MediaItem {
        val uri = Uri.parse(target.mediaUri)
        val adaptiveMime = resolveAdaptiveOrHintMimeType(target.mediaUri, target.mimeType)
        val metadata = MediaMetadata.Builder()
            .setTitle(target.title)
            .setSubtitle(target.subtitle)
            .build()

        val subtitleConfigs = target.subtitleUris.mapIndexed { idx, subUri ->
            MediaItem.SubtitleConfiguration.Builder(Uri.parse(subUri))
                .setMimeType(resolveSubtitleMimeType(subUri))
                .setLanguage("und")
                .setLabel("External Sub #${idx + 1}")
                .setSelectionFlags(if (idx == 0) C.SELECTION_FLAG_DEFAULT else 0)
                .build()
        }

        val builder = MediaItem.Builder()
            .setMediaId(target.mediaUri)
            .setUri(uri)
            .setMediaMetadata(metadata)

        if (adaptiveMime != null) {
            builder.setMimeType(adaptiveMime)
        }
        if (subtitleConfigs.isNotEmpty()) {
            builder.setSubtitleConfigurations(subtitleConfigs)
        }
        return builder.build()
    }

    fun formatCodecLabel(sampleMimeType: String?): String {
        val mime = sampleMimeType?.lowercase(Locale.US) ?: return "Auto"
        return when {
            "avc" in mime || "h264" in mime -> "H.264 / AVC"
            "hevc" in mime || "h265" in mime -> "H.265 / HEVC"
            "av01" in mime -> "AV1"
            "vp9" in mime -> "VP9"
            "vp8" in mime -> "VP8"
            "mp4v" in mime -> "MPEG-4"
            "mp4a" in mime || "aac" in mime -> "AAC"
            "mpeg" in mime || "mp3" in mime -> "MP3"
            "opus" in mime -> "Opus"
            "vorbis" in mime -> "Vorbis"
            "flac" in mime -> "FLAC"
            "alac" in mime -> "ALAC"
            "ac3" in mime -> "AC-3"
            "eac3" in mime -> "E-AC-3"
            "raw" in mime || "pcm" in mime -> "PCM / WAV"
            "amr" in mime -> "AMR"
            else -> sampleMimeType.substringAfterLast('/').uppercase(Locale.US)
        }
    }
}

object PlayerTrackHelper {

    fun extractTracks(tracks: Tracks): Pair<List<MediaTrackOption>, List<MediaTrackOption>> {
        val audioTracks = mutableListOf<MediaTrackOption>()
        val textTracks = mutableListOf<MediaTrackOption>()

        tracks.groups.forEachIndexed { groupIdx, group ->
            val type = group.type
            if (type != C.TRACK_TYPE_AUDIO && type != C.TRACK_TYPE_TEXT) return@forEachIndexed
            for (trackIdx in 0 until group.length) {
                val format = group.getTrackFormat(trackIdx)
                val option = MediaTrackOption(
                    groupIndex = groupIdx,
                    trackIndex = trackIdx,
                    trackType = type,
                    label = buildTrackLabel(format, type, trackIdx + 1),
                    language = format.language,
                    mimeType = format.sampleMimeType,
                    bitrate = format.bitrate,
                    isSelected = group.isTrackSelected(trackIdx),
                    isSupported = group.isTrackSupported(trackIdx)
                )
                if (type == C.TRACK_TYPE_AUDIO) {
                    audioTracks.add(option)
                } else {
                    textTracks.add(option)
                }
            }
        }
        return audioTracks to textTracks
    }

    fun extractTechnicalInfo(tracks: Tracks, uri: String, explicitMime: String?): MediaTechnicalInfo {
        var videoFormat: Format? = null
        var audioFormat: Format? = null

        for (group in tracks.groups) {
            if (group.type == C.TRACK_TYPE_VIDEO && videoFormat == null) {
                for (i in 0 until group.length) {
                    if (group.isTrackSelected(i)) {
                        videoFormat = group.getTrackFormat(i)
                        break
                    }
                }
                if (videoFormat == null && group.length > 0) videoFormat = group.getTrackFormat(0)
            }
            if (group.type == C.TRACK_TYPE_AUDIO && audioFormat == null) {
                for (i in 0 until group.length) {
                    if (group.isTrackSelected(i)) {
                        audioFormat = group.getTrackFormat(i)
                        break
                    }
                }
                if (audioFormat == null && group.length > 0) audioFormat = group.getTrackFormat(0)
            }
        }

        val formatLabel = SupportedFormatCatalog.detectDisplayFormatLabel(uri, explicitMime)
        val vCodec = SupportedFormatCatalog.formatCodecLabel(videoFormat?.sampleMimeType)
        val resolution = if (videoFormat != null && videoFormat.width > 0 && videoFormat.height > 0) {
            "${videoFormat.width} × ${videoFormat.height}"
        } else {
            "Audio / Adaptive"
        }
        val fps = if (videoFormat != null && videoFormat.frameRate > 0f) {
            String.format(Locale.US, "%.2f fps", videoFormat.frameRate)
        } else {
            "—"
        }
        val aCodec = SupportedFormatCatalog.formatCodecLabel(audioFormat?.sampleMimeType)
        val audioSpec = buildList {
            if (audioFormat != null && audioFormat.sampleRate > 0) {
                add("${audioFormat.sampleRate / 1000.0} kHz")
            }
            if (audioFormat != null && audioFormat.channelCount > 0) {
                add(
                    when (audioFormat.channelCount) {
                        1 -> "Mono"
                        2 -> "Stereo"
                        6 -> "5.1 Surround"
                        8 -> "7.1 Surround"
                        else -> "${audioFormat.channelCount} ch"
                    }
                )
            }
        }.joinToString(" • ").ifBlank { "Default" }

        val sumBitrate = listOfNotNull(
            videoFormat?.bitrate?.takeIf { it > 0 },
            audioFormat?.bitrate?.takeIf { it > 0 }
        ).sum()
        val totalBitrate = if (sumBitrate > 0) "${sumBitrate / 1000} kbps" else "Variable"

        return MediaTechnicalInfo(
            formatLabel = formatLabel,
            videoCodec = if (videoFormat == null) "None" else vCodec,
            resolution = resolution,
            frameRate = fps,
            audioCodec = if (audioFormat == null) "None" else aCodec,
            audioSpec = audioSpec,
            totalBitrate = totalBitrate
        )
    }

    private fun buildTrackLabel(format: Format, trackType: Int, fallbackNumber: Int): String {
        val parts = mutableListOf<String>()
        format.label?.takeIf { it.isNotBlank() }?.let { parts.add(it) }
        format.language?.takeIf { it.isNotBlank() && it != "und" }?.let { lang ->
            val localeName = Locale.forLanguageTag(lang).displayLanguage.ifBlank { lang.uppercase(Locale.US) }
            if (parts.none { it.equals(localeName, ignoreCase = true) }) {
                parts.add(localeName)
            }
        }
        if (trackType == C.TRACK_TYPE_AUDIO) {
            parts.add(SupportedFormatCatalog.formatCodecLabel(format.sampleMimeType))
            if (format.channelCount > 0) parts.add("${format.channelCount}ch")
            if (format.bitrate > 0) parts.add("${format.bitrate / 1000}kbps")
        } else {
            format.sampleMimeType?.let { parts.add(it.substringAfterLast('/').uppercase(Locale.US)) }
        }
        return parts.joinToString(" • ").ifBlank {
            if (trackType == C.TRACK_TYPE_AUDIO) "Audio Track #$fallbackNumber" else "Subtitle #$fallbackNumber"
        }
    }
}
