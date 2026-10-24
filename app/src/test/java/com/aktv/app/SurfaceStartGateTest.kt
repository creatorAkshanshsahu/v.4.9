package com.aktv.app

import org.junit.Assert.*
import org.junit.Test

class SurfaceStartGateTest {
    @Test fun `preparation waits for the real surface and only runs once`() {
        var ready = false
        var calls = 0
        val gate = SurfaceStartGate({ true }, { ready }, { calls++ })
        gate.signal(); assertEquals(0, calls)
        ready = true
        gate.signal(); gate.signal(); gate.signal()
        assertEquals(1, calls)
    }

    @Test fun `late surface creation cannot prepare a released or replaced player`() {
        var active = true
        var ready = false
        var calls = 0
        val gate = SurfaceStartGate({ active }, { ready }, { calls++ })
        gate.signal(); active = false; ready = true; gate.signal()
        assertEquals(0, calls)
    }

    @Test fun `a surface ready before callback registration prepares immediately`() {
        var calls = 0
        val gate = SurfaceStartGate({ true }, { true }, { calls++ })
        gate.signal(); gate.signal()
        assertEquals(1, calls)
    }
}
