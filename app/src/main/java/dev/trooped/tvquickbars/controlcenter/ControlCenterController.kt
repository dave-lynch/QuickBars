package dev.trooped.tvquickbars.controlcenter

import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.annotation.MainThread
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import dev.trooped.tvquickbars.R
import dev.trooped.tvquickbars.background.BackgroundHaConnectionManager
import dev.trooped.tvquickbars.data.AppIdProvider
import dev.trooped.tvquickbars.services.ComposeViewLifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Shows the Control Center (see [ControlCenterSpec]) as a full-screen, focusable overlay window, so the remote drives
 * it while it is open; Back (or 90 s without a key press) closes it. "update" events refresh the content in place.
 * Presses go back to HA as quickbars.action events: cid "control_center", action_id e.g. "tile:light.x",
 * "notif_open:<id>", "notif_dismiss:<id>", "catchup_play:<id>". Camera tiles open the app's own camera PiP.
 *
 * Speed: the event data (up to a few hundred KB with the TV rows) is parsed off the main thread, in arrival order. The
 * window is added once and kept (hidden, not focusable, no content) between opens, so opening only sets the content.
 * [openNow] opens it straight from the remote button, before HA has answered, with the last content (or empty cards).
 */
class ControlCenterController(
    private val context: Context,
    private val windowManager: WindowManager,
    private val runOnMain: (() -> Unit) -> Unit,
    private val serviceScope: CoroutineScope,
    private val openCamera: (String, String?, Boolean, String) -> Unit,   // entity, RTSP, big PiP, name
) {
    private var view: ComposeView? = null
    private var params: WindowManager.LayoutParams? = null
    private var shown = false
    private val lifecycleOwner = ComposeViewLifecycleOwner()
    private var created = false
    private val spec = mutableStateOf<ControlCenterSpec?>(null)
    /** Bumped on each open: the panel starts again from its first page with fresh state. */
    private val openSeq = mutableIntStateOf(0)
    /** Waiting for HA's content after [openNow] (the panel shows "Updating…" and dims what it carried over). */
    private val loading = mutableStateOf(false)
    private var lastSpecAt = 0L
    private var localOpenAt = 0L
    private val idle = Handler(Looper.getMainLooper())
    private val idleClose = Runnable { close() }
    private val loadingOff = Runnable { loading.value = false }
    private val inbox = Channel<JSONObject>(Channel.UNLIMITED)

    init {
        // One consumer: parse in order on a background thread, then apply on the main thread (posts keep the order)
        serviceScope.launch(Dispatchers.Default) {
            for (data in inbox) {
                val s = try { ControlCenterSpec.parse(data) } catch (t: Throwable) { Log.e(TAG, "bad control center data", t); continue }
                val keys = data.keys().asSequence().toSet()
                runOnMain { apply(s, keys) }
            }
        }
    }

    fun onServiceConnected() { if (!created) { lifecycleOwner.create(); created = true } }
    fun onDestroy() {
        close(); inbox.close()
        view?.let { v -> try { windowManager.removeViewImmediate(v) } catch (_: Throwable) {} }
        view = null
        try { lifecycleOwner.destroy() } catch (_: Throwable) {}
    }

    fun handle(data: JSONObject) { inbox.trySend(data) }

    /** The remote button that runs HA's Control Center script was pressed: show the panel now, HA's data follows. */
    @MainThread
    fun openNow() {
        if (shown) return
        val last = spec.value
        // the carried-over date line would be wrong after midnight, so only keep it for a while
        val s = (last ?: ControlCenterSpec.EMPTY).copy(action = "open", page = "home",
            subtitle = if (last != null && SystemClock.elapsedRealtime() - lastSpecAt < 30 * 60_000) last.subtitle else "")
        spec.value = s; localOpenAt = SystemClock.elapsedRealtime()
        loading.value = true; idle.removeCallbacks(loadingOff); idle.postDelayed(loadingOff, 15_000)
        openSeq.intValue++
        show(); bumpIdle()
    }

    @MainThread
    private fun apply(s: ControlCenterSpec, has: Set<String>) {
        when (s.action) {
            "close" -> close()
            // Update only what was sent (e.g. just "tiles" after a press), keep the page the user is on
            "update" -> spec.value?.let { cur ->   // also while closed: keeps the next open's carried-over parts fresh
                spec.value = cur.copy(
                    subtitle = if ("subtitle" in has) s.subtitle else cur.subtitle,
                    now = if ("now" in has) s.now else cur.now,
                    notifications = if ("notifications" in has) s.notifications else cur.notifications,
                    cameras = if ("cameras" in has) s.cameras else cur.cameras,
                    snaps = if ("snaps" in has) s.snaps else cur.snaps,
                    cameraStatus = if ("camera_status" in has) s.cameraStatus else cur.cameraStatus,
                    tiles = if ("tiles" in has) s.tiles else cur.tiles,
                    catchup = if ("catchup" in has) s.catchup else cur.catchup,
                    rooms = if ("rooms" in has) s.rooms else cur.rooms,
                    meals = if ("meals" in has) s.meals else cur.meals,
                )
                lastSpecAt = SystemClock.elapsedRealtime()
                // the slow parts have arrived: the carried-over content is current again
                if ("notifications" in has || "catchup" in has) { loading.value = false; idle.removeCallbacks(loadingOff) }
            }
            // Open: HA sends the quick parts at once and the slow ones (notifications, now playing, catch-up, rooms) a moment later as an
            // "update", so keep showing the last ones we had meanwhile instead of an empty space
            else -> {
                val last = spec.value
                val s2 = if (last == null) s else s.copy(
                    notifications = if ("notifications" in has) s.notifications else last.notifications,
                    now = if ("now" in has) s.now else last.now,
                    catchup = if ("catchup" in has) s.catchup else last.catchup,
                    rooms = if ("rooms" in has) s.rooms else last.rooms,
                    meals = last.meals,
                )
                spec.value = s2; lastSpecAt = SystemClock.elapsedRealtime()
                // HA's answer to the button press that already opened the panel: fill it in place (same page, same focus).
                // Any other open (a script or automation, or the panel was closed meanwhile) starts the panel again.
                val answer = shown && s2.page == "home" && SystemClock.elapsedRealtime() - localOpenAt < 10_000
                if (!answer) {
                    openSeq.intValue++
                    if (!shown) { loading.value = false; show() }
                }
                localOpenAt = 0L
            }
        }
        if (shown) bumpIdle()
    }

    private fun bumpIdle() { idle.removeCallbacks(idleClose); idle.postDelayed(idleClose, 90_000) }

    private fun hiddenFlags() = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
    private fun shownFlags() = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED

    /** Adds the (hidden) window once; later opens reuse it. */
    @MainThread
    private fun ensureWindow(): ComposeView? {
        view?.let { return it }
        if (!android.provider.Settings.canDrawOverlays(context)) return null
        val v = ComposeView(ContextThemeWrapper(context, R.style.Theme_HAQuickBars)).apply {
            setViewCompositionStrategy(androidx.compose.ui.platform.ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            visibility = View.GONE
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, hiddenFlags(), PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.START; windowAnimations = 0 }
        lifecycleOwner.attachToView(v)
        try { windowManager.addView(v, lp) } catch (t: Throwable) { Log.e(TAG, "addView failed", t); return null }
        v.setOnKeyListener { _, _, _ -> bumpIdle(); false }
        view = v; params = lp
        return v
    }

    /** Builds the window now (hidden), so the first press of the button doesn't pay for it. */
    @MainThread
    fun prepare() { ensureWindow() }

    @MainThread
    private fun show() {
        val v = ensureWindow() ?: return
        val lp = params ?: return
        shown = true
        lifecycleOwner.resume()
        v.setContent {
            key(openSeq.intValue) {
                spec.value?.let { s ->
                    ControlCenterRoot(
                        spec = s,
                        loading = loading.value,
                        onAction = { a -> bumpIdle(); send(a) },
                        onCamera = { e, rtsp, big, name -> openCamera(e, rtsp, big, name) },
                        onClose = { close() },
                        onKeepAlive = { bumpIdle() },
                    )
                }
            }
        }
        lp.flags = shownFlags()
        v.visibility = View.VISIBLE
        try { windowManager.updateViewLayout(v, lp) } catch (t: Throwable) { Log.e(TAG, "show failed", t) }
        v.requestFocus()
        send("opened")
    }

    @MainThread
    fun close() {
        idle.removeCallbacks(idleClose)
        if (!shown) return
        shown = false; localOpenAt = 0L
        val v = view ?: return
        try { lifecycleOwner.pause() } catch (_: Throwable) {}
        // drop the content (stops the clock, camera refreshes and video) and hide the window; it stays added for the next open
        try { v.setContent { } } catch (_: Throwable) {}
        v.visibility = View.GONE
        params?.let { lp ->
            lp.flags = hiddenFlags()
            try { windowManager.updateViewLayout(v, lp) } catch (t: Throwable) {
                Log.e(TAG, "hide failed", t); try { windowManager.removeViewImmediate(v) } catch (_: Throwable) {}; view = null
            }
        }
    }

    private fun send(actionId: String) {
        val ha = BackgroundHaConnectionManager.getClient() ?: return
        val data = JSONObject().apply {
            put("cid", "control_center"); put("action_id", actionId)
            AppIdProvider.get(context).takeIf { it.isNotBlank() }?.let { put("id", it) }
        }
        serviceScope.launch(Dispatchers.IO) { ha.fireEvent("quickbars.action", data) }
    }

    companion object { private const val TAG = "ControlCenter" }
}
