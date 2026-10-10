package dev.trooped.tvquickbars.controlcenter

import android.annotation.SuppressLint
import android.content.Context
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val Amber = Color(0xFFF2A93B)
private val Ink = Color(0xFF1C1828)
private val PanelBrush = Brush.verticalGradient(listOf(Color(0xF51A1626), Color(0xF52A1742)))
private fun white(a: Float) = Color.White.copy(alpha = a)

/** OK / Enter on a TV remote. */
private fun KeyEvent.isOk() = key == Key.DirectionCenter || key == Key.Enter || key == Key.NumPadEnter

@SuppressLint("DiscouragedApi")
@Composable
private fun ccIcon(name: String): Int {
    val ctx = LocalContext.current
    return remember(name) {
        val n = name.replace("-", "_").removePrefix("mdi:").replace(":", "_")
        listOf("cc_$n", n, "cc_bell_outline").firstNotNullOf { r ->
            ctx.resources.getIdentifier(r, "drawable", ctx.packageName).takeIf { it != 0 }
        }
    }
}

/** Image request for HA-relative or absolute URLs; HA URLs get the long-lived token. */
private fun imageRequest(ctx: Context, url: String?, tick: Int = 0): ImageRequest? {
    if (url.isNullOrBlank()) return null
    val (abs, isHa) = resolveAgainstHaBase(ctx, url)
    if (abs == null) return null
    val token = if (isHa) SecurePrefsManager.getHAToken(ctx) else null
    val u = if (tick > 0) abs + (if (abs.contains("?")) "&" else "?") + "cc=" + tick else abs
    return ImageRequest.Builder(ctx).data(u).crossfade(tick == 0).allowHardware(false)
        .apply {
            if (!token.isNullOrBlank()) addHeader("Authorization", "Bearer $token")
            if (tick > 0) { memoryCachePolicy(CachePolicy.DISABLED); diskCachePolicy(CachePolicy.DISABLED) }
        }.build()
}

