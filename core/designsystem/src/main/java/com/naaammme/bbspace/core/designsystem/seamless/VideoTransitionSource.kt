package com.naaammme.bbspace.core.designsystem.seamless

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import com.naaammme.bbspace.core.model.VideoTarget
import com.naaammme.bbspace.core.model.VideoTransitionBounds
import com.naaammme.bbspace.core.model.VideoTransitionCoordinator
import com.naaammme.bbspace.core.model.VideoTransitionSource

/**
 * 记录视频卡片封面在根布局中的位置，供所有视频入口统一使用 ColorOS 风格兼容动画。
 * 与首页卡片使用同一套 [VideoTransitionCoordinator]，支持滚动后实时更新。
 */
private val transitionBounds = HashMap<Any, Rect>()

fun Modifier.trackVideoTransitionSource(
    target: VideoTarget?,
    cover: String?
): Modifier {
    if (target == null) return this
    return onGloballyPositioned { coordinates ->
        transitionBounds[target] = coordinates.boundsInRoot()
        val current = VideoTransitionCoordinator.source
        if (current != null && current.target == target) {
            val bounds = coordinates.boundsInRoot()
            VideoTransitionCoordinator.source = current.copy(
                bounds = VideoTransitionBounds(
                    left = bounds.left,
                    top = bounds.top,
                    right = bounds.right,
                    bottom = bounds.bottom
                )
            )
        }
    }
}

fun prepareVideoTransition(
    target: VideoTarget?,
    cover: String?
) {
    if (target == null) return
    val bounds = transitionBounds[target] ?: return
    VideoTransitionCoordinator.source = VideoTransitionSource(
        target = target,
        bounds = VideoTransitionBounds(
            left = bounds.left,
            top = bounds.top,
            right = bounds.right,
            bottom = bounds.bottom
        ),
        cover = cover
    )
}
