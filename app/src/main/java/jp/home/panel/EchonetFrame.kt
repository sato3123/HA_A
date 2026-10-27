package jp.home.panel

/*
 * ECHONET Lite の電文（Android に依存しない部分。単体テストあり）
 *
 *  EHD1(0x10) EHD2(0x81) TID(2) SEOJ(3) DEOJ(3) ESV(1) OPC(1) [EPC(1) PDC(1) EDT(PDC)]×OPC
 */

const val EL_PORT = 3610
const val EL_MULTICAST = "224.0.23.0"

/** このアプリ自身（コントローラー） */
val EOJ_CONTROLLER = Eoj(0x05, 0xFF, 0x01)
/** 機器の「ノードプロファイル」（その機器に入っている機能の一覧を持つ） */
val EOJ_NODE_PROFILE = Eoj(0x0E, 0xF0, 0x01)

object Esv {
    const val SETI = 0x60      // 設定（返事なし）
    const val SETC = 0x61      // 設定（返事あり）
    const val GET = 0x62       // 読み取り
    const val INF_REQ = 0x63   // 通知のお願い
    const val SET_RES = 0x71
    const val GET_RES = 0x72
    const val INF = 0x73       // 機器からの通知
    const val INFC = 0x74
    const val SETI_SNA = 0x50
    const val SETC_SNA = 0x51
    const val GET_SNA = 0x52
}

/** ECHONET オブジェクト（クラスグループ・クラス・インスタンス） */
data class Eoj(val group: Int, val cls: Int, val instance: Int) {
    /** 0x0130 のような機器の種類 */
    val classCode: Int get() = (group shl 8) or cls
    fun hex(): String = String.format("%02X%02X%02X", group, cls, instance)

    companion object {
        fun parse(hex: String): Eoj? = if (hex.length == 6) runCatching {
            Eoj(hex.substring(0, 2).toInt(16), hex.substring(2, 4).toInt(16), hex.substring(4, 6).toInt(16))
        }.getOrNull() else null
    }
}

data class Prop(val epc: Int, val edt: ByteArray = ByteArray(0)) {
    override fun equals(other: Any?) = other is Prop && other.epc == epc && other.edt.contentEquals(edt)
    override fun hashCode() = epc * 31 + edt.contentHashCode()
}

data class ElFrame(val tid: Int, val seoj: Eoj, val deoj: Eoj, val esv: Int, val props: List<Prop>) {
    fun encode(): ByteArray {
        val out = ArrayList<Byte>()
        fun b(v: Int) = out.add((v and 0xFF).toByte())
        b(0x10); b(0x81)
        b(tid shr 8); b(tid)
        b(seoj.group); b(seoj.cls); b(seoj.instance)
        b(deoj.group); b(deoj.cls); b(deoj.instance)
        b(esv)
        b(props.size)
        props.forEach { p ->
            b(p.epc)
            b(p.edt.size)
            p.edt.forEach { out.add(it) }
        }
        return out.toByteArray()
    }

    companion object {
        fun decode(data: ByteArray, length: Int = data.size): ElFrame? {
            if (length < 12) return null
            fun u(i: Int) = data[i].toInt() and 0xFF
            if (u(0) != 0x10 || u(1) != 0x81) return null
            val tid = (u(2) shl 8) or u(3)
            val seoj = Eoj(u(4), u(5), u(6))
            val deoj = Eoj(u(7), u(8), u(9))
            val esv = u(10)
            val opc = u(11)
            var i = 12
            val props = ArrayList<Prop>()
            // SetGet 系の応答（0x7E 等）は扱わない。読める分だけ読む
            repeat(opc) {
                if (i + 2 > length) return ElFrame(tid, seoj, deoj, esv, props)
                val epc = u(i)
                val pdc = u(i + 1)
                i += 2
                if (i + pdc > length) return ElFrame(tid, seoj, deoj, esv, props)
                props.add(Prop(epc, data.copyOfRange(i, i + pdc)))
                i += pdc
            }
            return ElFrame(tid, seoj, deoj, esv, props)
        }
    }
}

