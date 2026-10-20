package com.aktv.app

import android.app.Activity
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ProgrammeGuideLayoutTest {
    @Test fun `native live player exposes a guide action and a separate schedule panel`() {
        val controller = Robolectric.buildActivity(Activity::class.java).create()
        try {
            val activity = controller.get(); activity.setTheme(R.style.Theme_AKTV)
            val root = LayoutInflater.from(activity).inflate(R.layout.activity_main, null)
            activity.setContentView(root)
            val action = root.findViewById<TextView>(R.id.infoGuideAction)
            val panel = root.findViewById<View>(R.id.programmeGuidePanel)
            val close = root.findViewById<TextView>(R.id.programmeGuideClose)
            assertEquals("GUIDE", action.text.toString())
            assertEquals(View.GONE, panel.visibility)
            assertTrue(close.isFocusable && close.isClickable)
            assertNotNull(root.findViewById<View>(R.id.programmeGuideEntries))
            assertNotNull(root.findViewById<View>(R.id.programmeGuideScrim))
        } finally { controller.destroy() }
    }
}
