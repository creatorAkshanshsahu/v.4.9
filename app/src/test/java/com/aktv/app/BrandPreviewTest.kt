package com.aktv.app

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.Duration

/** Pixel previews of the real Android logo and entrance, not generated concept art. */
@RunWith(org.robolectric.RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w960dp-h540dp-land-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BrandPreviewTest {
    @Test fun `render selected AK mark and entrance from production views`() {
        val controller = Robolectric.buildActivity(Activity::class.java).create()
        try {
            val activity = controller.get().apply { setTheme(R.style.Theme_AKTV) }
            controller.start().resume().visible()
            val output = File(System.getProperty("aktv.ui.previewDir", "build/reports/ui-preview")).apply { mkdirs() }
            val root = FrameLayout(activity).apply { setBackgroundColor(Accent.ABYSS) }
            val mark = ImageView(activity).apply {
                setImageResource(R.drawable.ic_brand); scaleType = ImageView.ScaleType.FIT_CENTER
                contentDescription = "AK TV logo preview"
            }
            root.addView(mark, FrameLayout.LayoutParams(760, 475, Gravity.CENTER).apply { bottomMargin = 100 })
            root.addView(TextView(activity).apply {
                text = "AK TV"; textSize = 36f; setTextColor(Color.WHITE); typeface = android.graphics.Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
            }, FrameLayout.LayoutParams(-1, 56, Gravity.CENTER).apply { topMargin = 320 })
            capture(root, 960, 540, File(output, "brand-mark.webp"))

            activity.getSharedPreferences("aktv", Context.MODE_PRIVATE).edit().putBoolean("intro_sound_enabled", false).commit()
            val introRoot = FrameLayout(activity).apply { setBackgroundColor(Accent.ABYSS) }
            val intro = AkIntroView(activity)
            introRoot.addView(intro, FrameLayout.LayoutParams(-1, -1))
            activity.setContentView(introRoot)
            introRoot.measure(ViewSpec.exact(960), ViewSpec.exact(540)); introRoot.layout(0, 0, 960, 540)
            intro.start()
            shadowOf(android.os.Looper.getMainLooper()).idleFor(Duration.ofMillis(700))
            // Pin a known in-flight frame: Robolectric's animation scale may otherwise jump to completion.
            intro.skip()
            AkIntroView::class.java.getDeclaredField("progress").apply { isAccessible = true }.setFloat(intro, 0.55f)
            capture(introRoot, 960, 540, File(output, "entrance.webp"))
        } finally { controller.destroy() }
    }

    private fun capture(view: android.view.View, width: Int, height: Int, file: File) {
        view.measure(ViewSpec.exact(width), ViewSpec.exact(height)); view.layout(0, 0, width, height)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.WEBP, 88, it)) }
        bitmap.recycle()
    }

    private object ViewSpec {
        fun exact(size: Int) = android.view.View.MeasureSpec.makeMeasureSpec(size, android.view.View.MeasureSpec.EXACTLY)
    }
}
