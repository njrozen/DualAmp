package com.dualamp.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.snapping.SnapPosition
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Equalizer
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.dualamp.ui.Accent
import com.dualamp.ui.Surface1
import com.dualamp.ui.Surface3
import com.dualamp.ui.TextDim
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sign

/** How far apart the covers sit, as a fraction of the cover size. */
private const val SLOT = 0.40f
/** Distance of the first cover on each side of the centre one. */
private const val FIRST_STEP = 0.60f
/** Spacing between the stacked covers further out. */
private const val STACK_STEP = 0.22f

/** Horizontal on-screen position of a cover, relative to the centre, from its slot distance [f]. */
private fun visualOffset(f: Float, cover: Float): Float {
    val af = abs(f)
    return if (af <= 1f) f * FIRST_STEP * cover else sign(f) * (FIRST_STEP + (af - 1f) * STACK_STEP) * cover
}

/**
 * iPod-style Cover Flow: the selected cover faces you in the middle, the rest are turned and
 * stacked to either side with a reflection underneath. Swipe or use the D-pad to flip through;
 * tap the centre cover to open it (or play it, for a song). Play/Shuffle actions sit above.
 */
@Composable
internal fun CoverFlow(
    data: PageData,
    state: LazyListState,
    cursor: Int,
    padActive: Boolean,
    nowPlaying: Long?,
    onCursor: (Int) -> Unit,
    onActivate: (Int, Entry) -> Unit,
    onLongPress: (Int, Entry) -> Unit,
) {
    // Covers in the carousel, with their index in data.entries.
    val covers = remember(data) { data.entries.withIndex().filter { !it.value.fullWidth } }
    val actions = remember(data) { data.entries.withIndex().filter { it.value.fullWidth } }

    Column(Modifier.fillMaxSize()) {
        data.hero?.let { h ->
            Text(
                listOf(h.subtitle, h.detail).filter { it.isNotBlank() }.joinToString(" · "),
                color = TextDim, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            )
        }
        if (actions.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            ) {
                actions.forEach { (i, e) ->
                    Text(
                        e.title,
                        style = MaterialTheme.typography.labelLarge,
                        color = Color.White,
                        modifier = Modifier
                            .highlight(padActive && cursor == i)
                            .background(Surface3, RoundedCornerShape(50))
                            .clickable { onActivate(i, e) }
                            .padding(horizontal = 16.dp, vertical = 6.dp),
                    )
                }
            }
        }

        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val density = LocalDensity.current
            val coverDp = min(maxHeight.value * 0.58f, maxWidth.value * 0.42f).dp
            val coverPx = with(density) { coverDp.toPx() }
            val slotDp = coverDp * SLOT
            val sidePad = (maxWidth - slotDp) / 2

            // Index (within covers) of the cover nearest the centre.
            val centered by remember(state) {
                derivedStateOf {
                    val info = state.layoutInfo
                    val mid = (info.viewportStartOffset + info.viewportEndOffset) / 2f
                    info.visibleItemsInfo.minByOrNull { abs(it.offset + it.size / 2f - mid) }?.index ?: 0
                }
            }
            val scope = rememberCoroutineScope()
            val latestCursor by rememberUpdatedState(cursor)
            val latestPad by rememberUpdatedState(padActive)

            // Touch scrolling moves the cursor; gamepad moves the carousel.
            LaunchedEffect(centered, covers) {
                if (!latestPad) covers.getOrNull(centered)?.let { if (it.index != latestCursor) onCursor(it.index) }
            }
            LaunchedEffect(cursor, padActive, covers) {
                val pos = covers.indexOfFirst { it.index == cursor }
                if (pos >= 0 && (padActive || state.layoutInfo.visibleItemsInfo.isEmpty())) state.animateScrollToItem(pos)
            }
            // Start on the current cover when the page opens.
            LaunchedEffect(covers) {
                val pos = covers.indexOfFirst { it.index == cursor }
                if (pos > 0) state.scrollToItem(pos)
            }

            LazyRow(
                state = state,
                contentPadding = PaddingValues(horizontal = sidePad),
                flingBehavior = rememberSnapFlingBehavior(state, SnapPosition.Center),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(covers, coverPx) {
                        // Taps are resolved here because the covers are drawn away from their slots.
                        fun hit(x: Float): Int? {
                            val info = state.layoutInfo
                            val mid = (info.viewportStartOffset + info.viewportEndOffset) / 2f
                            val viewMid = size.width / 2f
                            return info.visibleItemsInfo
                                .map { it.index to viewMid + visualOffset((it.offset + it.size / 2f - mid) / it.size, coverPx) }
                                .minByOrNull { abs(it.second - x) }?.first
                        }
                        detectTapGestures(
                            onTap = { pt ->
                                val pos = hit(pt.x) ?: return@detectTapGestures
                                val (i, e) = covers.getOrNull(pos) ?: return@detectTapGestures
                                if (pos == centered) onActivate(i, e) else scope.launch { state.animateScrollToItem(pos) }
                            },
                            onLongPress = { pt ->
                                val pos = hit(pt.x) ?: return@detectTapGestures
                                covers.getOrNull(pos)?.let { (i, e) -> onLongPress(i, e) }
                            },
                        )
                    },
            ) {
                itemsIndexed(covers, key = { _, c -> c.value.key }) { pos, (_, e) ->
                    // Closer covers are drawn on top of further ones.
                    val depth by remember(state, pos) {
                        derivedStateOf {
                            val info = state.layoutInfo
                            val mid = (info.viewportStartOffset + info.viewportEndOffset) / 2f
                            val item = info.visibleItemsInfo.firstOrNull { it.index == pos }
                            if (item == null) 0f else -(abs(item.offset + item.size / 2f - mid) / item.size).roundToInt().toFloat()
                        }
                    }
                    Box(Modifier.size(slotDp, coverDp).zIndex(depth), contentAlignment = Alignment.Center) {
                        Box(
                            Modifier
                                .requiredSize(coverDp)
                                .graphicsLayer {
                                    val info = state.layoutInfo
                                    val item = info.visibleItemsInfo.firstOrNull { it.index == pos } ?: return@graphicsLayer
                                    val mid = (info.viewportStartOffset + info.viewportEndOffset) / 2f
                                    val d = item.offset + item.size / 2f - mid
                                    val f = d / item.size
                                    val t = min(abs(f), 1f)
                                    translationX = visualOffset(f, coverPx) - d
                                    rotationY = -sign(f) * t * 60f
                                    scaleX = 1f - 0.16f * t
                                    scaleY = scaleX
                                    cameraDistance = 10f * this.density
                                }
                                .reflection(Surface1),
                        ) {
                            ArtBox(coverArt(e), e.round, Modifier.fillMaxSize())
                        }
                    }
                }
            }
        }

        // Caption for the centre cover.
        val current = data.entries.getOrNull(cursor)?.takeIf { !it.fullWidth } ?: covers.firstOrNull()?.value
        val position = covers.indexOfFirst { it.value == current } + 1
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (current?.song != null && current.song.id == nowPlaying) {
                    Icon(Icons.Rounded.Equalizer, null, tint = Accent, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                }
                Text(current?.title.orEmpty(), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(
                listOfNotNull(current?.subtitle?.ifBlank { null }, if (covers.size > 1) "$position of ${covers.size}" else null).joinToString(" · "),
                color = TextDim, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
        }
    }
}

/** Songs inside an album show a track number in list view; in Cover Flow they show the cover. */
private fun coverArt(e: Entry): Art {
    val s = e.song
    return if (e.art is Art.TrackNo && s != null) Art.Cover(s.albumId, s.id) else e.art
}

/** Mirrored, fading copy of the content just below it, like the iPod's glossy floor. */
private fun Modifier.reflection(background: Color): Modifier = drawWithContent {
    drawContent()
    val h = size.height
    val gap = 2.dp.toPx()
    val depth = h * 0.35f
    clipRect(top = h + gap, bottom = h + gap + depth) {
        withTransform({
            translate(top = h + gap)
            scale(1f, -1f, pivot = Offset(size.width / 2f, h / 2f))
        }) {
            this@drawWithContent.drawContent()
        }
    }
    drawRect(
        Brush.verticalGradient(listOf(background.copy(alpha = 0.6f), background), startY = h + gap, endY = h + gap + depth),
        topLeft = Offset(0f, h + gap),
        size = Size(size.width, depth),
    )
}
