package com.naaammme.bbspace.playback

import com.naaammme.bbspace.core.model.VideoTransitionSource

enum class VideoTransitionPhase {
    Opening,
    Open,
    Closing
}

data class VideoTransitionState(
    val source: VideoTransitionSource,
    val phase: VideoTransitionPhase,
    val fadeOut: Boolean = false
)
