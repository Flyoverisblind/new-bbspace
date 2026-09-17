package com.naaammme.bbspace.core.model

import androidx.compose.runtime.Immutable

@Immutable
data class VideoQueueItem(
    val target: VideoTarget,
    val title: String,
    val cover: String? = null,
    val ownerName: String? = null,
    val durationText: String? = null
)
