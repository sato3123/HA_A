package jp.home.panel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RulesTest {
    // 数は型（Double・BigDecimal など）を問わず、値で比べる
    private fun norm(l: List<Act>) = l.map { a -> Triple(a.entityId, a.service, a.data.mapValues { (_, v) -> if (v is Number) v.toDouble() else v.toString() }) }

    private fun rule(vararg acts: Act) = Rule(
        id = "hp_auto_1", name = "朝の暖房", schedule = false, trigger = Trig.Time("06:00"),
        weekdays = emptySet(), after = null, before = null, actions = acts.toList(), conds = emptyList(),
    )

    @Test
    fun climateOnIsSentAsModeThenTemperatureAndRetriedIfStillOff() {
        val r = rule(Act("climate.living", "climate.set_temperature", mapOf("hvac_mode" to "heat", "temperature" to 22.5)))
        val c = r.toConfig()
        val s = c.toString()
        assertTrue("つかなければもう一度 が入る", s.contains(EL_ENSURE))
        val ensure = c.getJSONArray("actions").getJSONObject(0).getJSONArray("sequence")
        assertEquals("climate.set_hvac_mode", ensure.getJSONObject(0).getString("action"))
        assertEquals("heat", ensure.getJSONObject(0).getJSONObject("data").getString("hvac_mode"))
        assertEquals("climate.set_temperature", ensure.getJSONObject(1).getString("action"))
        assertTrue(ensure.getJSONObject(0).getBoolean("continue_on_error"))
        assertTrue(ensure.getJSONObject(2).has("wait_template"))
        assertTrue(ensure.getJSONObject(3).has("if"))
        // 読み直すと、もとの1つの操作に戻る
        val back = parseRule("hp_auto_1", c)
        assertNotNull(back)
        assertEquals(norm(r.actions), norm(back!!.actions))
    }

    @Test
    fun linkedEchonetFallbackRoundTrips() {
        val r = rule(Act("climate.bed", "climate.set_temperature", mapOf("hvac_mode" to "cool", "temperature" to 26.5, "fan_mode" to "auto")))
        val c = r.toConfig(elFor = { if (it == "climate.bed") "climate.el_192_168_1_12_013001" else null })
        val s = c.toString()
        assertTrue(s.contains(EL_FALLBACK))
        assertTrue(s.contains(EL_ENSURE))
        val back = parseRule("hp_auto_1", c)
        assertEquals(norm(r.actions), norm(back!!.actions))
    }

    @Test
    fun turnOffIsNotWrapped() {
        val r = rule(Act("climate.living", "climate.turn_off"))
        val c = r.toConfig()
        assertTrue(!c.toString().contains(EL_ENSURE))
        assertEquals(norm(r.actions), norm(parseRule("hp_auto_1", c)!!.actions))
    }
}

class JpHolidayTest {
    @Test
    fun holidays2026() {
        val h = JpHoliday.of(2026).map { it.toString() }.toSet()
        listOf("2026-01-01", "2026-01-12", "2026-03-20", "2026-05-06", "2026-09-21", "2026-09-22", "2026-09-23", "2026-11-23").forEach {
            assertTrue(it, it in h)
        }
        assertTrue("2026-11-24" !in h)
    }

    @Test
    fun skipHolidaysRoundTrips() {
        val r = Rule(id = "hp_sched_1", name = "朝", schedule = true, trigger = Trig.Time("06:00"), weekdays = setOf("mon", "tue", "wed", "thu", "fri"),
            after = null, before = null, actions = listOf(Act("switch.a", "switch.turn_on")), skipHolidays = true)
        val c = r.toConfig()
        assertTrue(c.toString().contains(HOLIDAY_ALIAS))
        assertTrue(c.toString().contains("2026-11-23") || c.toString().contains("-11-23"))
        assertTrue(parseRule("hp_sched_1", c)!!.skipHolidays)
    }
}
