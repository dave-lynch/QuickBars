package dev.trooped.tvquickbars.controlcenter

import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.annotation.MainThread
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import dev.trooped.tvquickbars.R
import dev.trooped.tvquickbars.background.BackgroundHaConnectionManager
import dev.trooped.tvquickbars.data.AppIdProvider
import dev.trooped.tvquickbars.services.ComposeViewLifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Shows the Control Center (see [ControlCenterSpec]) as a full-screen, focusable overlay window, so the remote drives
 * it while it is open; Back (or 90 s without a key press) closes it. "update" events refresh the content in place.
 * Presses go back to HA as quickbars.action events: cid "control_center", action_id e.g. "tile:light.x",
 * "notif_open:<id>", "notif_dismiss:<id>", "catchup_play:<id>". Camera tiles open the app's own camera PiP.
 */
class ControlCenterController(
    private val context: Context,
    private val windowManager: WindowManager,
    private val runOnMain: (() -> Unit) -> Unit,
    private val serviceScope: CoroutineScope,
    private val openCamera: (String, String?) -> Unit,
) {
    private var view: ComposeView? = null
    private val lifecycleOwner = ComposeViewLifecycleOwner()
    private var created = false
    private val spec = mutableStateOf<ControlCenterSpec?>(null)
    private val idle = Handler(Looper.getMainLooper())
    private val idleClose = Runnable { close() }

    fun onServiceConnected() { if (!created) { lifecycleOwner.create(); created = true } }
    fun onDestroy() { close(); try { lifecycleOwner.destroy() } catch (_: Throwable) {} }

    fun handle(data: JSONObject) = runOnMain {
        val s = try { ControlCenterSpec.parse(data) } catch (t: Throwable) { Log.e(TAG, "bad control center data", t); return@runOnMain }
        when (s.action) {
            "close" -> close()
            // Update only what was sent (e.g. just "tiles" after a press), keep the page the user is on
            "update" -> spec.value?.let { cur -> if (view != null) spec.value = cur.copy(
                subtitle = if (data.has("subtitle")) s.subtitle else cur.subtitle,
                now = if (data.has("now")) s.now else cur.now,
                notifications = if (data.has("notifications")) s.notifications else cur.notifications,
                cameras = if (data.has("cameras")) s.cameras else cur.cameras,
                cameraStatus = if (data.has("camera_status")) s.cameraStatus else cur.cameraStatus,
                tiles = if (data.has("tiles")) s.tiles else cur.tiles,
                catchup = if (data.has("catchup")) s.catchup else cur.catchup,
            ) }
            else -> { spec.value = s; if (view == null) show() else { close(); spec.value = s; show() } }
        }
        bumpIdle()
    }

    private fun bumpIdle() { idle.removeCallbacks(idleClose); idle.postDelayed(idleClose, 90_000) }

    @MainThread
    private fun show() {
        if (!android.provider.Settings.canDrawOverlays(context)) return
        val v = ComposeView(ContextThemeWrapper(context, R.style.Theme_HAQuickBars)).apply {
            setViewCompositionStrategy(androidx.compose.ui.platform.ViewCompositionStrategy.DisposeOnDetachedFromWindow)
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.START; windowAnimations = 0 }
        view = v
        try { windowManager.addView(v, lp) } catch (t: Throwable) { Log.e(TAG, "addView failed", t); view = null; return }
        v.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(av: View) {
                if (av !== view) { av.removeOnAttachStateChangeListener(this); return }
                lifecycleOwner.attachToView(v); lifecycleOwner.resume()
                v.setContent {
                    spec.value?.let { s ->
                        ControlCenterRoot(
                            spec = s,
                            onAction = { a -> bumpIdle(); send(a) },
                            onCamera = { e, rtsp -> openCamera(e, rtsp) },
                            onClose = { close() },
                            onKeepAlive = { bumpIdle() },
                        )
                    }
                }
                av.removeOnAttachStateChangeListener(this)
            }
            override fun onViewDetachedFromWindow(av: View) {}
        })
        v.setOnKeyListener { _, _, _ -> bumpIdle(); false }
        send("opened")
    }

    @MainThread
    fun close() {
        idle.removeCallbacks(idleClose)
        val v = view ?: return
        view = null
        try { lifecycleOwner.pause() } catch (_: Throwable) {}
        try { v.setContent { } } catch (_: Throwable) {}
        try { windowManager.removeViewImmediate(v) } catch (_: Throwable) {}
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
