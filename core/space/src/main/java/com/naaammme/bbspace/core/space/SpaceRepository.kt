package com.naaammme.bbspace.core.space

import android.text.format.DateFormat
import com.naaammme.bbspace.core.common.BiliConstants
import com.naaammme.bbspace.core.common.media.httpsImageUrl
import com.naaammme.bbspace.core.auth.AuthStore
import com.naaammme.bbspace.core.model.SpaceArchivePage
import com.naaammme.bbspace.core.model.SpaceHome
import com.naaammme.bbspace.core.model.SpaceOrderOption
import com.naaammme.bbspace.core.model.SpaceProfile
import com.naaammme.bbspace.core.model.SpaceRoute
import com.naaammme.bbspace.core.model.SpaceTab2
import com.naaammme.bbspace.core.model.SpaceTab2Item
import com.naaammme.bbspace.core.model.SpaceVideo
import com.naaammme.bbspace.core.model.RelationUser
import com.naaammme.bbspace.core.model.VideoTarget
import com.naaammme.bbspace.core.model.VideoTargetTool
import com.naaammme.bbspace.core.common.UserAgentBuilder
import com.naaammme.bbspace.infra.crypto.BiliSessionId
import com.naaammme.bbspace.infra.crypto.DeviceIdentity
import com.naaammme.bbspace.infra.crypto.WbiSigner
import com.naaammme.bbspace.infra.grpc.BiliGrpcClient
import com.naaammme.bbspace.infra.network.BiliRestClient
import com.naaammme.bbspace.infra.network.BiliRestParamBuilder
import com.naaammme.bbspace.infra.network.BiliRestProfile
import com.bapis.bilibili.relation.interfaces.Act
import com.bapis.bilibili.relation.interfaces.FollowingReq
import com.bapis.bilibili.relation.interfaces.ModifyRelationReply
import java.util.TimeZone
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import okhttp3.OkHttpClient
import okhttp3.Request
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

