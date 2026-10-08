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
    onOk: () -> Unit = {},
    content: @Composable BoxScope.(focused: Boolean) -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
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
                else if (e.isOk()) { if (e.type == KeyEventType.KeyUp) onOk(); true } else false
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
    val req = remember(url, tick) { imageRequest(ctx, url, tick) }
    if (req == null) Box(modifier.background(white(.08f))) else AsyncImage(req, null, modifier, contentScale = crop)
}

// ============================================================ root
@Composable
fun ControlCenterRoot(
    spec: ControlCenterSpec,
    onAction: (String) -> Unit,
    onCamera: (String) -> Unit,
    onClose: () -> Unit,
) {
    var page by remember(spec.page) { mutableStateOf(spec.page) }
    val dismissed = remember { mutableStateListOf<String>() }
    val notes = spec.notifications.filter { it.id !in dismissed }
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    val slide by animateFloatAsState(if (shown) 0f else 1f, spring(dampingRatio = .85f, stiffness = 300f), label = "ccSlide")

    fun back() { page = when (page) { "catchup" -> if (spec.page == "catchup") "close" else "notifications"; "notifications" -> "home"; else -> "close" }
                 if (page == "close") onClose() }
    fun openNote(n: CcNotification) {
        when {
            n.open.startsWith("page:") -> page = n.open.removePrefix("page:")
            n.open.startsWith("camera:") -> { onCamera(n.open.removePrefix("camera:")); onClose() }
            else -> { onAction("notif_open:${n.id}"); onClose() }
        }
    }
    fun dismiss(n: CcNotification) { dismissed += n.id; onAction("notif_dismiss:${n.id}") }

    Box(
        Modifier.fillMaxSize()
            .background(Brush.horizontalGradient(listOf(Color.Transparent, Color(0x99000000))))
            .onPreviewKeyEvent { e ->
                if (e.key == Key.Back || e.key == Key.Escape) { if (e.type == KeyEventType.KeyUp) back(); true } else false
            }
    ) {
        when (page) {
            "catchup" -> CatchupPage(spec, onBack = { back() }, onAction = onAction)
            else -> Box(
                Modifier.align(Alignment.CenterEnd).padding(20.dp).width(320.dp).fillMaxHeight()
                    .graphicsLayer { translationX = slide * size.width * 1.1f }
                    .clip(RoundedCornerShape(26.dp)).background(PanelBrush)
                    .border(1.dp, white(.12f), RoundedCornerShape(26.dp))
            ) {
                if (page == "notifications") NotificationsPage(notes, onBack = { back() }, onOpen = ::openNote, onDismiss = ::dismiss,
                    onClearAll = { notes.forEach { dismiss(it) }; page = "home" })
                else HomePage(spec, notes, onOpenStack = { page = "notifications" }, onOpen = ::openNote, onAction = onAction, onCamera = { onCamera(it); onClose() })
            }
        }
    }
}

