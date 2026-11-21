package com.aktv.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UpdateDownloadTest {
    @Test fun `progress is an integer percent clamped to the bar`() {
        assertEquals(0, UpdateManifest.progressPercent(0, 100))
        assertEquals(50, UpdateManifest.progressPercent(50, 100))
        assertEquals(100, UpdateManifest.progressPercent(100, 100))
        assertEquals(100, UpdateManifest.progressPercent(150, 100))
    }

    @Test fun `progress with an unknown total stays at zero`() {
        assertEquals(0, UpdateManifest.progressPercent(1234, 0))
        assertEquals(0, UpdateManifest.progressPercent(1234, -1))
    }

    @Test fun `a range answer yields its start byte and total`() {
        assertEquals(100L, UpdateManifest.rangeStart("bytes 100-199/1000"))
        assertEquals(1000L, UpdateManifest.rangeTotal("bytes 100-199/1000"))
    }

    @Test fun `an unsatisfiable range still yields its total but no start`() {
        assertNull(UpdateManifest.rangeStart("bytes */1000"))
        assertEquals(1000L, UpdateManifest.rangeTotal("bytes */1000"))
    }

    @Test fun `garbage range answers are a miss, never an exception`() {
        assertNull(UpdateManifest.rangeStart(null))
        assertNull(UpdateManifest.rangeTotal(null))
        assertNull(UpdateManifest.rangeStart("bytes abc-def/1000"))
        assertNull(UpdateManifest.rangeTotal("bytes 100-199/bogus"))
        assertNull(UpdateManifest.rangeTotal("bytes 100-199/0"))
        assertNull(UpdateManifest.rangeTotal("bytes 100-199/99999999999999999999"))
    }
}
