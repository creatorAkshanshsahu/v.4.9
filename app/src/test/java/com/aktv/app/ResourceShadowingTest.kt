package com.aktv.app

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A resource name that exists in two drawable folders is not a build error — Android quietly prefers the
 * density-qualified copy. That is exactly how an old launcher artwork kept winning the icon: the device
 * showed the old mark while the new layer-list sat unused beside it. The tree is checked here so a name
 * cannot be defined twice again.
 */
class ResourceShadowingTest {

    private fun res(): File {
        for (candidate in listOf("src/main/res", "app/src/main/res", "../app/src/main/res")) {
            val dir = File(candidate)
            if (dir.isDirectory) return dir
        }
        throw AssertionError("could not find app/src/main/res from ${File(".").absolutePath}")
    }

    private fun drawableDirs(root: File): List<File> =
        root.listFiles().orEmpty().filter { it.isDirectory && it.name.startsWith("drawable") }

    @Test
    fun `no drawable name is defined twice across density folders`() {
        val seen = mutableMapOf<String, MutableList<String>>()
        for (dir in drawableDirs(res())) {
            for (f in dir.listFiles().orEmpty()) {
                seen.getOrPut(f.nameWithoutExtension) { mutableListOf() } += "${dir.name}/${f.name}"
            }
        }
        val shadowed = seen.filterValues { it.size > 1 }
        assertTrue("the same drawable is defined in more than one folder: $shadowed", shadowed.isEmpty())
    }

    @Test
    fun `the launcher foreground is the brand tile layer, not leftover art`() {
        val foreground = File(res(), "drawable/ic_launcher_foreground.xml")
        assertTrue("launcher foreground layer-list is missing", foreground.isFile)
        assertTrue("launcher foreground must draw the brand tile", foreground.readText().contains("streamr_icon_fg"))
        val adaptive = File(res(), "mipmap-anydpi-v26/ic_launcher.xml")
        assertTrue("adaptive icon is missing", adaptive.isFile)
        assertTrue("adaptive icon must keep the dark ground behind the tile", adaptive.readText().contains("@color/night"))
    }
}
