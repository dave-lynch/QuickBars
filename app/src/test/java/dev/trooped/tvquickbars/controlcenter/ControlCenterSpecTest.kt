package dev.trooped.tvquickbars.controlcenter

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The HA -> app contract: what a quickbars.control_center event turns into. */
class ControlCenterSpecTest {
    private fun parse(json: String) = ControlCenterSpec.parse(JSONObject(json))

    @Test fun defaults() {
        val s = parse("{}")
        assertEquals("open", s.action)
        assertEquals("home", s.page)
        assertNull(s.now)
        assertTrue(s.notifications.isEmpty() && s.tiles.isEmpty() && s.catchup.isEmpty() && s.rooms.isEmpty())
        assertNull(s.meals)
        assertEquals("home", parse("""{"action": "", "page": ""}""").page)
    }

    @Test fun nowWithoutTitleIsNoCard() {
        assertNull(parse("""{"now": {"label": "Live", "title": ""}}""").now)
        val n = parse("""{"now": {"label": "Live", "title": "News", "ok": "stream_next:x",
            "streams": [{"id": "a", "title": "Stream 1", "on": true}, {"id": "", "title": "no id"}]}}""").now!!
        assertEquals("stream_next:x", n.ok)
        assertEquals(listOf("a"), n.streams.map { it.id })   // a pick without an id is dropped
        assertTrue(n.streams[0].on)
    }

    @Test fun notifications() {
        val n = parse("""{"notifications": [{"id": "u1", "title": "Bins", "color": "#112233", "thumbs": ["/a.jpg", ""],
            "actions": [{"id": "ignore", "label": "Ignore"}, {"id": "", "label": "x"}]}]}""").notifications.single()
        assertEquals(0xFF112233, n.color)
        assertEquals("action", n.open)
        assertEquals("bell_outline", n.icon)
        assertEquals(listOf("/a.jpg"), n.thumbs)
        assertEquals(listOf("ignore" to "Ignore"), n.actions)
        assertEquals(0xFFF59E0B, parse("""{"notifications": [{"id": "u", "color": "red"}]}""").notifications.single().color)
    }

    @Test fun tilesAndGroups() {
        val t = parse("""{"tiles": [{"id": "light.g", "title": "Lights", "on": true, "bri": 40, "n": 0, "kmin": 2200, "kmax": 6500,
            "members": [{"id": "light.a", "title": "A", "n": 2}]}]}""").tiles.single()
        assertEquals(40, t.bri)
        assertEquals(1, t.n)   // at least 1
        assertEquals("lightbulb", t.icon)
        assertEquals(2, t.members.single().n)
        assertEquals(-1, t.members.single().bri)
    }

    @Test fun catchupRows() {
        val sec = parse("""{"catchup": [{"section": "On now", "views": ["series", "all"], "items": [
            {"id": "1", "title": "Show", "img": "null", "prog": 0.5, "menu": "remind, like,,", "ok": "watch_ch:101"},
            {"id": "2", "title": "Film", "prog": null}]}]}""").catchup.single()
        assertEquals(listOf("series", "all"), sec.views)
        val (a, b) = sec.items
        assertNull(a.img)   // "null" text counts as no picture
        assertEquals(0.5f, a.prog!!, 0.001f)
        assertEquals(listOf("remind", "like"), a.menu)
        assertNull(b.prog)
    }

    @Test fun roomTemperature() {
        val r = parse("""{"rooms": [{"id": "k", "name": "Kitchen", "devices": [], "actions": [],
            "temp": {"now": "20.5", "points": [19.0, 20.0, "x"]}}]}""").rooms.single()
        assertEquals(listOf(19f, 20f), r.temp!!.points)   // a value that isn't a number is left out
    }

    @Test fun mealsNeedNights() {
        assertNull(parse("""{"meals": {"window": "x"}}""").meals)
        val m = parse("""{"meals": {"nights": [], "sections": [{"id": "week", "items": [{"id": "a", "q": 0}]}], "total": {}}}""").meals!!
        assertEquals(1, m.sections.single().items.single().q)   // quantity at least 1
        assertFalse(m.draft)
        assertNull(m.find)
    }
}
