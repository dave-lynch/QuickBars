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

// Kitchen > Dinners & shopping: nights, shopping review, search, voice and the dinner picker.

// ============================================================ Kitchen > Dinners & shopping (a TV-only draft in HA)
/** Three tabs: the nights the next order covers (OK changes a night's dinner), the suggested shopping by section (OK ticks an item
 *  in or out, hold OK sets the quantity) and the total. Everything comes from HA (tv_meals.py); presses go back as meals:<...>. */
@Composable
internal fun MealsPage(
    m: CcMeals?, tab: Int, onTab: (Int) -> Unit, onAction: (String) -> Unit, overrides: MutableMap<String, Pair<Boolean, Int>>,
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
internal fun MealNights(m: CcMeals, onPick: (CcMealNight) -> Unit, onNext: () -> Unit) {
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
                                else -> listOf(n.mins, n.needs.take(4).joinToString(" · ")).filter { it.isNotBlank() }.joinToString(" · ") },
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
                        n.needs.take(7).forEach { Text(it, color = white(.85f), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                        if (n.needs.size > 7) Text("+${n.needs.size - 7} more", color = white(.5f), fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

@Composable
internal fun Badge(t: String) {
    Text(t, color = Ink, fontSize = 10.sp, fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(start = 6.dp).clip(RoundedCornerShape(99.dp)).background(Amber).padding(horizontal = 6.dp, vertical = 1.dp))
}

internal fun eur(v: Double) = "€" + String.format(Locale.UK, "%.2f", v)

@Composable
internal fun ShopReview(m: CcMeals, ov: MutableMap<String, Pair<Boolean, Int>>, onAction: (String) -> Unit, onQty: (CcShopItem) -> Unit, onNext: () -> Unit,
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
                            onLong = { onQty(item) }, onOk = { onAction("meals:item:${item.id}:${if (isOn) 0 else 1}"); ov[item.id] = !isOn to qq }) {
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
internal fun cleanSpoken(t: String): String =
    t.lowercase(Locale.UK).replace(Regex("[^a-z0-9 &-]"), " ").replace(Regex("^\\s*(please\\s+)?(add|search for|search|find|get|buy)\\s+(some\\s+)?"), "")
        .replace(Regex("\\s+(please|to the list|to my list)\\s*$"), "").replace(Regex("\\s+"), " ").trim().take(40).trim()

@Composable
internal fun Pill(icon: String, label: String, onOk: () -> Unit, requester: FocusRequester? = null) {
    Focusable(Modifier.padding(start = 8.dp).height(34.dp), RoundedCornerShape(17.dp), bg = white(.12f), focusedBg = Amber, requester = requester, onOk = onOk) { f ->
        Row(Modifier.align(Alignment.Center).padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Image(painterResource(ccIcon(icon)), null, Modifier.size(16.dp), colorFilter = ColorFilter.tint(if (f) Ink else Color.White))
            Text(" $label", color = if (f) Ink else Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
    }
}

/** Listening / heard / error card over the meals page while voice search runs. */
@Composable
internal fun VoiceBubble(v: CcVoice.Ui, modifier: Modifier) {
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
 *  first, then the shop's catalogue, then a live shop lookup), and "Add <words> as said". Adds go under "Added by you". */
@Composable
internal fun FindResults(m: CcMeals, f: CcFind?, searching: String, ov: MutableMap<String, Pair<Boolean, Int>>, onAction: (String) -> Unit,
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
        Text("Searching your orders and the shop…", color = white(.7f), fontSize = 15.sp, modifier = Modifier.padding(top = 18.dp))
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
                    onAction("meals:item:${o.id}:${if (isOn) 0 else 1}"); ov[o.id] = !isOn to qq
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
        if (f.looking) item(key = "look") { Text("Looking in the shop for more…", color = white(.6f), fontSize = 13.sp, modifier = Modifier.padding(6.dp)) }
        else if (f.hits.isEmpty()) item(key = "none") { Text("Nothing in your orders or the shop's catalogue.", color = white(.6f), fontSize = 13.sp, modifier = Modifier.padding(6.dp)) }
        item(key = "free") {
            FindRow(if (freeAdded) "check" else "plus", "Add “${f.q.replaceFirstChar { it.uppercase() }}” as said", "No shop product - you pick it when ordering", "",
                on = freeAdded, requester = if (firstAt == "free") first else null) { if (!freeAdded) { freeAdded = true; onAction("meals:addfree") } }
        }
    }
}

@Composable
internal fun FindRow(icon: String?, name: String, sub: String, price: String, on: Boolean, requester: FocusRequester?, onOk: () -> Unit) {
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
internal fun ShopTotal(m: CcMeals, ov: Map<String, Pair<Boolean, Int>>, onAction: (String) -> Unit) {
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
internal fun MealPicker(n: CcMealNight, choices: List<CcMealChoice>, onPick: (String) -> Unit) {
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
internal fun QtyPopup(name: String, q: Int, price: Double) {
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
