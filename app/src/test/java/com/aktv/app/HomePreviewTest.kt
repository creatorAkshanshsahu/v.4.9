package com.aktv.app

import android.app.Activity
import android.content.res.ColorStateList
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
            root.findViewById<TextView>(R.id.sectionTitle).text = if (movies) "Trending in Animation" else "Live TV"
            root.findViewById<TextView>(R.id.heroTitle).text = if (movies) "The Last Horizon" else "AK News"
            root.findViewById<TextView>(R.id.heroBadge).apply {
                text = if (movies) "2026  ·  Sci-Fi  ·  ★ 6.6" else "News · English"
                visibility = View.VISIBLE
            }
            root.findViewById<View>(R.id.status).visibility = View.GONE
            root.findViewById<TextView>(R.id.heroDesc).apply {
                text = if (movies) "A story for the long way home." else "Live. Global. Independent."
                visibility = View.VISIBLE
            }
            root.findViewById<TextView>(R.id.heroDot).apply {
                visibility = View.VISIBLE
                text = if (movies) "ORIGINAL FILM" else "LIVE"
            }
            root.findViewById<TextView>(R.id.heroPlayLabel).text = if (movies) "▶   Play" else "▶   Play Live"
            root.findViewById<TextView>(R.id.hero2Title).text = if (movies) "Night Signal" else "Match Centre"
            root.findViewById<TextView>(R.id.hero2Badge).apply {
                text = if (movies) "2026  ·  Thriller  ·  ★ 8.1" else "Sports · Hindi"
            }
            root.findViewById<TextView>(R.id.hero2Play).text = if (movies) "▶   Play" else "▶   Play Live"
            root.findViewById<ImageView>(R.id.hero2Backdrop).apply {
                setImageResource(if (movies) R.drawable.home_nature_art else R.drawable.home_sports_art)
            }
            root.findViewById<ImageView>(R.id.heroLogo).apply {
                setImageResource(R.drawable.streamr_mark)
                visibility = if (movies) View.GONE else View.VISIBLE
            }
            root.findViewById<View>(R.id.heroLogoCaption).visibility = View.GONE
            root.findViewById<View>(R.id.heroOrb).background = GradientDrawable(GradientDrawable.Orientation.TL_BR,
                intArrayOf(0xFF2A3352.toInt(), 0xFF1A2340.toInt(), 0xFF141B31.toInt()))
            root.findViewById<ImageView>(R.id.heroBackdrop).apply {
                setImageResource(R.drawable.home_hero_cinematic); visibility = View.VISIBLE; alpha = 1f
            }
            root.findViewById<View>(R.id.heroPreview).visibility = View.GONE
            root.findViewById<View>(R.id.heroGlow).visibility = View.GONE
            for (id in listOf(R.id.segLive, R.id.segJio, R.id.segPortal, R.id.segFusion, R.id.segSites, R.id.segSeries, R.id.segOg, R.id.segStar)) {
                val selected = id == if (movies) R.id.segSites else R.id.segLive
                val view = root.findViewById<TextView>(id)
                view.background = Ui.pill(1f, Color.TRANSPARENT, 18f)
                view.setTextColor(if (selected) Color.WHITE else FlixColors.TAB_IDLE)
            }
            for (id in listOf(R.id.phoneLive, R.id.phoneJio, R.id.phonePortal, R.id.phoneFusion, R.id.phoneMovies, R.id.phoneSeries, R.id.phoneOg, R.id.phoneStar, R.id.phoneSearch)) {
                val selected = id == if (movies) R.id.phoneMovies else R.id.phoneLive
                val view = root.findViewById<TextView>(id)
                view.background = Ui.pill(1f, if (selected) FlixColors.PILL else Color.TRANSPARENT, 18f)
                view.setTextColor(if (selected) Color.WHITE else FlixColors.INK)
            }
            val strip = root.findViewById<LinearLayout>(R.id.flixChips)
            val sampleChips = if (movies) listOf("✦  Trending" to true, "◆  Action" to false, "♥  Romance" to false, "●  Animation" to false)
                else listOf("✦  All" to true, "♥  Favorites" to false, "●  News" to false, "▲  Sports" to false)
            sampleChips.forEach { (label, selected) ->
                strip.addView(TextView(activity).apply {
                    text = label; textSize = if (compact) 11f else 12.5f; setTextColor(if (selected) FlixColors.INK else Color.WHITE)
                    setPadding(16, 9, 16, 9)
                    background = Ui.pill(1f, if (selected) FlixColors.CHIP_SELECTED else FlixColors.CHIP, 14f)
                }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = 10 })
            }
            // The Live tab's real shape: the live row, the trending strip, then the recent row. The Sony
            // section that used to sit between them is gone; nothing here uses the browser or the site page.
            // Mirrors the app's own monogram rule, so the preview shows what a logo-less tile really shows.
            fun initials(n: String): String {
                val w = n.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
                return (if (w.size >= 2) "${w[0].first()}${w[1].first()}" else n.trim().take(2)).uppercase()
            }
            val liveTitles = listOf("Live Channels", "Trending Now", "Recently watched")
            val movieTitles = listOf("Trending movies", "Top rated movies", "Keep watching")
            val rows = root.findViewById<RecyclerView>(R.id.rows)
            rows.layoutManager = LinearLayoutManager(activity)
            rows.adapter = object : RecyclerView.Adapter<VH>() {
                override fun getItemCount() = 3
                override fun onCreateViewHolder(parent: ViewGroup, type: Int) = VH(LayoutInflater.from(activity).inflate(R.layout.item_row, parent, false))
                override fun onBindViewHolder(holder: VH, position: Int) {
                    HomeChrome.styleRow(holder.itemView, compact, 1f)
                    holder.itemView.findViewById<TextView>(R.id.rowTitle).text =
                        if (movies) movieTitles[position] else liveTitles[position]
                    val landscape = !movies && position == 1
                    val list = holder.itemView.findViewById<RecyclerView>(R.id.rowList)
                    list.layoutManager = LinearLayoutManager(activity, RecyclerView.HORIZONTAL, false)
                    list.adapter = object : RecyclerView.Adapter<VH>() {
                        override fun getItemCount() = 8
                        override fun onCreateViewHolder(parent: ViewGroup, type: Int) = VH(LayoutInflater.from(activity)
                            .inflate(if (movies) R.layout.item_poster else if (landscape) R.layout.item_trending else R.layout.item_tile, parent, false))
                        override fun onBindViewHolder(item: VH, index: Int) {
                            val tile = item.itemView
                            HomeChrome.styleTile(tile, poster = movies, compact = compact, dp = 1f, landscape = landscape)
                            // Sample tile tints harmonised onto the cyber cyan-to-magenta arc.
                            val colors = listOf("#0F2852", "#260B48", "#4A0A52", "#0B3A4D", "#3D0A40")
                                .map { Accent.normalize(android.graphics.Color.parseColor(it)) }
                            if (landscape) {
                                tile.findViewById<ImageView>(R.id.trendingImage).setImageResource(listOf(
                                    R.drawable.home_hero_cinematic, R.drawable.home_nature_art,
                                    R.drawable.home_sports_art, R.drawable.home_family_art)[index % 4])
                                tile.findViewById<TextView>(R.id.trendingTitle).text = listOf("The Last Horizon", "Night Signal", "Paper Skies", "Velocity")[index % 4]
                                tile.findViewById<TextView>(R.id.trendingMeta).text = "2026  ·  ★ 8.1"
                                tile.findViewById<TextView>(R.id.trendingRank).text = "${(index + 1).toString().padStart(2, '0')}  TRENDING"
                            } else if (movies) {
                                val titles = listOf("The Last Horizon", "Night Signal", "Paper Skies", "Velocity")
                                val ratings = listOf("★ 8.1  |  2026", "★ 7.4  |  2026", "★ 6.9  |  2025", "★ 8.8  |  2026")
                                tile.findViewById<TextView>(R.id.posterName).apply {
                                    text = titles[index % 4]; textSize = if (compact) 13f else 18f; setTextColor(Color.WHITE)
                                    background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(colors[index % 4], 0xFF12060E.toInt()))
                                }
                                tile.findViewById<ImageView>(R.id.poster).setImageResource(listOf(
                                    R.drawable.home_hero_cinematic, R.drawable.home_nature_art,
                                    R.drawable.home_sports_art, R.drawable.home_family_art)[index % 4])
                                tile.findViewById<TextView>(R.id.posterTitle).text = titles[index % 4]
                                tile.findViewById<TextView>(R.id.posterMeta).text = ratings[index % 4]
                                tile.findViewById<TextView>(R.id.posterRank).apply {
                                    visibility = if (position == 0) View.VISIBLE else View.GONE
                                    text = (index + 1).toString()
                                }
                            } else {
                                val cardColor = colors[index % 5]
                                tile.background = GradientDrawable(GradientDrawable.Orientation.TL_BR,
                                    intArrayOf(Ui.mix(FlixColors.CARD, cardColor, 0.22f), FlixColors.CARD)).apply {
                                    cornerRadius = 20f
                                    if (position == 0 && index == 0) setStroke(3, FlixColors.PILL)
                                }
                                tile.clipToOutline = true
                                tile.findViewById<ImageView>(R.id.logo).visibility = View.GONE
                                tile.findViewById<TextView>(R.id.mono).apply {
                                    visibility = View.VISIBLE
                                    text = initials(listOf("News Now", "Match Centre", "Fresh Stories", "Family Time", "Hits")[index % 5])
                                    setTextColor(Color.WHITE)
                                    textSize = if (compact) 18f else 23f
                                    background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Ui.mix(cardColor, Color.WHITE, 0.16f)) }
                                }
                                tile.findViewById<TextView>(R.id.name).apply {
                                    text = listOf("News Now", "Match Centre", "Fresh Stories", "Family Time", "Hits")[index % 5]
                                    setTextColor(FlixColors.INK)
                                }
                                tile.findViewById<TextView>(R.id.nowCaption).apply {
                                    text = listOf("Global headlines", "Match centre", "Fresh stories", "Family time", "Now playing")[index % 5]
                                    setTextColor(FlixColors.MUTED)
                                }
                                tile.findViewById<TextView>(R.id.liveBadge).apply {
                                    text = "WORKING"; setTextColor(Color.WHITE)
                                    background = GradientDrawable().apply { cornerRadius = 7f; setColor(0xFF16885D.toInt()) }
                                }
                                tile.findViewById<View>(R.id.healthDot).backgroundTintList = ColorStateList.valueOf(FlixColors.RED)
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
