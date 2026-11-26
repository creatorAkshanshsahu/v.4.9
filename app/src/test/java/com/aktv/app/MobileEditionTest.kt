package com.aktv.app

import org.junit.Assert.*
import org.junit.Test

/** The mobile edition's shared seams: catalogue parsing, Play-on-TV wire, update asset keys. */
class MobileEditionTest {

    @Test
    fun `v1 catalogue parses and drops event streams`() {
        val json = """{"channels":[
            {"id":"star-plus","name":"Star Plus","category":"Entertainment","logo":"https://x/logo.png"},
            {"id":"ls-99","name":"Event 99","category":"Sports","logo":""},
            {"id":"","name":"Nameless","category":"Other","logo":""}
        ]}"""
        val entries = MobileCatalog.parseV1(json)
        assertEquals(1, entries.size)
        assertEquals("star-plus", entries[0].id)
        assertEquals("Entertainment", entries[0].category)
    }

    @Test
    fun `v1 catalogue keeps events when they are all there is`() {
        val json = """{"channels":[{"id":"ls-1","name":"Event 1","category":"Sports","logo":""}]}"""
        assertEquals(1, MobileCatalog.parseV1(json).size)
    }

    @Test
    fun `v2 catalogue defaults category to first letter and sorts`() {
        val json = """{"channels":[
            {"id":"zee","name":"Zee TV","logo":""},
            {"id":"abp","name":"ABP News","logo":""}
        ]}"""
        val entries = MobileCatalog.parseV2(json)
        assertEquals(listOf("ABP News", "Zee TV"), entries.map { it.name })
        assertEquals("A", entries[0].category)
        assertEquals("Z", entries[1].category)
    }

    @Test
    fun `broken catalogue bodies parse to nothing`() {
        assertTrue(MobileCatalog.parseV1("nope").isEmpty())
        assertTrue(MobileCatalog.parseV2("{}").isEmpty())
    }

    @Test
    fun `cast live command round-trips`() {
        val line = CastProtocol.encodeLive("tg", "star-plus", "Star Plus")
        val command = CastProtocol.decode(line)
        assertNotNull(command)
        assertEquals(CastProtocol.KIND_LIVE, command!!.kind)
        assertEquals("tg", command.provider)
        assertEquals("star-plus", command.id)
        assertEquals("Star Plus", command.label)
    }

    @Test
    fun `cast vod command round-trips`() {
        val line = CastProtocol.encodeVod("frost", 123, "tv", 2, 5, "Some Show")
        val command = CastProtocol.decode(line)
        assertNotNull(command)
        assertEquals(CastProtocol.KIND_VOD, command!!.kind)
        assertEquals(123, command.tmdb)
        assertEquals("tv", command.type)
        assertEquals(2, command.season)
        assertEquals(5, command.episode)
    }

    @Test
    fun `cast rejects garbage, wrong version and bad vod`() {
        assertNull(CastProtocol.decode(""))
        assertNull(CastProtocol.decode("hello"))
        assertNull(CastProtocol.decode("""{"v":2,"kind":"live","provider":"tg","id":"x"}"""))
        assertNull(CastProtocol.decode("""{"v":1,"kind":"live","provider":"","id":"x"}"""))
        assertNull(CastProtocol.decode("""{"v":1,"kind":"vod","tmdb":0,"type":"movie"}"""))
        assertNull(CastProtocol.decode("""{"v":1,"kind":"vod","tmdb":5,"type":"radio"}"""))
    }

    @Test
    fun `empty-state reasons hide urls and hex tokens`() {
        val reason = MobileErrors.why(java.io.IOException("HTTP 403 https://cdn.example.com/k/abcdef0123456789"))
        assertFalse(reason.contains("https://"))
        assertFalse(reason.contains("abcdef0123456789"))
        assertTrue(reason.contains("403"))
    }

    @Test
    fun `abi tag follows bitness`() {
        assertEquals("arm64", UpdateManifest.abiTag(listOf("arm64-v8a", "armeabi-v7a")))
        assertEquals("arm32", UpdateManifest.abiTag(listOf("armeabi-v7a")))
    }

    @Test
    fun `manifest picks this edition's asset`() {
        val body = """{"versionCode":173,"versionName":"5.4.54","notes":"n","apks":{
            "arm64":{"url":"https://github.com/o/r/releases/latest/download/STREAMR-arm64.apk","sha256":"${"a".repeat(64)}","size":10},
            "mobile-arm64":{"url":"https://github.com/o/r/releases/latest/download/STREAMR-mobile-arm64.apk","sha256":"${"b".repeat(64)}","size":11}
        }}"""
        val tv = UpdateManifest.parse(body, "arm64")
        val mobile = UpdateManifest.parse(body, "mobile-arm64")
        assertNotNull(tv); assertNotNull(mobile)
        assertTrue(tv!!.apkUrl.endsWith("STREAMR-arm64.apk"))
        assertTrue(mobile!!.apkUrl.endsWith("STREAMR-mobile-arm64.apk"))
    }
}
