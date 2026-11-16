package com.hitomatito.amwf

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MainActivityToggleTest {

    @Test
    fun `unverified check locks enabling monitor mode but keeps disable and retry`() {
        assertFalse(toggleEnabledForState(false, MonitorMode.MANAGED))
        assertTrue(toggleEnabledForState(false, MonitorMode.MONITOR))
        assertTrue(toggleEnabledForState(false, MonitorMode.UNKNOWN))
    }

    @Test
    fun `verified check leaves every mode actionable`() {
        assertTrue(toggleEnabledForState(true, MonitorMode.MANAGED))
        assertTrue(toggleEnabledForState(true, MonitorMode.MONITOR))
        assertTrue(toggleEnabledForState(true, MonitorMode.UNKNOWN))
    }
}
