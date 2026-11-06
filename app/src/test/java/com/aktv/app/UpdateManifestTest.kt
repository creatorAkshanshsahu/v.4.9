package com.aktv.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The in-app updater must never install a file it cannot verify. */
class UpdateManifestTest {
    private val sha64 = "a".repeat(64)
    private val manifest = """
        {
          "versionCode": 63,
          "versionName": "4.9.46",
          "build": "1234",
          "notes": "Playlist section + faster first attempt",
          "apks": {
            "arm32": {"url": "https://github.com/creatorAkshanshsahu/v.4.9/releases/latest/download/STREAMR-arm32.apk", "sha256": "$sha64", "size": 100},
            "arm64": {"url": "https://github.com/creatorAkshanshsahu/v.4.9/releases/latest/download/STREAMR-arm64.apk", "sha256": "${"b".repeat(64)}", "size": 200}
          }
        }
    """.trimIndent()

    @Test fun `the published manifest is read per ABI`() {
        val arm64 = UpdateManifest.parse(manifest, "arm64")!!
        assertEquals(63L, arm64.versionCode)
        assertEquals("4.9.46", arm64.versionName)
        assertTrue(arm64.apkUrl.endsWith("STREAMR-arm64.apk"))
        assertEquals(200L, arm64.size)
        val arm32 = UpdateManifest.parse(manifest, "arm32")!!
        assertTrue(arm32.apkUrl.endsWith("STREAMR-arm32.apk"))
        assertEquals("4.9.46 (code 63)", arm32.label)
    }

    @Test fun `a manifest without this ABI is not installable`() {
        assertNull(UpdateManifest.parse(manifest, "x86"))
    }

    @Test fun `a missing or malformed checksum is rejected`() {
        assertNull(UpdateManifest.parse(manifest.replace(sha64, "1234"), "arm32"))
        assertNull(UpdateManifest.parse(manifest.replace("https://github.com/", "http://evil.example/"), "arm32"))
        assertNull(UpdateManifest.parse("", "arm32"))
        assertNull(UpdateManifest.parse("{\"versionCode\":63}", "arm32"))
        assertNull(UpdateManifest.parse(manifest.replace("\"versionCode\": 63", "\"versionCode\": 0"), "arm32"))
    }

    @Test fun `only a higher code or a re-published build counts as an update`() {
        val release = UpdateManifest.parse(manifest, "arm32")!!
        assertTrue(UpdateManifest.isNewer(62, release, null))
        assertFalse(UpdateManifest.isNewer(63, release, null))
        assertFalse(UpdateManifest.isNewer(64, release, null))
        // Same code, different file: a re-run published a new APK and the installed one is known.
        assertTrue(UpdateManifest.isNewer(63, release, "c".repeat(64)))
        assertFalse(UpdateManifest.isNewer(63, release, sha64))
    }

    @Test fun `the ABI tag follows a 64-bit install`() {
        assertEquals("arm64", UpdateManifest.abiTag(listOf("arm64-v8a", "armeabi-v7a")))
        assertEquals("arm32", UpdateManifest.abiTag(listOf("armeabi-v7a", "armeabi")))
    }

    @Test fun `a universal asset is accepted when a per-ABI build is not published`() {
        val universal = manifest.replace("\"arm32\": {", "\"universal\": {").replace("\"arm64\": {", "\"spare\": {")
        assertNotNull(UpdateManifest.parse(universal, "arm32"))
    }
}
