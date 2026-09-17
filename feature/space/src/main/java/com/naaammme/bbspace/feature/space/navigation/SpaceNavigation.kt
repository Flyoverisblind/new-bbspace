package com.naaammme.bbspace.feature.space.navigation

import android.net.Uri
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.naaammme.bbspace.core.model.LiveRecordItem
import com.naaammme.bbspace.core.model.LiveRoute
import com.naaammme.bbspace.core.model.SpaceRoute
import com.naaammme.bbspace.core.model.SpaceRouteTool
import com.naaammme.bbspace.core.model.VideoTarget
import com.naaammme.bbspace.feature.space.SpaceScreen
import com.naaammme.bbspace.feature.space.record.LiveRecordPlayerScreen

const val SPACE_MID_ARG = "mid"
const val SPACE_NAME_ARG = "name"
const val SPACE_FROM_ARG = "from"
const val SPACE_FROM_VIEW_AID_ARG = "fromViewAid"

private const val SPACE_ROUTE =
    "space/{$SPACE_MID_ARG}" +
            "?$SPACE_NAME_ARG={$SPACE_NAME_ARG}" +
            "&$SPACE_FROM_ARG={$SPACE_FROM_ARG}" +
            "&$SPACE_FROM_VIEW_AID_ARG={$SPACE_FROM_VIEW_AID_ARG}"

fun NavController.navigateToSpace(route: SpaceRoute) {
    navigate(
        "space/${route.mid}" +
                "?$SPACE_NAME_ARG=${Uri.encode(route.name.orEmpty())}" +
                "&$SPACE_FROM_ARG=${route.from}" +
                "&$SPACE_FROM_VIEW_AID_ARG=${route.fromViewAid ?: -1L}"
    )
}

fun NavGraphBuilder.spaceScreen(
    onBack: () -> Unit,
    onOpenVideo: (VideoTarget) -> Unit,
    onOpenDynamic: (String) -> Unit = {},
    onOpenLive: (LiveRoute) -> Unit = {},
    onOpenLiveRecord: (LiveRecordItem) -> Unit = {},
    onOpenIm: ((Long, String, String?) -> Unit)? = null,
    onOpenRelation: (Long, Int) -> Unit = { _, _ -> }
) {
    composable(
        route = SPACE_ROUTE,
        arguments = listOf(
            navArgument(SPACE_MID_ARG) {
                type = NavType.LongType
                defaultValue = 0L
            },
            navArgument(SPACE_NAME_ARG) {
                type = NavType.StringType
                defaultValue = ""
            },
            navArgument(SPACE_FROM_ARG) {
                type = NavType.IntType
                defaultValue = SpaceRouteTool.FROM_DEFAULT
            },
            navArgument(SPACE_FROM_VIEW_AID_ARG) {
                type = NavType.LongType
                defaultValue = -1L
            }
        )
    ) {
        SpaceScreen(
            onBack = onBack,
            onOpenVideo = onOpenVideo,
            onOpenDynamic = onOpenDynamic,
            onOpenLive = onOpenLive,
            onOpenLiveRecord = onOpenLiveRecord,
            onOpenIm = onOpenIm,
            onOpenRelation = onOpenRelation
        )
    }
}

private const val LIVE_RECORD_URL_ARG = "url"
private const val LIVE_RECORD_TITLE_ARG = "title"

fun NavController.navigateToLiveRecordPlayer(url: String, title: String?) {
    navigate(
        "live_record_player?$LIVE_RECORD_URL_ARG=${Uri.encode(url)}" +
                "&$LIVE_RECORD_TITLE_ARG=${Uri.encode(title.orEmpty())}"
    )
}

fun NavGraphBuilder.liveRecordPlayerScreen(onBack: () -> Unit) {
    composable(
        route = "live_record_player?$LIVE_RECORD_URL_ARG={$LIVE_RECORD_URL_ARG}" +
                "&$LIVE_RECORD_TITLE_ARG={$LIVE_RECORD_TITLE_ARG}",
        arguments = listOf(
            navArgument(LIVE_RECORD_URL_ARG) { type = NavType.StringType; defaultValue = "" },
            navArgument(LIVE_RECORD_TITLE_ARG) { type = NavType.StringType; defaultValue = "" }
        )
    ) { entry ->
        LiveRecordPlayerScreen(
            title = entry.arguments?.getString(LIVE_RECORD_TITLE_ARG),
            url = entry.arguments?.getString(LIVE_RECORD_URL_ARG).orEmpty(),
            onBack = onBack
        )
    }
}

fun NavController.navigateToSpaceRelation(vmid: Long, initialTab: Int) {
    navigate("space_relation/$vmid?initialTab=$initialTab")
}

fun NavGraphBuilder.spaceRelationScreen(
    onBack: () -> Unit,
    onOpenSpace: (SpaceRoute) -> Unit
) {
    composable(
        route = "space_relation/{vmid}?initialTab={initialTab}",
        arguments = listOf(
            navArgument("vmid") {
                type = NavType.LongType
                defaultValue = 0L
            },
            navArgument("initialTab") {
                type = NavType.IntType
                defaultValue = 0
            }
        )
    ) {
        com.naaammme.bbspace.feature.space.relation.RelationScreen(
            onBack = onBack,
            onOpenSpace = onOpenSpace
        )
    }
}
