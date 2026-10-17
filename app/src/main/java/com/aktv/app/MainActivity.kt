package com.aktv.app

import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.TypefaceSpan
import android.view.*
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.res.ResourcesCompat
import androidx.media3.common.*
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.drm.*
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.trackselection.AdaptiveTrackSelection
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.*
import coil.load
import coil.imageLoader
import coil.request.ImageRequest
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.Future

class VH(v: View) : RecyclerView.ViewHolder(v)

/** The TG TV API answered with a non-2xx status. */
class ApiException(val code: Int) : IOException("HTTP $code")

class MainActivity : AppCompatActivity() {
    /** V1 = live TV, v2 = the 24/7 catalogue. [key] is unique across both, so favourites and glow colours never collide. */
    data class Channel(val id: String, val name: String, val category: String, val logo: String, val v2: Boolean = false) {
        val key: String get() = if (v2) "v2:$id" else id
    }
    sealed class Tile {
        data class Ch(val c: Channel) : Tile()
        data class Mv(val m: Movies.Title) : Tile()
        object Hint : Tile()
    }
    class Row(val title: String, var tiles: List<Tile>)

    // movie & series shelves (filled the first time the tab opens)
    private val vodRows = arrayOf(listOf<Row>(), listOf<Row>()); private val vodLoading = booleanArrayOf(false, false)   // [0] Movies, [1] Series
    private val RECENT = "Recently watched"; private val CONTINUE = "Continue watching"
    private lateinit var heroBackdrop: ImageView; private lateinit var heroPreview: View; private lateinit var previewPv: PlayerView
    private var previewPlayer: ExoPlayer? = null; private var previewToken = 0
    private var previewJob: Future<*>? = null
    // ➜ CHANNEL UPDATE LINKS: the app re-reads both lists from here every launch
    private val SITE = TgServers.SITE
    private val BASE = "$SITE/api/public/channels"            // V1 live TV
    private val BASE_V2 = "$SITE/api/public/v2/channels"      // V2 24/7
    private val GUIDE = "$SITE/api/public/guide"              // programme details (V1)
    private val UA = "Mozilla/5.0 (Linux; Android 9; TV) AppleWebKit/537.36 Chrome/110 Safari/537.36"
    private val io = Executors.newFixedThreadPool(4)
    private val prefs by lazy { getSharedPreferences("aktv", Context.MODE_PRIVATE) }
    private val dp by lazy { resources.displayMetrics.density }
    private val ui = Handler(Looper.getMainLooper())
    private val fRegular by lazy { ResourcesCompat.getFont(this, R.font.sora_regular) ?: Typeface.DEFAULT }
    private val fSemi by lazy { ResourcesCompat.getFont(this, R.font.sora_semibold) ?: Typeface.DEFAULT_BOLD }

    private var all = listOf<Channel>(); private var allV2 = listOf<Channel>(); private var shown = listOf<Channel>()
    private var rows = listOf<Row>(); private val inner = HashMap<Int, TileAdapter>()
    private var favs = setOf<String>(); private var heroTile: Tile? = null
    private val accents = HashMap<String, Int>()
    private var tab = 0; private var current = -1
    private var player: ExoPlayer? = null
    private var lastRefresh = 0L; private var v1Msg = "Loading channels…"

    // Only the native player owns live TV playback. Resolve jobs are cancelled/guarded when zapping.
    private var playToken = 0
    private var resolveJob: Future<*>? = null
    private val playbackIo = Executors.newFixedThreadPool(2)
    private var nativeSources = emptyList<TgStream>()
    private var serverIdx = 0
    private val recovery = PlaybackRecovery()
    private var okLong = false
    private var renderedFrame = false
    // HD smoothness: picture size is capped to what this box can decode, and lowered by itself if frames start dropping
    private var trackSel: androidx.media3.exoplayer.trackselection.DefaultTrackSelector? = null
    private var capH = 1080; private var badSeconds = 0
    private val lowEnd by lazy {
        (getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager).isLowRamDevice || android.os.Build.SUPPORTED_64_BIT_ABIS.isEmpty()
    }
    private fun startCap(): Int {
        val ch = shown.getOrNull(current)
        val saved = if (ch != null) prefs.getInt("q_${ch.key}", 0) else 0     // what worked last time for this channel
        return if (saved > 0) saved else if (lowEnd) 720 else 1080
    }
    private val dropWatcher = object : AnalyticsListener {
        override fun onDroppedVideoFrames(eventTime: AnalyticsListener.EventTime, droppedFrames: Int, elapsedMs: Long) {
            if (elapsedMs <= 0) return
            if (droppedFrames * 1000f / elapsedMs >= 6f) badSeconds++ else badSeconds = (badSeconds - 1).coerceAtLeast(0)
            if (badSeconds < 3 || capH <= 540) return
            badSeconds = 0
            capH = if (capH > 720) 720 else 540
            shown.getOrNull(current)?.let { prefs.edit().putInt("q_${it.key}", capH).apply() }
            trackSel?.let { it.setParameters(it.buildUponParameters().setMaxVideoSize(capH * 16 / 9, capH)) }
            playMsg("Smoothing playback (${capH}p)…")
        }
    }

