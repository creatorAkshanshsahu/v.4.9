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
import android.os.Build
import android.os.SystemClock
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
import androidx.appcompat.app.AlertDialog
import androidx.core.content.res.ResourcesCompat
import androidx.media3.common.*
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.drm.*
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
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
import java.net.URI
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
    class Row(val title: String, var tiles: List<Tile>, val landscape: Boolean = false)

    // movie & series shelves (filled the first time the tab opens)
    private val vodRows = arrayOf(listOf<Row>(), listOf<Row>()); private val vodLoading = booleanArrayOf(false, false)   // [0] Movies, [1] Series
    private val RECENT = "Recently watched"; private val CONTINUE = "Continue watching"
    private lateinit var heroBackdrop: ImageView; private lateinit var heroPreview: View; private lateinit var previewPv: PlayerView
    private var previewPlayer: ExoPlayer? = null; private var previewToken = 0
    private var previewJob: Future<*>? = null
    private var previewStall: Runnable? = null
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
    private var trendingTitles = emptyList<Movies.Title>(); private var trendingLoading = false; private var trendingFailed = false
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
    private var nativeProviders = listOf(LiveProvider.TG)
    private var nextNativeProvider = 1
    private var nativePassFresh = false
    private var currentNativeProvider = LiveProvider.JJTV
    private var requestedNativeProvider: Pair<String, LiveProvider>? = null
    private val providerWindow = NativeProviderWindow()
    private val decoderRetries = NativeDecoderRetries()
    private val authRecovery = PlaybackAuthRecovery()
    private val recovery = PlaybackRecovery()
    // A transient refusal gets two paced, fresh-metadata retries inside the existing 60 s budget.
    private var refusalRetryCount = 0
    private var okLong = false
    private var renderedFrame = false
    private val television get() = NativePlaybackPolicy.isTelevision(this)
    private var compatiblePlayback = false
    private var playbackActive = false
    private val startupBudget = PlaybackStartupBudget()
    private var playbackHealth: PlaybackHealth? = null
    private var surfaceWait: Pair<SurfaceHolder, SurfaceHolder.Callback>? = null
    private var diagnostics = PlaybackDiagnostics()
    private var activeAttempt: PlaybackDiagnostics.Attempt? = null
    private var diagnosticsDialog: AlertDialog? = null
    private var sourceMenu: AlertDialog? = null
    private var diagnosticsText: TextView? = null
    private val drmProbeIo = Executors.newSingleThreadExecutor()
    private var drmProbeRunning = false
    private var drmProbeStatus = "ClearKey self-test: not run (MENU > Playback details > Check DRM)."
    private val appVersion by lazy { packageManager.getPackageInfo(packageName, 0).versionName ?: "unknown" }
    // HD smoothness: picture size is capped to what this box can decode, and lowered by itself if frames start dropping
    private var trackSel: androidx.media3.exoplayer.trackselection.DefaultTrackSelector? = null
    private var picture: NativePictureController? = null
    private val lowEnd by lazy {
        (getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager).isLowRamDevice || android.os.Build.SUPPORTED_64_BIT_ABIS.isEmpty()
    }
    private fun startCap(): Int {
        val ch = shown.getOrNull(current)
        val saved = if (ch != null) prefs.getInt("q_${ch.key}", 0) else 0
        val ceiling = if (lowEnd || television) 720 else 1080
        return if (saved > 0) minOf(saved, ceiling) else ceiling
    }
    private fun stablePicture(ch: Channel?): Boolean = ch != null && prefs.getBoolean("stable_picture_${ch.key}",
        (television && !ch.v2 && ch.id == "1108") || prefs.getInt("q_${ch.key}", 0) > 0)

    private lateinit var rowsView: RecyclerView; private lateinit var status: TextView
    private lateinit var layer: View; private lateinit var pv: PlayerView; private lateinit var spinner: ProgressBar
    private lateinit var segLive: TextView; private lateinit var segV2: TextView; private lateinit var segSites: TextView; private lateinit var segSeries: TextView
    private val segs by lazy { listOf(segLive, segV2, segSites, segSeries) }   // TV rail: Live, Always on, Movies, Series
    private lateinit var phoneSegs: List<TextView>; private lateinit var phoneSearchBtn: TextView
    private val segBgs by lazy { List(4) { Ui.pill(dp, Color.TRANSPARENT, 12f) } }
    private val phoneSegBgs by lazy { List(5) { Ui.pill(dp, Color.TRANSPARENT, 20f) } }
    private var lastRowsFocus: View? = null
    private val compact by lazy { resources.configuration.screenHeightDp < 420 }
    private lateinit var searchBtn: TextView; private lateinit var intro: AkIntroView
    private lateinit var heroText: View; private lateinit var heroDot: View; private lateinit var heroBadge: TextView
    private lateinit var heroTitle: TextView; private lateinit var heroSubheading: TextView; private lateinit var heroChips: LinearLayout; private lateinit var heroTagline: View
    private lateinit var heroOrb: View; private lateinit var heroLogo: ImageView; private lateinit var heroMono: TextView
    private lateinit var heroProg: TextView; private lateinit var heroProgress: ProgressBar
    private lateinit var heroDesc: TextView; private lateinit var heroNext: TextView
    private val orbBg by lazy { GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(0xFF091521.toInt(), 0xFF0C1D2A.toInt(), 0xFF07111B.toInt())) }
    private lateinit var infoCard: View; private lateinit var infoLogo: ImageView; private lateinit var infoName: TextView
    private lateinit var infoCat: TextView; private lateinit var playerScrim: View; private lateinit var playerClock: View; private lateinit var playerBrand: View
    private lateinit var infoMono: TextView; private lateinit var infoNow: TextView; private lateinit var infoProgress: ProgressBar
    private lateinit var infoDesc: TextView; private lateinit var infoScheduleNext: TextView; private lateinit var infoNext: TextView
    private lateinit var infoActions: View; private lateinit var infoSourceAction: TextView; private lateinit var infoQualityAction: TextView; private lateinit var infoRetryAction: TextView
    private lateinit var infoGuideAction: TextView
    private lateinit var playStatus: TextView; private var currentGuideNow: Programme? = null
    private var currentGuideData: Guide? = null
    private lateinit var programmeGuidePanel: ViewGroup; private lateinit var programmeGuideScrim: View
    private lateinit var programmeGuideClose: TextView; private lateinit var programmeGuideChannel: TextView
    private lateinit var programmeGuideStatus: TextView; private lateinit var programmeGuideEntries: LinearLayout
    private lateinit var programmeGuideCredit: TextView; private var programmeGuideShown = false
    private var programmeGuideCurrentChannel: String? = null
    // channel guide
    private val FAV = "Favorites"
    private lateinit var home: ViewGroup; private var homeFocusMode = 0; private var lastHomeFocus: View? = null
    private lateinit var guide: ViewGroup; private lateinit var guideScrim: View; private lateinit var guideDivider: View
    private lateinit var catList: RecyclerView; private lateinit var chList: RecyclerView
    private lateinit var chCol: View; private lateinit var chHeader: TextView; private lateinit var channelSearch: EditText
    private var guideShown = false; private var shownCat = ""
    private val guideWaiters = HashMap<String, MutableList<(Guide?) -> Unit>>()
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
        phoneSegs = listOf(R.id.phoneLive, R.id.phoneV2, R.id.phoneMovies, R.id.phoneSeries).map { findViewById(it) }
        phoneSearchBtn = findViewById(R.id.phoneSearch)
        heroText = findViewById(R.id.heroText); heroDot = findViewById(R.id.heroDot); heroBadge = findViewById(R.id.heroBadge)
        heroTitle = findViewById(R.id.heroTitle); heroSubheading = findViewById(R.id.heroSubheading)
        heroChips = findViewById(R.id.heroChips); heroTagline = findViewById(R.id.heroTagline)
        heroOrb = findViewById(R.id.heroOrb); heroLogo = findViewById(R.id.heroLogo); heroMono = findViewById(R.id.heroMono)
        heroProg = findViewById(R.id.heroProg); heroProgress = findViewById(R.id.heroProgress)
        heroDesc = findViewById(R.id.heroDesc); heroNext = findViewById(R.id.heroNext)
        infoCard = findViewById(R.id.infoCard); infoLogo = findViewById(R.id.infoLogo); infoName = findViewById(R.id.infoName)
        infoCat = findViewById(R.id.infoCat); playerScrim = findViewById(R.id.playerScrim); playerClock = findViewById(R.id.playerClock)
        playerBrand = findViewById(R.id.playerBrand)
        infoMono = findViewById(R.id.infoMono); infoNow = findViewById(R.id.infoNow); infoProgress = findViewById(R.id.infoProgress)
        infoDesc = findViewById(R.id.infoDesc); infoScheduleNext = findViewById(R.id.infoScheduleNext)
        infoNext = findViewById(R.id.infoNext); playStatus = findViewById(R.id.playStatus); infoActions = findViewById(R.id.infoActions)
        infoSourceAction = findViewById(R.id.infoSourceAction); infoQualityAction = findViewById(R.id.infoQualityAction); infoRetryAction = findViewById(R.id.infoRetryAction)
        infoGuideAction = findViewById(R.id.infoGuideAction)
        infoSourceAction.setOnClickListener { showNativeSourceMenu() }
        infoQualityAction.setOnClickListener { showPictureMenu() }
        infoRetryAction.setOnClickListener { reloadStream() }
        infoGuideAction.setOnClickListener { showProgrammeGuide() }
        listOf(infoGuideAction, infoSourceAction, infoQualityAction, infoRetryAction).forEach { action ->
            action.background = Ui.pill(dp, 0xB31C2432.toInt(), 18f)
            action.setOnFocusChangeListener { v, focused ->
                val text = v as TextView
                text.background = Ui.pill(dp, if (focused) Accent.ION else 0xB30A111B.toInt(), 18f).apply {
                    setStroke((if (focused) 2 * dp else 1 * dp).toInt().coerceAtLeast(1), if (focused) Accent.TEXT else 0x55FFFFFF)
                }
                text.setTextColor(if (focused) Accent.ABYSS else Accent.TEXT)
                v.animate().scaleX(if (focused) 1.045f else 1f).scaleY(if (focused) 1.045f else 1f)
                    .translationZ(if (focused) 8 * dp else 0f).setDuration(180).start()
            }
        }
        programmeGuidePanel = findViewById(R.id.programmeGuidePanel)
        programmeGuideScrim = findViewById(R.id.programmeGuideScrim)
        programmeGuideClose = findViewById(R.id.programmeGuideClose)
        programmeGuideChannel = findViewById(R.id.programmeGuideChannel)
        programmeGuideStatus = findViewById(R.id.programmeGuideStatus)
        programmeGuideEntries = findViewById(R.id.programmeGuideEntries)
        programmeGuideCredit = findViewById(R.id.programmeGuideCredit)
        programmeGuideClose.setOnClickListener { closeProgrammeGuide() }
        programmeGuideScrim.setOnClickListener { closeProgrammeGuide() }
        val scheduleWidth = minOf(440f * dp, (resources.displayMetrics.widthPixels - 32f * dp).coerceAtLeast(280f * dp)).toInt()
        (programmeGuidePanel.layoutParams as? FrameLayout.LayoutParams)?.let { lp ->
            lp.width = scheduleWidth; lp.gravity = Gravity.END; lp.setMargins((16 * dp).toInt(), (16 * dp).toInt(), (16 * dp).toInt(), (16 * dp).toInt())
            programmeGuidePanel.layoutParams = lp
        }

        // Phones have no MENU/OK remote keys: make the visible source/status lines actionable.
        infoNext.setOnClickListener { if (!guideShown) showNativeSourceMenu() }
        playStatus.setOnClickListener { showPlaybackDetails() }
        searchLayer = findViewById(R.id.searchLayer); searchQuery = findViewById(R.id.searchQuery); searchKeys = findViewById(R.id.searchKeys)
        searchResults = findViewById(R.id.searchResults); searchCount = findViewById(R.id.searchCount); searchEmpty = findViewById(R.id.searchEmpty)
        intro = findViewById(R.id.splash)
        heroBackdrop = findViewById(R.id.heroBackdrop); heroPreview = findViewById(R.id.heroPreview); previewPv = findViewById(R.id.heroPreviewPv)

        // Use selective translucent panels, not blur/backdrop filters; this stays cheap on 32-bit TVs.
        layoutHero()
        segs.forEachIndexed { i, tv -> tv.background = segBgs[i] }
        heroOrb.background = orbBg
        infoCard.background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
            intArrayOf(0xE5080B11.toInt(), 0xD90D121A.toInt())).apply {
            cornerRadius = 16 * dp
            setStroke((1 * dp).toInt().coerceAtLeast(1), 0x44FFFFFF)
        }
        infoCard.elevation = 10 * dp
        findViewById<View>(R.id.infoLogoBox).background = Ui.pill(dp, Accent.SURFACE, 13f).apply {
            setStroke((1 * dp).toInt().coerceAtLeast(1), 0x44FFFFFF)
        }
        val playerDock = findViewById<View>(R.id.playerInfoDock)
        val phoneWidth = resources.configuration.screenWidthDp < 600
        val dockLp = playerDock.layoutParams as FrameLayout.LayoutParams
        if (phoneWidth) {
            val margin = (16 * dp).toInt()
            dockLp.width = (resources.displayMetrics.widthPixels - 2 * margin).coerceAtLeast(1)
            dockLp.setMargins(margin, 0, margin, margin); dockLp.marginStart = margin; dockLp.marginEnd = margin
            playerDock.layoutParams = dockLp
            val card = infoCard as LinearLayout
            card.orientation = LinearLayout.VERTICAL; card.minimumWidth = 0
            card.setPadding((14 * dp).toInt(), (12 * dp).toInt(), (14 * dp).toInt(), (12 * dp).toInt())
            (card.layoutParams as? LinearLayout.LayoutParams)?.let { lp ->
                lp.width = LinearLayout.LayoutParams.MATCH_PARENT; lp.height = LinearLayout.LayoutParams.WRAP_CONTENT; lp.weight = 0f
                card.layoutParams = lp
            }
            findViewById<View>(R.id.infoLogoBox).visibility = View.GONE
            (card.getChildAt(1).layoutParams as? LinearLayout.LayoutParams)?.let { lp ->
                lp.marginStart = 0; lp.width = LinearLayout.LayoutParams.MATCH_PARENT; lp.weight = 0f
                card.getChildAt(1).layoutParams = lp
            }
            infoName.maxWidth = dockLp.width - (28 * dp).toInt()
            listOf(infoGuideAction, infoSourceAction, infoQualityAction, infoRetryAction).forEach { action ->
                action.textSize = 9f; action.setPadding((8 * dp).toInt(), 0, (8 * dp).toInt(), 0)
                (action.layoutParams as? LinearLayout.LayoutParams)?.let { lp -> lp.marginEnd = (4 * dp).toInt(); action.layoutParams = lp }
            }
        } else {
            dockLp.width = (resources.displayMetrics.widthPixels * 0.84f).toInt()
            dockLp.setMargins((40 * dp).toInt(), 0, 0, (28 * dp).toInt())
            dockLp.marginStart = (40 * dp).toInt(); dockLp.marginEnd = 0
            playerDock.layoutParams = dockLp
            findViewById<View>(R.id.infoHint).visibility = View.GONE
        }
        findViewById<TextView>(R.id.infoHint).text = SpannableStringBuilder().apply {
            key(this, "▲ ▼", "Channel"); append("     "); key(this, "▶", "All channels"); append("     ")
            key(this, "GUIDE", "Schedule"); append("     "); key(this, "Hold OK", "Reload"); append("     ")
            key(this, "◀", "Next server"); append("     "); key(this, "Back", "Exit")
        }
        val track = ColorStateList.valueOf(Color.argb(60, 255, 255, 255))
        heroProgress.progressBackgroundTintList = track; infoProgress.progressBackgroundTintList = track

        // search pill in the top bar
        searchBtn.background = searchBg
        searchBtn.setOnFocusChangeListener { _, _ -> styleSegs() }
        searchBtn.setOnClickListener { openSearch() }
        phoneSegs.forEachIndexed { i, tv ->
            tv.background = phoneSegBgs[i]
            tv.setOnClickListener { switchTab(i) }
            tv.setOnFocusChangeListener { _, _ -> styleSegs() }
        }
        phoneSearchBtn.background = phoneSegBgs[4]
        phoneSearchBtn.setOnClickListener { openSearch() }
        phoneSearchBtn.setOnFocusChangeListener { _, _ -> styleSegs() }

        home = findViewById(R.id.home); homeFocusMode = home.descendantFocusability
        guide = findViewById(R.id.guide); guideScrim = findViewById(R.id.guideScrim); guideDivider = findViewById(R.id.guideDivider)
        catList = findViewById(R.id.catList); chList = findViewById(R.id.chList)
        chCol = findViewById(R.id.chCol); chHeader = findViewById(R.id.chHeader); channelSearch = findViewById(R.id.channelSearch)
        guide.background = Ui.pill(dp, 0xF20E1422.toInt(), 22f).apply { setStroke((1 * dp).toInt().coerceAtLeast(1), 0x556687FF) }
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

        // A short, skippable reveal. No hidden movie-catalogue warm-up competing with live decoding.
        home.alpha = 0f; home.translationY = 0f
        intro.onReveal = { home.alpha = 1f }
        intro.onDone = {
            home.alpha = 1f; intro.visibility = View.GONE
            if (findViewById<View>(R.id.phoneNavScroll).visibility == View.VISIBLE) phoneSegs[tab].requestFocus()
            else segs[tab].requestFocus()
        }
        Movies.diskDir = File(filesDir, "tmdb").apply { mkdirs() }
        Movies.timingListener = { event -> android.util.Log.i("AKTV-VOD", "${event.stage} ${event.elapsedMs}ms cache=${event.cacheHit}") }
        loadHomeTrending()
        // Warm only the first Movies and Series shelves, after the entrance and channel list have begun loading.
        ui.postDelayed({ if (!isFinishing && !isDestroyed) Movies.prefetchOnLaunch() }, 8_000)
        findViewById<TextView>(R.id.navVersion).text = appVersion
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
    private fun heroMetaChip(text: String) = TextView(this).apply {
        this.text = text; textSize = if (compact) 9f else 10f; typeface = fSemi; setTextColor(Accent.TEXT)
        isFocusable = false; setPadding((10 * dp).toInt(), (6 * dp).toInt(), (10 * dp).toInt(), (6 * dp).toInt())
        background = Ui.pill(dp, 0x7733424D, 18f)
        layoutParams = LinearLayout.LayoutParams(-2, -2).apply { marginEnd = (7 * dp).toInt() }
    }

    private fun showHomeChannelDetails(channel: Channel) {
        val guide = Epg.cached(channel.id)
        val text = buildString {
            append(channel.category)
            if (channel.v2) append("  ·  Always on")
            guide?.now?.let { append("\n\nNOW  ·  ${it.title}") }
            guide?.next?.let { append("\nNEXT  ·  ${it.title}") }
            if (guide?.now == null && guide?.next == null) append("\n\nProgramme details will appear when the guide has a listing.")
        }
        AlertDialog.Builder(this).setTitle(channel.name).setMessage(text).setPositiveButton("Done", null).show()
    }

    private fun chip(text: CharSequence, primary: Boolean = false) = TextView(this).apply {
        this.text = text; textSize = if (compact) 11f else 13f; typeface = fSemi
        isFocusable = true; isClickable = true
        setPadding((16 * dp).toInt(), ((if (compact) 7 else 10) * dp).toInt(), (18 * dp).toInt(), ((if (compact) 7 else 10) * dp).toInt())
        val accent = sectionAccent()
        val bg = Ui.pill(dp, if (primary) Color.WHITE else 0xB30A111B.toInt(), 12f)
        background = bg
        setTextColor(if (primary) Accent.ABYSS else Accent.TEXT)
        setOnFocusChangeListener { _, focused ->
            bg.setColor(if (primary) Color.WHITE else if (focused) Ui.mix(Accent.ABYSS, accent, 0.32f) else 0xB30A111B.toInt())
            bg.setStroke(if (focused) (2 * dp).toInt().coerceAtLeast(1) else 0, accent)
            setTextColor(if (primary || focused) Accent.ABYSS else Accent.TEXT)
        }
        nextFocusDownId = R.id.rows; nextFocusLeftId = segs[tab].id
        layoutParams = LinearLayout.LayoutParams(-2, -2).apply { marginEnd = (8 * dp).toInt() }
    }

    private fun layoutHero() {
        val dm = resources.displayMetrics
        HomeChrome.apply(findViewById(R.id.home), dm.widthPixels, dm.heightPixels, dp, tab >= 2)
        val searchWidth = minOf(340f, dm.widthPixels / dp * 0.43f)
        findViewById<View>(R.id.searchInputColumn).layoutParams.width = (searchWidth * dp).toInt()
        findViewById<View>(R.id.home).requestLayout()
    }

    private fun sectionAccent() = when (tab) { 0 -> Accent.SIGNAL; 1 -> Accent.ION; 2 -> Accent.GOLD; else -> Accent.INDIGO }

    private fun navStyle(tv: TextView, bg: GradientDrawable, focused: Boolean, active: Boolean) {
        val accent = sectionAccent()
        bg.setColor(when { focused -> Ui.mix(Accent.ABYSS, accent, 0.23f); active -> Ui.mix(Accent.ABYSS, accent, 0.12f); else -> Color.TRANSPARENT })
        val fg = when { focused -> Accent.TEXT; active -> accent; else -> Accent.DIM }
        tv.setTextColor(fg); tv.compoundDrawableTintList = ColorStateList.valueOf(fg)
        bg.setStroke((if (focused) 2 * dp else if (active) 1 * dp else 0f).toInt(), if (focused || active) accent else Color.TRANSPARENT)
    }
    private val searchBg by lazy { Ui.pill(dp, Color.TRANSPARENT, 12f) }

    private fun styleSegs() {
        searchBtn.text = "Search"
        searchBtn.contentDescription = when (tab) { 2 -> "Search movies"; 3 -> "Search series"; else -> "Search channels" }
        segs.forEachIndexed { i, tv -> navStyle(tv, segBgs[i], tv.isFocused, tab == i) }
        navStyle(searchBtn, searchBg, searchBtn.isFocused, false)
        phoneSegs.forEachIndexed { i, tv -> navStyle(tv, phoneSegBgs[i], tv.isFocused, tab == i) }
        phoneSearchBtn.contentDescription = when (tab) { 2 -> "Search movies"; 3 -> "Search series"; else -> "Search channels" }
        navStyle(phoneSearchBtn, phoneSegBgs[4], phoneSearchBtn.isFocused, false)
        findViewById<TextView>(R.id.sectionTitle).text = when (tab) { 0 -> "Live TV"; 1 -> "Always on"; 2 -> "Movies"; else -> "Series" }
        findViewById<TextView>(R.id.sectionKicker).apply {
            text = when (tab) { 0 -> "LIVE, RIGHT NOW"; 1 -> "PLAY ANYTIME · 24/7"; 2 -> "YOUR NEXT MOVIE NIGHT"; else -> "STORIES THAT KEEP GOING" }
            setTextColor(sectionAccent())
        }
        val count = when (tab) { 0 -> all.size; 1 -> allV2.size; else -> vodRows[tab - 2].sumOf { it.tiles.size } }
        findViewById<TextView>(R.id.librarySummary).text = if (count > 0) "$count ${if (tab < 2) "channels" else "titles"}  ·  Native playback" else "LIVE + ON DEMAND"
    }

    /** Small view-property lift plus a translucent aqua focus plate; no layout animation or blur pass. */
    private fun focusAnim(x: View, f: Boolean, color: Int = 0) {
        val s = if (f) 1.06f else 1f
        x.animate().cancel()
        x.animate().setStartDelay(0).scaleX(s).scaleY(s).translationZ(if (f) 12 * dp else 0f)
            .setDuration(180).setInterpolator(DecelerateInterpolator(1.6f)).start()
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

    /** Populate the home-only trending strip from the existing TMDB catalogue without blocking the intro or live list. */
    private fun loadHomeTrending() {
        if (trendingLoading || trendingTitles.isNotEmpty()) return
        trendingLoading = true
        io.execute {
            val loaded = try { Movies.shelf(Movies.MOVIE_SHELVES.first()).take(20) } catch (_: Exception) { emptyList() }
            runOnUiThread {
                trendingLoading = false
                trendingFailed = loaded.isEmpty()
                if (!isFinishing && !isDestroyed) {
                    if (loaded.isNotEmpty()) trendingTitles = loaded
                    if (tab == 0) refreshRowsKeepFocus(requestFocus = false)
                }
            }
        }
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
    private fun featuredChannels(channels: List<Channel>): List<Channel> = channels.sortedWith(
        compareBy<Channel> {
            val label = "${it.name} ${it.category}".lowercase()
            when {
                "news" in label -> 0
                listOf("sport", "cricket", "football", "tennis").any { it in label } -> 1
                listOf("entertain", "movie", "drama", "serial").any { it in label } -> 2
                listOf("kids", "child", "cartoon").any { it in label } -> 3
                "music" in label -> 4
                else -> 5
            }
        }.thenBy { it.name.lowercase() }
    ).take(24)

    private fun liveRows(channels: List<Channel>, alwaysOn: Boolean = false): List<Row> {
        val trend: List<Tile> = if (trendingTitles.isEmpty()) listOf(Tile.Hint) else trendingTitles.map { Tile.Mv(it) }
        return buildList {
            if (channels.isNotEmpty()) add(Row(if (alwaysOn) "Always-on Channels" else "Live Channels", featuredChannels(channels).map { Tile.Ch(it) }))
            if (!alwaysOn) add(Row("Trending on AK", trend, landscape = trendingTitles.isNotEmpty()))
            add(Row(FAV, favTiles()))
            addAll(recentRow())
            addAll(channels.groupBy { it.category }.toSortedMap().map { (name, items) -> Row(name, items.map { Tile.Ch(it) }) })
        }
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
            0 -> liveRows(all)
            1 -> liveRows(allV2, alwaysOn = true)
            else -> { loadVod(tab); continueRow() + vodRows[tab - 2] }
        }
        setHeroCollapsed(false)
        inner.clear()
        rowAdapter.notifyDataSetChanged()
        val first: Tile? = when (tab) {
            0, 1 -> featuredChannels(pool()).firstOrNull()?.let { Tile.Ch(it) }
            else -> rows.firstOrNull()?.tiles?.firstOrNull()
        }
        setHero(first, immediate = true)
        updateStatus(); styleSegs()
    }

    private fun toggleFav(c: Channel) {
        val s = favs.toMutableSet(); val added = s.add(c.key); if (!added) s.remove(c.key)
        favs = s; prefs.edit().putStringSet("favs", s).apply()
        Toast.makeText(this, if (added) "Added to favorites" else "Removed from favorites", Toast.LENGTH_SHORT).show()
        for ((ri, ad) in inner) {
            val idx = rows.getOrNull(ri)?.tiles?.indexOfFirst { it is Tile.Ch && it.c.key == c.key } ?: -1
            if (idx >= 0) ad.notifyItemChanged(idx, "fav")
        }
        val favRow = rows.indexOfFirst { it.title == FAV }
        if (favRow >= 0) { rows[favRow].tiles = favTiles(); rowAdapter.notifyItemChanged(favRow) }
        (heroTile as? Tile.Ch)?.let { if (it.c.key == c.key) fillHero(it) }
    }

    private fun switchTab(t: Int) {
        if (t == tab) return
        val dir = if (t > tab) 1 else -1
        tab = t; layoutHero(); styleSegs(); stopPreview()
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
        if (url.isEmpty() || layer.visibility == View.VISIBLE) return
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
    /** Tints the native, image-free hero gradient with the selected channel's accent. */
    private fun glowHero(c: Int, partner: Int) {
        orbBg.colors = intArrayOf(Ui.mix(0xFF091521.toInt(), partner, 0.12f), Ui.mix(0xFF0C1D2A.toInt(), c, 0.16f), 0xFF07111B.toInt())
        heroProgress.progressTintList = ColorStateList.valueOf(c)
    }
    /** Keep the reference's atmospheric hero native: gradient, typography and accents, never a backdrop image/video. */
    private fun showCinematicFallback() {
        heroBackdrop.animate().cancel(); heroOrb.animate().cancel()
        heroBackdrop.setImageDrawable(null); heroBackdrop.alpha = 0f; heroOrb.alpha = 1f
        heroPreview.visibility = View.GONE
        stopPreview()
    }
    private fun fillHero(t: Tile?) {
        heroChips.removeAllViews()
        heroBadge.setPadding((9 * dp).toInt(), (3 * dp).toInt(), (9 * dp).toInt(), (3 * dp).toInt())
        heroBadge.setTextColor(Accent.TEXT)
        heroBadge.background = Ui.pill(dp, 0x2424D3D0, 16f).apply {
            setStroke((1 * dp).toInt().coerceAtLeast(1), androidx.core.graphics.ColorUtils.setAlphaComponent(sectionAccent(), 150))
        }
        showCinematicFallback()
        heroTagline.visibility = if (tab < 2 && resources.configuration.screenWidthDp >= 600) View.VISIBLE else View.GONE
        heroSubheading.text = if (tab < 2) "A clearer view of what’s next." else "Find your next story."
        when (t) {
            is Tile.Ch -> {
                heroLogo.alpha = 1f
                heroDot.visibility = View.VISIBLE
                (heroDot as? TextView)?.text = if (t.c.v2) "●  ALWAYS ON" else "●  LIVE NOW"
                heroDot.backgroundTintList = ColorStateList.valueOf(if (t.c.v2) Accent.ION else Accent.LIVE)
                (heroDot as? TextView)?.setTextColor(if (t.c.v2) Accent.ION else Accent.LIVE)
                heroBadge.text = (if (t.c.v2) "24/7   ·   " else "") + t.c.category
                heroTitle.text = t.c.name
                heroChips.addView(chip("▶   Play Live", true).apply { setOnClickListener {
                    shown = (if (t.c.v2) allV2 else all).ifEmpty { listOf(t.c) }; shownCat = t.c.category
                    play(shown.indexOfFirst { it.key == t.c.key }.coerceAtLeast(0))
                } })
                heroChips.addView(chip("ⓘ   Details").apply { setOnClickListener { showHomeChannelDetails(t.c) } })
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
                val accent = sectionAccent()
                heroDot.visibility = View.VISIBLE
                (heroDot as? TextView)?.apply {
                    text = if (t.m.isSeries) "SERIES SPOTLIGHT" else "FEATURED MOVIE"
                    setTextColor(accent); backgroundTintList = ColorStateList.valueOf(accent)
                }
                heroTitle.text = t.m.name
                heroSubheading.text = if (t.m.isSeries) "Stories that keep going." else "Your next movie night starts here."
                heroBadge.text = if (t.m.isSeries) "SERIES  /  ON DEMAND" else "MOVIE  /  ON DEMAND"
                heroChips.addView(chip("▶   Details", true).apply { setOnClickListener {
                    stopPreview(); DetailActivity.open(this@MainActivity, t.m)
                } })
                listOfNotNull(t.m.year.takeIf { it.isNotEmpty() }, t.m.rating.takeIf { it.isNotEmpty() }?.let { "★ $it" })
                    .plus(t.m.genres.take(2)).forEach { heroChips.addView(heroMetaChip(it)) }
                heroMono.visibility = View.GONE; heroLogo.visibility = View.VISIBLE; heroLogo.alpha = 0f
                if (t.m.logo.isNotBlank()) heroLogo.load(t.m.logo) {
                    crossfade(180); listener(onSuccess = { _, _ -> if ((heroTile as? Tile.Mv)?.m?.imdb == t.m.imdb) heroLogo.animate().alpha(1f).setDuration(220).start() })
                } else {
                    heroLogo.setImageResource(R.drawable.brand_aktv_user)
                    heroLogo.animate().alpha(0.92f).setDuration(220).start()
                }
                heroProg.visibility = View.GONE; heroProgress.visibility = View.GONE; heroNext.visibility = View.GONE
                heroDesc.text = t.m.description
                heroDesc.visibility = if (!compact && t.m.description.isNotBlank()) View.VISIBLE else View.GONE
                ui.removeCallbacks(heroGuideRunnable)
                glowHero(accent, if (t.m.isSeries) 0xFF493A8A.toInt() else 0xFF815331.toInt())
            }
            else -> {
                heroDot.visibility = View.GONE; heroBadge.text = "LIVE · FILMS · SERIES"; heroTitle.text = "AK News"
                heroSubheading.text = "A clearer view of what’s next."
                heroMono.visibility = View.GONE; heroLogo.visibility = View.VISIBLE; heroLogo.setImageResource(R.drawable.brand_aktv_user)
                glowHero(Accent.SIGNAL, Accent.INDIGO); scheduleHeroGuide(null)
            }
        }
    }

    // ---------- programme details ----------
    private fun guideHasSchedule(g: Guide?) = g != null && (g.now != null || g.next != null || g.upcoming.isNotEmpty())

    private fun fetchGuide(c: Channel, cb: (Guide?) -> Unit) {
        if (c.v2) { cb(null); return }
        Epg.cached(c.id)?.let { cb(it); return }
        val leader = synchronized(guideWaiters) {
            val existing = guideWaiters[c.id]
            if (existing != null) { existing.add(cb); false }
            else { guideWaiters[c.id] = mutableListOf(cb); true }
        }
        if (!leader) return
        io.execute {
            val community = try { ExternalEpg.guide(applicationContext, c.id) } catch (_: Exception) { null }
            val tgFallback = if (guideHasSchedule(community)) null
                else try { Epg.parse(get("$GUIDE/${c.id}", 2)) } catch (_: Exception) { null }
            val guide = community?.takeIf(::guideHasSchedule)
                ?: tgFallback?.takeIf(::guideHasSchedule)
                ?: community ?: tgFallback
                ?: Guide(null, null, emptyList(), "No schedule currently published")
            Epg.put(c.id, guide)
            val callbacks = synchronized(guideWaiters) { guideWaiters.remove(c.id).orEmpty() }
            runOnUiThread { callbacks.forEach { it(guide) } }
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
        val channel = (heroTile as? Tile.Ch)?.c
        val channelLabel = "${channel?.name.orEmpty()} ${channel?.category.orEmpty()}".lowercase()
        val fallback = if ("news" in channelLabel) "Live. Global. Independent. Breaking news, bigger perspectives."
            else "Live TV, curated for your next moment."
        val d = n?.desc?.takeIf { it.isNotBlank() } ?: fallback
        heroDesc.text = d
        heroDesc.visibility = if (compact || tab >= 2) View.GONE else View.VISIBLE
        heroNext.visibility = if (nx != null && !compact) View.VISIBLE else View.GONE
        if (nx != null) heroNext.text = progLine("NEXT", nx, range = false)
    }

    // ---------- player overlay ----------
    private val guideProgressTick = object : Runnable {
        override fun run() {
            val active = currentGuideNow
            if (active != null && layer.visibility == View.VISIBLE && infoCard.alpha > 0.02f) {
                infoProgress.progress = (Epg.progress(active) * 1000).toInt()
                ui.postDelayed(this, 60_000)
            }
        }
    }
    private val hideInfo = Runnable {
        if (programmeGuideShown) return@Runnable
        ui.removeCallbacks(guideProgressTick)
        infoCard.animate().alpha(0f).translationY(14 * dp).setDuration(450).withEndAction { infoActions.visibility = View.GONE }.start()
        playerScrim.animate().alpha(0f).setDuration(450).start()
        playerClock.animate().alpha(0f).setDuration(450).start()
        playerBrand.animate().alpha(0f).setDuration(450).start()
    }
    private fun showInfo() {
        ui.removeCallbacks(hideInfo)
        infoActions.visibility = View.VISIBLE
        infoCard.animate().alpha(1f).translationY(0f).setDuration(260).setInterpolator(DecelerateInterpolator(2f)).start()
        playerScrim.animate().alpha(1f).setDuration(260).start()
        playerClock.animate().alpha(1f).setDuration(260).start()
        playerBrand.animate().alpha(1f).setDuration(260).start()
        ui.removeCallbacks(guideProgressTick); ui.postDelayed(guideProgressTick, 60_000)
        ui.postDelayed(hideInfo, 5000)
    }
    private fun bindInfo(ch: Channel) {
        infoName.text = ch.name
        infoCat.text = "${if (ch.v2) "V2 · 24/7" else ch.category}   ${current + 1} of ${shown.size}"
        bindLogo(infoLogo, infoMono, ch)
        val ac = accentOf(ch.key)
        spinner.indeterminateTintList = ColorStateList.valueOf(ac)
        infoProgress.progressTintList = ColorStateList.valueOf(ac)
        currentGuideNow = null; currentGuideData = null; programmeGuideCurrentChannel = ch.key
        infoNow.visibility = View.GONE; infoProgress.visibility = View.GONE; infoDesc.visibility = View.GONE
        infoScheduleNext.visibility = View.GONE; infoNext.visibility = View.GONE
        if (ch.v2) { infoNow.text = "24/7 live channel · programme schedule unavailable"; infoNow.visibility = View.VISIBLE }
        else fetchGuide(ch) { g -> if (shown.getOrNull(current)?.key == ch.key) fillInfoGuide(g) }
        showInfo()
    }
    private fun fillInfoGuide(g: Guide?) {
        val n = g?.now
        currentGuideData = g
        currentGuideNow = n
        if (n == null) {
            infoNow.text = if (g?.next != null) "No current programme listing · see Guide for what’s next"
                else "Programme schedule unavailable · live playback continues"
            infoNow.visibility = View.VISIBLE; infoProgress.visibility = View.GONE; infoDesc.visibility = View.GONE
            infoScheduleNext.visibility = View.GONE
        } else {
            infoNow.text = progLine("NOW", n); infoNow.visibility = View.VISIBLE
            infoProgress.progress = (Epg.progress(n) * 1000).toInt(); infoProgress.visibility = View.VISIBLE
            infoDesc.text = n.desc; infoDesc.visibility = if (n.desc.isEmpty()) View.GONE else View.VISIBLE
            val next = g?.next
            infoScheduleNext.visibility = if (next == null) View.GONE else View.VISIBLE
            if (next != null) infoScheduleNext.text = progLine("NEXT", next)
        }
        if (programmeGuideShown && programmeGuideCurrentChannel == shown.getOrNull(current)?.key) {
            shown.getOrNull(current)?.let { renderProgrammeGuide(g, it, loading = false) }
        }
    }

    private fun showProgrammeGuide() {
        val ch = shown.getOrNull(current) ?: return
        if (guideShown) closeGuide()
        ui.removeCallbacks(hideInfo)
        programmeGuideShown = true; programmeGuideCurrentChannel = ch.key
        programmeGuideScrim.visibility = View.VISIBLE; programmeGuideScrim.alpha = 0f
        programmeGuidePanel.visibility = View.VISIBLE; programmeGuidePanel.alpha = 0f; programmeGuidePanel.translationX = 24 * dp
        programmeGuideScrim.animate().alpha(1f).setDuration(180).start()
        programmeGuidePanel.animate().alpha(1f).translationX(0f).setDuration(220).setInterpolator(DecelerateInterpolator(2f)).start()
        renderProgrammeGuide(currentGuideData, ch, loading = !ch.v2 && currentGuideData == null)
        programmeGuideClose.requestFocus()
        if (!ch.v2 && currentGuideData == null) fetchGuide(ch) { guideData ->
            if (programmeGuideShown && programmeGuideCurrentChannel == ch.key) renderProgrammeGuide(guideData, ch, loading = false)
        }
    }

    private fun renderProgrammeGuide(g: Guide?, ch: Channel, loading: Boolean) {
        programmeGuideChannel.text = ch.name
        programmeGuideEntries.removeAllViews()
        if (ch.v2) {
            programmeGuideStatus.text = "This always-on channel does not publish a programme schedule."
            programmeGuideCredit.text = "Live playback is unaffected. V2 channels currently have no schedule feed."
            return
        }
        val available = g?.now != null || g?.next != null || !g?.upcoming.isNullOrEmpty()
        programmeGuideStatus.text = when {
            loading -> "Checking the channel schedule…"
            !available -> "No current or upcoming listings are available for this channel."
            g?.now == null -> "Now on air is not listed. Upcoming programmes are shown below."
            else -> "Now on air and the next programmes"
        }
        if (g?.now != null) programmeGuideEntries.addView(programmeEntry("NOW ON", g.now, true))
        if (g?.next != null) programmeGuideEntries.addView(programmeEntry("UP NEXT", g.next, false))
        g?.upcoming.orEmpty().forEachIndexed { index, programme ->
            programmeGuideEntries.addView(programmeEntry(if (g?.next == null && index == 0) "UP NEXT" else "LATER", programme, false))
        }
        programmeGuideCredit.text = if (guideHasSchedule(g))
            "Schedule source: ${g?.source}. Listings are informational; the live stream stays native."
            else "Checked the TG guide and the community JioTV XMLTV fallback. Live playback is unaffected."
    }

    private fun programmeEntry(label: String, p: Programme, currentProgramme: Boolean): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((14 * dp).toInt(), (12 * dp).toInt(), (14 * dp).toInt(), (12 * dp).toInt())
            background = Ui.pill(dp, 0x66151C2D, 14f).apply { setStroke((1 * dp).toInt().coerceAtLeast(1), 0x2AFFFFFF) }
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = (10 * dp).toInt() }
        }
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val badge = TextView(this).apply {
            text = label; textSize = 9f; typeface = fSemi; setTextColor(if (currentProgramme) Accent.ION else Accent.SIGNAL)
            letterSpacing = 0.08f
        }
        val time = TextView(this).apply {
            text = Epg.range(p); textSize = 10f; typeface = fSemi; setTextColor(Accent.DIM); gravity = Gravity.END
            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
        }
        top.addView(badge, LinearLayout.LayoutParams(0, -2, 1f)); top.addView(time, LinearLayout.LayoutParams(0, -2, 1.5f))
        card.addView(top)
        card.addView(TextView(this).apply {
            text = p.title; textSize = 16f; typeface = fSemi; setTextColor(Accent.TEXT); maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END; setPadding(0, (7 * dp).toInt(), 0, 0)
        })
        if (p.desc.isNotBlank()) card.addView(TextView(this).apply {
            text = p.desc; textSize = 11f; setTextColor(Accent.DIM); maxLines = 3; ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(0, (5 * dp).toInt(), 0, 0)
        })
        if (currentProgramme) card.addView(ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000; progress = (Epg.progress(p) * 1000).toInt(); progressTintList = ColorStateList.valueOf(Accent.ION)
            progressBackgroundTintList = ColorStateList.valueOf(0x55FFFFFF); minimumHeight = (3 * dp).toInt()
        }, LinearLayout.LayoutParams(-1, (3 * dp).toInt()).apply { topMargin = (10 * dp).toInt() })
        return card
    }

    private fun closeProgrammeGuide() {
        if (!programmeGuideShown) return
        programmeGuideShown = false; programmeGuideCurrentChannel = null
        programmeGuidePanel.animate().cancel(); programmeGuideScrim.animate().cancel()
        programmeGuidePanel.animate().alpha(0f).translationX(24 * dp).setDuration(170).withEndAction {
            if (!programmeGuideShown) programmeGuidePanel.visibility = View.GONE
        }.start()
        programmeGuideScrim.animate().alpha(0f).setDuration(170).withEndAction {
            if (!programmeGuideShown) programmeGuideScrim.visibility = View.GONE
        }.start()
        if (layer.visibility == View.VISIBLE && !guideShown) {
            showInfo()
            infoGuideAction.requestFocus()
        }
    }

    private fun resetProgrammeGuide() {
        programmeGuideShown = false; programmeGuideCurrentChannel = null
        if (::programmeGuidePanel.isInitialized) {
            programmeGuidePanel.animate().cancel(); programmeGuideScrim.animate().cancel()
            programmeGuidePanel.visibility = View.GONE; programmeGuideScrim.visibility = View.GONE
            programmeGuidePanel.alpha = 1f; programmeGuidePanel.translationX = 0f; programmeGuideScrim.alpha = 1f
        }
    }

    private fun programmeGuideKey(e: KeyEvent): Boolean {
        if (e.action != KeyEvent.ACTION_DOWN) return false
        when (e.keyCode) {
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_GUIDE -> { closeProgrammeGuide(); return true }
            KeyEvent.KEYCODE_CHANNEL_UP -> { closeProgrammeGuide(); play(current - 1); return true }
            KeyEvent.KEYCODE_CHANNEL_DOWN -> { closeProgrammeGuide(); play(current + 1); return true }
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> return true
        }
        return false
    }
    private fun playMsg(msg: String?) {
        if (msg == null) playStatus.visibility = View.GONE else { playStatus.text = msg; playStatus.visibility = View.VISIBLE }
    }

    // ---------- native live playback ----------
    /** User picked a channel (or zapped): start with the site's current sources, never a saved web engine. */
    private fun play(index: Int) {
        if (shown.isEmpty()) return
        if (drmProbeRunning) { playMsg("The local DRM check is still running. Please wait, or restart the app if the system service is stuck."); return }
        current = (index + shown.size) % shown.size
        requestedNativeProvider = null
        val ch = shown[current]
        stopPreview(); rememberChannel(ch); recovery.reset(); beginNativeSession(ch)
        home.visibility = View.INVISIBLE
        heroText.animate().cancel(); heroBackdrop.animate().cancel(); rowsView.animate().cancel()
        ui.removeCallbacks(heroRunnable); ui.removeCallbacks(heroGuideRunnable)
        nativeSources = emptyList(); serverIdx = 0
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); layer.keepScreenOn = true
        if (layer.visibility != View.VISIBLE) { layer.animate().cancel(); layer.alpha = 1f; layer.visibility = View.VISIBLE }
        playMsg(null); bindInfo(ch)
        resolveAndStart(ch)
    }

    /** Resolve the last working native provider first; never wait for a web player or bundled playlist. */
    private fun resolveAndStart(ch: Channel, fresh: Boolean = false) {
        val token = ++playToken
        resolveJob?.cancel(true)
        nativeSources = emptyList(); serverIdx = 0
        nativeProviders = providerOrder(ch); nextNativeProvider = 1; nativePassFresh = fresh
        val provider = nativeProviders.first()
        enterNativeProvider(ch, provider)
        releasePlayer(); spinner.visibility = View.GONE
        infoNext.text = "Auto · ${providerName(provider)} · resolving live configuration…"
        infoNext.visibility = View.VISIBLE
        playMsg(if (fresh) "Refreshing ${providerName(provider)} stream…" else "Connecting to ${providerName(provider)}…")
        resolveJob = playbackIo.submit {
            try {
                val sources = LiveMetadataRequests.fetch(provider, ch.id, fresh = fresh, v2 = ch.v2)
                runOnUiThread {
                    if (token != playToken || layer.visibility != View.VISIBLE) return@runOnUiThread
                    nativeSources = sources
                    val saved = prefs.getString("source_${ch.key}", null)
                    serverIdx = sources.indexOfFirst { sourceKey(it) == saved }.coerceAtLeast(0)
                    if (sources.isEmpty()) advanceNative("No native stream was advertised") else startNative()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    if (token == playToken && layer.visibility == View.VISIBLE) {
                        diagnostics.metadataFailed(PlaybackProblems.from(e), metadataFailureReason(provider, ch, e))
                        nativeSources = emptyList()
                        advanceNative(metadataFailureReason(provider, ch, e))
                    }
                }
            }
        }
    }

    private fun providerOrder(ch: Channel) = ChannelProviderOrder.order(
        ch.id,
        ch.v2,
        prefs.getString("source_provider_${ch.key}", null),
        requestedNativeProvider?.takeIf { it.first == ch.key }?.second
    )
    private fun savedCompatibility(ch: Channel, provider: LiveProvider): Boolean = prefs.getBoolean(
        "native_compat_${ch.key}_${provider.name}",
        prefs.getString("source_provider_${ch.key}", null) == provider.website && prefs.getBoolean("native_compat_${ch.key}", false))
    private fun enterNativeProvider(ch: Channel, provider: LiveProvider) {
        currentNativeProvider = provider
        diagnostics.resolving(if (provider == LiveProvider.JJTV) PlaybackDiagnostics.Provider.JJTV else PlaybackDiagnostics.Provider.TG)
        compatiblePlayback = savedCompatibility(ch, provider) || decoderRetries.used(provider)
        providerWindow.enter(SystemClock.elapsedRealtime())
    }
    private fun providerName(provider: LiveProvider) = if (provider == LiveProvider.TG) "TG TV" else "JJTV"
    private fun sourceKey(source: TgStream) = LiveProviders.sourceKey(source)
    private fun rememberNativeSource(ch: Channel, source: TgStream) {
        prefs.edit().putString("source_${ch.key}", sourceKey(source))
            .putString("source_provider_${ch.key}", source.requestOrigin).apply()
    }

    /** Load another advertised native source only after the current list fails. Keep one player engine. */
    private fun loadMoreNative(ch: Channel, fresh: Boolean, reason: String) {
        val providerIndex = nextNativeProvider
        val provider = nativeProviders.getOrNull(providerIndex) ?: run { advanceNative(reason); return }
        nextNativeProvider = providerIndex + 1
        enterNativeProvider(ch, provider)
        val token = ++playToken
        resolveJob?.cancel(true)
        spinner.visibility = View.GONE
        infoNext.text = "Auto · ${providerName(provider)} · resolving live configuration…"
        infoNext.visibility = View.VISIBLE
        playMsg("Automatically trying ${providerName(provider)} native streams…")
        resolveJob = playbackIo.submit {
            var failureReason = reason
            val extra = try { LiveMetadataRequests.fetch(provider, ch.id, fresh = fresh, v2 = ch.v2) } catch (error: Exception) {
                runOnUiThread { if (token == playToken) diagnostics.metadataFailed(PlaybackProblems.from(error), metadataFailureReason(provider, ch, error)) }
                failureReason = metadataFailureReason(provider, ch, error)
                emptyList()
            }
            runOnUiThread {
                if (token != playToken || layer.visibility != View.VISIBLE) return@runOnUiThread
                if (extra.isEmpty()) advanceNative(failureReason)
                else {
                    val first = nativeSources.size
                    nativeSources = nativeSources + extra
                    serverIdx = first
                    startNative()
                }
            }
        }
    }

    // Keep a comfortable live buffer, but don't wait for the full buffer before showing the first frame.
    private fun liveCfg() = MediaItem.LiveConfiguration.Builder().setTargetOffsetMs(12000)
        .setMinOffsetMs(6000).setMaxOffsetMs(40000).setMinPlaybackSpeed(1f).setMaxPlaybackSpeed(1f).build()

    /** Shared by full-screen playback and previews so headers, type, redirects and DRM cannot drift apart. */
    private fun nativeMediaSource(source: TgStream, trace: PlaybackDiagnostics.Attempt? = null): MediaSource {
        val data = Streams.factory(source)
        val factory: MediaSource.Factory = when (source.type) {
            "hls" -> HlsMediaSource.Factory(data).setLoadErrorHandlingPolicy(NativeLoadErrorPolicy())
            "mp4" -> ProgressiveMediaSource.Factory(data).setLoadErrorHandlingPolicy(NativeLoadErrorPolicy())
            else -> DashMediaSource.Factory(data).setLoadErrorHandlingPolicy(NativeLoadErrorPolicy())
        }
        val item = MediaItem.Builder().setUri(source.url).setLiveConfiguration(liveCfg())
            .setMimeType(when (source.type) { "hls" -> MimeTypes.APPLICATION_M3U8; "mp4" -> MimeTypes.VIDEO_MP4; else -> MimeTypes.APPLICATION_MPD })
        source.drm?.let { drm ->
            val license = ClearKey.license(drm)
            if (license != null) {
                // The website overrides manifest DRM with its supplied ClearKeys. Supplying a local
                // licence alone is insufficient for Widevine-only MPDs or missing default_KID tags.
                (factory as? DashMediaSource.Factory)?.setManifestParser(ClearKeyDashManifestParser(drm))
                val manager = DefaultDrmSessionManager.Builder()
                    .setUuidAndExoMediaDrmProvider(C.CLEARKEY_UUID,
                        trace?.let { ObservedMediaDrm.provider(it) } ?: FrameworkMediaDrm.DEFAULT_PROVIDER)
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
        spinner.visibility = View.GONE
        val sourceLabel = if (LiveProviders.provider(source) == LiveProvider.TG) "TG TV · ${source.label}" else source.label
        val probeStatus = starProbeStatus(currentNativeProvider, shown.getOrNull(current), nativeSources)
        infoNext.text = "Auto · $sourceLabel · ${serverIdx + 1} of ${nativeSources.size}" +
            (if (nativeSources.size > 1) "   ·   OK switches server" else "") + (probeStatus?.let { "   ·   $it" } ?: "")
        infoNext.visibility = View.VISIBLE
        val trace = diagnostics.begin(
            if (source.requestOrigin == JjtvResolver.SITE) PlaybackDiagnostics.Provider.JJTV else PlaybackDiagnostics.Provider.TG,
            when (source.type) { "hls" -> PlaybackDiagnostics.Media.HLS; "mp4" -> PlaybackDiagnostics.Media.FILE; else -> PlaybackDiagnostics.Media.DASH },
            when { source.drm == null -> PlaybackDiagnostics.Drm.NONE
                ClearKey.license(source.drm) != null -> PlaybackDiagnostics.Drm.CLEARKEY
                else -> PlaybackDiagnostics.Drm.WIDEVINE },
            source.omitReferrer, compatiblePlayback)
        diagnostics.metadataStatus(probeStatus)
        try { attach(nativeMediaSource(source, trace), trace) }
        catch (error: Exception) { trace.failed(PlaybackProblems.from(error)); advanceNative("This source could not be opened by the native player") }
    }

    private fun attach(ms: MediaSource, trace: PlaybackDiagnostics.Attempt) {
        releasePlayer()
        stopPreview()
        val profile = NativePlaybackPolicy.profile(television, lowEnd, compatiblePlayback, startCap())
        val channel = shown.getOrNull(current)
        val stable = stablePicture(channel)
        val ts = NativePlaybackPolicy.tracks(this, profile, shown.getOrNull(current)?.name?.contains("Hindi", true) == true)
        val bw = androidx.media3.exoplayer.upstream.DefaultBandwidthMeter.Builder(this)
            .setInitialBitrateEstimate(if (profile.softwareFirst) 1_000_000L else if (television) 2_000_000L else 4_000_000L).build()
        val p = ExoPlayer.Builder(this, NativePlaybackPolicy.renderers(this, profile))
            .setLoadControl(if (stable) androidx.media3.exoplayer.DefaultLoadControl.Builder()
                .setBufferDurationsMs(10_000, 30_000, 1500, 4000).setBackBuffer(0, false)
                .setTargetBufferBytes(profile.bufferBytes).setPrioritizeTimeOverSizeThresholds(true).build()
                else NativePlaybackPolicy.buffers(profile)).setTrackSelector(ts).setBandwidthMeter(bw).build()
        p.setVideoScalingMode(C.VIDEO_SCALING_MODE_SCALE_TO_FIT)
        trackSel = ts; activeAttempt = trace
        val controller = NativePictureController(p, ts, { player === p && playbackActive },
            profile.allowReportedCapabilityExceedance, profile.height, stable,
            remember = { selected -> channel?.let { prefs.edit().putInt("q_${it.key}", selected.height)
                .putBoolean("stable_picture_${it.key}", true).apply() } },
            notice = { text -> if (player === p) { Toast.makeText(this, text, Toast.LENGTH_LONG).show(); showInfo() } })
        picture = controller
        p.addAnalyticsListener(controller); p.addListener(controller)
        p.addAnalyticsListener(NativePlaybackDiagnostics(trace))
        renderedFrame = false
        val health = PlaybackHealth(SystemClock.elapsedRealtime(), expectsVideo())
        playbackHealth = health
        p.setVideoFrameMetadataListener { _, _, _, _ -> health.videoProgress(SystemClock.elapsedRealtime()) }
        p.setMediaSource(ms); p.playWhenReady = true; p.addListener(listenerFor(p, trace))
        pv.player = p; player = p
        prepareOnSurface(p)
        showInfo()
    }

    /** Avoid configuring an older TV codec against a placeholder before its real SurfaceView exists. */
    private fun prepareOnSurface(owner: ExoPlayer) {
        val view = pv.videoSurfaceView as? SurfaceView
        if (view == null || view.holder.surface.isValid) { activeAttempt?.surfaceReady(); owner.prepare(); return }
        playMsg("Waiting for the TV video surface…")
        val holder = view.holder
        val callback = object : SurfaceHolder.Callback {
            private val gate = SurfaceStartGate(
                active = { player === owner && playbackActive },
                ready = { holder.surface.isValid },
                prepare = {
                    holder.removeCallback(this)
                    if (surfaceWait?.second === this) surfaceWait = null
                    activeAttempt?.surfaceReady()
                    try { owner.prepare() } catch (error: Exception) {
                        activeAttempt?.failed(PlaybackProblems.from(error))
                        if (player === owner && !(PlaybackProblems.from(error).family == PlaybackDiagnostics.Family.DECODER &&
                            retryCompatibleDecoder("The TV could not start its video decoder"))) advanceNative("The native video decoder could not be started")
                    }
                }
            )
            override fun surfaceCreated(holder: SurfaceHolder) = gate.signal()
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = gate.signal()
            override fun surfaceDestroyed(holder: SurfaceHolder) = Unit
        }
        surfaceWait = holder to callback
        holder.addCallback(callback)
        // Surface creation can race registration; the callback is idempotent.
        if (holder.surface.isValid) callback.surfaceCreated(holder)
    }

    private fun listenerFor(owner: ExoPlayer, trace: PlaybackDiagnostics.Attempt) = object : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
            if (player !== owner) return
            trace.state(when (state) { Player.STATE_READY -> "READY"; Player.STATE_BUFFERING -> "BUFFERING"; Player.STATE_ENDED -> "ENDED"; else -> "IDLE" })
            when (state) {
                Player.STATE_READY -> {
                    if (renderedFrame || !expectsVideo()) {
                        spinner.visibility = View.GONE; playMsg(null)
                        if (!expectsVideo()) { startupBudget.rendered(); providerWindow.rendered() }
                    } else {
                        spinner.visibility = View.GONE
                        playMsg("Stream connected — waiting for the first picture…")
                    }
                }
                Player.STATE_BUFFERING -> spinner.visibility = View.GONE
                Player.STATE_ENDED -> { trace.failed(PlaybackDiagnostics.Problem(PlaybackDiagnostics.Family.PLAYER, detail = "StreamEnded")); advanceNative("The live stream ended") }
            }
        }
        override fun onRenderedFirstFrame() {
            if (player !== owner) return
            renderedFrame = true
            trace.frameRendered()
            playbackHealth?.firstFrame(SystemClock.elapsedRealtime())
            startupBudget.rendered(); providerWindow.rendered()
            if (owner.playbackState == Player.STATE_READY) { spinner.visibility = View.GONE; playMsg(null) }
            val ch = shown.getOrNull(current) ?: return
            nativeSources.getOrNull(serverIdx)?.let { rememberNativeSource(ch, it) }
        }
        override fun onPlayerError(error: PlaybackException) {
            if (player !== owner) return
            trace.failed(PlaybackProblems.from(error, error.errorCode))
            val code = httpCode(error)
            val provider = if (nativeSources.getOrNull(serverIdx)?.requestOrigin == JjtvResolver.SITE) "JJTV" else "TG TV"
            val stage = trace.lastLoadStageName()?.lowercase()?.replaceFirstChar { it.uppercase() }
            val message = when {
                code == 451 && stage != null -> "$stage HTTP 451"
                code == 451 -> "$provider upstream HTTP 451"
                code != null && stage != null -> "$stage HTTP $code"
                code != null -> "$provider stream HTTP $code"
                error.errorCode in 6000..6999 -> "$provider DRM could not be opened (code ${error.errorCode})"
                error.errorCode in 4000..4999 -> "This device couldn't decode this source (code ${error.errorCode})"
                else -> "The live stream stopped responding (code ${error.errorCode})"
            }
            if (refreshNativeCredentials(code)) return
            if (retryRefusedProvider(code)) return
            if (code == null && PlaybackProblems.from(error, error.errorCode).family == PlaybackDiagnostics.Family.DECODER &&
                retryCompatibleDecoder(message)) return
            advanceNative(message)
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

    /** Report only whether the exact Star channel yielded an advertised native source; never echo its URL or cookie. */
    private fun starProbeStatus(provider: LiveProvider, channel: Channel?, sources: List<TgStream>): String? {
        if (provider != LiveProvider.JJTV || channel == null || channel.id !in setOf("1108", "362")) return null
        val count = sources.count { source ->
            LiveProviders.provider(source) == LiveProvider.JJTV && try { JjtvProtocol.isStarUrl(source.url) } catch (_: Exception) { false }
        }
        return "Star cookie probe: channel ${channel.id}; ${if (count == 0) "no exact source" else "$count exact source${if (count == 1) "" else "s"}"}"
    }

    private fun metadataFailureReason(provider: LiveProvider, channel: Channel, error: Throwable): String = when {
        error is LiveMetadataTimeoutException -> "${providerName(provider)} metadata failed: ${LiveMetadataRequests.deadlineSeconds(provider)}-second resolution deadline reached"
        error is JjtvHttpException -> "JJTV metadata HTTP ${error.status}"
        error is TgResolveException -> "TG metadata failed${error.status?.let { " (HTTP $it)" }.orEmpty()}"
        error is java.net.SocketTimeoutException -> "${providerName(provider)} metadata timed out"
        error is java.net.UnknownHostException -> "${providerName(provider)} metadata host unavailable"
        error is javax.net.ssl.SSLException -> "${providerName(provider)} metadata TLS failed"
        provider == LiveProvider.JJTV && channel.id in setOf("1108", "362") ->
            "JJTV Star cookie probe: no exact-channel native source returned for channel ID ${channel.id}"
        provider == LiveProvider.TG -> "TG metadata failed; no native stream configuration was returned"
        else -> "JJTV metadata failed; no native stream configuration was returned"
    }

    /** Classify a relay stall without retaining or displaying the stream URL itself. */
    private fun relayStallReason(): String {
        val source = nativeSources.getOrNull(serverIdx) ?: return "The live stream stopped responding"
        val relay = try { URI(source.url).host.orEmpty().contains("allinonereborn", true) } catch (_: Exception) { false }
        return if (relay && LiveProviders.provider(source) == LiveProvider.TG) "TG relay stalled" else "The live stream stopped responding"
    }

    private fun expectsVideo() = shown.getOrNull(current)?.category?.equals("Radio", true) != true

    private fun beginNativeSession(ch: Channel) {
        playbackActive = true
        diagnostics = PlaybackDiagnostics(); decoderRetries.clear(); authRecovery.reset(); providerWindow.clear()
        refusalRetryCount = 0
        compatiblePlayback = savedCompatibility(ch, providerOrder(ch).first())
        startupBudget.start(SystemClock.elapsedRealtime())
        ui.removeCallbacks(nativeHealthCheck); ui.postDelayed(nativeHealthCheck, 500)
    }

    private fun endNativeSession() {
        playbackActive = false; startupBudget.clear(); providerWindow.clear(); ui.removeCallbacks(nativeHealthCheck)
    }

    private val nativeHealthCheck = object : Runnable {
        override fun run() {
            if (!playbackActive || layer.visibility != View.VISIBLE) return
            val now = SystemClock.elapsedRealtime()
            if (startupBudget.expired(now)) {
                activeAttempt?.timeout()
                fail("No playable picture within 60 seconds. Hold OK to retry.")
                return
            }
            if (providerWindow.expired(now)) {
                activeAttempt?.timeout()
                recovery.skip(nativeSources.indices.filter { LiveProviders.provider(nativeSources[it]) == currentNativeProvider })
                advanceNative("${providerName(currentNativeProvider)} did not start within its 25-second turn")
                if (playbackActive) ui.postDelayed(this, 500)
                return
            }
            val p = player
            val health = playbackHealth
            if (p != null && health != null) {
                picture?.checkBuffering(now)
                val state = when (p.playbackState) {
                    Player.STATE_READY -> PlaybackHealth.State.READY
                    Player.STATE_BUFFERING -> PlaybackHealth.State.BUFFERING
                    Player.STATE_ENDED -> PlaybackHealth.State.ENDED
                    else -> PlaybackHealth.State.IDLE
                }
                val result = health.sample(now, state, p.isPlaying, p.currentPosition, p.playWhenReady)
                if (result.failure != null) activeAttempt?.timeout()
                when (result.failure) {
                    PlaybackHealth.Failure.NO_PICTURE, PlaybackHealth.Failure.VIDEO_STOPPED -> {
                        val reason = if (surfaceWait != null) "The TV video surface is not ready" else relayStallReason()
                        if (activeAttempt?.canRetryDecoder() != true || !retryCompatibleDecoder(reason)) advanceNative(reason)
                    }
                    PlaybackHealth.Failure.STARTUP, PlaybackHealth.Failure.BUFFERING, PlaybackHealth.Failure.ENDED ->
                        advanceNative(relayStallReason())
                    null -> if (result.stable) {
                        recovery.reset(); authRecovery.reset(); refusalRetryCount = 0
                        shown.getOrNull(current)?.let { ch ->
                            prefs.edit().putBoolean("native_compat_${ch.key}_${currentNativeProvider.name}", compatiblePlayback).apply()
                        }
                    }
                }
            }
            if (diagnosticsText != null) refreshPlaybackDetails()
            if (playbackActive) ui.postDelayed(this, 500)
        }
    }

    /** One same-source retry: synchronous adapters, supported AVC/AAC, lower load, software preferred.
     * The codec selector still enforces secure-decoder requirements. This does not bypass DRM.
     */
    private fun retryCompatibleDecoder(reason: String): Boolean {
        if (compatiblePlayback || !expectsVideo() || nativeSources.getOrNull(serverIdx) == null ||
            startupBudget.expired(SystemClock.elapsedRealtime())) return false
        compatiblePlayback = true; decoderRetries.mark(currentNativeProvider)
        providerWindow.recover(SystemClock.elapsedRealtime())
        startupBudget.recover(SystemClock.elapsedRealtime())
        val token = ++playToken
        resolveJob?.cancel(true); releasePlayer()
        playMsg("$reason. Trying native TV compatibility mode…")
        ui.post { if (playbackActive && token == playToken && layer.visibility == View.VISIBLE) startNative() }
        return true
    }

    /** A rejected token needs fresh configuration, not a second header style carrying the same
     * credential. Refresh this provider once, inside the existing provider/whole-cycle deadlines.
     */
    private fun refreshNativeCredentials(code: Int?): Boolean {
        val ch = shown.getOrNull(current) ?: return false
        val provider = currentNativeProvider
        if (startupBudget.expired(SystemClock.elapsedRealtime()) || providerWindow.expired(SystemClock.elapsedRealtime()) ||
            !authRecovery.retry(provider, code)) return false
        startupBudget.recover(SystemClock.elapsedRealtime()); providerWindow.recover(SystemClock.elapsedRealtime())
        val refusedSource = nativeSources.getOrNull(serverIdx)
        val previousKey = refusedSource?.let(::sourceKey)
        recovery.skip(nativeSources.indices.filter { LiveProviders.provider(nativeSources[it]) == provider })
        val token = ++playToken
        resolveJob?.cancel(true); releasePlayer()
        diagnostics.resolving(if (provider == LiveProvider.JJTV) PlaybackDiagnostics.Provider.JJTV else PlaybackDiagnostics.Provider.TG)
        spinner.visibility = View.GONE
        infoNext.text = "Auto · ${providerName(provider)} · refreshing rejected credentials…"
        infoNext.visibility = View.VISIBLE
        playMsg("${providerName(provider)} returned HTTP $code — fetching fresh stream credentials once…")
        resolveJob = playbackIo.submit {
            try {
                val sources = LiveMetadataRequests.fetch(provider, ch.id, fresh = true, v2 = ch.v2)
                runOnUiThread {
                    if (token != playToken || !playbackActive || layer.visibility != View.VISIBLE) return@runOnUiThread
                    if (sources.isEmpty()) advanceNative("The provider supplied no refreshed stream")
                    else {
                        val retained = nativeSources.withIndex().filter { LiveProviders.provider(it.value) != provider }
                        recovery.remapSources(retained.mapIndexed { index, item -> item.index to index }.toMap())
                        val first = retained.size
                        nativeSources = retained.map { it.value } + sources
                        // A refused request style is not repeated: try the provider's other
                        // documented style for the same channel before moving on.
                        val pick = if (code == 403) RequestProfileFallback.preferredIndex(sources, refusedSource) {
                            sourceKey(it) == previousKey
                        } else sources.indexOfFirst { sourceKey(it) == previousKey }.coerceAtLeast(0)
                        serverIdx = first + pick.coerceIn(0, (sources.size - 1).coerceAtLeast(0))
                        startNative()
                    }
                }
            } catch (error: Exception) {
                runOnUiThread {
                    if (token != playToken || !playbackActive || layer.visibility != View.VISIBLE) return@runOnUiThread
                    diagnostics.metadataFailed(PlaybackProblems.from(error), metadataFailureReason(provider, ch, error))
                    advanceNative(metadataFailureReason(provider, ch, error))
                }
            }
        }
        return true
    }

    /** The TV is refused on some requests and accepted seconds later. Give the provider that
     * actually plays this channel two delayed, freshly credentialed retries before moving to a
     * provider whose advertised DRM cannot be opened. Bounded inside the existing playback budget;
     * every other HTTP result keeps the existing immediate fallback.
     */
    private fun retryRefusedProvider(code: Int?): Boolean {
        val delayMs = RefusalRetryBackoff.delayFor(refusalRetryCount)
        if (code != 403 || delayMs == null || !playbackActive) return false
        val ch = shown.getOrNull(current) ?: return false
        if (layer.visibility != View.VISIBLE || startupBudget.expired(SystemClock.elapsedRealtime())) return false
        refusalRetryCount++
        val provider = currentNativeProvider
        val refusedSource = nativeSources.getOrNull(serverIdx)
        val token = ++playToken
        resolveJob?.cancel(true); releasePlayer()
        spinner.visibility = View.GONE
        infoNext.text = "Auto · ${providerName(provider)} · paced retry $refusalRetryCount of 2…"
        infoNext.visibility = View.VISIBLE
        playMsg("${providerName(provider)} refused this request — retry $refusalRetryCount of 2 in ${delayMs / 1000}s…")
        resolveJob = playbackIo.submit {
            try { Thread.sleep(delayMs) } catch (_: InterruptedException) {
                Thread.currentThread().interrupt(); return@submit
            }
            try {
                val sources = LiveMetadataRequests.fetch(provider, ch.id, fresh = true, v2 = ch.v2)
                runOnUiThread {
                    if (token != playToken || !playbackActive || layer.visibility != View.VISIBLE) return@runOnUiThread
                    if (sources.isEmpty()) advanceNative("${providerName(provider)} returned no fresh source after the refused request")
                    else {
                        // A new attempt has its own single credential refresh and provider window.
                        authRecovery.reset()
                        providerWindow.enter(SystemClock.elapsedRealtime())
                        nativeSources = sources
                        // Same rule as the credential refresh: do not replay the style that was
                        // just refused when the provider advertises another one for this channel.
                        serverIdx = RequestProfileFallback.preferredIndex(sources, refusedSource) { false }
                        startNative()
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    if (token == playToken && playbackActive) {
                        diagnostics.metadataFailed(PlaybackProblems.from(e), metadataFailureReason(provider, ch, e))
                        advanceNative(metadataFailureReason(provider, ch, e))
                    }
                }
            }
        }
        return true
    }

    /** Bounded recovery in ExoPlayer: current servers, another native source, one refresh, then an error. */
    private fun advanceNative(reason: String) {
        val ch = shown.getOrNull(current) ?: return
        startupBudget.recover(SystemClock.elapsedRealtime())
        providerWindow.recover(SystemClock.elapsedRealtime())
        releasePlayer()
        if (startupBudget.expired(SystemClock.elapsedRealtime())) { fail("No playable picture within 60 seconds. Hold OK to retry."); return }
        when (val action = recovery.failed(serverIdx, nativeSources.size, canLoadMore = nextNativeProvider < nativeProviders.size)) {
            is PlaybackRecovery.Action.Source -> {
                serverIdx = action.index
                val provider = LiveProviders.provider(nativeSources[serverIdx])
                if (provider != currentNativeProvider) enterNativeProvider(ch, provider)
                playMsg("Automatically trying ${nativeSources[serverIdx].label}…")
                val token = playToken
                ui.post { if (token == playToken && layer.visibility == View.VISIBLE) startNative() }
            }
            is PlaybackRecovery.Action.MoreSources -> loadMoreNative(ch, action.fresh || nativePassFresh, reason)
            PlaybackRecovery.Action.Refresh -> {
                TgServers.invalidate(ch.id, ch.v2)
                resolveAndStart(ch, fresh = true)
            }
            PlaybackRecovery.Action.Unavailable -> fail("$reason. Hold OK to reload or choose another channel.")
        }
    }

    /** Left / OK switches native sources, not players; a single-source channel simply reloads. */
    private fun switchServer() {
        if (drmProbeRunning) return
        if (nativeSources.size < 2) { reloadStream(); return }
        playToken++; resolveJob?.cancel(true); recovery.reset()
        shown.getOrNull(current)?.let { beginNativeSession(it) }
        releasePlayer()
        serverIdx = (serverIdx + 1) % nativeSources.size
        shown.getOrNull(current)?.let { enterNativeProvider(it, LiveProviders.provider(nativeSources[serverIdx])) }
        playMsg("Switching to ${nativeSources[serverIdx].label}…")
        startNative()
    }

    /** Manual reload really bypasses cached signed URLs and re-reads the site's current player configuration. */
    private fun reloadStream() {
        if (drmProbeRunning) { showPlaybackDetails(); return }
        val ch = shown.getOrNull(current) ?: return
        recovery.reset(); beginNativeSession(ch); TgServers.invalidate(ch.id, ch.v2)
        resolveAndStart(ch, fresh = true)
    }

    private fun showNativeSourceMenu() {
        val ch = shown.getOrNull(current) ?: return
        if (sourceMenu?.isShowing == true) return
        val choices = ArrayList<Pair<String, () -> Unit>>()
        choices.add("Automatic native source" to { selectNativeProvider(null) })
        if (!ch.v2) choices.add("Play JJTV now (native)" to { selectNativeProvider(LiveProvider.JJTV) })
        choices.add("Play TG TV now (native)" to { selectNativeProvider(LiveProvider.TG) })
        choices.add((if (prefs.getBoolean("native_force_tv", false)) "TV compatibility: return to automatic detection" else "TV compatibility: force TV mode (generic boxes)") to {
            prefs.edit().putBoolean("native_force_tv", !prefs.getBoolean("native_force_tv", false)).apply()
            selectNativeProvider(requestedNativeProvider?.takeIf { it.first == ch.key }?.second)
        })
        if (player != null) choices.add("Picture quality · ${picture?.modeLabel() ?: "Auto"}" to { showPictureMenu() })
        choices.add("Playback details" to { showPlaybackDetails() })
        val dialog = AlertDialog.Builder(this).setTitle("Native stream source")
            .setItems(choices.map { it.first }.toTypedArray()) { _, index -> choices[index].second() }
            .setNegativeButton("Close", null).create()
        sourceMenu = dialog
        dialog.setOnDismissListener { if (sourceMenu === dialog) sourceMenu = null }
        dialog.show()
    }

    private fun showPictureMenu() {
        val controller = picture ?: return
        val ch = shown.getOrNull(current) ?: return
        val offered = controller.choices().take(24)
        val labels = listOf("Auto — adaptive quality", "Stable — hold quality, step down only when needed") + offered.map { it.label }
        sourceMenu?.dismiss()
        val dialog = AlertDialog.Builder(this).setTitle("Picture quality · ${controller.modeLabel()}")
            .setItems(labels.toTypedArray()) { _, index ->
                if (picture !== controller || shown.getOrNull(current)?.key != ch.key) return@setItems
                if (index == 0) {
                    prefs.edit().remove("q_${ch.key}").putBoolean("stable_picture_${ch.key}", false).apply()
                    val cap = if (television || lowEnd) 720 else 1080
                    trackSel?.let { selector -> selector.setParameters(selector.buildUponParameters().setMaxVideoSize(cap * 16 / 9, cap)) }
                    controller.automatic(cap)
                } else {
                    prefs.edit().putBoolean("stable_picture_${ch.key}", true).apply()
                    if (index == 1) controller.stable() else offered.getOrNull(index - 2)?.let(controller::select)
                }
            }.setNegativeButton("Close", null).create()
        sourceMenu = dialog
        dialog.setOnDismissListener { if (sourceMenu === dialog) sourceMenu = null }
        dialog.show()
    }

    private fun selectNativeProvider(provider: LiveProvider?) {
        if (layer.visibility != View.VISIBLE) return
        val ch = shown.getOrNull(current) ?: return
        if (drmProbeRunning) { showPlaybackDetails(); return }
        if (ch.v2 && provider == LiveProvider.JJTV) return
        requestedNativeProvider = provider?.let { ch.key to it }
        recovery.reset(); beginNativeSession(ch)
        resolveAndStart(ch, fresh = true)
    }

    private fun playbackDetailsText(): String = buildString {
        append("${AppBuildInfo.label(this@MainActivity)}\nPlayback baseline: 4.9.18 native requests\nAndroid ${Build.VERSION.RELEASE} / API ${Build.VERSION.SDK_INT}\n")
        append("${Build.MANUFACTURER.take(40)} ${Build.MODEL.take(48)} · ${Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown ABI"}\n")
        append("App process: ${if (android.os.Process.is64Bit()) "64-bit" else "32-bit"}\n")
        append("Device UTC: ${java.time.Instant.ofEpochMilli(System.currentTimeMillis())}\n")
        append("Engine: native Media3 / SurfaceView. WebView and Gecko are not used here.\n")
        append("TV reported codec-limit fallback: $television (unsupported DRM remains rejected)\n")
        shown.getOrNull(current)?.let { append("Channel ID: ${it.id.take(32)}${if (it.v2) " (V2)" else ""}\n") }
        picture?.let { append("\n${it.details()}\n") }
        append("\n${diagnostics.report()}\n\n$drmProbeStatus\n")
        append("\nCheck DRM stops playback and tests only this device's local ClearKey service. It does not test a real channel or prove decoded video.")
    }

    private fun refreshPlaybackDetails() { diagnosticsText?.text = playbackDetailsText() }

    private fun showPlaybackDetails() {
        if (diagnosticsDialog?.isShowing == true) { refreshPlaybackDetails(); return }
        val text = TextView(this).apply {
            text = playbackDetailsText(); textSize = 14f; setTextColor(Color.WHITE)
            typeface = Typeface.MONOSPACE
            setPadding((18 * dp).toInt(), (10 * dp).toInt(), (18 * dp).toInt(), (10 * dp).toInt())
        }
        val scroll = ScrollView(this).apply { addView(text); isFocusable = true }
        val holder = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(scroll, LinearLayout.LayoutParams(-1, (resources.displayMetrics.heightPixels * 0.45f).toInt()))
            addView(Button(this@MainActivity).apply {
                this.text = "Copy redacted report"
                setOnClickListener {
                    (getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager)
                        .setPrimaryClip(android.content.ClipData.newPlainText("AK TV playback details", playbackDetailsText()))
                    Toast.makeText(this@MainActivity, "Report copied — no URLs or keys", Toast.LENGTH_SHORT).show()
                }
            }, LinearLayout.LayoutParams(-1, -2))
        }
        val dialog = AlertDialog.Builder(this).setTitle("Native playback details")
            .setView(holder).setNegativeButton("Close", null).setPositiveButton("Retry", null)
            .setNeutralButton("Check DRM", null).create()
        diagnosticsText = text; diagnosticsDialog = dialog
        dialog.setOnDismissListener { if (diagnosticsDialog === dialog) { diagnosticsDialog = null; diagnosticsText = null } }
        dialog.setOnShowListener {
            dialog.window?.setLayout((resources.displayMetrics.widthPixels * 0.9f).toInt(), -2)
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (!drmProbeRunning) { dialog.dismiss(); reloadStream() }
            }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener { runNativeDrmCheck() }
        }
        dialog.show()
    }

    /** Explicit user action. No website requests, no real channel credentials, no extra session during playback. */
    private fun runNativeDrmCheck() {
        if (drmProbeRunning) return
        endNativeSession(); playToken++; resolveJob?.cancel(true); releasePlayer(); stopPreview()
        spinner.visibility = View.GONE
        val token = playToken
        drmProbeRunning = true
        drmProbeStatus = "ClearKey self-test RUNNING: checking the Android DRM service locally…"
        playMsg("Playback stopped for the local DRM check. MENU / OK shows details.")
        refreshPlaybackDetails()
        val timeout = Runnable {
            if (drmProbeRunning) {
                drmProbeStatus = "ClearKey self-test has not returned after 10 seconds. A local platform call may be stuck; this is not a network test. Restart the app/TV before retrying if it stays blocked."
                refreshPlaybackDetails()
                if (token == playToken && layer.visibility == View.VISIBLE) playMsg(drmProbeStatus)
            }
        }
        ui.postDelayed(timeout, 10_000)
        drmProbeIo.submit {
            val result = NativeClearKeyProbe.run()
            runOnUiThread {
                ui.removeCallbacks(timeout)
                if (isDestroyed) return@runOnUiThread
                drmProbeRunning = false; drmProbeStatus = result.describe()
                refreshPlaybackDetails()
                if (token == playToken && layer.visibility == View.VISIBLE)
                    playMsg("$drmProbeStatus\nPress OK / MENU for details; hold OK to retry.")
            }
        }
    }

    private fun fail(message: String) {
        endNativeSession(); playToken++; resolveJob?.cancel(true); releasePlayer(); spinner.visibility = View.GONE
        playMsg("$message\n${diagnostics.summary()}\nAK TV $appVersion · Android ${Build.VERSION.RELEASE} / API ${Build.VERSION.SDK_INT} · ${Build.MODEL.take(48)}\nMENU: choose JJTV / TG · OK: details · Hold OK: retry")
        ui.removeCallbacks(hideInfo); hideInfo.run()
    }

    private fun releasePlayer() {
        surfaceWait?.let { (holder, callback) -> holder.removeCallback(callback) }; surfaceWait = null
        playbackHealth = null; activeAttempt = null
        val old = player; player = null; trackSel = null
        pv.player = null
        picture?.let { controller -> old?.removeAnalyticsListener(controller); old?.removeListener(controller) }; picture = null
        old?.release()
    }
    private fun closePlayer() {
        sourceMenu?.dismiss(); diagnosticsDialog?.dismiss()
        endNativeSession(); playToken++; resolveJob?.cancel(true); playMsg(null)
        releasePlayer(); nativeSources = emptyList(); ui.removeCallbacks(hideInfo); resetGuide(); resetProgrammeGuide()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); layer.keepScreenOn = false
        layer.animate().cancel(); layer.visibility = View.GONE; home.visibility = View.VISIBLE
        home.post { if (layer.visibility != View.VISIBLE && !searchShown) {
            if (lastRowsFocus?.takeIf { it.isAttachedToWindow }?.requestFocus() != true) rowsView.requestFocus()
        } }
        if (tab < 2) { val sel = heroTile; refreshRowsKeepFocus(); setHero(sel, immediate = true) }
    }

    private fun shortOk() {
        if (!playbackActive) { showPlaybackDetails(); return }
        if (nativeSources.size > 1 && infoCard.alpha > 0.5f) switchServer() else showInfo()
    }

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        if (intro.visibility == View.VISIBLE) { if (e.action == KeyEvent.ACTION_DOWN) intro.skip(); return true }
        if (searchShown) return searchKey(e) || super.dispatchKeyEvent(e)
        if (layer.visibility == View.VISIBLE && programmeGuideShown) return programmeGuideKey(e) || super.dispatchKeyEvent(e)
        if (layer.visibility == View.VISIBLE && guideShown) return guideKey(e) || super.dispatchKeyEvent(e)
        if (layer.visibility == View.VISIBLE && (e.keyCode == KeyEvent.KEYCODE_DPAD_CENTER || e.keyCode == KeyEvent.KEYCODE_ENTER)) {
            // tap OK = info / next native source, hold OK = fetch a fresh stream
            if (e.action == KeyEvent.ACTION_DOWN) {
                if (e.repeatCount == 0) okLong = false
                else if (e.repeatCount == 8 && !okLong) { okLong = true; reloadStream() }
            } else if (e.action == KeyEvent.ACTION_UP && !okLong) shortOk()
            return true
        }
        if (layer.visibility == View.VISIBLE && e.action == KeyEvent.ACTION_DOWN) when (e.keyCode) {
            KeyEvent.KEYCODE_BACK -> { closePlayer(); return true }
            KeyEvent.KEYCODE_DPAD_RIGHT -> { openGuide(); return true }
            KeyEvent.KEYCODE_GUIDE -> { showProgrammeGuide(); return true }
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_UP -> { play(current - 1); return true }
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN -> { play(current + 1); return true }
            KeyEvent.KEYCODE_MENU -> { showNativeSourceMenu(); return true }
            KeyEvent.KEYCODE_INFO -> { showPlaybackDetails(); return true }
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
        if (layer.visibility != View.VISIBLE && e.action == KeyEvent.ACTION_DOWN) {
            val focus = currentFocus
            val inRail = focus != null && (focus === searchBtn || segs.any { it === focus })
            if (inRail) when (e.keyCode) {
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    val back = lastRowsFocus
                    if (back?.takeIf { it.isAttachedToWindow && isInside(it, rowsView) }?.requestFocus() != true) rowsView.requestFocus()
                    return true
                }
                KeyEvent.KEYCODE_DPAD_LEFT -> return true
                KeyEvent.KEYCODE_DPAD_UP -> if (focus === segLive) return true
                KeyEvent.KEYCODE_DPAD_DOWN -> if (focus === searchBtn) return true
            }
            if (focus != null && isInside(focus, rowsView)) {
                val rowView = rowsView.findContainingItemView(focus)
                val rowIndex = rowView?.let { rowsView.getChildAdapterPosition(it) } ?: -1
                val rowList = rowView?.findViewById<RecyclerView>(R.id.rowList)
                val card = rowList?.findContainingItemView(focus)
                if (e.keyCode == KeyEvent.KEYCODE_DPAD_LEFT && card != null && rowList.getChildAdapterPosition(card) == 0) {
                    segs[tab].requestFocus(); return true
                }
                if (e.keyCode == KeyEvent.KEYCODE_DPAD_UP && rowIndex == 0) {
                    if (!heroCollapsed && heroChips.childCount > 0) heroChips.getChildAt(0).requestFocus() else segs[tab].requestFocus()
                    return true
                }
            }
            if (focus != null && isInside(focus, heroChips) && e.keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                rowsView.requestFocus(); return true
            }
        }
        return super.dispatchKeyEvent(e)
    }
    /** Downloads a changed community feed off-main; only refreshes the visible V1 guide after a new file lands. */
    private fun refreshProgrammeFeed() {
        io.execute {
            val changed = ExternalEpg.refreshIfDue(applicationContext)
            if (changed) ui.post {
                val ch = if (layer.visibility == View.VISIBLE) shown.getOrNull(current) else (heroTile as? Tile.Ch)?.c
                if (ch != null && !ch.v2) {
                    Epg.invalidate(ch.id)
                    fetchGuide(ch) { g ->
                        if (layer.visibility == View.VISIBLE && shown.getOrNull(current)?.key == ch.key) fillInfoGuide(g)
                        if ((heroTile as? Tile.Ch)?.c?.key == ch.key) applyHeroGuide(g)
                    }
                }
            }
        }
    }

    /** While the app is open, quietly refreshes channel lists and checks the guide feed on its own cadence. */
    private val autoRefresh = object : Runnable {
        override fun run() {
            if (layer.visibility != View.VISIBLE && !searchShown && System.currentTimeMillis() - lastRefresh > 4 * 60_000L) loadChannels()
            TgServers.refreshSite()
            refreshProgrammeFeed()
            ui.postDelayed(this, 5 * 60_000L)
        }
    }
    override fun onStart() {
        super.onStart()
        refreshProgrammeFeed()
        ui.removeCallbacks(autoRefresh); ui.postDelayed(autoRefresh, 5 * 60_000L)
        if (tab >= 2 && ::rowsView.isInitialized && rows.isNotEmpty()) refreshRowsKeepFocus()
        if (System.currentTimeMillis() - lastRefresh > 3 * 60_000L) loadChannels()
    }
    override fun onStop() {
        sourceMenu?.dismiss(); diagnosticsDialog?.dismiss()
        super.onStop(); ui.removeCallbacks(autoRefresh); stopPreview(); window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); endNativeSession(); playToken++; resolveJob?.cancel(true); releasePlayer(); nativeSources = emptyList(); layer.visibility = View.GONE; home.visibility = View.VISIBLE; layer.keepScreenOn = false; resetGuide(); resetProgrammeGuide()
        if (searchShown) hideSearch(false)
    }

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null); resolveJob?.cancel(true); previewJob?.cancel(true)
        sourceMenu?.dismiss(); sourceMenu = null
        diagnosticsDialog?.dismiss(); diagnosticsDialog = null; diagnosticsText = null
        playbackIo.shutdownNow(); io.shutdownNow(); drmProbeIo.shutdownNow()
        super.onDestroy()
    }

    // ---------- search ----------
    private fun buildKeys() {
        val col = findViewById<View>(R.id.searchInputColumn).layoutParams.width
        val m = (2 * dp).toInt()
        val kw = ((col - 12 * m) / 6).coerceAtLeast((26 * dp).toInt())
        val available = resources.displayMetrics.heightPixels - ((if (compact) 126 else 172) * dp).toInt()
        val kh = ((available - 14 * m) / 7).coerceIn((22 * dp).toInt(), (44 * dp).toInt())
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
        text = label; gravity = Gravity.CENTER; textSize = if (compact) 12f else 15f; typeface = fSemi; setTextColor(Accent.DIM)
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
        if (c != null && tab < 2 && !heroCollapsed && layer.visibility != View.VISIBLE && prefs.getBoolean("previews", !lowEnd && !television)) ui.postDelayed(previewRunnable, 1200)
    }
    private fun stopPreview() {
        ui.removeCallbacks(previewRunnable); previewToken++; previewJob?.cancel(true)
        releasePreviewPlayer()
        // A TextureView must remain laid out for the first frame to fade in.
        heroPreview.animate().cancel(); if (heroPreview.alpha > 0f) heroPreview.animate().alpha(0f).setDuration(200).start()
    }
    private fun releasePreviewPlayer() {
        previewStall?.let { ui.removeCallbacks(it) }; previewStall = null
        previewPv.player = null
        val old = previewPlayer; previewPlayer = null
        old?.let { try { it.release() } catch (_: Exception) {} }
    }
    private fun previewIsCurrent(ch: Channel, token: Int) = token == previewToken && layer.visibility != View.VISIBLE &&
        !searchShown && tab < 2 && !heroCollapsed && (heroTile as? Tile.Ch)?.c?.key == ch.key

    private fun startPreview() {
        val ch = (heroTile as? Tile.Ch)?.c ?: return
        if (layer.visibility == View.VISIBLE || searchShown) return
        val token = ++previewToken
        loadPreviewProvider(ch, token, providerOrder(ch), 0)
    }
    private fun loadPreviewProvider(ch: Channel, token: Int, providers: List<LiveProvider>, providerIndex: Int) {
        if (!previewIsCurrent(ch, token)) return
        releasePreviewPlayer()
        val provider = providers.getOrNull(providerIndex) ?: run { stopPreview(); return }
        previewJob = io.submit {
            val sources = try { LiveMetadataRequests.fetch(provider, ch.id, v2 = ch.v2) } catch (_: Exception) { emptyList() }
            runOnUiThread {
                if (!previewIsCurrent(ch, token)) return@runOnUiThread
                // Put a previously rendered source first without removing the other native candidates.
                val saved = prefs.getString("source_${ch.key}", null)
                val ordered = sources.sortedBy { if (sourceKey(it) == saved) 0 else 1 }
                playPreview(ordered, 0, ch, token, providers, providerIndex)
            }
        }
    }
    private fun playPreview(sources: List<TgStream>, index: Int, ch: Channel, token: Int,
                            providers: List<LiveProvider>, providerIndex: Int) {
        if (!previewIsCurrent(ch, token)) return
        releasePreviewPlayer()
        val source = sources.getOrNull(index) ?: run {
            loadPreviewProvider(ch, token, providers, providerIndex + 1); return
        }
        var advancing = false
        fun next() {
            if (advancing || !previewIsCurrent(ch, token)) return
            advancing = true
            ui.post { if (previewIsCurrent(ch, token)) playPreview(sources, index + 1, ch, token, providers, providerIndex) }
        }
        try {
            val profile = NativePlaybackPolicy.profile(television, lowEnd, savedCompatibility(ch, LiveProviders.provider(source)), 720)
            val p = ExoPlayer.Builder(this, NativePlaybackPolicy.renderers(this, profile))
                .setLoadControl(NativePlaybackPolicy.buffers(profile))
                .setTrackSelector(NativePlaybackPolicy.tracks(this, profile, ch.name.contains("Hindi", true))).build()
            p.volume = 0f
            previewPlayer = p
            val health = PlaybackHealth(SystemClock.elapsedRealtime(),
                expectsVideo = !ch.category.equals("Radio", true), firstFrameTimeoutMs = 12_000)
            val monitor = object : Runnable {
                override fun run() {
                    if (previewPlayer !== p || !previewIsCurrent(ch, token)) return
                    val state = when (p.playbackState) {
                        Player.STATE_READY -> PlaybackHealth.State.READY
                        Player.STATE_BUFFERING -> PlaybackHealth.State.BUFFERING
                        Player.STATE_ENDED -> PlaybackHealth.State.ENDED
                        else -> PlaybackHealth.State.IDLE
                    }
                    if (health.sample(SystemClock.elapsedRealtime(), state, p.isPlaying, p.currentPosition, p.playWhenReady).failure != null) next()
                    else ui.postDelayed(this, 500)
                }
            }
            p.setVideoFrameMetadataListener { _, _, _, _ -> health.videoProgress(SystemClock.elapsedRealtime()) }
            p.setMediaSource(nativeMediaSource(source))
            p.addListener(object : Player.Listener {
                override fun onRenderedFirstFrame() {
                    if (previewPlayer !== p || !previewIsCurrent(ch, token)) return
                    health.firstFrame(SystemClock.elapsedRealtime())
                    rememberNativeSource(ch, source)
                    heroPreview.animate().cancel(); heroPreview.animate().alpha(1f).setDuration(450).start()
                }
                override fun onPlaybackStateChanged(state: Int) {
                    if (previewPlayer !== p || !previewIsCurrent(ch, token)) return
                    if (state == Player.STATE_ENDED) next()
                }
                override fun onPlayerError(error: PlaybackException) { if (previewPlayer === p) next() }
            })
            previewPv.player = p; p.playWhenReady = true; p.prepare()
            previewStall = monitor; ui.postDelayed(monitor, 500)
        } catch (_: Exception) { releasePreviewPlayer(); next() }
    }

    /** Rebuilds the rows (e.g. a new "Recently watched" entry) without losing the scroll position. */
    private fun refreshRowsKeepFocus(requestFocus: Boolean = true) {
        val lm = rowsView.layoutManager as LinearLayoutManager
        val pos = lm.findFirstVisibleItemPosition()
        val top = lm.findViewByPosition(pos)?.top ?: 0
        val old = rows.map { it.title }
        rows = when (tab) {
            0 -> liveRows(all)
            1 -> liveRows(allV2, alwaysOn = true)
            else -> continueRow() + vodRows[tab - 2]
        }
        inner.clear(); rowAdapter.notifyDataSetChanged()
        val shift = rows.size - old.size
        if (pos >= 0) lm.scrollToPositionWithOffset((pos + if (pos > 0) shift else 0).coerceAtLeast(0), top)
        if (requestFocus) rowsView.postDelayed({ if (layer.visibility != View.VISIBLE && !searchShown && currentFocus == null) rowsView.requestFocus() }, 80)
    }

    // ---------- adapters ----------
    private inner class TileAdapter(val row: Row) : RecyclerView.Adapter<VH>() {
        override fun getItemCount() = row.tiles.size
        override fun getItemViewType(i: Int) = when {
            row.landscape && row.tiles[i] is Tile.Mv -> 3
            row.tiles[i] is Tile.Ch -> 0
            row.tiles[i] is Tile.Mv -> 1
            else -> 2
        }
        override fun onCreateViewHolder(p: ViewGroup, t: Int): VH {
            val l = when (t) { 0 -> R.layout.item_tile; 1 -> R.layout.item_poster; 3 -> R.layout.item_trending; else -> R.layout.item_hint }
            val v = LayoutInflater.from(p.context).inflate(l, p, false)
            HomeChrome.styleTile(v, poster = t == 1, landscape = t == 3, compact = compact, dp = dp)
            if (t != 1 && t != 3) { v.background = Ui.pill(dp, Accent.SURFACE, 14f); v.clipToOutline = true }
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
                    v.tag = t.c.key; bg.setColor(Ui.mix(Accent.SURFACE, accentOf(t.c.key), 0.12f))
                    v.findViewById<TextView>(R.id.name).text = t.c.name
                    val nowCaption = v.findViewById<TextView>(R.id.nowCaption)
                    nowCaption.tag = t.c.key
                    nowCaption.text = if (t.c.v2) "Always-on · no schedule feed" else Epg.cached(t.c.id)?.now?.title ?: "Programme guide on focus"
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
                                    if (v.tag == t.c.key) bg.setColor(Ui.mix(Accent.SURFACE, c, 0.12f))
                                }
                            })
                        }
                    }
                    v.findViewById<View>(R.id.fav).visibility = if (t.c.key in favs) View.VISIBLE else View.GONE
                    v.setOnFocusChangeListener { x, f ->
                        focusAnim(x, f)
                        if (f) {
                            setHero(t)
                            fetchGuide(t.c) { guide ->
                                if (nowCaption.tag == t.c.key) nowCaption.text = if (t.c.v2) "Always-on · no schedule feed" else guide?.now?.title ?: "Programme information unavailable"
                            }
                        }
                    }
                    v.setOnClickListener {
                        shown = row.tiles.filterIsInstance<Tile.Ch>().map { it.c }; shownCat = row.title
                        play(shown.indexOfFirst { it.key == t.c.key })
                    }
                    v.setOnLongClickListener { toggleFav(t.c); true }
                }
                is Tile.Mv -> {
                    if (row.landscape) {
                        v.findViewById<ImageView>(R.id.trendingImage).load(t.m.background.ifEmpty { t.m.poster }) {
                            crossfade(180); size(660, 426); placeholder(null)
                        }
                        v.findViewById<TextView>(R.id.trendingTitle).text = t.m.name
                        v.findViewById<TextView>(R.id.trendingMeta).text =
                            (listOf(t.m.year.takeIf { it.isNotEmpty() }, t.m.rating.takeIf { it.isNotEmpty() }?.let { "★ $it" })
                                .filterNotNull()).joinToString("  ·  ")
                        v.findViewById<TextView>(R.id.trendingRank).text = "${(i + 1).toString().padStart(2, '0')}  TRENDING"
                    } else {
                        val img = v.findViewById<ImageView>(R.id.poster)
                        v.findViewById<TextView>(R.id.posterName).text = t.m.name
                        img.load(t.m.poster) { crossfade(180); size(348, 522); placeholder(null) }
                        v.findViewById<TextView>(R.id.posterTitle).text = t.m.name
                        v.findViewById<TextView>(R.id.posterMeta).text =
                            (listOf(t.m.year.takeIf { it.isNotEmpty() }, t.m.rating.takeIf { it.isNotEmpty() }?.let { "★ $it" })
                                .filterNotNull()).joinToString("  ·  ")
                        v.findViewById<TextView>(R.id.posterRank).apply {
                            visibility = if (i < 10 && row.title.startsWith("Trending", true)) View.VISIBLE else View.GONE
                            text = (i + 1).toString()
                        }
                    }
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
                else -> {
                    v.findViewById<TextView>(R.id.hintText).text = when {
                        row.title.equals("Trending on AK", true) && trendingFailed -> "Trending titles will appear when the catalogue is available."
                        row.title.equals("Trending on AK", true) -> "Loading titles from the native catalogue…"
                        else -> "Hold OK on any channel to keep it here"
                    }
                }
            }
        }
    }

    private val tilePool = RecyclerView.RecycledViewPool().apply { setMaxRecycledViews(0, 40); setMaxRecycledViews(1, 40); setMaxRecycledViews(3, 24) }
    private val rowAdapter = object : RecyclerView.Adapter<VH>() {
        override fun getItemCount() = rows.size
        override fun onCreateViewHolder(p: ViewGroup, t: Int) =
            VH(LayoutInflater.from(p.context).inflate(R.layout.item_row, p, false))
        override fun onBindViewHolder(h: VH, i: Int) {
            val v = h.itemView
            HomeChrome.styleRow(v, compact, dp)
            val row = rows[i]
            val title = v.findViewById<TextView>(R.id.rowTitle); val count = v.findViewById<TextView>(R.id.rowCount)
            title.text = row.title
            val n = row.tiles.count { it is Tile.Ch || it is Tile.Mv }
            count.text = if (n > 0) n.toString() else ""
            count.visibility = if (n > 0) View.VISIBLE else View.GONE
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
