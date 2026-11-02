package com.aktv.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FreshRefusalPolicyTest {
    @Test fun `a fresh JJTV 403 moves to the alternate native provider`() {
        assertTrue(FreshRefusalPolicy.shouldMoveToAlternateProvider(LiveProvider.JJTV, 403, freshLookupTried = true))
    }

    @Test fun `first JJTV refusal still gets its fresh credential lookup`() {
        assertFalse(FreshRefusalPolicy.shouldMoveToAlternateProvider(LiveProvider.JJTV, 403, freshLookupTried = false))
    }

    @Test fun `TG retains its bounded paced retry behavior`() {
        assertFalse(FreshRefusalPolicy.shouldMoveToAlternateProvider(LiveProvider.TG, 403, freshLookupTried = true))
    }

    @Test fun `other HTTP errors are not misclassified as rejected credentials`() {
        assertFalse(FreshRefusalPolicy.shouldMoveToAlternateProvider(LiveProvider.JJTV, 451, freshLookupTried = true))
    }
}
