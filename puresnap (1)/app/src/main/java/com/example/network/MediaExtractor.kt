package com.example.network

import android.net.Uri
import com.example.data.MediaType
import com.example.privacy.PrivacyShield
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URL
import java.util.Locale
import java.util.concurrent.TimeUnit

data class MediaStreamVariant(
    val id: String,
    val mediaUrl: String,
    val mediaType: MediaType,
    val formatLabel: String,
    val qualityLabel: String,
    val mimeType: String,
    val estimatedBytes: Long = -1L,
    val isHls: Boolean = false
)

data class ExtractionResult(
    val sourceUrl: String,
    val cleanUrl: String,
    val strippedParams: List<String>,
    val title: String,
    val thumbnailUrl: String?,
    val variants: List<MediaStreamVariant>
)

class MediaExtractor {

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    suspend fun inspectUrl(
        rawUrl: String,
        sanitizeEnabled: Boolean = true
    ): Result<ExtractionResult> = withContext(Dispatchers.IO) {
        runCatching {
            val sanitized = if (sanitizeEnabled) {
                PrivacyShield.sanitizeUrl(rawUrl)
            } else {
                val trimmed = rawUrl.trim()
                val normalized = if (trimmed.startsWith("http://", true) || trimmed.startsWith("https://", true)) {
                    trimmed
                } else {
                    "https://$trimmed"
                }
                com.example.privacy.SanitizedUrlResult(normalized, emptyList())
            }

            val cleanUrl = sanitized.cleanUrl
            require(cleanUrl.startsWith("http://") || cleanUrl.startsWith("https://")) {
                "Enter a valid http:// or https:// media URL."
            }

            // First probe with HEAD, falling back to a ranged GET if server rejects HEAD
            val probeMeta = probeUrlMetadata(cleanUrl)
            val contentType = probeMeta.contentType?.lowercase(Locale.US) ?: ""

            if (isDirectMediaContentType(contentType) || PrivacyShield.isMediaStreamUrl(cleanUrl)) {
                val (ext, mime) = PrivacyShield.inferExtensionAndMime(cleanUrl, contentType)
                val isAudio = mime.startsWith("audio/")
                val isHls = ext == "m3u8"

                if (isHls) {
                    val hlsVariants = parseHlsPlaylist(cleanUrl)
                    val title = deriveTitleFromUrl(cleanUrl, "HLS Media Stream")
                    return@runCatching ExtractionResult(
                        sourceUrl = rawUrl,
                        cleanUrl = cleanUrl,
                        strippedParams = sanitized.strippedParams,
                        title = title,
                        thumbnailUrl = null,
                        variants = hlsVariants
                    )
                }

                val title = probeMeta.suggestedFilename ?: deriveTitleFromUrl(cleanUrl, "PureSnap Media")
                val variant = MediaStreamVariant(
                    id = "direct_${cleanUrl.hashCode()}",
                    mediaUrl = cleanUrl,
                    mediaType = if (isAudio) MediaType.AUDIO else MediaType.VIDEO,
                    formatLabel = ext.uppercase(Locale.US),
                    qualityLabel = if (isAudio) "Original Audio" else "Direct Stream",
                    mimeType = mime,
                    estimatedBytes = probeMeta.contentLength,
                    isHls = false
                )
                return@runCatching ExtractionResult(
                    sourceUrl = rawUrl,
                    cleanUrl = cleanUrl,
                    strippedParams = sanitized.strippedParams,
                    title = title,
                    thumbnailUrl = null,
                    variants = listOf(variant)
                )
            }

            // Fetch HTML document and extract media references
            val html = fetchHtmlBody(cleanUrl)
                ?: throw IllegalStateException("Could not load page content. Try opening it in the Privacy Browser tab.")

            extractFromHtml(
                rawUrl = rawUrl,
                cleanUrl = cleanUrl,
                strippedParams = sanitized.strippedParams,
                html = html
            )
        }
    }

    suspend fun probeVariantSize(url: String): Long = withContext(Dispatchers.IO) {
        probeUrlMetadata(url).contentLength
    }

    private data class ProbeMeta(
        val contentType: String?,
        val contentLength: Long,
        val suggestedFilename: String?
    )

