package com.aktv.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Where a portal's API may be, in the order a client should try it. */
class StalkerEndpointsTest {

    @Test
    fun `the configured address is tried first and the common panel layout second`() {
        val list = StalkerEndpoints.candidates("http://new.maxtv.be/stalker_portal/c/")
        assertEquals("http://new.maxtv.be/stalker_portal/c/server/load.php", list[0])
        assertEquals("http://new.maxtv.be/stalker_portal/server/load.php", list[1])
        assertTrue("the bare host is a candidate too: $list", list.contains("http://new.maxtv.be/server/load.php"))
        assertTrue(list.none { it.endsWith("//") })
        assertEquals("no address is offered twice", list.size, list.toSet().size)
    }

    @Test
    fun `an address without the client path does not invent one`() {
        val list = StalkerEndpoints.candidates("https://host.example/c")
        assertEquals("https://host.example/c/server/load.php", list[0])
        assertEquals("https://host.example/server/load.php", list[1])
        assertTrue("an https portal is not retried over plain http", list.none { it.startsWith("http://") })
    }

    @Test
    fun `labels quote the path so a status line stays readable`() {
        assertEquals("/stalker_portal/server/load.php",
            StalkerEndpoints.label("http://host.example/stalker_portal/server/load.php"))
        assertEquals("http://host.example:8080", StalkerEndpoints.originOf("http://host.example:8080/stalker_portal/c/"))
    }
}
