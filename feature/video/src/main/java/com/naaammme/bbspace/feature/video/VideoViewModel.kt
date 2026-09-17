package com.naaammme.bbspace.feature.video

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.Player
import com.naaammme.bbspace.core.settings.AppSettings
import com.naaammme.bbspace.core.playback.VideoActionRepository
import com.naaammme.bbspace.core.playback.VideoOnlineRepository
import com.naaammme.bbspace.core.playback.VideoPlaybackController
import com.naaammme.bbspace.core.model.CommentSubject
import com.naaammme.bbspace.core.model.CommentSubjectTool
import com.naaammme.bbspace.core.model.DanmakuConfig
import com.naaammme.bbspace.core.model.PlayBiz
import com.naaammme.bbspace.core.model.PlaybackEndAction
import com.naaammme.bbspace.core.model.PlaybackProgress
import com.naaammme.bbspace.core.model.PlaybackState
import com.naaammme.bbspace.core.model.PlayerBufferProfile
import com.naaammme.bbspace.core.model.PlayerPlaybackPrefs
import com.naaammme.bbspace.core.model.ResolvedVideoIds
import com.naaammme.bbspace.core.model.VideoCdnMode
import com.naaammme.bbspace.core.model.VideoDetail
import com.naaammme.bbspace.core.model.VideoDownloadKind
import com.naaammme.bbspace.core.model.VideoDownloadMeta
import com.naaammme.bbspace.core.model.VideoDownloadRequest
import com.naaammme.bbspace.core.model.VideoPlaybackState
import com.naaammme.bbspace.core.model.VideoQueueItem
import com.naaammme.bbspace.core.model.VideoTarget
import com.naaammme.bbspace.core.model.isSameEntry
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class VideoPlayQueue(
    val title: String,
    val items: List<VideoQueueItem>,
    val currentIndex: Int
)

data class VideoActionUiState(
    val isLiked: Boolean = false,
    val isCoined: Boolean = false,
    val coinCount: Int = 0,
    val isFavorited: Boolean = false,
    val likeDelta: Int = 0,
    val coinDelta: Int = 0,
    val favDelta: Int = 0,
    val isWorking: Boolean = false,
    val message: String? = null
)

