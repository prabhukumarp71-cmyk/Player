package com.example.model

import androidx.camera.video.Quality

enum class VideoQuality(
    val label: String,
    val resolutionLabel: String,
    val width: Int,
    val height: Int,
    val cameraXQuality: Quality,
    val description: String
) {
    UHD_4K(
        label = "4K UHD",
        resolutionLabel = "3840×2160",
        width = 3840,
        height = 2160,
        cameraXQuality = Quality.UHD,
        description = "Ultra High Definition • Maximum detail"
    ),
    FHD_1080P(
        label = "1080p FHD",
        resolutionLabel = "1920×1080",
        width = 1920,
        height = 1080,
        cameraXQuality = Quality.FHD,
        description = "Full High Definition • Balanced storage"
    ),
    HD_720P(
        label = "720p HD",
        resolutionLabel = "1280×720",
        width = 1280,
        height = 720,
        cameraXQuality = Quality.HD,
        description = "High Definition • Compact file size"
    );

    companion object {
        fun fromQuality(quality: Quality): VideoQuality {
            return when (quality) {
                Quality.UHD -> UHD_4K
                Quality.FHD -> FHD_1080P
                Quality.HD -> HD_720P
                else -> FHD_1080P
            }
        }
    }
}

data class QualitySupportInfo(
    val quality: VideoQuality,
    val isSupported: Boolean,
    val details: String
)