    private fun probeUrlMetadata(url: String): ProbeMeta {
        val headReq = Request.Builder()
            .url(url)
            .head()
            .header("User-Agent", CLEAN_USER_AGENT)
            .header("DNT", "1")
            .build()

        runCatching {
            client.newCall(headReq).execute().use { resp ->
                if (resp.isSuccessful) {
                    val ct = resp.header("Content-Type")
                    val cl = resp.header("Content-Length")?.toLongOrNull() ?: -1L
                    val cd = parseContentDispositionFilename(resp.header("Content-Disposition"))
                    return ProbeMeta(ct, cl, cd)
                }
            }
        }

        // Fallback ranged GET (0-0) to read headers cleanly without downloading file
        val rangeReq = Request.Builder()
            .url(url)
            .get()
            .header("User-Agent", CLEAN_USER_AGENT)
            .header("DNT", "1")
            .header("Range", "bytes=0-0")
            .build()

        return runCatching {
            client.newCall(rangeReq).execute().use { resp ->
                val ct = resp.header("Content-Type")
                val totalFromRange = resp.header("Content-Range")
                    ?.substringAfterLast('/')
                    ?.toLongOrNull()
                val cl = totalFromRange ?: resp.header("Content-Length")?.toLongOrNull() ?: -1L
                val cd = parseContentDispositionFilename(resp.header("Content-Disposition"))
                ProbeMeta(ct, cl, cd)
            }
        }.getOrDefault(ProbeMeta(null, -1L, null))
    }

