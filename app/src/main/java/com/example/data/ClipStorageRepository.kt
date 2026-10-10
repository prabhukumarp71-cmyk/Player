package com.example.data

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.MediaStore
import androidx.camera.video.MediaStoreOutputOptions
import com.example.model.RecordedClip
import com.example.model.VideoQuality
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ClipStorageRepository(private val context: Context) {

    fun createMediaStoreOutputOptions(quality: VideoQuality, isFarOnly: Boolean): MediaStoreOutputOptions {
        val dateFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val farPrefix = if (isFarOnly) "FAR_" else ""
        val qualityTag = when (quality) {
            VideoQuality.UHD_4K -> "4K"
            VideoQuality.FHD_1080P -> "1080p"
            VideoQuality.HD_720P -> "720p"
        }
        val filename = "PLAYER_${farPrefix}${qualityTag}_${dateFormat}.mp4"

        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/Player")
            }
        }

        return MediaStoreOutputOptions.Builder(
            context.contentResolver,
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        ).setContentValues(contentValues).build()
    }

    suspend fun getSavedClips(): List<RecordedClip> = withContext(Dispatchers.IO) {
        val clips = mutableListOf<RecordedClip>()

        // 1. Query MediaStore
        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.DATA,
            MediaStore.Video.Media.DATE_MODIFIED,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.WIDTH,
            MediaStore.Video.Media.HEIGHT
        )

        val selection = "${MediaStore.Video.Media.DISPLAY_NAME} LIKE ?"
        val selectionArgs = arrayOf("PLAYER_%")
        val sortOrder = "${MediaStore.Video.Media.DATE_MODIFIED} DESC"

        try {
            context.contentResolver.query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                sortOrder
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
                val dataCol = cursor.getColumnIndex(MediaStore.Video.Media.DATA)
                val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_MODIFIED)
                val durationCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
                val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
                val widthCol = cursor.getColumnIndex(MediaStore.Video.Media.WIDTH)
                val heightCol = cursor.getColumnIndex(MediaStore.Video.Media.HEIGHT)

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    val contentUri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id)
                    val name = cursor.getString(nameCol) ?: "PLAYER_clip.mp4"
                    val path = if (dataCol != -1) cursor.getString(dataCol) ?: "" else ""
                    val dateModified = cursor.getLong(dateCol) * 1000
                    var duration = cursor.getLong(durationCol)
                    val size = cursor.getLong(sizeCol)
                    var width = if (widthCol != -1) cursor.getInt(widthCol) else 0
                    var height = if (heightCol != -1) cursor.getInt(heightCol) else 0
                    var bitrate = 0L

                    // If dimensions or duration are missing, inspect with MediaMetadataRetriever
                    if (width == 0 || height == 0 || duration <= 0) {
                        val meta = extractMetadata(contentUri)
                        if (meta != null) {
                            if (width == 0) width = meta.width
                            if (height == 0) height = meta.height
                            if (duration <= 0) duration = meta.durationMs
                            bitrate = meta.bitrate
                        }
                    }

                    val isFarOnly = name.contains("FAR", ignoreCase = true)
                    val qualityTag = when {
                        name.contains("4K", ignoreCase = true) || width >= 3840 -> "4K UHD"
                        name.contains("1080p", ignoreCase = true) || width >= 1920 -> "1080p FHD"
                        name.contains("720p", ignoreCase = true) || width >= 1280 -> "720p HD"
                        width > 0 -> "${width}×${height}"
                        else -> "HD"
                    }

                    clips.add(
                        RecordedClip(
                            id = id.toString(),
                            uri = contentUri,
                            displayName = name,
                            filePath = path.ifEmpty { "Movies/Player/$name" },
                            dateModifiedMs = dateModified,
                            durationMs = duration,
                            sizeBytes = size,
                            width = width,
                            height = height,
                            bitrate = bitrate,
                            isFarOnly = isFarOnly,
                            qualityTag = qualityTag
                        )
                    )
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // 2. Also check local app Movies/Player directory if any direct files were saved
        val localDir = File(context.getExternalFilesDir(Environment.DIRECTORY_MOVIES), "Player")
        if (localDir.exists()) {
            val localFiles = localDir.listFiles { file ->
                file.isFile && file.name.startsWith("PLAYER_") && file.name.endsWith(".mp4")
            } ?: emptyArray()

            for (file in localFiles) {
                if (clips.none { it.displayName == file.name }) {
                    val uri = Uri.fromFile(file)
                    val meta = extractMetadata(uri)
                    val width = meta?.width ?: 0
                    val height = meta?.height ?: 0
                    val duration = meta?.durationMs ?: 0L
                    val bitrate = meta?.bitrate ?: 0L

                    val isFarOnly = file.name.contains("FAR", ignoreCase = true)
                    val qualityTag = when {
                        file.name.contains("4K", ignoreCase = true) || width >= 3840 -> "4K UHD"
                        file.name.contains("1080p", ignoreCase = true) || width >= 1920 -> "1080p FHD"
                        file.name.contains("720p", ignoreCase = true) || width >= 1280 -> "720p HD"
                        width > 0 -> "${width}×${height}"
                        else -> "HD"
                    }

                    clips.add(
                        RecordedClip(
                            id = file.absolutePath,
                            uri = uri,
                            displayName = file.name,
                            filePath = file.absolutePath,
                            dateModifiedMs = file.lastModified(),
                            durationMs = duration,
                            sizeBytes = file.length(),
                            width = width,
                            height = height,
                            bitrate = bitrate,
                            isFarOnly = isFarOnly,
                            qualityTag = qualityTag
                        )
                    )
                }
            }
        }

        clips.sortedByDescending { it.dateModifiedMs }
    }

    data class ExtractedMeta(
        val width: Int,
        val height: Int,
        val durationMs: Long,
        val bitrate: Long
    )

    fun extractMetadata(uri: Uri): ExtractedMeta? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            val widthStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
            val heightStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
            val durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            val bitrateStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)

            val width = widthStr?.toIntOrNull() ?: 0
            val height = heightStr?.toIntOrNull() ?: 0
            val duration = durationStr?.toLongOrNull() ?: 0L
            val bitrate = bitrateStr?.toLongOrNull() ?: 0L

            ExtractedMeta(width, height, duration, bitrate)
        } catch (e: Exception) {
            null
        } finally {
            try {
                retriever.release()
            } catch (_: Exception) {}
        }
    }

    suspend fun deleteClip(clip: RecordedClip): Boolean = withContext(Dispatchers.IO) {
        try {
            if (clip.uri.scheme == "content") {
                val deleted = context.contentResolver.delete(clip.uri, null, null)
                deleted > 0
            } else {
                val file = File(clip.filePath)
                file.exists() && file.delete()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    fun getAvailableStorageBytes(): Long {
        return try {
            val path = Environment.getDataDirectory()
            val stat = StatFs(path.path)
            stat.availableBlocksLong * stat.blockSizeLong
        } catch (_: Exception) {
            0L
        }
    }

    fun formatBytes(bytes: Long): String {
        val gb = bytes / (1024.0 * 1024.0 * 1024.0)
        return if (gb >= 1.0) "%.1f GB free".format(gb) else "%.0f MB free".format(bytes / (1024.0 * 1024.0))
    }
}
