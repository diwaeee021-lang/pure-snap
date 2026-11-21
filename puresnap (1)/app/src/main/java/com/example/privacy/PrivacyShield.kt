package com.example.privacy

import android.net.Uri
import java.util.Locale

data class SanitizedUrlResult(
    val cleanUrl: String,
    val strippedParams: List<String>
)

data class BlockedRequestEvent(
    val host: String,
    val category: String,
    val timestamp: Long = System.currentTimeMillis()
)

object PrivacyShield {

    // Curated ad network, telemetry, analytics, and attribution tracker domains
    val blockedHosts: Set<String> = setOf(
        // Ad networks & exchanges
        "doubleclick.net",
        "googlesyndication.com",
        "googleadservices.com",
        "adservice.google.com",
        "admob.com",
        "ads.google.com",
        "pagead2.googlesyndication.com",
        "tpc.googlesyndication.com",
        "adnxs.com",
        "adsrvr.org",
        "rubiconproject.com",
        "pubmatic.com",
        "openx.net",
        "criteo.com",
        "criteo.net",
        "taboola.com",
        "outbrain.com",
        "revcontent.com",
        "mgid.com",
        "smartadserver.com",
        "casalemedia.com",
        "indexww.com",
        "advertising.com",
        "adroll.com",
        "media.net",
        "bidswitch.net",
        "sharethrough.com",
        "triplelift.com",
        "yieldmo.com",
        "33across.com",
        "sovrn.com",
        "lijit.com",
        "spotxchange.com",
        "teads.tv",
        "unityads.unity3d.com",
        "applovin.com",
        "vungle.com",
        "ironsrc.com",
        "chartboost.com",
        "inmobi.com",
        "mopub.com",
        "smaato.net",
        "tapjoy.com",
        "fyber.com",
        "pangle.io",
        "pangleglobal.com",
        "bytedance.com",
        "adcolony.com",
        "startappservice.com",
        "popads.net",
        "popcash.net",
        "propellerads.com",
        "exoclick.com",
        "juicyads.com",
        "adsterra.com",

        // Analytics, behavioral telemetry & session replay
        "google-analytics.com",
        "ssl.google-analytics.com",
        "analytics.google.com",
        "googletagmanager.com",
        "googletagservices.com",
        "firebase.googleapis.com",
        "firebaselogging.googleapis.com",
        "app-measurement.com",
        "crashlytics.com",
        "mixpanel.com",
        "api.mixpanel.com",
        "amplitude.com",
        "api.amplitude.com",
        "segment.io",
        "segment.com",
        "api.segment.io",
        "hotjar.com",
        "fullstory.com",
        "mouseflow.com",
        "crazyegg.com",
        "clarity.ms",
        "logrocket.com",
        "heapanalytics.com",
        "kissmetrics.com",
        "chartbeat.com",
        "scorecardresearch.com",
        "quantserve.com",
        "comscore.com",
        "newrelic.com",
        "nr-data.net",
        "sentry.io",
        "bugsnag.com",
        "datadoghq.com",

        // Mobile attribution & cross-app tracking
        "appsflyer.com",
        "adjust.com",
        "branch.io",
        "app.link",
        "kochava.com",
        "singular.net",
        "tenjin.io",
        "tune.com",
        "flurry.com",

        // Social pixels & retargeting
        "connect.facebook.net",
        "pixel.facebook.com",
        "an.facebook.com",
        "ads-twitter.com",
        "analytics.twitter.com",
        "ads.linkedin.com",
        "px.ads.linkedin.com",
        "analytics.tiktok.com",
        "ads.tiktok.com",
        "tr.snapchat.com",
        "sc-static.net",
        "ct.pinterest.com",
        "ads.pinterest.com",
        "alb.reddit.com",
        "events.reddit.com",
        "ads.yahoo.com",
        "bat.bing.com",
        "amazon-adsystem.com",
        "aax.amazon-adsystem.com"
    )

    private val trackerPathHints = listOf(
        "/pagead/",
        "/aclk?",
        "/pixel.gif",
        "/pixel?",
        "/tr/?id=",
        "/g/collect",
        "/j/collect",
        "/beacon/",
        "/telemetry",
        "/ads/vast",
        "/prebid",
        "doubleclick"
    )

