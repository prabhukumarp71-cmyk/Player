package com.example.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun FarOnlyReticle(
    currentFocusDistance: Float,
    onDistanceSelected: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "reticle_pulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.6f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse_alpha"
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .testTag("far_only_reticle_overlay"),
        contentAlignment = Alignment.Center
    ) {
        // Optical scope reticle lines
        Canvas(modifier = Modifier.fillMaxSize()) {
            val cx = size.width / 2f
            val cy = size.height / 2f - 40.dp.toPx()
            val scopeRadius = 130.dp.toPx()
            val bracketLen = 26.dp.toPx()
            val cyanColor = Color(0xFF00E5FF).copy(alpha = pulseAlpha)

            // Outer scope dashed ring
            drawCircle(
                color = cyanColor.copy(alpha = 0.35f * pulseAlpha),
                radius = scopeRadius,
                center = Offset(cx, cy),
                style = Stroke(
                    width = 2.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 12f), 0f)
                )
            )

            // Inner focus circle
            drawCircle(
                color = cyanColor,
                radius = 18.dp.toPx(),
                center = Offset(cx, cy),
                style = Stroke(width = 1.5.dp.toPx())
            )

            // Center target dot
            drawCircle(
                color = cyanColor,
                radius = 3.dp.toPx(),
                center = Offset(cx, cy)
            )

            // Four corner viewfinder brackets around scope
            val bSize = scopeRadius * 0.75f
            val strokeW = 2.5.dp.toPx()

            // Top-left bracket
            drawLine(cyanColor, Offset(cx - bSize, cy - bSize), Offset(cx - bSize + bracketLen, cy - bSize), strokeW)
            drawLine(cyanColor, Offset(cx - bSize, cy - bSize), Offset(cx - bSize, cy - bSize + bracketLen), strokeW)

            // Top-right bracket
            drawLine(cyanColor, Offset(cx + bSize, cy - bSize), Offset(cx + bSize - bracketLen, cy - bSize), strokeW)
            drawLine(cyanColor, Offset(cx + bSize, cy - bSize), Offset(cx + bSize, cy - bSize + bracketLen), strokeW)

            // Bottom-left bracket
            drawLine(cyanColor, Offset(cx - bSize, cy + bSize), Offset(cx - bSize + bracketLen, cy + bSize), strokeW)
            drawLine(cyanColor, Offset(cx - bSize, cy + bSize), Offset(cx - bSize, cy + bSize - bracketLen), strokeW)

            // Bottom-right bracket
            drawLine(cyanColor, Offset(cx + bSize, cy + bSize), Offset(cx + bSize - bracketLen, cy + bSize), strokeW)
            drawLine(cyanColor, Offset(cx + bSize, cy + bSize), Offset(cx + bSize, cy + bSize - bracketLen), strokeW)

            // Horizontal distance crosshairs
            drawLine(cyanColor.copy(alpha = 0.5f), Offset(cx - scopeRadius - 30f, cy), Offset(cx - scopeRadius + 15f, cy), 1.5.dp.toPx())
            drawLine(cyanColor.copy(alpha = 0.5f), Offset(cx + scopeRadius - 15f, cy), Offset(cx + scopeRadius + 30f, cy), 1.5.dp.toPx())
        }

        // Overlay status badge and Far Focus presets dial
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(top = 150.dp)
        ) {
            Box(
                modifier = Modifier
                    .background(Color(0xDD001824), shape = RoundedCornerShape(20.dp))
                    .border(1.dp, Color(0xFF00E5FF).copy(alpha = 0.6f), RoundedCornerShape(20.dp))
                    .padding(horizontal = 14.dp, vertical = 6.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Visibility,
                        contentDescription = "Far Only Optics Active",
                        tint = Color(0xFF00E5FF),
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "FAR ONLY MODE • OPTICAL DISTANCE LOCK [ ∞ ]",
                        color = Color(0xFF00E5FF),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        letterSpacing = 1.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Far Distance Range Tuning Selector
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .background(Color(0xCC0B101B), RoundedCornerShape(16.dp))
                    .border(1.dp, Color(0xFF1E2A3A), RoundedCornerShape(16.dp))
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                val presets = listOf(
                    0.05f to "Hyperfocal (10m+)",
                    0.015f to "Horizon (∞)",
                    0.08f to "Far (15-30m)",
                    0.12f to "Mid-Far (6-15m)"
                )

                presets.forEach { (distance, label) ->
                    val isSelected = kotlin.math.abs(currentFocusDistance - distance) < 0.02f
                    Box(
                        modifier = Modifier
                            .background(
                                if (isSelected) Color(0xFF00E5FF) else Color(0x3300E5FF),
                                RoundedCornerShape(10.dp)
                            )
                            .clickable { onDistanceSelected(distance) }
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = label,
                            color = if (isSelected) Color(0xFF07111E) else Color(0xFFE0F7FA),
                            fontSize = 10.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Tap any distant object on screen to lock focus • Near objects bypassed",
                color = Color.White.copy(alpha = 0.75f),
                fontSize = 10.sp,
                fontFamily = FontFamily.SansSerif
            )
        }
    }
}