/** A focusable surface: brightens and grows a little when selected (tvOS style), OK runs [onOk]. */
@Composable
private fun Focusable(
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
private fun IconTile(icon: String, size: Dp, color: Long? = null, on: Boolean = true) {
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
private fun Net(url: String?, modifier: Modifier, tick: Int = 0, crop: ContentScale = ContentScale.Crop) {
    val ctx = LocalContext.current
    if (tick == 0) {
        val req = remember(url) { imageRequest(ctx, url) }
        if (req == null) Box(modifier.background(white(.08f))) else AsyncImage(req, null, modifier, contentScale = crop)
        return
    }
    // Live frames (cameras): load each refresh in the background and swap only when it has arrived, so the tile
    // keeps the last frame instead of going blank while loading (every 4 s, and when an update re-sends the list).
    var frame by remember(url) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(url, tick) {
        val req = imageRequest(ctx, url, tick) ?: return@LaunchedEffect
        val r = ctx.imageLoader.execute(req)
        (r as? SuccessResult)?.drawable?.let { d -> frame = d.toBitmap().asImageBitmap() }
    }
    val f = frame
    if (f == null) Box(modifier.background(Color.Black)) else Image(f, null, modifier, contentScale = crop)
}

// ============================================================ root
@Composable
fun ControlCenterRoot(
    spec: ControlCenterSpec,
    onAction: (String) -> Unit,
    onCamera: (String, String?) -> Unit,   // entity, RTSP stream for the corner PiP
    onClose: () -> Unit,
    onKeepAlive: () -> Unit = {},
) {
    var page by remember(spec.page) { mutableStateOf(spec.page) }
    var catchupFrom by remember(spec.page) { mutableStateOf("notifications") }
    var openGroup by remember { mutableStateOf<String?>(null) }
    var tvMenu by remember { mutableStateOf<CcCatchupItem?>(null) }   // hold-OK menu open on the TV page   // light group held open: its lights show under the tiles   // where Back from catch-up returns to
    val dismissed = remember { mutableStateListOf<String>() }
    val notes = spec.notifications.filter { it.id !in dismissed }
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    val slide by animateFloatAsState(if (shown) 0f else 1f, spring(dampingRatio = .85f, stiffness = 300f), label = "ccSlide")

    // Live camera opened from the panel: shown large, sliding out from behind the panel's bottom-left edge. The first
    // Back hands it to the corner PiP (bottom right) and closes the panel; Back again closes the PiP.
    var live by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(live) { while (live != null) { onKeepAlive(); delay(30_000) } }   // watching isn't "idle"

    fun back() { if (page == "home" && openGroup != null) { openGroup = null; return }
                 if (page == "catchup" && tvMenu != null) { tvMenu = null; return }
                 page = when (page) { "catchup" -> if (spec.page == "catchup") "close" else catchupFrom; "notifications" -> "home"; else -> "close" }
                 if (page == "close") onClose() }
    fun openNote(n: CcNotification) {
        when {
            n.open.startsWith("page:") -> { catchupFrom = page; page = n.open.removePrefix("page:") }
            n.open.startsWith("camera:") -> live = n.open.removePrefix("camera:")
            else -> { onAction("notif_open:${n.id}"); onClose() }
        }
    }
    fun dismiss(n: CcNotification) { dismissed += n.id; onAction("notif_dismiss:${n.id}") }

    // Brightness slider (hold OK on a light tile): ▲▼ in 10% steps, sent to HA after a short pause; OK / Back close it
    // A light group opens with its parts listed under the slider (e.g. All · Spotlights · Standing lamp):
    // ◀ ▶ picks which one the slider sets, ▲ ▼ sets it.
    var dimTargets by remember { mutableStateOf<List<CcTile>>(emptyList()) }
    var dimSel by remember { mutableIntStateOf(0) }
    val dimPcts = remember { mutableStateMapOf<String, Int>() }
    var dim by remember { mutableStateOf<CcTile?>(null) }
    var dimPct by remember { mutableIntStateOf(0) }
    var dimSent by remember { mutableIntStateOf(-1) }
    var dimHeld by remember { mutableStateOf(false) }   // the OK that opened it is still held: ignore its release
    fun pctOf(t: CcTile) = if (t.bri >= 0) t.bri else if (t.on) 100 else 0
    fun openDim(t: CcTile) {
        dimTargets = listOf(t) + t.members; dimSel = 0; dimPcts.clear(); dimTargets.forEach { dimPcts[it.id] = pctOf(it) }
        dim = t; dimPct = pctOf(t); dimSent = dimPct; dimHeld = true
    }
    fun pickDim(i: Int) {
        val d = dim ?: return
        if (dimTargets.size < 2) return
        if (dimPct != dimSent) onAction("bright:${d.id}:$dimPct")   // send the one being left at once
        dimPcts[d.id] = dimPct
        dimSel = i.coerceIn(0, dimTargets.size - 1); val t = dimTargets[dimSel]
        dim = t; dimPct = dimPcts[t.id] ?: pctOf(t); dimSent = dimPct
    }
    LaunchedEffect(dimPct, dim) {
        val t = dim ?: return@LaunchedEffect
        if (dimPct == dimSent) return@LaunchedEffect
        delay(300); dimSent = dimPct; onAction("bright:${t.id}:$dimPct")
    }

    Box(
        Modifier.fillMaxSize()
            .background(Brush.horizontalGradient(listOf(Color.Transparent, Color(0x99000000))))
            .onPreviewKeyEvent { e ->
                if (dim == null && live != null && (e.key == Key.Back || e.key == Key.Escape)) {
                    if (e.type == KeyEventType.KeyUp) { val cam = live!!; live = null
                        onCamera(cam, spec.cameras.firstOrNull { it.entity == cam }?.rtsp?.takeIf { it.isNotBlank() }); onClose() }
                    true
                } else if (dim != null) {
                    when {
                        e.type != KeyEventType.KeyDown && !(e.isOk() || e.key == Key.Back || e.key == Key.Escape) -> {}
                        e.key == Key.DirectionUp && e.type == KeyEventType.KeyDown -> dimPct = (dimPct + 10).coerceAtMost(100)
                        e.key == Key.DirectionDown && e.type == KeyEventType.KeyDown -> dimPct = (dimPct - 10).coerceAtLeast(0)
                        e.key == Key.DirectionLeft && e.type == KeyEventType.KeyDown -> pickDim(dimSel - 1)
                        e.key == Key.DirectionRight && e.type == KeyEventType.KeyDown -> pickDim(dimSel + 1)
                        e.isOk() && dimHeld -> { if (e.type == KeyEventType.KeyUp) dimHeld = false }
                        (e.isOk() || e.key == Key.Back || e.key == Key.Escape) && e.type == KeyEventType.KeyUp -> {
                            // close; send the last value at once if the pause hadn't sent it yet
                            if (dimPct != dimSent) { dimSent = dimPct; onAction("bright:${dim!!.id}:$dimPct") }
                            dim = null
                        }
                    }
                    true
                } else if (e.key == Key.Back || e.key == Key.Escape) { if (e.type == KeyEventType.KeyUp) back(); true } else false
            }
    ) {
        // drawn before the panel so the panel overlaps its right edge: the camera looks like it extends out of it
        live?.takeIf { page != "catchup" }?.let { e ->
            val cam = spec.cameras.firstOrNull { it.entity == e }
            LiveCamera(e, cam?.name ?: e.substringAfter('.').replace('_', ' ').replaceFirstChar { it.uppercase() }, cam?.rtspSub ?: "",
                Modifier.align(Alignment.BottomEnd).padding(end = 358.dp, bottom = 34.dp))
        }
        when (page) {
            "catchup" -> CatchupPage(spec, onAction = onAction, onClose = onClose, menu = tvMenu, onMenu = { tvMenu = it })
            else -> Box(
                Modifier.align(Alignment.CenterEnd).padding(20.dp).width(350.dp).fillMaxHeight()
                    .graphicsLayer { translationX = slide * size.width * 1.1f }
                    .clip(RoundedCornerShape(26.dp)).background(PanelBrush)
                    .border(1.dp, white(.12f), RoundedCornerShape(26.dp))
            ) {
                if (page == "notifications") NotificationsPage(notes, onBack = { back() }, onOpen = ::openNote, onDismiss = ::dismiss,
                    onClearAll = { notes.forEach { dismiss(it) }; page = "home" })
                else HomePage(spec, notes, onOpenStack = { page = "notifications" }, onOpen = ::openNote,
                    onCatchup = { catchupFrom = "home"; page = "catchup" },
                    openGroup = openGroup, onGroup = { openGroup = if (openGroup == it) null else it }, onAction = onAction, onCamera = { live = if (live == it) null else it },
                    onDim = { openDim(it) })
                dim?.let { Dimmer(it.title, dimPct, dimTargets.map { t -> t.title to (if (t.id == it.id) dimPct else dimPcts[t.id] ?: 0) }, dimSel) }
            }
        }
    }
}

// ============================================================ D2: home
@Composable
private fun HomePage(
    spec: ControlCenterSpec, notes: List<CcNotification>,
    onOpenStack: () -> Unit, onOpen: (CcNotification) -> Unit, onAction: (String) -> Unit, onCamera: (String) -> Unit,
    onDim: (CcTile) -> Unit = {}, onCatchup: () -> Unit = {},
    openGroup: String? = null, onGroup: (String) -> Unit = {},
) {
    var clock by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { while (true) { clock = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date()); delay(10_000) } }
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(4_000); tick++ } }
    val first = remember { FocusRequester() }
    var hasFocus by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { delay(80); runCatching { first.requestFocus() } }
    // An update can remove the item that had focus (e.g. the last notification cleared on the phone): move focus to
    // the first item still on screen so the remote keeps working, without stealing it otherwise.
    LaunchedEffect(notes.isEmpty(), spec.cameras.size, spec.tiles.size, openGroup) { delay(120); if (!hasFocus) runCatching { first.requestFocus() } }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 18.dp).onFocusChanged { hasFocus = it.hasFocus },
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(clock, color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Bold)
        if (spec.subtitle.isNotBlank()) Text(spec.subtitle, color = white(.65f), fontSize = 14.sp, modifier = Modifier.offset(y = (-8).dp))
        spec.now?.let { n ->
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(white(.08f)).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Net(n.image, Modifier.size(70.dp, 40.dp).clip(RoundedCornerShape(8.dp)))
                Column(Modifier.padding(start = 10.dp)) {
                    Text(n.label, color = Amber, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    Text(n.title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (n.sub.isNotBlank()) Text(n.sub, color = white(.6f), fontSize = 12.sp, maxLines = 1)
                }
            }
        }
        // notification stack (collapsed): top card + two edges peeking behind
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Notifications", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            if (notes.isNotEmpty()) Text("${notes.size} new", color = Ink, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.clip(RoundedCornerShape(50)).background(Amber).padding(horizontal = 8.dp, vertical = 2.dp))
        }
        if (notes.isEmpty()) {
            Text("You're all caught up", color = white(.5f), fontSize = 14.sp, modifier = Modifier.padding(vertical = 8.dp))
        } else Box(Modifier.fillMaxWidth().height(if (notes.size > 1) 92.dp else 80.dp)) {
            if (notes.size > 2) Box(Modifier.align(Alignment.BottomCenter).padding(horizontal = 18.dp).fillMaxWidth().height(20.dp).clip(RoundedCornerShape(12.dp)).background(white(.06f)))
            if (notes.size > 1) Box(Modifier.align(Alignment.BottomCenter).padding(horizontal = 9.dp).offset(y = (-6).dp).fillMaxWidth().height(20.dp).clip(RoundedCornerShape(13.dp)).background(white(.10f)))
            Focusable(Modifier.fillMaxWidth().height(80.dp), RoundedCornerShape(14.dp), bg = Color(0xFF332B45), focusedBg = Color(0xFF443A5A),
                requester = first, onOk = { if (notes.size == 1) onOpen(notes[0]) else onOpenStack() }) {
                NoteBody(notes[0], compact = true)
            }
        }
        if (notes.size > 1) Text("+${notes.size - 1} more · " + notes.drop(1).take(3).joinToString(", ") { it.title.take(18) },
            color = white(.45f), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        // cameras strip
        if (spec.cameras.isNotEmpty()) {
            Text(spec.cameraStatus.ifBlank { "Cameras" }, color = white(.75f), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            // Every camera as a sideways strip: about 3½ tiles show, so the next one peeks in; ◀ ▶ scroll it. Only the
            // tiles on screen are composed, so only those refresh their frame every 4 s.
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                items(spec.cameras, key = { it.entity }) { c ->
                    Focusable(Modifier.width(84.dp).height(60.dp), RoundedCornerShape(10.dp), bg = Color.Black,
                        requester = if (notes.isEmpty() && c == spec.cameras.first()) first else null, onOk = { onCamera(c.entity) }) { f ->
                        Net(c.image, Modifier.fillMaxSize(), tick)
                        Text(c.name, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1,
                            modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth().background(Color(0x99000000)).padding(horizontal = 5.dp, vertical = 2.dp))
                    }
                }
            }
        }
        // quick tiles
        if (spec.tiles.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            spec.tiles.take(4).forEach { t -> key(t.id) {
                QuickTile(t, requester = if (notes.isEmpty() && spec.cameras.isEmpty() && t == spec.tiles.first()) first else null,
                    open = openGroup == t.id,
                    // a group opens its lights; a single light opens the brightness slider
                    onLong = if (t.id.startsWith("light.")) ({ onDim(t) }) else null,
                    onAction = onAction)
            } }
        }
        // TV: always here, so clearing notifications never loses the guide's catch-up picks
        val cu = spec.catchup.flatMap { it.items }
        if (cu.isNotEmpty()) Focusable(Modifier.fillMaxWidth().height(68.dp), RoundedCornerShape(14.dp), bg = white(.08f), focusedBg = white(.20f),
            requester = if (notes.isEmpty() && spec.cameras.isEmpty() && spec.tiles.isEmpty()) first else null, onOk = onCatchup) {
            Row(Modifier.fillMaxSize().padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Image(painterResource(ccIcon("television_play")), null, Modifier.size(18.dp), colorFilter = ColorFilter.tint(Amber))
                Column(Modifier.weight(1f).padding(start = 8.dp)) {
                    Text("TV", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    Text(spec.catchup.take(3).joinToString(" · ") { it.title } + " · " + cu.take(2).joinToString(", ") { it.title }, color = white(.6f), fontSize = 11.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    cu.take(3).forEach { c -> key(c.id) { Net(c.img, Modifier.size(26.dp, 38.dp).clip(RoundedCornerShape(4.dp))) } }
                }
            }
        }
        Spacer(Modifier.weight(1f))
        Text("OK open · hold OK brightness · Back close", color = white(.35f), fontSize = 11.sp, maxLines = 1)
    }
}

/** A quick tile. Flips at once on OK; the live update from HA then confirms it. If HA didn't follow within 4 s (device
 *  offline), it falls back to the real state instead of showing a wrong tile. `open`: a group whose lights are showing. */
@Composable
private fun RowScope.QuickTile(
    t: CcTile, requester: FocusRequester?, open: Boolean = false, onLong: (() -> Unit)?, onAction: (String) -> Unit,
) {
    var on by remember(t.on) { mutableStateOf(t.on) }
    LaunchedEffect(on, t.on) { if (on != t.on) { delay(4_000); on = t.on } }
    Focusable(Modifier.weight(1f).height(84.dp).then(if (open) Modifier.border(2.dp, Amber, RoundedCornerShape(14.dp)) else Modifier),
        RoundedCornerShape(14.dp),
        bg = if (on) white(.92f) else white(.10f), focusedBg = if (on) Color.White else white(.22f),
        requester = requester, onLong = onLong,
        onOk = { on = !on; onAction("tile:${t.id}") }) {
        Column(Modifier.fillMaxSize().padding(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Image(painterResource(ccIcon(t.icon)), null, Modifier.size(18.dp), colorFilter = ColorFilter.tint(if (on) Color(0xFFF59E0B) else Color.White))
                Spacer(Modifier.weight(1f))
                if (t.members.isNotEmpty()) Text(if (open) "▴" else "${t.members.size}", color = if (on) Ink.copy(alpha = .5f) else white(.5f), fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.weight(1f))
            Text(t.title, color = if (on) Ink else Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (t.sub.isNotBlank()) Text(t.sub, color = if (on) Ink.copy(alpha = .6f) else white(.6f), fontSize = 11.sp, maxLines = 1)
        }
    }
}

@Composable
private fun NoteBody(n: CcNotification, compact: Boolean) {
    Row(Modifier.fillMaxSize().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconTile(n.icon, 18.dp, n.color)
                Text(n.app, color = white(.6f), fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 6.dp).weight(1f), maxLines = 1)
                Text(n.whenText, color = white(.5f), fontSize = 11.sp)
            }
            Text(n.title, color = Color.White, fontSize = if (compact) 12.sp else 13.sp, fontWeight = FontWeight.Bold, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
            if (n.body.isNotBlank()) Text(n.body, color = white(.65f), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (n.thumbs.isNotEmpty()) Row(Modifier.padding(start = 6.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            n.thumbs.take(3).forEach { Net(it, Modifier.size(22.dp, 32.dp).clip(RoundedCornerShape(4.dp))) }
        }
    }
}

// ============================================================ D3: notifications list
@Composable
private fun NotificationsPage(
    notes: List<CcNotification>, onBack: () -> Unit, onOpen: (CcNotification) -> Unit,
    onDismiss: (CcNotification) -> Unit, onClearAll: () -> Unit,
) {
    val first = remember { FocusRequester() }
    var hasFocus by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { delay(60); runCatching { first.requestFocus() } }
    // A dismissed or remotely cleared notification takes its focus with it: put focus back on the top of the list
    LaunchedEffect(notes.size) { delay(120); if (!hasFocus) runCatching { first.requestFocus() } }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 18.dp).onFocusChanged { hasFocus = it.hasFocus }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Image(painterResource(ccIcon("chevron_left")), null, Modifier.size(22.dp), colorFilter = ColorFilter.tint(Amber))
            Text("Notifications", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Focusable(Modifier.height(24.dp), RoundedCornerShape(12.dp), requester = if (notes.isEmpty()) first else null, onOk = onClearAll) {
                Text("Clear all", color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.align(Alignment.Center).padding(horizontal = 10.dp))
            }
        }
        Spacer(Modifier.height(12.dp))
        if (notes.isEmpty()) Text("You're all caught up", color = white(.5f), fontSize = 12.sp)
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f), contentPadding = PaddingValues(4.dp)) {
            itemsIndexed(notes, key = { _, n -> n.id }) { i, n ->
                Focusable(Modifier.fillMaxWidth().height(70.dp), RoundedCornerShape(14.dp), bg = white(.08f), focusedBg = white(.20f),
                    requester = if (i == 0) first else null,
                    onKey = { e -> if ((e.key == Key.DirectionLeft || e.key == Key.DirectionRight) && e.type == KeyEventType.KeyDown && e.nativeKeyEvent.isLongPress.not()) {
                        // Left / right dismisses (like swiping a notification away); keep focus on the list
                        onDismiss(n); true } else false },
                    onOk = { onOpen(n) }) { NoteBody(n, compact = false) }
            }
        }
        Text("OK open · ◀ ▶ dismiss · Back", color = white(.35f), fontSize = 8.sp, modifier = Modifier.padding(top = 6.dp))
    }
}

