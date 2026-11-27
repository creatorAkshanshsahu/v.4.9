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
                text = if (movies) "MOVIE  /  ON DEMAND" else "NEWS  /  LIVE"
                setPadding(9, 3, 9, 3)
                background = GlassDrawable(1f, 100f, restAlpha = 0.14f, shine = false)
            }
            root.findViewById<TextView>(R.id.heroSubheading).text = if (movies) "Your next movie night starts here." else "A clearer view of what’s next."
            root.findViewById<TextView>(R.id.heroDesc).text = if (movies) "A story for the long way home." else "Live. Global. Independent. Breaking news, bigger perspectives."
            root.findViewById<View>(R.id.status).visibility = View.GONE
            root.findViewById<View>(R.id.heroDesc).visibility = View.VISIBLE
            root.findViewById<TextView>(R.id.heroDot).apply {
                visibility = View.VISIBLE
                text = if (movies) "FEATURED MOVIE" else "LIVE NOW"
                setTextColor(if (movies) Accent.ROSE_BRIGHT else Accent.LIVE)
                if (!movies) setCompoundDrawablesRelativeWithIntrinsicBounds(PulseDotDrawable(1f, Accent.LIVE, 12f), null, null, null)
            }
            root.findViewById<ImageView>(R.id.heroLogo).apply {
                setImageResource(R.drawable.aktv_logo)
                visibility = if (movies) View.GONE else View.VISIBLE
            }
            root.findViewById<TextView>(R.id.heroLogoCaption).visibility = if (movies) View.GONE else View.VISIBLE
            root.findViewById<View>(R.id.heroOrb).background = GradientDrawable(GradientDrawable.Orientation.TL_BR,
                intArrayOf(0x00060812, 0x22060812, 0x66060812))
            root.findViewById<ImageView>(R.id.heroBackdrop).apply {
                setImageResource(R.drawable.home_hero_cinematic); visibility = View.VISIBLE; alpha = 1f
            }
            root.findViewById<View>(R.id.heroPreview).visibility = View.GONE
            root.findViewById<View>(R.id.heroGlow).visibility = View.VISIBLE
            val chips = root.findViewById<LinearLayout>(R.id.heroChips)
            val heroActions = if (movies) listOf("▶   More info") else listOf("▶   Play Live", "ⓘ   Details")
            heroActions.forEachIndexed { index, label ->
                chips.addView(TextView(activity).apply {
                    text = label; textSize = if (compact) 11f else 13f; setTextColor(if (index == 0) Accent.INK else Accent.TEXT)
                    setPadding(20, if (compact) 8 else 11, 22, if (compact) 8 else 11)
                    background = if (index == 0) Glass.primary(1f) else Glass.pill(1f, rest = 0.14f)
                }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = 8 })
            }
            for (id in listOf(R.id.segLive, R.id.segV2, R.id.segSites, R.id.segSeries, R.id.searchBtn)) {
                val selected = id == if (movies) R.id.segSites else R.id.segLive
                val view = root.findViewById<TextView>(id)
                // Same glass the app uses; the open tab shows its "active" look.
                view.background = Glass.pill(1f, 18f, rest = 0f).apply { shine = false; setLook(false, selected) }
                view.setTextColor(if (selected) Accent.TEXT else Accent.DIM)
                view.compoundDrawableTintList = android.content.res.ColorStateList.valueOf(if (selected) Accent.ROSE_BRIGHT else Accent.DIM)
            }
            for (id in listOf(R.id.phoneLive, R.id.phoneV2, R.id.phoneMovies, R.id.phoneSeries, R.id.phoneSearch)) {
                val selected = id == if (movies) R.id.phoneMovies else R.id.phoneLive
                val view = root.findViewById<TextView>(id)
                view.background = Glass.pill(1f, rest = 0.06f).apply { setLook(false, selected) }
                view.setTextColor(if (selected) Accent.TEXT else Accent.DIM)
            }
            // The Live tab's real shape: generic channels, then the Sony shelf built by the shipped
            // selector, then trending. The names below are the ones the live feed actually serves.
            val sonyCatalogue = listOf(
                "SET HD", "Sony SAB HD", "Sony SAB SD", "Sony Marathi SD", "Sony Pal", "Sony aath",
                "Sony Yay Hindi", "Sony Max HD", "Sony MAX1", "Sony Wah", "Sony MAX2", "Sony Pix HD",
                "Sony Ten 1 HD", "Sony Ten 2 HD", "Sony Ten 3 Hindi HD", "Sony BBC Earth HD"
            ).mapIndexed { index, name -> MainActivity.Channel((3400 + index).toString(), name, "Sony", "") }
            val sonyShelf = LiveCollections.channels(LiveCollections.SONY, sonyCatalogue)
            // Mirrors the app's own monogram rule, so the preview shows what a logo-less tile really shows.
            fun initials(n: String): String {
                val w = n.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
                return (if (w.size >= 2) "${w[0].first()}${w[1].first()}" else n.trim().take(2)).uppercase()
            }
            val liveTitles = listOf("Live Channels", "Sony Live", "Trending on AK")
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
                            val colors = listOf("#FF4F7B", "#B65CFF", "#6366FF", "#FF8A5C", "#E040FB")
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
                                if (position == 0 && index == 0) { tile.scaleX = 1.07f; tile.scaleY = 1.07f
                                    (tile as android.widget.FrameLayout).foreground?.state = intArrayOf(android.R.attr.state_focused)
                                    tile.background?.state = intArrayOf(android.R.attr.state_focused) }
                                tile.findViewById<TextView>(R.id.posterName).apply {
                                    text = titles[index % 4]; textSize = if (compact) 13f else 18f; setTextColor(Color.WHITE)
                                    background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(colors[index % 4], 0xFF12060E.toInt()))
                                }
                                tile.findViewById<ImageView>(R.id.poster).setImageResource(listOf(
                                    R.drawable.home_hero_cinematic, R.drawable.home_nature_art,
                                    R.drawable.home_sports_art, R.drawable.home_family_art)[index % 4])
                                tile.findViewById<TextView>(R.id.posterTitle).text = titles[index % 4]
                                tile.findViewById<TextView>(R.id.posterMeta).text = "Sample artwork"
                            } else {
                                val channel = if (isSony) sonyShelf.getOrNull(index) else null
                                val cardColor = colors[index % 5]
                                val focusedTile = position == 0 && index == 0
                                tile.background = Glass.card(1f, 18f, cardColor).apply {
                                    restAlpha = 0.16f; activeAlpha = 0.22f; focusAlpha = 0.30f; accent = cardColor
                                    setLook(focusedTile)
                                }
                                if (focusedTile) { tile.scaleX = 1.06f; tile.scaleY = 1.06f }
                                tile.clipToOutline = true
                                tile.findViewById<ImageView>(R.id.logo).visibility = View.GONE
                                tile.findViewById<TextView>(R.id.mono).apply {
                                    visibility = View.VISIBLE
                                    // The Sony row shows the real channel names the shelf selected, so the
                                    // preview proves the shelf renders - not just that a row exists.
                                    text = if (channel != null) initials(channel.name) else "AK"
                                    setTextColor(Color.WHITE)
                                    textSize = if (compact) 13f else 23f
                                    background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Ui.mix(cardColor, Color.WHITE, 0.25f), Accent.partner(cardColor))).apply {
                                        shape = GradientDrawable.OVAL; setStroke(1, Ui.alpha(Color.WHITE, 0.45f))
                                    }
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
