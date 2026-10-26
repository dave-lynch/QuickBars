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

// Rooms (D5) and one room's devices, with the 24-hour temperature chart.

// ============================================================ D5: Rooms (full screen), like the main HA dashboard
@Composable
internal fun RoomsPage(rooms: List<CcRoom>, onOpen: (String) -> Unit, from: String? = null) {
    // Rooms grouped by floor (in the order HA sends them), four to a row. OK opens a room; Back returns to the panel.
    val reqs = remember(rooms) { rooms.associate { it.id to FocusRequester() } }
    LaunchedEffect(Unit) { delay(60); runCatching { reqs[from ?: rooms.firstOrNull()?.id]?.requestFocus() } }
    val floors = rooms.map { it.floor }.distinct()
    Column(Modifier.fillMaxSize().background(Color(0xFF0D0A14)).padding(start = 52.dp, end = 52.dp, top = 26.dp, bottom = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Image(painterResource(ccIcon("chevron_left")), null, Modifier.size(22.dp), colorFilter = ColorFilter.tint(Amber))
            Text("Rooms", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        }
        LazyColumn(Modifier.weight(1f).padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(4.dp)) {
            floors.forEach { fl ->
                item(key = "f-$fl") { Text(fl.uppercase(), color = white(.55f), fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp)) }
                rooms.filter { it.floor == fl }.chunked(4).forEachIndexed { ci, row ->
                    item(key = "r-$fl-$ci") {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            row.forEach { r -> key(r.id) {
                                Focusable(Modifier.weight(1f).height(96.dp), RoundedCornerShape(18.dp), requester = reqs[r.id], onOk = { onOpen(r.id) }) {
                                    Column(Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 12.dp)) {
                                        Text(r.name, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Spacer(Modifier.weight(1f))
                                        r.temp?.let { t ->
                                            Row(verticalAlignment = Alignment.Bottom) {
                                                Text("${t.now}°", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                                                Text("  low ${t.low}° · high ${t.high}°", color = white(.65f), fontSize = 12.sp, modifier = Modifier.padding(bottom = 3.dp))
                                            }
                                        } ?: Text(r.summary, color = white(.65f), fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                            } }
                            repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
            }
        }
        Text("◀ ▶ ▲ ▼ rooms · OK open · Back", color = white(.4f), fontSize = 11.sp)
    }
}

/** One room: its devices as tiles (OK turns on / off; hold OK on a light for brightness, on a TV or speaker for volume),
 *  the room's actions (scripts in that area), and for a room with a temperature sensor its last 24 hours. */
@Composable
internal fun RoomPage(r: CcRoom, onAction: (String) -> Unit, onDim: (CcTile) -> Unit, onPage: (String) -> Unit = {}) {
    val first = remember(r.id) { FocusRequester() }
    var hasFocus by remember { mutableStateOf(false) }
    LaunchedEffect(r.id) { delay(60); runCatching { first.requestFocus() } }
    Column(Modifier.fillMaxSize().background(Color(0xFF0D0A14)).padding(start = 52.dp, end = 52.dp, top = 26.dp, bottom = 14.dp)
        .onFocusChanged { hasFocus = it.hasFocus }.then(if (r.devices.isEmpty() && r.actions.isEmpty()) Modifier.focusRequester(first).focusable() else Modifier),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Image(painterResource(ccIcon("chevron_left")), null, Modifier.size(22.dp), colorFilter = ColorFilter.tint(Amber))
            Text("Rooms · ", color = white(.55f), fontSize = 14.sp)
            Text(r.name, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        }
        r.temp?.let { t ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                listOf(Triple("Now", "${t.now}°", Color.White), Triple("Lowest · ${t.lowAt}", "${t.low}°", Color(0xFF7FB8FF)),
                    Triple("Highest · ${t.highAt}", "${t.high}°", Amber), Triple("Humidity", if (t.hum.isBlank()) "–" else "${t.hum}%", Color.White)).forEach { (l, v, c) ->
                    Column(Modifier.weight(1f).clip(RoundedCornerShape(18.dp)).background(white(.10f)).padding(horizontal = 16.dp, vertical = 12.dp)) {
                        Text(l, color = white(.65f), fontSize = 12.sp, maxLines = 1)
                        Text(v, color = c, fontSize = 30.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
            if (t.points.size > 1) Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(white(.06f)).padding(16.dp)) {
                Text("Last 24 hours", color = white(.75f), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                TempChart(t.points, Modifier.fillMaxWidth().height(170.dp).padding(top = 10.dp))
                Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                    Text("24 h ago", color = white(.5f), fontSize = 11.sp, modifier = Modifier.weight(1f))
                    Text("12 h ago", color = white(.5f), fontSize = 11.sp, modifier = Modifier.weight(1f))
                    Text("now", color = white(.5f), fontSize = 11.sp)
                }
            }
        }
        if (r.devices.isNotEmpty()) Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            r.devices.chunked(4).forEachIndexed { ci, row ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    row.forEachIndexed { i, d -> key(d.id) {
                        RoomTile(d, requester = if (ci == 0 && i == 0) first else null, onAction = onAction,
                            onLong = if (d.bri >= 0 && (d.id.startsWith("light.") || d.id.startsWith("media_player."))) ({ onDim(d) }) else null)
                    } }
                    repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
        if (r.actions.isNotEmpty()) {
            Text("ACTIONS", color = white(.55f), fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                r.actions.forEachIndexed { i, a -> key(a.id) {
                    Focusable(Modifier.height(44.dp), RoundedCornerShape(14.dp), requester = if (r.devices.isEmpty() && i == 0) first else null,
                        onOk = { if (a.id.startsWith("page:")) onPage(a.id.removePrefix("page:")) else onAction("room:run:${a.id}") }) {
                        Text(a.title, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                            modifier = Modifier.align(Alignment.Center).padding(horizontal = 18.dp))
                    }
                } }
            }
        }
        Spacer(Modifier.weight(1f))
        Text(if (r.devices.isEmpty() && r.actions.isEmpty()) "Back" else "OK on / off · hold OK brightness or volume · Back", color = white(.4f), fontSize = 11.sp)
    }
}

/** A device in a room. Flips at once on OK and falls back to HA's state if it didn't follow within 4 s (as QuickTile). */
@Composable
internal fun RowScope.RoomTile(d: CcTile, requester: FocusRequester?, onAction: (String) -> Unit, onLong: (() -> Unit)?) {
    var on by remember(d.on) { mutableStateOf(d.on) }
    LaunchedEffect(on, d.on) { if (on != d.on) { delay(4_000); on = d.on } }
    Focusable(Modifier.weight(1f).height(100.dp), RoundedCornerShape(18.dp),
        bg = if (on) white(.92f) else white(.10f), focusedBg = if (on) Color.White else white(.22f),
        requester = requester, onLong = onLong, onOk = { on = !on; onAction("room:toggle:${d.id}") }) {
        Column(Modifier.fillMaxSize().padding(12.dp)) {
            Image(painterResource(ccIcon(d.icon)), null, Modifier.size(22.dp), colorFilter = ColorFilter.tint(if (on) Color(0xFFF59E0B) else Color.White))
            Spacer(Modifier.weight(1f))
            Text(d.title, color = if (on) Ink else Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (d.sub.isNotBlank()) Text(d.sub, color = if (on) Ink.copy(alpha = .6f) else white(.6f), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Hourly temperatures as a line with a soft fill; the lowest point blue, the highest amber, now white. */
@Composable
internal fun TempChart(pts: List<Float>, modifier: Modifier) {
    val lo = pts.min(); val hi = pts.max(); val pad = ((hi - lo) * .15f).coerceAtLeast(.5f)
    val min = lo - pad; val max = hi + pad
    Canvas(modifier) {
        fun at(i: Int) = Offset(size.width * i / (pts.size - 1), size.height * (1 - (pts[i] - min) / (max - min)))
        val line = Path().apply { pts.indices.forEach { i -> val o = at(i); if (i == 0) moveTo(o.x, o.y) else lineTo(o.x, o.y) } }
        val fill = Path().apply { addPath(line); lineTo(size.width, size.height); lineTo(0f, size.height); close() }
        drawPath(fill, Amber.copy(alpha = .14f))
        drawPath(line, Amber, style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawCircle(Color(0xFF7FB8FF), 6.dp.toPx(), at(pts.indexOf(lo)))
        drawCircle(Amber, 6.dp.toPx(), at(pts.indexOf(hi)))
        drawCircle(Color.White, 6.dp.toPx(), at(pts.size - 1))
    }
}
