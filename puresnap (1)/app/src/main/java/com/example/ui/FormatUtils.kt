package com.example.ui

import java.util.Locale

data class OpenMediaPortal(
    val id: String,
    val name: String,
    val subtitle: String,
    val url: String,
    val badge: String
)

object OpenPortals {
    val list = listOf(
        OpenMediaPortal(
            id = "archive",
            name = "Internet Archive",
            subtitle = "Public domain films & audio",
            url = "https://archive.org/details/movies",
            badge = "VIDEO & AUDIO"
        ),
        OpenMediaPortal(
            id = "wikimedia",
            name = "Wikimedia Commons",
            subtitle = "Free educational media",
            url = "https://commons.wikimedia.org/wiki/Category:Videos",
            badge = "OPEN MP4 / WEBM"
        ),
        OpenMediaPortal(
            id = "pexels",
            name = "Pexels Videos",
            subtitle = "Free stock HD clips",
            url = "https://www.pexels.com/videos/",
            badge = "HD CLIPS"
        ),
        OpenMediaPortal(
            id = "librivox",
            name = "LibriVox Audio",
            subtitle = "Free public audiobooks",
            url = "https://librivox.org/search",
            badge = "MP3 / M4B"
        )
    )
}

object FormatUtils {
    fun formatBytes(bytes: Long): String {
        if (bytes < 0L) return "Unknown size"
        if (bytes < 1024L) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024.0) return String.format(Locale.US, "%.1f KB", kb)
        val mb = kb / 1024.0
        if (mb < 1024.0) return String.format(Locale.US, "%.1f MB", mb)
        val gb = mb / 1024.0
        return String.format(Locale.US, "%.2f GB", gb)
    }

    fun formatSpeed(bytesPerSec: Long): String {
        if (bytesPerSec <= 0L) return ""
        return "${formatBytes(bytesPerSec)}/s"
    }

    fun formatDuration(ms: Long): String {
        if (ms <= 0L) return "00:00"
        val totalSeconds = ms / 1000L
        val hours = totalSeconds / 3600L
        val minutes = (totalSeconds % 3600L) / 60L
        val seconds = totalSeconds % 60L
        return if (hours > 0L) {
            String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.US, "%02d:%02d", minutes, seconds)
        }
    }
}
