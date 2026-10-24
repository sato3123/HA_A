package jp.home.panel

import org.json.JSONArray
import org.json.JSONObject

/**
 * Home Assistant の「エネルギー」ダッシュボードの設定と統計から、電気の収支を計算する。
 * 使う API（通常のログインで読める）：
 *  - energy/get_prefs                     … どのセンサーが買電・売電・太陽光・蓄電池か
 *  - recorder/get_statistics_metadata     … 単位（Wh / kWh / MWh）
 *  - recorder/statistics_during_period    … 期間ごとの増えた量（change）
 */
data class EnergySources(
    val gridIn: List<String>,      // 買電（電力会社から）
    val gridOut: List<String>,     // 売電（電力会社へ）
    val solar: List<String>,       // 太陽光の発電
    val batteryOut: List<String>,  // 蓄電池から使った（放電）
    val batteryIn: List<String>,   // 蓄電池へためた（充電）
    val cost: List<String>,        // 買電の電気代
    val devices: List<String>,     // 機器ごとの使用量
) {
    val all: List<String> get() = (gridIn + gridOut + solar + batteryOut + batteryIn + cost + devices).distinct()
    val isEmpty: Boolean get() = gridIn.isEmpty() && solar.isEmpty() && batteryOut.isEmpty()
}

private fun JSONArray?.objects(): List<JSONObject> =
    if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

private fun JSONObject.str(key: String): String? = optString(key).takeIf { it.isNotBlank() && it != "null" }

fun parseEnergyPrefs(r: JSONObject): EnergySources {
    val gIn = mutableListOf<String>(); val gOut = mutableListOf<String>(); val solar = mutableListOf<String>()
    val bOut = mutableListOf<String>(); val bIn = mutableListOf<String>(); val cost = mutableListOf<String>()
    r.optJSONArray("energy_sources").objects().forEach { s ->
        when (s.optString("type")) {
            "grid" -> {
                s.optJSONArray("flow_from").objects().forEach { f ->
                    f.str("stat_energy_from")?.let(gIn::add); f.str("stat_cost")?.let(cost::add)
                }
                s.optJSONArray("flow_to").objects().forEach { f -> f.str("stat_energy_to")?.let(gOut::add) }
                // 新しい形式（flow_from / flow_to が無い場合）
                s.str("stat_energy_from")?.let(gIn::add)
                s.str("stat_energy_to")?.let(gOut::add)
                s.str("stat_cost")?.let(cost::add)
            }
            "solar" -> s.str("stat_energy_from")?.let(solar::add)
            "battery" -> {
                s.str("stat_energy_from")?.let(bOut::add)
                s.str("stat_energy_to")?.let(bIn::add)
            }
        }
    }
    val devices = r.optJSONArray("device_consumption").objects().mapNotNull { it.str("stat_consumption") }
    return EnergySources(gIn.distinct(), gOut.distinct(), solar.distinct(), bOut.distinct(), bIn.distinct(), cost.distinct(), devices.distinct())
}

/** 単位を kWh にそろえるための倍率 */
fun unitFactor(unit: String?): Double = when (unit?.lowercase()) {
    "wh" -> 0.001
    "mwh" -> 1000.0
    "gj" -> 277.778
    else -> 1.0
}

data class EnergyTotals(
    val gridIn: Double,
    val gridOut: Double,
    val solar: Double,
    val batteryOut: Double,
    val batteryIn: Double,
    val cost: Double?,
) {
    /** 家で使った電気 = 買電 + 発電 + 放電 − 売電 − 充電 */
    val used: Double get() = (gridIn + solar + batteryOut - gridOut - batteryIn).coerceAtLeast(0.0)
    /** 自給率（買電に頼らなかった割合） */
    val selfRate: Double? get() = if (used > 0.01) ((1 - gridIn / used) * 100).coerceIn(0.0, 100.0) else null
    /** 収支（売電 − 買電）。プラスなら売った方が多い */
    val net: Double get() = gridOut - gridIn
}

data class EnergyReport(
    val today: EnergyTotals,
    val yesterday: EnergyTotals,
    val month: EnergyTotals,
    val hourlyUsed: List<Double>,          // 今日の0時から1時間ごとの使用量
    val topDevices: List<Pair<String, Double>>,
    val hasSolar: Boolean,
    val hasBattery: Boolean,
    val hasExport: Boolean,
)

/** statistics_during_period の結果から、statistic_id ごとの change の合計 */
fun sumChanges(result: JSONObject, ids: List<String>, factor: (String) -> Double): Double =
    ids.sumOf { id -> result.optJSONArray(id).objects().sumOf { it.optDouble("change", 0.0).takeUnless { v -> v.isNaN() } ?: 0.0 } * factor(id) }

fun totalsOf(result: JSONObject, src: EnergySources, factor: (String) -> Double, costFactor: Boolean = true) = EnergyTotals(
    gridIn = sumChanges(result, src.gridIn, factor),
    gridOut = sumChanges(result, src.gridOut, factor),
    solar = sumChanges(result, src.solar, factor),
    batteryOut = sumChanges(result, src.batteryOut, factor),
    batteryIn = sumChanges(result, src.batteryIn, factor),
    cost = if (src.cost.isEmpty() || !costFactor) null else sumChanges(result, src.cost) { 1.0 },
)

/** 1時間ごとの使用量（今日の0時から） */
fun hourlyUsed(result: JSONObject, src: EnergySources, factor: (String) -> Double, startMs: Long, hours: Int): List<Double> {
    val buckets = DoubleArray(hours)
    fun add(ids: List<String>, sign: Double) {
        ids.forEach { id ->
            result.optJSONArray(id).objects().forEach { row ->
                val t = when (val st = row.opt("start")) {
                    is Number -> st.toLong()
                    is String -> runCatching { java.time.OffsetDateTime.parse(st).toInstant().toEpochMilli() }.getOrDefault(0L)
                    else -> 0L
                }
                val h = ((t - startMs) / 3_600_000L).toInt()
                if (h in 0 until hours) buckets[h] += sign * (row.optDouble("change", 0.0).takeUnless { it.isNaN() } ?: 0.0) * factor(id)
            }
        }
    }
    add(src.gridIn, 1.0); add(src.solar, 1.0); add(src.batteryOut, 1.0)
    add(src.gridOut, -1.0); add(src.batteryIn, -1.0)
    return buckets.map { it.coerceAtLeast(0.0) }
}

fun kwh(v: Double): String = when {
    v >= 100 -> String.format("%.0f", v)
    v >= 10 -> String.format("%.1f", v)
    else -> String.format("%.2f", v)
} + " kWh"
