package com.example.camera

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.os.SystemClock
import android.util.Log
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceOrientedMeteringPointFactory
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.example.data.ClipStorageRepository
import com.example.model.QualitySupportInfo
import com.example.model.VideoQuality
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

/**
 * Dedicated LifecycleOwner that remains in RESUMED state while foreground recording is active.
 * This guarantees that when the user switches to other apps or minimizes to background,
 * CameraX VideoCapture continues recording without interruption.
 */
class ForegroundRecordingLifecycleOwner : LifecycleOwner {
    private val lifecycleRegistry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = lifecycleRegistry

    init {
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
        lifecycleRegistry.currentState = Lifecycle.State.STARTED
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
    }

    fun resume() {
        if (lifecycleRegistry.currentState != Lifecycle.State.RESUMED) {
            lifecycleRegistry.currentState = Lifecycle.State.STARTED
            lifecycleRegistry.currentState = Lifecycle.State.RESUMED
        }
    }

    fun stop() {
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
    }
}

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
    private var currentPreviewView: PreviewView? = null

    // Dedicated lifecycle owner that keeps recording alive across background transitions
    private val foregroundLifecycleOwner = ForegroundRecordingLifecycleOwner()

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

    // Zoom states
    private val _zoomRatio = MutableStateFlow(1.0f)
    val zoomRatio: StateFlow<Float> = _zoomRatio.asStateFlow()

    private val _maxZoomRatio = MutableStateFlow(8.0f)
    val maxZoomRatio: StateFlow<Float> = _maxZoomRatio.asStateFlow()

    private val _minZoomRatio = MutableStateFlow(1.0f)
    val minZoomRatio: StateFlow<Float> = _minZoomRatio.asStateFlow()

    // Far Mode focus distance (in diopters: ~0.05f = hyperfocal sharp range)
    private val _farFocusDistance = MutableStateFlow(0.05f)
    val farFocusDistance: StateFlow<Float> = _farFocusDistance.asStateFlow()

    private val _hyperfocalDistance = MutableStateFlow<Float?>(null)
    val hyperfocalDistance: StateFlow<Float?> = _hyperfocalDistance.asStateFlow()

    // Exposure compensation states
    private val _exposureIndex = MutableStateFlow(0)
    val exposureIndex: StateFlow<Int> = _exposureIndex.asStateFlow()

    private val _minExposureIndex = MutableStateFlow(-4)
    val minExposureIndex: StateFlow<Int> = _minExposureIndex.asStateFlow()

    private val _maxExposureIndex = MutableStateFlow(4)
    val maxExposureIndex: StateFlow<Int> = _maxExposureIndex.asStateFlow()

    private val _exposureStep = MutableStateFlow(0.33f)
    val exposureStep: StateFlow<Float> = _exposureStep.asStateFlow()

    private val _exposureValueText = MutableStateFlow("0.0 EV")
    val exposureValueText: StateFlow<String> = _exposureValueText.asStateFlow()

    private val _isExposureSupported = MutableStateFlow(false)
    val isExposureSupported: StateFlow<Boolean> = _isExposureSupported.asStateFlow()

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
        previewView: PreviewView?,
        quality: VideoQuality,
        farOnly: Boolean,
        onReady: () -> Unit = {}
    ) {
        currentPreviewView = previewView
        currentQuality = quality
        isFarOnlyModeEnabled = farOnly
        foregroundLifecycleOwner.resume()

        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            try {
                cameraProvider = cameraProviderFuture.get()
                bindCameraUseCases(withPreview = previewView != null)
                onReady()
            } catch (e: Exception) {
                Log.e("CameraRecorderManager", "Failed to bind camera", e)
                _recordingState.value = RecordingState.Error("Camera initialization failed: ${e.message}")
            }
        }, executor)
    }

    private fun bindCameraUseCases(withPreview: Boolean) {
        val provider = cameraProvider ?: return

        // If recording is actively in progress, do not unbind videoCapture!
        if (activeRecording == null) {
            provider.unbindAll()
        } else {
            preview?.let { provider.unbind(it) }
        }

        val cameraSelector = CameraSelector.Builder()
            .requireLensFacing(currentLensFacing)
            .build()

        // Setup Preview if requested and surface is available
        if (withPreview && currentPreviewView != null) {
            preview = Preview.Builder().build().also {
                it.surfaceProvider = currentPreviewView!!.surfaceProvider
            }
        } else {
            preview = null
        }

        // Query real supported qualities for selected lens
        checkSupportedQualities(provider, cameraSelector)

        // Build VideoCapture only if not already built or not recording
        if (videoCapture == null || activeRecording == null) {
            val qualitySelector = QualitySelector.from(
                currentQuality.cameraXQuality,
                FallbackStrategy.lowerQualityOrHigherThan(currentQuality.cameraXQuality)
            )

            val recorder = Recorder.Builder()
                .setQualitySelector(qualitySelector)
                .setExecutor(executor)
                .build()

            videoCapture = VideoCapture.withOutput(recorder)
        }

        try {
            if (activeRecording == null) {
                // Initial bind with both videoCapture and optional preview
                camera = if (preview != null) {
                    provider.bindToLifecycle(
                        foregroundLifecycleOwner,
                        cameraSelector,
                        preview,
                        videoCapture
                    )
                } else {
                    provider.bindToLifecycle(
                        foregroundLifecycleOwner,
                        cameraSelector,
                        videoCapture
                    )
                }
            } else if (preview != null) {
                // Recording is ongoing in background, re-attach preview surface seamlessly
                camera = provider.bindToLifecycle(
                    foregroundLifecycleOwner,
                    cameraSelector,
                    preview
                )
            }

            // Setup zoom observers
            camera?.cameraInfo?.zoomState?.observe(foregroundLifecycleOwner) { state ->
                _zoomRatio.value = state.zoomRatio
                _maxZoomRatio.value = state.maxZoomRatio
                _minZoomRatio.value = state.minZoomRatio
            }

            // Setup exposure compensation state
            val exposureState = camera?.cameraInfo?.exposureState
            if (exposureState != null && exposureState.isExposureCompensationSupported) {
                _isExposureSupported.value = true
                _minExposureIndex.value = exposureState.exposureCompensationRange.lower
                _maxExposureIndex.value = exposureState.exposureCompensationRange.upper
                val stepFloat = exposureState.exposureCompensationStep.let {
                    if (it.denominator != 0) it.numerator.toFloat() / it.denominator.toFloat() else 0.33f
                }
                _exposureStep.value = stepFloat
                _exposureIndex.value = exposureState.exposureCompensationIndex
                updateExposureText(exposureState.exposureCompensationIndex, stepFloat)
            } else {
                _isExposureSupported.value = false
            }

            // Query lens hyperfocal distance for far focus calibration
            queryHyperfocalDistance()

            // Apply Far Only Mode optical setting if active
            applyFarOnlyModeInternal(isFarOnlyModeEnabled, _farFocusDistance.value)

        } catch (e: Exception) {
            Log.e("CameraRecorderManager", "Binding use cases failed", e)
            _recordingState.value = RecordingState.Error("Camera binding failed: ${e.message}")
        }
    }

    fun attachPreview(previewView: PreviewView) {
        currentPreviewView = previewView
        if (cameraProvider != null) {
            bindCameraUseCases(withPreview = true)
        }
    }

    fun detachPreview() {
        currentPreviewView = null
        val provider = cameraProvider ?: return
        try {
            preview?.let {
                provider.unbind(it)
                preview = null
            }
        } catch (e: Exception) {
            Log.w("CameraRecorderManager", "Detaching preview failed", e)
        }
    }

    @SuppressLint("UnsafeOptInUsageError")
    private fun queryHyperfocalDistance() {
        val cam = camera ?: return
        try {
            val camera2Info = Camera2CameraInfo.from(cam.cameraInfo)
            val hf = camera2Info.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_HYPERFOCAL_DISTANCE)
            _hyperfocalDistance.value = hf
            if (hf != null && hf > 0.0f) {
                _farFocusDistance.value = hf
            }
        } catch (e: Exception) {
            Log.w("CameraRecorderManager", "Hyperfocal distance not reported by hardware", e)
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
        applyFarOnlyModeInternal(enabled, _farFocusDistance.value)
    }

    @SuppressLint("UnsafeOptInUsageError")
    fun setFarFocusDistance(distanceDiopters: Float) {
        val clamped = distanceDiopters.coerceIn(0.005f, 0.25f)
        _farFocusDistance.value = clamped
        if (isFarOnlyModeEnabled) {
            applyFarOnlyModeInternal(true, clamped)
        }
    }

    @SuppressLint("UnsafeOptInUsageError")
    private fun applyFarOnlyModeInternal(enabled: Boolean, focusDistance: Float) {
        val cam = camera ?: return
        try {
            val camera2Control = Camera2CameraControl.from(cam.cameraControl)
            if (enabled) {
                val targetDistance = focusDistance.coerceAtLeast(0.005f)

                val options = CaptureRequestOptions.Builder()
                    .setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                    .setCaptureRequestOption(CaptureRequest.LENS_FOCUS_DISTANCE, targetDistance)
                    .setCaptureRequestOption(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_HIGH_QUALITY)
                    .setCaptureRequestOption(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY)
                    .setCaptureRequestOption(CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE, CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE_HIGH_QUALITY)
                    .build()
                camera2Control.setCaptureRequestOptions(options)
            } else {
                val options = CaptureRequestOptions.Builder()
                    .setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                    .build()
                camera2Control.setCaptureRequestOptions(options)
            }
        } catch (e: Exception) {
            Log.w("CameraRecorderManager", "Failed to apply Far Only optical settings", e)
        }
    }

    fun focusOnFarSubject(x: Float, y: Float, viewWidth: Float, viewHeight: Float) {
        val cam = camera ?: return
        try {
            val factory = SurfaceOrientedMeteringPointFactory(viewWidth, viewHeight)
            val point = factory.createPoint(x, y)
            val action = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE)
                .setAutoCancelDuration(0, TimeUnit.SECONDS)
                .build()
            cam.cameraControl.startFocusAndMetering(action)
        } catch (e: Exception) {
            Log.w("CameraRecorderManager", "Tap to focus failed", e)
        }
    }

    fun setExposureIndex(index: Int) {
        val cam = camera ?: return
        val clamped = index.coerceIn(_minExposureIndex.value, _maxExposureIndex.value)
        _exposureIndex.value = clamped
        cam.cameraControl.setExposureCompensationIndex(clamped)
        updateExposureText(clamped, _exposureStep.value)
    }

    fun stepExposure(delta: Int) {
        setExposureIndex(_exposureIndex.value + delta)
    }

    fun resetExposure() {
        setExposureIndex(0)
    }

    private fun updateExposureText(index: Int, step: Float) {
        val ev = index * step
        _exposureValueText.value = if (ev > 0) "+%.1f EV".format(ev) else "%.1f EV".format(ev)
    }

    fun switchCamera(
        previewView: PreviewView?,
        quality: VideoQuality
    ) {
        if (_recordingState.value is RecordingState.Recording) return
        currentLensFacing = if (currentLensFacing == CameraSelector.LENS_FACING_BACK) {
            CameraSelector.LENS_FACING_FRONT
        } else {
            CameraSelector.LENS_FACING_BACK
        }
        currentPreviewView = previewView
        bindCameraUseCases(withPreview = previewView != null)
    }

    fun changeQuality(
        previewView: PreviewView?,
        quality: VideoQuality
    ) {
        if (_recordingState.value is RecordingState.Recording) return
        currentQuality = quality
        currentPreviewView = previewView
        bindCameraUseCases(withPreview = previewView != null)
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
        if (activeRecording == null) {
            foregroundLifecycleOwner.stop()
            cameraProvider?.unbindAll()
        }
    }

    companion object {
        @Volatile
        private var INSTANCE: CameraRecorderManager? = null

        fun getInstance(context: Context): CameraRecorderManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: CameraRecorderManager(
                    context.applicationContext,
                    ClipStorageRepository(context.applicationContext)
                ).also { INSTANCE = it }
            }
        }
    }
}