/** ノードプロファイルの 0xD6（自ノードインスタンスリスト S）から機器の一覧を取り出す */
fun parseInstanceList(edt: ByteArray): List<Eoj> {
    if (edt.isEmpty()) return emptyList()
    val n = edt[0].toInt() and 0xFF
    return (0 until n).mapNotNull { k ->
        val i = 1 + k * 3
        if (i + 3 > edt.size) null else Eoj(edt[i].toInt() and 0xFF, edt[i + 1].toInt() and 0xFF, edt[i + 2].toInt() and 0xFF)
    }
}

/**
 * プロパティマップ（0x9D/0x9E/0x9F）を読む。
 * 16個未満は EPC を並べた形、16個以上は 16バイトのビットマップの形。
 */
fun parsePropertyMap(edt: ByteArray): Set<Int> {
    if (edt.isEmpty()) return emptySet()
    val n = edt[0].toInt() and 0xFF
    if (n < 16) return (1..minOf(n, edt.size - 1)).map { edt[it].toInt() and 0xFF }.toSet()
    val set = HashSet<Int>()
    for (i in 0 until 16) {
        if (1 + i >= edt.size) break
        val byte = edt[1 + i].toInt() and 0xFF
        for (bit in 0 until 8) {
            if (byte and (1 shl bit) != 0) set.add(0x80 + bit * 0x10 + i)
        }
    }
    return set
}

fun ByteArray.u8(i: Int = 0): Int? = if (size > i) this[i].toInt() and 0xFF else null
fun ByteArray.s8(i: Int = 0): Int? = if (size > i) this[i].toInt() else null
fun ByteArray.u16(i: Int = 0): Int? = if (size >= i + 2) ((this[i].toInt() and 0xFF) shl 8) or (this[i + 1].toInt() and 0xFF) else null
fun ByteArray.s32(i: Int = 0): Long? = if (size >= i + 4)
    (((this[i].toInt() and 0xFF) shl 24) or ((this[i + 1].toInt() and 0xFF) shl 16) or
        ((this[i + 2].toInt() and 0xFF) shl 8) or (this[i + 3].toInt() and 0xFF)).toLong() else null
fun ByteArray.u32(i: Int = 0): Long? = s32(i)?.let { it and 0xFFFFFFFFL }

fun bytesOf(vararg v: Int) = ByteArray(v.size) { (v[it] and 0xFF).toByte() }

// ---------------- 機器の種類 ----------------

/** アプリでどう扱うか */
enum class ElKind { AIRCON, LIGHT, COVER, ONOFF, METER, SOLAR, BATTERY, WATER_HEATER, READONLY }

data class ElClass(val code: Int, val name: String, val kind: ElKind)

/** 対応する機器の種類（ここにない種類は「その他」として状態だけ表示） */
val EL_CLASSES = listOf(
    ElClass(0x0130, "エアコン", ElKind.AIRCON),
    ElClass(0x0290, "照明", ElKind.LIGHT),
    ElClass(0x0291, "照明", ElKind.LIGHT),
    ElClass(0x02A3, "照明システム", ElKind.LIGHT),
    ElClass(0x02A4, "照明（拡張）", ElKind.LIGHT),
    ElClass(0x0260, "ブラインド", ElKind.COVER),
    ElClass(0x0261, "電動シャッター", ElKind.COVER),
    ElClass(0x0262, "電動カーテン", ElKind.COVER),
    ElClass(0x0263, "電動雨戸・シャッター", ElKind.COVER),
    ElClass(0x0264, "電動ゲート", ElKind.READONLY),   // 安全のため操作しない
    ElClass(0x0265, "電動窓", ElKind.COVER),
    ElClass(0x0133, "換気扇", ElKind.ONOFF),
    ElClass(0x0134, "加湿器", ElKind.ONOFF),
    ElClass(0x0135, "空気清浄機", ElKind.ONOFF),
    ElClass(0x0139, "空気清浄機", ElKind.ONOFF),
    ElClass(0x013D, "除湿機", ElKind.ONOFF),
    ElClass(0x0143, "扇風機", ElKind.ONOFF),
    ElClass(0x027B, "床暖房", ElKind.ONOFF),
    ElClass(0x0272, "給湯器", ElKind.READONLY),
    ElClass(0x026B, "エコキュート", ElKind.WATER_HEATER), // 電気温水器。おひさま沸き上げの沸き上げ操作だけに使う
    ElClass(0x026F, "電気錠", ElKind.READONLY),       // 安全のため操作しない
    ElClass(0x0288, "スマートメーター", ElKind.METER),
    ElClass(0x0287, "分電盤（電力の計測）", ElKind.METER), // Panasonic のスマートコスモなど。瞬時電力は 0xC6
    ElClass(0x0279, "太陽光発電", ElKind.SOLAR),
    ElClass(0x02A5, "太陽光発電（マルチ入力 PCS）", ElKind.SOLAR),
    ElClass(0x027D, "蓄電池", ElKind.BATTERY),
    ElClass(0x02A1, "電気自動車充電器", ElKind.READONLY),
    ElClass(0x05FF, "コントローラー", ElKind.READONLY),
)

