package com.example

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.example.data.ClipStorageRepository
import com.example.model.RecordedClip
import com.example.model.VideoQuality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExampleRobolectricTest {

    @Test
    fun verifyAppNameIsPlayer() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("Player", appName)
    }

    @Test
    fun verifyVideoQualityResolutions() {
        assertEquals(3840, VideoQuality.UHD_4K.width)
        assertEquals(2160, VideoQuality.UHD_4K.height)
        assertEquals("3840×2160", VideoQuality.UHD_4K.resolutionLabel)

        assertEquals(1920, VideoQuality.FHD_1080P.width)
        assertEquals(1080, VideoQuality.FHD_1080P.height)
        assertEquals("1920×1080", VideoQuality.FHD_1080P.resolutionLabel)

        assertEquals(1280, VideoQuality.HD_720P.width)
        assertEquals(720, VideoQuality.HD_720P.height)
        assertEquals("1280×720", VideoQuality.HD_720P.resolutionLabel)
    }

    @Test
    fun verifyRecordedClipFormatting() {
        val clip = RecordedClip(
            id = "1",
            uri = Uri.parse("content://media/external/video/media/1"),
            displayName = "PLAYER_FAR_4K_test.mp4",
            filePath = "Movies/Player/PLAYER_FAR_4K_test.mp4",
            dateModifiedMs = System.currentTimeMillis(),
            durationMs = 75000L, // 1 min 15 sec
            sizeBytes = 104857600L, // 100 MB
            width = 3840,
            height = 2160,
            bitrate = 20000000L,
            isFarOnly = true,
            qualityTag = "4K UHD"
        )

        assertEquals("01:15", clip.formattedDuration)
        assertTrue(clip.formattedSize.contains("100"))
        assertEquals("3840×2160", clip.resolutionText)
        assertTrue(clip.isFarOnly)
    }

    @Test
    fun verifyStorageRepositoryOutputOptions() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repository = ClipStorageRepository(context)

        val optionsFar = repository.createMediaStoreOutputOptions(VideoQuality.UHD_4K, true)
        val optionsNormal = repository.createMediaStoreOutputOptions(VideoQuality.FHD_1080P, false)

        val farDisplayName = optionsFar.contentValues.getAsString(android.provider.MediaStore.MediaColumns.DISPLAY_NAME)
        val normalDisplayName = optionsNormal.contentValues.getAsString(android.provider.MediaStore.MediaColumns.DISPLAY_NAME)

        assertTrue("Should contain FAR prefix", farDisplayName.contains("FAR"))
        assertTrue("Should contain 4K tag", farDisplayName.contains("4K"))
        assertTrue("Should start with PLAYER_", normalDisplayName.startsWith("PLAYER_"))
        assertTrue("Should contain 1080p tag", normalDisplayName.contains("1080p"))
    }
}
