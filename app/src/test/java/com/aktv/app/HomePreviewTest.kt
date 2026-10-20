package com.aktv.app

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Native Android layout previews with labelled sample content, no network or playback. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HomePreviewTest {
    @Test @Config(qualifiers = "w960dp-h540dp-land-mdpi")
    fun tvHome() = render("home-tv", 960, 540, false)
    @Test @Config(qualifiers = "w800dp-h360dp-land-mdpi")
    fun phoneHome() = render("home-phone", 800, 360, false)
    @Test @Config(qualifiers = "w960dp-h540dp-land-mdpi")
    fun movieHome() = render("home-movies", 960, 540, true)

    private fun render(name: String, width: Int, height: Int, movies: Boolean) {
        val controller = Robolectric.buildActivity(Activity::class.java).create()
        try {
            val activity = controller.get(); activity.setTheme(R.style.Theme_AKTV)
            val root = LayoutInflater.from(activity).inflate(R.layout.home_content, null)
            activity.setContentView(root)
            val compact = height < 420
            HomeChrome.apply(root, width, height, 1f, movies)
            root.findViewById<TextView>(R.id.sectionTitle).text = if (movies) "Movies" else "Live TV"
            root.findViewById<TextView>(R.id.sectionKicker).text = if (movies) "FIND YOUR NEXT FAVORITE" else "YOUR FRONT ROW"
            root.findViewById<TextView>(R.id.librarySummary).text = "SAMPLE CONTENT · UI PREVIEW"
            root.findViewById<TextView>(R.id.homeClock).text = "8:45 PM"
            root.findViewById<TextView>(R.id.heroTitle).text = if (movies) "Make it a movie night." else "Star Sports 1 Hindi HD"
            root.findViewById<TextView>(R.id.heroBadge).text = if (movies) "MOVIES  /  SERIES" else "SPORTS  /  HINDI"
            root.findViewById<TextView>(R.id.heroDesc).text = if (movies) "Explore the stories you love. All in one place." else "Your front-row seat to the channels you love."
            root.findViewById<View>(R.id.status).visibility = View.GONE
            if (movies) root.findViewById<View>(R.id.heroDesc).visibility = View.GONE
            root.findViewById<View>(R.id.heroDot).visibility = if (movies) View.GONE else View.VISIBLE
            root.findViewById<ImageView>(R.id.heroLogo).setImageResource(R.drawable.ic_brand)
            root.findViewById<View>(R.id.heroOrb).background = GradientDrawable(GradientDrawable.Orientation.TL_BR,
                intArrayOf(0xFF273E7A.toInt(), 0xFF273253.toInt(), 0xFF10182B.toInt()))
            val chips = root.findViewById<LinearLayout>(R.id.heroChips)
            val heroActions = if (movies) listOf("▶   Explore title") else listOf("▶   Watch live", "+   My favorites")
            heroActions.forEachIndexed { index, label ->
                chips.addView(TextView(activity).apply {
                    text = label; textSize = if (compact) 11f else 13f; setTextColor(if (index == 0) Accent.ABYSS else Accent.TEXT)
                    setPadding(16, if (compact) 7 else 10, 18, if (compact) 7 else 10)
                    background = Ui.pill(1f, if (index == 0) Accent.SIGNAL else 0xB31C2740.toInt(), 10f)
                }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = 8 })
            }
            for (id in listOf(R.id.segLive, R.id.segV2, R.id.segSites, R.id.segSeries, R.id.searchBtn)) {
                val selected = id == if (movies) R.id.segSites else R.id.segLive
                val view = root.findViewById<TextView>(id)
                view.background = Ui.pill(1f, if (selected) 0xFF202A52.toInt() else Color.TRANSPARENT, 12f)
                view.setTextColor(if (selected) Accent.SIGNAL else Accent.DIM)
                view.compoundDrawableTintList = android.content.res.ColorStateList.valueOf(if (selected) Accent.SIGNAL else Accent.DIM)
            }
            for (id in listOf(R.id.phoneLive, R.id.phoneV2, R.id.phoneMovies, R.id.phoneSeries, R.id.phoneSearch)) {
                val selected = id == if (movies) R.id.phoneMovies else R.id.phoneLive
                val view = root.findViewById<TextView>(id)
                view.background = Ui.pill(1f, if (selected) 0xFF202A52.toInt() else Color.TRANSPARENT, 20f)
                view.setTextColor(if (selected) Accent.SIGNAL else Accent.DIM)
            }
            val rows = root.findViewById<RecyclerView>(R.id.rows)
            rows.layoutManager = LinearLayoutManager(activity)
            rows.adapter = object : RecyclerView.Adapter<VH>() {
                override fun getItemCount() = 3
                override fun onCreateViewHolder(parent: ViewGroup, type: Int) = VH(LayoutInflater.from(activity).inflate(R.layout.item_row, parent, false))
                override fun onBindViewHolder(holder: VH, position: Int) {
                    HomeChrome.styleRow(holder.itemView, compact, 1f)
                    holder.itemView.findViewById<TextView>(R.id.rowTitle).text = if (movies) listOf("An evening of discovery", "Popular stories", "Keep watching")[position]
                        else listOf("Your favorites", "Sports & entertainment", "Around the world")[position]
                    val list = holder.itemView.findViewById<RecyclerView>(R.id.rowList)
                    list.layoutManager = LinearLayoutManager(activity, RecyclerView.HORIZONTAL, false)
                    list.adapter = object : RecyclerView.Adapter<VH>() {
                        override fun getItemCount() = 8
                        override fun onCreateViewHolder(parent: ViewGroup, type: Int) = VH(LayoutInflater.from(activity)
                            .inflate(if (movies) R.layout.item_poster else R.layout.item_tile, parent, false))
                        override fun onBindViewHolder(item: VH, index: Int) {
                            val tile = item.itemView
                            HomeChrome.styleTile(tile, movies, compact, 1f)
                            val colors = listOf(0xFF244D56.toInt(), 0xFF40385A.toInt(), 0xFF5A4532.toInt(), 0xFF294950.toInt())
                            if (movies) {
                                val titles = listOf("The Last Horizon", "Night Signal", "Paper Skies", "Velocity")
                                tile.findViewById<TextView>(R.id.posterName).apply {
                                    text = titles[index % 4]; textSize = if (compact) 13f else 18f; setTextColor(Color.WHITE)
                                    background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(colors[index % 4], 0xFF09131E.toInt()))
                                }
                                tile.findViewById<TextView>(R.id.posterTitle).text = titles[index % 4]
                                tile.findViewById<TextView>(R.id.posterMeta).text = "Sample artwork"
                            } else {
                                tile.background = Ui.pill(1f, Ui.mix(Accent.SURFACE, colors[index % 4], 0.35f), 14f)
                                tile.findViewById<ImageView>(R.id.logo).visibility = View.GONE
                                tile.findViewById<TextView>(R.id.mono).apply {
                                    visibility = View.VISIBLE; text = listOf("SS", "YAY", "ZEE", "N18")[index % 4]
                                    setTextColor(0xFFB0F1E8.toInt()); textSize = if (compact) 18f else 23f
                                }
                                tile.findViewById<TextView>(R.id.name).text = listOf("Star Sports 1 Hindi HD", "Sony YAY Hindi", "Zee Cinema HD", "News18 India")[index % 4]
                            }
                        }
                    }
                }
            }
            root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, width, height)
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            root.draw(Canvas(bitmap))
            val folder = File(System.getProperty("aktv.ui.previewDir", "build/reports/ui-preview")).apply { mkdirs() }
            File(folder, "$name.webp").outputStream().use { org.junit.Assert.assertTrue(bitmap.compress(Bitmap.CompressFormat.WEBP, 78, it)) }
            bitmap.recycle()
        } finally { controller.destroy() }
    }
}
