package dev.trooped.tvquickbars.controlcenter

import org.junit.Assert.assertEquals
import org.junit.Test

class ControlCenterHelpersTest {
    @Test fun spokenWordsBecomeASearch() {
        assertEquals("cheddar", cleanSpoken("Add cheddar please"))
        assertEquals("milk", cleanSpoken("please add some milk to the list"))
        assertEquals("fish & chips", cleanSpoken("Search for Fish & Chips!"))
        assertEquals(40, cleanSpoken("a".repeat(60)).length)
    }

    @Test fun colourPresets() {
        // a light from 2200 to 6500 K: Warm starts at a normal warm-white bulb, Cool is the coolest it goes
        assertEquals(4, CT_PRESETS.size)
        assertEquals(2700, ctPreset(0, 2200, 6500))
        assertEquals(6500, ctPreset(3, 2200, 6500))
        assertEquals("Warm", ctName(2600, 2200, 6500))
        assertEquals("Cool", ctName(6400, 2200, 6500))
        // a light that can't reach 2700 K keeps its own range
        assertEquals(2000, ctPreset(0, 2000, 2600))
    }

    @Test fun sportStripGroups() {
        val item = CcCatchupItem("1", "t", "", null, null, null, "", "")
        val rows = listOf(CcSection("Live now", listOf(item), listOf("sport"), "Top"), CcSection("F1 races", listOf(item), listOf("sport"), "Formula 1"),
            CcSection("Drivers' championship", listOf(item), listOf("sport"), "Formula 1"), CcSection("Soccer", listOf(item), listOf("sport"), "Soccer"))
        assertEquals(listOf("Top", "Formula 1", "Soccer"), sportGroups(rows))
        assertEquals(listOf("F1 races", "Drivers' championship"), sportRows(rows, "Formula 1").map { it.title })
        assertEquals(listOf("Live now"), sportRows(rows, "gone").map { it.title })          // a sport no longer sent: the first
        val old = listOf(CcSection("Soccer", listOf(item), listOf("sport")))                // older HA without groups: no strip
        assertEquals(old, sportRows(old, "Soccer"))
    }

    @Test fun tvFilterByViews() {
        val item = CcCatchupItem("1", "t", "", null, null, null, "", "")
        val rows = listOf(CcSection("Series on now", listOf(item), listOf("series", "all")), CcSection("Sport", listOf(item), listOf("sport")))
        assertEquals(listOf("Series on now"), tvRows(rows, "series").map { it.title })
        assertEquals(listOf("Sport"), tvRows(rows, "sport").map { it.title })
    }

    @Test fun tvFilterByTagsForOlderData() {
        fun it(id: String, tag: String) = CcCatchupItem(id, id, "", null, null, null, "", "", tag = tag)
        val rows = listOf(CcSection("Tonight", listOf(it("a", "mine"), it("b", "pick"), it("c", ""), it("d", "sport"))))
        assertEquals(listOf("a"), tvRows(rows, "mine").single().items.map { it.id })
        assertEquals(listOf("a", "b"), tvRows(rows, "foryou").single().items.map { it.id })
        assertEquals(listOf("a", "b", "c"), tvRows(rows, "all").single().items.map { it.id })
        assertEquals(listOf("d"), tvRows(rows, "sport").single().items.map { it.id })
    }
}
