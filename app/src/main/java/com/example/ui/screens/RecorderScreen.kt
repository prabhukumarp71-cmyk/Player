package com.example.ui.screens

import android.view.ViewGroup
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AllInclusive
import androidx.compose.material.icons.filled.BrightnessMedium
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.camera.CameraRecorderManager
import com.example.model.VideoQuality
import com.example.ui.components.FarOnlyReticle
import com.example.ui.components.QualitySelectorDialog
import com.example.viewmodel.PlayerViewModel
import com.example.viewmodel.ScreenDestination

@Composable
fun RecorderScreen(
    viewModel: PlayerViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    val recordingState by viewModel.recordingState.collectAsState()
    val recordingDuration by viewModel.recordingDurationSeconds.collectAsState()
    val recordingBytes by viewModel.recordingBytes.collectAsState()
    val selectedQuality by viewModel.selectedQuality.collectAsState()
    val isFarOnlyMode by viewModel.isFarOnlyMode.collectAsState()
    val isTorchOn by viewModel.isTorchOn.collectAsState()
    val isAudioEnabled by viewModel.isAudioEnabled.collectAsState()
    val savedClips by viewModel.savedClips.collectAsState()
    val freeStorage by viewModel.freeStorageText.collectAsState()
    val zoomRatio by viewModel.zoomRatio.collectAsState()
    val statusMessage by viewModel.statusMessage.collectAsState()
    val supportedQualities by viewModel.supportedQualities.collectAsState()

    // Far mode distance & Exposure controls
    val farFocusDistance by viewModel.farFocusDistance.collectAsState()
    val exposureIndex by viewModel.exposureIndex.collectAsState()
    val minExposureIndex by viewModel.minExposureIndex.collectAsState()
    val maxExposureIndex by viewModel.maxExposureIndex.collectAsState()
    val exposureValueText by viewModel.exposureValueText.collectAsState()
    val isExposureSupported by viewModel.isExposureSupported.collectAsState()

    var showQualityDialog by remember { mutableStateOf(false) }
    var showExposureControl by remember { mutableStateOf(false) }
    var previewViewRef by remember { mutableStateOf<PreviewView?>(null) }
    var viewSize by remember { mutableStateOf(IntSize.Zero) }

    // Tap to focus indicator position
    var tapFocusPoint by remember { mutableStateOf<Offset?>(null) }

    val isRecording = recordingState is CameraRecorderManager.RecordingState.Recording
    val isPaused = recordingState is CameraRecorderManager.RecordingState.Paused

    // Blinking animation for red record dot
    val infiniteTransition = rememberInfiniteTransition(label = "rec_blink")
    val recDotAlpha by infiniteTransition.animateFloat(
        initialValue = 0.2f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "rec_dot_alpha"
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .onGloballyPositioned { coordinates ->
                viewSize = coordinates.size
            }
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    tapFocusPoint = offset
                    if (viewSize.width > 0 && viewSize.height > 0) {
                        viewModel.focusOnFarSubject(
                            x = offset.x,
                            y = offset.y,
                            width = viewSize.width.toFloat(),
                            height = viewSize.height.toFloat()
                        )
                    }
                }
            }
            .testTag("recorder_screen")
    ) {
        DisposableEffect(Unit) {
            onDispose {
                viewModel.cameraManager.detachPreview()
            }
        }

        // Camera Viewfinder
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                PreviewView(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                    implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                    previewViewRef = this
                    viewModel.cameraManager.initializeCamera(
                        previewView = this,
                        quality = selectedQuality,
                        farOnly = isFarOnlyMode
                    )
                }
            },
            update = { pv ->
                viewModel.cameraManager.attachPreview(pv)
            }
        )

        // Far-Only Mode Reticle Overlay with interactive distance presets
        if (isFarOnlyMode) {
            FarOnlyReticle(
                currentFocusDistance = farFocusDistance,
                onDistanceSelected = { distance ->
                    viewModel.setFarFocusDistance(distance)
                },
                modifier = Modifier.fillMaxSize()
            )
        }

        // Tap to focus target indicator
        tapFocusPoint?.let { pos ->
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(
                        start = (pos.x / (LocalContext.current.resources.displayMetrics.density) - 24).coerceAtLeast(0f).dp,
                        top = (pos.y / (LocalContext.current.resources.displayMetrics.density) - 24).coerceAtLeast(0f).dp
                    )
                    .size(48.dp)
                    .border(1.5.dp, if (isFarOnlyMode) Color(0xFF00E5FF) else Color(0xFFFACC15), CircleShape)
            )
        }

        // Top Control Bar
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // App Branding: Player
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .background(Color(0x99111318), RoundedCornerShape(20.dp))
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .background(
                                if (isRecording) Color(0xFFEF4444).copy(alpha = recDotAlpha) else Color(0xFF10B981),
                                CircleShape
                            )
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "PLAYER",
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 1.2.sp
                    )
                }

                // Storage indicator
                Box(
                    modifier = Modifier
                        .background(Color(0x88111318), RoundedCornerShape(14.dp))
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = freeStorage,
                        color = Color(0xFFCBD5E1),
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Quick Toolbar: Resolution, Far Only, Exposure EV, Flash, Mic
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Real Quality Chip
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = Color(0xBB181B26),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF38BDF8).copy(alpha = 0.5f)),
                    modifier = Modifier
                        .clickable(enabled = !isRecording) { showQualityDialog = true }
                        .testTag("quality_selector_chip")
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Tune,
                            contentDescription = "Quality Settings",
                            tint = Color(0xFF38BDF8),
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(5.dp))
                        Text(
                            text = selectedQuality.label,
                            color = Color.White,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                // Far Only Mode Toggle Button
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = if (isFarOnlyMode) Color(0xDD003547) else Color(0xAA181B26),
                    border = androidx.compose.foundation.BorderStroke(
                        width = 1.dp,
                        color = if (isFarOnlyMode) Color(0xFF00E5FF) else Color(0x44FFFFFF)
                    ),
                    modifier = Modifier
                        .clickable { viewModel.toggleFarOnlyMode(!isFarOnlyMode) }
                        .testTag("far_only_toggle_button")
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.AllInclusive,
                            contentDescription = "Far Only Mode",
                            tint = if (isFarOnlyMode) Color(0xFF00E5FF) else Color(0xFF94A3B8),
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(5.dp))
                        Text(
                            text = if (isFarOnlyMode) "FAR: ON" else "FAR ONLY",
                            color = if (isFarOnlyMode) Color(0xFF00E5FF) else Color(0xFFE2E8F0),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                // Exposure Control Toggle Button
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = if (showExposureControl) Color(0xDD3B2E05) else Color(0xAA181B26),
                    border = androidx.compose.foundation.BorderStroke(
                        width = 1.dp,
                        color = if (showExposureControl) Color(0xFFFACC15) else Color(0x44FFFFFF)
                    ),
                    modifier = Modifier
                        .clickable { showExposureControl = !showExposureControl }
                        .testTag("exposure_control_chip")
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.BrightnessMedium,
                            contentDescription = "Exposure Compensation",
                            tint = if (showExposureControl) Color(0xFFFACC15) else Color(0xFFE2E8F0),
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = exposureValueText,
                            color = if (showExposureControl) Color(0xFFFACC15) else Color.White,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Spacer(modifier = Modifier.weight(1f))

                // Mic toggle
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .background(Color(0x99181B26), CircleShape)
                        .clickable { viewModel.toggleAudio() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (isAudioEnabled) Icons.Default.Mic else Icons.Default.MicOff,
                        contentDescription = "Audio Toggle",
                        tint = if (isAudioEnabled) Color(0xFFE2E8F0) else Color(0xFFEF4444),
                        modifier = Modifier.size(16.dp)
                    )
                }

                // Torch toggle
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .background(Color(0x99181B26), CircleShape)
                        .clickable { viewModel.toggleTorch() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (isTorchOn) Icons.Default.FlashOn else Icons.Default.FlashOff,
                        contentDescription = "Torch Toggle",
                        tint = if (isTorchOn) Color(0xFFFACC15) else Color(0xFFE2E8F0),
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            // Expandable Exposure Adjustment Slider & Stepper
            AnimatedVisibility(
                visible = showExposureControl,
                enter = fadeIn() + slideInVertically(),
                exit = fadeOut() + slideOutVertically()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp)
                        .background(Color(0xDD121520), RoundedCornerShape(16.dp))
                        .border(1.dp, Color(0xFFFACC15).copy(alpha = 0.5f), RoundedCornerShape(16.dp))
                        .padding(horizontal = 14.dp, vertical = 10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.BrightnessMedium,
                                contentDescription = "Exposure",
                                tint = Color(0xFFFACC15),
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Exposure Compensation",
                                color = Color.White,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = exposureValueText,
                                color = Color(0xFFFACC15),
                                fontSize = 13.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "RESET",
                                color = Color(0xFF94A3B8),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier
                                    .background(Color(0xFF202636), RoundedCornerShape(6.dp))
                                    .clickable { viewModel.resetExposure() }
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = { viewModel.stepExposure(-1) },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(Icons.Default.Remove, "Decrease Exposure", tint = Color.White, modifier = Modifier.size(18.dp))
                        }

                        Slider(
                            value = exposureIndex.toFloat(),
                            onValueChange = { newVal ->
                                viewModel.setExposureIndex(newVal.toInt())
                            },
                            valueRange = minExposureIndex.toFloat()..maxExposureIndex.toFloat(),
                            steps = (maxExposureIndex - minExposureIndex - 1).coerceAtLeast(0),
                            modifier = Modifier.weight(1f),
                            colors = SliderDefaults.colors(
                                thumbColor = Color(0xFFFACC15),
                                activeTrackColor = Color(0xFFFACC15),
                                inactiveTrackColor = Color(0xFF333A4D)
                            )
                        )

                        IconButton(
                            onClick = { viewModel.stepExposure(1) },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(Icons.Default.Add, "Increase Exposure", tint = Color.White, modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
        }

        // Active Recording HUD Center Overlay + Background Recording Assurance
        if (isRecording || isPaused) {
            Column(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 114.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Surface(
                    shape = RoundedCornerShape(24.dp),
                    color = Color(0xCC0F1117),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFEF4444).copy(alpha = 0.6f)),
                    modifier = Modifier.testTag("active_recording_hud")
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .background(
                                    if (isPaused) Color(0xFFF59E0B) else Color(0xFFEF4444).copy(alpha = recDotAlpha),
                                    CircleShape
                                )
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        val minutes = recordingDuration / 60
                        val seconds = recordingDuration % 60
                        Text(
                            text = "%02d:%02d".format(minutes, seconds),
                            color = Color.White,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        val mb = recordingBytes / (1024.0 * 1024.0)
                        Text(
                            text = "%.1f MB".format(mb),
                            color = Color(0xFF94A3B8),
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = selectedQuality.label,
                            color = Color(0xFF38BDF8),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                // Background Recording Indicator
                Box(
                    modifier = Modifier
                        .background(Color(0xBB082015), RoundedCornerShape(12.dp))
                        .border(1.dp, Color(0xFF10B981).copy(alpha = 0.4f), RoundedCornerShape(12.dp))
                        .padding(horizontal = 10.dp, vertical = 3.dp)
                ) {
                    Text(
                        text = "FOREGROUND SERVICE ACTIVE • RECORDS IN BACKGROUND",
                        color = Color(0xFF10B981),
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }

        // Quick Zoom Bar
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 126.dp)
                .background(Color(0x99181B26), RoundedCornerShape(24.dp))
                .padding(horizontal = 6.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            listOf(1.0f to "1×", 2.0f to "2×", 3.0f to "3×", 5.0f to "5×").forEach { (zoom, label) ->
                val isCurrent = kotlin.math.abs(zoomRatio - zoom) < 0.2f
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .background(
                            if (isCurrent) Color(0xFF38BDF8) else Color.Transparent,
                            CircleShape
                        )
                        .clickable { viewModel.setZoom(zoom) }
                        .testTag("zoom_button_${label}"),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = label,
                        color = if (isCurrent) Color(0xFF0F172A) else Color.White,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        // Bottom Controls Bar (Saved Clips button, Record shutter, Flip Camera)
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Color(0xDD0B0C10))
                .navigationBarsPadding()
                .padding(horizontal = 24.dp, vertical = 20.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Saved Clips Gallery Button
                BadgedBox(
                    badge = {
                        if (savedClips.isNotEmpty()) {
                            Badge(
                                containerColor = Color(0xFF38BDF8),
                                contentColor = Color(0xFF0F172A)
                            ) {
                                Text(
                                    text = savedClips.size.toString(),
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    },
                    modifier = Modifier.testTag("gallery_shortcut_badge")
                ) {
                    Box(
                        modifier = Modifier
                            .size(54.dp)
                            .background(Color(0xFF1E222D), RoundedCornerShape(16.dp))
                            .border(1.dp, Color(0xFF333846), RoundedCornerShape(16.dp))
                            .clickable { viewModel.navigateTo(ScreenDestination.Gallery) }
                            .testTag("gallery_shortcut_button"),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Folder,
                            contentDescription = "Saved Clips Storage",
                            tint = Color(0xFFE2E8F0),
                            modifier = Modifier.size(26.dp)
                        )
                    }
                }

                // Shutter / Record Trigger Button
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    // Pause/Resume button when recording
                    if (isRecording || isPaused) {
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .background(Color(0xFF262A36), CircleShape)
                                .clickable {
                                    if (isPaused) viewModel.resumeRecording() else viewModel.pauseRecording()
                                }
                                .testTag("pause_resume_recording_button"),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = if (isPaused) Icons.Default.PlayArrow else Icons.Default.Pause,
                                contentDescription = if (isPaused) "Resume" else "Pause",
                                tint = Color.White,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(16.dp))
                    }

                    // Main Shutter Button
                    Box(
                        modifier = Modifier
                            .size(76.dp)
                            .border(
                                width = 4.dp,
                                color = if (isRecording) Color(0xFFEF4444) else Color.White,
                                shape = CircleShape
                            )
                            .padding(6.dp)
                            .clickable {
                                if (isRecording || isPaused) {
                                    viewModel.stopRecording(context)
                                } else {
                                    viewModel.startRecording(context)
                                }
                            }
                            .testTag("record_shutter_button"),
                        contentAlignment = Alignment.Center
                    ) {
                        if (isRecording || isPaused) {
                            // Square Stop Icon
                            Box(
                                modifier = Modifier
                                    .size(28.dp)
                                    .background(Color(0xFFEF4444), RoundedCornerShape(6.dp))
                            )
                        } else {
                            // Red Shutter Circle
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(Color(0xFFEF4444), CircleShape)
                            )
                        }
                    }

                    if (isRecording || isPaused) {
                        Spacer(modifier = Modifier.width(60.dp))
                    }
                }

                // Switch Camera Lens Button
                Box(
                    modifier = Modifier
                        .size(54.dp)
                        .background(Color(0xFF1E222D), RoundedCornerShape(16.dp))
                        .border(1.dp, Color(0xFF333846), RoundedCornerShape(16.dp))
                        .clickable(enabled = !isRecording) {
                            previewViewRef?.let { pv ->
                                viewModel.switchCamera(pv)
                            }
                        }
                        .testTag("switch_camera_button"),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Cameraswitch,
                        contentDescription = "Switch Camera",
                        tint = if (isRecording) Color(0xFF64748B) else Color(0xFFE2E8F0),
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
        }

        // Status Toast / Confirmation banner
        AnimatedVisibility(
            visible = statusMessage != null,
            enter = slideInVertically(initialOffsetY = { -it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { -it }) + fadeOut(),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(top = 70.dp, start = 16.dp, end = 16.dp)
        ) {
            statusMessage?.let { msg ->
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = Color(0xEE1E293B),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF38BDF8)),
                    shadowElevation = 8.dp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { viewModel.dismissStatusMessage() }
                        .testTag("status_banner")
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Videocam,
                            contentDescription = "Status",
                            tint = Color(0xFF38BDF8),
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = msg,
                            color = Color.White,
                            fontSize = 12.sp,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }

        // Resolution Quality Dialog
        if (showQualityDialog) {
            QualitySelectorDialog(
                currentQuality = selectedQuality,
                supportedQualities = supportedQualities,
                onSelectQuality = { q ->
                    previewViewRef?.let { pv ->
                        viewModel.selectQuality(q, pv)
                    }
                },
                onDismiss = { showQualityDialog = false }
            )
        }
    }
}
