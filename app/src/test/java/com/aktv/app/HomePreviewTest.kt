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
                setTextColor(Accent.SIGNAL)
            }
            root.findViewById<TextView>(R.id.librarySummary).text = "SAMPLE CONTENT · UI PREVIEW"
            root.findViewById<TextView>(R.id.homeClock).text = "8:45 PM"
            root.findViewById<TextView>(R.id.heroTitle).text = if (movies) "The Last Horizon" else "AK News"
            root.findViewById<TextView>(R.id.heroBadge).apply {
                text = if (movies) "2026 · Sci-Fi · ★ 6.6" else "News · English"
                setPadding(0, 0, 0, 0)
                background = null
                setTextColor(Accent.DIM)
            }
            root.findViewById<View>(R.id.heroSubheading).visibility = View.GONE
            root.findViewById<TextView>(R.id.heroDesc).text = if (movies) "A story for the long way home." else "Live. Global. Independent. Breaking news, bigger perspectives."
            root.findViewById<View>(R.id.status).visibility = View.GONE
            root.findViewById<View>(R.id.heroDesc).visibility = View.VISIBLE
            root.findViewById<TextView>(R.id.heroDot).apply {
                visibility = View.VISIBLE
                text = if (movies) "ORIGINAL FILM" else "LIVE"
                setTextColor(Accent.AMBER)
            }
            root.findViewById<ImageView>(R.id.heroLogo).apply {
                setImageResource(R.drawable.aktv_mark_cinematic)
                visibility = if (movies) View.GONE else View.VISIBLE
            }
            root.findViewById<View>(R.id.heroLogoCaption).visibility = View.GONE
            root.findViewById<View>(R.id.heroOrb).background = GradientDrawable(GradientDrawable.Orientation.TL_BR,
                intArrayOf(0xFF1A070D.toInt(), 0xFF2A0A14.toInt(), 0xFF07040A.toInt()))
            root.findViewById<ImageView>(R.id.heroBackdrop).apply {
                setImageResource(R.drawable.home_hero_cinematic); visibility = View.VISIBLE; alpha = 1f
            }
            root.findViewById<View>(R.id.heroPreview).visibility = View.GONE
            root.findViewById<View>(R.id.heroGlow).visibility = View.GONE
            val chips = root.findViewById<LinearLayout>(R.id.heroChips)
            val heroActions = if (movies) listOf("▶   Play", "More Info") else listOf("▶   Play Live", "Details")
            heroActions.forEachIndexed { index, label ->
                chips.addView(TextView(activity).apply {
                    text = label; textSize = if (compact) 11f else 13f; setTextColor(if (index == 0) Accent.ABYSS else Accent.TEXT)
                    setPadding(22, if (compact) 7 else 11, 22, if (compact) 7 else 11)
                    background = Ui.pill(1f, if (index == 0) Color.WHITE else 0x33FFFFFF, 22f)
                }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = 12 })
            }
            for (id in listOf(R.id.segLive, R.id.segJio, R.id.segSites, R.id.segSeries, R.id.searchBtn)) {
                val selected = id == if (movies) R.id.segSites else R.id.segLive
                val view = root.findViewById<TextView>(id)
                view.background = Ui.pill(1f, if (selected) Accent.TEXT else Color.TRANSPARENT, 18f)
                view.setTextColor(if (selected) Accent.ABYSS else Accent.DIM)
            }
            for (id in listOf(R.id.phoneLive, R.id.phoneJio, R.id.phoneMovies, R.id.phoneSeries, R.id.phoneSearch)) {
                val selected = id == if (movies) R.id.phoneMovies else R.id.phoneLive
                val view = root.findViewById<TextView>(id)
                view.background = Ui.pill(1f, if (selected) Accent.TEXT else 0x33FFFFFF, 22f).apply {
                    if (selected) setStroke(2, Accent.SIGNAL)
                }
                view.setTextColor(if (selected) Accent.ABYSS else Accent.DIM)
            }
            // The Live tab's real shape: generic channels, then the one Sony section built by the shipped
            // selector, then trending. The names below are the ones the live feed actually serves.
            val sonyCatalogue = listOf(
                "SET HD", "Sony SAB HD", "Sony SAB SD", "Sony Marathi SD", "Sony Pal", "Sony aath",
                "Sony Yay Hindi", "Sony Max HD", "Sony MAX1", "Sony Wah", "Sony MAX2", "Sony Pix HD",
                "Sony Ten 1 HD", "Sony Ten 2 HD", "Sony Ten 3 Hindi HD", "Sony BBC Earth HD"
            ).mapIndexed { index, name -> MainActivity.Channel((3400 + index).toString(), name, "Sony", "") }
            // The shipped selector for the one Sony section: the page's tiles bound to the playlist.
            val sonyShelf = SonyPage.channels(sonyCatalogue.map { it.copy(jio = true) })
            // Mirrors the app's own monogram rule, so the preview shows what a logo-less tile really shows.
            fun initials(n: String): String {
                val w = n.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
                return (if (w.size >= 2) "${w[0].first()}${w[1].first()}" else n.trim().take(2)).uppercase()
            }
            val liveTitles = listOf("Live Channels", SonyPage.TITLE, "Trending on AK")
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
                    val isSony = !movies && position == 1
                    val landscape = !movies && position == 2
                    val list = holder.itemView.findViewById<RecyclerView>(R.id.rowList)
                    list.layoutManager = LinearLayoutManager(activity, RecyclerView.HORIZONTAL, false)
                    list.adapter = object : RecyclerView.Adapter<VH>() {
                        override fun getItemCount() = if (isSony) sonyShelf.size else 8
                        override fun onCreateViewHolder(parent: ViewGroup, type: Int) = VH(LayoutInflater.from(activity)
                            .inflate(if (movies) R.layout.item_poster else if (landscape) R.layout.item_trending else R.layout.item_tile, parent, false))
                        override fun onBindViewHolder(item: VH, index: Int) {
                            val tile = item.itemView
                            HomeChrome.styleTile(tile, poster = movies, compact = compact, dp = 1f, landscape = landscape)
                            // Sample tile tints sampled through the real harmoniser, so the preview shows the
                            // crimson identity instead of the retired teal/violet palette.
                            val colors = listOf("#3F0A14", "#2B0710", "#55202A", "#4A0812", "#8C0F1C")
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
                                tile.background = Ui.pill(1f, Color.TRANSPARENT, 14f).apply {
                                    if (position == 0 && index == 0) setStroke(3, Accent.SIGNAL)
                                }
                                tile.findViewById<TextView>(R.id.posterName).apply {
                                    text = titles[index % 4]; textSize = if (compact) 13f else 18f; setTextColor(Color.WHITE)
                                    background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(colors[index % 4], 0xFF12060E.toInt()))
                                }
                                tile.findViewById<ImageView>(R.id.poster).setImageResource(listOf(
                                    R.drawable.home_hero_cinematic, R.drawable.home_nature_art,
                                    R.drawable.home_sports_art, R.drawable.home_family_art)[index % 4])
                                tile.findViewById<TextView>(R.id.posterTitle).text = titles[index % 4]
                                tile.findViewById<TextView>(R.id.posterMeta).text = if (movies) "MOVIE" else "SERIES"
                            } else {
                                val channel = if (isSony) sonyShelf.getOrNull(index) else null
                                val cardColor = colors[index % 5]
                                tile.background = GradientDrawable(GradientDrawable.Orientation.TL_BR,
                                    intArrayOf(cardColor, Ui.mix(cardColor, Accent.ABYSS, 0.48f))).apply {
                                    cornerRadius = 14f
                                    if (position == 0 && index == 0) setStroke(3, Accent.SIGNAL)
                                }
                                tile.clipToOutline = true
                                tile.findViewById<ImageView>(R.id.logo).visibility = View.GONE
                                tile.findViewById<TextView>(R.id.mono).apply {
                                    visibility = View.VISIBLE
                                    // The Sony row shows the real channel names the section selected, so the
                                    // preview proves the section renders - not just that a row exists.
                                    text = if (channel != null) initials(channel.name) else "AK"
                                    setTextColor(Accent.GLOSS_HI)
                                    textSize = if (compact) 18f else 23f
                                    background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Ui.mix(cardColor, Color.WHITE, 0.16f)) }
                                }
                                val names = listOf("AK News", "AK Sport", "AK Reality", "AK Kids", "AK Hits")
                                tile.findViewById<TextView>(R.id.name).text = channel?.name ?: names[index % 5]
                                tile.findViewById<TextView>(R.id.nowCaption).text = if (channel != null) "SONY  ·  LIVE"
                                    else listOf("Global headlines", "Match centre", "Fresh stories", "Family time", "Now playing")[index % 5]
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
