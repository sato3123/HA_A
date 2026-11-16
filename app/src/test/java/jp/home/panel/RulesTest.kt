package jp.home.panel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RulesTest {
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
        assertEquals(r.actions, back!!.actions)
    }

    @Test
    fun linkedEchonetFallbackRoundTrips() {
        val r = rule(Act("climate.bed", "climate.set_temperature", mapOf("hvac_mode" to "cool", "temperature" to 26.5, "fan_mode" to "auto")))
        val c = r.toConfig(elFor = { if (it == "climate.bed") "climate.el_192_168_1_12_013001" else null })
        val s = c.toString()
        assertTrue(s.contains(EL_FALLBACK))
        assertTrue(s.contains(EL_ENSURE))
        val back = parseRule("hp_auto_1", c)
        assertEquals(r.actions, back!!.actions)
    }

    @Test
    fun turnOffIsNotWrapped() {
        val r = rule(Act("climate.living", "climate.turn_off"))
        val c = r.toConfig()
        assertTrue(!c.toString().contains(EL_ENSURE))
        assertEquals(r.actions, parseRule("hp_auto_1", c)!!.actions)
    }
}