// ============================================================ D4: TV page (full screen)
private fun okLabel(ok: String) = when {
    ok.startsWith("watch_ch:") -> "Watch"
    ok.startsWith("stream:") -> "Stream"
    ok.startsWith("catchup_play:") -> "Catch up"
    ok.startsWith("tvfb:remind:") -> "Remind me"
    else -> ""
}
private val MENU_LABEL = mapOf("remind" to "Remind me", "follow" to "Follow", "like" to "Like", "dislike" to "Not interested")

/** TV page filter, remembered while the app runs: "mine" = My shows, "foryou" = mine + recommended, "all" = everything. */
private object TvView { var current = "mine" }
private val TV_VIEWS = listOf("mine" to "My shows", "foryou" to "For you", "all" to "Everything")
private fun tvKeep(view: String, it: CcCatchupItem) = when (view) {
    "mine" -> it.tag == "mine"
    "foryou" -> it.tag == "mine" || it.tag == "pick"
    else -> true
}

@Composable
private fun CatchupPage(
    spec: ControlCenterSpec, onAction: (String) -> Unit, onClose: () -> Unit,
    menu: CcCatchupItem?, onMenu: (CcCatchupItem?) -> Unit,
) {
    // Apple TV style: the focused programme fills the top (big title, where and when, description, its artwork fading
    // in from the right); rows of posters underneath: Up next, On now, Later tonight, New to stream, then the catch-up
    // days. OK does the row's main thing (watch / stream / remind); hold OK for remind / follow / like / not interested.
    // ▲ from the top row reaches the filter chips (My shows · For you · Everything); OK on one switches the rows.
    var view by remember {
        mutableStateOf(TvView.current.let { v -> if (v == "mine" && spec.catchup.none { s -> s.items.any { it.tag == "mine" } }) "foryou" else v })
    }
    val shown = remember(spec.catchup, view) {
        spec.catchup.map { CcSection(it.title, it.items.filter { i -> tvKeep(view, i) }) }.filter { it.items.isNotEmpty() }
    }
    var sel by remember { mutableStateOf(shown.firstOrNull()?.items?.firstOrNull()) }
    var selDay by remember { mutableStateOf(shown.firstOrNull()?.title ?: "") }
    LaunchedEffect(view) { sel = shown.firstOrNull()?.items?.firstOrNull(); selDay = shown.firstOrNull()?.title ?: "" }
    val chip = remember { FocusRequester() }
    var toast by remember { mutableStateOf("") }
    LaunchedEffect(toast) { if (toast.isNotEmpty()) { delay(3_000); toast = "" } }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(60); runCatching { if (shown.isEmpty()) chip.requestFocus() else first.requestFocus() } }
    fun press(it: CcCatchupItem) {
        val ok = it.ok
        when {
            ok.isBlank() -> toast = "Search for ${it.title} in the streaming app"
            ok.startsWith("watch_ch:") || ok.startsWith("stream:") -> { onAction(ok); onClose() }
            ok.startsWith("tvfb:remind:") -> { onAction(ok); toast = "Reminder set · ${it.title}" }
            else -> onAction(ok)
        }
    }
    Box(Modifier.fillMaxSize().background(Color(0xFF0D0A14))) {
        // artwork of the focused programme, top right, fading into the background
        sel?.let { s ->
            Box(Modifier.align(Alignment.TopEnd).fillMaxWidth(.62f).height(300.dp)) {
                key(s.id) { Net(s.backdrop ?: s.img, Modifier.fillMaxSize()) }
                Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(Color(0xFF0D0A14), Color(0x990D0A14), Color(0x330D0A14)))))
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0x000D0A14), Color(0x000D0A14), Color(0xFF0D0A14)))))
            }
        }
        Column(Modifier.fillMaxSize().padding(start = 52.dp, top = 26.dp, bottom = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Image(painterResource(ccIcon("chevron_left")), null, Modifier.size(22.dp), colorFilter = ColorFilter.tint(Amber))
                Text("TV", color = white(.85f), fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(end = 6.dp))
                TV_VIEWS.forEach { (v, label) ->
                    val on = view == v
                    Focusable(Modifier.padding(start = 8.dp).height(30.dp), RoundedCornerShape(50),
                        bg = if (on) Amber else white(.10f), focusedBg = if (on) Amber else white(.30f),
                        requester = if (v == TV_VIEWS[0].first) chip else null,
                        onOk = { view = v; TvView.current = v }) {
                        Text(label, color = if (on) Ink else Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                            modifier = Modifier.align(Alignment.Center).padding(horizontal = 14.dp))
                    }
                }
                Text("   " + toast, color = Amber, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
            // focused programme
            Column(Modifier.height(196.dp).widthIn(max = 520.dp).padding(top = 12.dp)) {
                sel?.let { s ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        s.badge?.let { b -> Text(b, color = Ink, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(end = 8.dp).clip(RoundedCornerShape(50)).background(Amber).padding(horizontal = 9.dp, vertical = 2.dp)) }
                        Text(listOf(selDay, s.days).filter { it.isNotBlank() }.joinToString(" · "), color = white(.7f), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    }
                    Text(s.title, color = Color.White, fontSize = 32.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 6.dp))
                    Text(s.detail.ifBlank { s.sub }, color = white(.85f), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
                    s.prog?.let { p ->
                        Box(Modifier.padding(top = 6.dp).width(220.dp).height(4.dp).clip(RoundedCornerShape(2.dp)).background(white(.18f))) {
                            Box(Modifier.fillMaxHeight().fillMaxWidth(p.coerceIn(0f, 1f)).background(Amber))
                        }
                    }
                    if (s.summary.isNotBlank()) Text(s.summary, color = white(.72f), fontSize = 13.sp, lineHeight = 18.sp, maxLines = 3,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
                    Spacer(Modifier.weight(1f))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val l = okLabel(s.ok)
                        if (l.isNotEmpty()) Text("OK  $l", color = Ink, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(end = 10.dp).clip(RoundedCornerShape(50)).background(Amber).padding(horizontal = 14.dp, vertical = 5.dp))
                        Text((if (s.menu.isNotEmpty()) "hold OK for more · " else "") + "◀ ▶ programmes · ▲ ▼ rows · ▲ at top: filter · Back", color = white(.5f), fontSize = 11.sp)
                    }
                }
            }
            if (shown.isEmpty()) Text(if (view == "mine") "Nothing from your shows right now · press ▲ and pick For you" else "Nothing here yet",
                color = white(.6f), fontSize = 16.sp, modifier = Modifier.padding(top = 12.dp))
            LazyColumn(Modifier.weight(1f).padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                itemsIndexed(shown, key = { _, sec -> view + sec.title }) { si, sec ->
                    Column {
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text(sec.title, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                            Text("  ${sec.items.size}", color = white(.55f), fontSize = 12.sp, modifier = Modifier.padding(bottom = 2.dp))
                        }
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp), contentPadding = PaddingValues(top = 10.dp, bottom = 6.dp, start = 4.dp, end = 52.dp)) {
                            itemsIndexed(sec.items, key = { _, it -> it.id }) { ii, it ->
                                PosterCard(it, requester = if (si == 0 && ii == 0) first else null,
                                    onFocus = { sel = it; selDay = sec.title }, onOk = { press(it) },
                                    onLong = if (it.menu.isNotEmpty() || it.ok.isNotBlank()) ({ onMenu(it) }) else null)
                            }
                        }
                    }
                }
            }
        }
        menu?.let { m ->
            TvMenu(m, onPick = { act ->
                onMenu(null)
                if (act == "ok") press(m)
                else {
                    onAction("tvfb:$act:${m.key}")
                    toast = when (act) { "remind" -> "Reminder set"; "follow" -> "Following"; "like" -> "Liked"; else -> "Hidden from now on" } + " · ${m.title}"
                }
            })
        }
    }
}

