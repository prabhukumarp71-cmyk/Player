package com.example.model

import android.net.Uri

data class RecordedClip(
    val id: String,
    val uri: Uri,
    val displayName: String,
    val filePath: String,
    val dateModifiedMs: Long,
    val durationMs: Long,
    val sizeBytes: Long,
    val width: Int,
    val height: Int,
    val bitrate: Long,
    val isFarOnly: Boolean,
    val qualityTag: String
) {
    val formattedDuration: String
        get() {
            val totalSeconds = (durationMs / 1000).coerceAtLeast(0)
            val minutes = totalSeconds / 60
            val seconds = totalSeconds % 60
            return "%02d:%02d".format(minutes, seconds)
        }

    val formattedSize: String
        get() {
            val kb = sizeBytes / 1024.0
            val mb = kb / 1024.0
            val gb = mb / 1024.0
            return when {
                gb >= 1.0 -> "%.2f GB".format(gb)
                mb >= 1.0 -> "%.1f MB".format(mb)
                else -> "%.0f KB".format(kb)
            }
        }

    val resolutionText: String
        get() = if (width > 0 && height > 0) "${width}×${height}" else qualityTag
}
