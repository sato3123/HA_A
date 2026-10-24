package jp.home.panel

/**
 * 部屋。key は
 *  - "area:<area_id>" … Home Assistant のエリア
 *  - "local:<番号>"   … アプリで追加した部屋
 *  - "none"           … 部屋が決まっていない機器
 */
data class Room(val key: String, val name: String, val fromHa: Boolean)

const val NO_ROOM = "none"
val NO_ROOM_ITEM = Room(NO_ROOM, "その他", false)

/** 機器がどの部屋にあるか：アプリでの割り当て → Home Assistant のエリア → その他 */
fun roomKeyOf(entityId: String, overrides: Map<String, String>, haAreas: Map<String, String>, rooms: List<Room>): String {
    val o = overrides[entityId]
    if (o != null && (o == NO_ROOM || rooms.any { it.key == o })) return o
    val a = haAreas[entityId] ?: return NO_ROOM
    val key = "area:$a"
    return if (rooms.any { it.key == key }) key else NO_ROOM
}

/** 部屋ごとに並べた一覧（機器が無い部屋は出さない） */
fun groupByRoom(
    entities: List<Entity>,
    rooms: List<Room>,
    keyOf: (Entity) -> String,
): List<Pair<Room, List<Entity>>> {
    val byKey = entities.groupBy(keyOf)
    return (rooms + NO_ROOM_ITEM).mapNotNull { r -> byKey[r.key]?.takeIf { it.isNotEmpty() }?.let { r to it } }
}