// ============================================================ D2: home
@Composable
private fun HomePage(
    spec: ControlCenterSpec, notes: List<CcNotification>,
    onOpenStack: () -> Unit, onOpen: (CcNotification) -> Unit, onAction: (String) -> Unit, onCamera: (String) -> Unit,
) {
    var clock by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { while (true) { clock = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date()); delay(10_000) } }
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(4_000); tick++ } }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(80); runCatching { first.requestFocus() } }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(clock, color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
        if (spec.subtitle.isNotBlank()) Text(spec.subtitle, color = white(.65f), fontSize = 11.sp, modifier = Modifier.offset(y = (-8).dp))
        spec.now?.let { n ->
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(white(.08f)).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Net(n.image, Modifier.size(70.dp, 40.dp).clip(RoundedCornerShape(8.dp)))
                Column(Modifier.padding(start = 10.dp)) {
                    Text(n.label, color = Amber, fontSize = 9.sp, fontWeight = FontWeight.SemiBold)
                    Text(n.title, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (n.sub.isNotBlank()) Text(n.sub, color = white(.6f), fontSize = 9.sp, maxLines = 1)
                }
            }
        }
        // notification stack (collapsed): top card + two edges peeking behind
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Notifications", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            if (notes.isNotEmpty()) Text("${notes.size} new", color = Ink, fontSize = 9.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.clip(RoundedCornerShape(50)).background(Amber).padding(horizontal = 8.dp, vertical = 2.dp))
        }
        if (notes.isEmpty()) {
            Text("You're all caught up", color = white(.5f), fontSize = 11.sp, modifier = Modifier.padding(vertical = 8.dp))
        } else Box(Modifier.fillMaxWidth().height(if (notes.size > 1) 92.dp else 80.dp)) {
            if (notes.size > 2) Box(Modifier.align(Alignment.BottomCenter).padding(horizontal = 18.dp).fillMaxWidth().height(20.dp).clip(RoundedCornerShape(12.dp)).background(white(.06f)))
            if (notes.size > 1) Box(Modifier.align(Alignment.BottomCenter).padding(horizontal = 9.dp).offset(y = (-6).dp).fillMaxWidth().height(20.dp).clip(RoundedCornerShape(13.dp)).background(white(.10f)))
            Focusable(Modifier.fillMaxWidth().height(80.dp), RoundedCornerShape(14.dp), bg = Color(0xFF332B45), focusedBg = Color(0xFF443A5A),
                requester = first, onOk = { if (notes.size == 1) onOpen(notes[0]) else onOpenStack() }) {
                NoteBody(notes[0], compact = true)
            }
        }
        if (notes.size > 1) Text("+${notes.size - 1} more · " + notes.drop(1).take(3).joinToString(", ") { it.title.take(18) },
            color = white(.45f), fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        // cameras strip
        if (spec.cameras.isNotEmpty()) {
            Text(spec.cameraStatus.ifBlank { "Cameras" }, color = white(.75f), fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                spec.cameras.take(4).forEach { c ->
                    Focusable(Modifier.weight(1f).height(56.dp), RoundedCornerShape(10.dp), bg = Color.Black, onOk = { onCamera(c.entity) }) { f ->
                        Net(c.image, Modifier.fillMaxSize(), tick)
                        Text(c.name, color = Color.White, fontSize = 8.sp, fontWeight = FontWeight.Bold, maxLines = 1,
                            modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth().background(Color(0x99000000)).padding(horizontal = 5.dp, vertical = 2.dp))
                    }
                }
            }
        }
        // quick tiles
        if (spec.tiles.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            spec.tiles.take(4).forEach { t ->
                var on by remember(t.id, t.on) { mutableStateOf(t.on) }
                Focusable(Modifier.weight(1f).height(72.dp), RoundedCornerShape(14.dp),
                    bg = if (on) white(.92f) else white(.10f), focusedBg = if (on) Color.White else white(.22f),
                    onOk = { on = !on; onAction("tile:${t.id}") }) {
                    Column(Modifier.fillMaxSize().padding(8.dp)) {
                        Image(painterResource(ccIcon(t.icon)), null, Modifier.size(18.dp), colorFilter = ColorFilter.tint(if (on) Color(0xFFF59E0B) else Color.White))
                        Spacer(Modifier.weight(1f))
                        Text(t.title, color = if (on) Ink else Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (t.sub.isNotBlank()) Text(t.sub, color = if (on) Ink.copy(alpha = .6f) else white(.6f), fontSize = 8.sp, maxLines = 1)
                    }
                }
            }
        }
        Spacer(Modifier.weight(1f))
        Text("OK to open · Back to close", color = white(.35f), fontSize = 8.sp)
    }
}

