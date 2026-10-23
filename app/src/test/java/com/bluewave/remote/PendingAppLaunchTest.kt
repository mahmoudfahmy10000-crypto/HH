package com.bluewave.remote

import org.junit.Assert.*
import org.junit.Test

class PendingAppLaunchTest {
    @Test
    fun appListResponseLaunchesOneUserIntentOnlyOnce() {
        val pending = PendingAppLaunch()
        val app = TvApp("", "YouTube")
        assertTrue(pending.begin("192.168.1.5", app))
        assertFalse(pending.begin("192.168.1.5", app))
        assertEquals(app, pending.consume("192.168.1.5"))
        assertNull(pending.consume("192.168.1.5"))
    }

    @Test
    fun switchingTvsOrClearingNeverReplaysOldLaunch() {
        val pending = PendingAppLaunch()
        assertTrue(pending.begin("192.168.1.5", TvApp("", "Shahid")))
        assertNull(pending.consume("192.168.1.6"))
        assertNull(pending.consume("192.168.1.5"))
        assertTrue(pending.begin("192.168.1.5", TvApp("", "Yango Play")))
        pending.clear()
        assertNull(pending.consume("192.168.1.5"))
    }
}
