package dev.trooped.tvquickbars.controlcenter

import android.annotation.SuppressLint
import android.content.Context
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import coil.request.SuccessResult
import coil.imageLoader
import androidx.core.graphics.drawable.toBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.runtime.key
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.CachePolicy
import coil.request.ImageRequest
import dev.trooped.tvquickbars.notification.resolveAgainstHaBase
import dev.trooped.tvquickbars.persistence.SecurePrefsManager
import kotlinx.coroutines.delay
import kotlin.math.roundToInt
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Shared look and building blocks of the Control Center: colours, icons, image loading, focusable surfaces.

internal val Amber = Color(0xFFF2A93B)
internal val Ink = Color(0xFF1C1828)
internal val PanelBrush = Brush.verticalGradient(listOf(Color(0xF51A1626), Color(0xF52A1742)))
internal fun white(a: Float) = Color.White.copy(alpha = a)

/** OK / Enter on a TV remote. */
internal fun KeyEvent.isOk() = key == Key.DirectionCenter || key == Key.Enter || key == Key.NumPadEnter

@SuppressLint("DiscouragedApi")
@Composable
internal fun ccIcon(name: String): Int {
    val ctx = LocalContext.current
    return remember(name) {
        val n = name.replace("-", "_").removePrefix("mdi:").replace(":", "_")
        listOf("cc_$n", n, "cc_bell_outline").firstNotNullOf { r ->
            ctx.resources.getIdentifier(r, "drawable", ctx.packageName).takeIf { it != 0 }
        }
    }
}

/** Image request for HA-relative or absolute URLs; HA URLs get the long-lived token. [widthPx]: the size it is shown at.
 *  HA's camera snapshots (/api/camera_proxy/) are then asked for at that width instead of full size (a 1080p or 4K
 *  frame every 4 s per tile was the heaviest work while the panel is open), and any picture is decoded no bigger. */
internal fun imageRequest(ctx: Context, url: String?, tick: Int = 0, widthPx: Int = 0): ImageRequest? {
    if (url.isNullOrBlank()) return null
    val (abs, isHa) = resolveAgainstHaBase(ctx, url)
    if (abs == null) return null
    val token = if (isHa) SecurePrefsManager.getHAToken(ctx) else null
    val params = listOfNotNull(
        if (isHa && widthPx > 0 && abs.contains("/api/camera_proxy/")) "width=$widthPx" else null,
        if (tick > 0) "cc=$tick" else null,
    )
    val u = if (params.isEmpty()) abs else abs + (if (abs.contains("?")) "&" else "?") + params.joinToString("&")
    return ImageRequest.Builder(ctx).data(u).crossfade(tick == 0)
        .apply {
            if (widthPx > 0) size(widthPx, widthPx)
            if (!token.isNullOrBlank()) addHeader("Authorization", "Bearer $token")
            if (tick > 0) { memoryCachePolicy(CachePolicy.DISABLED); diskCachePolicy(CachePolicy.DISABLED) }
        }.build()
}

/** A focusable surface: brightens and grows a little when selected (tvOS style), OK runs [onOk]. */
@Composable
internal fun Focusable(
    modifier: Modifier = Modifier,
    shape: RoundedCornerShape = RoundedCornerShape(16.dp),
    bg: Color = white(.10f),
    focusedBg: Color = white(.22f),
    requester: FocusRequester? = null,
    onKey: ((KeyEvent) -> Boolean)? = null,
    onLong: (() -> Unit)? = null,
    onOk: () -> Unit = {},
    content: @Composable BoxScope.(focused: Boolean) -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    var longFired by remember { mutableStateOf(false) }
    val s by animateFloatAsState(if (focused) 1.04f else 1f, spring(stiffness = 500f), label = "ccScale")
    Box(
        modifier
            .graphicsLayer { scaleX = s; scaleY = s }
            .clip(shape)
            .background(if (focused) focusedBg else bg)
            .border(if (focused) 2.dp else 1.dp, if (focused) white(.85f) else white(.06f), shape)
            .then(if (requester != null) Modifier.focusRequester(requester) else Modifier)
            .onFocusChanged { focused = it.isFocused }
            .onKeyEvent { e ->
                if (onKey?.invoke(e) == true) true
                else if (e.isOk()) {
                    // Holding OK repeats the key-down: the first repeat runs onLong (if any) and the release is then
                    // swallowed; a short press runs onOk on release as before.
                    if (e.type == KeyEventType.KeyDown && e.nativeKeyEvent.repeatCount == 0) longFired = false
                    if (e.type == KeyEventType.KeyDown && onLong != null && !longFired && e.nativeKeyEvent.repeatCount >= 1) {
                        longFired = true; onLong()
                    } else if (e.type == KeyEventType.KeyUp) { if (longFired) longFired = false else onOk() }
                    true
                } else false
            }
            .focusable(),
    ) { content(focused) }
}

@Composable
internal fun IconTile(icon: String, size: Dp, color: Long? = null, on: Boolean = true) {
    val c = color?.let { Color(it) } ?: Color(0xFFF59E0B)
    Box(
        Modifier.size(size).clip(RoundedCornerShape(size * .28f))
            .background(if (on) Brush.verticalGradient(listOf(c, Color(0xFF7C3AED))) else Brush.verticalGradient(listOf(white(.16f), white(.10f)))),
        contentAlignment = Alignment.Center,
    ) {
        Image(painterResource(ccIcon(icon)), null, Modifier.size(size * .56f), colorFilter = ColorFilter.tint(Color.White))
    }
}

@Composable
internal fun Net(url: String?, modifier: Modifier, tick: Int = 0, crop: ContentScale = ContentScale.Crop, widthPx: Int = 0) {
    val ctx = LocalContext.current
    if (tick == 0) {
        val req = remember(url, widthPx) { imageRequest(ctx, url, widthPx = widthPx) }
        if (req == null) Box(modifier.background(white(.08f))) else AsyncImage(req, null, modifier, contentScale = crop)
        return
    }
    // Live frames (cameras): load each refresh in the background and swap only when it has arrived, so the tile
    // keeps the last frame instead of going blank while loading (every 4 s, and when an update re-sends the list).
    var frame by remember(url) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(url, tick) {
        val req = imageRequest(ctx, url, tick, widthPx) ?: return@LaunchedEffect
        val r = ctx.imageLoader.execute(req)
        (r as? SuccessResult)?.drawable?.let { d -> frame = d.toBitmap().asImageBitmap() }
    }
    val f = frame
    if (f == null) Box(modifier.background(Color.Black)) else Image(f, null, modifier, contentScale = crop)
}
