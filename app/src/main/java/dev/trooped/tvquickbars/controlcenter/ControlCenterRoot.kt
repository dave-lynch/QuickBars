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

// ============================================================ root
@Composable
fun ControlCenterRoot(
    spec: ControlCenterSpec,
    onAction: (String) -> Unit,
    onCamera: (String, String?, Boolean, String) -> Unit,   // entity, RTSP stream, big PiP, name - for the corner PiP
    onClose: () -> Unit,
    onKeepAlive: () -> Unit = {},
    loading: Boolean = false,   // opened from the remote before HA's data came: what is shown is from last time
) {
    var page by remember(spec.page) { mutableStateOf(spec.page) }
    var homeFrom by remember { mutableStateOf<String?>(null) }   // the panel item a sub-page was opened from: Back puts focus there
    var catchupFrom by remember(spec.page) { mutableStateOf("notifications") }
    var openGroup by remember { mutableStateOf<String?>(null) }
    var room by remember { mutableStateOf<String?>(null) }   // the room open on the "room" page
    var tvMenu by remember { mutableStateOf<CcCatchupItem?>(null) }
    // Kitchen > Dinners & shopping: tab, the night being changed (dinner picker), the item whose quantity is being set, and
    // presses shown at once until HA's update arrives (cleared whenever new meals data comes in)
    var mealTab by remember { mutableIntStateOf(0) }
    var mealsFrom by remember { mutableStateOf("room") }   // Back from Dinners & shopping: the Kitchen room, or the notifications
    var noteMenu by remember { mutableStateOf<CcNotification?>(null) }   // hold OK on a notification
    var nowMenu by remember { mutableStateOf<CcNow?>(null) }   // the Now Playing picker (hold OK on the card)
    // Shown on the Now Playing card from the press until HA redraws it (or 20 s): the switch takes a few seconds
    var nowBusy by remember { mutableStateOf("") }
    LaunchedEffect(spec.now) { nowBusy = "" }
    LaunchedEffect(nowBusy) { if (nowBusy.isNotEmpty()) { delay(20_000); nowBusy = "" } }
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
    LaunchedEffect(voice.error) { if (voice.error.isNotEmpty()) { delay(5000); CcVoice.clearError() } }
    val dismissed = remember { mutableStateListOf<String>() }
    // The notification just dismissed: hidden at once, sent to HA after 5 s unless Undo is pressed (or at once when the panel closes)
    var undoNote by remember { mutableStateOf<CcNotification?>(null) }
    val sendNow = rememberUpdatedState(onAction)
    LaunchedEffect(undoNote?.id) { val n = undoNote ?: return@LaunchedEffect; delay(5_000); onAction("notif_dismiss:${n.id}"); undoNote = null }
    DisposableEffect(Unit) { onDispose { undoNote?.let { sendNow.value("notif_dismiss:${it.id}") } } }
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
                 if (nowMenu != null) { nowMenu = null; return }
                 page = when (page) { "catchup" -> if (spec.page == "catchup") "close" else catchupFrom; "notifications" -> "home"
                                      "room" -> "rooms"; "rooms" -> "home"; "cameras" -> "home"; "meals" -> mealsFrom; else -> "close" }
                 if (page == "close") onClose() }
    fun openNote(n: CcNotification) {
        when {
            n.open == "page:meals" -> { mealsFrom = page; mealTab = 0; onAction("meals:open"); page = "meals" }
            n.open.startsWith("page:") -> { homeFrom = null; catchupFrom = page; page = n.open.removePrefix("page:") }
            n.open.startsWith("camera:") -> live = n.open.removePrefix("camera:")
            else -> { onAction("notif_open:${n.id}"); onClose() }
        }
    }
    fun dismiss(n: CcNotification) {
        undoNote?.let { if (it.id != n.id) onAction("notif_dismiss:${it.id}") }   // the previous one can't be undone any more
        dismissed += n.id; undoNote = n
    }
    fun undoDismiss() { undoNote?.let { dismissed -= it.id }; undoNote = null }
    fun clearAll(list: List<CcNotification>) { undoNote?.let { onAction("notif_dismiss:${it.id}") }; undoNote = null; list.forEach { dismissed += it.id; onAction("notif_dismiss:${it.id}") } }

    // Brightness slider (hold OK on a light tile): ▲▼ in 10% steps, sent to HA after a short pause; OK / Back close it
    // A light group opens with its parts listed under the slider (e.g. All · Ceiling · Lamp):
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
    // A light group's level is what Home Assistant reports for it: the average of its lights that are on (a two-light group count as
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
    // Neutral, Cool over the light's own range). OK switches ◀ ▶ from picking a light to stepping the presets (Back closes);
    // ▲ ▼ stays brightness. Sent as ct:<light>:<kelvin>.
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
                        val cc = spec.cameras.firstOrNull { it.entity == cam }
                        onCamera(cam, cc?.rtsp?.takeIf { it.isNotBlank() }, cc?.big == true, cc?.name.orEmpty()); onClose() }
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
                        // OK on a light with a colour temperature: brightness <-> colour (Back closes); otherwise OK closes
                        e.isOk() && e.type == KeyEventType.KeyUp && hasCt(dim) -> { ctMode = !ctMode; dimTouched = System.currentTimeMillis() }
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
            "cameras" -> CamerasPage(spec, onAction, onKeepAlive)
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
                    onClearAll = { clearAll(notes); page = "home" }, undo = undoNote, onUndo = ::undoDismiss)
                else HomePage(spec, notes, onOpenStack = { homeFrom = null; page = "notifications" }, onOpen = ::openNote, onMenu = { noteMenu = it },
                    onCatchup = { homeFrom = "tv"; catchupFrom = "home"; page = "catchup" }, onRooms = { homeFrom = "rooms"; room = null; page = "rooms" },
                    onCameras = { homeFrom = "cameras"; live = null; page = "cameras" }, returnTo = homeFrom, loading = loading,
                    undo = undoNote, onUndo = ::undoDismiss,
                    openGroup = openGroup, onGroup = { openGroup = if (openGroup == it) null else it }, onAction = onAction, onCamera = { live = if (live == it) null else it },
                    onDim = { openDim(it) }, onNowMenu = { nowMenu = it }, nowBusy = nowBusy,
                    onNowOk = { a -> nowBusy = "Switching to the next stream…"; onAction(a) })
                dim?.let { Dimmer(it.title, dimPct, dimTargets.mapIndexed { i, t -> t.title to shown(i) }, dimSel,
                    ct = if (hasCt(it)) CtView(ctMode, ctK, it.kmin, it.kmax, dimTargets.indices.map { i -> shownCt(i) }) else null) }
                nowMenu?.let { n -> NowMenu(n, onPick = { p -> nowMenu = null
                    if (!p.on) { nowBusy = "Switching to ${p.title.substringBefore(" · ")}…"; onAction(p.id) } }) }
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
