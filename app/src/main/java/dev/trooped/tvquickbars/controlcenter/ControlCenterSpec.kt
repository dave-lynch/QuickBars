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
 *   rooms         [{id, name, floor, summary, devices:[tile], actions:[tile], temp:{now, low, low_at, high, high_at, hum, points:[°C]}}]
 *                 Home Assistant's rooms (areas) for the Rooms page; a room with temp shows its 24-hour temperature.
 *                 A room action whose id starts "page:" (e.g. "page:meals") opens that page of the app instead of running a script.
 *   meals         Kitchen > Dinners & shopping (sent on "meals:open" and after each change, as an update): {window, nights:[{date, day,
 *                 long, name, kind (dinner|takeaway|leftovers|empty), img, mins, needs:[..], count, ai, changed}], choices:[{id, name, img,
 *                 mins, count, needs}], sections:[{id, title, note, count, sub, items:[{id, name, sub, q, price, price_txt, on, unpriced}]}],
 *                 total:{amount, items, dinners, usual, unpriced, removed:[..], removed_n}, draft, note}. A TV-only draft in HA:
 *                 presses go back as meals:set:<date>:<choice id|takeaway|leftovers|clear|orig>, meals:item:<id>:<0|1>, meals:q:<id>:<n>, meals:reset.
 * Icons are names: a drawable "cc_<name>" (bundled MDI icons) or an existing drawable of that name.
 */
data class CcNow(val label: String, val title: String, val sub: String, val image: String?)
data class CcNotification(
    val id: String, val app: String, val icon: String, val color: Long, val whenText: String,
    val title: String, val body: String, val thumbs: List<String>, val open: String,
    /** hold OK: [id, label] the menu offers besides Dismiss (TV only): e.g. meals, ignore (clear it everywhere), remove (use-by) */
    val actions: List<Pair<String, String>> = emptyList(),
)
/** rtsp / rtspSub: Frigate's go2rtc restreams (full / lighter sub stream) for smooth video; blank = HA's MJPEG proxy */
data class CcCamera(val entity: String, val name: String, val image: String, val rtsp: String = "", val rtspSub: String = "")
/** bri: light brightness 0-100 (-1 when not a dimmable light), for the hold-OK brightness slider; on a TV or
 *  speaker (media_player) it is the volume 0-100, and holding OK opens the same slider as a volume control */
data class CcTile(val id: String, val title: String, val sub: String, val icon: String, val on: Boolean, val bri: Int = -1,
                  val members: List<CcTile> = emptyList(),   // a light group's own lights, shown when it is held
                  val n: Int = 1)   // how many single lights this part is (Spotlights = 2): weights the group's average
data class CcCatchupItem(
    val id: String, val title: String, val sub: String, val img: String?, val badge: String?,
    val prog: Float?, val days: String, val detail: String,
    val summary: String = "", val backdrop: String? = null,
    /** what OK sends (watch_ch:<n>, stream:<type>:<imdb>, catchup_play:<id>, tvfb:remind:<key>); blank = nothing */
    val ok: String = "", val key: String = "", val menu: List<String> = emptyList(),
    /** TV page filter: "mine" (followed / reminded / liked / watching), "pick" (recommended) or "" */
    val tag: String = "",
)
data class CcSection(val title: String, val items: List<CcCatchupItem>)
/** 24-hour temperature of a room (hourly averages in `points`, oldest first; low / high with their times) */
data class CcTemp(val now: String, val low: String, val lowAt: String, val high: String, val highAt: String, val hum: String,
                  val points: List<Float>)
data class CcRoom(val id: String, val name: String, val floor: String, val summary: String,
                  val devices: List<CcTile>, val actions: List<CcTile>, val temp: CcTemp?)

data class CcMealNight(val date: String, val day: String, val long: String, val name: String, val kind: String, val img: String?,
                       val mins: String, val needs: List<String>, val count: Int, val ai: Boolean, val changed: Boolean)
data class CcMealChoice(val id: String, val name: String, val img: String?, val mins: String, val count: Int, val needs: List<String>)
data class CcShopItem(val id: String, val name: String, val sub: String, val q: Int, val price: Double, val priceTxt: String,
                      val on: Boolean, val unpriced: Boolean)
data class CcShopSection(val id: String, val title: String, val note: String, val count: Int, val sub: String, val items: List<CcShopItem>)
data class CcMealTotal(val amount: String, val items: Int, val dinners: Int, val usual: String, val unpriced: Int,
                       val removed: List<String>, val removedN: Int)
