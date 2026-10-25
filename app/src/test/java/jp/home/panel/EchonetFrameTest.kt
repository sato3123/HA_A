package jp.home.panel

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EchonetFrameTest {

    @Test
    fun encodeGetInstanceList() {
        val f = ElFrame(1, EOJ_CONTROLLER, EOJ_NODE_PROFILE, Esv.GET, listOf(Prop(0xD6)))
        assertArrayEquals(
            bytesOf(0x10, 0x81, 0x00, 0x01, 0x05, 0xFF, 0x01, 0x0E, 0xF0, 0x01, 0x62, 0x01, 0xD6, 0x00),
            f.encode(),
        )
    }

    @Test
    fun decodeRoundTrip() {
        val f = ElFrame(0x1234, Eoj(0x01, 0x30, 0x01), EOJ_CONTROLLER, Esv.GET_RES,
            listOf(Prop(0x80, bytesOf(0x30)), Prop(0xB3, bytesOf(26))))
        val d = ElFrame.decode(f.encode())!!
        assertEquals(0x1234, d.tid)
        assertEquals(0x0130, d.seoj.classCode)
        assertEquals(Esv.GET_RES, d.esv)
        assertEquals(2, d.props.size)
        assertEquals(26, d.props[1].edt.u8())
    }

    @Test
    fun decodeRejectsGarbage() {
        assertNull(ElFrame.decode(bytesOf(0x10, 0x82, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)))
        assertNull(ElFrame.decode(bytesOf(0x10, 0x81, 0)))
    }

    @Test
    fun instanceList() {
        // 2台：エアコン（013001）と照明（029001）
        val list = parseInstanceList(bytesOf(0x02, 0x01, 0x30, 0x01, 0x02, 0x90, 0x01))
        assertEquals(listOf(Eoj(0x01, 0x30, 0x01), Eoj(0x02, 0x90, 0x01)), list)
    }

    @Test
    fun propertyMapList() {
        assertEquals(setOf(0x80, 0xB0, 0xB3), parsePropertyMap(bytesOf(0x03, 0x80, 0xB0, 0xB3)))
    }

    @Test
    fun propertyMapBitmap() {
        // 0x80（1バイト目 bit0）と 0xB3（4バイト目 bit3）
        val edt = ByteArray(17)
        edt[0] = 16
        edt[1] = 0x01
        edt[4] = 0x08
        assertEquals(setOf(0x80, 0xB3), parsePropertyMap(edt))
    }

    @Test
    fun signedRoomTemperature() {
        assertEquals(-5, bytesOf(0xFB).s8())
        assertEquals(1500L, bytesOf(0x00, 0x00, 0x05, 0xDC).s32())
        assertEquals(-200L, bytesOf(0xFF, 0xFF, 0xFF, 0x38).s32())
    }

    @Test
    fun location() {
        assertEquals("リビング", elLocationName(0x08))
        assertEquals("キッチン", elLocationName(0x19))
        assertNull(elLocationName(0x00))
    }
}
