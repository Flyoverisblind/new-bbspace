package com.naaammme.bbspace.core.playback

import com.naaammme.bbspace.core.auth.AuthStore
import com.naaammme.bbspace.core.common.BiliConstants
import com.naaammme.bbspace.infra.network.BiliRestClient
import com.naaammme.bbspace.infra.network.BiliRestParamBuilder
import com.naaammme.bbspace.infra.network.BiliRestProfile
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class VideoOnlineRepository @Inject constructor(
    private val restClient: BiliRestClient,
    private val restParamBuilder: BiliRestParamBuilder,
    private val authStore: AuthStore
) {
    suspend fun fetchOnlineTotal(
        aid: Long,
        cid: Long,
        bvid: String?
    ): Long {
        if (aid <= 0L || cid <= 0L) return 0L
        val ts = System.currentTimeMillis() / 1000L
        val params = restParamBuilder.app(BiliRestProfile.APP, ts, authStore.accessToken) + buildMap {
            put("aid", aid.toString())
            put("cid", cid.toString())
            bvid?.takeIf(String::isNotBlank)?.let { put("bvid", it) }
        }
        val json = restClient.getSigned(
            url = "${BiliConstants.BASE_URL_API}/x/player/online/total",
            params = params,
            profile = BiliRestProfile.APP
        )
        val data = json.optJSONObject("data") ?: return 0L
        return data.optString("total").toLongOrNull()
            ?: data.optLong("total").takeIf { it > 0L }
            ?: data.optString("count").toLongOrNull()
            ?: 0L
    }
}
