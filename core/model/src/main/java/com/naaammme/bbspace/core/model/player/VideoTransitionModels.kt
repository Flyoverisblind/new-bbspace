package com.naaammme.bbspace.core.model

import androidx.compose.runtime.Immutable

@Immutable
data class VideoTransitionBounds(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
}

@Immutable
data class VideoTransitionSource(
    val target: VideoTarget,
    val bounds: VideoTransitionBounds,
    val cover: String?
)

object VideoTransitionCoordinator {
    var source: VideoTransitionSource? = null
}
