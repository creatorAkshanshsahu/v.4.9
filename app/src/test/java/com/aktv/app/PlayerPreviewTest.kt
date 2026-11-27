package com.aktv.app

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.media3.ui.PlayerView
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Offline screenshots of the production native player layouts; sample stills do not imply playback. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w960dp-h540dp-land-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PlayerPreviewTest {
    @Test fun `render vod and live player screens without opening a source`() {
        val output = File(System.getProperty("aktv.ui.previewDir", "build/reports/ui-preview")).apply { mkdirs() }
        renderVod(File(output, "vod-player.webp"))
        renderLive(File(output, "live-player.webp"))
    }

    private fun renderVod(file: File) {
        val app = RuntimeEnvironment.getApplication()
        val intent = Intent(app, MoviePlayerActivity::class.java)
            .putExtra("tmdb", 1288069).putExtra("type", "movie").putExtra("name", "The Last Horizon")
        val controller = Robolectric.buildActivity(MoviePlayerActivity::class.java, intent).create()
        try {
            val activity = controller.get().apply { setTheme(R.style.Theme_AKTV) }
            val content = activity.findViewById<ViewGroup>(android.R.id.content)
            val root = content.getChildAt(0) as ViewGroup
            val player = descendants(root).filterIsInstance<PlayerView>().single()
            player.visibility = View.INVISIBLE
            root.addView(ImageView(activity).apply {
                setImageResource(R.drawable.home_hero_cinematic); scaleType = ImageView.ScaleType.CENTER_CROP
                contentDescription = "Sample cinematic still"
            }, 0, FrameLayout.LayoutParams(-1, -1))
            privateField(activity, "status", TextView::class.java).text = "FROST  ·  NATIVE MEDIA3"
            privateField(activity, "spinner", View::class.java).visibility = View.GONE
            privateField(activity, "overlay", FrameLayout::class.java).visibility = View.VISIBLE
            capture(root, 960, 540, file)
        } finally { controller.destroy() }
    }

    private fun renderLive(file: File) {
        val controller = Robolectric.buildActivity(Activity::class.java).create()
        try {
            val activity = controller.get().apply { setTheme(R.style.Theme_AKTV) }
            val root = android.view.LayoutInflater.from(activity).inflate(R.layout.activity_main, null)
            activity.setContentView(root)
            root.findViewById<View>(R.id.home).visibility = View.GONE
            root.findViewById<View>(R.id.splash).visibility = View.GONE
            val layer = root.findViewById<FrameLayout>(R.id.playerLayer).apply { visibility = View.VISIBLE }
            layer.addView(ImageView(activity).apply {
                setImageResource(R.drawable.home_sports_art); scaleType = ImageView.ScaleType.CENTER_CROP
                contentDescription = "Sample sports still"
            }, 0, FrameLayout.LayoutParams(-1, -1))
            layer.findViewById<PlayerView>(R.id.playerView).visibility = View.INVISIBLE
            // Same scrim + CRT scanlines production installs.
            layer.findViewById<View>(R.id.playerScrim).apply {
                alpha = 0.72f
                background = android.graphics.drawable.LayerDrawable(arrayOf(
                    activity.getDrawable(R.drawable.scrim_player)!!, Cyber.scanlines(activity.resources, 38)))
            }
            layer.findViewById<TextView>(R.id.playerHud).apply { alpha = 1f; text = "1080P  ·  50 FPS  ·  H.264  ·  SRV 1" }
            layer.findViewById<TextView>(R.id.playerLive).apply {
                setCompoundDrawablesRelativeWithIntrinsicBounds(PulseDotDrawable(1f, Accent.ABYSS, 8f), null, null, null)
                compoundDrawablePadding = 4
            }
            layer.findViewById<View>(R.id.playerTopBar).alpha = 1f
            layer.findViewById<TextView>(R.id.playerTopTitle).text = "1 · AK Sport HD"
            layer.findViewById<View>(R.id.playerClock).alpha = 1f
            val dock = layer.findViewById<View>(R.id.playerInfoDock)
            (dock.layoutParams as FrameLayout.LayoutParams).apply {
                width = 806; setMargins(40, 0, 0, 28)
            }
            dock.layoutParams = dock.layoutParams
            layer.findViewById<View>(R.id.infoActions).visibility = View.VISIBLE
            val card = layer.findViewById<LinearLayout>(R.id.infoCard)
            card.alpha = 1f
            // The cyberpunk HUD slab production installs behind the channel block.
            card.background = HudPanelDrawable(1f)
            layer.findViewById<ImageView>(R.id.infoLogo).setImageResource(R.drawable.streamr_mark)
            layer.findViewById<TextView>(R.id.infoCat).text = "SPORTS  ·  LIVE   1 OF 18"
            layer.findViewById<TextView>(R.id.infoName).text = "AK Sport HD"
            layer.findViewById<TextView>(R.id.infoNow).apply {
                text = "India v Australia   14:00 – 18:00"
                visibility = View.VISIBLE
            }
            // Production's segmented neon programme bar, part-way through the show.
            layer.findViewById<View>(R.id.infoProgressRow).visibility = View.VISIBLE
            layer.findViewById<TextView>(R.id.infoStart).text = "14:00"
            layer.findViewById<TextView>(R.id.infoEnd).text = "18:00"
            layer.findViewById<android.widget.ProgressBar>(R.id.infoProgress).apply {
                visibility = View.VISIBLE
                progressDrawable = SegmentedBarDrawable(1f); progressTintList = null; progressBackgroundTintList = null
                progress = 430
            }
            layer.findViewById<TextView>(R.id.infoScheduleNext).apply {
                text = "Up next:  Post-match coverage"
                visibility = View.VISIBLE
            }
            layer.findViewById<View>(R.id.infoHint).visibility = View.GONE
            val primary = layer.findViewById<TextView>(R.id.infoGuideAction)
            // The real player_action selector, shown in its focused (aurora) state.
            primary.background = primary.context.getDrawable(R.drawable.player_action)!!.apply {
                state = intArrayOf(android.R.attr.state_focused); jumpToCurrentState()
            }
            primary.scaleX = 1.07f; primary.scaleY = 1.07f
            primary.setTextColor(Accent.TEXT)
            capture(root, 960, 540, file)
        } finally { controller.destroy() }
    }

    private fun <T : View> privateField(owner: Any, name: String, type: Class<T>): T {
        val field = owner.javaClass.getDeclaredField(name).apply { isAccessible = true }
        return type.cast(field.get(owner))
    }

    private fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()

    private fun capture(view: View, width: Int, height: Int, file: File) {
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, width, height)
        val previewWidth = 600
        val previewHeight = (previewWidth * height.toFloat() / width).toInt().coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(previewWidth, previewHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap); canvas.scale(previewWidth / width.toFloat(), previewHeight / height.toFloat()); view.draw(canvas)
        file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.WEBP, 76, it)) }
        bitmap.recycle()
    }
}