    private fun fetchHtmlBody(url: String): String? {
        val req = Request.Builder()
            .url(url)
            .get()
            .header("User-Agent", CLEAN_USER_AGENT)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("DNT", "1")
            .build()

        return runCatching {
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val body = resp.body ?: return null
                // Cap read at 2MB to stay fast and memory-safe
                val source = body.source()
                source.request(2L * 1024L * 1024L)
                source.buffer.clone().readUtf8()
            }
        }.getOrNull()
    }

    private fun extractFromHtml(
        rawUrl: String,
        cleanUrl: String,
        strippedParams: List<String>,
        html: String
    ): ExtractionResult {
        val baseUrl = runCatching { URL(cleanUrl) }.getOrNull()

        val ogTitle = extractMetaContent(html, "og:title")
            ?: extractMetaContent(html, "twitter:title")
            ?: extractHtmlTitle(html)
            ?: deriveTitleFromUrl(cleanUrl, "Web Media")

        val thumbnail = extractMetaContent(html, "og:image")
            ?.let { resolveUrl(baseUrl, it) }

        val discoveredUrls = linkedSetOf<String>()
        val qualityOverrides = mutableMapOf<String, Pair<MediaType?, String>>()

        // Normalize JSON-escaped slashes and ampersands (single or double-escaped) so embedded social payloads match cleanly
        val normalizedHtml = html
            .replace(Regex("""\\+u002F""", RegexOption.IGNORE_CASE), "/")
            .replace(Regex("""\\+u0026""", RegexOption.IGNORE_CASE), "&")
            .replace(Regex("""\\+/"""), "/")
            .replace("&amp;", "&")

        // 1. OpenGraph / Twitter meta tags
        listOf("og:video:secure_url", "og:video:url", "og:video", "og:audio", "twitter:player:stream")
            .forEach { prop ->
                extractMetaContent(normalizedHtml, prop)?.let { raw ->
                    resolveUrl(baseUrl, raw)?.let { resolved ->
                        discoveredUrls.add(resolved)
                        if (prop == "og:audio") {
                            qualityOverrides[resolved] = MediaType.AUDIO to "Audio Stream"
                        }
                    }
                }
            }

        // 2. HTML5 <video>, <audio>, <source> tags
        val tagSrcRegex = Regex(
            """<(?:video|audio|source)[^>]+src\s*=\s*["']([^"']+)["']""",
            setOf(RegexOption.IGNORE_CASE)
        )
        tagSrcRegex.findAll(normalizedHtml).forEach { match ->
            resolveUrl(baseUrl, match.groupValues[1])?.let { discoveredUrls.add(it) }
        }

        // 3. Structured JSON media fields (JSON-LD & social video/audio metadata blocks)
        val jsonMediaKeys = listOf(
            "playable_url_quality_hd" to (MediaType.VIDEO to "1080p HD"),
            "browser_native_hd_url" to (MediaType.VIDEO to "HD Video"),
            "hd_src" to (MediaType.VIDEO to "HD Video"),
            "playable_url" to (MediaType.VIDEO to "SD Video"),
            "browser_native_sd_url" to (MediaType.VIDEO to "SD Video"),
            "sd_src" to (MediaType.VIDEO to "SD Video"),
            "video_url" to (MediaType.VIDEO to "Social Video"),
            "videoUrl" to (MediaType.VIDEO to "Social Video"),
            "playAddr" to (MediaType.VIDEO to "HD Video"),
            "downloadAddr" to (MediaType.VIDEO to "HD Video"),
            "fallback_url" to (MediaType.VIDEO to "MP4 Video"),
            "playbackUrl" to (MediaType.VIDEO to "HD Stream"),
            "mp4Url" to (MediaType.VIDEO to "MP4 Video"),
            "hls_url" to (MediaType.VIDEO to "HLS Stream"),
            "hlsUrl" to (MediaType.VIDEO to "HLS Stream"),
            "contentUrl" to (null to "Direct Media"),
            "audio_url" to (MediaType.AUDIO to "Audio Stream"),
            "audioUrl" to (MediaType.AUDIO to "Audio Stream"),
            "stream_url" to (null to "Media Stream"),
            "streamUrl" to (null to "Media Stream"),
            "mp3-128" to (MediaType.AUDIO to "128 kbps MP3")
        )
        for ((key, hint) in jsonMediaKeys) {
            val keyRegex = Regex(
                """"${Regex.escape(key)}"\s*:\s*"(https?://[^"]+)"""",
                setOf(RegexOption.IGNORE_CASE)
            )
            keyRegex.findAll(normalizedHtml).forEach { match ->
                resolveUrl(baseUrl, match.groupValues[1])?.let { resolved ->
                    if (PrivacyShield.matchBlockedHost(resolved) == null) {
                        discoveredUrls.add(resolved)
                        qualityOverrides.putIfAbsent(resolved, hint)
                    }
                }
            }
        }

        // 4. Direct media links anywhere in page markup / JSON config
        val directMediaRegex = Regex(
            """https?://[^\s"'<>\\]+\.(?:mp4|m4v|mkv|webm|mov|avi|flv|ts|m3u8|mpd|mp3|m4a|aac|flac|ogg|opus|wav|amr)(?:\?[^\s"'<>\\]*)?""",
            setOf(RegexOption.IGNORE_CASE)
        )
        directMediaRegex.findAll(normalizedHtml).take(30).forEach { match ->
            val candidate = match.value
            if (PrivacyShield.matchBlockedHost(candidate) == null) {
                discoveredUrls.add(candidate)
            }
        }

        val variants = mutableListOf<MediaStreamVariant>()
        val seenMedia = hashSetOf<String>()

        for (mediaUrl in discoveredUrls) {
            if (!seenMedia.add(mediaUrl)) continue
            val (ext, mime) = PrivacyShield.inferExtensionAndMime(mediaUrl)
            if (ext == "m3u8") {
                variants.addAll(parseHlsPlaylist(mediaUrl))
            } else {
                val hint = qualityOverrides[mediaUrl]
                val isAudio = hint?.first == MediaType.AUDIO || mime.startsWith("audio/")
                val finalMime = if (isAudio && !mime.startsWith("audio/")) "audio/mp4" else mime
                val formatLabel = if (isAudio && ext == "mp4") "M4A" else ext.uppercase(Locale.US)
                val qualityLabel = hint?.second ?: inferQualityHint(mediaUrl, isAudio)
                variants.add(
                    MediaStreamVariant(
                        id = "v_${mediaUrl.hashCode()}",
                        mediaUrl = mediaUrl,
                        mediaType = if (isAudio) MediaType.AUDIO else MediaType.VIDEO,
                        formatLabel = formatLabel,
                        qualityLabel = qualityLabel,
                        mimeType = finalMime,
                        estimatedBytes = -1L,
                        isHls = false
                    )
                )
            }
        }

        if (variants.isEmpty()) {
            throw IllegalStateException(
                "No direct video or audio stream found in static HTML. Open this URL in the Privacy Browser tab so PureSnap can capture dynamic streams as the page plays."
            )
        }

        return ExtractionResult(
            sourceUrl = rawUrl,
            cleanUrl = cleanUrl,
            strippedParams = strippedParams,
            title = decodeHtmlEntities(ogTitle).trim(),
            thumbnailUrl = thumbnail,
            variants = variants.distinctBy { it.mediaUrl }
        )
    }

    private fun parseHlsPlaylist(playlistUrl: String): List<MediaStreamVariant> {
        val text = fetchHtmlBody(playlistUrl)
        if (text.isNullOrBlank() || !text.contains("#EXT-X-STREAM-INF")) {
            return listOf(
                MediaStreamVariant(
                    id = "hls_${playlistUrl.hashCode()}",
                    mediaUrl = playlistUrl,
                    mediaType = MediaType.VIDEO,
                    formatLabel = "HLS",
                    qualityLabel = "Adaptive Stream",
                    mimeType = "application/vnd.apple.mpegurl",
                    estimatedBytes = -1L,
                    isHls = true
                )
            )
        }

        val base = runCatching { URL(playlistUrl) }.getOrNull()
        val lines = text.lines()
        val result = mutableListOf<MediaStreamVariant>()

        for (i in lines.indices) {
            val line = lines[i].trim()
            if (line.startsWith("#EXT-X-STREAM-INF:")) {
                val nextUrl = lines.getOrNull(i + 1)?.trim().orEmpty()
                if (nextUrl.isNotEmpty() && !nextUrl.startsWith("#")) {
                    val resolved = resolveUrl(base, nextUrl) ?: nextUrl
                    val resolution = Regex("""RESOLUTION=\d+x(\d+)""").find(line)?.groupValues?.get(1)
                    val bandwidth = Regex("""BANDWIDTH=(\d+)""").find(line)?.groupValues?.get(1)?.toLongOrNull()
                    val qualityLabel = when {
                        resolution != null -> "${resolution}p HLS"
                        bandwidth != null -> "${bandwidth / 1000} kbps HLS"
                        else -> "HLS Variant"
                    }
                    result.add(
                        MediaStreamVariant(
                            id = "hls_${resolved.hashCode()}",
                            mediaUrl = resolved,
                            mediaType = MediaType.VIDEO,
                            formatLabel = "HLS",
                            qualityLabel = qualityLabel,
                            mimeType = "application/vnd.apple.mpegurl",
                            estimatedBytes = -1L,
                            isHls = true
                        )
                    )
                }
            }
        }

        return if (result.isNotEmpty()) result else listOf(
            MediaStreamVariant(
                id = "hls_${playlistUrl.hashCode()}",
                mediaUrl = playlistUrl,
                mediaType = MediaType.VIDEO,
                formatLabel = "HLS",
                qualityLabel = "Adaptive Stream",
                mimeType = "application/vnd.apple.mpegurl",
                estimatedBytes = -1L,
                isHls = true
            )
        )
    }

    private fun isDirectMediaContentType(contentType: String): Boolean {
        return contentType.startsWith("video/") ||
            contentType.startsWith("audio/") ||
            contentType.contains("mpegurl") ||
            contentType == "application/octet-stream"
    }

    private fun extractMetaContent(html: String, propertyName: String): String? {
        val escaped = Regex.escape(propertyName)
        val regex1 = Regex(
            """<meta[^>]+(?:property|name)\s*=\s*["']$escaped["'][^>]+content\s*=\s*["']([^"']+)["']""",
            RegexOption.IGNORE_CASE
        )
        val regex2 = Regex(
            """<meta[^>]+content\s*=\s*["']([^"']+)["'][^>]+(?:property|name)\s*=\s*["']$escaped["']""",
            RegexOption.IGNORE_CASE
        )
        return regex1.find(html)?.groupValues?.get(1)
            ?: regex2.find(html)?.groupValues?.get(1)
    }

    private fun extractHtmlTitle(html: String): String? {
        val regex = Regex("""<title[^>]*>(.*?)</title>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        return regex.find(html)?.groupValues?.get(1)?.replace(Regex("\\s+"), " ")?.trim()
    }

    private fun resolveUrl(base: URL?, raw: String): String? {
        val cleaned = raw.trim().replace("&amp;", "&")
        if (cleaned.isEmpty() || cleaned.startsWith("blob:", true) || cleaned.startsWith("data:", true)) {
            return null
        }
        return runCatching {
            if (base != null) URL(base, cleaned).toString() else cleaned
        }.getOrNull()
    }

    private fun inferQualityHint(url: String, isAudio: Boolean): String {
        if (isAudio) return "Audio Stream"
        val lower = url.lowercase(Locale.US)
        return when {
            lower.contains("2160") || lower.contains("4k") -> "2160p 4K"
            lower.contains("1080") -> "1080p HD"
            lower.contains("720") -> "720p HD"
            lower.contains("480") -> "480p SD"
            lower.contains("360") -> "360p"
            else -> "HD / Direct"
        }
    }

    private fun parseContentDispositionFilename(header: String?): String? {
        if (header.isNullOrBlank()) return null
        val match = Regex("""filename\*?=(?:UTF-8'')?["']?([^"';]+)["']?""", RegexOption.IGNORE_CASE)
            .find(header)
        return match?.groupValues?.get(1)?.trim()
    }

    private fun deriveTitleFromUrl(url: String, fallback: String): String {
        val lastSegment = runCatching { Uri.parse(url).lastPathSegment }.getOrNull()
        if (!lastSegment.isNullOrBlank()) {
            val withoutExt = lastSegment.substringBeforeLast('.')
                .replace('_', ' ')
                .replace('-', ' ')
                .trim()
            if (withoutExt.isNotBlank()) return withoutExt
        }
        return fallback
    }

    private fun decodeHtmlEntities(input: String): String {
        return input
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
    }

    companion object {
        private const val CLEAN_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"
    }
}
