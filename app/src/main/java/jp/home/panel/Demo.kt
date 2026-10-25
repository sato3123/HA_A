package jp.home.panel

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * 画面確認用の見本データ（開発用のビルドの「デモ表示」だけで使う。通常版では使わない）。
 * 自動テストで、Home Assistant が無くてもホーム画面のレイアウトを確かめるため。
 */
object Demo {
    private fun e(id: String, state: String, vararg attrs: Pair<String, Any>): Entity {
        val a = JSONObject()
        attrs.forEach { (k, v) -> a.put(k, v) }
        return Entity(id, state, a, lastChanged = java.time.OffsetDateTime.now().minusMinutes(12).toString(),
            lastUpdated = java.time.OffsetDateTime.now().minusMinutes(2).toString())
    }

    fun states(): Map<String, Entity> = listOf(
        e("climate.living", "cool", "friendly_name" to "リビングのエアコン", "hvac_modes" to JSONArray(listOf("off", "cool", "heat", "dry", "fan_only", "auto")),
            "temperature" to 26, "current_temperature" to 27.5, "current_humidity" to 58, "min_temp" to 16, "max_temp" to 31, "target_temp_step" to 0.5,
            "fan_modes" to JSONArray(listOf("auto", "low", "medium", "high")), "fan_mode" to "auto"),
        e("climate.bedroom", "off", "friendly_name" to "寝室のエアコン", "hvac_modes" to JSONArray(listOf("off", "cool", "heat")),
            "temperature" to 24, "current_temperature" to 25.1, "min_temp" to 16, "max_temp" to 31),
        e("light.living", "on", "friendly_name" to "リビングの照明", "brightness" to 180,
            "supported_color_modes" to JSONArray(listOf("color_temp", "hs")), "color_temp_kelvin" to 4000,
            "min_color_temp_kelvin" to 2700, "max_color_temp_kelvin" to 6500),
        e("light.kitchen", "off", "friendly_name" to "キッチンの照明", "supported_color_modes" to JSONArray(listOf("brightness"))),
        e("light.hall", "off", "friendly_name" to "廊下", "supported_color_modes" to JSONArray(listOf("onoff"))),
        e("fan.bedroom", "on", "friendly_name" to "寝室の扇風機", "percentage" to 50, "percentage_step" to 25),
        e("cover.curtain", "open", "friendly_name" to "リビングのカーテン", "current_position" to 100, "supported_features" to 15),
        e("switch.tv", "off", "friendly_name" to "テレビ"),
        e("switch.washer", "on", "friendly_name" to "洗濯機"),
        e("scene.night", "2026-10-24T10:00:00", "friendly_name" to "おやすみ"),
        e("script.goout", "off", "friendly_name" to "おでかけ"),
        e("automation.morning", "on", "friendly_name" to "朝のエアコン", "id" to "hp_sched_1"),
        e("weather.home", "partlycloudy", "friendly_name" to "自宅", "temperature" to 18.4, "humidity" to 62, "temperature_unit" to "°C"),
        // SwitchBot 温湿度計（温度と湿度が別のセンサー）
        e("sensor.meter_living_temperature", "24.3", "friendly_name" to "リビング 温度", "device_class" to "temperature", "unit_of_measurement" to "°C"),
        e("sensor.meter_living_humidity", "52", "friendly_name" to "リビング 湿度", "device_class" to "humidity", "unit_of_measurement" to "%"),
        e("sensor.meter_kids_temperature", "17.8", "friendly_name" to "子ども部屋 温度", "device_class" to "temperature", "unit_of_measurement" to "°C"),
        e("sensor.meter_kids_humidity", "61", "friendly_name" to "子ども部屋 湿度", "device_class" to "humidity", "unit_of_measurement" to "%"),
        e("sensor.meter_kids_battery", "12", "friendly_name" to "子ども部屋 電池", "device_class" to "battery", "unit_of_measurement" to "%"),
        e("sensor.outdoor_temperature", "29.4", "friendly_name" to "ベランダ 温度", "device_class" to "temperature", "unit_of_measurement" to "°C"),
        e("calendar.timetree_family", "off", "friendly_name" to "TimeTree 家族"),
    ).associateBy { it.entityId }

    val areas = listOf(Room("area:living", "リビング", true), Room("area:bedroom", "寝室", true), Room("area:kitchen", "キッチン", true))
    val entityAreas = mapOf(
        "climate.living" to "living", "light.living" to "living", "cover.curtain" to "living", "switch.tv" to "living",
        "climate.bedroom" to "bedroom", "fan.bedroom" to "bedroom", "light.kitchen" to "kitchen",
    )

    fun forecast(): List<JSONObject> = (0..4).map { n ->
        val d = LocalDate.now().plusDays(n.toLong()).atTime(12, 0).atZone(ZoneId.systemDefault()).toOffsetDateTime()
        JSONObject().put("datetime", d.toString())
            .put("condition", listOf("partlycloudy", "rainy", "sunny", "cloudy", "sunny")[n])
            .put("temperature", listOf(22, 19, 23, 21, 24)[n]).put("templow", listOf(14, 13, 12, 14, 15)[n])
            .put("precipitation_probability", listOf(20, 80, 0, 30, 10)[n])
    }

    fun events(): List<CalEvent> {
        val t = LocalDate.now()
        fun ev(day: Long, h: Int, m: Int, title: String, allDay: Boolean = false) = CalEvent(
            "calendar.timetree_family", "TimeTree 家族", title,
            LocalDateTime.of(t.plusDays(day), java.time.LocalTime.of(h, m)),
            LocalDateTime.of(t.plusDays(day), java.time.LocalTime.of(h, m)).plusHours(1), allDay, "",
        )
        return listOf(ev(0, 0, 0, "燃えるゴミ", true), ev(0, 15, 30, "歯医者"), ev(0, 19, 0, "塾のお迎え"),
            ev(1, 10, 0, "サッカー"), ev(3, 9, 0, "町内会"))
    }

    fun energy(): EnergyReport {
        fun t(gi: Double, go: Double, s: Double) = EnergyTotals(gi, go, s, 0.0, 0.0, gi * 31)
        return EnergyReport(
            today = t(6.4, 2.1, 9.8), yesterday = t(9.2, 3.4, 12.6), month = t(182.0, 64.0, 251.0),
            hourlyUsed = List(24) { h -> if (h <= LocalDateTime.now().hour) 0.3 + (h % 7) * 0.12 else 0.0 },
            topDevices = emptyList(), hasSolar = true, hasBattery = false, hasExport = true,
        )
    }
}
