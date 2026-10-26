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

// Live camera video and the Cameras page.

// ============================================================ live camera, extending out of the panel
/** The doorbell waking up: [s] seconds so far (0 = awake, connecting; -1 = the video wouldn't start). */
@Composable
internal fun WakeWait(s: Int) {
    val pulse by rememberInfiniteTransition(label = "wake").animateFloat(.35f, 1f,
        infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "wakePulse")
    val late = s > 45
    Column(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF1B1530), Color(0xFF0B0914)))),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Image(painterResource(ccIcon("bell_outline")), null, Modifier.size(34.dp).graphicsLayer { alpha = if (s < 0 || late) 1f else pulse },
            colorFilter = ColorFilter.tint(Amber))
        Text(when { s < 0 -> "The video didn't start"; late -> "Couldn't wake the doorbell"; s == 0 -> "Connecting…"; else -> "Waking the doorbell…" },
            color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 10.dp))
        Text(when { s < 0 || late -> "OK on the tile to close, then try again"; s == 0 -> "Starting the video"; else -> "Usually 10–20 seconds · ${s}s" },
            color = white(.6f), fontSize = 11.sp, modifier = Modifier.padding(top = 3.dp))
    }
}

/** A camera's video: Frigate's RTSP restream (hardware decoded) when it has one, else HA's MJPEG proxy. A sleeping
 *  camera ([wake], the doorbell) is woken first: cam_wake when shown, cam_keep each minute (HA lets it sleep about 90 s
 *  after the last one), a waiting screen until HA says it is [awake], then the video, tried up to 5 times. */
@Composable
internal fun CamVideo(entity: String, rtsp: String, modifier: Modifier, wake: Boolean = false, awake: Boolean = false, onAction: (String) -> Unit = {}) {
    val ctx = LocalContext.current
    val url = remember(entity) {
        (dev.trooped.tvquickbars.notification.normalizedHaBase(ctx)?.toString()?.trimEnd('/')
            ?: SecurePrefsManager.getHAUrl(ctx)?.trimEnd('/') ?: "") + "/api/camera_proxy_stream/" + entity
    }
    val token = remember { SecurePrefsManager.getHAToken(ctx) }
    var waited by remember(entity) { mutableIntStateOf(0) }
    if (wake) LaunchedEffect(entity) {
        onAction("cam_wake:$entity")
        var s = 0
        while (true) { delay(1000); s++; if (s <= 60) waited = s; if (s % 60 == 0) onAction("cam_keep:$entity") }
    }
    key(entity) {
        Box(modifier.background(Color.Black)) {
            var rtspFailed by remember(entity) { mutableStateOf(false) }
            var tries by remember(entity) { mutableIntStateOf(0) }
            var retryAt by remember(entity) { mutableIntStateOf(0) }   // the attempt the 2 s pause is over for
            var playing by remember(entity) { mutableStateOf(false) }
            val prof = dev.trooped.tvquickbars.camera.RtspProfile(latency = dev.trooped.tvquickbars.camera.StreamLatency.LOW_LATENCY, muteAudio = true)
            if (wake) {
                // no picture without the stream (HA's still is a 503): once awake, try the video up to 5 times, 2 s apart
                LaunchedEffect(tries) { if (tries > 0) delay(2000); retryAt = tries }
                if (awake && !rtspFailed && rtsp.isNotBlank() && retryAt == tries) key(tries) {
                    dev.trooped.tvquickbars.camera.CameraRtspView(url = rtsp, config = prof, modifier = Modifier.fillMaxSize(),
                        onReady = { playing = true }, onError = { playing = false; if (tries < 4) tries++ else rtspFailed = true })
                }
                if (!playing) WakeWait(if (rtspFailed) -1 else if (awake) 0 else waited)
            } else if (rtsp.isNotBlank() && !rtspFailed)
                dev.trooped.tvquickbars.camera.CameraRtspView(url = rtsp, config = prof, modifier = Modifier.fillMaxSize(), onError = { rtspFailed = true })
            else dev.trooped.tvquickbars.camera.CameraMjpegView(url = url, authToken = token, modifier = Modifier.fillMaxSize())
        }
    }
}

