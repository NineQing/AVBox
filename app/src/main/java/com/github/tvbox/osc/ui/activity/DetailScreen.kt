@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package com.github.tvbox.osc.ui.activity

import android.content.res.Configuration
import android.widget.Toast
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.github.tvbox.osc.R
import com.github.tvbox.osc.bean.Movie
import com.github.tvbox.osc.player.ui.playerDim
import com.github.tvbox.osc.ui.components.LoadState
import com.github.tvbox.osc.ui.components.LoadStateBox
import com.github.tvbox.osc.ui.components.HomeBackdrop
import com.github.tvbox.osc.ui.components.VodCardMenu
import com.github.tvbox.osc.ui.components.rememberVodCardMenuState
import com.github.tvbox.osc.ui.player.PlayContainer
import com.github.tvbox.osc.ui.theme.AppThemeState
import kotlinx.coroutines.delay
import com.github.tvbox.osc.ui.page.jumpToSearch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScreen(activity: DetailActivity, vm: DetailViewModel) {
    val menuContext = LocalContext.current
    val pageState by vm.pageState.collectAsState()
    val full by vm.fullScreen.collectAsState()
    val rotating by vm.rotating.collectAsState()
    val revision by vm.revision.collectAsState()
    val playSignal by vm.playSignal.collectAsState()
    val playing by vm.playing.collectAsState()
    val toast by vm.toastEvent.collectAsState()
    val finish by vm.finishEvent.collectAsState()
    val vodMenu = rememberVodCardMenuState()

    val configuration = LocalConfiguration.current
    val isLandscapeNow = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val fullBox = if (rotating) isLandscapeNow else full
    val shortEdge = minOf(configuration.screenWidthDp, configuration.screenHeightDp).dp
    val longEdge = maxOf(configuration.screenWidthDp, configuration.screenHeightDp).dp
    val previewBoxHeight = (shortEdge * 9f / 16f)
        .coerceAtLeast(150.dp)
        .coerceAtMost(maxOf(150.dp, longEdge / 2))

    var container by remember { mutableStateOf<PlayContainer?>(null) }
    var backdropSeed by remember { mutableStateOf<Int?>(null) }
    val darkTheme = AppThemeState.isDark(isSystemInDarkTheme())
    val pageBackdrop by animateColorAsState(
        targetValue = backdropSeed
            ?.takeIf { !playing }
            ?.let { Color(HomeBackdrop.surfaceOf(it, darkTheme)) }
            ?: MaterialTheme.colorScheme.surfaceContainer,
        animationSpec = tween(200),
        label = "detailBackdrop",
    )

    LaunchedEffect(playing, playSignal) {
        if (container == null && (playing || playSignal > 0)) container = activity.ensurePlayContainer()
    }

    LaunchedEffect(vm) {
        vm.playbackCommands.collect { command ->
            val c = activity.playContainer ?: return@collect
            when (command) {
                is PlaybackCommand.StopForContentSwitch -> c.stopForContentSwitch()
                is PlaybackCommand.StopForSourceSwitch -> c.stopForSourceSwitch(command.tip)
                is PlaybackCommand.ClearSourceSwitchTip -> c.clearSourceSwitchTip()
                is PlaybackCommand.SetEpisodeSheetOpen -> c.setEpisodeSheetOpen(command.open)
                is PlaybackCommand.SelectQuality -> c.selectQuality(command.position)
            }
        }
    }

    LaunchedEffect(playSignal) {
        if (playSignal > 0) activity.playCurrent()
    }

    var musicWatch by remember { mutableStateOf(false) }
    var musicArmed by remember { mutableStateOf(false) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { musicWatch = true }
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { musicWatch = false }
    LaunchedEffect(playSignal) {
        if (playSignal > 0) musicArmed = true
    }
    LaunchedEffect(musicWatch, musicArmed) {
        if (!musicWatch || !musicArmed) return@LaunchedEffect
        while (true) {
            delay(300)
            if (!activity.musicPlaybackDetected()) continue
            if (!activity.handOffToMusicPlayer()) continue
            musicArmed = false
            return@LaunchedEffect
        }
    }

    LaunchedEffect(full) {
        activity.applyFullscreen(full)
    }

    LaunchedEffect(toast) {
        toast?.let {
            Toast.makeText(activity, it, Toast.LENGTH_SHORT).show()
            vm.clearToast()
        }
    }

    LaunchedEffect(finish) {
        if (finish) {
            vm.consumeFinish()
            activity.finish()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(pageBackdrop),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = when {
                    fullBox -> Modifier.fillMaxSize().background(Color.Black)
                    playing -> Modifier.fillMaxWidth()
                        .background(Color.Black)
                        .statusBarsPadding()
                        .height(previewBoxHeight)
                        .background(Color.Black)
                    else -> Modifier.fillMaxWidth().height(0.dp)
                },
            ) {
                val playerContainer = container
                if (playerContainer != null) {
                    AndroidView(
                        factory = { playerContainer },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                if (!fullBox && playing && pageState is DetailViewModel.PageState.Ready) {
                    Icon(
                        painter = painterResource(R.drawable.ic_player_expand),
                        contentDescription = stringResource(R.string.detail_fullscreen_play),
                        tint = Color.White.copy(alpha = 0.9f),
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(
                                end = 16.dp,
                                bottom = (16.dp + playerDim(R.dimen.vs_30) / 2 - 20.dp).coerceAtLeast(0.dp),
                            )
                            .size(40.dp)
                            .clickable { vm.onFullScreenToggleRequested(true, activity.playbackFacts()) }
                            .padding(9.dp),
                    )
                }
            }

            if (!fullBox) {
                when (val state = pageState) {
                    is DetailViewModel.PageState.Loading -> {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            ContainedLoadingIndicator()
                        }
                    }

                    is DetailViewModel.PageState.Empty -> {
                        Column(modifier = Modifier.fillMaxSize()) {
                            LoadStateBox(
                                state = LoadState.Empty,
                                emptyText = state.msg ?: stringResource(R.string.detail_empty_source),
                                errorText = "",
                                retryText = "",
                                modifier = Modifier.weight(1f),
                            )
                            SourceSection(vm, currentSourceName = null, revision = revision)
                        }
                    }

                    is DetailViewModel.PageState.Ready -> {
                        DetailContent(
                            activity = activity,
                            vm = vm,
                            revision = revision,
                            playing = playing,
                            backdropColor = pageBackdrop,
                            onBackdropSeed = { backdropSeed = it },
                            onCardLongClick = { vodMenu.show(it) },
                        )
                    }
                }
            }
        }
        if (!fullBox) {
            DetailTopScrim(modifier = Modifier.align(Alignment.TopCenter))
        }
    }

    EpisodeSheet(vm, revision, slideFromEnd = fullBox && isLandscapeNow)
    VodCardMenu(vodMenu) { menuContext.jumpToSearch(it) }
}
