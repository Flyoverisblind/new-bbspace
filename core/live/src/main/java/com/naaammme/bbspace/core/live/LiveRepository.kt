package com.naaammme.bbspace.core.live

import android.os.Build
import com.naaammme.bbspace.core.common.BiliConstants
import com.naaammme.bbspace.core.common.media.httpsImageUrl
import com.naaammme.bbspace.core.auth.AuthStore
import com.naaammme.bbspace.core.model.LivePlaybackSource
import com.naaammme.bbspace.core.model.LiveQualityOption
import com.naaammme.bbspace.core.model.LiveRecordItem
import com.naaammme.bbspace.core.model.LiveRecordPage
import com.naaammme.bbspace.core.model.LiveStatus
import com.naaammme.bbspace.infra.crypto.DeviceIdentity
import com.naaammme.bbspace.infra.network.BiliRestClient
import com.naaammme.bbspace.infra.network.BiliRestParamBuilder
import com.naaammme.bbspace.infra.network.BiliRestProfile
import javax.inject.Inject
import javax.inject.Singleton
import org.json.JSONArray
import org.json.JSONObject

data class LiveRecordStream(
    val type: Int,
    val url: String
)

@Singleton
class LiveRepository @Inject constructor(
    private val restClient: BiliRestClient,
    private val authStore: AuthStore,
    private val deviceIdentity: DeviceIdentity,
    private val restParamBuilder: BiliRestParamBuilder
) {

    suspend fun fetchPlaybackSource(
        roomId: Long,
        qn: Int
    ): LivePlaybackSource {
        val json = restClient.getSigned(
            url = "${BiliConstants.BASE_URL_LIVE_API}$ROOM_PLAY_INFO_ENDPOINT",
            params = buildParams(roomId, qn),
            profile = BiliRestProfile.APP
        )
        return parsePlaybackSource(roomId, json)
    }

    suspend fun reportRoomEntryAction(
        roomId: Long,
        jumpFrom: Int
    ) {
        val token = authStore.accessToken.takeIf(String::isNotBlank) ?: return
        val ts = System.currentTimeMillis() / 1000L
        restClient.postSigned(
            url = "${BiliConstants.BASE_URL_LIVE_API}$ROOM_ENTRY_ACTION_ENDPOINT",
            params = restParamBuilder.app(BiliRestProfile.APP, ts, token) + buildMap {
                put("actionKey", "appkey")
                put("device", BiliConstants.PLATFORM)
                put("jumpFrom", jumpFrom.toString())
                put("noHistory", "0") // 常规进房按默认历史逻辑处理
                put("room_id", roomId.toString())
                put("version", BiliConstants.VERSION)
            },
            profile = BiliRestProfile.APP
        )
    }

    suspend fun fetchLiveRecords(
        uid: Long,
        roomId: Long = 0L,
        page: Int = 1,
        pageSize: Int = 20
    ): LiveRecordPage {
        val ts = System.currentTimeMillis() / 1000L
        val params = restParamBuilder.app(BiliRestProfile.APP, ts, authStore.accessToken) + buildMap {
            put("live_uid", uid.toString())
            if (roomId > 0L) put("room_id", roomId.toString())
            put("time_range", "3")
            put("page", page.coerceAtLeast(1).toString())
            put("page_size", pageSize.coerceIn(1, 30).toString())
            put("web_location", "333.999")
            put("version", BiliConstants.VERSION)
        }
        val json = restClient.getSigned(
            url = "${BiliConstants.BASE_URL_LIVE_API}$LIVE_RECORD_LIST_ENDPOINT",
            params = params,
            profile = BiliRestProfile.APP
        )
        return parseLiveRecordPage(json, page, pageSize, uid)
    }

    private fun parseLiveRecordPage(
        json: JSONObject,
        requestedPage: Int,
        requestedPageSize: Int,
        fallbackUid: Long
    ): LiveRecordPage {
        val code = json.optInt("code")
        if (code != 0) {
            val message = json.optString("message").takeIf(String::isNotBlank)
            throw IllegalStateException(
                when (code) {
                    301 -> "该 UP 主未开放直播回放"
                    -101 -> "请先登录后再查看直播回放"
                    else -> message ?: "加载直播回放失败（$code）"
                }
            )
        }
        val data = json.optJSONObjectSafe("data")
            ?: throw IllegalStateException("直播回放数据格式异常")
        val list = data.optJSONArraySafe("replay_info")
            ?: data.optJSONArraySafe("list")
            ?: data.optJSONArraySafe("records")
            ?: data.optJSONArraySafe("items")
            ?: JSONArray()
        val items = buildList {
            for (i in 0 until list.length()) {
                val item = list.optJSONObject(i) ?: continue
                val recordId = item.optLongCompat("replay_id")
                    .takeIf { it > 0L }
                    ?: item.optLongCompat("rid").takeIf { it > 0L }
                    ?: item.optLongCompat("record_id").takeIf { it > 0L }
                    ?: item.optLongCompat("id").takeIf { it > 0L }
                    ?: continue
                val liveInfo = item.optJSONObjectSafe("live_info")
                val videoInfo = item.optJSONObjectSafe("video_info")
                val avid = item.optLongCompat("avid").takeIf { it > 0L }
                    ?: item.optLongCompat("aid").takeIf { it > 0L }
                    ?: videoInfo?.optLongCompat("avid")?.takeIf { it > 0L }
                    ?: videoInfo?.optLongCompat("aid")?.takeIf { it > 0L }
                val cid = item.optLongCompat("cid").takeIf { it > 0L }
                    ?: videoInfo?.optLongCompat("cid")?.takeIf { it > 0L }
                val viewCount = item.optLongCompat("play").takeIf { it > 0L }
                    ?: videoInfo?.optLongCompat("play")?.takeIf { it > 0L }
                val danmakuCount = item.optLongCompat("danmu").takeIf { it > 0L }
                    ?: item.optLongCompat("danmaku").takeIf { it > 0L }
                    ?: videoInfo?.optLongCompat("danmu")?.takeIf { it > 0L }
                    ?: videoInfo?.optLongCompat("danmaku")?.takeIf { it > 0L }
                val title = liveInfo?.optString("title")
                    .orEmpty()
                    .ifBlank { item.optString("title") }
                    .ifBlank { item.optString("name") }
                    .ifBlank { "直播回放 $recordId" }
                add(
                    LiveRecordItem(
                        recordId = recordId,
                        liveKey = item.optString("live_key").ifBlank { null },
                        roomId = item.optLongCompat("room_id"),
                        uid = item.optLongCompat("uid")
                            .takeIf { it > 0L }
                            ?: fallbackUid,
                        title = title,
                        cover = liveInfo?.optString("cover")
                            .orEmpty()
                            .ifBlank { item.optString("cover") }
                            .ifBlank { item.optString("cover_url") }
                            .httpsImageUrl()
                            .ifBlank { null },
                        startTimeSec = item.optLongCompat("start_time")
                            .takeIf { it > 0L }
                            ?: liveInfo?.optLongCompat("live_time")?.takeIf { it > 0L },
                        endTimeSec = item.optLongCompat("end_time").takeIf { it > 0L },
                        durationSec = videoInfo?.optLongCompat("duration")?.takeIf { it > 0L }
                            ?: item.optLongCompat("duration").takeIf { it > 0L }
                            ?: item.optLongCompat("live_time").takeIf { it > 0L },
                        online = item.optLongCompat("online").takeIf { it > 0L },
                        playUrl = videoInfo?.optString("download_url")
                            ?.ifBlank { item.optString("play_url") }
                            ?.ifBlank { item.optString("url") }
                            ?.takeIf { it.isNotBlank() },
                        avid = avid,
                        cid = cid,
                        viewCount = viewCount,
                        danmakuCount = danmakuCount
                    )
                )
            }
        }
        val pagination = data.optJSONObjectSafe("pagination")
        val total = pagination?.optInt("total")
            ?.takeIf { it > 0 }
            ?: data.optInt("total").takeIf { it > 0 }
            ?: data.optInt("count").takeIf { it > 0 }
            ?: items.size
        val resolvedPage = pagination?.optInt("page")
            ?.takeIf { it > 0 }
            ?: data.optInt("page").takeIf { it > 0 }
            ?: requestedPage
        val resolvedPageSize = pagination?.optInt("page_size")
            ?.takeIf { it > 0 }
            ?: data.optInt("page_size").takeIf { it > 0 }
            ?: requestedPageSize
        val hasMore = data.optInt("has_more") == 1 ||
                data.optBoolean("has_next") ||
                resolvedPage * resolvedPageSize < total
        return LiveRecordPage(
            items = items,
            page = resolvedPage,
            pageSize = resolvedPageSize,
            total = total,
            hasMore = hasMore
        )
    }

    private fun JSONObject.optLongCompat(key: String): Long {
        return optLong(key).takeIf { it != 0L }
            ?: optString(key).toLongOrNull()
            ?: 0L
    }

    private fun JSONObject.optJSONObjectSafe(key: String): JSONObject? {
        return runCatching { optJSONObject(key) }.getOrNull() ?: (opt(key) as? JSONObject)
    }

    private fun JSONObject.optJSONArraySafe(key: String): JSONArray? {
        return runCatching { optJSONArray(key) }.getOrNull() ?: (opt(key) as? JSONArray)
    }

    suspend fun fetchPublishedRecords(
        uid: Long,
        page: Int = 1,
        pageSize: Int = 20
    ): List<LiveRecordItem> {
        if (uid <= 0L) return emptyList()
        val ts = System.currentTimeMillis() / 1000L
        val params = restParamBuilder.app(BiliRestProfile.APP, ts, authStore.accessToken) + buildMap {
            put("live_uid", uid.toString())
            put("page", page.coerceAtLeast(1).toString())
            put("page_size", pageSize.coerceIn(1, 20).toString())
            put("web_location", "333.999")
        }
        val json = restClient.getSigned(
            url = "${BiliConstants.BASE_URL_LIVE_API}$LIVE_RECORD_PUBLISHED_ENDPOINT",
            params = params,
            profile = BiliRestProfile.APP
        )
        val data = json.optJSONObjectSafe("data") ?: return emptyList()
        val list = data.optJSONArraySafe("slice_info")
            ?: data.optJSONArraySafe("list")
            ?: return emptyList()
        return buildList {
            for (i in 0 until list.length()) {
                val item = list.optJSONObject(i) ?: continue
                val status = item.optInt("status", 0)
                val avid = item.optLongCompat("avid").takeIf { it > 0L }
                if (status != 2 && avid == null) continue
                val title = item.optString("title").ifBlank { "直播回放" }
                val cover = item.optString("cover").httpsImageUrl().ifBlank { null }
                add(
                    LiveRecordItem(
                        recordId = item.optLongCompat("slice_id").takeIf { it > 0L }
                            ?: avid
                            ?: continue,
                        liveKey = item.optString("live_key").ifBlank { null },
                        roomId = 0L,
                        uid = item.optLongCompat("live_uid").takeIf { it > 0L } ?: uid,
                        title = title,
                        cover = cover,
                        startTimeSec = null,
                        endTimeSec = null,
                        durationSec = item.optLongCompat("av_duration").takeIf { it > 0L },
                        online = null,
                        playUrl = null,
                        avid = avid,
                        cid = item.optLongCompat("cid").takeIf { it > 0L }
                    )
                )
            }
        }
    }

    suspend fun fetchRecordStreams(
        liveKey: String,
        startTime: Long,
        endTime: Long,
        liveUid: Long
    ): List<LiveRecordStream> {
        if (liveKey.isBlank() || startTime <= 0L || endTime <= 0L || liveUid <= 0L) return emptyList()
        val ts = System.currentTimeMillis() / 1000L
        val params = restParamBuilder.app(BiliRestProfile.APP, ts, authStore.accessToken) + buildMap {
            put("live_key", liveKey)
            put("start_time", startTime.toString())
            put("end_time", endTime.toString())
            put("live_uid", liveUid.toString())
            put("web_location", "333.999")
        }
        val json = restClient.getSigned(
            url = "${BiliConstants.BASE_URL_LIVE_API}$LIVE_RECORD_STREAM_ENDPOINT",
            params = params,
            profile = BiliRestProfile.APP
        )
        val list = json.optJSONObject("data")?.optJSONArray("list") ?: return emptyList()
        return buildList {
            for (i in 0 until list.length()) {
                val item = list.optJSONObject(i) ?: continue
                val url = item.optString("stream")
                    .ifBlank { item.optString("url") }
                    .takeIf(String::isNotBlank)
                    ?: continue
                add(
                    LiveRecordStream(
                        type = item.optInt("type", i + 1),
                        url = url
                    )
                )
            }
        }
    }

    suspend fun fetchRecordStream(
        liveKey: String,
        startTime: Long,
        endTime: Long,
        liveUid: Long
    ): String? = fetchRecordStreams(liveKey, startTime, endTime, liveUid)
        .firstOrNull()
        ?.url

    suspend fun fetchRecordDownloadUrl(
        recordId: Long,
        liveKey: String?
    ): String? {
        if (recordId <= 0L) return null
        val ts = System.currentTimeMillis() / 1000L
        val csrf = authStore.biliJct
        val params = restParamBuilder.app(BiliRestProfile.APP, ts, authStore.accessToken) + buildMap {
            put("record_id", recordId.toString())
            liveKey?.takeIf(String::isNotBlank)?.let { put("live_key", it) }
            if (csrf.isNotBlank()) {
                put("csrf", csrf)
                put("csrf_token", csrf)
            }
        }
        val json = restClient.postSignedRaw(
            url = "${BiliConstants.BASE_URL_LIVE_API}$LIVE_RECORD_DOWNLOAD_ENDPOINT",
            params = params,
            profile = BiliRestProfile.APP
        )
        if (json.optInt("code") != 0) return null
        val data = json.optJSONObject("data") ?: return null
        return data.optString("download_url")
            .takeIf(String::isNotBlank)
            ?: data.optJSONArray("download_url_list")
                ?.optString(0)
                ?.takeIf(String::isNotBlank)
    }

    private fun buildParams(
        roomId: Long,
        qn: Int
    ): Map<String, String> {
        val ts = System.currentTimeMillis() / 1000L
        return restParamBuilder.app(BiliRestProfile.APP, ts, authStore.accessToken) + buildMap {
            put("buvid", deviceIdentity.buvid)
            put("device", BiliConstants.PLATFORM)
            put("device_name", Build.MODEL)
            put("format", "0")
            put("free_type", "0")
            put("hdr_type", "0")
            put("http", "0")
            put("network", "wifi")
            put("no_playurl", "0")
            put("only_audio", "0")
            put("only_video", "0")
            put("play_type", "0")
            put("protocol", "0")
            put("qn", qn.coerceAtLeast(0).toString())
            put("room_id", roomId.toString())
            put("codec", "0")
            put("mask", "0")
            put("dolby", "0")
            put("special_scenario", "2")
            put("supported_drms", "0,3")
            put("version", BiliConstants.VERSION)
        }
    }

    private fun parsePlaybackSource(
        roomId: Long,
        json: JSONObject
    ): LivePlaybackSource {
        val data = json.optJSONObjectSafe("data")
            ?: throw IllegalStateException("直播取流数据格式异常")
        val liveStatus = LiveStatus.from(data.optInt("live_status"))
        val playurl = data.optJSONObjectSafe("playurl_info")
            ?.optJSONObjectSafe("playurl")
            ?: throw NoPlayableStreamException(
                if (liveStatus == LiveStatus.Offline) "当前未开播" else "暂无可用直播流"
            )
        val qualityOptions = parseQualityOptions(playurl.optJSONArray("g_qn_desc"))
        val codec = findCodec(playurl.optJSONArray("stream"))
            ?: throw NoPlayableStreamException("暂无可用 FLV 直播流")
        val baseUrl = codec.optString("base_url")
        val urlInfoArr = codec.optJSONArray("url_info")
        val urls = buildUrls(baseUrl, urlInfoArr)
        val currentQn = codec.optInt("current_qn")
        val currentDesc = qualityOptions.firstOrNull { it.qn == currentQn }?.description
            ?: "原画"

        return LivePlaybackSource(
            roomId = roomId,
            liveStatus = liveStatus,
            currentQn = currentQn,
            currentDescription = currentDesc,
            qualityOptions = qualityOptions,
            protocol = "http_stream",
            format = "flv",
            codec = codec.optString("codec_name").ifBlank { "avc" },
            primaryUrl = urls.first(),
            backupUrls = urls.drop(1),
            session = codec.optString("session").takeIf(String::isNotBlank)
        )
    }

    private fun parseQualityOptions(arr: JSONArray?): List<LiveQualityOption> {
        if (arr == null || arr.length() == 0) return emptyList()
        return buildList {
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                val qn = item.optInt("qn")
                if (qn <= 0 || any { it.qn == qn }) continue
                val desc = item.optString("desc").ifBlank { "画质 $qn" }
                add(
                    LiveQualityOption(
                        qn = qn,
                        description = desc
                    )
                )
            }
        }
    }

    private fun findCodec(streamArr: JSONArray?): JSONObject? {
        if (streamArr == null) return null
        return findCodec(streamArr, "http_stream", "flv", "avc")
            ?: findCodec(streamArr, "http_stream", "flv", null)
    }

    private fun findCodec(
        streamArr: JSONArray,
        protocolName: String,
        formatName: String,
        codecName: String?
    ): JSONObject? {
        for (i in 0 until streamArr.length()) {
            val stream = streamArr.optJSONObject(i) ?: continue
            if (stream.optString("protocol_name") != protocolName) continue
            val formats = stream.optJSONArray("format") ?: continue
            for (j in 0 until formats.length()) {
                val format = formats.optJSONObject(j) ?: continue
                if (format.optString("format_name") != formatName) continue
                val codecs = format.optJSONArray("codec") ?: continue
                for (k in 0 until codecs.length()) {
                    val codec = codecs.optJSONObject(k) ?: continue
                    if (codecName == null || codec.optString("codec_name") == codecName) {
                        return codec
                    }
                }
            }
        }
        return null
    }

    private fun buildUrls(
        baseUrl: String,
        urlInfoArr: JSONArray?
    ): List<String> {
        if (baseUrl.isBlank() || urlInfoArr == null || urlInfoArr.length() == 0) {
            throw NoPlayableStreamException("直播流地址为空")
        }
        return buildList {
            for (i in 0 until urlInfoArr.length()) {
                val info = urlInfoArr.optJSONObject(i) ?: continue
                val host = info.optString("host")
                val extra = info.optString("extra")
                if (host.isBlank() || extra.isBlank()) continue
                add(joinUrl(host, baseUrl, extra))
            }
        }.distinct().ifEmpty {
            throw NoPlayableStreamException("直播流地址为空")
        }
    }

    private fun joinUrl(
        host: String,
        baseUrl: String,
        extra: String
    ): String {
        return when {
            baseUrl.endsWith("?") || baseUrl.endsWith("&") -> "$host$baseUrl$extra"
            baseUrl.contains('?') -> "$host$baseUrl&$extra"
            else -> "$host$baseUrl?$extra"
        }
    }

    private companion object {
        const val ROOM_PLAY_INFO_ENDPOINT = "/xlive/app-room/v2/index/getRoomPlayInfo"
        const val ROOM_ENTRY_ACTION_ENDPOINT = "/xlive/app-room/v1/index/roomEntryAction"
        const val LIVE_RECORD_LIST_ENDPOINT = "/xlive/web-room/v1/videoService/GetOtherSliceList"
        const val LIVE_RECORD_STREAM_ENDPOINT = "/xlive/web-room/v1/videoService/GetUserSliceStream"
        const val LIVE_RECORD_DOWNLOAD_ENDPOINT = "/xlive/app-blink/v1/anchorVideo/AnchorVideoDownload"
        const val LIVE_RECORD_PUBLISHED_ENDPOINT = "/xlive/web-room/v1/videoService/GetPublishedList"
    }
}

private class NoPlayableStreamException(
    message: String
) : IllegalStateException(message)
