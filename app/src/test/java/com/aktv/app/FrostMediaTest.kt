package com.aktv.app

import androidx.media3.common.C
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class FrostMediaTest {
    @Test fun `VOD items declare the actual source format without a live offset or a DRM substitution`() {
        for (format in VodFormat.values()) {
            val source = FrostSource(FrostProvider.FROST, "id", "https://relay.example.test/proxy?url=synthetic", format,
                "1080p", "English", null, emptyMap(), emptyList())
            val item = FrostMedia.item(source)
            assertEquals(format.mime, item.localConfiguration!!.mimeType)
            assertEquals(source.url, item.localConfiguration!!.uri.toString())
            assertEquals(C.TIME_UNSET, item.liveConfiguration.targetOffsetMs)
            assertNull(item.localConfiguration!!.drmConfiguration)
        }
    }
    @Test fun `external subtitles retain language label MIME and default flag`() {
        val source = FrostSource(FrostProvider.FROST, "id", "https://media.example.test/v.mp4", VodFormat.MP4,
            "720p", "English", null, emptyMap(), listOf(FrostSubtitle("https://subs.example.test/en.vtt", "English", "en", "text/vtt", true)))
        val subtitle = FrostMedia.item(source).localConfiguration!!.subtitleConfigurations.single()
        assertEquals("en", subtitle.language)
        assertEquals("English", subtitle.label)
        assertEquals("text/vtt", subtitle.mimeType)
        assertEquals(C.SELECTION_FLAG_DEFAULT, subtitle.selectionFlags)
    }
}
