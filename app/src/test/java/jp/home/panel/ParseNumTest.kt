package jp.home.panel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 画面で入れた数字の読み取り（全角・単位つき）。読めないと保存されない・0 になる不具合を防ぐ */
class ParseNumTest {
    @Test
    fun halfWidth() {
        assertEquals(8.5, parseNum("8.5")!!, 1e-9)
        assertEquals(-1.5, parseNum("-1.5")!!, 1e-9)
        assertEquals(16.0, parseNum("16")!!, 1e-9)
    }

    @Test
    fun fullWidthAndUnits() {
        assertEquals(8.5, parseNum("８．５")!!, 1e-9)
        assertEquals(8.5, parseNum("８。５円")!!, 1e-9)
        assertEquals(-1.5, parseNum("－１．５")!!, 1e-9)
        assertEquals(16.0, parseNum("16円/kWh")!!, 1e-9)
        assertEquals(1200.0, parseNum("1,200円")!!, 1e-9)
        assertEquals(2.5, parseNum("2,5")!!, 1e-9)
        assertEquals(100.0, parseNum("100L")!!, 1e-9)
        assertEquals(25.0, parseNum(" 25℃ ")!!, 1e-9)
    }

    @Test
    fun emptyAndInvalid() {
        assertNull(parseNum(""))
        assertEquals(0.0, parseNum("", empty = 0.0)!!, 1e-9)
        assertNull(parseNum("abc"))
        assertNull(parseNum("8.5.1"))
    }
}
