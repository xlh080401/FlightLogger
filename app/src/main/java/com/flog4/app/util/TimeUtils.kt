package com.flog4.app.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object TimeUtils {
    fun formatUtc(timestampMs: Long): String {
        if (timestampMs <= 0L) return "—"
        val sdf = SimpleDateFormat("HH:mm:ss 'UTC'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        return sdf.format(Date(timestampMs))
    }

    fun formatLocalTime(timestampMs: Long): String {
        if (timestampMs <= 0L) return "—"
        val sdf = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        return sdf.format(Date(timestampMs))
    }

    fun formatDate(timestampMs: Long): String {
        val ts = if (timestampMs > 0L) timestampMs else System.currentTimeMillis()
        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        return sdf.format(Date(ts))
    }

    fun formatDuration(durationMs: Long): String {
        if (durationMs <= 0L) return "0h 00m"
        val totalMinutes = (durationMs / 60_000L).coerceAtLeast(1L)
        val hours = totalMinutes / 60
        val minutes = totalMinutes % 60
        return String.format(Locale.US, "%dh %02dm", hours, minutes)
    }

    fun parseDurationDisplayToMs(display: String, fallbackMs: Long): Long {
        val text = display.trim().lowercase()
        val hMatch = Regex("(\\d+)\\s*(?:h|小时|时)").find(text)?.groupValues?.get(1)?.toLongOrNull()
        val mMatch = Regex("(\\d+)\\s*(?:m|分钟|分)").find(text)?.groupValues?.get(1)?.toLongOrNull()
        if (hMatch != null || mMatch != null) {
            return ((hMatch ?: 0L) * 60L + (mMatch ?: 0L)) * 60_000L
        }
        val colonParts = text.split(":")
        if (colonParts.size >= 2) {
            val h = colonParts[0].trim().toLongOrNull()
            val m = colonParts[1].trim().toLongOrNull()
            if (h != null && m != null) {
                return (h * 60L + m) * 60_000L
            }
        }
        val plainMinutes = text.toLongOrNull()
        if (plainMinutes != null) {
            return plainMinutes * 60_000L
        }
        return fallbackMs
    }
}