    private val trackingQueryParams: Set<String> = setOf(
        "utm_source",
        "utm_medium",
        "utm_campaign",
        "utm_term",
        "utm_content",
        "utm_id",
        "utm_name",
        "utm_reader",
        "utm_viz_id",
        "utm_pubreferrer",
        "fbclid",
        "gclid",
        "gclsrc",
        "dclid",
        "msclkid",
        "yclid",
        "twclid",
        "ttclid",
        "li_fat_id",
        "mc_cid",
        "mc_eid",
        "igshid",
        "si",
        "spm",
        "ref_src",
        "ref_url",
        "_ga",
        "_gl",
        "_hsenc",
        "_hsmi",
        "mkt_tok",
        "vero_id",
        "wickedid",
        "oly_enc_id",
        "oly_anon_id",
        "rb_clickid",
        "s_kwcid",
        "zanpid",
        "epik"
    )

    fun matchBlockedHost(url: String): BlockedRequestEvent? {
        val lower = url.lowercase(Locale.US)
        val uri = runCatching { Uri.parse(lower) }.getOrNull() ?: return null
        val host = uri.host ?: return null

        // Direct or subdomain match against blocklist
        val parts = host.split('.')
        for (i in parts.indices) {
            val candidate = parts.drop(i).joinToString(".")
            if (candidate in blockedHosts) {
                val category = categorizeHost(candidate)
                return BlockedRequestEvent(host = candidate, category = category)
            }
        }

        // Heuristic tracker URL path detection
        if (trackerPathHints.any { lower.contains(it) }) {
            return BlockedRequestEvent(host = host, category = "Telemetry / Pixel")
        }

        return null
    }

    private fun categorizeHost(host: String): String = when {
        host.contains("analytics") || host.contains("mixpanel") || host.contains("amplitude") ||
            host.contains("segment") || host.contains("hotjar") || host.contains("clarity") ||
            host.contains("scorecard") || host.contains("quantserve") -> "Analytics"
        host.contains("appsflyer") || host.contains("adjust") || host.contains("branch") ||
            host.contains("kochava") || host.contains("singular") -> "Attribution"
        host.contains("facebook") || host.contains("tiktok") || host.contains("twitter") ||
            host.contains("linkedin") || host.contains("snapchat") || host.contains("pinterest") -> "Social Pixel"
        else -> "Ad Network"
    }

    fun sanitizeUrl(rawInput: String): SanitizedUrlResult {
        val trimmed = rawInput.trim()
        if (trimmed.isEmpty()) return SanitizedUrlResult("", emptyList())

        val normalized = when {
            trimmed.startsWith("http://", ignoreCase = true) ||
                trimmed.startsWith("https://", ignoreCase = true) -> trimmed
            else -> "https://$trimmed"
        }

        val uri = runCatching { Uri.parse(normalized) }.getOrNull()
            ?: return SanitizedUrlResult(normalized, emptyList())

        val paramNames = runCatching { uri.queryParameterNames }.getOrNull().orEmpty()
        if (paramNames.isEmpty()) {
            return SanitizedUrlResult(normalized, emptyList())
        }

        val stripped = mutableListOf<String>()
        val builder = uri.buildUpon().clearQuery()
        for (name in paramNames) {
            val lowerName = name.lowercase(Locale.US)
            if (lowerName in trackingQueryParams || lowerName.startsWith("utm_")) {
                stripped.add(name)
            } else {
                for (value in uri.getQueryParameters(name)) {
                    builder.appendQueryParameter(name, value)
                }
            }
        }

        return SanitizedUrlResult(
            cleanUrl = builder.build().toString(),
            strippedParams = stripped
        )
    }