@Composable
internal fun LiveCamera(entity: String, name: String, rtsp: String, modifier: Modifier,
                       wake: Boolean = false, awake: Boolean = false, onAction: (String) -> Unit = {}) {
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    val slide by animateFloatAsState(if (shown) 0f else 1f, spring(dampingRatio = .85f, stiffness = 320f), label = "liveCam")
    Box(modifier.size(468.dp, 266.dp)
        .graphicsLayer { translationX = slide * 120f; alpha = 1f - slide }
        .clip(RoundedCornerShape(topStart = 24.dp, bottomStart = 24.dp, topEnd = 6.dp, bottomEnd = 6.dp))
        .background(PanelBrush).border(1.dp, white(.12f), RoundedCornerShape(topStart = 24.dp, bottomStart = 24.dp, topEnd = 6.dp, bottomEnd = 6.dp))
    ) {
        CamVideo(entity, rtsp, Modifier.padding(start = 8.dp, top = 8.dp, bottom = 8.dp, end = 14.dp).clip(RoundedCornerShape(18.dp)).fillMaxSize(),
            wake = wake, awake = awake, onAction = onAction)
        Row(Modifier.padding(start = 18.dp, top = 16.dp).clip(RoundedCornerShape(50)).background(Color(0x99000000))
            .padding(horizontal = 9.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(7.dp).clip(RoundedCornerShape(50)).background(Color(0xFFEF4444)))
            Text("  $name · live", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }
        Text("Back: keep watching in the corner · OK on the tile: close", color = white(.85f), fontSize = 11.sp,
            modifier = Modifier.align(Alignment.BottomStart).padding(start = 18.dp, bottom = 14.dp)
                .clip(RoundedCornerShape(50)).background(Color(0x99000000)).padding(horizontal = 8.dp, vertical = 2.dp))
    }
}

// ============================================================ cameras page
/** Every camera, newest detection first, with the latest detection snapshots underneath. OK plays a camera live (up to
 *  3; a 4th stops the one started longest ago), OK on a live one stops it. Nothing live: all cameras fill the space as a
 *  grid. Something live: the live ones fill the left (1 large, 2 side by side, 3 as 2 + 1) and the rest shrink into a
 *  grid on the right. The panel stays open while you are on this page (it keeps itself awake, up to 30 minutes). */
@Composable
internal fun CamerasPage(spec: ControlCenterSpec, onAction: (String) -> Unit, onKeepAlive: () -> Unit) {
    val cams = spec.cameras
    val live = remember { mutableStateListOf<String>() }
    fun toggle(e: String) { if (e in live) live.remove(e) else { live.add(e); while (live.size > 3) live.removeAt(0) } }
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(4_000); tick++ } }
    LaunchedEffect(Unit) { repeat(60) { onKeepAlive(); delay(30_000) } }   // watching isn't idle: 30 minutes
    val reqs = remember { mutableMapOf<String, FocusRequester>() }
    fun req(e: String) = reqs.getOrPut(e) { FocusRequester() }
    LaunchedEffect(Unit) { delay(60); cams.firstOrNull()?.let { runCatching { req(it.entity).requestFocus() } } }
    // keep focus on the tile just pressed after it moves between the live area and the grid
    var lastOk by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(live.toList()) { lastOk?.let { delay(80); runCatching { req(it).requestFocus() } } }

    @Composable
    fun Tile(c: CcCamera, modifier: Modifier, big: Boolean) {
        val on = c.entity in live
        Focusable(modifier, RoundedCornerShape(if (big) 16.dp else 12.dp), bg = Color.Black, focusedBg = Color.Black,
            requester = req(c.entity), onOk = { lastOk = c.entity; toggle(c.entity) }) {
            if (on) CamVideo(c.entity, c.rtspSub.ifBlank { c.rtsp }, Modifier.fillMaxSize(), wake = c.wake, awake = c.awake, onAction = onAction)
            else Net(c.image, Modifier.fillMaxSize(), tick, widthPx = if (big) 960 else 480)
            Row(Modifier.align(Alignment.TopStart).padding(6.dp).clip(RoundedCornerShape(50)).background(Color(0x99000000))
                .padding(horizontal = 7.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                if (on) Box(Modifier.size(6.dp).clip(RoundedCornerShape(50)).background(Color(0xFFEF4444)))
                Text((if (on) " " else "") + c.name + (if (on) " · live" else ""), color = Color.White,
                    fontSize = if (big) 13.sp else 11.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            }
            if (c.seen.isNotBlank()) Text(c.seen, color = Color.White, fontSize = if (big) 12.sp else 10.sp, maxLines = 1,
                modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp).clip(RoundedCornerShape(50))
                    .background(Color(0xCC7C2D12)).padding(horizontal = 7.dp, vertical = 2.dp))
        }
    }
    @Composable
    fun Grid(list: List<CcCamera>, cols: Int, modifier: Modifier, big: Boolean) {
        // Tiles keep the cameras' 16:9 shape: the largest size where every row fits in the space, centred in it
        BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
            val gap = 8.dp
            val rows = ((list.size + cols - 1) / cols).coerceAtLeast(1)
            val w = minOf((maxWidth - gap * (cols - 1)) / cols, ((maxHeight - gap * (rows - 1)) / rows) * (16f / 9f))
            Column(verticalArrangement = Arrangement.spacedBy(gap), horizontalAlignment = Alignment.CenterHorizontally) {
                list.chunked(cols).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                        row.forEach { c -> key(c.entity) { Tile(c, Modifier.width(w).height(w * 9f / 16f), big) } }
                    }
                }
            }
        }
    }

    Column(Modifier.fillMaxSize().background(Color(0xFF0D0A14)).padding(start = 28.dp, end = 28.dp, top = 16.dp, bottom = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Image(painterResource(ccIcon("chevron_left")), null, Modifier.size(20.dp), colorFilter = ColorFilter.tint(Amber))
            Text("Cameras", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text("   ${live.size} of 3 live · newest activity first · OK play / stop · Back", color = white(.5f), fontSize = 11.sp)
        }
        val liveCams = live.mapNotNull { e -> cams.firstOrNull { it.entity == e } }
        val rest = cams.filter { it.entity !in live }
        Row(Modifier.weight(1f).fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (liveCams.isEmpty()) Grid(rest, 4, Modifier.fillMaxSize(), big = false)
            else {
                Grid(liveCams, if (liveCams.size == 1) 1 else 2, Modifier.weight(2.6f).fillMaxHeight(), big = true)
                if (rest.isNotEmpty()) Grid(rest, 2, Modifier.weight(1f).fillMaxHeight(), big = false)
            }
        }
        if (spec.snaps.isNotEmpty()) {
            Text("Recent", color = white(.75f), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().height(104.dp)) {
                items(spec.snaps, key = { it.image }) { sn ->
                    Focusable(Modifier.width(170.dp).fillMaxHeight(), RoundedCornerShape(12.dp), bg = Color.Black,
                        onOk = { if (sn.entity !in live) { lastOk = sn.entity; toggle(sn.entity) } }) {
                        Net(sn.image, Modifier.fillMaxSize(), widthPx = 360)
                        Text("${sn.what} · ${sn.cam} · ${sn.at}", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth().background(Color(0x99000000)).padding(horizontal = 6.dp, vertical = 3.dp))
                    }
                }
            }
        }
    }
}
