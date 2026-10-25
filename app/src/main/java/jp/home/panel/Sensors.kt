package jp.home.panel

/**
 * 温度計（SwitchBot 温湿度計など、Home Assistant に取り込んだもの）。
 * Home Assistant では温度と湿度が別々のセンサーになっているので、同じ機器のものを1つにまとめる。
 */
data class Thermo(
    val key: String,            // まとめる単位（機器の ID、無ければ名前）
    val name: String,
    val temperature: Double?,
    val humidity: Double?,
    val battery: Double?,
    val tempEntity: String?,
    val humEntity: String?,
    val unavailable: Boolean,
    val original: String = name, // 名前を変える前の名前
)

private fun Entity.deviceClass() = attributes.optString("device_class")
private fun Entity.unit() = attributes.optString("unit_of_measurement")

fun isTemperatureSensor(e: Entity) = e.domain == "sensor" && (e.deviceClass() == "temperature" || e.unit() in setOf("°C", "℃", "°F"))
fun isHumiditySensor(e: Entity) = e.domain == "sensor" && (e.deviceClass() == "humidity" || (e.unit() == "%" && e.name.contains(Regex("湿度|[Hh]umidity"))))
fun isBatterySensor(e: Entity) = e.domain == "sensor" && e.deviceClass() == "battery"

/** 「寝室 温度」「Bedroom Temperature」→「寝室」 */
fun baseName(name: String): String = name
    .replace(Regex("[\\s_]*(温度|湿度|気温|電池|バッテリー|Temperature|Humidity|Battery)$", RegexOption.IGNORE_CASE), "")
    .trim()
    .ifBlank { name }

/**
 * 温度計の一覧を作る。
 * entityDevice：entity_id → 機器の ID（Home Assistant の機器の登録から）。無ければ名前でまとめる。
 */
fun buildThermos(states: Collection<Entity>, entityDevice: Map<String, String>, deviceNames: Map<String, String>): List<Thermo> {
    val sensors = states.filter { isTemperatureSensor(it) || isHumiditySensor(it) || isBatterySensor(it) }
    fun keyOf(e: Entity) = entityDevice[e.entityId]?.let { "dev:$it" } ?: ("name:" + baseName(e.name))
    val byKey = sensors.groupBy(::keyOf)
    return byKey.mapNotNull { (key, list) ->
        val t = list.firstOrNull(::isTemperatureSensor)
        val h = list.firstOrNull(::isHumiditySensor)
        if (t == null && h == null) return@mapNotNull null // 電池だけの機器は出さない
        val b = list.firstOrNull(::isBatterySensor)
        fun num(e: Entity?) = e?.state?.toDoubleOrNull()
        var temp = num(t)
        if (temp != null && t?.unit() == "°F") temp = (temp - 32) * 5 / 9
        val devName = key.removePrefix("dev:").let { deviceNames[it] }
        Thermo(
            key = key,
            name = devName?.takeIf { it.isNotBlank() } ?: baseName((t ?: h)!!.name),
            temperature = temp,
            humidity = num(h),
            battery = num(b),
            tempEntity = t?.entityId,
            humEntity = h?.entityId,
            unavailable = (t?.unavailable ?: true) && (h?.unavailable ?: true),
        )
    }.sortedBy { it.name }
}

/** 暑さ・寒さの目安の色（0 寒い〜4 暑い） */
fun tempLevel(t: Double?): Int = when {
    t == null -> 2
    t < 16 -> 0
    t < 20 -> 1
    t < 26 -> 2
    t < 29 -> 3
    else -> 4
}
