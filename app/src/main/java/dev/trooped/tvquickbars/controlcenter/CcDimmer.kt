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

// Brightness / volume slider and colour temperature presets (hold OK on a light tile).

// ============================================================ brightness slider (hold OK on a light tile)
@Composable
internal fun Dimmer(title: String, pct: Int, parts: List<Pair<String, Int>> = emptyList(), sel: Int = 0, volume: Boolean = false,
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
                    Column(Modifier.width(104.dp).clip(RoundedCornerShape(12.dp)).background(if (i == sel) Color.White else white(.12f))
                        .border(if (hi) 2.dp else 0.dp, if (hi) Amber else Color.Transparent, RoundedCornerShape(12.dp))
                        .padding(horizontal = 8.dp, vertical = 6.dp)) {
                        Text(if (i == 0) "All" else name, color = if (i == sel) Ink else Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val k = ct?.parts?.getOrNull(i) ?: 0
                        Text((if (p == 0) "Off" else "$p%") + (if (ct != null && k > 0) " · ${ctName(k, ct.kmin, ct.kmax)}" else ""),
                            color = if (i == sel) Ink.copy(alpha = .6f) else white(.6f), fontSize = 11.sp, maxLines = 1)
                    }
                }
            }
            // colour: a thin warm-to-cool strip with the presets under it; the dot is where the light is
            if (ct != null) {
                val w = if (parts.size > 1) (104 * parts.size + 6 * (parts.size - 1)).dp else 220.dp
                val lo = ctPreset(0, ct.kmin, ct.kmax)   // the strip runs Warm to Cool, like the presets under it
                val f = ((ct.k - lo).toFloat() / (ct.kmax - lo).coerceAtLeast(1)).coerceIn(0f, 1f)
                Box(Modifier.padding(top = 16.dp).width(w).height(16.dp), contentAlignment = Alignment.CenterStart) {
                    Box(Modifier.fillMaxWidth().height(if (ct.on) 6.dp else 4.dp).clip(RoundedCornerShape(3.dp))
                        .background(Brush.horizontalGradient(listOf(kColor(lo), kColor((lo + ct.kmax) / 2), kColor(ct.kmax)))))
                    Box(Modifier.offset(x = (w - 16.dp) * f).size(16.dp).clip(RoundedCornerShape(8.dp)).background(kColor(ct.k))
                        .border(2.dp, if (ct.on) Amber else Color.White, RoundedCornerShape(8.dp)))
                }
                Row(Modifier.width(w).padding(top = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    val here = ctName(ct.k, ct.kmin, ct.kmax)
                    CT_PRESETS.forEach { n ->
                        Text(n, color = if (n == here) (if (ct.on) Amber else Color.White) else white(.45f), fontSize = 11.sp,
                            fontWeight = if (n == here) FontWeight.Bold else FontWeight.Normal)
                    }
                }
            }
            Text(if (ct != null && ct.on) "◀ ▶ warmer / cooler · ▲ ▼ brightness · OK " + (if (parts.size > 1) "pick a light" else "back") + " · Back done"
                 else (if (parts.size > 1) "◀ ▶ pick a light · " else "") + "▲ ▼ adjust · " + (if (ct != null) "OK colour · Back done" else "OK done"),
                color = white(.5f), fontSize = 11.sp, modifier = Modifier.padding(top = 12.dp))
        }
    }
}

/** The colour pill's state: [on] = ▲ ▼ moves the colour; [parts] = each chip's kelvin (0 = no colour temperature). */
internal data class CtView(val on: Boolean, val k: Int, val kmin: Int, val kmax: Int, val parts: List<Int>)

// Same names and spacing as the room card: four presets evenly over the light's range
internal val CT_PRESETS = listOf("Warm", "Soft", "Neutral", "Cool")
// Warm starts at 2700 K (a normal warm-white bulb), not the light's very warmest (some lights go down to 2200 K)
internal const val CT_WARM = 2700
internal fun ctPreset(i: Int, kmin: Int, kmax: Int): Int {
    val lo = if (kmax > CT_WARM) maxOf(kmin, CT_WARM) else kmin
    return ((lo + (kmax - lo) * i / 3.0) / 100).roundToInt() * 100
}
internal fun ctName(k: Int, kmin: Int, kmax: Int): String =
    CT_PRESETS[CT_PRESETS.indices.minByOrNull { kotlin.math.abs(ctPreset(it, kmin, kmax) - k) } ?: 0]

/** Rough colour of white light at [k] kelvin (Tanner Helland's fit), for the colour pill. */
internal fun kColor(k: Int): Color {
    val t = k / 100.0
    val r = if (t <= 66) 255.0 else 329.698727446 * Math.pow(t - 60, -0.1332047592)
    val g = if (t <= 66) 99.4708025861 * Math.log(t) - 161.1195681661 else 288.1221695283 * Math.pow(t - 60, -0.0755148492)
    val b = if (t >= 66) 255.0 else if (t <= 19) 0.0 else 138.5177312231 * Math.log(t - 10) - 305.0447927307
    fun c(v: Double) = v.coerceIn(0.0, 255.0).toInt()
    return Color(c(r), c(g), c(b))
}