fun elClassOf(code: Int): ElClass = EL_CLASSES.firstOrNull { it.code == code }
    ?: ElClass(code, String.format("機器（%04X）", code), ElKind.READONLY)

/** 設置場所（0x81）の名前 */
fun elLocationName(v: Int?): String? = when (v?.let { (it shr 3) and 0x0F }) {
    1 -> "リビング"; 2 -> "ダイニング"; 3 -> "キッチン"; 4 -> "浴室"; 5 -> "トイレ"; 6 -> "洗面所"
    7 -> "廊下"; 8 -> "部屋"; 9 -> "階段"; 10 -> "玄関"; 11 -> "納戸"; 12 -> "庭"; 13 -> "車庫"
    14 -> "ベランダ"; 15 -> "その他"
    else -> null
}

/** 設置場所（0x81）を「部屋2」「リビング」のような名前に（番号があれば付ける） */
fun elLocationFull(v: Int?): String? {
    val base = elLocationName(v) ?: return null
    val n = v!! and 0x07
    return if (n > 0 && v != 0x08) "$base$n" else base
}

/** エコキュートの項目の名前（エラーの表示用） */
fun waterHeaterEpcName(epc: Int): String = when (epc) {
    0xB0 -> "沸き上げ自動設定"
    0xC0 -> "昼間沸き増し許可"
    0xC7 -> "エネルギーシフト参加"
    0xC9 -> "エネルギーシフト回数"
    0xCA -> "昼間沸き上げシフト時刻"
    0xB6 -> "タンク運転モード"
    0xB2 -> "沸き上げ中の状態"
    0xB3 -> "沸き上げ湯温"
    0xB4 -> "手動沸き上げ停止日数"
    0xB5 -> "手動沸き上げ停止（時間）"
    0xC8 -> "沸き上げ開始の基準時刻"
    0xE1 -> "残りのお湯の量"
    0xE2 -> "タンクの容量"
    else -> String.format("0x%02X", epc)
}

// エアコンの運転モード（0xB0）
val AC_MODE_TO_HA = mapOf(0x41 to "auto", 0x42 to "cool", 0x43 to "heat", 0x44 to "dry", 0x45 to "fan_only")
val AC_MODE_FROM_HA = AC_MODE_TO_HA.entries.associate { it.value to it.key } + ("heat_cool" to 0x41)

// エアコンの風量（0xA0）
val AC_FAN_TO_HA = mapOf(0x41 to "auto", 0x31 to "1", 0x32 to "2", 0x33 to "3", 0x34 to "4", 0x35 to "5", 0x36 to "6", 0x37 to "7", 0x38 to "8")
val AC_FAN_FROM_HA = AC_FAN_TO_HA.entries.associate { it.value to it.key } + mapOf("low" to 0x31, "medium" to 0x34, "high" to 0x38)
