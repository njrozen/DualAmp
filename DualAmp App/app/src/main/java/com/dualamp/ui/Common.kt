package com.dualamp.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.focusable
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.dualamp.library.Artwork

val Accent = Color(0xFF1DB954)
val AccentViz = Color(0xFFB388FF)
val Surface1 = Color(0xFF121212)
val Surface2 = Color(0xFF1E1E1E)
val Surface3 = Color(0xFF2A2A2A)
val TextDim = Color(0xFFB3B3B3)

@Composable
fun DualAmpTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Accent,
            onPrimary = Color.Black,
            secondary = AccentViz,
            background = Surface1,
            surface = Surface1,
            surfaceVariant = Surface2,
            onSurface = Color.White,
            onSurfaceVariant = TextDim,
        ),
    ) {
        // Default text and icon colour for everything not inside a Surface.
        CompositionLocalProvider(LocalContentColor provides Color.White, content = content)
    }
}

/** Square album cover; shows a tinted note placeholder until (or unless) art loads. */
@Composable
fun AlbumArt(
    albumId: Long,
    songId: Long,
    modifier: Modifier = Modifier,
    shape: Shape = MaterialTheme.shapes.small,
    seed: String = albumId.toString(),
) {
    BoxWithConstraints(modifier.aspectRatio(1f).clip(shape)) {
        val px = with(LocalDensity.current) { maxWidth.toPx().toInt().coerceAtLeast(64) }
        val context = LocalContext.current
        val image by produceState<ImageBitmap?>(Artwork.cached(albumId, px), albumId, px) {
            // The state outlives a change of album, so always replace what it holds.
            value = Artwork.cached(albumId, px) ?: Artwork.load(context, albumId, songId, px)
        }
        val img = image
        if (img != null) {
            Image(img, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else {
            Placeholder(seed, Modifier.fillMaxSize())
        }
    }
}

@Composable
fun Placeholder(seed: String, modifier: Modifier = Modifier, icon: androidx.compose.ui.graphics.vector.ImageVector = Icons.Rounded.MusicNote) {
    val hue = (seed.hashCode().toUInt().toLong() % 360).toFloat()
    Box(
        modifier.background(
            Brush.linearGradient(listOf(Color.hsv(hue, 0.55f, 0.45f), Color.hsv((hue + 40) % 360, 0.6f, 0.2f)))
        ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, tint = Color.White.copy(alpha = 0.7f), modifier = Modifier.fillMaxWidth(0.4f).aspectRatio(1f))
    }
}

fun formatTime(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

fun formatDurationLong(ms: Long): String {
    val min = ms / 60000
    return if (min >= 60) "${min / 60} hr ${min % 60} min" else "$min min"
}

fun plural(n: Int, word: String) = "$n $word" + if (n == 1) "" else "s"

/**
 * Keeps keyboard/gamepad focus on this (invisible) container. Without it Android moves focus to the
 * first button on the first key press and draws a focus ring there; the gamepad is handled by
 * ControllerBus instead.
 */
@Composable
fun Modifier.holdKeyFocus(): Modifier {
    val requester = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { requester.requestFocus() } }
    return this.focusRequester(requester).focusable()
}