@HiltViewModel
class VideoViewModel @Inject constructor(
    private val playbackController: VideoPlaybackController,
    private val playerSettings: AppSettings,
    private val onlineRepository: VideoOnlineRepository,
    private val actionRepository: VideoActionRepository
) : ViewModel() {

    private val _targetStack = MutableStateFlow<List<VideoTarget>>(emptyList())
    private var playQueue: List<VideoTarget> = emptyList()
    private var playQueueIndex = -1
    private var playbackPrefs = PlayerPlaybackPrefs()
    private var endedHandled = false
    private val _playQueueState = MutableStateFlow<VideoPlayQueue?>(null)
    private val _actionState = MutableStateFlow(VideoActionUiState())
    private val _onlineCount = MutableStateFlow(0L)
    private var onlineKey: Pair<Long, Long>? = null
    private var actionKey: Pair<Long, Long>? = null

    val playQueueState: StateFlow<VideoPlayQueue?> = _playQueueState
    val actionState: StateFlow<VideoActionUiState> = _actionState
    val onlineCount: StateFlow<Long> = _onlineCount

    val player: StateFlow<Player?> = playbackController.player
    val videoState: StateFlow<VideoPlaybackState> = playbackController.videoState
    val playbackProgress: StateFlow<PlaybackProgress> = playbackController.playbackProgress
    val settingsState = playerSettings.state

    init {
        viewModelScope.launch {
            playerSettings.state.collect { playbackPrefs = it.playback }
        }
        viewModelScope.launch {
            videoState.collect { state ->
                val ids = state.ids
                val key = ids.aid to ids.cid
                if (ids.aid > 0L && actionKey != key) {
                    actionKey = key
                    _actionState.value = VideoActionUiState()
                    viewModelScope.launch {
                        runCatching {
                            actionRepository.fetchActionState(ids.aid)
                        }.onSuccess { action ->
                            if (actionKey == key) {
                                _actionState.value = _actionState.value.copy(
                                    isLiked = action.liked,
                                    isCoined = action.coined,
                                    coinCount = action.coinCount,
                                    isFavorited = action.favorited
                                )
                            }
                        }
                    }
                }
                if (ids.aid > 0L && ids.cid > 0L && key != onlineKey) {
                    onlineKey = key
                    runCatching {
                        onlineRepository.fetchOnlineTotal(
                            aid = ids.aid,
                            cid = ids.cid,
                            bvid = ids.bvid
                        )
                    }.onSuccess { _onlineCount.value = it }
                } else if (ids.aid <= 0L) {
                    onlineKey = null
                    _onlineCount.value = 0L
                }
                if (ids.aid <= 0L) {
                    actionKey = null
                    _actionState.value = VideoActionUiState()
                }
            }
        }
        viewModelScope.launch {
            videoState.collect { state ->
                val ended = state.playbackState == PlaybackState.Ended &&
                        !state.isPreparing &&
                        state.playbackSource != null
                if (!ended) {
                    endedHandled = false
                    syncPlayQueueFromDetail()
                    return@collect
                }
                if (endedHandled) return@collect
                endedHandled = true
                handlePlaybackEnded()
            }
        }
    }

    val commentSubject: CommentSubject?
        get() {
            val src = currentTarget()?.src ?: return null
            val aid = videoState.value.ids.aid.takeIf { it > 0L } ?: return null
            return CommentSubjectTool.video(aid, src)
        }

    internal val danmakuState = playbackController.danmakuState

    fun openRoot(target: VideoTarget) {
        _targetStack.value = listOf(target)
        clearPlayQueue()
        playbackController.openVideo(target)
    }

    fun openPlaylist(
        title: String,
        items: List<VideoQueueItem>,
        startIndex: Int = 0
    ) {
        if (items.isEmpty()) return
        val index = startIndex.coerceIn(0, items.lastIndex)
        val target = items[index].target
        _targetStack.value = listOf(target)
        playQueue = items.map { it.target }
        playQueueIndex = index
        _playQueueState.value = VideoPlayQueue(
            title = title,
            items = items,
            currentIndex = index
        )
        playbackController.openVideo(target)
    }

    fun openTarget(target: VideoTarget) {
        val current = currentTarget()
        if (current == target) return
        _targetStack.value = when {
            current == null -> listOf(target)
            current.isSameEntry(target) -> _targetStack.value.dropLast(1) + target
            else -> _targetStack.value + target
        }
        clearPlayQueue()
        playbackController.openVideo(target)
    }

    fun popPage(): Boolean {
        val stack = _targetStack.value
        if (stack.size <= 1) return false
        val nextStack = stack.dropLast(1)
        val nextTarget = nextStack.last()
        _targetStack.value = nextStack
        syncPlayQueuePosition(nextTarget)
        playbackController.openVideo(nextTarget)
        return true
    }

    fun togglePlayPause() {
        if (videoState.value.isPlaying) {
            playbackController.pause()
        } else {
            playbackController.play()
        }
    }

    fun switchQuality(quality: Int) {
        playbackController.switchVideoQuality(quality)
    }

    fun switchAudio(audioId: Int) {
        playbackController.switchVideoAudio(audioId)
    }

    fun updateVideoCdnMode(mode: VideoCdnMode) {
        viewModelScope.launch {
            playerSettings.setVideoCdnMode(mode)
        }
    }

    fun updatePlaybackEndAction(action: PlaybackEndAction) {
        viewModelScope.launch {
            playerSettings.setPlaybackEndAction(action)
        }
    }

    fun likeVideo() {
        if (currentTarget() is VideoTarget.LiveRecord && videoState.value.ids.aid <= 0L) {
            val liked = !_actionState.value.isLiked
            _actionState.value = _actionState.value.copy(
                isLiked = liked,
                likeDelta = _actionState.value.likeDelta + if (liked) 1 else -1,
                message = if (liked) "已点赞" else "已取消赞"
            )
            return
        }
        val aid = videoState.value.ids.aid.takeIf { it > 0L } ?: return
        val previous = _actionState.value
        val nextLiked = !previous.isLiked
        _actionState.value = previous.copy(
            isLiked = nextLiked,
            likeDelta = previous.likeDelta + if (nextLiked) 1 else -1,
            isWorking = true,
            message = if (nextLiked) "已点赞" else "已取消赞"
        )
        viewModelScope.launch {
            runCatching {
                actionRepository.like(aid, liked = !nextLiked)
            }.onSuccess { ok ->
                if (!ok) {
                    _actionState.value = previous.copy(
                        isWorking = false,
                        message = "点赞失败"
                    )
                } else {
                    _actionState.value = _actionState.value.copy(
                        isWorking = false,
                        message = if (nextLiked) "已点赞" else "已取消赞"
                    )
                }
            }.onFailure { error ->
                _actionState.value = previous.copy(
                    isWorking = false,
                    message = error.message ?: "点赞失败"
                )
            }
        }
    }

    fun coinVideo(count: Int = 2) {
        if (currentTarget() is VideoTarget.LiveRecord && videoState.value.ids.aid <= 0L) {
            _actionState.value = _actionState.value.copy(
                isCoined = true,
                coinCount = count.coerceIn(1, 2),
                isLiked = true,
                coinDelta = _actionState.value.coinDelta + count.coerceIn(1, 2),
                likeDelta = if (_actionState.value.isLiked) _actionState.value.likeDelta else _actionState.value.likeDelta + 1,
                message = "投币成功"
            )
            return
        }
        val aid = videoState.value.ids.aid.takeIf { it > 0L } ?: return
        val previous = _actionState.value
        _actionState.value = previous.copy(
            isCoined = true,
            coinCount = count.coerceIn(1, 2),
            isLiked = true,
            coinDelta = previous.coinDelta + count.coerceIn(1, 2),
            likeDelta = if (previous.isLiked) previous.likeDelta else previous.likeDelta + 1,
            isWorking = true,
            message = "投币成功"
        )
        viewModelScope.launch {
            runCatching {
                actionRepository.coin(
                    aid = aid,
                    multiply = count.coerceIn(1, 2),
                    selectLike = true
                )
            }.onSuccess { ok ->
                if (ok) {
                    _actionState.value = _actionState.value.copy(
                        isWorking = false,
                        message = "投币成功"
                    )
                } else {
                    _actionState.value = previous.copy(
                        isWorking = false,
                        message = "投币失败"
                    )
                }
            }.onFailure { error ->
                _actionState.value = previous.copy(
                    isWorking = false,
                    message = error.message ?: "投币失败"
                )
            }
        }
    }

    fun favoriteVideo() {
        if (currentTarget() is VideoTarget.LiveRecord && videoState.value.ids.aid <= 0L) {
            val fav = !_actionState.value.isFavorited
            _actionState.value = _actionState.value.copy(
                isFavorited = fav,
                favDelta = _actionState.value.favDelta + if (fav) 1 else -1,
                message = if (fav) "已收藏" else "已取消收藏"
            )
            return
        }
        val aid = videoState.value.ids.aid.takeIf { it > 0L } ?: return
        val previous = _actionState.value
        val nextFav = !previous.isFavorited
        _actionState.value = previous.copy(
            isFavorited = nextFav,
            favDelta = previous.favDelta + if (nextFav) 1 else -1,
            isWorking = true,
            message = if (nextFav) "已收藏" else "已取消收藏"
        )
        viewModelScope.launch {
            runCatching {
                actionRepository.favorite(aid, fav = nextFav)
            }.onSuccess { ok ->
                _actionState.value = if (ok) {
                    _actionState.value.copy(
                        isWorking = false,
                        message = if (nextFav) "已收藏" else "已取消收藏"
                    )
                } else {
                    previous.copy(
                        isWorking = false,
                        message = "收藏失败"
                    )
                }
            }.onFailure { error ->
                _actionState.value = previous.copy(
                    isWorking = false,
                    message = error.message ?: "收藏失败"
                )
            }
        }
    }

    fun tripleVideo() {
        if (currentTarget() is VideoTarget.LiveRecord && videoState.value.ids.aid <= 0L) {
            _actionState.value = _actionState.value.copy(
                isLiked = true,
                isCoined = true,
                coinCount = 2,
                isFavorited = true,
                likeDelta = 1,
                coinDelta = 2,
                favDelta = 1,
                message = "一键三连成功"
            )
            return
        }
        val aid = videoState.value.ids.aid.takeIf { it > 0L } ?: return
        viewModelScope.launch {
            _actionState.value = _actionState.value.copy(isWorking = true, message = null)
            runCatching {
                actionRepository.triple(aid)
            }.onSuccess { result ->
                _actionState.value = _actionState.value.copy(
                    isLiked = result.like,
                    isCoined = result.coin,
                    coinCount = if (result.coin) 2 else _actionState.value.coinCount,
                    isFavorited = result.fav,
                    likeDelta = if (result.like) 1 else 0,
                    coinDelta = if (result.coin) 2 else 0,
                    favDelta = if (result.fav) 1 else 0,
                    isWorking = false,
                    message = if (result.like && result.coin && result.fav) "一键三连成功" else "一键三连完成"
                )
            }.onFailure { error ->
                _actionState.value = _actionState.value.copy(
                    isWorking = false,
                    message = error.message ?: "一键三连失败"
                )
            }
        }
    }

    fun seekTo(positionMs: Long) {
        playbackController.seekTo(positionMs)
    }

    fun setSpeed(speed: Float) {
        playbackController.setSpeed(speed)
    }

    fun updateBackgroundPlayback(enabled: Boolean) {
        viewModelScope.launch {
            playerSettings.setBackgroundPlayback(enabled)
        }
    }

    fun updateInAppMiniPlayer(enabled: Boolean) {
        viewModelScope.launch {
            playerSettings.setInAppMiniPlayer(enabled)
        }
    }

    fun updateReportPlayback(enabled: Boolean) {
        viewModelScope.launch {
            playerSettings.setReportPlayback(enabled)
        }
    }

    fun updateBufferProfile(profile: PlayerBufferProfile) {
        viewModelScope.launch {
            playerSettings.setBufferProfile(profile)
        }
    }

    fun updatePreferSoftwareDecode(enabled: Boolean) {
        viewModelScope.launch {
            playerSettings.setPreferSoftwareDecode(enabled)
        }
    }

    fun updateDecoderFallback(enabled: Boolean) {
        viewModelScope.launch {
            playerSettings.setDecoderFallback(enabled)
        }
    }

    fun updateAutoRotateFullscreen(enabled: Boolean) {
        viewModelScope.launch {
            playerSettings.setAutoRotateFullscreen(enabled)
        }
    }

    fun updateGestureSpeed(speed: Float) {
        viewModelScope.launch {
            playerSettings.setGestureSpeed(speed)
        }
    }

    fun updateDanmaku(config: DanmakuConfig) {
        viewModelScope.launch {
            playerSettings.setDanmaku(config)
        }
    }

    fun switchPage(cid: Long) {
        val pageTarget = currentTarget() as? VideoTarget.Ugc ?: return
        val ids = videoState.value.ids
        if (ids.cid == cid) return
        if (ids.aid <= 0L || cid <= 0L) return
        val nextTarget = VideoTarget.Ugc(
            aid = ids.aid,
            cid = cid,
            bvid = ids.bvid,
            src = pageTarget.src
        )
        updatePlayQueue(buildPageQueue(videoState.value.detail, ids), nextTarget)
        _targetStack.value = _targetStack.value.dropLast(1) + nextTarget
        playbackController.openVideo(nextTarget)
    }

    fun switchEpisode(target: VideoTarget) {
        val cur = currentTarget() ?: return
        if (cur == target) return
        updatePlayQueue(buildSeasonQueue(videoState.value.detail, target), target)
        _targetStack.value = _targetStack.value.dropLast(1) + target
        playbackController.openVideo(target)
    }

    fun switchPlayQueueItem(index: Int) {
        val queue = _playQueueState.value ?: return
        if (index !in queue.items.indices) return
        val target = queue.items[index].target
        playQueue = queue.items.map { it.target }
        playQueueIndex = index
        _playQueueState.value = queue.copy(currentIndex = index)
        _targetStack.value = listOf(target)
        playbackController.openVideo(target)
    }

    private fun clearPlayQueue() {
        playQueue = emptyList()
        playQueueIndex = -1
        _playQueueState.value = null
    }

    private fun updatePlayQueue(
        queue: List<VideoTarget>,
        current: VideoTarget
    ) {
        if (queue.size <= 1) {
            clearPlayQueue()
            return
        }
        playQueue = queue
        playQueueIndex = queue.indexOfFirst { it.matches(current) }
        _playQueueState.value = null
    }

    private fun syncPlayQueuePosition(target: VideoTarget) {
        if (playQueue.size <= 1) return
        val index = playQueue.indexOfFirst { it.matches(target) }
        if (index >= 0) {
            playQueueIndex = index
            _playQueueState.value = _playQueueState.value?.copy(currentIndex = index)
        }
    }

    private fun syncPlayQueueFromDetail() {
        if (playQueue.size > 1) return
        val current = currentTarget() ?: return
        val detail = videoState.value.detail ?: return
        val ids = videoState.value.ids

        val seasonQueue = buildSeasonQueue(detail, current)
        if (seasonQueue.size > 1) {
            updatePlayQueue(seasonQueue, current)
            return
        }

        val pageQueue = buildPageQueue(detail, ids)
        if (pageQueue.size > 1) {
            updatePlayQueue(pageQueue, current)
        }
    }

    private fun buildSeasonQueue(
        detail: VideoDetail?,
        current: VideoTarget
    ): List<VideoTarget> {
        val episodes = detail?.season
            ?.sections
            ?.flatMap { it.eps }
            .orEmpty()
        if (episodes.size <= 1) return listOf(current)
        if (episodes.none { it.target.matches(current) }) return listOf(current)
        return episodes.map { it.target }
    }

    private fun buildPageQueue(
        detail: VideoDetail?,
        ids: ResolvedVideoIds
    ): List<VideoTarget> {
        val current = currentTarget() ?: return emptyList()
        val src = (current as? VideoTarget.Ugc)?.src ?: return listOf(current)
        val pages = detail?.pages?.takeIf { it.size > 1 } ?: return listOf(current)
        if (ids.aid <= 0L) return listOf(current)
        return pages.map { page ->
            VideoTarget.Ugc(
                aid = ids.aid,
                cid = page.cid,
                bvid = ids.bvid,
                src = src
            )
        }
    }

    private fun handlePlaybackEnded() {
        when (playbackPrefs.playbackEndAction) {
            PlaybackEndAction.Pause -> playbackController.pause()
            PlaybackEndAction.Loop -> {
                playbackController.seekTo(0L)
                playbackController.play()
            }
            PlaybackEndAction.AutoNext -> {
                if (!playNextFromQueue()) {
                    playbackController.pause()
                }
            }
        }
    }

    private fun playNextFromQueue(): Boolean {
        if (playQueue.size <= 1 || playQueueIndex < 0) return false
        val nextIndex = playQueueIndex + 1
        if (nextIndex !in playQueue.indices) return false
        playQueueIndex = nextIndex
        val next = playQueue[nextIndex]
        _playQueueState.value = _playQueueState.value?.copy(currentIndex = nextIndex)
        _targetStack.value = _targetStack.value.dropLast(1) + next
        playbackController.openVideo(next)
        return true
    }

    private fun VideoTarget.matches(other: VideoTarget): Boolean {
        return when {
            this is VideoTarget.Ugc && other is VideoTarget.Ugc ->
                aid == other.aid && cid > 0L && cid == other.cid
            this is VideoTarget.Pgc && other is VideoTarget.Pgc ->
                (epId > 0L && epId == other.epId) ||
                        (seasonId != null && seasonId == other.seasonId)
            this is VideoTarget.Pugv && other is VideoTarget.Pugv ->
                (epId > 0L && epId == other.epId) ||
                        (seasonId != null && seasonId == other.seasonId)
            this is VideoTarget.LiveRecord && other is VideoTarget.LiveRecord ->
                recordId == other.recordId
            else -> this == other
        }
    }

    fun currentDownloadRequest(
        kind: VideoDownloadKind,
        videoQuality: Int,
        audioQuality: Int
    ): VideoDownloadRequest? {
        val state = videoState.value
        state.detail ?: return null
        currentTarget() ?: return null
        val ids = state.ids
        if (!ids.hasAny) return null
        val meta = buildDownloadMeta()
        return VideoDownloadRequest(
            biz = state.biz,
            aid = ids.aid,
            cid = ids.cid,
            bvid = ids.bvid,
            epId = ids.epId,
            seasonId = ids.seasonId,
            kind = kind,
            videoQuality = videoQuality,
            audioQuality = audioQuality,
            meta = meta
        )
    }

    private fun buildDownloadMeta(): VideoDownloadMeta {
        val detail = videoState.value.detail
        val cid = videoState.value.ids.cid.takeIf { it > 0L }
        val part = detail?.pages?.firstOrNull { it.cid == cid }
        val title = detail?.let {
            listOfNotNull(
                it.title.takeIf(String::isNotBlank),
                part?.part?.takeIf(String::isNotBlank)
            ).joinToString(" - ").takeIf(String::isNotBlank)
        }
        return VideoDownloadMeta(
            title = title,
            cover = detail?.cover,
            ownerUid = detail?.owner?.mid?.takeIf { it > 0L },
            ownerName = detail?.owner?.name?.takeIf(String::isNotBlank)
        )
    }

    private fun currentTarget(): VideoTarget? {
        return _targetStack.value.lastOrNull()
    }
}