    private lateinit var rowsView: RecyclerView; private lateinit var status: TextView
    private lateinit var layer: View; private lateinit var pv: PlayerView; private lateinit var spinner: ProgressBar
    private lateinit var segLive: TextView; private lateinit var segV2: TextView; private lateinit var segSites: TextView; private lateinit var segSeries: TextView
    private val segs by lazy { listOf(segLive, segV2, segSites, segSeries) }   // 0 Live, 1 24/7, 2 Movies, 3 Series
    // top navigation (Google TV style): flat pills, white while focused, grey for the open tab
    private val segBgs by lazy { List(4) { Ui.pill(dp, Color.TRANSPARENT) } }
    private var lastRowsFocus: View? = null
    private val compact by lazy { resources.configuration.screenHeightDp < 420 }
    private lateinit var searchBtn: TextView; private lateinit var intro: AkIntroView
    private lateinit var heroText: View; private lateinit var heroDot: View; private lateinit var heroBadge: TextView
    private lateinit var heroTitle: TextView; private lateinit var heroChips: LinearLayout
    private lateinit var heroOrb: View; private lateinit var heroLogo: ImageView; private lateinit var heroMono: TextView
    private lateinit var heroProg: TextView; private lateinit var heroProgress: ProgressBar
    private lateinit var heroDesc: TextView; private lateinit var heroNext: TextView
    private val orbBg by lazy { GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Accent.ABYSS, Accent.ABYSS)) }
    private lateinit var infoCard: View; private lateinit var infoLogo: ImageView; private lateinit var infoName: TextView
    private lateinit var infoCat: TextView; private lateinit var playerScrim: View; private lateinit var playerClock: View
    private lateinit var infoMono: TextView; private lateinit var infoNow: TextView; private lateinit var infoProgress: ProgressBar
    private lateinit var infoDesc: TextView; private lateinit var infoNext: TextView; private lateinit var playStatus: TextView
    // channel guide
    private val FAV = "Favorites"
    private lateinit var home: ViewGroup; private var homeFocusMode = 0; private var lastHomeFocus: View? = null
    private lateinit var guide: ViewGroup; private lateinit var guideScrim: View; private lateinit var guideDivider: View
    private lateinit var catList: RecyclerView; private lateinit var chList: RecyclerView
    private lateinit var chCol: View; private lateinit var chHeader: TextView; private lateinit var channelSearch: EditText
    private var guideShown = false; private var shownCat = ""
    private var guideCats = listOf<String>(); private var catCounts = mapOf<String, Int>()
    private var guideCat = ""; private var guideChannels = listOf<Channel>(); private var categoryChannels = listOf<Channel>()
    // search
    private lateinit var searchLayer: View; private lateinit var searchQuery: TextView; private lateinit var searchKeys: LinearLayout
    private lateinit var searchResults: RecyclerView; private lateinit var searchCount: TextView; private lateinit var searchEmpty: TextView
    private var searchShown = false; private val searchBuf = StringBuilder(); private var results = listOf<Channel>()

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        TgServers.init(applicationContext)
        // Old releases remembered web/backup engines and guessed HTTP profiles. Do not carry those forward.
        if (!prefs.getBoolean("native_only_v2", false)) {
            val edit = prefs.edit()
            prefs.all.keys.filter { it.startsWith("eng_") || it.startsWith("p_") }.forEach { edit.remove(it) }
            edit.putBoolean("native_only_v2", true).apply()
        }
        setContentView(R.layout.activity_main)
        rowsView = findViewById(R.id.rows); status = findViewById(R.id.status)
        layer = findViewById(R.id.playerLayer); pv = findViewById(R.id.playerView); spinner = findViewById(R.id.spinner)
        segLive = findViewById(R.id.segLive); segV2 = findViewById(R.id.segV2); segSites = findViewById(R.id.segSites); segSeries = findViewById(R.id.segSeries)
        searchBtn = findViewById(R.id.searchBtn)
        heroText = findViewById(R.id.heroText); heroDot = findViewById(R.id.heroDot); heroBadge = findViewById(R.id.heroBadge)
        heroTitle = findViewById(R.id.heroTitle); heroChips = findViewById(R.id.heroChips)
        heroOrb = findViewById(R.id.heroOrb); heroLogo = findViewById(R.id.heroLogo); heroMono = findViewById(R.id.heroMono)
        heroProg = findViewById(R.id.heroProg); heroProgress = findViewById(R.id.heroProgress)
        heroDesc = findViewById(R.id.heroDesc); heroNext = findViewById(R.id.heroNext)
        infoCard = findViewById(R.id.infoCard); infoLogo = findViewById(R.id.infoLogo); infoName = findViewById(R.id.infoName)
        infoCat = findViewById(R.id.infoCat); playerScrim = findViewById(R.id.playerScrim); playerClock = findViewById(R.id.playerClock)
        infoMono = findViewById(R.id.infoMono); infoNow = findViewById(R.id.infoNow); infoProgress = findViewById(R.id.infoProgress)
        infoDesc = findViewById(R.id.infoDesc); infoNext = findViewById(R.id.infoNext); playStatus = findViewById(R.id.playStatus)
        searchLayer = findViewById(R.id.searchLayer); searchQuery = findViewById(R.id.searchQuery); searchKeys = findViewById(R.id.searchKeys)
        searchResults = findViewById(R.id.searchResults); searchCount = findViewById(R.id.searchCount); searchEmpty = findViewById(R.id.searchEmpty)
        intro = findViewById(R.id.splash)
        heroBackdrop = findViewById(R.id.heroBackdrop); heroPreview = findViewById(R.id.heroPreview); previewPv = findViewById(R.id.heroPreviewPv)

        // flat surfaces (no glass: it made the UI laggy)
        layoutHero()
        segs.forEachIndexed { i, tv -> tv.background = segBgs[i] }
        heroOrb.background = orbBg
        findViewById<View>(R.id.infoLogoBox).background = Ui.pill(dp, Accent.SURFACE, 16f)
        findViewById<TextView>(R.id.infoHint).text = SpannableStringBuilder().apply {
            key(this, "▲ ▼", "Channel"); append("     "); key(this, "▶", "All channels"); append("     ")
            key(this, "Hold OK", "Reload"); append("     "); key(this, "◀", "Next server"); append("     "); key(this, "Back", "Exit")
        }
        val track = ColorStateList.valueOf(Color.argb(60, 255, 255, 255))
        heroProgress.progressBackgroundTintList = track; infoProgress.progressBackgroundTintList = track

        // search pill in the top bar
        searchBtn.background = searchBg
        searchBtn.setOnFocusChangeListener { _, _ -> styleSegs() }
        searchBtn.setOnClickListener { openSearch() }

        home = findViewById(R.id.home); homeFocusMode = home.descendantFocusability
        guide = findViewById(R.id.guide); guideScrim = findViewById(R.id.guideScrim); guideDivider = findViewById(R.id.guideDivider)
        catList = findViewById(R.id.catList); chList = findViewById(R.id.chList)
        chCol = findViewById(R.id.chCol); chHeader = findViewById(R.id.chHeader); channelSearch = findViewById(R.id.channelSearch)
        guide.background = Ui.pill(dp, 0xF21A1C21.toInt(), 22f)
        guide.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        listOf(catList to catAdapter, chList to chAdapter).forEach { (rv, ad) ->
            rv.layoutManager = LinearLayoutManager(this); rv.adapter = ad; rv.itemAnimator = null
            rv.overScrollMode = View.OVER_SCROLL_NEVER
        }
        searchResults.layoutManager = LinearLayoutManager(this); searchResults.adapter = searchAdapter
        searchResults.itemAnimator = null; searchResults.overScrollMode = View.OVER_SCROLL_NEVER
        searchQuery.background = Ui.pill(dp, Accent.SURFACE, 14f)
        buildKeys(); renderQuery()

        favs = prefs.getStringSet("favs", emptySet())!!.toSet()
        rowsView.layoutManager = SnapLayoutManager(this, RecyclerView.VERTICAL); rowsView.adapter = rowAdapter
        rowsView.itemAnimator = null; rowsView.setItemViewCacheSize(6)
        (rowsView.layoutManager as LinearLayoutManager).initialPrefetchItemCount = 3
        rowsView.overScrollMode = View.OVER_SCROLL_NEVER
        rowsView.viewTreeObserver.addOnGlobalFocusChangeListener { old, new ->
            dimRows()
            if (new != null && isInside(new, rowsView)) {
                lastRowsFocus = new
                // first row = full hero; any row below it = hero folds away, so the list is the whole page and you can see where you are
                val pos = rowsView.findContainingItemView(new)?.let { rowsView.getChildAdapterPosition(it) } ?: 0
                setHeroCollapsed(pos >= 1)
            }
            val ni = segs.indexOfFirst { it === new }
            if (ni >= 0) {
                // tabs open only on OK now: moving across the bar no longer switches tab, so walking right from
                // Movies past Series to the search button still searches Movies (each section keeps its own search)
                val fromSeg = segs.any { it === old } || old === searchBtn
                if (!fromSeg && ni != tab) segs[tab].post { if (segs[ni].isFocused) segs[tab].requestFocus() }   // focus fell onto the bar by accident
            }
        }
        channelSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val q = s?.toString()?.trim().orEmpty()
                guideChannels = if (q.isEmpty()) categoryChannels else categoryChannels.filter {
                    it.name.contains(q, ignoreCase = true) || it.category.contains(q, ignoreCase = true)
                }
                chHeader.text = "$guideCat  ${guideChannels.size}"
                chAdapter.notifyDataSetChanged()
                chList.scrollToPosition(0)
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        segs.forEachIndexed { i, tv ->
            tv.setOnClickListener { switchTab(i) }
            tv.setOnFocusChangeListener { _, _ -> styleSegs() }
        }
        styleSegs(); fillHero(null); loadChannels()

        // Entrance: the AK ribbons unfold, then the mark flies through and the home screen rises in behind it.
        intro.wordmark = fSemi
        home.alpha = 0f; home.translationY = 28 * dp
        intro.onReveal = {
            home.animate().alpha(1f).translationY(0f).setDuration(650).setInterpolator(DecelerateInterpolator(2f)).start()
        }
        intro.onDone = { intro.visibility = View.GONE; rowsView.requestFocus() }
        Movies.diskDir = cacheDir
        ui.postDelayed({ loadVod(2) }, 1500)   // warm up the Movies tab, then Series, in the background
        ui.postDelayed({ loadVod(3) }, 5000)
        intro.start()
    }

    // ---------- helpers ----------
    private fun key(sb: SpannableStringBuilder, k: String, label: String) {
        val s = sb.length; sb.append(k)
        sb.setSpan(ForegroundColorSpan(Accent.TEXT), s, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sb.setSpan(TypefaceSpan(fSemi), s, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sb.append("  ").append(label)
    }
    private fun keyHint(k: String, label: String) = SpannableStringBuilder().also { key(it, k, label) }

    /** Hero button: [primary] is the white pill (Watch / Details), the rest are soft grey pills. */
    private fun chip(text: CharSequence, primary: Boolean = false) = TextView(this).apply {
        this.text = text; textSize = 13f; typeface = fSemi
        setTextColor(if (primary) Accent.ABYSS else Accent.TEXT)
        setPadding((22 * dp).toInt(), (11 * dp).toInt(), (24 * dp).toInt(), (11 * dp).toInt())
        background = Ui.pill(dp, if (primary) Accent.SIGNAL else 0xCC1D2433.toInt())
        layoutParams = LinearLayout.LayoutParams(-2, -2).apply { marginEnd = (10 * dp).toInt() }
    }

    /** Sizes the hero card from the real screen: rounded card under the top bar, text inside it at the bottom-left, rows below. */
    private fun layoutHero() {
        val dm = resources.displayMetrics
        val w = dm.widthPixels; val h = dm.heightPixels
        val bar = ((if (compact) 56 else 72) * dp).toInt()
        val cardH = (h * (if (compact) 0.36f else 0.40f)).toInt()
        findViewById<View>(R.id.topBar).layoutParams.height = bar
        val media = findViewById<View>(R.id.heroMedia)
        (media.layoutParams as FrameLayout.LayoutParams).apply { height = cardH; topMargin = bar }
        media.background = Ui.pill(dp, 0xFF11151D.toInt(), 26f); media.clipToOutline = true
        findViewById<View>(R.id.heroArea).layoutParams.height = cardH
        heroText.layoutParams.width = (w * 0.46f).toInt()
        heroDesc.maxLines = if (compact) 1 else 2
        heroTitle.textSize = if (compact) 26f else 34f
    }

    private fun navStyle(tv: TextView, bg: GradientDrawable, focused: Boolean, active: Boolean) {
        bg.setColor(when { focused -> 0xFFFFFFFF.toInt(); active -> Accent.SIGNAL; else -> 0xFF1D2A47.toInt() })
        val fg = if (focused || active) Accent.ABYSS else 0xFFC4D5F7.toInt()
        tv.setTextColor(fg); tv.compoundDrawableTintList = ColorStateList.valueOf(fg)
        val sc = if (focused) 1.07f else 1f
        if (tv.scaleX != sc) tv.animate().scaleX(sc).scaleY(sc).setDuration(150).start()
    }
    private val searchBg by lazy { Ui.pill(dp, 0xFF1D2A47.toInt()) }

    private fun styleSegs() {
        // one search button per section: it always searches the section you are in
        val label = when (tab) { 2 -> "Search Movies"; 3 -> "Search Series"; else -> "Search Channels" }
        if (searchBtn.text.toString() != label) searchBtn.text = label
        segs.forEachIndexed { i, tv -> navStyle(tv, segBgs[i], tv.isFocused, tab == i) }
        navStyle(searchBtn, searchBg, searchBtn.isFocused, false)
    }

    /** Focused card grows a little; the white ring comes from the view's own foreground state. No shadows, no glow. */
    private fun focusAnim(x: View, f: Boolean, color: Int = 0) {
        val s = if (f) 1.08f else 1f
        x.animate().cancel()
        x.animate().setStartDelay(0).scaleX(s).scaleY(s).setDuration(150).setInterpolator(DecelerateInterpolator(1.6f)).start()
    }
    /**
     * Rows without focus step back so the active row reads first. Only the row's title steps back:
     * fading the whole row put it in an offscreen layer that clipped the tiles' glow into a hard dark band over the channels.
     */
    private fun dimRows() {
        val inRows = rowsView.hasFocus()
        for (i in 0 until rowsView.childCount) {
            val c = rowsView.getChildAt(i)
            val a = if (!inRows || c.hasFocus()) 1f else 0.45f
            for (id in intArrayOf(R.id.rowTitle, R.id.rowCount)) {
                val t = c.findViewById<View>(id) ?: continue
                if (t.alpha != a) t.animate().alpha(a).setDuration(220).start()
            }
        }
    }
    private fun focusRowAt(pos: Int) {
        val holder = rowsView.findViewHolderForAdapterPosition(pos) ?: return
        val rv = holder.itemView.findViewById<RecyclerView>(R.id.rowList) ?: return
        val lm = rv.layoutManager as? LinearLayoutManager ?: return
        val first = lm.findFirstCompletelyVisibleItemPosition().coerceAtLeast(0)
        (lm.findViewByPosition(first) ?: rv.getChildAt(0))?.requestFocus()
    }
    private fun isInside(v: View, parent: View): Boolean {
        var p: ViewParent? = v.parent
        while (p != null) { if (p === parent) return true; p = p.parent }
        return false
    }
    private fun accentOf(key: String) = accents[key] ?: Accent.SIGNAL
    private fun extractAccent(key: String, d: android.graphics.drawable.Drawable, cb: (Int) -> Unit) {
        accents[key]?.let { cb(it); return }
        Accent.from(d) { c -> accents[key] = c; cb(c) }
    }
    private fun str(o: JSONObject, k: String) = if (o.isNull(k)) "" else o.optString(k, "")
    private fun initials(n: String): String {
        val w = n.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        return (if (w.size >= 2) "${w[0].first()}${w[1].first()}" else n.trim().take(2)).uppercase()
    }
    /** Channel logo, or a coloured monogram when the channel has no logo (all of V2). */
    private fun bindLogo(img: ImageView, mono: TextView, ch: Channel) {
        if (ch.logo.isBlank()) {
            img.setImageDrawable(null); img.visibility = View.GONE
            mono.visibility = View.VISIBLE; mono.text = initials(ch.name); mono.setTextColor(accentOf(ch.key))
        } else { mono.visibility = View.GONE; img.visibility = View.VISIBLE; img.load(ch.logo) }
    }

    /**
     * GET with quick retries: the API answers 502 now and then when its upstream blinks, and
     * that used to surface straight away as a failed channel.
     */
    private fun get(u: String, tries: Int = 3, ua: String = UA): String {
        var last: Exception? = null
        for (n in 1..tries) {
            try {
                val c = URL(u).openConnection() as HttpURLConnection
                c.connectTimeout = 15000; c.readTimeout = 15000
                c.setRequestProperty("User-Agent", ua); c.setRequestProperty("Accept", "application/json")
                val code = c.responseCode
                if (code in 200..299) return c.inputStream.bufferedReader().use { it.readText() }
                c.disconnect()
                last = ApiException(code)
                if (code in 400..499 && code != 408 && code != 429) break      // asking again won't change a 4xx
            } catch (e: IOException) { last = e }
            if (n < tries) Thread.sleep(600L * n)
        }
        throw last ?: IOException("no response")
    }
    private fun why(e: Throwable): String = when {
        e is ApiException && e.code == 404 -> "no live stream for this channel right now"
        e is ApiException && e.code == 400 -> "unknown channel"
        e is ApiException && e.code >= 500 -> "the TG TV server is busy (HTTP ${e.code}), try again in a moment"
        e is ApiException -> "HTTP ${e.code}"
        e is java.net.UnknownHostException || e is java.net.SocketTimeoutException || e is java.net.ConnectException -> "no connection to the server"
        else -> e.message ?: e.javaClass.simpleName
    }

    // ---------- data ----------
    private fun cacheFile(n: String) = File(filesDir, n)
    private fun readCache(n: String): String? = try { cacheFile(n).takeIf { it.exists() }?.readText() } catch (e: Exception) { null }
    private fun writeCache(n: String, s: String) { try { cacheFile(n).writeText(s) } catch (_: Exception) {} }

    private fun parseV1(json: String): List<Channel> {
        val arr = JSONObject(json).getJSONArray("channels")
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val id = str(o, "id"); val name = str(o, "name").trim()
            if (id.isEmpty() || name.isEmpty()) null
            else Channel(id, name, str(o, "category").ifBlank { "Other" }, str(o, "logo"))
        }
    }
    private fun parseV2(json: String): List<Channel> {
        val arr = JSONObject(json).getJSONArray("channels")
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val id = str(o, "id"); val name = str(o, "name").trim()
            if (id.isEmpty() || name.isEmpty()) null
            else {
                val first = name.first().uppercaseChar()
                Channel(id, name, str(o, "category").ifBlank { if (first.isLetter()) first.toString() else "#" }, str(o, "logo"), true)
            }
        }.sortedBy { it.name.lowercase() }
    }
    /** Channels without a logo to sample get a stable colour from their name, so the UI still reacts to them. */
    private fun seedAccents(list: List<Channel>) { for (c in list) if (c.logo.isBlank()) accents[c.key] = Accent.fromName(c.name) }

    private fun applyV1(list: List<Channel>) {
        if (list == all) return
        all = list; seedAccents(list); updateStatus()
        list.firstOrNull()?.let { TgServers.refreshSite(it.id) }
        if (tab == 0) { refreshRows(); if (intro.visibility != View.VISIBLE && currentFocus == null) rowsView.requestFocus() }
        if (searchShown) updateResults()
    }
    private fun applyV2(list: List<Channel>) {
        if (list == allV2) return
        allV2 = list; seedAccents(list); updateStatus()
        if (tab == 1) { refreshRows(); if (intro.visibility != View.VISIBLE && currentFocus == null) rowsView.requestFocus() }
        if (searchShown) updateResults()
    }
    private fun updateStatus() {
        val msg = when { tab == 0 && all.isEmpty() -> v1Msg; tab == 1 && allV2.isEmpty() -> "Loading 24/7 channels…"
            tab >= 2 && vodRows[tab - 2].isEmpty() -> { val w = if (tab == 3) "series" else "movies"; if (vodLoading[tab - 2]) "Loading $w…" else "Couldn't load $w. Check the connection and open the tab again." }
            else -> "" }
        status.text = msg; status.visibility = if (msg.isEmpty()) View.GONE else View.VISIBLE
    }

    /** Shows the saved lists instantly, then re-reads both update links so new / changed channels arrive on their own. */
    private fun loadChannels() {
        lastRefresh = System.currentTimeMillis()
        readCache("v1.json")?.let { try { applyV1(parseV1(it)) } catch (_: Exception) {} }
        readCache("v2.json")?.let { try { applyV2(parseV2(it)) } catch (_: Exception) {} }
        v1Msg = "Loading channels…"; updateStatus()
        io.execute {
            try {
                val j = get(BASE); val list = parseV1(j)
                if (list.isEmpty()) throw IOException("empty channel list")
                writeCache("v1.json", j)
                runOnUiThread { applyV1(list) }
            } catch (e: Exception) {
                runOnUiThread {
                    if (all.isEmpty()) { v1Msg = "Channels didn't load (${why(e)}). Check the connection and reopen the app."; updateStatus() }
                }
            }
        }
        io.execute {
            try {
                val j = get(BASE_V2); val list = parseV2(j)
                if (list.isEmpty()) throw IOException("empty list")
                writeCache("v2.json", j)
                runOnUiThread { applyV2(list) }
            } catch (_: Exception) {}
        }
    }

    private fun pool() = if (tab == 1) allV2 else all
    private fun favTiles(): List<Tile> {
        val l = pool().filter { it.key in favs }.map { Tile.Ch(it) }
        return if (l.isEmpty()) listOf(Tile.Hint) else l
    }

    /** Channels played lately (newest first), limited to the current tab's list. */
    private fun recentRow(): List<Row> {
        val keys = prefs.getString("recent_ch", "")!!.split(",").filter { it.isNotEmpty() }
        val byKey = pool().associateBy { it.key }
        val l = keys.mapNotNull { byKey[it] }.map { Tile.Ch(it) }
        return if (l.isEmpty()) emptyList() else listOf(Row(RECENT, l))
    }
    private fun rememberChannel(c: Channel) {
        val keys = listOf(c.key) + prefs.getString("recent_ch", "")!!.split(",").filter { it.isNotEmpty() && it != c.key }
        prefs.edit().putString("recent_ch", keys.take(20).joinToString(",")).apply()
    }
    private fun continueRow(): List<Row> {
        val type = if (tab == 3) "series" else "movie"
        val l = Movies.recent(prefs).filter { it.type == type }.map { Tile.Mv(it) }
        return if (l.isEmpty()) emptyList() else listOf(Row(CONTINUE, l))
    }
    /**
     * Loads the shelves of one VOD tab (2 = Movies, 3 = Series) in parallel and shows each one the moment it (and those above it) are ready.
     * A title is shown only in the first shelf that has it, so the same poster no longer repeats down the page.
     */
    private fun loadVod(t: Int) {
        val idx = t - 2
        if (idx !in 0..1 || vodLoading[idx] || vodRows[idx].isNotEmpty()) return
        vodLoading[idx] = true; updateStatus()
        val shelves = Movies.shelves(if (idx == 1) "series" else "movie")
        Thread {
            val n = shelves.size
            val res = arrayOfNulls<List<Movies.Title>>(n); val done = BooleanArray(n)
            val pool = Executors.newFixedThreadPool(6)
            var published = 0
            val publish = {
                runOnUiThread {
                    synchronized(res) {
                        var k = published
                        while (k < n && done[k]) k++
                        if (k > published) {
                            published = k
                            val fresh = dedupeShelves(shelves, res, k, vodRows[idx])
                            val before = vodRows[idx].size; vodRows[idx] = fresh
                            if (tab == t) {
                                if (before == 0) { refreshRows(); if (segs.none { it.isFocused } && !searchBtn.isFocused && !searchShown) rowsView.requestFocus() }
                                else {
                                    val base = continueRow().size
                                    rows = continueRow() + vodRows[idx]
                                    if (fresh.size > before) rowAdapter.notifyItemRangeInserted(base + before, fresh.size - before)
                                }
                            }
                            updateStatus()
                        }
                    }
                }
            }
            shelves.forEachIndexed { i, sh ->
                pool.execute {
                    try { val l = Movies.shelf(sh); if (l.isNotEmpty()) synchronized(res) { res[i] = l } } catch (_: Exception) {}
                    synchronized(res) { done[i] = true }
                    publish()
                }
            }
            pool.shutdown(); pool.awaitTermination(40, java.util.concurrent.TimeUnit.SECONDS)
            runOnUiThread { vodLoading[idx] = false; updateStatus() }
        }.start()
    }

    /** Builds the rows for the first [upTo] shelves, giving each title to the first shelf that has it. Shelves left with too few new titles are skipped. */
    private fun dedupeShelves(shelves: List<Movies.Shelf>, res: Array<List<Movies.Title>?>, upTo: Int, old: List<Row>): List<Row> {
        val seen = HashSet<String>(); val out = ArrayList<Row>()
        val byTitle = old.associateBy { it.title }
        for (i in 0 until upTo) {
            val l = res[i] ?: continue
            val keep = l.filter { seen.add(it.imdb) }.take(50)
            if (keep.size < 6) { keep.forEach { seen.remove(it.imdb) }; continue }
            val tiles = keep.map { Tile.Mv(it) }
            val title = shelves[i].title
            out.add(byTitle[title]?.takeIf { it.tiles == tiles } ?: Row(title, tiles))   // same Row object when nothing changed
        }
        return out
    }

    private fun refreshRows() {
        rows = when (tab) {
            0 -> listOf(Row(FAV, favTiles())) + recentRow() + all.groupBy { it.category }.toSortedMap().map { (k, v) -> Row(k, v.map { Tile.Ch(it) }) }
            1 -> listOf(Row(FAV, favTiles())) + recentRow() + allV2.groupBy { it.category }.toSortedMap().map { (k, v) -> Row(k, v.map { Tile.Ch(it) }) }
            else -> { loadVod(tab); continueRow() + vodRows[tab - 2] }
        }
        setHeroCollapsed(false)
        inner.clear()
        rowAdapter.notifyDataSetChanged()
        val first: Tile? = when (tab) {
            0 -> pool().firstOrNull()?.let { Tile.Ch(it) }
            1 -> pool().firstOrNull()?.let { Tile.Ch(it) }
            else -> rows.firstOrNull()?.tiles?.firstOrNull()
        }
        setHero(first, immediate = true)
        updateStatus()
    }

    private fun toggleFav(c: Channel) {
        val s = favs.toMutableSet(); val added = s.add(c.key); if (!added) s.remove(c.key)
        favs = s; prefs.edit().putStringSet("favs", s).apply()
        Toast.makeText(this, if (added) "Added to favorites" else "Removed from favorites", Toast.LENGTH_SHORT).show()
        for ((ri, ad) in inner) if (ri > 0) {
            val idx = rows.getOrNull(ri)?.tiles?.indexOfFirst { it is Tile.Ch && it.c.key == c.key } ?: -1
            if (idx >= 0) ad.notifyItemChanged(idx, "fav")
        }
        if (rows.firstOrNull()?.title == FAV) { rows[0].tiles = favTiles(); rowAdapter.notifyItemChanged(0) }
        (heroTile as? Tile.Ch)?.let { if (it.c.key == c.key) fillHero(it) }
    }

    private fun switchTab(t: Int) {
        if (t == tab) return
        val dir = if (t > tab) 1 else -1
        tab = t; styleSegs(); stopPreview()
        val shift = 28 * dp * dir
        rowsView.animate().cancel()
        rowsView.animate().alpha(0f).translationY(-shift).setDuration(150).withEndAction {
            refreshRows(); rowsView.scrollToPosition(0)
            rowsView.translationY = shift
            rowsView.animate().alpha(1f).translationY(0f).setDuration(280).setInterpolator(DecelerateInterpolator(1.8f)).start()
        }.start()
    }

    // ---------- hero ----------
    private val heroRunnable = Runnable { applyHero(heroTile) }
    private var heroCollapsed = false
    /** Folds the hero (preview + text) away once you go below the first row, and brings it back on the first row. */
    private fun setHeroCollapsed(c: Boolean) {
        if (c == heroCollapsed) return
        heroCollapsed = c
        val media = findViewById<View>(R.id.heroMedia); val area = findViewById<View>(R.id.heroArea)
        media.animate().cancel(); area.animate().cancel()
        if (c) {
            stopPreview(); ui.removeCallbacks(heroRunnable); ui.removeCallbacks(heroGuideRunnable)
            media.animate().alpha(0f).setDuration(140).start()
            area.animate().alpha(0f).setDuration(140).withEndAction { if (heroCollapsed) area.visibility = View.GONE }.start()
        } else {
            area.visibility = View.VISIBLE
            area.animate().alpha(1f).setDuration(220).start(); media.animate().alpha(1f).setDuration(220).start()
            fillHero(heroTile)
        }
    }
    private fun prefetch(url: String) {
        if (url.isEmpty()) return
        imageLoader.enqueue(ImageRequest.Builder(this).data(url).build())
    }
    private fun setHero(t: Tile?, immediate: Boolean = false) {
        heroTile = t; ui.removeCallbacks(heroRunnable)
        if (heroCollapsed && !immediate) return                 // hero is folded away: nothing to redraw while scrolling down
        if (immediate) fillHero(t) else ui.postDelayed(heroRunnable, 130)   // debounce fast scrolling
    }
    private fun applyHero(t: Tile?) {
        heroText.animate().cancel()
        heroText.animate().alpha(0f).translationX(-10 * dp).setDuration(110).withEndAction {
            fillHero(t); heroText.translationX = 16 * dp
            heroText.animate().alpha(1f).translationX(0f).setDuration(300).setInterpolator(DecelerateInterpolator(2f)).start()
        }.start()
    }
    /** Tints the still hero panel (shown until the live preview is ready) with the channel's colour. */
    private fun glowHero(c: Int, partner: Int) {
        orbBg.colors = intArrayOf(Ui.mix(Accent.ABYSS, c, 0.42f), Ui.mix(Accent.ABYSS, partner, 0.12f))
        heroProgress.progressTintList = ColorStateList.valueOf(c)
    }
    private fun showBackdrop(url: String?) {
        if (url.isNullOrEmpty()) { heroBackdrop.animate().alpha(0f).setDuration(300).start(); heroOrb.animate().alpha(1f).setDuration(250).start(); return }
        heroOrb.animate().alpha(0f).setDuration(200).start()
        heroBackdrop.load(url) {
            crossfade(180)
            listener(onSuccess = { _, _ -> if ((heroTile as? Tile.Mv)?.m?.background == url) heroBackdrop.animate().alpha(1f).setDuration(260).start() })
        }
    }
    private fun fillHero(t: Tile?) {
        heroChips.removeAllViews()
        if (t !is Tile.Mv) showBackdrop(null)
        schedulePreview((t as? Tile.Ch)?.c)
        when (t) {
            is Tile.Ch -> {
                heroDot.visibility = View.VISIBLE; heroDot.backgroundTintList = ColorStateList.valueOf(Accent.LIVE)
                heroBadge.text = (if (t.c.v2) "24/7   ·   " else "") + t.c.category
                heroTitle.text = t.c.name
                heroChips.addView(chip("▶   Watch Live", true))
                heroChips.addView(chip(keyHint("Hold OK", if (t.c.key in favs) "Remove from favourites" else "Add to favourites")))
                if (t.c.logo.isBlank()) {
                    heroLogo.visibility = View.GONE; heroMono.visibility = View.VISIBLE
                    heroMono.text = initials(t.c.name); heroMono.textSize = if (heroMono.text.length > 1) 62f else 84f
                    heroMono.setTextColor(accentOf(t.c.key))
                } else {
                    heroMono.visibility = View.GONE; heroLogo.visibility = View.VISIBLE
                    heroLogo.load(t.c.logo) {
                        allowHardware(false); crossfade(true)
                        listener(onSuccess = { _, r ->
                            extractAccent(t.c.key, r.drawable) { c -> if ((heroTile as? Tile.Ch)?.c?.key == t.c.key) glowHero(c, Accent.partner(c)) }
                        })
                    }
                }
                accents[t.c.key]?.let { glowHero(it, Accent.partner(it)) }
                scheduleHeroGuide(t.c)
            }
            is Tile.Mv -> {
                val red = 0xFFE50914.toInt()
                heroDot.visibility = View.GONE
                heroTitle.text = t.m.name
                heroBadge.text = listOfNotNull(
                    if (t.m.isSeries) "Series" else "Movie",
                    t.m.year.ifEmpty { null },
                    if (t.m.rating.isNotEmpty()) "★ ${t.m.rating}" else null,
                    t.m.genres.take(2).joinToString(" · ").ifEmpty { null }
                ).joinToString("   ·   ")
                heroChips.addView(chip("▶   Details", true))
                heroProg.visibility = View.GONE; heroProgress.visibility = View.GONE; heroNext.visibility = View.GONE
                heroDesc.text = t.m.description; heroDesc.visibility = if (t.m.description.isEmpty()) View.GONE else View.VISIBLE
                ui.removeCallbacks(heroGuideRunnable)
                glowHero(red, 0xFF5A0A12.toInt()); showBackdrop(t.m.background.ifEmpty { t.m.poster })
            }
            else -> {
                heroDot.visibility = View.GONE; heroBadge.text = ""; heroTitle.text = "AK TV"
                heroMono.visibility = View.GONE; heroLogo.visibility = View.VISIBLE; heroLogo.setImageResource(R.drawable.ic_mark)
                glowHero(Accent.SIGNAL, Accent.INDIGO); scheduleHeroGuide(null)
            }
        }
    }

    // ---------- programme details ----------
    private fun fetchGuide(c: Channel, cb: (Guide?) -> Unit) {
        if (c.v2) { cb(null); return }
        Epg.cached(c.id)?.let { cb(it); return }
        io.execute {
            val g = try { Epg.parse(get("$GUIDE/${c.id}", 2)).also { Epg.put(c.id, it) } } catch (e: Exception) { null }
            runOnUiThread { cb(g) }
        }
    }
    private fun span(sb: SpannableStringBuilder, from: Int, color: Int) {
        sb.setSpan(ForegroundColorSpan(color), from, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sb.setSpan(TypefaceSpan(fSemi), from, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }
    /** "NOW   Show title   9:30 PM – 10:00 PM" */
    private fun progLine(label: String, p: Programme, range: Boolean = true): CharSequence {
        val sb = SpannableStringBuilder()
        var s = sb.length; sb.append(label); span(sb, s, Accent.DIM)
        sb.append("   ")
        s = sb.length; sb.append(p.title); span(sb, s, Accent.TEXT)
        val r = if (range) Epg.range(p) else Epg.startOf(p)
        if (r.isNotBlank()) { sb.append("   "); s = sb.length; sb.append(r); sb.setSpan(ForegroundColorSpan(Accent.DIM), s, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
        return sb
    }
    private val heroGuideRunnable = Runnable {
        val c = (heroTile as? Tile.Ch)?.c ?: return@Runnable
        fetchGuide(c) { g -> if ((heroTile as? Tile.Ch)?.c?.key == c.key) applyHeroGuide(g) }
    }
    private fun scheduleHeroGuide(c: Channel?) {
        ui.removeCallbacks(heroGuideRunnable)
        if (c == null) { applyHeroGuide(null); return }
        if (c.v2) {
            applyHeroGuide(null)
            heroDesc.text = "Always-on channel with several servers; if one stalls the player switches by itself."
            heroDesc.visibility = View.VISIBLE; return
        }
        val g = Epg.cached(c.id)
        if (g != null) applyHeroGuide(g) else { applyHeroGuide(null); ui.postDelayed(heroGuideRunnable, 320) }
    }
    private fun applyHeroGuide(g: Guide?) {
        val n = g?.now; val nx = g?.next
        heroProg.visibility = if (n != null) View.VISIBLE else View.GONE
        heroProgress.visibility = heroProg.visibility
        if (n != null) { heroProg.text = progLine("NOW", n); heroProgress.progress = (Epg.progress(n) * 1000).toInt() }
        val d = n?.desc.orEmpty()
        heroDesc.text = d; heroDesc.visibility = if (d.isEmpty()) View.GONE else View.VISIBLE
        heroNext.visibility = if (nx != null && !compact) View.VISIBLE else View.GONE
        if (nx != null) heroNext.text = progLine("NEXT", nx, range = false)
    }

    // ---------- player overlay ----------
    private val hideInfo = Runnable {
        infoCard.animate().alpha(0f).translationY(14 * dp).setDuration(450).start()
        playerScrim.animate().alpha(0f).setDuration(450).start()
        playerClock.animate().alpha(0f).setDuration(450).start()
    }
    private fun showInfo() {
        ui.removeCallbacks(hideInfo)
        infoCard.animate().alpha(1f).translationY(0f).setDuration(260).setInterpolator(DecelerateInterpolator(2f)).start()
        playerScrim.animate().alpha(1f).setDuration(260).start()
        playerClock.animate().alpha(1f).setDuration(260).start()
        ui.postDelayed(hideInfo, 5000)
    }
    private fun bindInfo(ch: Channel) {
        infoName.text = ch.name
        infoCat.text = "${if (ch.v2) "V2 · 24/7" else ch.category}   ${current + 1} of ${shown.size}"
        bindLogo(infoLogo, infoMono, ch)
        val ac = accentOf(ch.key)
        spinner.indeterminateTintList = ColorStateList.valueOf(ac)
        infoProgress.progressTintList = ColorStateList.valueOf(ac)
        infoNow.visibility = View.GONE; infoProgress.visibility = View.GONE; infoDesc.visibility = View.GONE; infoNext.visibility = View.GONE
        if (ch.v2) { infoNow.text = "24/7 live channel"; infoNow.visibility = View.VISIBLE }
        else fetchGuide(ch) { g -> if (shown.getOrNull(current)?.key == ch.key) fillInfoGuide(g) }
        showInfo()
    }
    private fun fillInfoGuide(g: Guide?) {
        val n = g?.now
        if (n == null) { infoNow.visibility = View.GONE; infoProgress.visibility = View.GONE; infoDesc.visibility = View.GONE; infoNext.visibility = View.GONE; return }
        infoNow.text = progLine("NOW", n); infoNow.visibility = View.VISIBLE
        infoProgress.progress = (Epg.progress(n) * 1000).toInt(); infoProgress.visibility = View.VISIBLE
        infoDesc.text = n.desc; infoDesc.visibility = if (n.desc.isEmpty()) View.GONE else View.VISIBLE
        val nx = g.next
        if (nx != null) { infoNext.text = progLine("NEXT", nx); infoNext.visibility = View.VISIBLE } else infoNext.visibility = View.GONE
    }
    private fun playMsg(msg: String?) {
        if (msg == null) playStatus.visibility = View.GONE else { playStatus.text = msg; playStatus.visibility = View.VISIBLE }
    }

    // ---------- native live playback ----------
    /** User picked a channel (or zapped): start with the site's current sources, never a saved web engine. */
    private fun play(index: Int) {
        if (shown.isEmpty()) return
        current = (index + shown.size) % shown.size
        val ch = shown[current]
        stopPreview(); rememberChannel(ch); recovery.reset()
        nativeSources = emptyList(); serverIdx = 0
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); layer.keepScreenOn = true
        if (layer.visibility != View.VISIBLE) { layer.animate().cancel(); layer.alpha = 1f; layer.visibility = View.VISIBLE }
        playMsg(null); bindInfo(ch)
        resolveAndStart(ch)
    }

    /** Signed URLs/keys come from TG at playback time. There is no wait for another website or playlist. */
    private fun resolveAndStart(ch: Channel, fresh: Boolean = false) {
        val token = ++playToken
        resolveJob?.cancel(true)
        nativeSources = emptyList(); serverIdx = 0
        disarmStall(); releasePlayer(); spinner.visibility = View.VISIBLE
        playMsg(if (fresh) "Refreshing TG TV stream…" else "Tuning in…")
        resolveJob = playbackIo.submit {
            try {
                val sources = TgServers.fetch(ch.id, fresh = fresh, v2 = ch.v2)
                runOnUiThread {
                    if (token != playToken || layer.visibility != View.VISIBLE) return@runOnUiThread
                    nativeSources = sources
                    // Remember a server, not its expiring URL/keys. A redeploy can safely change the list/order.
                    val saved = prefs.getString("source_${ch.key}", null)
                    serverIdx = sources.indexOfFirst { sourceKey(it) == saved }.coerceAtLeast(0)
                    startNative()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    if (token == playToken && layer.visibility == View.VISIBLE) {
                        nativeSources = emptyList()
                        advanceNative(if (e is TgResolveException) e.message.orEmpty() else "Couldn't reach TG TV")
                    }
                }
            }
        }
    }

    private fun sourceKey(source: TgStream): String = java.security.MessageDigest.getInstance("SHA-256")
        .digest(source.url.substringBefore('?').toByteArray()).joinToString("") { "%02x".format(it) }

    // Keep a comfortable live buffer, but don't wait for the full buffer before showing the first frame.
    private fun liveCfg() = MediaItem.LiveConfiguration.Builder().setTargetOffsetMs(12000)
        .setMinOffsetMs(6000).setMaxOffsetMs(40000).setMinPlaybackSpeed(1f).setMaxPlaybackSpeed(1f).build()

    /** Shared by full-screen playback and previews so headers, type, redirects and DRM cannot drift apart. */
    private fun nativeMediaSource(source: TgStream): MediaSource {
        val data = Streams.factory(source)
        val factory: MediaSource.Factory = when (source.type) {
            "hls" -> HlsMediaSource.Factory(data)
            "mp4" -> ProgressiveMediaSource.Factory(data)
            else -> DashMediaSource.Factory(data)
        }
        val item = MediaItem.Builder().setUri(source.url).setLiveConfiguration(liveCfg())
            .setMimeType(when (source.type) { "hls" -> MimeTypes.APPLICATION_M3U8; "mp4" -> MimeTypes.VIDEO_MP4; else -> MimeTypes.APPLICATION_MPD })
        source.drm?.let { drm ->
            val license = ClearKey.license(drm)
            if (license != null) {
                val manager = DefaultDrmSessionManager.Builder()
                    .setUuidAndExoMediaDrmProvider(C.CLEARKEY_UUID, FrameworkMediaDrm.DEFAULT_PROVIDER)
                    .setMultiSession(true).setPlayClearSamplesWithoutKeys(true)
                    .build(LocalMediaDrmCallback(license))
                factory.setDrmSessionManagerProvider { manager }
            } else {
                val type = drm.optString("type")
                val licenseUrl = drm.optString("licenseUrl", drm.optString("license_url"))
                if (!type.equals("widevine", true) || !licenseUrl.startsWith("https://"))
                    throw IOException("TG TV did not provide usable DRM for this source")
                val headers = HashMap<String, String>()
                drm.optJSONObject("headers")?.let { h -> h.keys().forEach { headers[it] = h.optString(it) } }
                item.setDrmConfiguration(MediaItem.DrmConfiguration.Builder(C.WIDEVINE_UUID)
                    .setLicenseUri(licenseUrl).setLicenseRequestHeaders(headers).build())
            }
        }
        return factory.createMediaSource(item.build())
    }

    private fun startNative() {
        val source = nativeSources.getOrNull(serverIdx) ?: return
        spinner.visibility = View.VISIBLE
        infoNext.text = "${source.label} · ${serverIdx + 1} of ${nativeSources.size}" +
            if (nativeSources.size > 1) "   ·   OK switches server" else ""
        infoNext.visibility = View.VISIBLE
        try { attach(nativeMediaSource(source)) }
        catch (_: Exception) { advanceNative("This TG TV source could not be opened by the native player") }
    }

    private fun attach(ms: MediaSource) {
        releasePlayer()
        stopPreview()
        val rf = DefaultRenderersFactory(this).setEnableDecoderFallback(true)
            .forceEnableMediaCodecAsynchronousQueueing()   // smoother 1080p / 50fps decoding on TV boxes
        val lc = DefaultLoadControl.Builder().setBufferDurationsMs(15000, 40000, 750, 2500).setBackBuffer(0, false)
            .setTargetBufferBytes(96 * 1024 * 1024).setPrioritizeTimeOverSizeThresholds(true).build()
        capH = startCap(); badSeconds = 0
        // steadier quality: switch up only after 30 s of headroom, so adaptive HD streams stop flip-flopping between sizes
        val ts = androidx.media3.exoplayer.trackselection.DefaultTrackSelector(this, AdaptiveTrackSelection.Factory(15_000, 20_000, 20_000, 0.75f)).apply {
            setParameters(buildUponParameters()
                .setMaxVideoSize(capH * 16 / 9, capH)
                .setPreferredAudioLanguages(*if (shown.getOrNull(current)?.name?.contains("Hindi", true) == true) arrayOf("hi", "hin") else emptyArray())
                .setViewportSizeToPhysicalDisplaySize(this@MainActivity, true)   // prefer what the screen can show...
                .setExceedVideoConstraintsIfNecessary(true)                      // ...but if every track is bigger, still play the smallest
                .setExceedRendererCapabilitiesIfNecessary(true))                 // (false here left audio playing over a black picture)
        }
        val bw = androidx.media3.exoplayer.upstream.DefaultBandwidthMeter.Builder(this).setInitialBitrateEstimate(4_000_000L).build()
        val p = ExoPlayer.Builder(this, rf).setLoadControl(lc).setTrackSelector(ts).setBandwidthMeter(bw).build()
        p.setVideoScalingMode(C.VIDEO_SCALING_MODE_SCALE_TO_FIT)
        trackSel = ts; p.addAnalyticsListener(dropWatcher)
        renderedFrame = false
        p.setMediaSource(ms); p.playWhenReady = true; p.addListener(listenerFor(p))
        pv.player = p; player = p; p.prepare()
        armStall(10000)
        showInfo()
    }

    private fun listenerFor(owner: ExoPlayer) = object : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
            if (player !== owner) return
            when (state) {
                Player.STATE_READY -> {
                    spinner.visibility = View.GONE; playMsg(null); disarmStall()
                    ui.removeCallbacks(videoCheck)
                    if (!renderedFrame) ui.postDelayed(videoCheck, 6000)
                    ui.removeCallbacks(stablePlayback); ui.postDelayed(stablePlayback, 30_000)
                }
                Player.STATE_BUFFERING -> {
                    ui.removeCallbacks(stablePlayback)
                    spinner.visibility = View.VISIBLE; armStall(11000)
                }
                Player.STATE_ENDED -> onStall()
            }
        }
        override fun onRenderedFirstFrame() {
            if (player !== owner) return
            renderedFrame = true; ui.removeCallbacks(videoCheck)
            val ch = shown.getOrNull(current) ?: return
            nativeSources.getOrNull(serverIdx)?.let { prefs.edit().putString("source_${ch.key}", sourceKey(it)).apply() }
        }
        override fun onPlayerError(error: PlaybackException) {
            if (player !== owner) return
            val code = httpCode(error)
            val message = when {
                code == 451 -> "TG TV's upstream is unavailable in this region"
                code != null -> "TG TV's stream returned HTTP $code"
                error.errorCode in 6000..6999 -> "TG TV did not supply usable DRM for this source"
                error.errorCode in 4000..4999 -> "This device couldn't decode this source"
                else -> "The live stream stopped responding"
            }
            advanceNative(message, refreshFirst = code in setOf(401, 403, 410))
        }
    }

    private fun httpCode(error: Throwable): Int? {
        var cause: Throwable? = error
        repeat(8) {
            if (cause is HttpDataSource.InvalidResponseCodeException) return (cause as HttpDataSource.InvalidResponseCodeException).responseCode
            cause = cause?.cause
        }
        return null
    }

    // READY alone isn't proof of a working stream. Reset the retry budget only after sustained playback.
    private val stablePlayback = Runnable { if (player?.isPlaying == true) recovery.reset() }
    private val videoCheck = Runnable {
        val p = player ?: return@Runnable
        if (layer.visibility == View.VISIBLE && p.playbackState == Player.STATE_READY &&
            !renderedFrame && p.currentTracks.containsType(C.TRACK_TYPE_VIDEO)) advanceNative("No picture from this source")
    }
    private val stallRunnable = Runnable { onStall() }
    private fun armStall(ms: Long) { ui.removeCallbacks(stallRunnable); ui.postDelayed(stallRunnable, ms) }
    private fun disarmStall() { ui.removeCallbacks(stallRunnable) }
    private fun onStall() {
        if (layer.visibility == View.VISIBLE && player != null) advanceNative("The live stream stopped responding")
    }

    /** Bounded recovery, always in ExoPlayer: current TG servers, one metadata refresh, then an actionable error. */
    private fun advanceNative(reason: String, refreshFirst: Boolean = false) {
        val ch = shown.getOrNull(current) ?: return
        disarmStall(); releasePlayer()
        when (val action = recovery.failed(serverIdx, nativeSources.size, refreshFirst)) {
            is PlaybackRecovery.Action.Source -> {
                serverIdx = action.index
                playMsg("Trying ${nativeSources[serverIdx].label}…")
                val token = playToken
                ui.post { if (token == playToken && layer.visibility == View.VISIBLE) startNative() }
            }
            PlaybackRecovery.Action.Refresh -> {
                TgServers.invalidate(ch.id, ch.v2)
                resolveAndStart(ch, fresh = true)
            }
            PlaybackRecovery.Action.Unavailable -> fail("$reason. Hold OK to reload or choose another channel.")
        }
    }

    /** Left / OK switches TG sources, not players; a single-source channel simply reloads. */
    private fun switchServer() {
        if (nativeSources.size < 2) { reloadStream(); return }
        playToken++; resolveJob?.cancel(true); recovery.reset()
        disarmStall(); releasePlayer()
        serverIdx = (serverIdx + 1) % nativeSources.size
        playMsg("Switching to ${nativeSources[serverIdx].label}…")
        startNative()
    }

    /** Manual reload really bypasses cached signed URLs and re-reads the site's current player configuration. */
    private fun reloadStream() {
        val ch = shown.getOrNull(current) ?: return
        recovery.reset(); TgServers.invalidate(ch.id, ch.v2)
        resolveAndStart(ch, fresh = true)
    }

    private fun fail(message: String) {
        disarmStall(); releasePlayer(); spinner.visibility = View.GONE
        playMsg(message); showInfo()
    }

    private fun releasePlayer() {
        ui.removeCallbacks(videoCheck); ui.removeCallbacks(stablePlayback)
        pv.player = null
        val old = player; player = null; trackSel = null
        old?.removeAnalyticsListener(dropWatcher); old?.release()
    }
    private fun closePlayer() {
        playToken++; resolveJob?.cancel(true); disarmStall(); playMsg(null)
        releasePlayer(); nativeSources = emptyList(); ui.removeCallbacks(hideInfo); resetGuide()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); layer.keepScreenOn = false
        layer.animate().cancel(); layer.visibility = View.GONE
        if (tab < 2) { val sel = heroTile; refreshRowsKeepFocus(); setHero(sel, immediate = true) }
    }

    private fun shortOk() {
        if (nativeSources.size > 1 && infoCard.alpha > 0.5f) switchServer() else showInfo()
    }

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        if (intro.visibility == View.VISIBLE) { if (e.action == KeyEvent.ACTION_DOWN) intro.skip(); return true }
        if (searchShown) return searchKey(e) || super.dispatchKeyEvent(e)
        if (layer.visibility == View.VISIBLE && guideShown) return guideKey(e) || super.dispatchKeyEvent(e)
        if (layer.visibility == View.VISIBLE && (e.keyCode == KeyEvent.KEYCODE_DPAD_CENTER || e.keyCode == KeyEvent.KEYCODE_ENTER)) {
            // tap OK = info / next TG source, hold OK = fetch a fresh stream
            if (e.action == KeyEvent.ACTION_DOWN) {
                if (e.repeatCount == 0) okLong = false
                else if (e.repeatCount == 8 && !okLong) { okLong = true; reloadStream() }
            } else if (e.action == KeyEvent.ACTION_UP && !okLong) shortOk()
            return true
        }
        if (layer.visibility == View.VISIBLE && e.action == KeyEvent.ACTION_DOWN) when (e.keyCode) {
            KeyEvent.KEYCODE_BACK -> { closePlayer(); return true }
            KeyEvent.KEYCODE_DPAD_RIGHT -> { openGuide(); return true }
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_UP -> { play(current - 1); return true }
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN -> { play(current + 1); return true }
            KeyEvent.KEYCODE_MENU -> { reloadStream(); return true }
            KeyEvent.KEYCODE_DPAD_LEFT -> { switchServer(); return true }
        }
        // Back from a lower row goes back to the top of the page (the hero row) first; Back again leaves the app.
        if (layer.visibility != View.VISIBLE && e.action == KeyEvent.ACTION_DOWN && e.keyCode == KeyEvent.KEYCODE_BACK) {
            val f = currentFocus
            if (f != null && isInside(f, rowsView)) {
                val pos = rowsView.findContainingItemView(f)?.let { rowsView.getChildAdapterPosition(it) } ?: -1
                if (pos > 0) { rowsView.scrollToPosition(0); rowsView.post { focusRowAt(0) }; return true }
            }
        }
        if (layer.visibility != View.VISIBLE && e.action == KeyEvent.ACTION_DOWN && e.keyCode == KeyEvent.KEYCODE_SEARCH) { openSearch(); return true }
        if (layer.visibility != View.VISIBLE && e.action == KeyEvent.ACTION_DOWN && e.keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
            val f = currentFocus
            if (f != null && isInside(f, rowsView)) {
                val next = FocusFinder.getInstance().findNextFocus(window.decorView as ViewGroup, f, View.FOCUS_DOWN)
                if (next == null || !isInside(next, rowsView)) {
                    val rowView = rowsView.findContainingItemView(f)
                    val pos = rowView?.let { rowsView.getChildAdapterPosition(it) } ?: -1
                    if (pos >= 0 && pos + 1 < rows.size) {
                        rowsView.scrollToPosition(pos + 1)
                        rowsView.post { focusRowAt(pos + 1) }
                    }
                    return true   // never jump to another tab or the top bar
                }
            }
        }
        // Up from the first row goes to the open tab in the top bar (Google TV behaviour).
        if (layer.visibility != View.VISIBLE && e.action == KeyEvent.ACTION_DOWN && e.keyCode == KeyEvent.KEYCODE_DPAD_UP) {
            val f = currentFocus
            if (f != null && isInside(f, rowsView)) {
                val pos = rowsView.findContainingItemView(f)?.let { rowsView.getChildAdapterPosition(it) } ?: -1
                if (pos == 0) { segs[tab].requestFocus(); return true }
            }
        }
        if (layer.visibility != View.VISIBLE && e.action == KeyEvent.ACTION_DOWN) {
            val f = currentFocus
            val inBar = f != null && (f === searchBtn || segs.any { it === f })
            if (f != null && inBar) when (e.keyCode) {
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    // back to the card you came from
                    val back = lastRowsFocus
                    if (back != null && back.isAttachedToWindow && isInside(back, rowsView) && back.requestFocus()) return true
                    rowsView.requestFocus(); return true
                }
                KeyEvent.KEYCODE_DPAD_UP -> return true
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    val dir = if (e.keyCode == KeyEvent.KEYCODE_DPAD_LEFT) View.FOCUS_LEFT else View.FOCUS_RIGHT
                    val next = FocusFinder.getInstance().findNextFocus(window.decorView as ViewGroup, f, dir)
                    if (next == null || !(next === searchBtn || segs.any { it === next })) return true   // stay inside the bar
                }
            }
        }
        return super.dispatchKeyEvent(e)
    }
    /** While the app is open, quietly re-reads both channel lists every few minutes (never while a channel is playing), so website changes arrive without a restart. */
    private val autoRefresh = object : Runnable {
        override fun run() {
            if (layer.visibility != View.VISIBLE && !searchShown && System.currentTimeMillis() - lastRefresh > 4 * 60_000L) loadChannels()
            TgServers.refreshSite()
            ui.postDelayed(this, 5 * 60_000L)
        }
    }
    override fun onStart() {
        super.onStart()
        ui.removeCallbacks(autoRefresh); ui.postDelayed(autoRefresh, 5 * 60_000L)
        if (tab >= 2 && ::rowsView.isInitialized && rows.isNotEmpty()) refreshRowsKeepFocus()
        if (System.currentTimeMillis() - lastRefresh > 3 * 60_000L) loadChannels()
    }
    override fun onStop() {
        super.onStop(); ui.removeCallbacks(autoRefresh); stopPreview(); window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); playToken++; resolveJob?.cancel(true); disarmStall(); releasePlayer(); nativeSources = emptyList(); layer.visibility = View.GONE; layer.keepScreenOn = false; resetGuide()
        if (searchShown) hideSearch(false)
    }

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null); resolveJob?.cancel(true); previewJob?.cancel(true)
        playbackIo.shutdownNow(); io.shutdownNow()
        super.onDestroy()
    }

    // ---------- search ----------
    private fun buildKeys() {
        val kw = (50 * dp).toInt(); val kh = (46 * dp).toInt(); val m = (3 * dp).toInt()
        for (r in listOf("ABCDEF", "GHIJKL", "MNOPQR", "STUVWX", "YZ0123", "456789")) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for (ch in r) row.addView(keyView(ch.toString(), kw, kh, m) { typeChar(ch.lowercaseChar()) })
            searchKeys.addView(row)
        }
        val wide = 2 * (kw + 2 * m) - 2 * m
        val last = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        last.addView(keyView("Space", wide, kh, m) { typeChar(' ') })
        last.addView(keyView("Delete", wide, kh, m) { backspace() })
        last.addView(keyView("Clear", wide, kh, m) { searchBuf.setLength(0); renderQuery(); updateResults() })
        searchKeys.addView(last)
    }
    private fun keyView(label: String, w: Int, h: Int, m: Int, onClick: () -> Unit) = TextView(this).apply {
        text = label; gravity = Gravity.CENTER; textSize = 15f; typeface = fSemi; setTextColor(Accent.DIM)
        isFocusable = true; isClickable = true
        background = Ui.focusBg(dp, 12f, rest = Accent.SURFACE)
        layoutParams = LinearLayout.LayoutParams(w, h).apply { setMargins(m, m, m, m) }
        setOnFocusChangeListener { v, f -> setTextColor(if (f) Accent.ABYSS else Accent.DIM); focusAnim(v, f) }
        setOnClickListener { onClick() }
    }
    private fun typeChar(c: Char) { if (searchBuf.length < 40) { searchBuf.append(c); renderQuery(); updateResults() } }
    private fun backspace() { if (searchBuf.isNotEmpty()) { searchBuf.setLength(searchBuf.length - 1); renderQuery(); updateResults() } }
    private fun renderQuery() {
        if (searchBuf.isEmpty()) { searchQuery.text = "Type to search"; searchQuery.setTextColor(Accent.FAINT) }
        else { searchQuery.text = "$searchBuf|"; searchQuery.setTextColor(Accent.TEXT) }
    }
    private fun runSearch(q: String): List<Channel> {
        val toks = q.lowercase().split(' ').filter { it.isNotBlank() }
        if (toks.isEmpty()) return emptyList()
        fun score(c: Channel): Int {
            val n = c.name.lowercase()
            return when { n.startsWith(toks[0]) -> 0; n.contains(" " + toks[0]) -> 1; else -> 2 }
        }
        return (all + allV2).filter { c -> val hay = (c.name + " " + c.category).lowercase(); toks.all { hay.contains(it) } }
            .sortedWith(compareBy({ score(it) }, { it.v2 }, { it.name.lowercase() })).take(120)
    }
    private var movieResults = listOf<Tile>(); private var movieSearchToken = 0; private var movieSearching = false
    private var searchScope = 0   // tab the search was opened from: 0/1 = channels, 2 = movies, 3 = series
    private val movieRow = Row("Results", emptyList())
    private val movieSearchAdapter by lazy { TileAdapter(movieRow) }
    private val movieSearchList by lazy {
        RecyclerView(this).apply {
            layoutManager = SnapLayoutManager(this@MainActivity, RecyclerView.HORIZONTAL)
            adapter = movieSearchAdapter; itemAnimator = null; overScrollMode = View.OVER_SCROLL_NEVER
            clipChildren = false; clipToPadding = false; visibility = View.GONE
            setPadding((4 * dp).toInt(), (10 * dp).toInt(), (80 * dp).toInt(), (16 * dp).toInt())
            val parent = searchResults.parent as ViewGroup
            parent.addView(this, parent.indexOfChild(searchResults), LinearLayout.LayoutParams(-1, -2))
        }
    }
    private val movieSearchRunnable = Runnable {
        val q = searchBuf.toString().trim(); val tok = ++movieSearchToken
        Thread {
            val only = if (searchScope == 3) "series" else "movie"
            val r = try { Movies.search(q, only).filter { it.type == only && it.poster.isNotEmpty() }.distinctBy { it.imdb }.take(40) } catch (_: Exception) { emptyList() }
            runOnUiThread {
                if (tok != movieSearchToken || !searchShown) return@runOnUiThread
                movieSearching = false
                movieResults = r.map { Tile.Mv(it) }; movieRow.tiles = movieResults
                movieSearchAdapter.notifyDataSetChanged(); movieSearchList.scrollToPosition(0)
                movieSearchList.visibility = if (movieResults.isEmpty()) View.GONE else View.VISIBLE
                renderCounts(q)
            }
        }.start()
    }
    private fun renderCounts(q: String) {
        val m = movieResults.size
        val vod = searchScope >= 2; val kind = if (searchScope == 3) "series" else "movies"
        searchCount.text = when {
            vod && q.isEmpty() -> "Search $kind"
            vod -> "$m " + (if (searchScope == 3) "series" else if (m == 1) "movie" else "movies")
            q.isEmpty() -> "Search ${all.size + allV2.size} channels"
            else -> "${results.size} channel" + (if (results.size == 1) "" else "s")
        }
        searchEmpty.text = when {
            q.isEmpty() -> when (searchScope) { 2 -> "Type a movie name, like Inception or Dangal."; 3 -> "Type a series name, like Friends or Money Heist."; else -> "Type a channel name, like Star Sports or Sony." }
            vod && m == 0 -> if (movieSearching) "Searching for \"$q\"…" else "Nothing matches \"$q\"."
            !vod && results.isEmpty() -> "Nothing matches \"$q\"."
            else -> ""
        }
        searchEmpty.visibility = if (searchEmpty.text.isEmpty()) View.GONE else View.VISIBLE
    }
    private fun updateResults() {
        val q0 = searchBuf.toString().trim()
        ui.removeCallbacks(movieSearchRunnable); movieSearchToken++
        movieResults = emptyList(); movieRow.tiles = movieResults; movieSearchAdapter.notifyDataSetChanged()
        movieSearchList.visibility = View.GONE
        val vod = searchScope >= 2
        movieSearching = vod && q0.length >= 2
        if (movieSearching) ui.postDelayed(movieSearchRunnable, 350)
        val q = q0
        searchResults.visibility = if (vod) View.GONE else View.VISIBLE
        results = if (q.isEmpty() || vod) emptyList() else runSearch(q)
        searchAdapter.notifyDataSetChanged(); searchResults.scrollToPosition(0)
        renderCounts(q)
    }
    private fun openSearch() {
        if (searchShown || layer.visibility == View.VISIBLE || intro.visibility == View.VISIBLE) return
        searchShown = true; searchScope = tab; searchBuf.setLength(0); renderQuery(); updateResults()
        if (home.descendantFocusability != ViewGroup.FOCUS_BLOCK_DESCENDANTS) {
            lastHomeFocus = currentFocus; home.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        }
        searchLayer.animate().cancel()
        searchLayer.visibility = View.VISIBLE; searchLayer.alpha = 0f
        searchLayer.animate().alpha(1f).setDuration(220).start()
        searchKeys.getChildAt(0)?.let { (it as ViewGroup).getChildAt(0)?.requestFocus() }
    }
    private fun closeSearch() { hideSearch(true) }
    private fun hideSearch(animate: Boolean) {
        if (!searchShown) return
        searchShown = false
        searchLayer.animate().cancel()
        if (animate) searchLayer.animate().alpha(0f).setDuration(180).withEndAction { if (!searchShown) searchLayer.visibility = View.GONE }.start()
        else searchLayer.visibility = View.GONE
        if (home.descendantFocusability == ViewGroup.FOCUS_BLOCK_DESCENDANTS && !guideShown) {
            home.descendantFocusability = homeFocusMode
            val back = lastHomeFocus; lastHomeFocus = null
            if (back?.takeIf { it.isAttachedToWindow }?.requestFocus() != true) searchBtn.requestFocus()
        }
    }
    private fun searchKey(e: KeyEvent): Boolean {
        if (e.action != KeyEvent.ACTION_DOWN) return false
        val k = e.keyCode
        when {
            k == KeyEvent.KEYCODE_BACK -> { closeSearch(); return true }
            k in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z -> { typeChar('a' + (k - KeyEvent.KEYCODE_A)); return true }
            k in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> { typeChar('0' + (k - KeyEvent.KEYCODE_0)); return true }
            k == KeyEvent.KEYCODE_SPACE -> { typeChar(' '); return true }
            k == KeyEvent.KEYCODE_DEL -> { backspace(); return true }
        }
        return false
    }
    private fun playFromSearch(i: Int) {
        if (i !in results.indices) return
        shown = results; shownCat = "Search"
        closeSearch(); play(i)
    }

    private val searchAdapter = object : RecyclerView.Adapter<VH>() {
        override fun getItemCount() = results.size
        override fun onCreateViewHolder(p: ViewGroup, t: Int): VH {
            val v = LayoutInflater.from(p.context).inflate(R.layout.item_guide_ch, p, false)
            v.background = Ui.focusBg(dp, 14f)
            v.findViewById<View>(R.id.chLogoBox).background = Ui.pill(dp, Accent.SURFACE, 12f)
            return VH(v)
        }
        override fun onBindViewHolder(h: VH, i: Int) {
            val v = h.itemView; val ch = results[i]
            val name = v.findViewById<TextView>(R.id.chName)
            name.text = ch.name
            v.findViewById<TextView>(R.id.chNum).text = (i + 1).toString()
            bindLogo(v.findViewById(R.id.chLogo), v.findViewById(R.id.chMono), ch)
            val sub = v.findViewById<TextView>(R.id.chNow)
            sub.text = if (ch.v2) "V2 · 24/7" else ch.category; sub.setTextColor(Accent.FAINT); sub.visibility = View.VISIBLE
            v.findViewById<View>(R.id.chFav).visibility = if (ch.key in favs) View.VISIBLE else View.GONE
            val style = { f: Boolean -> name.setTextColor(if (f) Accent.ABYSS else Accent.DIM); sub.setTextColor(if (f) 0xFF5F6368.toInt() else Accent.FAINT) }
            style(v.isFocused)
            v.setOnFocusChangeListener { _, f -> style(f) }
            v.setOnClickListener { playFromSearch(i) }
            v.setOnLongClickListener { toggleFav(ch); notifyItemChanged(i); true }
        }
    }

    // ---------- channel guide (Right opens · Left goes back · OK plays) ----------
    private fun guidePool() = if (shown.getOrNull(current)?.v2 == true) allV2 else all
    private fun channelsIn(cat: String) = if (cat == FAV) guidePool().filter { it.key in favs } else guidePool().filter { it.category == cat }
    private fun within(v: View?, rv: View) = v != null && (v === rv || isInside(v, rv))
    private fun itemOf(v: View, rv: RecyclerView): View { var x = v; while (x.parent !== rv) x = x.parent as View; return x }
    private val guideTimeout = Runnable { closeGuide() }
    private fun bumpGuide() { ui.removeCallbacks(guideTimeout); ui.postDelayed(guideTimeout, 15000) }

    private fun focusRow(rv: RecyclerView, i: Int) {
        val h = rv.findViewHolderForAdapterPosition(i)
        if (h != null && h.itemView.isShown) { h.itemView.requestFocus(); return }
        (rv.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(i, (120 * dp).toInt())
        rv.post { rv.findViewHolderForAdapterPosition(i)?.itemView?.requestFocus() ?: rv.requestFocus() }
    }

    private fun openGuide() {
        val pool = guidePool()
        if (pool.isEmpty()) return
        val byCat = pool.groupBy { it.category }
        val favCount = pool.count { it.key in favs }
        guideCats = (if (favCount > 0) listOf(FAV) else emptyList()) + byCat.keys.sorted()
        catCounts = byCat.mapValues { it.value.size } + (FAV to favCount)
        if (home.descendantFocusability != ViewGroup.FOCUS_BLOCK_DESCENDANTS) {
            lastHomeFocus = currentFocus; home.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        }
        guideShown = true; guideCat = ""
        guide.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        chCol.visibility = View.GONE; guideDivider.visibility = View.GONE
        channelSearch.setText("")
        catAdapter.notifyDataSetChanged()
        ui.removeCallbacks(hideInfo); hideInfo.run()
        guide.animate().cancel(); guideScrim.animate().cancel()
        guide.visibility = View.VISIBLE; guide.alpha = 0f; guide.translationX = 60 * dp
        guide.animate().alpha(1f).translationX(0f).setDuration(260).setInterpolator(DecelerateInterpolator(2f)).start()
        guideScrim.visibility = View.VISIBLE; guideScrim.alpha = 0f
        guideScrim.animate().alpha(1f).setDuration(260).start()
        focusRow(catList, guideCats.indexOf(shownCat).coerceAtLeast(0))
        bumpGuide()
    }

    private fun openCategory(i: Int) {
        val cat = guideCats.getOrNull(i) ?: return
        val prev = guideCats.indexOf(guideCat)
        guideCat = cat; categoryChannels = channelsIn(cat)
        channelSearch.setText("")
        guideChannels = categoryChannels
        chHeader.text = "$cat  ${guideChannels.size}"
        chAdapter.notifyDataSetChanged()
        if (chCol.visibility != View.VISIBLE) {
            chCol.animate().cancel()
            chCol.visibility = View.VISIBLE; guideDivider.visibility = View.VISIBLE
            chCol.alpha = 0f; chCol.translationX = 30 * dp
            chCol.animate().alpha(1f).translationX(0f).setDuration(240).setInterpolator(DecelerateInterpolator(2f)).start()
        }
        val playing = if (cat == shownCat) guideChannels.indexOfFirst { it.key == shown.getOrNull(current)?.key } else -1
        chList.scrollToPosition(0)
        focusRow(chList, playing.coerceAtLeast(0))
        if (prev >= 0) catAdapter.notifyItemChanged(prev)
        catAdapter.notifyItemChanged(i)
    }

    private fun backToCats() {
        val i = guideCats.indexOf(guideCat).coerceAtLeast(0)
        guideCat = ""
        focusRow(catList, i)
        catAdapter.notifyItemChanged(i)
        chCol.animate().cancel()
        chCol.animate().alpha(0f).translationX(30 * dp).setDuration(180)
            .withEndAction { if (guideCat.isEmpty()) { chCol.visibility = View.GONE; guideDivider.visibility = View.GONE } }.start()
    }

    private fun closeGuide() {
        if (!guideShown) return
        guideShown = false; guideCat = ""; ui.removeCallbacks(guideTimeout)
        guide.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        currentFocus?.clearFocus()
        guide.animate().alpha(0f).translationX(60 * dp).setDuration(200)
            .withEndAction { if (!guideShown) { guide.visibility = View.GONE; chCol.visibility = View.GONE; guideDivider.visibility = View.GONE } }.start()
        guideScrim.animate().alpha(0f).setDuration(200).withEndAction { if (!guideShown) guideScrim.visibility = View.GONE }.start()
    }

    /** Hard reset when the player itself closes: hide the guide and give focus back to the home screen. */
    private fun resetGuide() {
        guideShown = false; guideCat = ""; ui.removeCallbacks(guideTimeout)
        guide.animate().cancel(); guideScrim.animate().cancel()
        guide.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        guide.visibility = View.GONE; guideScrim.visibility = View.GONE; chCol.visibility = View.GONE; guideDivider.visibility = View.GONE
        if (home.descendantFocusability == ViewGroup.FOCUS_BLOCK_DESCENDANTS && !searchShown) {
            home.descendantFocusability = homeFocusMode
            val back = lastHomeFocus; lastHomeFocus = null
            if (back?.takeIf { it.isAttachedToWindow }?.requestFocus() != true) rowsView.requestFocus()
        }
    }

    private fun playFromGuide(i: Int) {
        val list = guideChannels; val cat = guideCat
        if (i !in list.indices) return
        shown = list; shownCat = cat
        closeGuide(); play(i)
    }

    private fun guideKey(e: KeyEvent): Boolean {
        if (e.action != KeyEvent.ACTION_DOWN) return false
        bumpGuide()
        val f = currentFocus
        val inCh = within(f, chList); val inCat = within(f, catList); val inSearch = f === channelSearch
        when (e.keyCode) {
            KeyEvent.KEYCODE_BACK -> { closeGuide(); return true }
            KeyEvent.KEYCODE_DPAD_LEFT -> { if (inCh || inSearch) backToCats() else closeGuide(); return true }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (inCat && f != null && f !== catList) openCategory(catList.getChildAdapterPosition(itemOf(f, catList)))
                return true
            }
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                // the search box above the channel list is now reachable: Up from the first channel, Down to leave it
                if (inSearch) { if (e.keyCode == KeyEvent.KEYCODE_DPAD_DOWN) focusRow(chList, 0); return true }
                if (!inCh && !inCat) { focusRow(catList, 0); return true }
                if (inCh && e.keyCode == KeyEvent.KEYCODE_DPAD_UP && chCol.visibility == View.VISIBLE &&
                    chList.getChildAdapterPosition(itemOf(f!!, chList)) <= 0) { channelSearch.requestFocus(); return true }
                val list = if (inCh) chList else catList
                // stay inside the current column; never jump across or out of the panel
                val next = f!!.focusSearch(if (e.keyCode == KeyEvent.KEYCODE_DPAD_UP) View.FOCUS_UP else View.FOCUS_DOWN)
                return next == null || !within(next, list)
            }
            KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_CHANNEL_DOWN -> return true
        }
        return false   // OK / long-press go to the focused row as normal
    }

    private val catAdapter = object : RecyclerView.Adapter<VH>() {
        override fun getItemCount() = guideCats.size
        override fun onCreateViewHolder(p: ViewGroup, t: Int): VH {
            val v = LayoutInflater.from(p.context).inflate(R.layout.item_guide_cat, p, false)
            v.background = Ui.focusBg(dp, 14f)
            return VH(v)
        }
        override fun onBindViewHolder(h: VH, i: Int) {
            val v = h.itemView; val cat = guideCats[i]
            val name = v.findViewById<TextView>(R.id.catName)
            name.text = cat
            v.findViewById<TextView>(R.id.catCount).text = (catCounts[cat] ?: 0).toString()
            v.findViewById<View>(R.id.catMark).visibility = if (cat == shownCat) View.VISIBLE else View.INVISIBLE
            val open = cat == guideCat
            v.isActivated = open
            val style = { f: Boolean -> name.setTextColor(if (f) Accent.ABYSS else if (open) Accent.TEXT else Accent.DIM) }
            style(v.isFocused)
            v.setOnFocusChangeListener { _, f -> style(f) }
            v.setOnClickListener { openCategory(i) }
        }
    }

    private val chAdapter = object : RecyclerView.Adapter<VH>() {
        override fun getItemCount() = guideChannels.size
        override fun onCreateViewHolder(p: ViewGroup, t: Int): VH {
            val v = LayoutInflater.from(p.context).inflate(R.layout.item_guide_ch, p, false)
            v.background = Ui.focusBg(dp, 14f)
            v.findViewById<View>(R.id.chLogoBox).background = Ui.pill(dp, Accent.SURFACE, 12f)
            return VH(v)
        }
        override fun onBindViewHolder(h: VH, i: Int) {
            val v = h.itemView; val ch = guideChannels[i]
            val name = v.findViewById<TextView>(R.id.chName)
            name.text = ch.name
            v.findViewById<TextView>(R.id.chNum).text = (i + 1).toString()
            bindLogo(v.findViewById(R.id.chLogo), v.findViewById(R.id.chMono), ch)
            val playing = shown.getOrNull(current)?.key == ch.key
            v.findViewById<View>(R.id.chNow).visibility = if (playing) View.VISIBLE else View.GONE
            v.findViewById<View>(R.id.chFav).visibility = if (ch.key in favs) View.VISIBLE else View.GONE
            v.isActivated = playing
            val style = { f: Boolean -> name.setTextColor(if (f) Accent.ABYSS else if (playing) Accent.TEXT else Accent.DIM) }
            style(v.isFocused)
            v.setOnFocusChangeListener { _, f -> style(f) }
            v.setOnClickListener { playFromGuide(i) }
            v.setOnLongClickListener { toggleFav(ch); notifyItemChanged(i); true }
        }
    }

    // ---------- live channel preview (hero window, muted) ----------
    private val previewRunnable = Runnable { startPreview() }
    private fun schedulePreview(c: Channel?) {
        stopPreview()
        // no live preview on the movie tabs, while the hero is folded away, or by default on weak boxes (it runs a second decoder)
        if (c != null && tab < 2 && !heroCollapsed && layer.visibility != View.VISIBLE && prefs.getBoolean("previews", !lowEnd)) ui.postDelayed(previewRunnable, 1200)
    }
    private fun stopPreview() {
        ui.removeCallbacks(previewRunnable); previewToken++; previewJob?.cancel(true)
        previewPv.player = null
        previewPlayer?.let { try { it.release() } catch (_: Exception) {} }; previewPlayer = null
        // never GONE: a TextureView only gets its surface while laid out, and the first frame is what fades it in
        heroPreview.animate().cancel(); if (heroPreview.alpha > 0f) heroPreview.animate().alpha(0f).setDuration(200).start()
    }
    private fun startPreview() {
        val ch = (heroTile as? Tile.Ch)?.c ?: return
        if (layer.visibility == View.VISIBLE || searchShown) return
        val token = ++previewToken
        previewJob = io.submit {
            try {
                val source = TgServers.fetch(ch.id, v2 = ch.v2).firstOrNull() ?: return@submit
                runOnUiThread { if (token == previewToken && layer.visibility != View.VISIBLE) playPreview(source) }
            } catch (_: Exception) { }
        }
    }
    private fun playPreview(source: TgStream) {
        try {
            val p = ExoPlayer.Builder(this).build()
            p.volume = 0f
            p.trackSelectionParameters = p.trackSelectionParameters.buildUpon().setMaxVideoSize(1280, 720).build()
            previewPlayer = p
            p.setMediaSource(nativeMediaSource(source))
            p.addListener(object : Player.Listener {
                override fun onRenderedFirstFrame() {
                    if (previewPlayer !== p) return
                    heroPreview.animate().cancel(); heroPreview.animate().alpha(1f).setDuration(450).start()
                }
                override fun onPlayerError(error: PlaybackException) { if (previewPlayer === p) stopPreview() }
            })
            previewPv.player = p; p.playWhenReady = true; p.prepare()
        } catch (_: Exception) { stopPreview() }
    }

    /** Rebuilds the rows (e.g. a new "Recently watched" entry) without losing the scroll position. */
    private fun refreshRowsKeepFocus() {
        val lm = rowsView.layoutManager as LinearLayoutManager
        val pos = lm.findFirstVisibleItemPosition()
        val top = lm.findViewByPosition(pos)?.top ?: 0
        val old = rows.map { it.title }
        rows = when (tab) {
            0 -> listOf(Row(FAV, favTiles())) + recentRow() + all.groupBy { it.category }.toSortedMap().map { (k, v) -> Row(k, v.map { Tile.Ch(it) }) }
            1 -> listOf(Row(FAV, favTiles())) + recentRow() + allV2.groupBy { it.category }.toSortedMap().map { (k, v) -> Row(k, v.map { Tile.Ch(it) }) }
            else -> continueRow() + vodRows[tab - 2]
        }
        inner.clear(); rowAdapter.notifyDataSetChanged()
        val shift = rows.size - old.size
        if (pos >= 0) lm.scrollToPositionWithOffset((pos + if (pos > 0) shift else 0).coerceAtLeast(0), top)
        rowsView.postDelayed({ if (layer.visibility != View.VISIBLE && !searchShown && currentFocus == null) rowsView.requestFocus() }, 80)
    }

    // ---------- adapters ----------
    private inner class TileAdapter(val row: Row) : RecyclerView.Adapter<VH>() {
        override fun getItemCount() = row.tiles.size
        override fun getItemViewType(i: Int) = when (row.tiles[i]) { is Tile.Ch -> 0; is Tile.Mv -> 1; else -> 2 }
        override fun onCreateViewHolder(p: ViewGroup, t: Int): VH {
            val l = when (t) { 0 -> R.layout.item_tile; 1 -> R.layout.item_poster; else -> R.layout.item_hint }
            val v = LayoutInflater.from(p.context).inflate(l, p, false)
            if (t != 1) { v.background = Ui.pill(dp, Accent.SURFACE, 14f); v.clipToOutline = true }   // posters carry their own card background
            return VH(v)
        }
        override fun onBindViewHolder(h: VH, i: Int, payloads: MutableList<Any>) {
            val t = row.tiles[i]
            if (payloads.isNotEmpty() && t is Tile.Ch) { h.itemView.findViewById<View>(R.id.fav).visibility = if (t.c.key in favs) View.VISIBLE else View.GONE; return }
            super.onBindViewHolder(h, i, payloads)
        }
        override fun onBindViewHolder(h: VH, i: Int) {
            val v = h.itemView
            when (val t = row.tiles[i]) {
                is Tile.Ch -> {
                    val bg = v.background as GradientDrawable
                    v.tag = t.c.key; bg.setColor(Ui.mix(Accent.SURFACE, accentOf(t.c.key), 0.26f))
                    v.findViewById<TextView>(R.id.name).text = t.c.name
                    val logo = v.findViewById<ImageView>(R.id.logo); val mono = v.findViewById<TextView>(R.id.mono)
                    if (t.c.logo.isBlank()) {
                        logo.setImageDrawable(null); logo.visibility = View.GONE
                        val ac = accentOf(t.c.key)
                        mono.visibility = View.VISIBLE; mono.text = initials(t.c.name)
                        mono.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(androidx.core.graphics.ColorUtils.setAlphaComponent(ac, 150)) }
                    } else {
                        mono.visibility = View.GONE; logo.visibility = View.VISIBLE
                        logo.load(t.c.logo) {
                            allowHardware(false)
                            listener(onSuccess = { _, r ->
                                extractAccent(t.c.key, r.drawable) { c ->
                                    if (v.tag == t.c.key) bg.setColor(Ui.mix(Accent.SURFACE, c, 0.26f))
                                }
                            })
                        }
                    }
                    v.findViewById<View>(R.id.fav).visibility = if (t.c.key in favs) View.VISIBLE else View.GONE
                    v.setOnFocusChangeListener { x, f ->
                        focusAnim(x, f); if (f) setHero(t)
                    }
                    v.setOnClickListener {
                        shown = row.tiles.filterIsInstance<Tile.Ch>().map { it.c }; shownCat = row.title
                        play(shown.indexOfFirst { it.key == t.c.key })
                    }
                    v.setOnLongClickListener { toggleFav(t.c); true }
                }
                is Tile.Mv -> {
                    val img = v.findViewById<ImageView>(R.id.poster)
                    v.findViewById<TextView>(R.id.posterName).text = t.m.name
                    img.load(t.m.poster) { crossfade(120); size(300, 400); placeholder(null) }
                    v.findViewById<TextView>(R.id.posterTitle).text = t.m.name
                    v.findViewById<TextView>(R.id.posterMeta).text =
                        (listOf(t.m.year) + t.m.genres.take(1) + listOf(t.m.runtime)).filter { it.isNotEmpty() }.joinToString("  •  ")
                    v.setOnFocusChangeListener { x, f ->
                        focusAnim(x, f)
                        if (f) {
                            setHero(t)
                            val p = h.bindingAdapterPosition
                            for (d in 1..3) (row.tiles.getOrNull(p + d) as? Tile.Mv)?.let { prefetch(it.m.background) }
                        }
                    }
                    v.setOnClickListener {
                        stopPreview(); DetailActivity.open(this@MainActivity, t.m)
                        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
                    }
                }
                else -> {}
            }
        }
    }

    private val tilePool = RecyclerView.RecycledViewPool().apply { setMaxRecycledViews(0, 40); setMaxRecycledViews(1, 40) }
    private val rowAdapter = object : RecyclerView.Adapter<VH>() {
        override fun getItemCount() = rows.size
        override fun onCreateViewHolder(p: ViewGroup, t: Int) =
            VH(LayoutInflater.from(p.context).inflate(R.layout.item_row, p, false))
        override fun onBindViewHolder(h: VH, i: Int) {
            val v = h.itemView
            val row = rows[i]
            val title = v.findViewById<TextView>(R.id.rowTitle); val count = v.findViewById<TextView>(R.id.rowCount)
            title.text = row.title
            val n = row.tiles.count { it is Tile.Ch || it is Tile.Mv }
            count.text = ""
            val rv = v.findViewById<RecyclerView>(R.id.rowList)
            if (rv.layoutManager == null) {
                rv.layoutManager = SnapLayoutManager(this@MainActivity, RecyclerView.HORIZONTAL)
                rv.itemAnimator = null
                rv.overScrollMode = View.OVER_SCROLL_NEVER
                rv.isNestedScrollingEnabled = false
                rv.setItemViewCacheSize(10)
                (rv.layoutManager as LinearLayoutManager).initialPrefetchItemCount = 8
            }
            rv.setRecycledViewPool(tilePool)
            val cur = rv.adapter as? TileAdapter
            val ad = if (cur != null && cur.row === row) cur else TileAdapter(row).also { rv.swapAdapter(it, true) }
            inner[i] = ad
            v.animate().cancel(); v.translationY = 0f; v.alpha = 1f
            val a = if (rowsView.hasFocus()) 0.45f else 1f
            title.alpha = a; count.alpha = a
        }
    }
}
