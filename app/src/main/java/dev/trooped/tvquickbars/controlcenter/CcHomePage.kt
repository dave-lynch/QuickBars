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

// Home page (D2), notifications list (D3) and the hold-OK menus for notifications and Now Playing.

// ============================================================ D2: home
@Composable
internal fun HomePage(
    spec: ControlCenterSpec, notes: List<CcNotification>,
    onOpenStack: () -> Unit, onOpen: (CcNotification) -> Unit, onAction: (String) -> Unit, onCamera: (String) -> Unit, onMenu: (CcNotification) -> Unit = {},
    onDim: (CcTile) -> Unit = {}, onCatchup: () -> Unit = {}, onRooms: () -> Unit = {}, onCameras: () -> Unit = {},
    openGroup: String? = null, onGroup: (String) -> Unit = {}, onNowMenu: (CcNow) -> Unit = {},
    nowBusy: String = "", onNowOk: (String) -> Unit = onAction,
    returnTo: String? = null, loading: Boolean = false, undo: CcNotification? = null, onUndo: () -> Unit = {},
) {
    var clock by remember { mutableStateOf("") }
    // changes on the minute (not every 10 s from whenever the panel opened)
    LaunchedEffect(Unit) { val f = SimpleDateFormat("HH:mm", Locale.getDefault())
        while (true) { val t = System.currentTimeMillis(); clock = f.format(Date(t)); delay(60_000 - t % 60_000 + 50) } }
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(4_000); tick++ } }
    val first = remember { FocusRequester() }
    // Back from TV / Rooms / Cameras: focus goes back to what opened it
    val back = remember { mapOf("tv" to FocusRequester(), "rooms" to FocusRequester(), "cameras" to FocusRequester()) }
    var hasFocus by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { delay(80); runCatching { (back[returnTo] ?: first).requestFocus() }.onFailure { runCatching { first.requestFocus() } } }
    // carried over from last time while HA's fresh data is on its way
    val stale = if (loading) Modifier.graphicsLayer { alpha = .5f } else Modifier
    // An update can remove the item that had focus (e.g. the last notification cleared on the phone): move focus to
    // the first item still on screen so the remote keeps working, without stealing it otherwise.
    LaunchedEffect(notes.isEmpty(), spec.cameras.size, spec.tiles.size, openGroup) { delay(120); if (!hasFocus) runCatching { first.requestFocus() } }

    // Scrolls when everything doesn't fit (now playing + notifications + cameras + tiles + TV / Rooms): moving focus down
    // brings the next row into view, so nothing at the bottom is ever cut off. The key hint stays pinned below.
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 18.dp).onFocusChanged { hasFocus = it.hasFocus }) {
    Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(clock, color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            if (loading) {
                val pulse by rememberInfiniteTransition(label = "ccLoad").animateFloat(.45f, 1f,
                    infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "ccLoadA")
                Text("Updating…", color = Amber.copy(alpha = pulse), fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }
        if (spec.subtitle.isNotBlank()) Text(spec.subtitle, color = white(.65f), fontSize = 14.sp, modifier = Modifier.offset(y = (-8).dp))
        spec.now?.let { n ->
            @Composable fun body() = Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Net(n.image, Modifier.size(70.dp, 40.dp).clip(RoundedCornerShape(8.dp)), crop = ContentScale.Fit)
                Column(Modifier.padding(start = 10.dp)) {
                    if (nowBusy.isNotEmpty()) {
                        // a press is on its way: say so at once (pulsing) until HA redraws the card
                        val pulse by rememberInfiniteTransition(label = "ccBusy").animateFloat(.45f, 1f,
                            infiniteRepeatable(tween(600), RepeatMode.Reverse), label = "ccBusyA")
                        Text(nowBusy, color = Amber.copy(alpha = pulse), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    } else Text(n.label, color = Amber, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    Text(n.title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (n.sub.isNotBlank()) Text(n.sub, color = white(.6f), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            // OK sends what HA set (e.g. the live channel's next stream); hold OK opens the picker. Up from the notifications reaches it.
            if (n.ok.isNotBlank() || n.streams.isNotEmpty())
                Focusable(Modifier.fillMaxWidth(), RoundedCornerShape(14.dp), bg = white(.08f),
                    onLong = if (n.streams.isNotEmpty()) ({ onNowMenu(n) }) else null,
                    onOk = { if (n.ok.isNotBlank()) onNowOk(n.ok) else onNowMenu(n) }) { body() }
            else Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(white(.08f))) { body() }
        }
        // notification stack (collapsed): top card + two edges peeking behind
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Notifications", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            if (notes.isNotEmpty()) Text("${notes.size} new", color = Ink, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.clip(RoundedCornerShape(50)).background(Amber).padding(horizontal = 8.dp, vertical = 2.dp))
        }
        undo?.let { UndoRow(it, onUndo) }
        if (notes.isEmpty()) {
            Text(if (loading && spec.notifications.isEmpty()) "Getting notifications…" else "You're all caught up", color = white(.5f), fontSize = 14.sp,
                modifier = Modifier.padding(vertical = 8.dp))
        } else Box(Modifier.fillMaxWidth().height(if (notes.size > 1) 92.dp else 80.dp).then(stale)) {
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
            // the heading opens the Cameras page (every camera large, up to 3 live, recent snapshots)
            Focusable(Modifier, RoundedCornerShape(8.dp), bg = Color.Transparent, focusedBg = white(.18f), requester = back["cameras"], onOk = onCameras) { f ->
                Text(spec.cameraStatus.ifBlank { "Cameras" } + " ›", color = if (f) Color.White else white(.75f), fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp))
            }
            // Every camera as a sideways strip: about 3½ tiles show, so the next one peeks in; ◀ ▶ scroll it. Only the
            // tiles on screen are composed, so only those refresh their frame every 4 s.
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                items(spec.cameras, key = { it.entity }) { c ->
                    Focusable(Modifier.width(84.dp).height(60.dp), RoundedCornerShape(10.dp), bg = Color.Black,
                        requester = if (notes.isEmpty() && c == spec.cameras.first()) first else null, onOk = { onCamera(c.entity) }) { f ->
                        Net(c.image, Modifier.fillMaxSize(), tick, widthPx = 320)
                        if (c.seen.isNotBlank()) Text(c.seen.substringBefore(" ·"), color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold,
                            modifier = Modifier.align(Alignment.TopEnd).padding(3.dp).clip(RoundedCornerShape(50)).background(Color(0xCC7C2D12))
                                .padding(horizontal = 4.dp, vertical = 1.dp))
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
        if (cu.isNotEmpty()) Focusable(Modifier.weight(1f).height(68.dp).focusRequester(back.getValue("tv")).then(stale), RoundedCornerShape(14.dp), bg = white(.08f), focusedBg = white(.20f),
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
        if (spec.rooms.isNotEmpty()) Focusable(Modifier.weight(1f).height(68.dp).focusRequester(back.getValue("rooms")), RoundedCornerShape(14.dp), bg = white(.08f), focusedBg = white(.20f),
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
internal fun RowScope.QuickTile(
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
internal fun NoteBody(n: CcNotification, compact: Boolean) {
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
internal fun NotificationsPage(
    notes: List<CcNotification>, onBack: () -> Unit, onOpen: (CcNotification) -> Unit,
    onDismiss: (CcNotification) -> Unit, onClearAll: () -> Unit, onMenu: (CcNotification) -> Unit = {},
    undo: CcNotification? = null, onUndo: () -> Unit = {},
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
            Focusable(Modifier.height(32.dp), RoundedCornerShape(16.dp), requester = if (notes.isEmpty()) first else null, onOk = onClearAll) {
                Text("Clear all", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.align(Alignment.Center).padding(horizontal = 10.dp))
            }
        }
        Spacer(Modifier.height(12.dp))
        undo?.let { UndoRow(it, onUndo) }
        if (notes.isEmpty()) Text("You're all caught up", color = white(.5f), fontSize = 12.sp)
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f), contentPadding = PaddingValues(4.dp)) {
            itemsIndexed(notes, key = { _, n -> n.id }) { i, n ->
                Focusable(Modifier.fillMaxWidth().height(70.dp), RoundedCornerShape(14.dp), bg = white(.08f), focusedBg = white(.20f),
                    requester = if (i == 0) first else null,
                    onKey = { e -> if ((e.key == Key.DirectionLeft || e.key == Key.DirectionRight) && e.type == KeyEventType.KeyDown) {
                        // Left / right dismisses (like swiping a notification away); keep focus on the list. Only a fresh
                        // press: holding the key repeats it, which would clear the list one by one.
                        if (e.nativeKeyEvent.repeatCount == 0) onDismiss(n); true } else false },
                    onLong = { onMenu(n) }, onOk = { onOpen(n) }) { NoteBody(n, compact = false) }
            }
        }
        Text("OK open · hold OK for options · ◀ ▶ dismiss" + (if (undo != null) " · ▲ undo" else "") + " · Back", color = white(.35f), fontSize = 11.sp,
            modifier = Modifier.padding(top = 6.dp))
    }
}

/** "Dismissed · <title> · OK to undo", for 5 s after a notification is dismissed on the TV (HA hears of it after that). */
@Composable
internal fun UndoRow(n: CcNotification, onUndo: () -> Unit) {
    Focusable(Modifier.fillMaxWidth().height(36.dp).padding(bottom = 4.dp), RoundedCornerShape(12.dp), bg = Amber.copy(alpha = .16f),
        focusedBg = Amber.copy(alpha = .35f), onOk = onUndo) {
        Text("Dismissed · ${n.title} · OK to undo", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.align(Alignment.CenterStart).padding(horizontal = 12.dp))
    }
}


/** Hold OK on a notification: Open / Dismiss (TV only) plus what HA offers for it (Open Dinners & shopping, Ignore everywhere,
 *  Not in the house: remove the item). Drawn over the panel; Back closes it. */
@Composable
internal fun NoteMenu(n: CcNotification, onPick: (String) -> Unit) {
    val first = remember { FocusRequester() }
    LaunchedEffect(n.id) { delay(60); runCatching { first.requestFocus() } }
    val opts = n.actions + ("dismiss" to "Dismiss on the TV")
    Box(Modifier.fillMaxSize().background(Color(0xE6140F22)).padding(14.dp), contentAlignment = Alignment.Center) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(n.title, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(n.app, color = white(.55f), fontSize = 11.sp, modifier = Modifier.padding(bottom = 4.dp))
            opts.forEachIndexed { i, (id, label) ->
                Focusable(Modifier.fillMaxWidth().height(40.dp), RoundedCornerShape(12.dp), requester = if (i == 0) first else null, onOk = { onPick(id) }) {
                    Text(label, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.align(Alignment.CenterStart).padding(horizontal = 14.dp))
                }
            }
            Text("OK choose · Back cancel", color = white(.4f), fontSize = 11.sp)
        }
    }
}
/** The Now Playing picker (hold OK on the card): HA's choices, e.g. every stream of the live channel with its playlist; the one
 *  playing now is marked and focused first. Drawn over the panel; Back closes it. */
@Composable
internal fun NowMenu(n: CcNow, onPick: (CcPick) -> Unit) {
    val first = remember { FocusRequester() }
    // The OK held to open the picker may still be down: its repeats and its release must not pick the focused stream.
    // Only OK events after a fresh press (repeat count 0) count.
    var fresh by remember { mutableStateOf(false) }
    val swallowHeld: (KeyEvent) -> Boolean = { e ->
        if (!e.isOk() || fresh) false
        else if (e.type == KeyEventType.KeyDown && e.nativeKeyEvent.repeatCount == 0) { fresh = true; false }
        else true
    }
    LaunchedEffect(n.title) { delay(60); runCatching { first.requestFocus() } }
    val start = n.streams.indexOfFirst { it.on }.coerceAtLeast(0)
    Box(Modifier.fillMaxSize().background(Color(0xE6140F22)).padding(14.dp), contentAlignment = Alignment.Center) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(n.title, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(n.label, color = white(.55f), fontSize = 11.sp, modifier = Modifier.padding(bottom = 4.dp))
            n.streams.forEachIndexed { i, p ->
                Focusable(Modifier.fillMaxWidth().height(48.dp), RoundedCornerShape(12.dp), bg = if (p.on) Color(0xFF3A3320) else white(.10f),
                    requester = if (i == start) first else null, onKey = swallowHeld, onOk = { onPick(p) }) {
                    Column(Modifier.align(Alignment.CenterStart).padding(horizontal = 14.dp)) {
                        Text(p.title, color = if (p.on) Amber else Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                            overflow = TextOverflow.Ellipsis)
                        if (p.sub.isNotBlank()) Text(p.sub, color = white(.55f), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            Text("Let go of OK, then pick a stream with ▲ ▼ and OK · Back cancel", color = white(.4f), fontSize = 11.sp)
        }
    }
}
