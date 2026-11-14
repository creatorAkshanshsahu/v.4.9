package com.aktv.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.*
import android.view.animation.DecelerateInterpolator
import android.widget.*
import androidx.core.content.res.ResourcesCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import org.json.JSONObject
import java.util.concurrent.Executors

/** Netflix / Apple TV style details page: backdrop, logo, facts, Play, and for series the seasons and episodes. */
class DetailActivity : Activity() {
    companion object {
        fun open(c: Context, t: Movies.Title, provider: String? = null) {
            c.startActivity(Intent(c, DetailActivity::class.java).putExtra("type", t.type).putExtra("imdb", t.imdb)
                .putExtra("tmdb", t.tmdb).putExtra("name", t.name).putExtra("poster", t.poster).putExtra("bg", t.background)
                .putExtra("logo", t.logo).putExtra("year", t.year).putExtra("rating", t.rating).putExtra("runtime", t.runtime)
                .putExtra("desc", t.description).putExtra("genres", t.genres.joinToString("|"))
                .putExtra("cast", t.cast.joinToString("|").take(900)).putExtra("trailerKey", t.trailerKey)
                .apply { if (!provider.isNullOrBlank()) putExtra("provider", provider) })
        }
    }

    private val io = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())
    private val dp by lazy { resources.displayMetrics.density }
    private val fReg by lazy { ResourcesCompat.getFont(this, R.font.sora_regular) ?: Typeface.DEFAULT }
    private val fSemi by lazy { ResourcesCompat.getFont(this, R.font.sora_semibold) ?: Typeface.DEFAULT_BOLD }
    private val fBold by lazy { ResourcesCompat.getFont(this, R.font.sora_bold) ?: Typeface.DEFAULT_BOLD }
    private val fBody by lazy { ResourcesCompat.getFont(this, R.font.manrope) ?: Typeface.DEFAULT }
    private val prefs by lazy { getSharedPreferences("aktv", Context.MODE_PRIVATE) }
    private val pageAccent get() = if (title.isSeries) Accent.BRAND_LIGHT else Accent.BRAND

    private lateinit var title: Movies.Title
    private var episodes = listOf<Movies.Episode>(); private var seasons = listOf<Int>(); private var season = 1
    private lateinit var backdrop: ImageView; private lateinit var logo: ImageView; private lateinit var nameTv: TextView
    private lateinit var facts: TextView; private lateinit var desc: TextView; private lateinit var playBtn: TextView
    private lateinit var metadataChips: LinearLayout
    private lateinit var seasonRow: LinearLayout; private lateinit var epList: RecyclerView; private lateinit var body: LinearLayout
    private lateinit var loading: ProgressBar
    private var selectedEpisode: Pair<Int, Int>? = null
    private var preferredProvider: String? = null

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        ImmersiveUi.hide(window)
        val i = intent
        preferredProvider = i.getStringExtra("provider")
        title = Movies.Title(i.getStringExtra("imdb") ?: "", i.getIntExtra("tmdb", 0), i.getStringExtra("type") ?: "movie",
            i.getStringExtra("name") ?: "", i.getStringExtra("poster") ?: "", i.getStringExtra("bg") ?: "", i.getStringExtra("logo") ?: "",
            i.getStringExtra("year") ?: "", i.getStringExtra("rating") ?: "",
            (i.getStringExtra("genres") ?: "").split("|").filter { it.isNotEmpty() }, i.getStringExtra("desc") ?: "",
            i.getStringExtra("runtime") ?: "", (i.getStringExtra("cast") ?: "").split("|").filter { it.isNotEmpty() },
            i.getStringExtra("trailerKey") ?: "")
        selectedEpisode = lastEpisode()
        build(); bind()
        io.execute {
            var (t, eps) = try { Movies.details(title.type, title.tmdb) } catch (_: Exception) { null to emptyList() }
            if (t == null && title.isSeries && title.tmdb > 0 && "Anime" in title.genres) {
                val asMovie = try { Movies.details("movie", title.tmdb) } catch (_: Exception) { null to emptyList() }
                if (asMovie.first != null) {
                    t = asMovie.first
                    eps = emptyList()
                }
            }
            val resolvedSeries = (t ?: title).isSeries
            if (resolvedSeries && eps.isEmpty() && title.tmdb > 0) {
                eps = try { ElfuraClient.episodes(title.tmdb) } catch (_: Exception) { emptyList() }
            }
            ui.post {
                if (isFinishing || isDestroyed) return@post
                loading.visibility = View.GONE
                if (t != null) title = t.copy(
                    tmdb = if (t.tmdb > 0) t.tmdb else title.tmdb,
                    genres = if ("Anime" in title.genres && "Anime" !in t.genres) listOf("Anime") + t.genres else t.genres
                )
                episodes = eps; seasons = eps.map { it.season }.distinct().sorted()
                val last = prefs.getInt("last_season_${title.imdb}", seasons.firstOrNull() ?: 1)
                season = if (last in seasons) last else seasons.firstOrNull() ?: 1
                selectedEpisode = lastEpisode()
                bind(); if (title.isSeries) bindSeasons()
            }
        }
    }

    private fun tv(size: Float, color: Int, face: Typeface) = TextView(this).apply { textSize = size; setTextColor(color); typeface = face }

    private fun build() {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val root = FrameLayout(this); root.setBackgroundColor(Accent.ABYSS)
        // Gradients fade to the ground colour itself, so a palette change cannot leave an old navy behind.
        val gr = Color.red(Accent.ABYSS); val gg = Color.green(Accent.ABYSS); val gb = Color.blue(Accent.ABYSS)
        backdrop = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; alpha = 0f }
        root.addView(backdrop, FrameLayout.LayoutParams(-1, -1))
        root.addView(View(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(Color.argb(250, gr, gg, gb), Color.argb(190, gr, gg, gb), Color.argb(30, gr, gg, gb)))
        }, FrameLayout.LayoutParams(-1, -1))
        root.addView(View(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
                intArrayOf(Color.argb(255, gr, gg, gb), Color.argb(0, gr, gg, gb)))
        }, FrameLayout.LayoutParams(-1, (300 * dp).toInt(), Gravity.BOTTOM))

        val compact = resources.configuration.screenHeightDp < 420
        val inset = (if (compact) 28 else 48) * dp
        val textWidth = (resources.displayMetrics.widthPixels * if (compact) 0.73f else 0.62f).toInt()
        body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(inset.toInt(), ((if (compact) 96 else 112) * dp).toInt(), inset.toInt(), (32 * dp).toInt()) }
        body.addView(tv(if (compact) 9f else 11f, Accent.BRAND_LIGHT, fSemi).apply {
            text = if (title.isSeries) "SERIES" else "ORIGINAL FILM"; letterSpacing = 0.28f
        },
            LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = (12 * dp).toInt() })
        logo = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_START; adjustViewBounds = true; visibility = View.GONE }
        body.addView(logo, LinearLayout.LayoutParams(minOf(textWidth, (420 * dp).toInt()), ((if (compact) 74 else 110) * dp).toInt()))
        nameTv = tv(if (compact) 30f else 42f, Accent.TEXT, fBold).apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END }
        body.addView(nameTv, LinearLayout.LayoutParams(textWidth, -2))
        facts = tv(if (compact) 12f else 13f, Accent.DIM, fBody)
        body.addView(facts, LinearLayout.LayoutParams(-2, -2).apply { topMargin = (12 * dp).toInt() })
        metadataChips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        desc = tv(if (compact) 12f else 14f, Accent.DIM, fBody).apply {
            maxLines = if (compact) 3 else 4; ellipsize = android.text.TextUtils.TruncateAt.END; setLineSpacing(0f, 1.3f)
        }
        body.addView(desc, LinearLayout.LayoutParams(textWidth, -2).apply { topMargin = (8 * dp).toInt() })

        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        playBtn = button("▶  Play", primary = true) { playCurrent() }
        buttons.addView(playBtn)
        val altIsOg = preferredProvider != FrostProvider.OG.id
        val altBtn = button(if (altIsOg) "▶  Play OG" else "▶  Play TG", primary = false) {
            playCurrent(if (altIsOg) FrostProvider.OG.id else FrostProvider.FROST.id)
        }
        buttons.addView(altBtn, LinearLayout.LayoutParams(-2, -2).apply { marginStart = (12 * dp).toInt() })
        val serverBtn = button("Servers", primary = false) { showServerPicker() }
        buttons.addView(serverBtn, LinearLayout.LayoutParams(-2, -2).apply { marginStart = (12 * dp).toInt() })
        loading = ProgressBar(this).apply { indeterminateTintList = android.content.res.ColorStateList.valueOf(pageAccent) }
        buttons.addView(loading, LinearLayout.LayoutParams((28 * dp).toInt(), (28 * dp).toInt()).apply { marginStart = (16 * dp).toInt() })
        val buttonScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false; clipChildren = false; clipToPadding = false
            addView(buttons)
        }
        body.addView(buttonScroll, LinearLayout.LayoutParams(-1, -2).apply { topMargin = (26 * dp).toInt() })

        val sc = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; clipChildren = false; clipToPadding = false }
        seasonRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, (8 * dp).toInt(), (40 * dp).toInt(), (8 * dp).toInt()) }
        sc.addView(seasonRow)
        body.addView(sc, LinearLayout.LayoutParams(-1, -2).apply { topMargin = (26 * dp).toInt() })
        epList = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@DetailActivity, RecyclerView.HORIZONTAL, false)
            adapter = epAdapter; itemAnimator = null; clipChildren = false; clipToPadding = false
            overScrollMode = View.OVER_SCROLL_NEVER; setPadding(0, (6 * dp).toInt(), (160 * dp).toInt(), (20 * dp).toInt())
            setItemViewCacheSize(12)
        }
        body.addView(epList, LinearLayout.LayoutParams(-1, -2))
        val contentScroll = ScrollView(this).apply { isFillViewport = true; isVerticalScrollBarEnabled = false; addView(body) }
        root.addView(contentScroll, FrameLayout.LayoutParams(-1, -1))
        val toolbar = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL; setPadding(inset.toInt(), (8 * dp).toInt(), inset.toInt(), (8 * dp).toInt())
            clipChildren = false; clipToPadding = false; minimumHeight = (72 * dp).toInt()
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.argb(190, gr, gg, gb), Color.TRANSPARENT))
            elevation = 0f
        }
        toolbar.addView(button("‹  Back", false) { finish() })
        toolbar.addView(ImageView(this).apply {
            setImageResource(R.drawable.streamr_mark); scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = "STREAMR"
        }, LinearLayout.LayoutParams((64 * dp).toInt(), (52 * dp).toInt()).apply { marginStart = (18 * dp).toInt() })
        root.addView(toolbar, FrameLayout.LayoutParams(-1, (76 * dp).toInt(), Gravity.TOP))
        setContentView(root)

        body.alpha = 0f; body.translationX = -30 * dp
        body.animate().alpha(1f).translationX(0f).setDuration(420).setInterpolator(DecelerateInterpolator(2f)).start()
        playBtn.requestFocus()
    }

    private fun button(label: String, primary: Boolean, onClick: () -> Unit) = TextView(this).apply {
        text = label; textSize = 14f; typeface = fSemi; gravity = Gravity.CENTER; isFocusable = true; isClickable = true
        setPadding((28 * dp).toInt(), (13 * dp).toInt(), (28 * dp).toInt(), (13 * dp).toInt())
        val bg = GradientDrawable().apply { cornerRadius = 12 * dp }
        background = bg
        val hairline = (1 * dp).toInt().coerceAtLeast(1)
        val focusRing = (2 * dp).toInt().coerceAtLeast(2)
        val style = { f: Boolean ->
            bg.setColor(if (primary) Accent.BRAND else if (f) 0xCC1B1630.toInt() else 0xB3100A23.toInt())
            bg.setStroke(
                if (f) focusRing else if (primary) 0 else hairline,
                if (f) Accent.SIGNAL else 0x4000ECF7
            )
            setTextColor(Accent.TEXT)
            animate().scaleX(if (f) 1.05f else 1f).scaleY(if (f) 1.05f else 1f).translationZ(if (f) 8 * dp else 0f)
                .setDuration(200).setInterpolator(DecelerateInterpolator(2f)).start()
        }
        style(false)
        setOnFocusChangeListener { _, f -> style(f) }
        setOnClickListener { onClick() }
    }

    private fun bind() {
        if (title.background.isNotEmpty()) backdrop.load(title.background) {
            crossfade(true); listener(onSuccess = { _, _ -> backdrop.animate().alpha(1f).setDuration(600).start() })
        }
        nameTv.text = title.name
        if (title.logo.isNotEmpty()) logo.load(title.logo) {
            listener(onSuccess = { _, _ -> logo.visibility = View.VISIBLE; nameTv.visibility = View.GONE },
                onError = { _, _ -> logo.visibility = View.GONE; nameTv.visibility = View.VISIBLE })
        }
        // `2026 · Sci-Fi · 2h 14m · ★ 6.6` - one muted line, as the site writes it, not a wall of chips.
        val bits = ArrayList<String>()
        if (title.year.isNotEmpty()) bits.add(title.year)
        if (title.genres.isNotEmpty()) bits.add(title.genres.take(2).joinToString(", "))
        if (title.runtime.isNotEmpty()) bits.add(title.runtime)
        if (title.rating.isNotEmpty()) bits.add("★ ${title.rating}")
        if (bits.isEmpty()) bits.add(if (title.isSeries) "Series" else "Movie")
        facts.text = bits.joinToString("  ·  ")
        metadataChips.removeAllViews()
        desc.text = title.description
        playBtn.text = if (title.isSeries) {
            val (s, e) = lastEpisode(); "▶   Play  S$s E$e"
        } else "▶   Play"
    }

    private fun metadataChip(label: String) = TextView(this).apply {
        text = label; textSize = 10f; typeface = fSemi; setTextColor(Accent.DIM)
        setPadding((11 * dp).toInt(), (6 * dp).toInt(), (11 * dp).toInt(), (6 * dp).toInt())
        background = Ui.pill(dp, 0x552D4652, 24f)
    }

    private fun lastEpisode(): Pair<Int, Int> {
        val s = prefs.getInt("last_season_${title.imdb}", episodes.firstOrNull()?.season ?: 1)
        val e = prefs.getInt("last_ep_${title.imdb}", episodes.firstOrNull { it.season == s }?.episode ?: 1)
        return s to e
    }

    private fun bindSeasons() {
        seasonRow.removeAllViews()
        if (seasons.isEmpty()) return
        seasons.forEach { s ->
            val chip = TextView(this).apply {
                text = "Season $s"; textSize = 14f; typeface = fSemi; isFocusable = true; isClickable = true
                setPadding((20 * dp).toInt(), (9 * dp).toInt(), (20 * dp).toInt(), (9 * dp).toInt())
                val bg = GradientDrawable().apply { cornerRadius = 40 * dp }; background = bg
                val style = { f: Boolean ->
                    bg.setColor(when { f -> Color.WHITE; s == season -> Color.argb(70, 255, 255, 255); else -> Color.argb(22, 255, 255, 255) })
                    setTextColor(if (f) Accent.ABYSS else if (s == season) Accent.TEXT else Accent.DIM)
                }
                style(false); tag = style
                setOnFocusChangeListener { _, f -> style(f); if (f && season != s) { season = s; refreshSeasonChips(); showSeason() } }
                setOnClickListener { season = s; refreshSeasonChips(); showSeason(); epList.post { epList.getChildAt(0)?.requestFocus() } }
            }
            seasonRow.addView(chip, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = (10 * dp).toInt() })
        }
        showSeason()
    }

    @Suppress("UNCHECKED_CAST")
    private fun refreshSeasonChips() {
        for (i in 0 until seasonRow.childCount) { val c = seasonRow.getChildAt(i); (c.tag as? (Boolean) -> Unit)?.invoke(c.isFocused) }
    }

    private var seasonEps = listOf<Movies.Episode>()
    private fun showSeason() {
        seasonEps = episodes.filter { it.season == season }
        epAdapter.notifyDataSetChanged(); epList.scrollToPosition(0)
        epList.alpha = 0f; epList.animate().alpha(1f).setDuration(260).start()
    }

    private val epAdapter = object : RecyclerView.Adapter<VH>() {
        override fun getItemCount() = seasonEps.size
        override fun onCreateViewHolder(p: ViewGroup, t: Int): VH {
            val card = LinearLayout(p.context).apply { orientation = LinearLayout.VERTICAL; isFocusable = true; isClickable = true }
            val img = ImageView(p.context).apply { scaleType = ImageView.ScaleType.CENTER_CROP; id = 1; clipToOutline = true
                background = GradientDrawable().apply { cornerRadius = 12 * dp; setColor(Color.argb(30, 255, 255, 255)) } }
            card.addView(img, LinearLayout.LayoutParams((240 * dp).toInt(), (135 * dp).toInt()))
            val n = tv(14f, Accent.TEXT, fSemi).apply { id = 2; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
            card.addView(n, LinearLayout.LayoutParams((240 * dp).toInt(), -2).apply { topMargin = (10 * dp).toInt() })
            card.addView(View(p.context).apply { id = 3; setBackgroundColor(pageAccent); visibility = View.GONE },
                LinearLayout.LayoutParams((240 * dp).toInt(), (2 * dp).toInt()).apply { topMargin = (5 * dp).toInt() })
            card.layoutParams = RecyclerView.LayoutParams(-2, -2).apply { marginEnd = (18 * dp).toInt() }
            return VH(card)
        }
        override fun onBindViewHolder(h: VH, i: Int) {
            val e = seasonEps[i]; val v = h.itemView
            val img = v.findViewById<ImageView>(1); img.setImageDrawable(null); if (e.thumb.isNotEmpty()) img.load(e.thumb) { crossfade(true) }
            v.findViewById<TextView>(2).text = "${e.episode}. ${e.name}"
            v.setOnFocusChangeListener { x, f ->
                x.animate().scaleX(if (f) 1.08f else 1f).scaleY(if (f) 1.08f else 1f).translationZ(if (f) 20 * dp else 0f)
                    .setDuration(220).setInterpolator(DecelerateInterpolator(2f)).start()
                (x as LinearLayout).getChildAt(1).let { (it as TextView).setTextColor(if (f) Accent.TEXT else Accent.DIM) }
                if (f) {
                    selectedEpisode = e.season to e.episode
                    desc.text = e.overview.ifEmpty { title.description }
                    updateEpisodeMarkers()
                }
            }
            (v as LinearLayout).getChildAt(1).let { (it as TextView).setTextColor(Accent.DIM) }
            v.findViewById<View>(3).visibility = if (selectedEpisode == (e.season to e.episode)) View.VISIBLE else View.GONE
            v.setOnClickListener { selectedEpisode = e.season to e.episode; play(e.season, e.episode) }
        }
    }

    private fun updateEpisodeMarkers() {
        for (i in 0 until epList.childCount) {
            val child = epList.getChildAt(i)
            val position = epList.getChildAdapterPosition(child)
            val episode = seasonEps.getOrNull(position)
            child.findViewById<View>(3)?.visibility = if (episode != null && selectedEpisode == (episode.season to episode.episode)) View.VISIBLE else View.GONE
        }
    }

    private fun showServerPicker() {
        val options = arrayOf(
            "TG FROST (Default)",
            "OG · TheOGPirateBot (Elfura / VidLink)",
            "TG OUT"
        )
        val providerIds = arrayOf(FrostProvider.FROST.id, FrostProvider.OG.id, FrostProvider.OUT.id)
        AlertDialog.Builder(this)
            .setTitle("Play with server")
            .setItems(options) { _, which ->
                val chosen = providerIds.getOrNull(which)
                playCurrent(chosen)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun playCurrent(providerOverride: String? = preferredProvider) {
        if (title.isSeries) {
            val (s, e) = selectedEpisode ?: lastEpisode()
            play(s, e, providerOverride)
        } else play(0, 0, providerOverride)
    }

    private fun play(s: Int, e: Int, providerOverride: String? = preferredProvider) {
        if (title.tmdb <= 0) { Toast.makeText(this, "This title can't be played right now", Toast.LENGTH_SHORT).show(); return }
        Movies.saveTitle(prefs, title)
        if (title.isSeries) prefs.edit().putInt("last_season_${title.imdb}", s).putInt("last_ep_${title.imdb}", e).apply()
        MoviePlayerActivity.open(this, title, s, e, episodes, provider = providerOverride)
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) ImmersiveUi.hide(window)
    }

    override fun onResume() { super.onResume(); if (::playBtn.isInitialized) bind() }
    override fun onBackPressed() { finish(); overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out) }
    override fun onDestroy() { io.shutdownNow(); ui.removeCallbacksAndMessages(null); super.onDestroy() }
}
