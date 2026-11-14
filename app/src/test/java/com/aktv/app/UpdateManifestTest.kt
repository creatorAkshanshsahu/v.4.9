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

    private val releaseJson = """
        {
          "tag_name": "latest",
          "name": "STREAMR latest build",
          "body": "# STREAMR 5.4.0\n\nVersion code **119**, CI build 121.\n",
          "assets": [
            {"name": "SHA256SUMS", "size": 168, "state": "uploaded",
             "browser_download_url": "https://github.com/creatorAkshanshsahu/v.4.9/releases/download/latest/SHA256SUMS",
             "digest": "sha256:${"d".repeat(64)}"},
            {"name": "STREAMR-arm64.apk", "size": 91156336, "state": "uploaded",
             "browser_download_url": "https://github.com/creatorAkshanshsahu/v.4.9/releases/download/latest/STREAMR-arm64.apk",
             "digest": "sha256:${"b".repeat(64)}"},
            {"name": "STREAMR-arm32.apk", "size": 85988152, "state": "uploaded",
             "browser_download_url": "https://github.com/creatorAkshanshsahu/v.4.9/releases/download/latest/STREAMR-arm32.apk",
             "digest": "sha256:$sha64"}
          ]
        }
    """.trimIndent()

    @Test fun `the release API is read per ABI when the download path will not answer`() {
        val arm32 = UpdateManifest.fromReleaseApi(releaseJson, "arm32")!!
        assertEquals(119L, arm32.versionCode)
        assertEquals("5.4.0", arm32.versionName)
        assertTrue(arm32.apkUrl.endsWith("STREAMR-arm32.apk"))
        assertEquals(sha64, arm32.sha256)
        assertEquals(85988152L, arm32.size)
        assertEquals("5.4.0 (code 119)", arm32.label)
        assertTrue(UpdateManifest.fromReleaseApi(releaseJson, "arm64")!!.apkUrl.endsWith("STREAMR-arm64.apk"))
    }

    @Test fun `a release without a version or a digest is not installable`() {
        assertNull(UpdateManifest.fromReleaseApi(releaseJson.replace("Version code **119**", "Version code 119"), "arm32"))
        assertNull(UpdateManifest.fromReleaseApi(releaseJson.replace("sha256:$sha64", "sha256:1234"), "arm32"))
        // An asset GitHub has not finished uploading cannot be installed.
        val pending = """{"body":"Version code **119**","assets":[{"name":"STREAMR-arm32.apk","state":"new",
            "size":1,"digest":"sha256:$sha64","browser_download_url":"https://github.com/x/y.apk"}]}"""
        assertNull(UpdateManifest.fromReleaseApi(pending, "arm32"))
        assertNull(UpdateManifest.fromReleaseApi("", "arm32"))
        assertNull(UpdateManifest.fromReleaseApi("{\"assets\":[]}", "arm32"))
    }
}
