package com.github.tvbox.osc.ui.activity

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.github.tvbox.osc.R
import com.github.tvbox.osc.ui.components.HeroSpotlightFadeStops
import com.github.tvbox.osc.ui.components.HomeBackdrop
import com.github.tvbox.osc.ui.components.ImagePalette
import com.github.tvbox.osc.ui.components.TopBarActionBox
import com.github.tvbox.osc.ui.components.VodPoster
import com.github.tvbox.osc.ui.components.heroSpotlightHeight
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val DetailTopScrimAlpha = 0.32f

private val DetailTopScrimExtra = 24.dp

private val HeroCaptionHorizontalPadding = 24.dp

private val HeroCaptionBottomPadding = 32.dp

private val HeroCaptionSpacing = 6.dp

private val MetaSeparator = " · "

private val TypeSeparators = Regex("[,，、/|;；]+")

@Composable
internal fun DetailTopScrim(modifier: Modifier = Modifier) {
    val statusTop = with(LocalDensity.current) { WindowInsets.statusBars.getTop(this).toDp() }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(statusTop + DetailTopScrimExtra)
            .background(
                Brush.verticalGradient(
                    0f to Color.Black.copy(alpha = DetailTopScrimAlpha),
                    1f to Color.Transparent,
                ),
            ),
    )
}

@Composable
internal fun DetailHero(
    title: String,
    picture: String?,
    year: Int,
    area: String?,
    type: String?,
    collected: Boolean,
    backdropColor: Color,
    onBackdropSeed: (Int?) -> Unit,
    onBack: () -> Unit,
    onCollect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    LaunchedEffect(picture) {
        if (HomeBackdrop.has(picture)) onBackdropSeed(HomeBackdrop.seedOf(picture))
    }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(heroSpotlightHeight(LocalConfiguration.current.screenHeightDp)),
    ) {
        VodPoster(
            name = title,
            pic = picture,
            preferLarge = true,
            modifier = Modifier.fillMaxSize(),
            onImage = { image ->
                if (!HomeBackdrop.has(picture)) {
                    scope.launch {
                        val seed = withContext(Dispatchers.Default) { ImagePalette.seedOf(image) }
                        HomeBackdrop.put(picture, seed)
                        onBackdropSeed(seed)
                    }
                }
            },
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        *HeroSpotlightFadeStops
                            .map { (position, alpha) -> position to backdropColor.copy(alpha = alpha) }
                            .toTypedArray(),
                    ),
                ),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TopBarActionBox(
                iconRes = R.drawable.ic_arrow_left,
                contentDescription = stringResource(R.string.common_back),
                onClick = onBack,
            )
            Spacer(Modifier.weight(1f))
            TopBarActionBox(
                iconRes = if (collected) R.drawable.ic_tab_collect_filled else R.drawable.ic_tab_collect,
                contentDescription = stringResource(
                    if (collected) R.string.detail_uncollect else R.string.detail_collect,
                ),
                onClick = onCollect,
                tint = if (collected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
        }
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(
                    start = HeroCaptionHorizontalPadding,
                    end = HeroCaptionHorizontalPadding,
                    bottom = HeroCaptionBottomPadding,
                ),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight(800),
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
            )
            val yearArea = listOfNotNull(
                year.takeIf { it > 0 }?.toString(),
                area?.takeIf { it.isNotBlank() },
            ).joinToString(MetaSeparator)
            if (yearArea.isNotEmpty()) {
                DetailHeroMetaLine(yearArea)
            }
            val types = type.orEmpty()
                .split(TypeSeparators)
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .joinToString(MetaSeparator)
            if (types.isNotEmpty()) {
                DetailHeroMetaLine(types)
            }
        }
    }
}

@Composable
private fun DetailHeroMetaLine(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Medium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = HeroCaptionSpacing),
    )
}
