package com.aktv.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The maxTV shelf is English and Hindi only, with sport, movies and news kept whatever their label. */
class PortalLanguagesTest {

    @Test
    fun `english and hindi channels stay`() {
        assertTrue(PortalLanguages.keeps("Star Sports 1 Hindi", "SPORTS"))
        assertTrue(PortalLanguages.keeps("Sony SAB", "HINDI"))
        assertTrue(PortalLanguages.keeps("English Movies", ""))
        assertTrue(PortalLanguages.keeps("Zee Cinema", "HINDI MOVIES"))
        assertTrue(PortalLanguages.keeps("BBC Earth", "KNOWLEDGE"))
    }

    @Test
    fun `regional feeds are dropped`() {
        assertFalse(PortalLanguages.keeps("Sun TV", "TAMIL"))
        assertFalse(PortalLanguages.keeps("Star Sports 1 Tamil", "SPORTS"))
        assertFalse(PortalLanguages.keeps("Zee Telugu HD", ""))
        assertFalse(PortalLanguages.keeps("Asianet", "MALAYALAM"))
        assertFalse(PortalLanguages.keeps("Colors Kannada", ""))
        assertFalse(PortalLanguages.keeps("Star Pravah", "MARATHI"))
        assertFalse(PortalLanguages.keeps("Zee Bangla", "BENGALI"))
        assertFalse(PortalLanguages.keeps("PTC Punjabi", ""))
        assertFalse(PortalLanguages.keeps("Bhojpuri Cinema", "BHOJPURI"))
    }

    @Test
    fun `a channel with no language at all is kept`() {
        assertTrue(PortalLanguages.keeps("Star Sports 1", "SPORTS"))
        assertTrue(PortalLanguages.keeps("Discovery Channel", ""))
        assertTrue(PortalLanguages.keeps("Sony Max", "MOVIES"))
    }

    @Test
    fun `an explicit english or hindi marker wins over another language in the same line`() {
        assertTrue(PortalLanguages.keeps("Star Gold", "HINDI/TAMIL"))
        assertTrue(PortalLanguages.keeps("Cartoon Network", "ENGLISH, TELUGU"))
    }
}
