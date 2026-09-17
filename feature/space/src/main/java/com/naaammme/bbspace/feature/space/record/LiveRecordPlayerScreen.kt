package com.naaammme.bbspace.feature.space.record

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.naaammme.bbspace.core.auth.AuthStore
import com.naaammme.bbspace.core.designsystem.component.StateMessageCard
import com.naaammme.bbspace.core.model.LiveRecordItem
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

@Composable
fun LiveRecordPlayerScreen(
    onBack: () -> Unit,
    viewModel: LiveRecordPlayerViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val webView = remember(context) { createWebView(context) }

    LaunchedEffect(state.selected?.recordId) {
        val selected = state.selected ?: return@LaunchedEffect
        syncLiveCookies(context)
        webView.loadUrl(recordPlayUrl(selected))
    }

    DisposableEffect(webView) {
        onDispose {
            webView.stopLoading()
            webView.destroy()
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .background(Color.Black)
        ) {
            AndroidView(
                factory = { webView },
                modifier = Modifier.fillMaxSize()
            )
            IconButton(
                onClick = onBack,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(8.dp)
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "返回",
                    tint = Color.White
                )
            }
        }

        when {
            state.loading && state.records.isEmpty() -> {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            }

            !state.error.isNullOrBlank() && state.records.isEmpty() -> {
                StateMessageCard(
                    text = state.error.orEmpty(),
                    isError = true,
                    actionText = "重试",
                    onAction = viewModel::load,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                )
            }

            else -> {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .navigationBarsPadding(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    item(key = "record_list_title") {
                        Text(
                            text = "直播回放列表",
                            style = MaterialTheme.typography.titleMedium
                        )
                    }
                    items(
                        items = state.records,
                        key = { "record_${it.recordId}" }
                    ) { item ->
                        val index = state.records.indexOf(item)
                        LiveRecordRow(
                            item = item,
                            selected = index == state.selectedIndex,
                            onClick = { viewModel.select(index) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LiveRecordRow(
    item: LiveRecordItem,
    selected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !selected, onClick = onClick)
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .width(112.dp)
                .aspectRatio(16f / 10f)
                .clip(MaterialTheme.shapes.large)
                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
        ) {
            com.naaammme.bbspace.core.designsystem.component.CoverImage(
                url = item.cover,
                contentDescription = item.title,
                modifier = Modifier.fillMaxSize()
            )
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = item.title,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            item.durationSec?.takeIf { it > 0L }?.let { seconds ->
                Text(
                    text = formatLiveRecordDuration(seconds),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        if (selected) {
            Text(
                text = "当前播放",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
private fun createWebView(context: android.content.Context): WebView {
    return WebView(context).apply {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.mediaPlaybackRequiresUserGesture = false
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true
        webViewClient = WebViewClient()
    }
}



private fun syncLiveCookies(context: android.content.Context) {
    val entryPoint = EntryPointAccessors.fromApplication(
        context.applicationContext,
        LiveRecordCookieEntryPoint::class.java
    )
    val cookies = entryPoint.authStore().getSavedCredential()?.cookies.orEmpty()
    if (cookies.isEmpty()) return
    val cookieManager = CookieManager.getInstance()
    cookieManager.setAcceptCookie(true)
    val cookieText = cookies.joinToString("; ") { "${it.name}=${it.value}" }
    cookieManager.setCookie("https://live.bilibili.com", "$cookieText; path=/")
    cookieManager.setCookie("https://www.bilibili.com", "$cookieText; path=/")
    cookieManager.flush()
}

private fun recordPlayUrl(item: LiveRecordItem): String {
    val params = buildList {
        if (item.roomId > 0L) add("room_id=${item.roomId}")
        item.liveKey?.takeIf(String::isNotBlank)?.let { add("live_key=$it") }
        add("record_id=${item.recordId}")
        add("replay_id=${item.recordId}")
        if (item.uid > 0L) {
            add("uid=${item.uid}")
            add("live_uid=${item.uid}")
        }
    }.joinToString("&")
    return "https://live.bilibili.com/p/html/live-app-playback/index.html?$params"
}

private fun formatLiveRecordDuration(seconds: Long): String {
    val m = seconds / 60
    val s = seconds % 60
    val h = m / 60
    return if (h > 0) {
        String.format(java.util.Locale.ROOT, "%d:%02d:%02d", h, m % 60, s)
    } else {
        String.format(java.util.Locale.ROOT, "%d:%02d", m, s)
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface LiveRecordCookieEntryPoint {
    fun authStore(): AuthStore
}
