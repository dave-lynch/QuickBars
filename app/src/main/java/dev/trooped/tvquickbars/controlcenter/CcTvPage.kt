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

// TV page (D4): filter chips, sports strip, poster rows and the hold-OK menu.

// ============================================================ D4: TV page (full screen)
internal fun okLabel(ok: String) = when {
    ok.startsWith("watch_ch:") -> "Watch"
    ok.startsWith("stream:") -> "Stream"
    ok.startsWith("catchup_play:") -> "Catch up"
    ok.startsWith("tvfb:remind:") -> "Remind me"
    else -> ""
}
internal val MENU_LABEL = mapOf("seen" to "Watched it", "remind" to "Remind me", "follow" to "Follow", "like" to "Like", "dislike" to "Not interested")

/** TV page filter, remembered while the app runs. When HA sends rows with "views": Series / Films (yours first, then
 *  what's on, then picks for you), Everything (the whole guide) and Sport; each row lists the chips that show it.
 *  Older HA data without views: "mine" = My shows, "foryou" = mine + recommended, "all", "sport" by item tag. */
internal object TvView { var current = "series"; var sport = "" }
internal val TV_VIEWS = listOf("series" to "Series", "films" to "Films", "all" to "Everything", "sport" to "Sport")
internal val TV_VIEWS_TAGS = listOf("mine" to "My shows", "foryou" to "Recommended", "all" to "Everything", "sport" to "Sport")
internal fun tvRows(catchup: List<CcSection>, view: String): List<CcSection> =
    if (catchup.any { it.views.isNotEmpty() }) catchup.filter { view in it.views }
    else catchup.map { CcSection(it.title, it.items.filter { i -> tvKeep(view, i) }) }.filter { it.items.isNotEmpty() }
/** The sports strip on the left of the Sport view: the rows' groups in order ("Top", "Formula 1", "Soccer"...), shown
 *  only when HA sends more than one; sportRows keeps the rows of the picked one (or the first, when it's gone). */
internal fun sportGroups(rows: List<CcSection>): List<String> = rows.map { it.group }.filter { it.isNotBlank() }.distinct()
internal fun sportRows(rows: List<CcSection>, group: String): List<CcSection> {
    val groups = sportGroups(rows)
    if (groups.size < 2) return rows
    val g = if (group in groups) group else groups[0]
    return rows.filter { it.group == g }
}
internal fun tvKeep(view: String, it: CcCatchupItem) = when (view) {
    "sport" -> it.tag == "sport"
    "mine" -> it.tag == "mine"
    "foryou" -> it.tag == "mine" || it.tag == "pick"
    else -> it.tag != "sport"
}

