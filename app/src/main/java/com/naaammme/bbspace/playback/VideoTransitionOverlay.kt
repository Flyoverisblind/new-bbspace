package com.naaammme.bbspace.playback

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.delay
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.compose.ui.graphics.graphicsLayer
import com.naaammme.bbspace.core.designsystem.component.BiliAsyncImage
import kotlin.math.roundToInt

@Composable
fun VideoTransitionOverlay(
    state: VideoTransitionState?,
    durationMs: Int,
    startRadiusDp: Int,
    onOpened: () -> Unit,
    onFaded: () -> Unit,
    onClosed: () -> Unit
) {
    if (state == null) return
    val progress = remember(state) {
        Animatable(if (state.phase == VideoTransitionPhase.Opening) 0f else 1f)
    }
    val alpha = remember(state) { Animatable(1f) }
    LaunchedEffect(state.phase, state.fadeOut) {
        when (state.phase) {
            VideoTransitionPhase.Opening -> {
                progress.snapTo(0f)
                progress.animateTo(1f, animationSpec = tween(durationMs.coerceAtLeast(1)))
                onOpened()
            }
            VideoTransitionPhase.Closing -> {
                progress.animateTo(0f, animationSpec = tween(durationMs.coerceAtLeast(1)))
                onClosed()
            }
            VideoTransitionPhase.Open -> {
                if (state.fadeOut) {
                    alpha.animateTo(0f, animationSpec = tween(180))
                    onFaded()
                } else {
                    delay(2000)
                    alpha.animateTo(0f, animationSpec = tween(180))
                    onFaded()
                }
            }
        }
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .zIndex(100f)
            .graphicsLayer { this.alpha = alpha.value }
    ) {
        val density = LocalDensity.current
        val target = Rect(
            left = 0f,
            top = 0f,
            right = constraints.maxWidth.toFloat(),
            bottom = constraints.maxHeight.toFloat()
        )
        val sourceBounds = Rect(
            left = state.source.bounds.left,
            top = state.source.bounds.top,
            right = state.source.bounds.right,
            bottom = state.source.bounds.bottom
        )
        val current = lerpRect(sourceBounds, target, progress.value)
        val radiusPx = with(density) { startRadiusDp.coerceAtLeast(0).dp.toPx() } * (1f - progress.value)
        Box(
            modifier = Modifier
                .offset {
                    IntOffset(
                        x = current.left.roundToInt(),
                        y = current.top.roundToInt()
                    )
                }
                .size(
                    width = with(density) { current.width.toDp() },
                    height = with(density) { current.height.toDp() }
                )
                .clip(RoundedCornerShape(with(density) { radiusPx.toDp() }))
                .background(Color.Black)
        ) {
            state.source.cover?.takeIf(String::isNotBlank)?.let { cover ->
                BiliAsyncImage(
                    url = cover,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            }
        }
    }
}

private fun lerpRect(start: Rect, end: Rect, fraction: Float): Rect {
    return Rect(
        left = lerp(start.left, end.left, fraction),
        top = lerp(start.top, end.top, fraction),
        right = lerp(start.right, end.right, fraction),
        bottom = lerp(start.bottom, end.bottom, fraction)
    )
}

private fun lerp(start: Float, end: Float, fraction: Float): Float {
    return start + (end - start) * fraction
}