@Composable
private fun NoteBody(n: CcNotification, compact: Boolean) {
    Row(Modifier.fillMaxSize().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconTile(n.icon, 18.dp, n.color)
                Text(n.app, color = white(.6f), fontSize = 8.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 6.dp).weight(1f), maxLines = 1)
                Text(n.whenText, color = white(.5f), fontSize = 8.sp)
            }
            Text(n.title, color = Color.White, fontSize = if (compact) 12.sp else 13.sp, fontWeight = FontWeight.Bold, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
            if (n.body.isNotBlank()) Text(n.body, color = white(.65f), fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
    LaunchedEffect(Unit) { delay(60); runCatching { first.requestFocus() } }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Image(painterResource(ccIcon("chevron_left")), null, Modifier.size(22.dp), colorFilter = ColorFilter.tint(Amber))
            Text("Notifications", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Focusable(Modifier.height(24.dp), RoundedCornerShape(12.dp), onOk = onClearAll) {
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

// ============================================================ D4: catch-up page (full screen)
@Composable
private fun CatchupPage(spec: ControlCenterSpec, onBack: () -> Unit, onAction: (String) -> Unit) {
    var sel by remember { mutableStateOf(spec.catchup.firstOrNull()?.items?.firstOrNull()) }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(60); runCatching { first.requestFocus() } }
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xF00E0B16), Color(0xFA120E1C))))) {
        Column(Modifier.fillMaxSize().padding(start = 48.dp, top = 28.dp, end = 0.dp, bottom = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Image(painterResource(ccIcon("chevron_left")), null, Modifier.size(26.dp), colorFilter = ColorFilter.tint(Amber))
                Text("Catch up", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            }
            Text("From your guide, reminders and followed shows", color = white(.55f), fontSize = 10.sp, modifier = Modifier.padding(start = 26.dp, bottom = 10.dp))
            if (spec.catchup.isEmpty()) Text("Nothing to catch up on", color = white(.5f), fontSize = 13.sp)
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                itemsIndexed(spec.catchup) { si, sec ->
                    Column {
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text(sec.title, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                            Text("  ${sec.items.size} programmes", color = white(.5f), fontSize = 9.sp, modifier = Modifier.padding(bottom = 2.dp))
                        }
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(13.dp), contentPadding = PaddingValues(top = 8.dp, bottom = 4.dp, end = 48.dp)) {
                            itemsIndexed(sec.items, key = { _, it -> it.id }) { ii, it ->
                                PosterCard(it, requester = if (si == 0 && ii == 0) first else null,
                                    onFocus = { sel = it }, onOk = { onAction("catchup_play:${it.id}") })
                            }
                        }
                    }
                }
            }
            sel?.let { s ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                    Text(s.detail.ifBlank { s.title }, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1, modifier = Modifier.widthIn(max = 280.dp))
                    Text("   OK  Watch", color = Ink, fontSize = 10.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(start = 12.dp).clip(RoundedCornerShape(50)).background(Amber).padding(horizontal = 10.dp, vertical = 4.dp))
                }
            }
        }
    }
}

@Composable
private fun PosterCard(it: CcCatchupItem, requester: FocusRequester?, onFocus: () -> Unit, onOk: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val s by animateFloatAsState(if (focused) 1.06f else 1f, tween(140), label = "pc")
    Column(Modifier.width(88.dp)) {
        Box(
            Modifier.size(88.dp, 126.dp).graphicsLayer { scaleX = s; scaleY = s }.clip(RoundedCornerShape(10.dp))
                .background(Brush.verticalGradient(listOf(Color(0xFF105C46), Color(0xFF0C283C))))
                .border(if (focused) 3.dp else 0.dp, if (focused) Amber else Color.Transparent, RoundedCornerShape(10.dp))
                .then(if (requester != null) Modifier.focusRequester(requester) else Modifier)
                .onFocusChanged { f -> focused = f.isFocused; if (f.isFocused) onFocus() }
                .onKeyEvent { e -> if (e.isOk()) { if (e.type == KeyEventType.KeyUp) onOk(); true } else false }
                .focusable()
        ) {
            if (it.img != null) Net(it.img, Modifier.fillMaxSize())
            else Text(it.title, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(8.dp))
            it.badge?.let { b ->
                Text(b, color = Amber, fontSize = 7.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(5.dp).clip(RoundedCornerShape(50)).background(Color(0xDD140F1E)).padding(horizontal = 5.dp, vertical = 1.dp))
            }
            it.prog?.let { p ->
                Box(Modifier.align(Alignment.BottomCenter).padding(6.dp).fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)).background(white(.35f))) {
                    Box(Modifier.fillMaxHeight().fillMaxWidth(p.coerceIn(0f, 1f)).background(Amber))
                }
            }
        }
        Text(it.title, color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
        Text(it.sub, color = white(.55f), fontSize = 8.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (it.days.isNotBlank()) Text(it.days, color = white(.38f), fontSize = 7.sp, maxLines = 1)
    }
}
