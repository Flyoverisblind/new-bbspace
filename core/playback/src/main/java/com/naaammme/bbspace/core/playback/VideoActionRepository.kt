package com.naaammme.bbspace.core.playback

import com.naaammme.bbspace.core.auth.AuthStore
import com.naaammme.bbspace.core.common.BiliConstants
import com.naaammme.bbspace.core.favorite.FavoriteRepository
import com.naaammme.bbspace.infra.network.BiliRestClient
import com.naaammme.bbspace.infra.network.BiliRestParamBuilder
import com.naaammme.bbspace.infra.network.BiliRestProfile
import javax.inject.Inject
import javax.inject.Singleton

data class VideoTripleResult(
    val like: Boolean,
    val coin: Boolean,
    val fav: Boolean
)

data class VideoActionRemoteState(
    val liked: Boolean = false,
    val coined: Boolean = false,
    val favorited: Boolean = false
)

@Singleton
class VideoActionRepository @Inject constructor(
    private val restClient: BiliRestClient,
    private val restParamBuilder: BiliRestParamBuilder,
    private val authStore: AuthStore,
    private val favoriteRepository: FavoriteRepository
) {
    suspend fun like(aid: Long, liked: Boolean): Boolean {
        requireLogin()
        val json = restClient.postSigned(
            url = "${BiliConstants.BASE_URL_APP}/x/v2/view/like",
            params = commonParams() + mapOf(
                "aid" to aid.toString(),
                "like" to if (liked) "0" else "1"
            )
        )
        return json.optInt("code") == 0
    }

    suspend fun coin(aid: Long, multiply: Int = 2, selectLike: Boolean = true): Boolean {
        requireLogin()
        val json = restClient.postSigned(
            url = "${BiliConstants.BASE_URL_APP}/x/v2/view/coin/add",
            params = commonParams() + mapOf(
                "aid" to aid.toString(),
                "multiply" to multiply.coerceIn(1, 2).toString(),
                "select_like" to if (selectLike) "1" else "0"
            )
        )
        return json.optInt("code") == 0
    }

    suspend fun favorite(aid: Long, fav: Boolean): Boolean {
        requireLogin()
        val folder = favoriteRepository.fetchMyFavorites().folders.firstOrNull() ?: return false
        val json = restClient.postSigned(
            url = "${BiliConstants.BASE_URL_API}/medialist/gateway/coll/resource/deal",
            params = commonParams() + buildMap {
                put("rid", aid.toString())
                put("type", "2")
                put("add_media_ids", if (fav) folder.fid.toString() else "")
                put("del_media_ids", if (fav) "" else folder.fid.toString())
            }
        )
        return json.optInt("code") == 0
    }

    suspend fun triple(aid: Long): VideoTripleResult {
        requireLogin()
        val json = restClient.postSigned(
            url = "${BiliConstants.BASE_URL_APP}/x/v2/view/like/triple",
            params = commonParams() + mapOf("aid" to aid.toString())
        )
        val data = json.optJSONObject("data")
        return VideoTripleResult(
            like = data?.optBoolean("like") == true,
            coin = data?.optBoolean("coin") == true,
            fav = data?.optBoolean("fav") == true
        )
    }

    suspend fun fetchActionState(aid: Long): VideoActionRemoteState {
        if (aid <= 0L) return VideoActionRemoteState()
        val base = commonParams() + mapOf("aid" to aid.toString())
        val liked = runCatching {
            restClient.getSigned(
                url = "${BiliConstants.BASE_URL_API}/x/web-interface/archive/has/like",
                params = base
            ).optInt("data") == 1
        }.getOrDefault(false)
        val coined = runCatching {
            val multiply = restClient.getSigned(
                url = "${BiliConstants.BASE_URL_API}/x/web-interface/archive/coins",
                params = base
            ).optJSONObject("data")?.optInt("multiply") ?: 0
            multiply > 0
        }.getOrDefault(false)
        val favorited = runCatching {
            restClient.getSigned(
                url = "${BiliConstants.BASE_URL_API}/x/v2/fav/video/favoured",
                params = base
            ).optJSONObject("data")?.optBoolean("favoured") == true
        }.getOrDefault(false)
        return VideoActionRemoteState(liked = liked, coined = coined, favorited = favorited)
    }

    private fun commonParams(): Map<String, String> {
        val ts = System.currentTimeMillis() / 1000L
        return restParamBuilder.app(BiliRestProfile.APP, ts, authStore.accessToken)
    }

    private fun requireLogin() {
        check(authStore.accessToken.isNotBlank()) { "请先登录" }
    }
}
