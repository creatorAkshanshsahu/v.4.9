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
            root.findViewById<TextView>(R.id.sectionKicker).apply {
                text = if (movies) "YOUR NEXT MOVIE NIGHT" else "LIVE, RIGHT NOW"
                setTextColor(if (movies) Accent.GOLD else Accent.SIGNAL)
            }
            root.findViewById<TextView>(R.id.librarySummary).text = "SAMPLE CONTENT · UI PREVIEW"
            root.findViewById<TextView>(R.id.homeClock).text = "8:45 PM"
            root.findViewById<TextView>(R.id.heroTitle).text = if (movies) "The Last Horizon" else "AK News"
            root.findViewById<TextView>(R.id.heroBadge).apply {
                text = if (movies) "MOVIE  /  ON DEMAND" else "NEWS  /  LIVE"
                setPadding(9, 3, 9, 3)
                background = Ui.pill(1f, 0x2424D3D0, 16f).apply { setStroke(1, Accent.SIGNAL) }
            }
            root.findViewById<TextView>(R.id.heroSubheading).text = if (movies) "Your next movie night starts here." else "A clearer view of what’s next."
            root.findViewById<TextView>(R.id.heroDesc).text = if (movies) "A story for the long way home." else "Live. Global. Independent. Breaking news, bigger perspectives."
            root.findViewById<View>(R.id.status).visibility = View.GONE
            root.findViewById<View>(R.id.heroDesc).visibility = View.VISIBLE
            root.findViewById<TextView>(R.id.heroDot).apply {
                visibility = View.VISIBLE
                text = if (movies) "FEATURED MOVIE" else "●  LIVE NOW"
                setTextColor(if (movies) Accent.GOLD else Accent.SIGNAL)
            }
            root.findViewById<ImageView>(R.id.heroLogo).setImageResource(if (movies) R.drawable.brand_aktv_user else R.drawable.brand_ak_mark)
            root.findViewById<TextView>(R.id.heroLogoCaption).visibility = if (movies) View.GONE else View.VISIBLE
            root.findViewById<View>(R.id.heroOrb).background = GradientDrawable(GradientDrawable.Orientation.TL_BR,
                intArrayOf(0xFF091521.toInt(), 0xFF0C1D2A.toInt(), 0xFF07111B.toInt()))
            root.findViewById<ImageView>(R.id.heroBackdrop).apply { setImageDrawable(null); visibility = View.GONE; alpha = 0f }
            root.findViewById<View>(R.id.heroPreview).visibility = View.GONE
            root.findViewById<View>(R.id.heroGlow).visibility = View.VISIBLE
            val chips = root.findViewById<LinearLayout>(R.id.heroChips)
            val heroActions = if (movies) listOf("▶   Details") else listOf("▶   Play Live", "ⓘ   Details")
            heroActions.forEachIndexed { index, label ->
                chips.addView(TextView(activity).apply {
                    text = label; textSize = if (compact) 11f else 13f; setTextColor(if (index == 0) Accent.ABYSS else Accent.TEXT)
                    setPadding(16, if (compact) 7 else 10, 18, if (compact) 7 else 10)
                    background = Ui.pill(1f, if (index == 0) Color.WHITE else 0xB30A111B.toInt(), 12f).apply {
                        setStroke(if (index == 0) 0 else 1, if (index == 0) Color.TRANSPARENT else Accent.SIGNAL)
                    }
                }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = 8 })
            }
            for (id in listOf(R.id.segLive, R.id.segV2, R.id.segSites, R.id.segSeries, R.id.searchBtn)) {
                val selected = id == if (movies) R.id.segSites else R.id.segLive
                val view = root.findViewById<TextView>(id)
                view.background = Ui.pill(1f, if (selected) 0x2427E4DF else Color.TRANSPARENT, 12f).apply {
                    if (selected) setStroke(2, Accent.SIGNAL)
                }
                view.setTextColor(if (selected) Accent.TEXT else Accent.DIM)
                view.compoundDrawableTintList = android.content.res.ColorStateList.valueOf(if (selected) Accent.SIGNAL else Accent.DIM)
            }
            for (id in listOf(R.id.phoneLive, R.id.phoneV2, R.id.phoneMovies, R.id.phoneSeries, R.id.phoneSearch)) {
                val selected = id == if (movies) R.id.phoneMovies else R.id.phoneLive
                val view = root.findViewById<TextView>(id)
                view.background = Ui.pill(1f, if (selected) 0x2427E4DF else Color.TRANSPARENT, 20f).apply {
                    if (selected) setStroke(2, Accent.SIGNAL)
                }
                view.setTextColor(if (selected) Accent.TEXT else Accent.DIM)
            }
            val rows = root.findViewById<RecyclerView>(R.id.rows)
            rows.layoutManager = LinearLayoutManager(activity)
            rows.adapter = object : RecyclerView.Adapter<VH>() {
                override fun getItemCount() = 3
                override fun onCreateViewHolder(parent: ViewGroup, type: Int) = VH(LayoutInflater.from(activity).inflate(R.layout.item_row, parent, false))
                override fun onBindViewHolder(holder: VH, position: Int) {
                    HomeChrome.styleRow(holder.itemView, compact, 1f)
                    holder.itemView.findViewById<TextView>(R.id.rowTitle).text = if (movies) listOf("Trending movies", "Top rated movies", "Keep watching")[position]
                        else listOf("Live Channels", "Trending on AK", "Popular stories")[position]
                    val list = holder.itemView.findViewById<RecyclerView>(R.id.rowList)
                    list.layoutManager = LinearLayoutManager(activity, RecyclerView.HORIZONTAL, false)
                    list.adapter = object : RecyclerView.Adapter<VH>() {
                        override fun getItemCount() = 8
                        override fun onCreateViewHolder(parent: ViewGroup, type: Int) = VH(LayoutInflater.from(activity)
                            .inflate(if (movies) R.layout.item_poster else if (!movies && position == 1) R.layout.item_trending else R.layout.item_tile, parent, false))
                        override fun onBindViewHolder(item: VH, index: Int) {
                            val tile = item.itemView
                            val landscape = !movies && position == 1
                            HomeChrome.styleTile(tile, poster = movies, compact = compact, dp = 1f, landscape = landscape)
                            val colors = listOf(0xFF244D56.toInt(), 0xFF40385A.toInt(), 0xFF5A4532.toInt(), 0xFF294950.toInt(), 0xFF4A2E54.toInt())
                            if (landscape) {
                                tile.findViewById<ImageView>(R.id.trendingImage).setImageResource(listOf(
                                    R.drawable.home_hero_cinematic, R.drawable.home_nature_art,
                                    R.drawable.home_sports_art, R.drawable.home_family_art)[index % 4])
                                tile.findViewById<TextView>(R.id.trendingTitle).text = listOf("The Last Horizon", "Night Signal", "Paper Skies", "Velocity")[index % 4]
                                tile.findViewById<TextView>(R.id.trendingMeta).text = "2026  ·  ★ 8.1"
                                tile.findViewById<TextView>(R.id.trendingRank).text = "${(index + 1).toString().padStart(2, '0')}  TRENDING"
                            } else if (movies) {
                                val titles = listOf("The Last Horizon", "Night Signal", "Paper Skies", "Velocity")
                                tile.background = Ui.pill(1f, Color.TRANSPARENT, 14f).apply {
                                    if (position == 0 && index == 0) setStroke(3, Accent.SIGNAL)
                                }
                                tile.findViewById<TextView>(R.id.posterName).apply {
                                    text = titles[index % 4]; textSize = if (compact) 13f else 18f; setTextColor(Color.WHITE)
                                    background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(colors[index % 4], 0xFF09131E.toInt()))
                                }
                                tile.findViewById<ImageView>(R.id.poster).setImageResource(listOf(
                                    R.drawable.home_hero_cinematic, R.drawable.home_nature_art,
                                    R.drawable.home_sports_art, R.drawable.home_family_art)[index % 4])
                                tile.findViewById<TextView>(R.id.posterTitle).text = titles[index % 4]
                                tile.findViewById<TextView>(R.id.posterMeta).text = "Sample artwork"
                            } else {
                                tile.background = Ui.pill(1f, Ui.mix(Accent.SURFACE, colors[index % 5], 0.35f), 14f).apply {
                                    if (position == 0 && index == 0) setStroke(3, Accent.SIGNAL)
                                }
                                tile.findViewById<ImageView>(R.id.channelArt).apply {
                                    setImageResource(listOf(R.drawable.home_hero_cinematic, R.drawable.home_sports_art,
                                        R.drawable.home_nature_art, R.drawable.home_family_art, R.drawable.home_music_art)[index % 5])
                                    visibility = View.VISIBLE
                                }
                                tile.findViewById<ImageView>(R.id.logo).visibility = View.GONE
                                tile.findViewById<TextView>(R.id.mono).apply {
                                    visibility = View.VISIBLE; text = "AK"
                                    setTextColor(0xFFB0F1E8.toInt()); textSize = if (compact) 18f else 23f
                                }
                                tile.findViewById<TextView>(R.id.name).text = listOf("AK News", "AK Sport", "AK Reality", "AK Kids", "AK Hits")[index % 5]
                                tile.findViewById<TextView>(R.id.nowCaption).text = listOf("Global headlines", "Match centre", "Fresh stories", "Family time", "Now playing")[index % 5]
                            }
                        }
                    }
                }
            }
            root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, width, height)
            val previewWidth = 600
            val previewHeight = (previewWidth * height.toFloat() / width).toInt().coerceAtLeast(1)
            val bitmap = Bitmap.createBitmap(previewWidth, previewHeight, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap); canvas.scale(previewWidth / width.toFloat(), previewHeight / height.toFloat()); root.draw(canvas)
            val folder = File(System.getProperty("aktv.ui.previewDir", "build/reports/ui-preview")).apply { mkdirs() }
            File(folder, "$name.webp").outputStream().use { org.junit.Assert.assertTrue(bitmap.compress(Bitmap.CompressFormat.WEBP, 72, it)) }
            bitmap.recycle()
        } finally { controller.destroy() }
    }
}
