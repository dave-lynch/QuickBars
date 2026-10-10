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
    var room by remember { mutableStateOf<String?>(null) }   // the room open on the "room" page
    var tvMenu by remember { mutableStateOf<CcCatchupItem?>(null) }
    // Kitchen > Dinners & shopping: tab, the night being changed (dinner picker), the item whose quantity is being set, and
    // presses shown at once until HA's update arrives (cleared whenever new meals data comes in)
    var mealTab by remember { mutableIntStateOf(0) }
    var mealsFrom by remember { mutableStateOf("room") }   // Back from Dinners & shopping: the Kitchen room, or the notifications
    var noteMenu by remember { mutableStateOf<CcNotification?>(null) }   // hold OK on a notification
    var mealPick by remember { mutableStateOf<CcMealNight?>(null) }
    var mealQty by remember { mutableStateOf<CcShopItem?>(null) }
    var mealQtyVal by remember { mutableIntStateOf(1) }
    var mealQtyHeld by remember { mutableStateOf(false) }
    val shopOv = remember { mutableStateMapOf<String, Pair<Boolean, Int>>() }
    LaunchedEffect(spec.meals) { shopOv.clear() }
    // Voice / search on Review shopping: the words sent (shown as "Searching…" until HA's results arrive) and Back's local hide
    val ctx = LocalContext.current
    val voice by CcVoice.ui
    var findSent by remember { mutableStateOf("") }
    var findHide by remember { mutableStateOf(false) }
    val find = spec.meals?.find?.takeIf { !findHide }
    LaunchedEffect(spec.meals?.find?.q) { if (spec.meals?.find != null) { findHide = false; if (spec.meals.find.q == findSent) findSent = "" } }
    LaunchedEffect(voice.error) { if (voice.error.isNotEmpty()) { delay(5000); CcVoice.clearError() } }   // hold-OK menu open on the TV page   // light group held open: its lights show under the tiles   // where Back from catch-up returns to
    val dismissed = remember { mutableStateListOf<String>() }
    val notes = spec.notifications.filter { it.id !in dismissed }
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    val slide by animateFloatAsState(if (shown) 0f else 1f, spring(dampingRatio = .85f, stiffness = 300f), label = "ccSlide")

    // Live camera opened from the panel: shown large, sliding out from behind the panel's bottom-left edge. The first
    // Back hands it to the corner PiP (bottom right) and closes the panel; Back again closes the PiP.
    var live by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(live) { while (live != null) { onKeepAlive(); delay(30_000) } }   // watching isn't "idle"

    fun listen() {
        if (page != "meals") return
        mealTab = 1
        CcVoice.start(ctx) { t -> val q = cleanSpoken(t); if (q.isNotBlank()) { findHide = false; findSent = q; onAction("meals:find:$q") } }
    }
    val listenNow = rememberUpdatedState { listen() }
    DisposableEffect(page) {
        CcVoice.keyHandler = if (page == "meals") ({ listenNow.value() }) else null
        onDispose { CcVoice.keyHandler = null; if (CcVoice.ui.value.listening) CcVoice.cancel() }
    }
    fun closeFind() { findHide = true; findSent = ""; onAction("meals:find:") }

    fun back() { if (page == "home" && openGroup != null) { openGroup = null; return }
                 if (page == "meals" && voice.listening) { CcVoice.cancel(); return }
                 if (page == "meals" && mealTab == 1 && (find != null || findSent.isNotEmpty())) { closeFind(); return }
                 if (page == "catchup" && tvMenu != null) { tvMenu = null; return }
                 if (page == "meals" && mealPick != null) { mealPick = null; return }
                 if (noteMenu != null) { noteMenu = null; return }
                 page = when (page) { "catchup" -> if (spec.page == "catchup") "close" else catchupFrom; "notifications" -> "home"
                                      "room" -> "rooms"; "rooms" -> "home"; "meals" -> mealsFrom; else -> "close" }
                 if (page == "close") onClose() }
    fun openNote(n: CcNotification) {
        when {
            n.open == "page:meals" -> { mealsFrom = page; mealTab = 0; onAction("meals:open"); page = "meals" }
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
    // A TV or speaker held on the Rooms page opens the same slider as its volume ("vol:"), in steps of 2.
    var dimVerb by remember { mutableStateOf("bright") }
    val dimStep = if (dimVerb == "vol") 2 else 10
    fun pctOf(t: CcTile) = if (t.bri >= 0) t.bri else if (t.on) 100 else 0
    // A light group's level is what Home Assistant reports for it: the average of its lights that are on (Spotlights count as
    // two). So "All" follows its parts, and setting "All" sets every part to that level.
    var dimTouched by remember { mutableLongStateOf(0L) }
    fun groupAvg(): Int {
        val parts = dimTargets.drop(1); if (parts.isEmpty()) return dimPct
        val lit = parts.map { (if (it.id == dim?.id) dimPct else dimPcts[it.id] ?: 0) to it.n }.filter { it.first > 0 }
        return if (lit.isEmpty()) 0 else (lit.sumOf { it.first * it.second }.toDouble() / lit.sumOf { it.second }).roundToInt()
    }
    fun shown(i: Int): Int {   // the level each chip shows, live while the slider moves
        val t = dimTargets.getOrNull(i) ?: return 0
        return when {
            t.id == dim?.id -> dimPct
            i == 0 && dimTargets.size > 1 -> groupAvg()
            dimSel == 0 && dimTargets.size > 1 -> dimPct
            else -> dimPcts[t.id] ?: pctOf(t)
        }
    }
    // Colour temperature (lights that have one): a thin strip under the light chips with the room card's presets (Warm, Soft,
    // Neutral, Cool over the light's own range). Hold OK switches ◀ ▶ from picking a light to stepping the presets; ▲ ▼ stays
    // brightness. Sent as ct:<light>:<kelvin>.
    var ctMode by remember { mutableStateOf(false) }
    var ctK by remember { mutableIntStateOf(0) }
    var ctSent by remember { mutableIntStateOf(0) }
    val ctKs = remember { mutableStateMapOf<String, Int>() }
    var okLong by remember { mutableStateOf(false) }   // a hold of OK switched the mode: its release doesn't close
    fun hasCt(t: CcTile?) = t != null && t.kmin > 0 && t.kmax > t.kmin
    fun ctOf(t: CcTile) = if (!hasCt(t)) 0 else (if (t.k > 0) t.k else (t.kmin + t.kmax) / 2).coerceIn(t.kmin, t.kmax)
    fun ctClamp(t: CcTile, k: Int) = if (!hasCt(t)) 0 else k.coerceIn(t.kmin, t.kmax)
    fun ctStep(up: Boolean) {   // ◀ ▶ in colour mode: the next preset warmer / cooler
        val t = dim ?: return; if (!hasCt(t)) return
        val i = CT_PRESETS.indices.minByOrNull { kotlin.math.abs(ctPreset(it, t.kmin, t.kmax) - ctK) } ?: 0
        ctK = ctClamp(t, ctPreset((i + if (up) 1 else -1).coerceIn(0, CT_PRESETS.size - 1), t.kmin, t.kmax))
    }
    fun shownCt(i: Int): Int {
        val t = dimTargets.getOrNull(i) ?: return 0
        return when {
            t.id == dim?.id -> ctK
            dimSel == 0 && dimTargets.size > 1 && i > 0 -> ctClamp(t, ctK)
            else -> ctKs[t.id] ?: ctOf(t)
        }
    }
    fun openDim(t: CcTile) {
        dimTargets = listOf(t) + t.members; dimSel = 0; dimPcts.clear(); dimTargets.forEach { dimPcts[it.id] = pctOf(it) }
        ctKs.clear(); dimTargets.forEach { ctKs[it.id] = ctOf(it) }
        dimVerb = if (t.id.startsWith("media_player.")) "vol" else "bright"
        dim = t; dimPct = pctOf(t); dimSent = dimPct; dimHeld = true; dimTouched = 0L
        ctMode = false; okLong = false; ctK = ctOf(t); ctSent = ctK
    }
    // Fresh levels from Home Assistant (the panel refreshes after a change, or the lights moved some other way) replace the
    // ones shown, unless a button was pressed in the last 2.5 s (then the slider's own value is newer).
    LaunchedEffect(spec.tiles, spec.rooms) {
        val d = dimTargets.firstOrNull() ?: return@LaunchedEffect
        if (dimVerb != "bright" || System.currentTimeMillis() - dimTouched < 2500) return@LaunchedEffect
        val fresh = (spec.tiles + spec.rooms.flatMap { it.devices }).firstOrNull { it.id == d.id } ?: return@LaunchedEffect
        val parts = listOf(fresh) + fresh.members
        if (parts.size != dimTargets.size) return@LaunchedEffect
        dimTargets = parts; parts.forEach { dimPcts[it.id] = pctOf(it); ctKs[it.id] = ctOf(it) }
        val cur = parts[dimSel.coerceIn(0, parts.size - 1)]
        dim = cur; dimPct = pctOf(cur); dimSent = dimPct; ctK = ctOf(cur); ctSent = ctK
    }
    fun pickDim(i: Int) {
        val d = dim ?: return
        if (dimTargets.size < 2) return
        if (dimPct != dimSent) onAction("$dimVerb:${d.id}:$dimPct")   // send the one being left at once
        if (dimSel == 0 && dimTargets.size > 1) dimTargets.drop(1).forEach { dimPcts[it.id] = dimPct }   // "All" set every part
        dimPcts[d.id] = dimPct
        if (dimSel != 0 && dimTargets.size > 1) dimPcts[dimTargets[0].id] = groupAvg()
        if (hasCt(d)) {
            if (ctK != ctSent) onAction("ct:${d.id}:$ctK")
            if (dimSel == 0 && dimTargets.size > 1) dimTargets.drop(1).forEach { ctKs[it.id] = ctClamp(it, ctK) }
            ctKs[d.id] = ctK
        }
        dimSel = i.coerceIn(0, dimTargets.size - 1); val t = dimTargets[dimSel]
        dim = t; dimPct = dimPcts[t.id] ?: pctOf(t); dimSent = dimPct
        ctK = ctKs[t.id] ?: ctOf(t); ctSent = ctK; if (!hasCt(t)) ctMode = false
    }
    LaunchedEffect(dimPct, dim) {
        val t = dim ?: return@LaunchedEffect
        if (dimPct == dimSent) return@LaunchedEffect
        delay(300); dimSent = dimPct; onAction("$dimVerb:${t.id}:$dimPct")
    }
    LaunchedEffect(ctK, dim) {
        val t = dim ?: return@LaunchedEffect
        if (!hasCt(t) || ctK == ctSent) return@LaunchedEffect
        delay(300); ctSent = ctK; onAction("ct:${t.id}:$ctK")
    }

    Box(
        Modifier.fillMaxSize()
            .background(Brush.horizontalGradient(listOf(Color.Transparent, Color(0x99000000))))
            .onPreviewKeyEvent { e ->
                if (dim == null && live != null && (e.key == Key.Back || e.key == Key.Escape)) {
                    if (e.type == KeyEventType.KeyUp) { val cam = live!!; live = null
                        onCamera(cam, spec.cameras.firstOrNull { it.entity == cam }?.rtsp?.takeIf { it.isNotBlank() }); onClose() }
                    true
                } else if (mealQty != null) {
                    val it0 = mealQty!!
                    when {
                        e.key == Key.DirectionUp && e.type == KeyEventType.KeyDown -> mealQtyVal = (mealQtyVal + 1).coerceAtMost(20)
                        e.key == Key.DirectionDown && e.type == KeyEventType.KeyDown -> mealQtyVal = (mealQtyVal - 1).coerceAtLeast(1)
                        e.isOk() && mealQtyHeld -> { if (e.type == KeyEventType.KeyUp) mealQtyHeld = false }
                        (e.isOk() || e.key == Key.Back || e.key == Key.Escape) && e.type == KeyEventType.KeyUp -> {
                            if (mealQtyVal != (shopOv[it0.id]?.second ?: it0.q)) {
                                shopOv[it0.id] = (shopOv[it0.id]?.first ?: it0.on) to mealQtyVal; onAction("meals:q:${it0.id}:$mealQtyVal")
                            }
                            mealQty = null
                        }
                    }
                    true
                } else if (dim != null) {
                    when {
                        e.type != KeyEventType.KeyDown && !(e.isOk() || e.key == Key.Back || e.key == Key.Escape) -> {}
                        e.key == Key.DirectionUp && e.type == KeyEventType.KeyDown -> { dimTouched = System.currentTimeMillis(); dimPct = (dimPct + dimStep).coerceAtMost(100) }
                        e.key == Key.DirectionDown && e.type == KeyEventType.KeyDown -> { dimTouched = System.currentTimeMillis(); dimPct = (dimPct - dimStep).coerceAtLeast(0) }
                        e.key == Key.DirectionLeft && e.type == KeyEventType.KeyDown -> { dimTouched = System.currentTimeMillis(); if (ctMode) ctStep(false) else pickDim(dimSel - 1) }
                        e.key == Key.DirectionRight && e.type == KeyEventType.KeyDown -> { dimTouched = System.currentTimeMillis(); if (ctMode) ctStep(true) else pickDim(dimSel + 1) }
                        e.isOk() && dimHeld -> { if (e.type == KeyEventType.KeyUp) dimHeld = false }
                        // hold OK: brightness <-> colour (a light with a colour temperature); the release then doesn't close
                        e.isOk() && e.type == KeyEventType.KeyDown && e.nativeKeyEvent.repeatCount == 1 && hasCt(dim) -> {
                            okLong = true; ctMode = !ctMode; dimTouched = System.currentTimeMillis() }
                        e.isOk() && e.type == KeyEventType.KeyUp && okLong -> okLong = false
                        (e.isOk() || e.key == Key.Back || e.key == Key.Escape) && e.type == KeyEventType.KeyUp -> {
                            // close; send the last value at once if the pause hadn't sent it yet
                            if (dimPct != dimSent) { dimSent = dimPct; onAction("$dimVerb:${dim!!.id}:$dimPct") }
                            if (hasCt(dim) && ctK != ctSent) { ctSent = ctK; onAction("ct:${dim!!.id}:$ctK") }
                            dim = null
                        }
                    }
                    true
                } else if (e.key == Key.Back || e.key == Key.Escape) { if (e.type == KeyEventType.KeyUp) back(); true } else false
            }
    ) {
        // drawn before the panel so the panel overlaps its right edge: the camera looks like it extends out of it
        live?.takeIf { page == "home" || page == "notifications" }?.let { e ->
            val cam = spec.cameras.firstOrNull { it.entity == e }
            LiveCamera(e, cam?.name ?: e.substringAfter('.').replace('_', ' ').replaceFirstChar { it.uppercase() }, cam?.rtspSub ?: "",
                Modifier.align(Alignment.BottomEnd).padding(end = 358.dp, bottom = 34.dp),
                wake = cam?.wake == true, awake = cam?.awake == true, onAction = onAction)
        }
        when (page) {
            "catchup" -> CatchupPage(spec, onAction = onAction, onClose = onClose, menu = tvMenu, onMenu = { tvMenu = it })
            "meals" -> {
                MealsPage(spec.meals, tab = mealTab, onTab = { mealTab = it }, onAction = onAction, overrides = shopOv,
                    onPick = { mealPick = it }, onQty = { i -> mealQty = i; mealQtyVal = shopOv[i.id]?.second ?: i.q; mealQtyHeld = true },
                    find = find, searching = findSent, onVoice = { listen() }, onCloseFind = { closeFind() })
                VoiceBubble(voice, Modifier.align(Alignment.TopCenter).padding(top = 70.dp))
                mealPick?.let { n -> MealPicker(n, spec.meals?.choices ?: emptyList(), onPick = { v -> onAction("meals:set:${n.date}:$v"); mealPick = null }) }
                mealQty?.let { i -> QtyPopup(i.name, mealQtyVal, i.price) }
            }
            "rooms", "room" -> {
                val r = spec.rooms.firstOrNull { it.id == room }
                if (page == "room" && r != null) RoomPage(r, onAction = onAction, onDim = { openDim(it) },
                    onPage = { pg -> if (pg == "meals") { mealsFrom = "room"; mealTab = 0; onAction("meals:open") }; page = pg })
                else RoomsPage(spec.rooms, onOpen = { room = it; page = "room" }, from = room)
                dim?.let { Dimmer(it.title, dimPct, dimTargets.mapIndexed { i, t -> t.title to shown(i) }, dimSel, volume = dimVerb == "vol",
                    ct = if (hasCt(it)) CtView(ctMode, ctK, it.kmin, it.kmax, dimTargets.indices.map { i -> shownCt(i) }) else null) }
            }
            else -> Box(
                Modifier.align(Alignment.CenterEnd).padding(20.dp).width(350.dp).fillMaxHeight()
                    .graphicsLayer { translationX = slide * size.width * 1.1f }
                    .clip(RoundedCornerShape(26.dp)).background(PanelBrush)
                    .border(1.dp, white(.12f), RoundedCornerShape(26.dp))
            ) {
                if (page == "notifications") NotificationsPage(notes, onBack = { back() }, onOpen = ::openNote, onDismiss = ::dismiss, onMenu = { noteMenu = it },
                    onClearAll = { notes.forEach { dismiss(it) }; page = "home" })
                else HomePage(spec, notes, onOpenStack = { page = "notifications" }, onOpen = ::openNote, onMenu = { noteMenu = it },
                    onCatchup = { catchupFrom = "home"; page = "catchup" }, onRooms = { room = null; page = "rooms" },
                    openGroup = openGroup, onGroup = { openGroup = if (openGroup == it) null else it }, onAction = onAction, onCamera = { live = if (live == it) null else it },
                    onDim = { openDim(it) })
                dim?.let { Dimmer(it.title, dimPct, dimTargets.mapIndexed { i, t -> t.title to shown(i) }, dimSel,
                    ct = if (hasCt(it)) CtView(ctMode, ctK, it.kmin, it.kmax, dimTargets.indices.map { i -> shownCt(i) }) else null) }
                noteMenu?.let { n -> NoteMenu(n, onPick = { a -> noteMenu = null
                    when (a) {
                        "dismiss" -> dismiss(n)
                        "meals" -> { mealsFrom = page; mealTab = 0; onAction("meals:open"); page = "meals" }
                        else -> { dismissed += n.id; onAction("notif_act:${n.id}:$a") }
                    } }) }
            }
        }
    }
}

// ============================================================ D2: home
@Composable
private fun HomePage(
    spec: ControlCenterSpec, notes: List<CcNotification>,
    onOpenStack: () -> Unit, onOpen: (CcNotification) -> Unit, onAction: (String) -> Unit, onCamera: (String) -> Unit, onMenu: (CcNotification) -> Unit = {},
    onDim: (CcTile) -> Unit = {}, onCatchup: () -> Unit = {}, onRooms: () -> Unit = {},
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

    // Scrolls when everything doesn't fit (now playing + notifications + cameras + tiles + TV / Rooms): moving focus down
    // brings the next row into view, so nothing at the bottom is ever cut off. The key hint stays pinned below.
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 18.dp).onFocusChanged { hasFocus = it.hasFocus }) {
    Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
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
                requester = first, onLong = { onMenu(notes[0]) }, onOk = { if (notes.size == 1) onOpen(notes[0]) else onOpenStack() }) {
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
        // TV and Rooms share one row (each half width), so both fit under the cameras and tiles
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (cu.isNotEmpty()) Focusable(Modifier.weight(1f).height(68.dp), RoundedCornerShape(14.dp), bg = white(.08f), focusedBg = white(.20f),
            requester = if (notes.isEmpty() && spec.cameras.isEmpty() && spec.tiles.isEmpty()) first else null, onOk = onCatchup) {
            Row(Modifier.fillMaxSize().padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Image(painterResource(ccIcon("television_play")), null, Modifier.size(18.dp), colorFilter = ColorFilter.tint(Amber))
                Column(Modifier.weight(1f).padding(start = 8.dp)) {
                    Text("TV", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    Text(spec.catchup.take(3).joinToString(" · ") { it.title } + " · " + cu.take(2).joinToString(", ") { it.title }, color = white(.6f), fontSize = 11.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    cu.take(2).forEach { c -> key(c.id) { Net(c.img, Modifier.size(24.dp, 36.dp).clip(RoundedCornerShape(4.dp))) } }
                }
            }
        }
        // Rooms: every room's devices, like the main Home Assistant dashboard
        if (spec.rooms.isNotEmpty()) Focusable(Modifier.weight(1f).height(68.dp), RoundedCornerShape(14.dp), bg = white(.08f), focusedBg = white(.20f),
            requester = if (notes.isEmpty() && spec.cameras.isEmpty() && spec.tiles.isEmpty() && spec.catchup.isEmpty()) first else null, onOk = onRooms) {
            Row(Modifier.fillMaxSize().padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Image(painterResource(ccIcon("home_outline")), null, Modifier.size(18.dp), colorFilter = ColorFilter.tint(Amber))
                Column(Modifier.weight(1f).padding(start = 8.dp)) {
                    Text("Rooms", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    Text(spec.rooms.take(3).joinToString(" · ") { it.name } + (if (spec.rooms.size > 3) " · +${spec.rooms.size - 3}" else ""),
                        color = white(.6f), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text("›", color = white(.5f), fontSize = 18.sp)
            }
        }
        }
    }
        Text("OK open · hold OK brightness · Back close", color = white(.35f), fontSize = 11.sp, maxLines = 1, modifier = Modifier.padding(top = 6.dp))
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
    onDismiss: (CcNotification) -> Unit, onClearAll: () -> Unit, onMenu: (CcNotification) -> Unit = {},
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
                    onLong = { onMenu(n) }, onOk = { onOpen(n) }) { NoteBody(n, compact = false) }
            }
        }
        Text("OK open · hold OK for options · ◀ ▶ dismiss · Back", color = white(.35f), fontSize = 8.sp, modifier = Modifier.padding(top = 6.dp))
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
private val TV_VIEWS = listOf("mine" to "My shows", "foryou" to "Recommended", "all" to "Everything")
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
    // ▲ from the top row reaches the filter chips (My shows · Recommended · Everything); OK on one switches the rows.
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
            if (shown.isEmpty()) Text(if (view == "mine") "Nothing from your shows right now · press ▲ and pick Recommended" else "Nothing here yet",
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

// ============================================================ D5: Rooms (full screen), like the main HA dashboard
@Composable
private fun RoomsPage(rooms: List<CcRoom>, onOpen: (String) -> Unit, from: String? = null) {
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
private fun RoomPage(r: CcRoom, onAction: (String) -> Unit, onDim: (CcTile) -> Unit, onPage: (String) -> Unit = {}) {
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
private fun RowScope.RoomTile(d: CcTile, requester: FocusRequester?, onAction: (String) -> Unit, onLong: (() -> Unit)?) {
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
private fun TempChart(pts: List<Float>, modifier: Modifier) {
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


/** Hold OK on a notification: Open / Dismiss (TV only) plus what HA offers for it (Open Dinners & shopping, Ignore everywhere,
 *  Not in the house: remove the item). Drawn over the panel; Back closes it. */
@Composable
private fun NoteMenu(n: CcNotification, onPick: (String) -> Unit) {
    val first = remember { FocusRequester() }
    LaunchedEffect(n.id) { delay(60); runCatching { first.requestFocus() } }
    val opts = n.actions + ("dismiss" to "Dismiss on the TV")
    Box(Modifier.fillMaxSize().background(Color(0xE6140F22)).padding(14.dp), contentAlignment = Alignment.Center) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(n.title, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(n.app, color = white(.55f), fontSize = 10.sp, modifier = Modifier.padding(bottom = 4.dp))
            opts.forEachIndexed { i, (id, label) ->
                Focusable(Modifier.fillMaxWidth().height(40.dp), RoundedCornerShape(12.dp), requester = if (i == 0) first else null, onOk = { onPick(id) }) {
                    Text(label, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.align(Alignment.CenterStart).padding(horizontal = 14.dp))
                }
            }
            Text("OK choose · Back cancel", color = white(.4f), fontSize = 9.sp)
        }
    }
}
// ============================================================ Kitchen > Dinners & shopping (a TV-only draft in HA)
/** Three tabs: the nights the next order covers (OK changes a night's dinner), the suggested shopping by section (OK ticks an item
 *  in or out, hold OK sets the quantity) and the total. Everything comes from HA (tv_meals.py); presses go back as meals:<...>. */
@Composable
private fun MealsPage(
    m: CcMeals?, tab: Int, onTab: (Int) -> Unit, onAction: (String) -> Unit, overrides: Map<String, Pair<Boolean, Int>>,
    onPick: (CcMealNight) -> Unit, onQty: (CcShopItem) -> Unit,
    find: CcFind? = null, searching: String = "", onVoice: () -> Unit = {}, onCloseFind: () -> Unit = {},
) {
    val tabs = listOf("Dinners", "Review shopping", "Total")
    val tabReq = remember { tabs.map { FocusRequester() } }
    Column(Modifier.fillMaxSize().background(Color(0xFF0D0A14)).padding(start = 44.dp, end = 44.dp, top = 22.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Image(painterResource(ccIcon("chevron_left")), null, Modifier.size(22.dp), colorFilter = ColorFilter.tint(Amber))
            Text("Kitchen · ", color = white(.55f), fontSize = 14.sp)
            Text("Dinners & shopping", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            tabs.forEachIndexed { i, t ->
                Focusable(Modifier.padding(start = 8.dp).height(36.dp), RoundedCornerShape(18.dp),
                    bg = if (i == tab) Amber else white(.10f), focusedBg = if (i == tab) Color(0xFFFFC56A) else white(.24f),
                    requester = tabReq[i], onOk = { onTab(i) }) {
                    Text("${i + 1} · $t", color = if (i == tab) Ink else Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.align(Alignment.Center).padding(horizontal = 16.dp))
                }
            }
        }
        if (m == null) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text("Working out this week's dinners and shopping…", color = white(.7f), fontSize = 16.sp)
            }
            Text("Back", color = white(.4f), fontSize = 11.sp)
            return@Column
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (tab) {
                0 -> MealNights(m, onPick = onPick, onNext = { onTab(1) })
                1 -> ShopReview(m, overrides, onAction = onAction, onQty = onQty, onNext = { onTab(2) },
                    find = find, searching = searching, onVoice = onVoice, onCloseFind = onCloseFind)
                else -> ShopTotal(m, overrides, onAction = onAction)
            }
        }
        Text(m.note + "  ·  " + when (tab) {
            0 -> "▲ ▼ nights · OK pick a different dinner · Back"
            1 -> if (find != null || searching.isNotEmpty()) "OK add to this order · mic button: search again · Back closes the search"
                 else "OK tick in or out · hold OK quantity · mic button: add by voice · Back"
            else -> "Back"
        }, color = white(.4f), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun MealNights(m: CcMeals, onPick: (CcMealNight) -> Unit, onNext: () -> Unit) {
    val first = remember { FocusRequester() }
    var sel by remember { mutableStateOf(m.nights.firstOrNull()?.date) }
    LaunchedEffect(Unit) { delay(80); runCatching { first.requestFocus() } }
    val cur = m.nights.firstOrNull { it.date == sel } ?: m.nights.firstOrNull()
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        LazyColumn(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(3.dp)) {
            itemsIndexed(m.nights, key = { _, n -> n.date }) { i, n ->
                Focusable(Modifier.fillMaxWidth().height(56.dp).onFocusChanged { if (it.isFocused) sel = n.date }, RoundedCornerShape(14.dp),
                    bg = if (n.kind == "dinner") white(.08f) else white(.04f), requester = if (i == 0) first else null, onOk = { onPick(n) }) {
                    Row(Modifier.fillMaxSize().padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(n.day, color = if (n.date == sel) Amber else white(.6f), fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(58.dp))
                        Box(Modifier.size(width = 64.dp, height = 40.dp).clip(RoundedCornerShape(8.dp)).background(white(.08f)), contentAlignment = Alignment.Center) {
                            if (n.img != null) Net(n.img, Modifier.fillMaxSize())
                            else Image(painterResource(ccIcon(if (n.kind == "dinner") "silverware_fork_knife" else "shopping_outline")), null, Modifier.size(18.dp),
                                colorFilter = ColorFilter.tint(white(.6f)))
                        }
                        Column(Modifier.weight(1f).padding(start = 12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(n.name, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f, fill = false))
                                if (n.ai) Badge("AI")
                                if (n.changed) Badge("CHANGED")
                            }
                            Text(when (n.kind) { "takeaway" -> "Nothing to buy"; "leftovers" -> "Nothing to buy"; "empty" -> "OK to pick a dinner"
                                else -> listOf(n.mins, n.needs.take(4).joinToString(" · ") { shortNeed(it) }).filter { it.isNotBlank() }.joinToString(" · ") },
                                color = white(.6f), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
            item(key = "next") {
                Focusable(Modifier.padding(top = 4.dp).height(42.dp), RoundedCornerShape(14.dp), bg = Amber, focusedBg = Color(0xFFFFC56A), onOk = onNext) {
                    Text("Next: review shopping ›", color = Ink, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.align(Alignment.Center).padding(horizontal = 18.dp))
                }
            }
        }
        // the highlighted night
        cur?.let { n ->
            Column(Modifier.width(300.dp).fillMaxHeight().clip(RoundedCornerShape(20.dp)).background(white(.06f))) {
                Box(Modifier.fillMaxWidth().height(150.dp).background(white(.08f)), contentAlignment = Alignment.Center) {
                    if (n.img != null) Net(n.img, Modifier.fillMaxSize())
                    else Image(painterResource(ccIcon(if (n.kind == "dinner") "silverware_fork_knife" else "shopping_outline")), null, Modifier.size(40.dp),
                        colorFilter = ColorFilter.tint(white(.4f)))
                }
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(n.long.uppercase(), color = Amber, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Text(n.name, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (n.needs.isNotEmpty()) {
                        Text("INGREDIENTS · ${n.needs.size}", color = white(.55f), fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 4.dp))
                        n.needs.take(7).forEach { Text(shortNeed(it), color = white(.85f), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                        if (n.needs.size > 7) Text("+${n.needs.size - 7} more", color = white(.5f), fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

/** "Lean irish beef mince typically 5% fat 350g" -> "Lean irish beef mince" (drops brand prefixes and pack sizes) */
private fun shortNeed(s: String): String =
    s.replace(Regex("(?i)^(dunnes stores |my family favourites )"), "").replace(Regex("(?i)\\s+(typically.*|\\d+(\\.\\d+)?\\s*(x\\s*\\d+(\\.\\d+)?\\s*)?(g|kg|ml|l|litre)\\b.*)$"), "")
        .replaceFirstChar { it.uppercase() }

@Composable
private fun Badge(t: String) {
    Text(t, color = Ink, fontSize = 9.sp, fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(start = 6.dp).clip(RoundedCornerShape(99.dp)).background(Amber).padding(horizontal = 6.dp, vertical = 1.dp))
}

private fun eur(v: Double) = "€" + String.format(Locale.UK, "%.2f", v)

@Composable
private fun ShopReview(m: CcMeals, ov: Map<String, Pair<Boolean, Int>>, onAction: (String) -> Unit, onQty: (CcShopItem) -> Unit, onNext: () -> Unit,
                       find: CcFind? = null, searching: String = "", onVoice: () -> Unit = {}, onCloseFind: () -> Unit = {}) {
    var sec by remember { mutableStateOf(m.sections.firstOrNull { it.id == "week" }?.id ?: m.sections.firstOrNull()?.id) }
    val secReq = remember(m.sections.size) { m.sections.map { FocusRequester() } }
    LaunchedEffect(Unit) { delay(80); runCatching { secReq[m.sections.indexOfFirst { it.id == sec }.coerceAtLeast(0)].requestFocus() } }
    fun on(i: CcShopItem) = ov[i.id]?.first ?: i.on
    fun q(i: CcShopItem) = ov[i.id]?.second ?: i.q
    val total = m.sections.sumOf { s -> s.items.filter { on(it) }.sumOf { it.price * q(it) } }
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(Modifier.width(240.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            m.sections.forEachIndexed { i, s ->
                val on = s.items.filter { on(it) }
                Focusable(Modifier.fillMaxWidth().height(50.dp).onFocusChanged { if (it.isFocused) sec = s.id }, RoundedCornerShape(14.dp),
                    bg = if (s.id == sec) white(.16f) else white(.06f), requester = secReq[i], onOk = { sec = s.id }) {
                    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalArrangement = Arrangement.Center) {
                        Text(s.title, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                        Text("${on.size} of ${s.items.size} · ${eur(on.sumOf { it.price * q(it) })}", color = white(.6f), fontSize = 11.sp)
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Amber.copy(alpha = .14f)).padding(12.dp)) {
                Text("Order so far", color = white(.75f), fontSize = 11.sp)
                Text(eur(total), color = Amber, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                if (m.total.usual.isNotBlank()) Text("Your usual order is ${m.total.usual}", color = white(.6f), fontSize = 11.sp)
            }
            Focusable(Modifier.fillMaxWidth().height(40.dp), RoundedCornerShape(14.dp), bg = Amber, focusedBg = Color(0xFFFFC56A), onOk = onNext) {
                Text("Next: total ›", color = Ink, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.Center))
            }
        }
        val s = m.sections.firstOrNull { it.id == sec }
        Column(Modifier.weight(1f).fillMaxHeight()) {
            if (find != null || searching.isNotEmpty()) FindResults(m, find, searching, ov, onAction = onAction, onVoice = onVoice, onClose = onCloseFind)
            else if (s != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(s.title, color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                    Text("  " + s.note, color = white(.55f), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Pill("microphone", "Add by voice", onVoice)
                }
                if (s.items.isEmpty()) Text("Nothing here this week.", color = white(.6f), fontSize = 14.sp, modifier = Modifier.padding(top = 16.dp))
                LazyColumn(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(5.dp), contentPadding = PaddingValues(3.dp)) {
                    items(s.items, key = { x -> x.id + s.id }) { item ->
                        val isOn = on(item); val qq = q(item)
                        Focusable(Modifier.fillMaxWidth().height(48.dp), RoundedCornerShape(12.dp), bg = if (isOn) white(.09f) else white(.03f),
                            onLong = { onQty(item) }, onOk = { onAction("meals:item:${item.id}:${if (isOn) 0 else 1}"); (ov as? MutableMap)?.put(item.id, !isOn to qq) }) {
                            Row(Modifier.fillMaxSize().padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(22.dp).clip(RoundedCornerShape(7.dp)).background(if (isOn) Amber else Color.Transparent)
                                    .border(2.dp, if (isOn) Amber else white(.5f), RoundedCornerShape(7.dp)), contentAlignment = Alignment.Center) {
                                    if (isOn) Image(painterResource(ccIcon("check")), null, Modifier.size(16.dp), colorFilter = ColorFilter.tint(Ink))
                                }
                                Column(Modifier.weight(1f).padding(start = 10.dp)) {
                                    Text(item.name, color = if (isOn) Color.White else white(.5f), fontSize = 14.sp, fontWeight = FontWeight.Bold,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(if (isOn) item.sub else "Not this time · " + item.sub, color = white(.55f), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                Text("× $qq", color = if (isOn) Color.White else white(.4f), fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 10.dp))
                                Text(if (item.unpriced) "–" else eur(item.price * qq), color = if (isOn) white(.85f) else white(.4f), fontSize = 13.sp,
                                    modifier = Modifier.width(64.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Spoken words -> a search: lower case, letters / digits / & / -, no "add" / "please", at most 40 characters (as HA accepts). */
private fun cleanSpoken(t: String): String =
    t.lowercase(Locale.UK).replace(Regex("[^a-z0-9 &-]"), " ").replace(Regex("^\\s*(please\\s+)?(add|search for|search|find|get|buy)\\s+(some\\s+)?"), "")
        .replace(Regex("\\s+(please|to the list|to my list)\\s*$"), "").replace(Regex("\\s+"), " ").trim().take(40).trim()

@Composable
private fun Pill(icon: String, label: String, onOk: () -> Unit, requester: FocusRequester? = null) {
    Focusable(Modifier.padding(start = 8.dp).height(34.dp), RoundedCornerShape(17.dp), bg = white(.12f), focusedBg = Amber, requester = requester, onOk = onOk) { f ->
        Row(Modifier.align(Alignment.Center).padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Image(painterResource(ccIcon(icon)), null, Modifier.size(16.dp), colorFilter = ColorFilter.tint(if (f) Ink else Color.White))
            Text(" $label", color = if (f) Ink else Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
    }
}

/** Listening / heard / error card over the meals page while voice search runs. */
@Composable
private fun VoiceBubble(v: CcVoice.Ui, modifier: Modifier) {
    if (!v.listening && v.error.isEmpty()) return
    Row(modifier.clip(RoundedCornerShape(28.dp)).background(Color(0xF21C1828)).border(1.dp, white(.2f), RoundedCornerShape(28.dp))
        .padding(horizontal = 22.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        val pulse by rememberInfiniteTransition(label = "mic").animateFloat(.55f, 1f, infiniteRepeatable(tween(650), RepeatMode.Reverse), label = "micA")
        Box(Modifier.size(40.dp).clip(RoundedCornerShape(20.dp)).background(if (v.error.isEmpty()) Amber.copy(alpha = pulse) else white(.15f)), contentAlignment = Alignment.Center) {
            Image(painterResource(ccIcon("microphone")), null, Modifier.size(22.dp), colorFilter = ColorFilter.tint(if (v.error.isEmpty()) Ink else Color.White))
        }
        Column(Modifier.padding(start = 14.dp)) {
            Text(if (v.error.isNotEmpty()) v.error else if (v.heard.isNotBlank()) "“${v.heard}”" else "Listening… say a product, e.g. cheddar",
                color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Text(if (v.error.isNotEmpty()) "Back to close" else "Back to cancel", color = white(.55f), fontSize = 11.sp)
        }
    }
}

/** Search results on Review shopping: what is already on the list (OK ticks it in / out), then products to add (past orders
 *  first, then the Dunnes catalogue, then the live Dunnes lookup), and "Add <words> as said". Adds go under "Added by you". */
@Composable
private fun FindResults(m: CcMeals, f: CcFind?, searching: String, ov: Map<String, Pair<Boolean, Int>>, onAction: (String) -> Unit,
                        onVoice: () -> Unit, onClose: () -> Unit) {
    val first = remember { FocusRequester() }
    val added = remember(f?.q) { mutableStateListOf<Int>() }
    var freeAdded by remember(f?.q) { mutableStateOf(false) }
    val q = if (searching.isNotEmpty()) searching else f?.q.orEmpty()
    val waiting = searching.isNotEmpty() && f?.q != searching
    LaunchedEffect(f?.q, waiting) { delay(120); runCatching { first.requestFocus() } }
    val items = m.sections.flatMap { it.items }.associateBy { it.id }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Image(painterResource(ccIcon("magnify")), null, Modifier.size(20.dp), colorFilter = ColorFilter.tint(Amber))
        Text(" “$q”", color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Pill("microphone", "Search again", onVoice)
        Pill("close_circle_outline", "Close", onClose)
    }
    if (waiting || f == null) {
        Text("Searching your orders and Dunnes…", color = white(.7f), fontSize = 15.sp, modifier = Modifier.padding(top = 18.dp))
        Box(Modifier.size(1.dp).focusRequester(first).focusable())
        return
    }
    // focus starts on the first product to add (or the first list match when nothing new was found)
    val firstAt = if (f.hits.isNotEmpty()) "h0" else if (f.on.isNotEmpty()) "on0" else "free"
    LazyColumn(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(5.dp), contentPadding = PaddingValues(3.dp)) {
        if (f.on.isNotEmpty()) {
            item(key = "onh") { Text("ON YOUR LIST", color = white(.55f), fontSize = 11.sp, fontWeight = FontWeight.Bold) }
            itemsIndexed(f.on, key = { _, o -> "on" + o.id + o.where }) { idx, o ->
                val it0 = items[o.id]; val isOn = ov[o.id]?.first ?: it0?.on ?: o.on; val qq = ov[o.id]?.second ?: it0?.q ?: 1
                FindRow(if (isOn) "check" else null, o.name, (if (isOn) "" else "Not this time · ") + o.where, if (isOn && it0 != null && !it0.unpriced) eur(it0.price * qq) else "",
                    on = isOn, requester = if (firstAt == "on$idx") first else null) {
                    onAction("meals:item:${o.id}:${if (isOn) 0 else 1}"); (ov as? MutableMap)?.put(o.id, !isOn to qq)
                }
            }
        }
        item(key = "addh") { Text("ADD TO THIS ORDER", color = white(.55f), fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 4.dp)) }
        itemsIndexed(f.hits, key = { _, h -> "h" + h.i + h.name }) { idx, h ->
            val done = h.added || h.i in added
            FindRow(if (done) "check" else "plus", h.name, if (done) "Added · " + h.src else h.src, h.priceTxt, on = done, requester = if (firstAt == "h$idx") first else null) {
                if (!done) { added += h.i; onAction("meals:add:${h.i}") }
            }
        }
        if (f.looking) item(key = "look") { Text("Looking on Dunnes for more…", color = white(.6f), fontSize = 13.sp, modifier = Modifier.padding(6.dp)) }
        else if (f.hits.isEmpty()) item(key = "none") { Text("Nothing in your orders or the Dunnes catalogue.", color = white(.6f), fontSize = 13.sp, modifier = Modifier.padding(6.dp)) }
        item(key = "free") {
            FindRow(if (freeAdded) "check" else "plus", "Add “${f.q.replaceFirstChar { it.uppercase() }}” as said", "No Dunnes product - you pick it when ordering", "",
                on = freeAdded, requester = if (firstAt == "free") first else null) { if (!freeAdded) { freeAdded = true; onAction("meals:addfree") } }
        }
    }
}

@Composable
private fun FindRow(icon: String?, name: String, sub: String, price: String, on: Boolean, requester: FocusRequester?, onOk: () -> Unit) {
    Focusable(Modifier.fillMaxWidth().height(48.dp), RoundedCornerShape(12.dp), bg = if (on) white(.09f) else white(.05f), requester = requester, onOk = onOk) {
        Row(Modifier.fillMaxSize().padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(22.dp).clip(RoundedCornerShape(7.dp)).background(if (on) Amber else Color.Transparent)
                .border(2.dp, if (on) Amber else white(.5f), RoundedCornerShape(7.dp)), contentAlignment = Alignment.Center) {
                if (icon != null) Image(painterResource(ccIcon(icon)), null, Modifier.size(16.dp), colorFilter = ColorFilter.tint(if (on) Ink else Color.White))
            }
            Column(Modifier.weight(1f).padding(start = 10.dp)) {
                Text(name, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(sub, color = white(.55f), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (price.isNotEmpty()) Text(price, color = white(.85f), fontSize = 13.sp, modifier = Modifier.padding(start = 8.dp))
        }
    }
}

@Composable
private fun ShopTotal(m: CcMeals, ov: Map<String, Pair<Boolean, Int>>, onAction: (String) -> Unit) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(80); runCatching { first.requestFocus() } }
    fun on(i: CcShopItem) = ov[i.id]?.first ?: i.on
    fun q(i: CcShopItem) = ov[i.id]?.second ?: i.q
    val all = m.sections.flatMap { s -> s.items.filter { on(it) } }
    val total = all.sumOf { it.price * q(it) }
    val unpriced = all.count { it.unpriced }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            listOf(Triple("Estimated total", eur(total), Amber), Triple("Items", "${all.size}", Color.White),
                Triple("Dinners", "${m.total.dinners}", Color.White), Triple("Your usual order", m.total.usual.removePrefix("about ").ifBlank { "–" }, Color.White)).forEach { (l, v, c) ->
                Column(Modifier.weight(1f).clip(RoundedCornerShape(18.dp)).background(white(.10f)).padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Text(l, color = white(.65f), fontSize = 12.sp)
                    Text(v, color = c, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(18.dp)).background(white(.06f)).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Text("By section", color = white(.75f), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                m.sections.forEach { s ->
                    val its = s.items.filter { on(it) }
                    Row { Text("${s.title} · ${its.size}", color = Color.White, fontSize = 14.sp, modifier = Modifier.weight(1f))
                          Text(eur(its.sumOf { it.price * q(it) }), color = white(.8f), fontSize = 14.sp) }
                }
                if (unpriced > 0) Text("$unpriced item${if (unpriced > 1) "s" else ""} without a price yet (not in the total)", color = white(.5f), fontSize = 12.sp)
            }
            Column(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(18.dp)).background(white(.06f)).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Text("Taken off this time", color = white(.75f), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                val off = m.sections.filter { it.id !in listOf("check", "cupboard") }.flatMap { s -> s.items.filter { !on(it) } }
                if (off.isEmpty()) Text("Nothing: everything suggested is on the list.", color = white(.6f), fontSize = 13.sp)
                off.take(8).forEach { Text(it.name, color = white(.75f), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                if (off.size > 8) Text("+${off.size - 8} more", color = white(.5f), fontSize = 12.sp)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Focusable(Modifier.height(42.dp), RoundedCornerShape(14.dp), requester = first, onOk = { onAction("meals:reset") }) {
                Text("Start again (undo TV changes)", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.align(Alignment.Center).padding(horizontal = 18.dp))
            }
            Text("Ordering from the TV comes once you're happy with this.", color = white(.5f), fontSize = 12.sp)
        }
    }
}

/** Full-screen dinner picker for one night: Takeaway / Leftovers / Nothing / Keep the plan, then your dinners. */
@Composable
private fun MealPicker(n: CcMealNight, choices: List<CcMealChoice>, onPick: (String) -> Unit) {
    val first = remember { FocusRequester() }
    LaunchedEffect(n.date) { delay(60); runCatching { first.requestFocus() } }
    val specials = listOf(Triple("orig", "As planned", "shopping_outline"), Triple("takeaway", "Takeaway", "shopping_outline"),
        Triple("leftovers", "Leftovers", "history"), Triple("clear", "Nothing", "close_circle_outline"))
    Column(Modifier.fillMaxSize().background(Color(0xF20D0A14)).padding(start = 44.dp, end = 44.dp, top = 22.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Dinner for ", color = white(.6f), fontSize = 16.sp)
            Text(n.long, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text("   now: ${n.name}", color = white(.55f), fontSize = 13.sp)
        }
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(4.dp)) {
            item(key = "sp") {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    specials.forEachIndexed { i, (v, t, ic) ->
                        Focusable(Modifier.weight(1f).height(44.dp), RoundedCornerShape(14.dp), requester = if (i == 0) first else null, onOk = { onPick(v) }) {
                            Row(Modifier.align(Alignment.Center), verticalAlignment = Alignment.CenterVertically) {
                                Image(painterResource(ccIcon(ic)), null, Modifier.size(16.dp), colorFilter = ColorFilter.tint(white(.8f)))
                                Text("  $t", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
            choices.chunked(5).forEachIndexed { ri, row ->
                item(key = "c$ri") {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        row.forEach { c ->
                            Focusable(Modifier.weight(1f).height(132.dp), RoundedCornerShape(14.dp), onOk = { onPick(c.id) }) {
                                Column(Modifier.fillMaxSize()) {
                                    Box(Modifier.fillMaxWidth().height(76.dp).background(white(.06f)), contentAlignment = Alignment.Center) {
                                        if (c.img != null) Net(c.img, Modifier.fillMaxSize())
                                        else Image(painterResource(ccIcon("silverware_fork_knife")), null, Modifier.size(26.dp), colorFilter = ColorFilter.tint(white(.4f)))
                                    }
                                    Column(Modifier.padding(horizontal = 9.dp, vertical = 6.dp)) {
                                        Text(c.name, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(listOf(c.mins, "${c.count} items").filter { it.isNotBlank() }.joinToString(" · "), color = white(.6f), fontSize = 11.sp)
                                    }
                                }
                            }
                        }
                        repeat(5 - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
        Text("OK put it on ${n.day.lowercase().replaceFirstChar { it.uppercase() }} · Back keeps it as it is", color = white(.4f), fontSize = 11.sp)
    }
}

@Composable
private fun QtyPopup(name: String, q: Int, price: Double) {
    Box(Modifier.fillMaxSize().background(Color(0xE6140F22)), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(name, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 520.dp))
            Text("× $q", color = Amber, fontSize = 44.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
            if (price > 0) Text(eur(price * q), color = white(.7f), fontSize = 14.sp)
            Text("▲ ▼ quantity · OK done", color = white(.5f), fontSize = 11.sp, modifier = Modifier.padding(top = 12.dp))
        }
    }
}

// ============================================================ brightness slider (hold OK on a light tile)
@Composable
private fun Dimmer(title: String, pct: Int, parts: List<Pair<String, Int>> = emptyList(), sel: Int = 0, volume: Boolean = false,
                   ct: CtView? = null) {
    Box(Modifier.fillMaxSize().background(Color(0xE6140F22)), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(title, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Text((if (volume) "Volume $pct" else if (pct == 0) "Off" else "$pct%") + (if (ct != null) " · ${ctName(ct.k, ct.kmin, ct.kmax)}" else ""),
                color = Amber, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 2.dp, bottom = 12.dp))
            // tall pill like the iOS Control Centre brightness slider; the lit part grows from the bottom
            Box(Modifier.width(78.dp).height(170.dp).clip(RoundedCornerShape(24.dp)).background(white(.14f)),
                contentAlignment = Alignment.BottomCenter) {
                Box(Modifier.fillMaxWidth().fillMaxHeight(pct / 100f).background(Color(0xFFF5F2FA)))
                Image(painterResource(ccIcon(if (volume) "speaker" else "lightbulb")), null, Modifier.padding(bottom = 14.dp).size(24.dp),
                    colorFilter = ColorFilter.tint(if (pct >= 15) Color(0xFFF59E0B) else Color.White))
            }
            // a group's parts under the slider: the highlighted one is what the slider sets
            if (parts.size > 1) Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                parts.forEachIndexed { i, (name, p) ->
                    val hi = i == sel && (ct == null || !ct.on)
                    Column(Modifier.width(86.dp).clip(RoundedCornerShape(12.dp)).background(if (i == sel) Color.White else white(.12f))
                        .border(if (hi) 2.dp else 0.dp, if (hi) Amber else Color.Transparent, RoundedCornerShape(12.dp))
                        .padding(horizontal = 8.dp, vertical = 6.dp)) {
                        Text(if (i == 0) "All" else name, color = if (i == sel) Ink else Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val k = ct?.parts?.getOrNull(i) ?: 0
                        Text((if (p == 0) "Off" else "$p%") + (if (ct != null && k > 0) " · ${ctName(k, ct.kmin, ct.kmax)}" else ""),
                            color = if (i == sel) Ink.copy(alpha = .6f) else white(.6f), fontSize = 9.sp, maxLines = 1)
                    }
                }
            }
            // colour: a thin warm-to-cool strip with the presets under it; the dot is where the light is
            if (ct != null) {
                val w = if (parts.size > 1) (86 * parts.size + 6 * (parts.size - 1)).dp else 220.dp
                val f = ((ct.k - ct.kmin).toFloat() / (ct.kmax - ct.kmin)).coerceIn(0f, 1f)
                Box(Modifier.padding(top = 16.dp).width(w).height(16.dp), contentAlignment = Alignment.CenterStart) {
                    Box(Modifier.fillMaxWidth().height(if (ct.on) 6.dp else 4.dp).clip(RoundedCornerShape(3.dp))
                        .background(Brush.horizontalGradient(listOf(kColor(ct.kmin), kColor((ct.kmin + ct.kmax) / 2), kColor(ct.kmax)))))
                    Box(Modifier.offset(x = (w - 16.dp) * f).size(16.dp).clip(RoundedCornerShape(8.dp)).background(kColor(ct.k))
                        .border(2.dp, if (ct.on) Amber else Color.White, RoundedCornerShape(8.dp)))
                }
                Row(Modifier.width(w).padding(top = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    val here = ctName(ct.k, ct.kmin, ct.kmax)
                    CT_PRESETS.forEach { n ->
                        Text(n, color = if (n == here) (if (ct.on) Amber else Color.White) else white(.45f), fontSize = 10.sp,
                            fontWeight = if (n == here) FontWeight.Bold else FontWeight.Normal)
                    }
                }
            }
            Text(if (ct != null && ct.on) "◀ ▶ warmer / cooler · ▲ ▼ brightness · hold OK " + (if (parts.size > 1) "pick a light" else "back") + " · OK done"
                 else (if (parts.size > 1) "◀ ▶ pick a light · " else "") + "▲ ▼ adjust · " + (if (ct != null) "hold OK colour · " else "") + "OK done",
                color = white(.5f), fontSize = 9.sp, modifier = Modifier.padding(top = 12.dp))
        }
    }
}

/** The colour pill's state: [on] = ▲ ▼ moves the colour; [parts] = each chip's kelvin (0 = no colour temperature). */
private data class CtView(val on: Boolean, val k: Int, val kmin: Int, val kmax: Int, val parts: List<Int>)

// Same names and spacing as the room card: four presets evenly over the light's range
private val CT_PRESETS = listOf("Warm", "Soft", "Neutral", "Cool")
private fun ctPreset(i: Int, kmin: Int, kmax: Int) = ((kmin + (kmax - kmin) * i / 3.0) / 100).roundToInt() * 100
private fun ctName(k: Int, kmin: Int, kmax: Int): String =
    CT_PRESETS[CT_PRESETS.indices.minByOrNull { kotlin.math.abs(ctPreset(it, kmin, kmax) - k) } ?: 0]

/** Rough colour of white light at [k] kelvin (Tanner Helland's fit), for the colour pill. */
private fun kColor(k: Int): Color {
    val t = k / 100.0
    val r = if (t <= 66) 255.0 else 329.698727446 * Math.pow(t - 60, -0.1332047592)
    val g = if (t <= 66) 99.4708025861 * Math.log(t) - 161.1195681661 else 288.1221695283 * Math.pow(t - 60, -0.0755148492)
    val b = if (t >= 66) 255.0 else if (t <= 19) 0.0 else 138.5177312231 * Math.log(t - 10) - 305.0447927307
    fun c(v: Double) = v.coerceIn(0.0, 255.0).toInt()
    return Color(c(r), c(g), c(b))
}

// ============================================================ live camera, extending out of the panel
/** The doorbell waking up: [s] seconds so far (0 = awake, connecting; -1 = the video wouldn't start). */
@Composable
private fun WakeWait(s: Int) {
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
            color = white(.6f), fontSize = 10.sp, modifier = Modifier.padding(top = 3.dp))
    }
}

@Composable
private fun LiveCamera(entity: String, name: String, rtsp: String, modifier: Modifier,
                       wake: Boolean = false, awake: Boolean = false, onAction: (String) -> Unit = {}) {
    val ctx = LocalContext.current
    val url = remember(entity) {
        (dev.trooped.tvquickbars.notification.normalizedHaBase(ctx)?.toString()?.trimEnd('/')
            ?: SecurePrefsManager.getHAUrl(ctx)?.trimEnd('/') ?: "") + "/api/camera_proxy_stream/" + entity
    }
    val token = remember { SecurePrefsManager.getHAToken(ctx) }
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    val slide by animateFloatAsState(if (shown) 0f else 1f, spring(dampingRatio = .85f, stiffness = 320f), label = "liveCam")
    // A sleeping camera (the doorbell): ask HA to wake it, then keep it awake each minute while it is shown (HA stops it
    // about 90 s after the last one). HA re-sends the cameras with awake = true once it streams.
    var waited by remember(entity) { mutableIntStateOf(0) }
    if (wake) LaunchedEffect(entity) {
        onAction("cam_wake:$entity")
        var s = 0
        while (true) { delay(1000); s++; if (s <= 60) waited = s; if (s % 60 == 0) onAction("cam_keep:$entity") }
    }
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
                var tries by remember(entity) { mutableIntStateOf(0) }
                var retryAt by remember(entity) { mutableIntStateOf(0) }   // the attempt the 2 s pause is over for
                var playing by remember(entity) { mutableStateOf(false) }
                if (wake) {
                    // no picture without the stream (HA's still is a 503): once awake, try the video up to 5 times, 2 s apart
                    LaunchedEffect(tries) { if (tries > 0) delay(2000); retryAt = tries }
                    if (awake && !rtspFailed && rtsp.isNotBlank() && retryAt == tries) key(tries) {
                        dev.trooped.tvquickbars.camera.CameraRtspView(url = rtsp,
                            config = dev.trooped.tvquickbars.camera.RtspProfile(latency = dev.trooped.tvquickbars.camera.StreamLatency.LOW_LATENCY, muteAudio = true),
                            modifier = Modifier.fillMaxSize(), onReady = { playing = true },
                            onError = { playing = false; if (tries < 4) tries++ else rtspFailed = true })
                    }
                    if (!playing) WakeWait(if (rtspFailed) -1 else if (awake) 0 else waited)
                } else if (rtsp.isNotBlank() && !rtspFailed)
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