/** Hold-OK menu over the TV page: the row's main action, then remind / follow / like / not interested. */
@Composable
private fun TvMenu(m: CcCatchupItem, onPick: (String) -> Unit) {
    val first = remember(m.id) { FocusRequester() }
    var held by remember(m.id) { mutableStateOf(true) }        // the OK that opened it is still down: ignore its release
    LaunchedEffect(m.id) { delay(60); runCatching { first.requestFocus() } }
    val opts = (if (okLabel(m.ok).isNotEmpty()) listOf("ok" to okLabel(m.ok)) else emptyList()) +
        m.menu.filterNot { it == "remind" && m.ok.startsWith("tvfb:remind:") }      // already the main action
            .mapNotNull { a -> MENU_LABEL[a]?.let { a to it } }
    Box(Modifier.fillMaxSize().background(Color(0xCC0D0A14)), contentAlignment = Alignment.Center) {
        Column(Modifier.width(300.dp).clip(RoundedCornerShape(20.dp)).background(PanelBrush).border(1.dp, white(.12f), RoundedCornerShape(20.dp))
            .padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(m.title, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(m.sub, color = white(.6f), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(bottom = 4.dp))
            opts.forEachIndexed { i, (a, label) ->
                Focusable(Modifier.fillMaxWidth().height(40.dp), RoundedCornerShape(12.dp), requester = if (i == 0) first else null,
                    onKey = { e -> if (held && e.isOk()) { if (e.type == KeyEventType.KeyUp) held = false; true } else false },
                    onOk = { onPick(a) }) {
                    Text(label, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.align(Alignment.CenterStart).padding(horizontal = 14.dp))
                }
            }
            Text("OK choose · Back", color = white(.4f), fontSize = 9.sp, modifier = Modifier.padding(top = 2.dp))
        }
    }
}

@Composable
private fun PosterCard(it: CcCatchupItem, requester: FocusRequester?, onFocus: () -> Unit, onOk: () -> Unit, onLong: (() -> Unit)? = null) {
    var focused by remember { mutableStateOf(false) }
    var longFired by remember { mutableStateOf(false) }
    val s by animateFloatAsState(if (focused) 1.08f else 1f, tween(140), label = "pc")
    Column(Modifier.width(112.dp)) {
        Box(
            Modifier.size(112.dp, 160.dp).graphicsLayer { scaleX = s; scaleY = s }.clip(RoundedCornerShape(12.dp))
                .background(Brush.verticalGradient(listOf(Color(0xFF2A2145), Color(0xFF15111F))))
                .border(if (focused) 3.dp else 0.dp, if (focused) Amber else Color.Transparent, RoundedCornerShape(12.dp))
                .then(if (requester != null) Modifier.focusRequester(requester) else Modifier)
                .onFocusChanged { f -> focused = f.isFocused; if (f.isFocused) onFocus() }
                .onKeyEvent { e ->
                    if (!e.isOk()) false
                    else {
                        // hold OK (key repeat) opens the menu; a short press does the row's main action
                        if (e.type == KeyEventType.KeyDown) {
                            if (e.nativeKeyEvent.repeatCount == 0) longFired = false
                            else if (!longFired && onLong != null) { longFired = true; onLong() }
                        } else if (e.type == KeyEventType.KeyUp) { if (!longFired) onOk(); longFired = false }
                        true
                    }
                }
                .focusable()
        ) {
            if (it.img != null) Net(it.img, Modifier.fillMaxSize())
            else Text(it.title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(10.dp))
            it.badge?.let { b ->
                Text(b, color = Amber, fontSize = 9.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(6.dp).clip(RoundedCornerShape(50)).background(Color(0xE6140F1E)).padding(horizontal = 6.dp, vertical = 1.dp))
            }
            it.prog?.let { p ->
                Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(6.dp).height(4.dp).clip(RoundedCornerShape(2.dp)).background(Color(0x99000000))) {
                    Box(Modifier.fillMaxHeight().fillMaxWidth(p.coerceIn(0f, 1f)).background(Amber))
                }
            }
        }
        // only the focused card spells everything out; the rest stay quiet so the row is easy to scan
        Text(it.title, color = if (focused) Color.White else white(.8f), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
        Text(it.sub, color = white(.55f), fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

// ============================================================ brightness slider (hold OK on a light tile)
@Composable
private fun Dimmer(title: String, pct: Int, parts: List<Pair<String, Int>> = emptyList(), sel: Int = 0) {
    Box(Modifier.fillMaxSize().background(Color(0xE6140F22)), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(title, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Text(if (pct == 0) "Off" else "$pct%", color = Amber, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 2.dp, bottom = 12.dp))
            // tall pill like the iOS Control Centre brightness slider; the lit part grows from the bottom
            Box(Modifier.width(78.dp).height(170.dp).clip(RoundedCornerShape(24.dp)).background(white(.14f)),
                contentAlignment = Alignment.BottomCenter) {
                Box(Modifier.fillMaxWidth().fillMaxHeight(pct / 100f).background(Color(0xFFF5F2FA)))
                Image(painterResource(ccIcon("lightbulb")), null, Modifier.padding(bottom = 14.dp).size(24.dp),
                    colorFilter = ColorFilter.tint(if (pct >= 15) Color(0xFFF59E0B) else Color.White))
            }
            // a group's parts under the slider: the highlighted one is what the slider sets
            if (parts.size > 1) Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                parts.forEachIndexed { i, (name, p) ->
                    Column(Modifier.width(86.dp).clip(RoundedCornerShape(12.dp)).background(if (i == sel) Color.White else white(.12f))
                        .border(if (i == sel) 2.dp else 0.dp, if (i == sel) Amber else Color.Transparent, RoundedCornerShape(12.dp))
                        .padding(horizontal = 8.dp, vertical = 6.dp)) {
                        Text(if (i == 0) "All" else name, color = if (i == sel) Ink else Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(if (p == 0) "Off" else "$p%", color = if (i == sel) Ink.copy(alpha = .6f) else white(.6f), fontSize = 9.sp)
                    }
                }
            }
            Text((if (parts.size > 1) "◀ ▶ pick a light · " else "") + "▲ ▼ adjust · OK done", color = white(.5f), fontSize = 9.sp,
                modifier = Modifier.padding(top = 12.dp))
        }
    }
}

// ============================================================ live camera, extending out of the panel
@Composable
private fun LiveCamera(entity: String, name: String, rtsp: String, modifier: Modifier) {
    val ctx = LocalContext.current
    val url = remember(entity) {
        (dev.trooped.tvquickbars.notification.normalizedHaBase(ctx)?.toString()?.trimEnd('/')
            ?: SecurePrefsManager.getHAUrl(ctx)?.trimEnd('/') ?: "") + "/api/camera_proxy_stream/" + entity
    }
    val token = remember { SecurePrefsManager.getHAToken(ctx) }
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    val slide by animateFloatAsState(if (shown) 0f else 1f, spring(dampingRatio = .85f, stiffness = 320f), label = "liveCam")
    Box(modifier.size(468.dp, 266.dp)
        .graphicsLayer { translationX = slide * 120f; alpha = 1f - slide }
        .clip(RoundedCornerShape(topStart = 24.dp, bottomStart = 24.dp, topEnd = 6.dp, bottomEnd = 6.dp))
        .background(PanelBrush).border(1.dp, white(.12f), RoundedCornerShape(topStart = 24.dp, bottomStart = 24.dp, topEnd = 6.dp, bottomEnd = 6.dp))
    ) {
        key(entity) {
            Box(Modifier.padding(start = 8.dp, top = 8.dp, bottom = 8.dp, end = 14.dp).clip(RoundedCornerShape(18.dp)).fillMaxSize().background(Color.Black)) {
                // Real video (H.264 from Frigate's restream, hardware decoded) when the camera has one; HA's MJPEG proxy
                // (a few stills a second) if not, or if the stream fails.
                var rtspFailed by remember(entity) { mutableStateOf(false) }
                if (rtsp.isNotBlank() && !rtspFailed)
                    dev.trooped.tvquickbars.camera.CameraRtspView(url = rtsp,
                        config = dev.trooped.tvquickbars.camera.RtspProfile(latency = dev.trooped.tvquickbars.camera.StreamLatency.LOW_LATENCY, muteAudio = true),
                        modifier = Modifier.fillMaxSize(), onError = { rtspFailed = true })
                else dev.trooped.tvquickbars.camera.CameraMjpegView(url = url, authToken = token, modifier = Modifier.fillMaxSize())
            }
        }
        Row(Modifier.padding(start = 18.dp, top = 16.dp).clip(RoundedCornerShape(50)).background(Color(0x99000000))
            .padding(horizontal = 9.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(7.dp).clip(RoundedCornerShape(50)).background(Color(0xFFEF4444)))
            Text("  $name · live", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        }
        Text("Back: keep watching in the corner · OK on the tile: close", color = white(.85f), fontSize = 8.sp,
            modifier = Modifier.align(Alignment.BottomStart).padding(start = 18.dp, bottom = 14.dp)
                .clip(RoundedCornerShape(50)).background(Color(0x99000000)).padding(horizontal = 8.dp, vertical = 2.dp))
    }
}
