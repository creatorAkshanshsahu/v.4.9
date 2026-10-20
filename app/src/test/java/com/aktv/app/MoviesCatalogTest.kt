package com.aktv.app

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class MoviesCatalogTest {
    @Test fun `trailer selection prefers an official YouTube trailer and ignores unrelated providers`() {
        val data = JSONObject("""{"videos":{"results":[
            {"site":"Vimeo","type":"Trailer","key":"vimeo_key"},
            {"site":"YouTube","type":"Clip","key":"clip_12345"},
            {"site":"YouTube","type":"Trailer","official":false,"key":"unofficial"},
            {"site":"YouTube","type":"Trailer","official":true,"key":"official_key1"}
        ]}}""")
        assertEquals("official_key1", Movies.videoKey(data))
    }

    @Test fun `trailer selection falls back to a YouTube teaser and rejects invalid keys`() {
        val teaser = JSONObject("""{"videos":{"results":[{"site":"YouTube","type":"Teaser","key":"teaser_1"}]}}""")
        assertEquals("teaser_1", Movies.videoKey(teaser))
        val invalid = JSONObject("""{"videos":{"results":[{"site":"YouTube","type":"Trailer","key":"unsafe/key?x=y"}]}}""")
        assertEquals("", Movies.videoKey(invalid))
    }
}