    fun isMediaStreamUrl(url: String): Boolean {
        val lower = url.lowercase(Locale.US)
        if (lower.startsWith("rtsp://")) return true
        val path = runCatching { Uri.parse(lower).path ?: "" }.getOrDefault("")
        return path.endsWith(".mp4") ||
            path.endsWith(".m4v") ||
            path.endsWith(".webm") ||
            path.endsWith(".mkv") ||
            path.endsWith(".mov") ||
            path.endsWith(".avi") ||
            path.endsWith(".flv") ||
            path.endsWith(".ts") ||
            path.endsWith(".m2ts") ||
            path.endsWith(".3gp") ||
            path.endsWith(".m3u8") ||
            path.endsWith(".mpd") ||
            path.endsWith(".ism") ||
            path.endsWith(".isml") ||
            path.endsWith("/manifest") ||
            path.endsWith(".mp3") ||
            path.endsWith(".m4a") ||
            path.endsWith(".aac") ||
            path.endsWith(".ogg") ||
            path.endsWith(".opus") ||
            path.endsWith(".wav") ||
            path.endsWith(".flac") ||
            path.endsWith(".amr") ||
            lower.contains("mime=video") ||
            lower.contains("mime=audio")
    }

    fun inferExtensionAndMime(url: String, contentType: String? = null): Pair<String, String> {
        val ct = contentType?.lowercase(Locale.US)?.substringBefore(';')?.trim()
        when (ct) {
            "video/mp4", "video/m4v" -> return "mp4" to "video/mp4"
            "video/webm" -> return "webm" to "video/webm"
            "video/x-matroska", "video/matroska" -> return "mkv" to "video/x-matroska"
            "video/quicktime" -> return "mov" to "video/quicktime"
            "video/x-msvideo", "video/avi" -> return "avi" to "video/x-msvideo"
            "video/x-flv" -> return "flv" to "video/x-flv"
            "video/mp2t" -> return "ts" to "video/mp2t"
            "video/3gpp" -> return "3gp" to "video/3gpp"
            "application/vnd.apple.mpegurl", "application/x-mpegurl" -> return "m3u8" to "application/vnd.apple.mpegurl"
            "application/dash+xml" -> return "mpd" to "application/dash+xml"
            "application/vnd.ms-sstr+xml" -> return "ism" to "application/vnd.ms-sstr+xml"
            "audio/mpeg", "audio/mp3" -> return "mp3" to "audio/mpeg"
            "audio/mp4", "audio/x-m4a" -> return "m4a" to "audio/mp4"
            "audio/aac", "audio/aacp" -> return "aac" to "audio/aac"
            "audio/ogg", "application/ogg" -> return "ogg" to "audio/ogg"
            "audio/opus" -> return "opus" to "audio/opus"
            "audio/wav", "audio/x-wav", "audio/vnd.wave" -> return "wav" to "audio/wav"
            "audio/flac", "audio/x-flac" -> return "flac" to "audio/flac"
            "audio/amr", "audio/amr-wb" -> return "amr" to "audio/amr"
        }

        val lower = url.lowercase(Locale.US)
        if (lower.startsWith("rtsp://")) return "rtsp" to "application/x-rtsp"
        val path = runCatching { Uri.parse(lower).path ?: "" }.getOrDefault("")
        return when {
            path.endsWith(".m4v") -> "m4v" to "video/mp4"
            path.endsWith(".webm") -> "webm" to "video/webm"
            path.endsWith(".mkv") -> "mkv" to "video/x-matroska"
            path.endsWith(".mov") -> "mov" to "video/quicktime"
            path.endsWith(".avi") -> "avi" to "video/x-msvideo"
            path.endsWith(".flv") -> "flv" to "video/x-flv"
            path.endsWith(".ts") || path.endsWith(".m2ts") -> "ts" to "video/mp2t"
            path.endsWith(".3gp") -> "3gp" to "video/3gpp"
            path.endsWith(".m3u8") -> "m3u8" to "application/vnd.apple.mpegurl"
            path.endsWith(".mpd") -> "mpd" to "application/dash+xml"
            path.endsWith(".ism") || path.endsWith(".isml") || path.endsWith("/manifest") -> "ism" to "application/vnd.ms-sstr+xml"
            path.endsWith(".mp3") -> "mp3" to "audio/mpeg"
            path.endsWith(".m4a") -> "m4a" to "audio/mp4"
            path.endsWith(".aac") -> "aac" to "audio/aac"
            path.endsWith(".ogg") -> "ogg" to "audio/ogg"
            path.endsWith(".opus") -> "opus" to "audio/opus"
            path.endsWith(".wav") -> "wav" to "audio/wav"
            path.endsWith(".flac") -> "flac" to "audio/flac"
            path.endsWith(".amr") -> "amr" to "audio/amr"
            else -> "mp4" to "video/mp4"
        }
    }
}
