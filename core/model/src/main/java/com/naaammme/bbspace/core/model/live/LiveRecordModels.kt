package com.naaammme.bbspace.core.model

import androidx.compose.runtime.Immutable

@Immutable
data class LiveRecordItem(
    val recordId: Long,
    val liveKey: String?,
    val roomId: Long,
    val uid: Long,
    val title: String,
    val cover: String?,
    val startTimeSec: Long?,
    val durationSec: Long?,
    val online: Long?,
    val playUrl: String?
)

@Immutable
data class LiveRecordPage(
    val items: List<LiveRecordItem>,
    val page: Int,
    val pageSize: Int,
    val total: Int,
    val hasMore: Boolean
)