@Singleton
class SpaceRepository @Inject constructor(
    private val restClient: BiliRestClient,
    private val restParamBuilder: BiliRestParamBuilder,
    private val authStore: AuthStore,
    private val grpcClient: BiliGrpcClient,
    private val okHttpClient: OkHttpClient,
    private val deviceIdentity: DeviceIdentity
) {
    @Volatile
    private var wbiKeys: Pair<String, String>? = null
    @Volatile
    private var webBuvid: String? = null


    // 空间页专用的关系操作与空间数据请求放在同一职责模块，避免为薄封装增加独立模块。
    private suspend fun modifyRelationAct(fid: Long, act: Act) {
        val req = FollowingReq.newBuilder()
            .setFid(fid)
            .setAct(act)
            .setSource(31)
            .setSpmid("main.space.0.0")
            .setExtendContent("{\"entity\":\"user\",\"entity_id\":\"$fid\"}")
            .setActionId(BiliSessionId.polarisAction())
            .build()
        grpcClient.call(
            endpoint = MODIFY_RELATION_ENDPOINT,
            requestBytes = req.toByteArray(),
            parser = ModifyRelationReply.parser()
        )
    }

    suspend fun modifyRelation(fid: Long, isFollow: Boolean) {
        modifyRelationAct(fid, if (isFollow) Act.ACT_ADD_FOLLOWING else Act.ACT_DEL_FOLLOWING)
    }

    suspend fun modifyBlacklist(fid: Long, isBlack: Boolean) {
        modifyRelationAct(fid, if (isBlack) Act.ACT_ADD_BLACK else Act.ACT_DEL_BLACK)
    }

    suspend fun fetchHome(route: SpaceRoute): SpaceHome {
        val json = restClient.getSigned(
            url = "${BiliConstants.BASE_URL_APP}$SPACE_HOME_ENDPOINT",
            params = buildHomeParams(route),
            profile = BiliRestProfile.APP
        )
        return withContext(Dispatchers.Default) {
            parseHome(route, json)
        }
    }

    suspend fun fetchArchive(
        mid: Long,
        order: String,
        cursorAid: Long? = null,
        fromViewAid: Long? = null
    ): SpaceArchivePage {
        val json = restClient.getSigned(
            url = "${BiliConstants.BASE_URL_APP}$SPACE_ARCHIVE_CURSOR_ENDPOINT",
            params = buildArchiveParams(mid, order, cursorAid, fromViewAid),
            profile = BiliRestProfile.APP
        )
        return withContext(Dispatchers.Default) {
            parseArchivePage(json)
        }
    }

    suspend fun fetchSeasonVideos(
        mid: Long,
        seasonId: Long
    ): SpaceArchivePage {
        val json = restClient.getSigned(
            url = "${BiliConstants.BASE_URL_APP}$SPACE_SEASON_VIDEOS_ENDPOINT",
            params = buildCommonParams(null) + mapOf(
                "vmid" to mid.toString(),
                "season_id" to seasonId.toString(),
                "sort" to SORT_DESC,
                "ps" to PAGE_SIZE.toString(),
                "qn" to QN
            ),
            profile = BiliRestProfile.APP
        )
        return withContext(Dispatchers.Default) { parseArchivePage(json) }
    }

    suspend fun fetchSeriesVideos(
        mid: Long,
        seriesId: Long
    ): SpaceArchivePage {
        val json = restClient.getSigned(
            url = "${BiliConstants.BASE_URL_APP}$SPACE_SERIES_ENDPOINT",
            params = buildCommonParams(null) + mapOf(
                "vmid" to mid.toString(),
                "series_id" to seriesId.toString(),
                "sort" to SORT_DESC,
                "ps" to PAGE_SIZE.toString(),
                "qn" to QN
            ),
            profile = BiliRestProfile.APP
        )
        return withContext(Dispatchers.Default) { parseArchivePage(json) }
    }

    suspend fun fetchLivePlaybackVideos(
        mid: Long,
        page: Int = 1
    ): SpaceArchivePage {
        if (mid <= 0L) return SpaceArchivePage(emptyList(), emptyList(), hasMore = false)
        val keys = getWbiKeys()
        val params = buildMap {
            put("mid", mid.toString())
            put("ps", PAGE_SIZE.toString())
            put("pn", page.coerceAtLeast(1).toString())
            put("order", "pubdate")
            put("platform", "web")
            put("web_location", "1550101")
            put("order_avoided", "true")
            put("tid", "0")
        }
        val signed = WbiSigner.sign(params, keys.first, keys.second)
        val url = "${BiliConstants.BASE_URL_API}/x/space/wbi/arc/search?${WbiSigner.encodeQuery(signed)}"
        val json = executeWebJson(
            url = url,
            referer = "https://space.bilibili.com/$mid"
        )
        if (json.optInt("code") != 0) {
            throw IllegalStateException(json.optString("message").ifBlank { "加载直播回放失败" })
        }
        val pageData = json.optJSONObject("data")
            ?.optJSONObject("list")
            ?: return SpaceArchivePage(emptyList(), emptyList(), hasMore = false)
        val items = pageData.optJSONArray("vlist")
        val videos = buildList {
            if (items == null) return@buildList
            for (i in 0 until items.length()) {
                val item = items.optJSONObject(i) ?: continue
                if (!item.optBoolean("is_live_playback")) continue
                val aid = item.optLong("aid").takeIf { it > 0L } ?: continue
                val cid = item.optLong("cid").takeIf { it > 0L } ?: continue
                val title = item.optString("title").ifBlank { continue }
                val cover = item.optString("pic").httpsImageUrl().ifBlank { continue }
                add(
                    SpaceVideo(
                        aid = aid,
                        cid = cid,
                        target = VideoTarget.Ugc(
                            aid = aid,
                            cid = cid,
                            bvid = item.optString("bvid").ifBlank { null },
                            src = VideoTargetTool.space()
                        ),
                        title = title,
                        cover = cover,
                        author = item.optString("author").ifBlank { null },
                        categoryName = item.optString("tname").ifBlank { null },
                        durationSec = parseLengthToSeconds(item.optString("length")),
                        viewText = item.optString("play").ifBlank { "0" },
                        danmakuText = item.optString("video_review").ifBlank { null },
                        publishTimeText = item.optLong("created").takeIf { it > 0L }?.let(::formatPubDate),
                        isLivePlayback = true
                    )
                )
            }
        }
        val total = pageData.optInt("count")
        return SpaceArchivePage(
            videos = videos,
            orders = emptyList(),
            hasMore = total > page * PAGE_SIZE
        )
    }

    private suspend fun getWbiKeys(): Pair<String, String> {
        wbiKeys?.let { return it }
        getWebBuvid()
        val json = executeWebJson(
            url = "${BiliConstants.BASE_URL_API}$NAV_ENDPOINT",
            referer = "https://www.bilibili.com/"
        )
        val wbiImg = json.optJSONObject("data")?.optJSONObject("wbi_img")
            ?: throw IllegalStateException("获取 WBI 密钥失败")
        val keys = wbiImg.optString("img_url").substringAfterLast('/').substringBefore('.') to
                wbiImg.optString("sub_url").substringAfterLast('/').substringBefore('.')
        wbiKeys = keys
        return keys
    }

    private suspend fun getWebBuvid(): String? {
        webBuvid?.let { return it }
        val json = requestWebJson(
            url = "${BiliConstants.BASE_URL_API}/x/web-frontend/getbuvid",
            referer = "https://www.bilibili.com/",
            cookie = null
        )
        val value = json.optJSONObject("data")
            ?.optString("buvid")
            ?.takeIf(String::isNotBlank)
        webBuvid = value
        return value
    }

    private suspend fun executeWebJson(
        url: String,
        referer: String
    ): JSONObject {
        val cookieParts = buildList {
            getWebBuvid()?.takeIf(String::isNotBlank)?.let { add("buvid3=$it") }
            deviceIdentity.buvid.takeIf(String::isNotBlank)?.let { if (it != webBuvid) add("buvid=$it") }
        }
        return requestWebJson(
            url = url,
            referer = referer,
            cookie = cookieParts.joinToString("; ").takeIf(String::isNotBlank)
        )
    }

    private suspend fun requestWebJson(
        url: String,
        referer: String,
        cookie: String?
    ): JSONObject = withContext(Dispatchers.IO) {
        val builder = Request.Builder()
            .url(url)
            .get()
            .addHeader("accept", "application/json, text/plain, */*")
            .addHeader("origin", BiliConstants.BASE_URL_API.replace("api.", "www."))
            .addHeader("referer", referer)
            .addHeader("user-agent", UserAgentBuilder.buildWebUserAgent())
        if (!cookie.isNullOrBlank()) {
            builder.addHeader("cookie", cookie)
        }
        okHttpClient.newCall(builder.build()).execute().use { response ->
            JSONObject(response.body?.string() ?: throw IllegalStateException("空响应"))
        }
    }

    private fun parseLengthToSeconds(length: String): Long {
        val parts = length.split(':').mapNotNull { it.toLongOrNull() }
        if (parts.isEmpty()) return 0L
        return when (parts.size) {
            1 -> parts[0]
            2 -> parts[0] * 60 + parts[1]
            3 -> parts[0] * 3600 + parts[1] * 60 + parts[2]
            else -> 0L
        }
    }

    private fun buildHomeParams(route: SpaceRoute): Map<String, String> {
        val base = buildCommonParams(route.fromViewAid)
        return base + buildMap {
            if (route.mid > 0L) {
                put("vmid", route.mid.toString())
                put("from", route.from.toString())
            } else {
                val name = route.name?.takeIf(String::isNotBlank)
                    ?: throw IllegalArgumentException("space name is required when mid <= 0")
                put("name", name)
            }
        }
    }

    private fun buildArchiveParams(
        mid: Long,
        order: String,
        cursorAid: Long?,
        fromViewAid: Long?
    ): Map<String, String> {
        return buildCommonParams(fromViewAid) + buildMap {
            put("vmid", mid.toString())
            put("ps", PAGE_SIZE.toString())
            put("order", order.ifBlank { DEFAULT_ORDER })
            put("sort", SORT_DESC)
            put("include_cursor", FALSE)
            cursorAid?.takeIf { it > 0L }?.let { put("aid", it.toString()) }
        }
    }

    private fun buildCommonParams(fromViewAid: Long?): Map<String, String> {
        val ts = System.currentTimeMillis() / 1000L
        return restParamBuilder.app(BiliRestProfile.APP, ts, authStore.accessToken) + buildMap {
            put("client_attr", ZERO)
            put("fnval", FNVAL)
            put("fnver", FNVER)
            put("force_host", ZERO)
            put("fourk", ZERO)
            put("local_time", localTime().toString())
            put("player_extra_content", PLAYER_EXTRA_CONTENT)
            put("player_net", PLAYER_NET)
            put("qn", QN)
            put("qn_policy", QN_POLICY)
            put("voice_balance", VOICE_BALANCE)
            fromViewAid?.takeIf { it > 0L }?.let { put("from_view_aid", it.toString()) }
        }
    }

    private fun parseHome(
        route: SpaceRoute,
        json: JSONObject
    ): SpaceHome {
        val data = json.optJSONObject("data")
            ?: throw IllegalStateException("个人空间首页缺少 data")
        val archive = data.optJSONObject("archive")
        val orders = parseOrders(archive?.optJSONArray("order"))
        val defaultOrder = orders.firstOrNull()?.value ?: DEFAULT_ORDER
        val videos = parseVideos(archive?.optJSONArray("item"))
        val total = archive?.optInt("count") ?: videos.size
        return SpaceHome(
            profile = parseProfile(route, data),
            bannerUrl = data.optJSONObject("images")
                ?.optString("imgUrl")
                .orEmpty()
                .httpsImageUrl()
                .ifBlank { null },
            videos = videos,
            orders = orders,
            defaultOrder = defaultOrder,
            hasMore = total > videos.size,
            tabs = parseTabs(data.optJSONArray("tab2"))
        )
    }

    private fun parseTabs(arr: JSONArray?): List<SpaceTab2> {
        if (arr == null || arr.length() == 0) return emptyList()
        return buildList {
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                val title = item.optString("title").ifBlank { continue }
                val param = item.optString("param").ifBlank { continue }
                val subItems = buildList {
                    val items = item.optJSONArray("items") ?: return@buildList
                    for (j in 0 until items.length()) {
                        val sub = items.optJSONObject(j) ?: continue
                        val subTitle = sub.optString("title").ifBlank { continue }
                        val subParam = sub.optString("param").ifBlank { continue }
                        add(
                            SpaceTab2Item(
                                title = subTitle,
                                param = subParam,
                                seasonId = sub.optLong("season_id").takeIf { it > 0L },
                                seriesId = sub.optLong("series_id").takeIf { it > 0L }
                            )
                        )
                    }
                }
                add(
                    SpaceTab2(
                        title = title,
                        param = param,
                        items = subItems
                    )
                )
            }
        }
    }

    private fun parseArchivePage(json: JSONObject): SpaceArchivePage {
        val data = json.optJSONObject("data")
            ?: throw IllegalStateException("个人空间投稿列表缺少 data")
        return SpaceArchivePage(
            videos = parseVideos(data.optJSONArray("item")),
            orders = parseOrders(data.optJSONArray("order")),
            hasMore = data.optBoolean("has_next")
        )
    }

    private fun parseProfile(
        route: SpaceRoute,
        data: JSONObject
    ): SpaceProfile {
        val card = data.optJSONObject("card")
        val archive = data.optJSONObject("archive")
        val article = data.optJSONObject("article")
        val season = data.optJSONObject("ugc_season")
        val series = data.optJSONObject("series")
        val vip = card?.optJSONObject("vip")
        val level = card?.optJSONObject("level_info")?.optInt("current_level") ?: 0
        val likeCount = card?.optJSONObject("likes")?.optLong("like_num") ?: 0L
        val name = card?.optString("name")
            ?.takeIf(String::isNotBlank)
            ?: route.name?.takeIf(String::isNotBlank)
            ?: "个人空间"
        return SpaceProfile(
            mid = card.optLongCompat("mid"),
            name = name,
            face = card?.optString("face").orEmpty().httpsImageUrl().ifBlank { null },
            sign = card?.optString("sign").orEmpty(),
            level = level,
            vipLabel = vip?.takeIf { it.optInt("vipStatus") > 0 }
                ?.optJSONObject("label")
                ?.optString("text")
                ?.takeIf(String::isNotBlank),
            fansCount = card?.optLong("fans") ?: 0L,
            followingCount = card?.optLong("attention") ?: 0L,
            likeCount = likeCount,
            videoCount = archive?.optInt("count") ?: 0,
            articleCount = article?.optInt("count") ?: 0,
            seasonCount = season?.optInt("count") ?: 0,
            seriesCount = series?.optInt("count") ?: 0,
            tags = parseTagTitles(card?.optJSONArray("space_tag")),
            relation = data.optInt("relation", -999),
            guestRelation = data.optInt("guest_relation", -999)
        )
    }

    private fun parseOrders(arr: JSONArray?): List<SpaceOrderOption> {
        val orders = buildList {
            if (arr == null) return@buildList
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                val value = item.optString("value").ifBlank { continue }
                add(
                    SpaceOrderOption(
                        title = item.optString("title").ifBlank { value },
                        value = value
                    )
                )
            }
        }
        return orders.ifEmpty { DEFAULT_ORDERS }
    }

    private fun parseVideos(arr: JSONArray?): List<SpaceVideo> {
        if (arr == null || arr.length() == 0) return emptyList()
        return buildList {
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                val uri = item.optString("uri")
                val aid = item.optLongCompat("param")
                    .takeIf { it > 0L }
                    ?: VideoTargetTool.aid(uri)
                    ?: continue
                val cid = item.optLong("first_cid")
                    .takeIf { it > 0L }
                    ?: VideoTargetTool.cid(uri)
                    ?: continue
                val title = item.optString("title").ifBlank { continue }
                val cover = item.optString("cover").httpsImageUrl().ifBlank { continue }
                val target = VideoTarget.Ugc(
                    aid = aid,
                    cid = cid,
                    bvid = item.optString("bvid").ifBlank { null }
                        ?: VideoTargetTool.bvid(uri),
                    src = VideoTargetTool.space()
                )
                add(
                    SpaceVideo(
                        aid = aid,
                        cid = cid,
                        target = target,
                        title = title,
                        cover = cover,
                        author = item.optString("author").ifBlank { null },
                        categoryName = item.optString("tname").ifBlank { null },
                        durationSec = item.optLong("duration").coerceAtLeast(0L),
                        viewText = item.optString("view_content").ifBlank {
                            item.optLong("play").toString()
                        },
                        danmakuText = item.optLong("danmaku")
                            .takeIf { it > 0L }
                            ?.toString(),
                        publishTimeText = item.optString("publish_time_text").ifBlank {
                            item.optLong("ctime")
                                .takeIf { it > 0L }
                                ?.let(::formatPubDate)
                        },
                        isLivePlayback = item.optBoolean("is_live_playback") || item.optInt("is_live_playback") == 1
                    )
                )
            }
        }
    }

    private fun parseTagTitles(arr: JSONArray?): List<String> {
        if (arr == null || arr.length() == 0) return emptyList()
        return buildList {
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                val title = item.optString("title").ifBlank { continue }
                add(title)
            }
        }.distinct()
    }

    private fun formatPubDate(ts: Long): String {
        return DateFormat.format("yyyy-MM-dd", ts * 1000).toString()
    }

    private fun localTime(): Int {
        return TimeZone.getDefault().rawOffset / 3_600_000
    }

    private fun JSONObject?.optLongCompat(key: String): Long {
        this ?: return 0L
        return optLong(key).takeIf { it != 0L }
            ?: optString(key).toLongOrNull()
            ?: 0L
    }

    suspend fun fetchRelationUsers(vmid: Long, page: Int, isFans: Boolean): List<RelationUser> {
        val ts = System.currentTimeMillis() / 1000L
        val endpoint = if (isFans) FANS_ENDPOINT else FOLLOWINGS_ENDPOINT
        val params = restParamBuilder.app(BiliRestProfile.APP, ts, authStore.accessToken) + buildMap {
            put("vmid", vmid.toString())
            put("pn", page.toString())
            put("ps", PAGE_SIZE.toString())
            put("scene", "1")
        }
        val json = restClient.getSigned(
            url = "${BiliConstants.BASE_URL_API}$endpoint",
            params = params,
            profile = BiliRestProfile.APP
        )
        return withContext(Dispatchers.Default) {
            parseRelationUsers(json)
        }
    }

    private fun parseRelationUsers(json: JSONObject): List<RelationUser> {
        val data = json.optJSONObject("data") ?: return emptyList()
        val list = data.optJSONArray("list") ?: return emptyList()
        return buildList {
            for (i in 0 until list.length()) {
                val item = list.optJSONObject(i) ?: continue
                val mid = item.optLongCompat("mid")
                if (mid <= 0L) continue
                val uname = item.optString("uname").orEmpty()
                val face = item.optString("face").orEmpty().httpsImageUrl()
                val sign = item.optString("sign").orEmpty()
                val vipObj = item.optJSONObject("vip")
                val isVip = vipObj?.optInt("vipStatus") ?: 0 > 0
                val vipLabel = vipObj
                    ?.takeIf { isVip }
                    ?.optJSONObject("label")
                    ?.optString("text")
                    ?.takeIf(String::isNotBlank)
                add(
                    RelationUser(
                        mid = mid,
                        uname = uname,
                        face = face,
                        sign = sign,
                        isVip = isVip,
                        vipLabel = vipLabel
                    )
                )
            }
        }
    }
    private companion object {
        const val SPACE_HOME_ENDPOINT = "/x/v2/space"
        const val SPACE_ARCHIVE_CURSOR_ENDPOINT = "/x/v2/space/archive/cursor"
        const val SPACE_SEASON_VIDEOS_ENDPOINT = "/x/v2/space/season/videos"
        const val SPACE_SERIES_ENDPOINT = "/x/v2/space/series"
        const val NAV_ENDPOINT = "/x/web-interface/nav"
        const val FOLLOWINGS_ENDPOINT = "/x/relation/followings"
        const val FANS_ENDPOINT = "/x/relation/fans"
        const val MODIFY_RELATION_ENDPOINT = "bilibili.relation.interface.v1.RelationInterface/ModifyRelation"
        const val PAGE_SIZE = 20
        const val DEFAULT_ORDER = "pubdate"
        const val SORT_DESC = "desc"
        const val FALSE = "false"
        const val ZERO = "0"
        const val FNVAL = "272"
        const val FNVER = "0"
        const val PLAYER_NET = "1"
        const val PLAYER_EXTRA_CONTENT = "{\"short_edge\":\"1080\",\"long_edge\":\"1920\"}"
        const val QN = "80"
        const val QN_POLICY = "0"
        const val VOICE_BALANCE = "1"
        val DEFAULT_ORDERS = listOf(
            SpaceOrderOption(title = "最新发布", value = "pubdate"),
            SpaceOrderOption(title = "最多播放", value = "click")
        )
    }
}