// Search / add on Review shopping (voice or typed): what is already on the list, then products from past orders / the Dunnes
// catalogue (the phone's Add box ranking, worked out in tv_meals.py). looking = the live Dunnes lookup is still running.
data class CcFindOn(val id: String, val name: String, val where: String, val on: Boolean)
data class CcFindHit(val i: Int, val name: String, val priceTxt: String, val src: String, val added: Boolean)
data class CcFind(val q: String, val on: List<CcFindOn>, val hits: List<CcFindHit>, val looking: Boolean)
data class CcMeals(val window: String, val nights: List<CcMealNight>, val choices: List<CcMealChoice>,
                   val sections: List<CcShopSection>, val total: CcMealTotal, val draft: Boolean, val note: String,
                   val find: CcFind? = null)

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
    val rooms: List<CcRoom> = emptyList(),
    val meals: CcMeals? = null,
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
                    actions = it.optJSONArray("actions").objects().map { a -> a.optString("id") to a.optString("label") }.filter { a -> a.first.isNotBlank() },
                )
            },
            cameras = o.optJSONArray("cameras").objects().map {
                CcCamera(it.optString("entity"), it.optString("name"), it.optString("image"), it.optString("rtsp"), it.optString("rtsp_sub"))
            },
            cameraStatus = o.optString("camera_status"),
            tiles = o.optJSONArray("tiles").objects().map { tile(it) },
            catchup = o.optJSONArray("catchup").objects().map { s ->
                CcSection(s.optString("section"), s.optJSONArray("items").objects().map {
                    CcCatchupItem(
                        id = it.optString("id"), title = it.optString("title"), sub = it.optString("sub"), img = it.str("img"),
                        badge = it.str("badge"), prog = if (it.has("prog") && !it.isNull("prog")) it.optDouble("prog").toFloat() else null,
                        days = it.optString("days"), detail = it.optString("detail"),
                        summary = it.optString("summary"), backdrop = it.str("backdrop"),
                        ok = it.optString("ok"), key = it.optString("key"),
                        menu = it.optString("menu").split(",").map { m -> m.trim() }.filter { m -> m.isNotEmpty() },
                        tag = it.optString("tag"),
                    )
                })
            },
            rooms = o.optJSONArray("rooms").objects().map { room(it) },
            meals = o.optJSONObject("meals")?.takeIf { it.has("nights") }?.let { meals(it) },
        )

        private fun meals(m: JSONObject): CcMeals {
            val t = m.optJSONObject("total") ?: JSONObject()
            return CcMeals(
                window = m.optString("window"),
                nights = m.optJSONArray("nights").objects().map {
                    CcMealNight(it.optString("date"), it.optString("day"), it.optString("long"), it.optString("name"), it.optString("kind"),
                        it.str("img"), it.optString("mins"), it.optJSONArray("needs").strings(), it.optInt("count"),
                        it.optBoolean("ai"), it.optBoolean("changed"))
                },
                choices = m.optJSONArray("choices").objects().map {
                    CcMealChoice(it.optString("id"), it.optString("name"), it.str("img"), it.optString("mins"), it.optInt("count"),
                        it.optJSONArray("needs").strings())
                },
                sections = m.optJSONArray("sections").objects().map { s ->
                    CcShopSection(s.optString("id"), s.optString("title"), s.optString("note"), s.optInt("count"), s.optString("sub"),
                        s.optJSONArray("items").objects().map {
                            CcShopItem(it.optString("id"), it.optString("name"), it.optString("sub"), it.optDouble("q", 1.0).toInt().coerceAtLeast(1),
                                it.optDouble("price", 0.0), it.optString("price_txt"), it.optBoolean("on"), it.optBoolean("unpriced"))
                        })
                },
                total = CcMealTotal(t.optString("amount"), t.optInt("items"), t.optInt("dinners"), t.optString("usual"), t.optInt("unpriced"),
                    t.optJSONArray("removed").strings(), t.optInt("removed_n")),
                draft = m.optBoolean("draft"), note = m.optString("note"),
                find = m.optJSONObject("find")?.let { f ->
                    CcFind(f.optString("q"),
                        f.optJSONArray("on").objects().map { CcFindOn(it.optString("id"), it.optString("name"), it.optString("where"), it.optBoolean("on")) },
                        f.optJSONArray("hits").objects().map { CcFindHit(it.optInt("i"), it.optString("name"), it.optString("price_txt"), it.optString("src"), it.optBoolean("added")) },
                        f.optBoolean("looking"))
                },
            )
        }

        private fun room(r: JSONObject): CcRoom = CcRoom(
            r.optString("id"), r.optString("name"), r.optString("floor"), r.optString("summary"),
            r.optJSONArray("devices").objects().map { tile(it) }, r.optJSONArray("actions").objects().map { tile(it) },
            r.optJSONObject("temp")?.let { t ->
                val pts = t.optJSONArray("points"); val list = (0 until (pts?.length() ?: 0)).map { i -> pts!!.optDouble(i).toFloat() }
                CcTemp(t.optString("now"), t.optString("low"), t.optString("low_at"), t.optString("high"), t.optString("high_at"),
                    t.optString("hum"), list.filter { !it.isNaN() })
            })

        private fun tile(it: JSONObject): CcTile =
            CcTile(it.optString("id"), it.optString("title"), it.optString("sub"), it.optString("icon", "lightbulb"), it.optBoolean("on"),
                it.optInt("bri", -1), it.optJSONArray("members").objects().map { m -> tile(m) }, it.optInt("n", 1).coerceAtLeast(1))
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
