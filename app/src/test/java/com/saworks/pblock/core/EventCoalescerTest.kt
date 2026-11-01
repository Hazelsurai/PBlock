package com.saworks.pblock.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EventCoalescerTest {
    @Test fun firstOfferSchedules() {
        val c = EventCoalescer()
        assertNull(c.take())
        assertTrue(c.offer("com.android.settings", windowChange = false))
        assertFalse(c.offer("com.android.settings", windowChange = false))
        assertEquals(EventCoalescer.Request("com.android.settings", false), c.take())
        assertNull(c.take())
        assertTrue(c.offer("com.android.settings", windowChange = false))
    }

    @Test fun windowChangeIsKeptAcrossMerges() {
        val c = EventCoalescer()
        c.offer("com.android.settings", windowChange = true)
        c.offer("com.android.settings", windowChange = false)
        assertEquals(EventCoalescer.Request("com.android.settings", true), c.take())
    }

    @Test fun latestPackageWins() {
        val c = EventCoalescer()
        c.offer("com.android.settings", windowChange = false)
        c.offer("com.google.android.packageinstaller", windowChange = true)
        c.offer("com.android.permissioncontroller", windowChange = false)
        assertEquals(EventCoalescer.Request("com.android.permissioncontroller", true), c.take())
    }
}