@Composable
internal fun CatchupPage(
    spec: ControlCenterSpec, onAction: (String) -> Unit, onClose: () -> Unit,
    menu: CcCatchupItem?, onMenu: (CcCatchupItem?) -> Unit,
) {
    // Apple TV style: the focused programme fills the top (big title, where and when, description, its artwork fading
    // in from the right); rows of posters underneath: Up next, On now, Later tonight, New to stream, then the catch-up
    // days. OK does the row's main thing (watch / stream / remind); hold OK for remind / follow / like / not interested.
    // ▲ from the top row reaches the filter chips (Series · Films · Everything · Sport); OK on one switches the
    // rows. Sport has its own rows (Formula 1, PPV, Soccer, Boxing), live TV channels first.
    val byViews = spec.catchup.any { it.views.isNotEmpty() }
    val views = if (byViews) TV_VIEWS else TV_VIEWS_TAGS
    var view by remember {
        mutableStateOf(TvView.current.let { v ->
            when {
                views.none { it.first == v } -> views[0].first
                v == "mine" && spec.catchup.none { s -> s.items.any { it.tag == "mine" } } -> "foryou"
                else -> v
            }
        })
    }
    val all = remember(spec.catchup, view) { tvRows(spec.catchup, view) }
    // Sport: a strip of sports down the left (Top, Formula 1, Soccer...); moving onto one shows its rows
    val groups = remember(all, view) { if (view == "sport") sportGroups(all) else emptyList() }
    var group by remember { mutableStateOf(TvView.sport) }
    val strip = groups.size > 1
    val shown = remember(all, group, strip) { if (strip) sportRows(all, group) else all }
    var sel by remember { mutableStateOf(shown.firstOrNull()?.items?.firstOrNull()) }
    var selDay by remember { mutableStateOf(shown.firstOrNull()?.title ?: "") }
    LaunchedEffect(view, group) { sel = shown.firstOrNull()?.items?.firstOrNull(); selDay = shown.firstOrNull()?.title ?: "" }
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
                views.forEach { (v, label) ->
                    val on = view == v
                    Focusable(Modifier.padding(start = 8.dp).height(30.dp), RoundedCornerShape(50),
                        bg = if (on) Amber else white(.10f), focusedBg = if (on) Amber else white(.30f),
                        requester = if (v == views[0].first) chip else null,
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
                        Text((if (s.menu.isNotEmpty()) "hold OK for more · " else "") + "◀ ▶ programmes · ▲ ▼ rows · ▲ at top: filter · " +
                            (if (strip) "◀ at the start: sports · " else "") + "Back", color = white(.5f), fontSize = 11.sp)
                    }
                }
            }
            if (shown.isEmpty()) Text(when (view) {
                    "mine" -> "Nothing from your shows right now · press ▲ and pick Recommended"
                    "series", "films" -> "Nothing here yet · press ▲ and pick Everything"
                    "sport" -> "No sport found yet: it fills at the next guide refresh"
                    else -> "Nothing here yet"
                },
                color = white(.6f), fontSize = 16.sp, modifier = Modifier.padding(top = 12.dp))
            Row(Modifier.weight(1f).padding(top = 10.dp)) {
            if (strip) Column(Modifier.width(150.dp).padding(end = 14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                val cur = if (group in groups) group else groups[0]
                groups.forEach { g ->
                    val on = g == cur
                    Focusable(Modifier.fillMaxWidth().height(36.dp), RoundedCornerShape(12.dp),
                        bg = if (on) Amber else white(.08f), focusedBg = if (on) Amber else white(.26f),
                        onOk = { group = g; TvView.sport = g }) { f ->
                        LaunchedEffect(f) { if (f && group != g) { group = g; TvView.sport = g } }
                        Text(g, color = if (on) Ink else Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1,
                            overflow = TextOverflow.Ellipsis, modifier = Modifier.align(Alignment.CenterStart).padding(horizontal = 12.dp))
                    }
                }
            }
            LazyColumn(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                itemsIndexed(shown, key = { _, sec -> view + sec.group + sec.title }) { si, sec ->
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
            }   // strip + rows
        }
        menu?.let { m ->
            TvMenu(m, onPick = { act ->
                onMenu(null)
                if (act == "ok") press(m)
                else {
                    onAction("tvfb:$act:${m.key}")
                    toast = when (act) { "seen" -> "Marked watched"; "remind" -> "Reminder set"; "follow" -> "Following"; "like" -> "Liked"; else -> "Hidden from now on" } + " · ${m.title}"
                }
            })
        }
    }
}

/** Hold-OK menu over the TV page: the row's main action, then remind / follow / like / not interested. */
@Composable
internal fun TvMenu(m: CcCatchupItem, onPick: (String) -> Unit) {
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
            Text("OK choose · Back", color = white(.4f), fontSize = 11.sp, modifier = Modifier.padding(top = 2.dp))
        }
    }
}

@Composable
internal fun PosterCard(it: CcCatchupItem, requester: FocusRequester?, onFocus: () -> Unit, onOk: () -> Unit, onLong: (() -> Unit)? = null) {
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
                Text(b, color = Amber, fontSize = 10.sp, fontWeight = FontWeight.Bold,
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
        Text(it.sub, color = white(.55f), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
