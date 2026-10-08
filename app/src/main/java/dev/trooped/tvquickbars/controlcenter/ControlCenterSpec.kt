package dev.trooped.tvquickbars.controlcenter

import org.json.JSONArray
import org.json.JSONObject

/**
 * Control Center (DL build): a tvOS-style panel built from data Home Assistant sends in a `quickbars.control_center`
 * event. HA owns the content (now playing, notifications from its event stack, cameras, tiles, catch-up list); the app
 * only draws it and reports what was pressed back as `quickbars.action` events (cid "control_center").
 *
 * Event data:
 *   action        "open" (default) | "update" (refresh content if open) | "close"
 *   page          "home" (default) | "notifications" | "catchup"
 *   subtitle      line under the clock, e.g. "Wednesday 8 October · 9° clear"
 *   now           {label, title, sub, image}
 *   notifications [{id, app, icon, color, when, title, body, thumbs:[url], open}]   open: what OK does
 *                 ("page:catchup", "camera:camera.gate", "action" = send notif_open to HA)
 *   cameras       [{entity, name, image}], camera_status
 *   tiles         [{id, title, sub, icon, on}]
 *   catchup       [{section, items:[{id, title, sub, img, badge, prog, days, detail}]}]
 * Icons are names: a drawable "cc_<name>" (bundled MDI icons) or an existing drawable of that name.
 */
data class CcNow(val label: String, val title: String, val sub: String, val image: String?)
data class CcNotification(
    val id: String, val app: String, val icon: String, val color: Long, val whenText: String,
    val title: String, val body: String, val thumbs: List<String>, val open: String,
)
data class CcCamera(val entity: String, val name: String, val image: String)
/** bri: light brightness 0-100 (-1 when not a dimmable light), for the hold-OK brightness slider */
data class CcTile(val id: String, val title: String, val sub: String, val icon: String, val on: Boolean, val bri: Int = -1)
data class CcCatchupItem(
    val id: String, val title: String, val sub: String, val img: String?, val badge: String?,
    val prog: Float?, val days: String, val detail: String,
    val summary: String = "", val backdrop: String? = null,
)
data class CcSection(val title: String, val items: List<CcCatchupItem>)

data class ControlCenterSpec(
    val action: String,
    val page: String,
    val subtitle: String,
    val now: CcNow?,
    val notifications: List<CcNotification>,
    val cameras: List<CcCamera>,
    val cameraStatus: String,
    val tiles: List<CcTile>,
    val catchup: List<CcSection>,
) {
    companion object {
        fun parse(o: JSONObject): ControlCenterSpec = ControlCenterSpec(
            action = o.optString("action", "open").ifBlank { "open" },
            page = o.optString("page", "home").ifBlank { "home" },
            subtitle = o.optString("subtitle"),
            now = o.optJSONObject("now")?.let {
                CcNow(it.optString("label"), it.optString("title"), it.optString("sub"), it.str("image"))
            },
            notifications = o.optJSONArray("notifications").objects().map {
                CcNotification(
                    id = it.optString("id"), app = it.optString("app"), icon = it.optString("icon", "bell_outline"),
                    color = parseColor(it.optString("color"), 0xFFF59E0B), whenText = it.optString("when"),
                    title = it.optString("title"), body = it.optString("body"),
                    thumbs = it.optJSONArray("thumbs").strings(), open = it.optString("open", "action"),
                )
            },
            cameras = o.optJSONArray("cameras").objects().map {
                CcCamera(it.optString("entity"), it.optString("name"), it.optString("image"))
            },
            cameraStatus = o.optString("camera_status"),
            tiles = o.optJSONArray("tiles").objects().map {
                CcTile(it.optString("id"), it.optString("title"), it.optString("sub"), it.optString("icon", "lightbulb"), it.optBoolean("on"), it.optInt("bri", -1))
            },
            catchup = o.optJSONArray("catchup").objects().map { s ->
                CcSection(s.optString("section"), s.optJSONArray("items").objects().map {
                    CcCatchupItem(
                        id = it.optString("id"), title = it.optString("title"), sub = it.optString("sub"), img = it.str("img"),
                        badge = it.str("badge"), prog = if (it.has("prog") && !it.isNull("prog")) it.optDouble("prog").toFloat() else null,
                        days = it.optString("days"), detail = it.optString("detail"),
                        summary = it.optString("summary"), backdrop = it.str("backdrop"),
                    )
                })
            },
        )

        private fun JSONObject.str(k: String): String? = optString(k, "").takeIf { it.isNotBlank() && it != "null" }
        private fun JSONArray?.objects(): List<JSONObject> =
            if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }
        private fun JSONArray?.strings(): List<String> =
            if (this == null) emptyList() else (0 until length()).mapNotNull { optString(it).takeIf { s -> s.isNotBlank() } }
        private fun parseColor(s: String, def: Long): Long = try {
            val h = s.removePrefix("#"); if (h.length == 6) 0xFF000000 or h.toLong(16) else def
        } catch (_: Throwable) { def }
    }
}
