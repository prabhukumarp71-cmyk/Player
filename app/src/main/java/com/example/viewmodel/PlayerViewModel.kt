package com.example.viewmodel

import android.app.Application
import android.content.Context
import androidx.camera.view.PreviewView
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.camera.CameraRecorderManager
import com.example.data.ClipStorageRepository
import com.example.model.QualitySupportInfo
import com.example.model.RecordedClip
import com.example.model.VideoQuality
import com.example.service.PlayerRecordingService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed class ScreenDestination {
    data object Recorder : ScreenDestination()
    data object Gallery : ScreenDestination()
    data class Player(val clip: RecordedClip) : ScreenDestination()
}

class PlayerViewModel(application: Application) : AndroidViewModel(application) {

    val storageRepository = ClipStorageRepository(application)
    val cameraManager = CameraRecorderManager(application, storageRepository)

    // Current navigation tab
    private val _currentScreen = MutableStateFlow<ScreenDestination>(ScreenDestination.Recorder)
    val currentScreen: StateFlow<ScreenDestination> = _currentScreen.asStateFlow()

    // Quality selection
    private val _selectedQuality = MutableStateFlow(VideoQuality.FHD_1080P)
    val selectedQuality: StateFlow<VideoQuality> = _selectedQuality.asStateFlow()

    // Far-Only Mode
    private val _isFarOnlyMode = MutableStateFlow(false)
    val isFarOnlyMode: StateFlow<Boolean> = _isFarOnlyMode.asStateFlow()

    // Torch state
    private val _isTorchOn = MutableStateFlow(false)
    val isTorchOn: StateFlow<Boolean> = _isTorchOn.asStateFlow()

    // Audio recording state
    private val _isAudioEnabled = MutableStateFlow(true)
    val isAudioEnabled: StateFlow<Boolean> = _isAudioEnabled.asStateFlow()

    // Saved clips list
    private val _savedClips = MutableStateFlow<List<RecordedClip>>(emptyList())
    val savedClips: StateFlow<List<RecordedClip>> = _savedClips.asStateFlow()

    // Storage capacity
    private val _freeStorageText = MutableStateFlow("Checking storage...")
    val freeStorageText: StateFlow<String> = _freeStorageText.asStateFlow()

    // Notifications / banner messages
    private val _statusMessage = MutableStateFlow<String?>(null)
    val statusMessage: StateFlow<String?> = _statusMessage.asStateFlow()

    // Recently saved clip preview
    private val _lastSavedClip = MutableStateFlow<RecordedClip?>(null)
    val lastSavedClip: StateFlow<RecordedClip?> = _lastSavedClip.asStateFlow()

    // Camera stats & controls
    val recordingState = cameraManager.recordingState
    val recordingDurationSeconds = cameraManager.recordingDurationSeconds
    val recordingBytes = cameraManager.recordingBytes
    val supportedQualities = cameraManager.supportedQualities
    val zoomRatio = cameraManager.zoomRatio
    val maxZoomRatio = cameraManager.maxZoomRatio
    val minZoomRatio = cameraManager.minZoomRatio

    // Far mode & exposure states
    val farFocusDistance = cameraManager.farFocusDistance
    val exposureIndex = cameraManager.exposureIndex
    val minExposureIndex = cameraManager.minExposureIndex
    val maxExposureIndex = cameraManager.maxExposureIndex
    val exposureValueText = cameraManager.exposureValueText
    val isExposureSupported = cameraManager.isExposureSupported

    init {
        refreshStorageInfo()
        loadSavedClips()
    }

    fun navigateTo(screen: ScreenDestination) {
        _currentScreen.value = screen
    }

    fun selectQuality(quality: VideoQuality, previewView: PreviewView) {
        _selectedQuality.value = quality
        cameraManager.changeQuality(previewView, quality)
    }

    fun toggleFarOnlyMode(enabled: Boolean) {
        _isFarOnlyMode.value = enabled
        cameraManager.setFarOnlyMode(enabled)
        val msg = if (enabled) {
            "Far Only Mode Engaged: Hyperfocal focus active. Distant objects sharp; foreground blurred."
        } else {
            "Far Only Mode Disengaged: Standard continuous auto-focus restored."
        }
        _statusMessage.value = msg
    }

    fun setFarFocusDistance(distance: Float) {
        cameraManager.setFarFocusDistance(distance)
    }

    fun focusOnFarSubject(x: Float, y: Float, width: Float, height: Float) {
        cameraManager.focusOnFarSubject(x, y, width, height)
        _statusMessage.value = "Locked focus to distant target"
    }

    fun setExposureIndex(index: Int) {
        cameraManager.setExposureIndex(index)
    }

    fun stepExposure(delta: Int) {
        cameraManager.stepExposure(delta)
    }

    fun resetExposure() {
        cameraManager.resetExposure()
    }

    fun toggleTorch() {
        val newState = cameraManager.toggleTorch()
        _isTorchOn.value = newState
    }

    fun toggleAudio() {
        val newState = !_isAudioEnabled.value
        _isAudioEnabled.value = newState
        cameraManager.setAudioEnabled(newState)
    }

    fun setZoom(ratio: Float) {
        cameraManager.setZoom(ratio)
    }

    fun switchCamera(previewView: PreviewView) {
        cameraManager.switchCamera(previewView, _selectedQuality.value)
    }

    fun startRecording(context: Context) {
        val quality = _selectedQuality.value
        val farOnly = _isFarOnlyMode.value

        // Start Foreground Service with notification
        PlayerRecordingService.start(context, quality.label, farOnly)

        cameraManager.startRecording(
            onClipSaved = { uriString ->
                PlayerRecordingService.stop(context)
                refreshStorageInfo()
                loadSavedClips()
                _statusMessage.value = "Clip saved directly to local storage (Movies/Player)!"
            },
            onError = { error ->
                PlayerRecordingService.stop(context)
                _statusMessage.value = "Recording error: $error"
            }
        )
    }

    fun stopRecording(context: Context) {
        cameraManager.stopRecording()
        PlayerRecordingService.stop(context)
    }

    fun pauseRecording() {
        cameraManager.pauseRecording()
    }

    fun resumeRecording() {
        cameraManager.resumeRecording()
    }

    fun loadSavedClips() {
        viewModelScope.launch {
            val clips = storageRepository.getSavedClips()
            _savedClips.value = clips
            if (clips.isNotEmpty()) {
                _lastSavedClip.value = clips.first()
            }
        }
    }

    fun deleteClip(clip: RecordedClip) {
        viewModelScope.launch {
            val success = storageRepository.deleteClip(clip)
            if (success) {
                _statusMessage.value = "Deleted clip: ${clip.displayName}"
                loadSavedClips()
                if (_currentScreen.value is ScreenDestination.Player) {
                    _currentScreen.value = ScreenDestination.Gallery
                }
            } else {
                _statusMessage.value = "Failed to delete clip"
            }
        }
    }

    fun refreshStorageInfo() {
        val bytes = storageRepository.getAvailableStorageBytes()
        _freeStorageText.value = storageRepository.formatBytes(bytes)
    }

    fun dismissStatusMessage() {
        _statusMessage.value = null
    }

    override fun onCleared() {
        super.onCleared()
        cameraManager.release()
    }
}
