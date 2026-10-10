package com.example.camera

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.camera2.CaptureRequest
import android.os.SystemClock
import android.util.Log
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.core.Camera
import androidx.camera.core.CameraControl
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.PendingRecording
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.example.data.ClipStorageRepository
import com.example.model.QualitySupportInfo
import com.example.model.VideoQuality
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.Executor

class CameraRecorderManager(
    private val context: Context,
    private val storageRepository: ClipStorageRepository
) {
    private val executor: Executor = ContextCompat.getMainExecutor(context)
    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var preview: Preview? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var activeRecording: Recording? = null

    private var currentLensFacing = CameraSelector.LENS_FACING_BACK
    private var currentQuality = VideoQuality.FHD_1080P
    private var isFarOnlyModeEnabled = false
    private var isTorchEnabled = false
    private var isAudioEnabled = true

    // State flows
    private val _recordingState = MutableStateFlow<RecordingState>(RecordingState.Idle)
    val recordingState: StateFlow<RecordingState> = _recordingState.asStateFlow()

    private val _recordingDurationSeconds = MutableStateFlow(0L)
    val recordingDurationSeconds: StateFlow<Long> = _recordingDurationSeconds.asStateFlow()

    private val _recordingBytes = MutableStateFlow(0L)
    val recordingBytes: StateFlow<Long> = _recordingBytes.asStateFlow()

    private val _supportedQualities = MutableStateFlow<List<QualitySupportInfo>>(emptyList())
    val supportedQualities: StateFlow<List<QualitySupportInfo>> = _supportedQualities.asStateFlow()

    private val _zoomRatio = MutableStateFlow(1.0f)
    val zoomRatio: StateFlow<Float> = _zoomRatio.asStateFlow()

    private val _maxZoomRatio = MutableStateFlow(8.0f)
    val maxZoomRatio: StateFlow<Float> = _maxZoomRatio.asStateFlow()

    private val _minZoomRatio = MutableStateFlow(1.0f)
    val minZoomRatio: StateFlow<Float> = _minZoomRatio.asStateFlow()

    private var recordingStartTime = 0L

    sealed class RecordingState {
        data object Idle : RecordingState()
        data class Starting(val quality: VideoQuality) : RecordingState()
        data class Recording(val quality: VideoQuality, val isFarOnly: Boolean) : RecordingState()
        data class Paused(val quality: VideoQuality) : RecordingState()
        data class Finalizing(val lastDuration: Long) : RecordingState()
        data class Error(val message: String) : RecordingState()
    }

    fun initializeCamera(
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView,
        quality: VideoQuality,
        farOnly: Boolean,
        onReady: () -> Unit = {}
    ) {
        currentQuality = quality
        isFarOnlyModeEnabled = farOnly

        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            try {
                cameraProvider = cameraProviderFuture.get()
                bindCameraUseCases(lifecycleOwner, previewView, quality, farOnly)
                onReady()
            } catch (e: Exception) {
                Log.e("CameraRecorderManager", "Failed to bind camera", e)
                _recordingState.value = RecordingState.Error("Camera initialization failed: ${e.message}")
            }
        }, executor)
    }

    private fun bindCameraUseCases(
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView,
        quality: VideoQuality,
        farOnly: Boolean
    ) {
        val provider = cameraProvider ?: return
        provider.unbindAll()

        val cameraSelector = CameraSelector.Builder()
            .requireLensFacing(currentLensFacing)
            .build()

        // Setup Preview
        preview = Preview.Builder().build().also {
            it.surfaceProvider = previewView.surfaceProvider
        }

        // Query real supported qualities for selected lens
        checkSupportedQualities(provider, cameraSelector)

        // Build real Recorder with QualitySelector
        val qualitySelector = QualitySelector.from(
            quality.cameraXQuality,
            FallbackStrategy.lowerQualityOrHigherThan(quality.cameraXQuality)
        )

        val recorder = Recorder.Builder()
            .setQualitySelector(qualitySelector)
            .setExecutor(executor)
            .build()

        videoCapture = VideoCapture.withOutput(recorder)

        try {
            camera = provider.bindToLifecycle(
                lifecycleOwner,
                cameraSelector,
                preview,
                videoCapture
            )

            // Setup zoom observers
            camera?.cameraInfo?.zoomState?.observe(lifecycleOwner) { state ->
                _zoomRatio.value = state.zoomRatio
                _maxZoomRatio.value = state.maxZoomRatio
                _minZoomRatio.value = state.minZoomRatio
            }

            // Apply Far Only Mode optical setting if active
            applyFarOnlyModeInternal(farOnly)

        } catch (e: Exception) {
            Log.e("CameraRecorderManager", "Binding use cases failed", e)
            _recordingState.value = RecordingState.Error("Camera binding failed: ${e.message}")
        }
    }

    private fun checkSupportedQualities(provider: ProcessCameraProvider, selector: CameraSelector) {
        try {
            val cameraInfo = provider.availableCameraInfos.firstOrNull {
                selector.filter(listOf(it)).isNotEmpty()
            } ?: return

            val capabilities = Recorder.getVideoCapabilities(cameraInfo)

            val supportedList = capabilities.getSupportedQualities(androidx.camera.core.DynamicRange.SDR)
            val list = mutableListOf<QualitySupportInfo>()
            for (q in VideoQuality.entries) {
                val supported = supportedList.contains(q.cameraXQuality)
                val detail = if (supported) {
                    "${q.resolutionLabel} • Supported natively"
                } else {
                    "${q.resolutionLabel} • Limited by sensor"
                }
                list.add(QualitySupportInfo(q, supported, detail))
            }
            _supportedQualities.value = list
        } catch (e: Exception) {
            Log.w("CameraRecorderManager", "Could not check video capabilities", e)
        }
    }

    @SuppressLint("UnsafeOptInUsageError")
    fun setFarOnlyMode(enabled: Boolean) {
        isFarOnlyModeEnabled = enabled
        applyFarOnlyModeInternal(enabled)
    }

    @SuppressLint("UnsafeOptInUsageError")
    private fun applyFarOnlyModeInternal(enabled: Boolean) {
        val cam = camera ?: return
        try {
            val camera2Control = Camera2CameraControl.from(cam.cameraControl)
            if (enabled) {
                // Far Only Mode:
                // 1. Lock optical focus to infinity (0.0f diopters in Camera2)
                // 2. Disable AF hunting (CONTROL_AF_MODE_OFF)
                val options = CaptureRequestOptions.Builder()
                    .setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                    .setCaptureRequestOption(CaptureRequest.LENS_FOCUS_DISTANCE, 0.0f)
                    .build()
                camera2Control.setCaptureRequestOptions(options)

                // When Far Only Mode is activated, set zoom to a telephoto / distance perspective (e.g. 2.0x min)
                val currentZoom = _zoomRatio.value
                if (currentZoom < 2.0f && _maxZoomRatio.value >= 2.0f) {
                    cam.cameraControl.setZoomRatio(2.0f)
                }
            } else {
                // Normal Mode:
                // Reset to continuous video autofocus
                val options = CaptureRequestOptions.Builder()
                    .setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                    .build()
                camera2Control.setCaptureRequestOptions(options)
            }
        } catch (e: Exception) {
            Log.w("CameraRecorderManager", "Failed to apply Far Only optical settings", e)
        }
    }

    fun switchCamera(
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView,
        quality: VideoQuality
    ) {
        if (_recordingState.value is RecordingState.Recording) return
        currentLensFacing = if (currentLensFacing == CameraSelector.LENS_FACING_BACK) {
            CameraSelector.LENS_FACING_FRONT
        } else {
            CameraSelector.LENS_FACING_BACK
        }
        bindCameraUseCases(lifecycleOwner, previewView, quality, isFarOnlyModeEnabled)
    }

    fun changeQuality(
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView,
        quality: VideoQuality
    ) {
        if (_recordingState.value is RecordingState.Recording) return
        currentQuality = quality
        bindCameraUseCases(lifecycleOwner, previewView, quality, isFarOnlyModeEnabled)
    }

    fun toggleTorch(): Boolean {
        val cam = camera ?: return false
        if (cam.cameraInfo.hasFlashUnit()) {
            isTorchEnabled = !isTorchEnabled
            cam.cameraControl.enableTorch(isTorchEnabled)
            return isTorchEnabled
        }
        return false
    }

    fun setZoom(ratio: Float) {
        camera?.cameraControl?.setZoomRatio(ratio.coerceIn(_minZoomRatio.value, _maxZoomRatio.value))
    }

    fun setAudioEnabled(enabled: Boolean) {
        isAudioEnabled = enabled
    }

    @SuppressLint("MissingPermission")
    fun startRecording(
        onClipSaved: (uri: String) -> Unit,
        onError: (String) -> Unit
    ) {
        val capture = videoCapture ?: run {
            onError("Camera recorder not initialized")
            return
        }

        if (activeRecording != null) {
            onError("Recording already in progress")
            return
        }

        _recordingState.value = RecordingState.Starting(currentQuality)
        _recordingDurationSeconds.value = 0L
        _recordingBytes.value = 0L
        recordingStartTime = SystemClock.elapsedRealtime()

        val outputOptions = storageRepository.createMediaStoreOutputOptions(currentQuality, isFarOnlyModeEnabled)

        var pending: PendingRecording = capture.output.prepareRecording(context, outputOptions)

        if (isAudioEnabled) {
            try {
                pending = pending.withAudioEnabled()
            } catch (e: SecurityException) {
                Log.w("CameraRecorderManager", "Audio permission not granted", e)
            }
        }

        activeRecording = pending.start(executor) { recordEvent ->
            when (recordEvent) {
                is VideoRecordEvent.Start -> {
                    _recordingState.value = RecordingState.Recording(currentQuality, isFarOnlyModeEnabled)
                    recordingStartTime = SystemClock.elapsedRealtime()
                }

                is VideoRecordEvent.Status -> {
                    val stats = recordEvent.recordingStats
                    _recordingDurationSeconds.value = stats.recordedDurationNanos / 1_000_000_000L
                    _recordingBytes.value = stats.numBytesRecorded
                }

                is VideoRecordEvent.Pause -> {
                    _recordingState.value = RecordingState.Paused(currentQuality)
                }

                is VideoRecordEvent.Resume -> {
                    _recordingState.value = RecordingState.Recording(currentQuality, isFarOnlyModeEnabled)
                }

                is VideoRecordEvent.Finalize -> {
                    val durationSec = _recordingDurationSeconds.value
                    _recordingState.value = RecordingState.Finalizing(durationSec)
                    activeRecording = null

                    if (recordEvent.hasError()) {
                        Log.e("CameraRecorderManager", "Recording error: ${recordEvent.error}", recordEvent.cause)
                        _recordingState.value = RecordingState.Error("Recording error: ${recordEvent.error}")
                        onError("Recording failed (${recordEvent.error})")
                    } else {
                        val savedUri = recordEvent.outputResults.outputUri.toString()
                        _recordingState.value = RecordingState.Idle
                        onClipSaved(savedUri)
                    }
                }
            }
        }
    }

    fun pauseRecording() {
        activeRecording?.pause()
    }

    fun resumeRecording() {
        activeRecording?.resume()
    }

    fun stopRecording() {
        activeRecording?.stop()
        activeRecording = null
    }

    fun release() {
        activeRecording?.stop()
        activeRecording = null
        cameraProvider?.unbindAll()
    }
}
