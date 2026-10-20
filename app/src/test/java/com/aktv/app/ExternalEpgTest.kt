package com.aktv.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.FileOutputStream
import java.time.Instant
import java.util.zip.GZIPOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ExternalEpgTest {
    @Test fun `streaming XMLTV parser builds now next and later items by Jio channel id`() {
        val now = Instant.parse("2030-01-01T09:30:00Z").epochSecond
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <tv>
              <programme start="20300101090000 +0000" stop="20300101100000 +0000" channel="144">
                <title>Morning live</title><desc>Current show description</desc><category>News</category>
              </programme>
              <programme start="20300101100000 +0000" stop="20300101110000 +0000" channel="144">
                <title>Next show</title>
              </programme>
              <programme start="20300101110000 +0000" stop="20300101120000 +0000" channel="144">
                <title>Later show</title>
              </programme>
              <programme start="20300101080000 +0000" stop="20300101090000 +0000" channel="999">
                <title>Other channel</title>
              </programme>
            </tv>
        """.trimIndent()
        val file = File.createTempFile("aktv-epg-test", ".xml.gz")
        try {
            GZIPOutputStream(FileOutputStream(file)).bufferedWriter(Charsets.UTF_8).use { it.write(xml) }
            val index = EpgXmlTv.parse(file, now)
            val guide = EpgXmlTv.guide(index["144"].orEmpty(), now)
            assertNotNull(guide)
            assertEquals("Morning live", guide?.now?.title)
            assertEquals("Next show", guide?.next?.title)
            assertEquals("Later show", guide?.upcoming?.single()?.title)
            assertEquals("Current show description", guide?.now?.desc)
            assertEquals(ExternalEpg.SOURCE, guide?.source)
            assertEquals(null, index["999"]?.firstOrNull { it.start >= now })
        } finally { file.delete() }
    }

    @Test fun `missing or stale channel listing has no fabricated programme`() {
        val now = Instant.parse("2030-01-01T09:30:00Z").epochSecond
        val file = File.createTempFile("aktv-epg-empty", ".xml.gz")
        try {
            GZIPOutputStream(FileOutputStream(file)).bufferedWriter(Charsets.UTF_8).use {
                it.write("<tv><programme start=\"20291201090000 +0000\" stop=\"20291201100000 +0000\" channel=\"165\"><title>Old</title></programme></tv>")
            }
            val index = EpgXmlTv.parse(file, now)
            assertNull(EpgXmlTv.guide(index["165"].orEmpty(), now))
            assertNull(EpgXmlTv.guide(index["872"].orEmpty(), now))
        } finally { file.delete() }
    }
}
