package com.saworks.pblock.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MonitorAndBreathingTest {
    @Test fun missedBoots() {
        assertEquals(0, MonitorLogic.missedBoots(-1, 10))
        assertEquals(0, MonitorLogic.missedBoots(10, 10))
        assertEquals(0, MonitorLogic.missedBoots(10, 11))
        assertEquals(1, MonitorLogic.missedBoots(10, 12)) // e.g. one boot into safe mode
        assertEquals(0, MonitorLogic.missedBoots(10, -1))
    }

    @Test fun downTracking() {
        val g = MonitorLogic.GRACE_MS
        assertEquals(MonitorLogic.Action.NONE, MonitorLogic.step(up = true, since = 0, logged = false, now = 1000))
        assertEquals(MonitorLogic.Action.START, MonitorLogic.step(up = false, since = 0, logged = false, now = 1000))
        assertEquals(MonitorLogic.Action.NONE, MonitorLogic.step(up = false, since = 1000, logged = false, now = 1000 + g - 1))
        assertEquals(MonitorLogic.Action.LOG_OFF, MonitorLogic.step(up = false, since = 1000, logged = false, now = 1000 + g))
        assertEquals(MonitorLogic.Action.NONE, MonitorLogic.step(up = false, since = 1000, logged = true, now = 1000 + 5 * g))
        assertEquals(MonitorLogic.Action.LOG_ON, MonitorLogic.step(up = true, since = 1000, logged = true, now = 1000 + 5 * g))
        assertEquals(MonitorLogic.Action.CLEAR, MonitorLogic.step(up = true, since = 1000, logged = false, now = 2000)) // short blip
    }

    @Test fun boxBreathing() {
        assertEquals(96_000L, BoxBreathing.TOTAL_MS)
        val s0 = BoxBreathing.at(0)!!
        assertEquals(BoxBreathing.Phase.IN, s0.phase); assertEquals(1, s0.round); assertEquals(4, s0.secondsLeft)
        assertEquals(1, BoxBreathing.at(3_500)!!.secondsLeft)
        assertEquals(BoxBreathing.Phase.HOLD_FULL, BoxBreathing.at(4_000)!!.phase)
        assertEquals(BoxBreathing.Phase.OUT, BoxBreathing.at(8_000)!!.phase)
        assertEquals(BoxBreathing.Phase.HOLD_EMPTY, BoxBreathing.at(12_000)!!.phase)
        assertEquals(2, BoxBreathing.at(16_000)!!.round)
        assertEquals(6, BoxBreathing.at(95_999)!!.round)
        assertNull(BoxBreathing.at(96_000))
        assertNull(BoxBreathing.at(-1))
    }
}
