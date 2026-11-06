package com.aktv.app

import android.app.Activity
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
import androidx.appcompat.app.AlertDialog
import androidx.core.content.res.ResourcesCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import org.json.JSONObject
import java.util.concurrent.Executors

/** Netflix / Apple TV style details page: backdrop, logo, facts, Play, and for series the seasons and episodes. */
class DetailActivity : Activity() {
    companion object {
        fun open(c: Context, t: Movies.Title) {
            c.startActivity(Intent(c, DetailActivity::class.java).putExtra("type", t.type).putExtra("imdb", t.imdb)
                .putExtra("tmdb", t.tmdb).putExtra("name", t.name).putExtra("poster", t.poster).putExtra("bg", t.background)
                .putExtra("logo", t.logo).putExtra("year", t.year).putExtra("rating", t.rating).putExtra("runtime", t.runtime)
                .putExtra("desc", t.description).putExtra("genres", t.genres.joinToString("|"))
                .putExtra("cast", t.cast.joinToString("|").take(900)).putExtra("trailerKey", t.trailerKey))
        }
    }

    private val io = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())
    private val dp by lazy { resources.displayMetrics.density }
    private val fReg by lazy { ResourcesCompat.getFont(this, R.font.sora_regular) ?: Typeface.DEFAULT }
    private val fSemi by lazy { ResourcesCompat.getFont(this, R.font.sora_semibold) ?: Typeface.DEFAULT_BOLD }
    private val fBold by lazy { ResourcesCompat.getFont(this, R.font.sora_bold) ?: Typeface.DEFAULT_BOLD }
    private val prefs by lazy { getSharedPreferences("aktv", Context.MODE_PRIVATE) }
    private val pageAccent get() = if (title.isSeries) Accent.ION else Accent.SIGNAL

    private lateinit var title: Movies.Title
    private var episodes = listOf<Movies.Episode>(); private var seasons = listOf<Int>(); private var season = 1
    private lateinit var backdrop: ImageView; private lateinit var logo: ImageView; private lateinit var nameTv: TextView
    private lateinit var facts: TextView; private lateinit var desc: TextView; private lateinit var playBtn: TextView
    private lateinit var metadataChips: LinearLayout
    private lateinit var seasonRow: LinearLayout; private lateinit var epList: RecyclerView; private lateinit var body: LinearLayout
    private lateinit var loading: ProgressBar
    private var selectedEpisode: Pair<Int, Int>? = null

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        ImmersiveUi.hide(window)
        val i = intent
        title = Movies.Title(i.getStringExtra("imdb") ?: "", i.getIntExtra("tmdb", 0), i.getStringExtra("type") ?: "movie",
            i.getStringExtra("name") ?: "", i.getStringExtra("poster") ?: "", i.getStringExtra("bg") ?: "", i.getStringExtra("logo") ?: "",
            i.getStringExtra("year") ?: "", i.getStringExtra("rating") ?: "",
            (i.getStringExtra("genres") ?: "").split("|").filter { it.isNotEmpty() }, i.getStringExtra("desc") ?: "",
            i.getStringExtra("runtime") ?: "", (i.getStringExtra("cast") ?: "").split("|").filter { it.isNotEmpty() },
            i.getStringExtra("trailerKey") ?: "")
        selectedEpisode = lastEpisode()
        build(); bind()
        io.execute {
            val (t, eps) = try { Movies.details(title.type, title.tmdb) } catch (_: Exception) { null to emptyList() }
            ui.post {
                if (isFinishing || isDestroyed) return@post
                loading.visibility = View.GONE
                if (t != null) title = t.copy(tmdb = if (t.tmdb > 0) t.tmdb else title.tmdb)
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
        backdrop = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; alpha = 0f }
        root.addView(backdrop, FrameLayout.LayoutParams(-1, -1))
        root.addView(View(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(Color.argb(250, 5, 6, 13), Color.argb(200, 5, 6, 13), Color.argb(40, 5, 6, 13)))
        }, FrameLayout.LayoutParams(-1, -1))
        root.addView(View(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
                intArrayOf(Color.argb(255, 5, 6, 13), Color.argb(0, 5, 6, 13)))
        }, FrameLayout.LayoutParams(-1, (300 * dp).toInt(), Gravity.BOTTOM))

        val compact = resources.configuration.screenHeightDp < 420
        val inset = (if (compact) 28 else 48) * dp
        val textWidth = (resources.displayMetrics.widthPixels * if (compact) 0.73f else 0.62f).toInt()
        body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(inset.toInt(), ((if (compact) 96 else 112) * dp).toInt(), inset.toInt(), (32 * dp).toInt()) }
        body.addView(tv(10f, pageAccent, fSemi).apply { text = if (title.isSeries) "AK SERIES  /  ON DEMAND" else "AK CINEMA  /  ON DEMAND"; letterSpacing = 0.14f },
            LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = (12 * dp).toInt() })
        logo = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_START; adjustViewBounds = true; visibility = View.GONE }
        body.addView(logo, LinearLayout.LayoutParams(minOf(textWidth, (420 * dp).toInt()), ((if (compact) 74 else 110) * dp).toInt()))
        nameTv = tv(if (compact) 30f else 42f, Accent.TEXT, fBold).apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END }
        body.addView(nameTv, LinearLayout.LayoutParams(textWidth, -2))
        facts = tv(if (compact) 12f else 14f, Accent.DIM, fSemi)
        body.addView(facts, LinearLayout.LayoutParams(-2, -2).apply { topMargin = (14 * dp).toInt() })
        metadataChips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val chipScroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; clipChildren = false; addView(metadataChips) }
        body.addView(chipScroll, LinearLayout.LayoutParams(-1, (42 * dp).toInt()).apply { topMargin = (8 * dp).toInt() })
        desc = tv(if (compact) 12f else 14f, Accent.DIM, fReg).apply { maxLines = if (compact) 3 else 4; ellipsize = android.text.TextUtils.TruncateAt.END; setLineSpacing(0f, 1.25f) }
        body.addView(desc, LinearLayout.LayoutParams(textWidth, -2).apply { topMargin = (8 * dp).toInt() })

        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        playBtn = button("▶  Play", primary = true) { playCurrent() }
        buttons.addView(playBtn)
        loading = ProgressBar(this).apply { indeterminateTintList = android.content.res.ColorStateList.valueOf(pageAccent) }
        buttons.addView(loading, LinearLayout.LayoutParams((28 * dp).toInt(), (28 * dp).toInt()).apply { marginStart = (16 * dp).toInt() })
        body.addView(buttons, LinearLayout.LayoutParams(-2, -2).apply { topMargin = (26 * dp).toInt() })

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
            setBackgroundColor(0xFF080B14.toInt())
            elevation = (8 * dp)
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
        text = label; textSize = 17f; typeface = fSemi; gravity = Gravity.CENTER; isFocusable = true; isClickable = true
        setPadding((34 * dp).toInt(), (13 * dp).toInt(), (38 * dp).toInt(), (13 * dp).toInt())
        val bg = GradientDrawable().apply { cornerRadius = 12 * dp }
        background = bg
        val style = { f: Boolean ->
            bg.setColor(if (f && !primary) Color.WHITE else if (primary) Color.WHITE else 0x991A2936.toInt())
            bg.setStroke(if (f) (2 * dp).toInt().coerceAtLeast(1) else 0, pageAccent)
            setTextColor(if (primary || f) Accent.ABYSS else Accent.TEXT)
            animate().scaleX(if (f) 1.045f else 1f).scaleY(if (f) 1.045f else 1f).translationZ(if (f) 8 * dp else 0f)
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
        val bits = ArrayList<String>()
        if (title.rating.isNotEmpty()) bits.add("★ ${title.rating}")
        if (title.year.isNotEmpty()) bits.add(title.year)
        if (title.runtime.isNotEmpty()) bits.add(title.runtime)
        bits.add(if (title.isSeries) "Series" else "Movie")
        if (title.genres.isNotEmpty()) bits.add(title.genres.take(3).joinToString(" · "))
        facts.text = bits.joinToString("   •   ")
        metadataChips.removeAllViews()
        (title.genres.take(5) + title.cast.take(4)).distinct().forEach { label ->
            metadataChips.addView(metadataChip(label), LinearLayout.LayoutParams(-2, -2).apply { marginEnd = (7 * dp).toInt() })
        }
        metadataChips.visibility = if (metadataChips.childCount == 0) View.GONE else View.VISIBLE
        desc.text = title.description
        playBtn.text = if (title.isSeries) {
            val (s, e) = lastEpisode(); "▶  Play S$s · E$e"
        } else "▶  Play"
    }

    private fun metadataChip(label: String) = TextView(this).apply {
        text = label; textSize = 10f; typeface = fSemi; setTextColor(Accent.DIM)
        setPadding((11 * dp).toInt(), (6 * dp).toInt(), (11 * dp).toInt(), (6 * dp).toInt())
        background = Ui.pill(dp, 0x552D4652, 24f)
    }

    override fun onDestroy() { io.shutdownNow(); ui.removeCallbacksAndMessages(null); super.onDestroy() }
}
