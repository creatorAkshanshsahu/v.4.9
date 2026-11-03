package com.aktv.app

import org.junit.Assert.*
import org.junit.Test

class LiveSourceWarmupGateTest {
    @Test fun `one focus prefetch per provider channel can be in flight`() {
        val gate = LiveSourceWarmupGate()
        assertTrue(gate.begin("JJTV:892", 100))
        assertFalse(gate.begin("JJTV:892", 200))
        assertTrue(gate.begin("TG:892", 200))
        gate.finish("JJTV:892")
        assertFalse(gate.begin("JJTV:892", 300))
    }

    @Test fun `warmup can run again after cooldown and across independent channels`() {
        val gate = LiveSourceWarmupGate(cooldownMs = 1_000)
        assertTrue(gate.begin("JJTV:892", 100))
        gate.finish("JJTV:892")
        assertFalse(gate.begin("JJTV:892", 1_099))
        assertTrue(gate.begin("JJTV:892", 1_100))
        assertTrue(gate.begin("JJTV:156", 1_100))
    }
}
