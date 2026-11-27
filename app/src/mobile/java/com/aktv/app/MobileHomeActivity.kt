package com.aktv.app

import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.util.concurrent.Executors

/**
 * STREAMR Mobile home: a liquid-glass storefront over the same accounts and resolvers as the TV
 * shelf. Home is a Netflix-style front page (hero, continue watching, trending, Top 10, platform
 * and genre rails); Live lazily loads each provider only when asked; Movies and Series are
 * separate tabs with their own shelves, search and downloads; Saved lists offline files.
 */
class MobileHomeActivity : AppCompatActivity() {
    private val io = Executors.newFixedThreadPool(4) { r -> Thread(r, "aktv-mobile").apply { isDaemon = true } }
    private val ui = Handler(Looper.getMainLooper())
    private val image = MobileImage()
    private val prefs by lazy { getSharedPreferences("aktv_mobile", Context.MODE_PRIVATE) }
    private var body: ScrollView? = null
    private var tabRow: LinearLayout? = null
    private var tab = 0
    private var tvs: List<CastSender.Tv> = emptyList()
    private var discovery: CastSender.Discovery? = null

    private data class LiveItem(val provider: String, val id: String, val name: String, val logo: String, val category: String, val v2: Boolean = false, val cmd: String = "")

    // Home front-page state.
    private var heroTitles: List<Movies.Title> = emptyList()
    private var heroIndex = 0
    private var heroFrame: FrameLayout? = null
    private var homeBox: LinearLayout? = null
    private var homeRails: List<Pair<String, List<Movies.Title>>> = emptyList()
    private var homeStarted = false
    private val heroRotate = object : Runnable {
        override fun run() {
            ui.removeCallbacks(this)
            if (tab == 0 && heroTitles.size > 1 && !isFinishing) {
                heroIndex = (heroIndex + 1) % heroTitles.size
                heroFrame?.let { fillHero(it) }
                ui.postDelayed(this, 8_000)
            }
        }
    }

    // Live state: each provider loads once, on demand.
    private var providerTabs = listOf("All", "TG", "Jio", "TATA", "Portal", "Portal 2", "Xtream")
    private val liveCache = HashMap<String, List<LiveItem>>()
    private val liveLoading = HashSet<String>()
    private val liveErrors = HashMap<String, String>()
    private var liveQuery = ""
    private var liveCategory = "All"

    // Movies / Series state.
    private val shelfCache = HashMap<String, List<Movies.Title>>()
    private var movieQuery = ""
    private var movieResults: List<Movies.Title> = emptyList()
    private var movieSearching = false
    private var movieGenre = "All"
    private var seriesQuery = ""
    private var seriesResults: List<Movies.Title> = emptyList()
    private var seriesSearching = false
    private var seriesGenre = "All"
    private val searchHandler = Handler(Looper.getMainLooper())
    private var searchBox: LinearLayout? = null
    private val searchRun = Runnable { runCatalogSearch() }

    private val downloadReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (tab == 4) showTab()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Glass.density = resources.displayMetrics.density
        MobileImage.init(this)
        runCatching { TgServers.init(this) }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Glass.ground()
        }
        // The real STREAMR wordmark (with the "by Akshansh Sahu" credit), not plain text.
        val header = android.widget.ImageView(this).apply {
            setImageResource(R.drawable.streamr_logo_full)
            scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            adjustViewBounds = true
            contentDescription = "STREAMR"
            setPadding(dp(48), dp(14), dp(48), dp(6))
        }
        root.addView(header, LinearLayout.LayoutParams(-1, dp(68)))
        val body = ScrollView(this).apply { isFillViewport = true }
        this.body = body
        root.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
        val tabShell = FrameLayout(this).apply {
            setPadding(dp(12), dp(4), dp(12), dp(12))
            background = null
        }
        val tabRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(4), dp(6), dp(4), dp(6))
        }
        this.tabRow = tabRow
        tabShell.addView(Glass.frostPanel(tabRow, 26, 0x2E), FrameLayout.LayoutParams(-1, -2))
        root.addView(tabShell, LinearLayout.LayoutParams(-1, -2))
        // Living STREAMR aurora behind the whole mobile home; the glass cards sit over it.
        root.background = null
        setContentView(FrameLayout(this).apply {
            background = Glass.ground()
            addView(AuroraView(this@MobileHomeActivity).apply { intensity = 0.45f; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO },
                FrameLayout.LayoutParams(-1, -1))
            addView(root, FrameLayout.LayoutParams(-1, -1))
        })
        buildTabs()
        showTab()
        runCatching {
            val filter = IntentFilter(android.app.DownloadManager.ACTION_DOWNLOAD_COMPLETE)
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(downloadReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            else @Suppress("UnspecifiedRegisterReceiverFlags") registerReceiver(downloadReceiver, filter)
        }
        discovery = CastSender.Discovery(this) { found -> tvs = found }.also { it.start() }
    }

    override fun onResume() {
        super.onResume()
        ui.removeCallbacks(heroRotate)
        if (tab == 0 && heroTitles.size > 1) ui.postDelayed(heroRotate, 8_000)
        if (tab == 4) showTab()
    }

    override fun onPause() {
        ui.removeCallbacks(heroRotate)
        super.onPause()
    }

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        runCatching { unregisterReceiver(downloadReceiver) }
        discovery?.stop()
        io.shutdownNow()
        super.onDestroy()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    // ---------- Tabs ----------

    private fun buildTabs() {
        val names = listOf("⌂\nHome", "◉\nLive", "🎬\nMovies", "📺\nSeries", "⬇\nSaved")
        val row = tabRow ?: return
        row.removeAllViews()
        names.forEachIndexed { i, name ->
            val selected = i == tab
            val button = Button(this, null, android.R.attr.borderlessButtonStyle).apply {
                text = name
                textSize = 11f
                setLineSpacing(0f, 0.9f)
                gravity = Gravity.CENTER
                setTextColor(Color.parseColor(if (selected) Glass.ACCENT else Glass.SUB))
                typeface = if (selected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                background = if (selected) Glass.card(20, 0x30) else null
                stateListAnimator = android.animation.AnimatorInflater.loadStateListAnimator(context, R.animator.glass_lift)
            }
            button.setOnClickListener { if (tab != i) { tab = i; buildTabs(); showTab() } }
            row.addView(button, LinearLayout.LayoutParams(0, -2, 1f))
        }
    }

    private fun showTab() {
        ui.removeCallbacks(heroRotate)
        val body = body ?: return
        body.removeAllViews()
        heroFrame = null
        homeBox = null
        when (tab) {
            0 -> showHome(body)
            1 -> showLive(body)
            2 -> showCatalog(body, movies = true)
            3 -> showCatalog(body, movies = false)
            4 -> showSaved(body)
        }
    }

    // ---------- Shared widgets ----------

    private fun section(title: String): TextView = TextView(this).apply {
        text = title
        setTextColor(Color.parseColor(Glass.TEXT))
        textSize = 18f
        typeface = Typeface.DEFAULT_BOLD
        setPadding(dp(16), dp(16), dp(16), dp(8))
    }

    private fun hint(text: String): TextView = TextView(this).apply {
        this.text = text
        setTextColor(Color.parseColor(Glass.SUB))
        textSize = 14f
        setPadding(dp(16), dp(8), dp(16), dp(8))
    }

    private fun pillButton(text: String, primary: Boolean = false, onTap: () -> Unit): Button =
        Button(this, null, android.R.attr.borderlessButtonStyle).apply {
            this.text = text
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(if (primary) Color.WHITE else Color.parseColor(Glass.ACCENT))
            background = if (primary) Glass.accentPill() else Glass.card(24, 0x2E)
            stateListAnimator = android.animation.AnimatorInflater.loadStateListAnimator(context, R.animator.glass_lift)
            setPadding(dp(20), dp(10), dp(20), dp(10))
            setOnClickListener { onTap() }
        }

    private fun chip(text: String, selected: Boolean, onTap: () -> Unit): Button =
        Button(this, null, android.R.attr.borderlessButtonStyle).apply {
            this.text = text
            textSize = 13f
            typeface = if (selected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            setTextColor(Color.parseColor(if (selected) "#FFFFFF" else Glass.TEXT))
            background = if (selected) Glass.accentPill() else Glass.card(24, 0x22)
            stateListAnimator = android.animation.AnimatorInflater.loadStateListAnimator(context, R.animator.glass_lift)
            setPadding(dp(16), dp(8), dp(16), dp(8))
            setOnClickListener { onTap() }
        }

    private fun searchField(hintText: String, initial: String, onQuery: (String) -> Unit): EditText =
        EditText(this).apply {
            hint = hintText
            setHintTextColor(Color.parseColor("#5E5A6B"))
            setTextColor(Color.parseColor(Glass.TEXT))
            textSize = 15f
            setText(initial)
            background = Glass.card(24, 0x22)
            setPadding(dp(18), dp(12), dp(18), dp(12))
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply {
                leftMargin = dp(16); rightMargin = dp(16); topMargin = dp(4)
            }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                override fun afterTextChanged(s: Editable?) = onQuery(s?.toString().orEmpty())
            })
        }

    private class PosterVH(val card: View, val art: ImageView, val name: TextView) : RecyclerView.ViewHolder(card)

    private fun rail(titles: List<Movies.Title>, onTap: (Movies.Title) -> Unit): RecyclerView {
        val list = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@MobileHomeActivity, RecyclerView.HORIZONTAL, false)
            layoutParams = ViewGroup.LayoutParams(-1, dp(196))
        }
        list.adapter = object : RecyclerView.Adapter<PosterVH>() {
            override fun onCreateViewHolder(p: ViewGroup, t: Int): PosterVH {
                val card = LinearLayout(this@MobileHomeActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(5), dp(2), dp(5), dp(2))
                }
                val art = ImageView(this@MobileHomeActivity).apply {
                    layoutParams = ViewGroup.LayoutParams(dp(112), dp(158))
                    scaleType = ImageView.ScaleType.CENTER_CROP
                }
                Glass.round(art, 12)
                val name = TextView(this@MobileHomeActivity).apply {
                    setTextColor(Color.parseColor(Glass.TEXT)); textSize = 11f
                    maxLines = 1
                    setPadding(0, dp(4), 0, 0)
                    layoutParams = ViewGroup.LayoutParams(dp(112), -2)
                }
                card.addView(art); card.addView(name)
                return PosterVH(card, art, name)
            }
            override fun getItemCount() = titles.size
            override fun onBindViewHolder(h: PosterVH, position: Int) {
                val title = titles[position]
                h.name.text = title.name
                image.load(title.poster, h.art)
                h.card.setOnClickListener { onTap(title) }
            }
        }
        return list
    }

    // ---------- Home front page ----------

    private fun showHome(body: ScrollView) {
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        body.addView(column)
        val frame = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(-1, dp(430)).apply {
                leftMargin = dp(12); rightMargin = dp(12); topMargin = dp(4)
            }
        }
        Glass.round(frame, 22)
        heroFrame = frame
        column.addView(frame)
        if (heroTitles.isNotEmpty()) {
            fillHero(frame)
            ui.postDelayed(heroRotate, 8_000)
        } else {
            frame.addView(ProgressBar(this).apply {
                isIndeterminate = true
                layoutParams = FrameLayout.LayoutParams(-2, -2, Gravity.CENTER)
            })
        }
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        homeBox = box
        column.addView(box)
        homeRails.forEach { (title, titles) ->
            box.addView(section(title))
            box.addView(if (title.startsWith("Top 10")) top10Rail(titles) else rail(titles) { openDetail(it) })
        }
        if (!homeStarted) {
            homeStarted = true
            startHomeLoads()
        }
    }

    private fun fillHero(frame: FrameLayout) {
        val title = heroTitles.getOrNull(heroIndex) ?: return
        frame.removeAllViews()
        val art = ImageView(this).apply {
            layoutParams = FrameLayout.LayoutParams(-1, -1)
            scaleType = ImageView.ScaleType.CENTER_CROP
        }
        frame.addView(art)
        image.load(title.background.ifBlank { title.poster }, art)
        frame.addView(View(this).apply {
            background = Glass.scrim()
            layoutParams = FrameLayout.LayoutParams(-1, -1)
        })
        val info = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        info.addView(TextView(this).apply {
            text = title.name
            setTextColor(Color.parseColor(Glass.TEXT)); textSize = 22f
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 2
        })
        val meta = listOf(title.year, title.rating, title.genres.take(3).joinToString(" · "))
            .filter { it.isNotBlank() }.joinToString(" · ")
        if (meta.isNotBlank()) info.addView(TextView(this).apply {
            text = meta
            setTextColor(Color.parseColor(Glass.SUB)); textSize = 13f
            setPadding(0, dp(2), 0, dp(8))
        })
        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val play = pillButton("▶ Play", primary = true) { playTitle(title, 0, 0) }
        play.layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = dp(8) }
        val save = pillButton("⬇") { downloadTitle(title, 0, 0) }
        val tv = pillButton("📺") { castTitle(title, 0, 0) }
        val more = pillButton("ⓘ") { openDetail(title) }
        buttons.addView(play); buttons.addView(save); buttons.addView(tv); buttons.addView(more)
        info.addView(buttons)
        frame.addView(Glass.frostPanel(info, 20, 0x30), FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply {
            leftMargin = dp(12); rightMargin = dp(12); bottomMargin = dp(12)
        })
        if (heroTitles.size > 1) {
            val dots = TextView(this).apply {
                text = heroTitles.indices.joinToString(" ") { if (it == heroIndex) "●" else "○" }
                setTextColor(Color.parseColor(Glass.ACCENT)); textSize = 9f
                gravity = Gravity.CENTER
                layoutParams = FrameLayout.LayoutParams(-1, -2, Gravity.TOP).apply { topMargin = dp(8) }
            }
            frame.addView(dots)
        }
    }

    private fun top10Rail(titles: List<Movies.Title>): RecyclerView {
        val top = titles.take(10)
        val list = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@MobileHomeActivity, RecyclerView.HORIZONTAL, false)
            layoutParams = ViewGroup.LayoutParams(-1, dp(190))
        }
        list.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun onCreateViewHolder(p: ViewGroup, t: Int): RecyclerView.ViewHolder {
                val row = LinearLayout(this@MobileHomeActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.BOTTOM
                    setPadding(dp(4), dp(2), dp(10), dp(2))
                }
                val rank = TextView(this@MobileHomeActivity).apply {
                    setTextColor(Color.argb(0x66, 0xFF, 0xFF, 0xFF)); textSize = 76f
                    typeface = Typeface.DEFAULT_BOLD
                    setPadding(0, 0, dp(-14), dp(-8))
                }
                val art = ImageView(this@MobileHomeActivity).apply {
                    layoutParams = ViewGroup.LayoutParams(dp(104), dp(150))
                    scaleType = ImageView.ScaleType.CENTER_CROP
                }
                Glass.round(art, 12)
                row.addView(rank); row.addView(art)
                return object : RecyclerView.ViewHolder(row) {}
            }
            override fun getItemCount() = top.size
            override fun onBindViewHolder(h: RecyclerView.ViewHolder, position: Int) {
                val row = h.itemView as LinearLayout
                (row.getChildAt(0) as TextView).text = "${position + 1}"
                val art = row.getChildAt(1) as ImageView
                image.load(top[position].poster, art)
                row.setOnClickListener { openDetail(top[position]) }
            }
        }
        return list
    }

    private fun startHomeLoads() {
        // Continue watching first: local, instant.
        val recent = Movies.recent(prefs)
        if (recent.isNotEmpty()) addHomeRail("Continue watching", recent)
        // Trending movies feed the hero, the Top 10 and the first rail.
        io.submit {
            val shelf = Movies.MOVIE_SHELVES.firstOrNull()
            val trending = runCatching { if (shelf != null) Movies.shelf(shelf) else emptyList() }.getOrElse { emptyList() }
            runOnUiThread {
                if (trending.isNotEmpty() && heroTitles.isEmpty()) {
                    heroTitles = trending.filter { it.background.isNotBlank() }.take(5).ifEmpty { trending.take(5) }
                    heroFrame?.let { fillHero(it) }
                    if (tab == 0) {
                        ui.removeCallbacks(heroRotate)
                        if (heroTitles.size > 1) ui.postDelayed(heroRotate, 8_000)
                    }
                }
                if (trending.isNotEmpty()) addHomeRail("Top 10 movies", trending)
                // A dead hero never sits on its spinner: say so, with a retry.
                if (trending.isEmpty() && heroTitles.isEmpty()) heroFrame?.let { frame ->
                    frame.removeAllViews()
                    val retry = pillButton("Couldn't load home — tap to retry") {
                        homeStarted = false
                        if (tab == 0) showTab()
                    }
                    frame.addView(retry, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
                }
            }
        }
        // Every other rail loads on its own thread and lands when it lands.
        val specs = mutableListOf<Pair<String, () -> List<Movies.Title>>>()
        Movies.SERIES_SHELVES.firstOrNull()?.let { shelf ->
            specs += "Trending series" to { runCatching { Movies.shelf(shelf) }.getOrElse { emptyList() } }
        }
        Movies.PLATFORM_SHELVES.filter {
            it.title.startsWith("Netflix") || it.title.startsWith("Prime Video") ||
                it.title.startsWith("Disney") || it.title.startsWith("Apple TV") ||
                it.title.startsWith("Indian Cinema") || it.title.startsWith("Korean Dramas")
        }.forEach { shelf ->
            specs += shelf.title to { runCatching { Movies.shelf(shelf) }.getOrElse { emptyList() } }
        }
        Movies.MOVIE_SHELVES.filter { it.genre in listOf("Action", "Comedy", "Thriller") }.forEach { shelf ->
            specs += shelf.title to { runCatching { Movies.shelf(shelf) }.getOrElse { emptyList() } }
        }
        Movies.SERIES_SHELVES.filter { it.genre in listOf("Drama", "Crime") }.forEach { shelf ->
            specs += shelf.title to { runCatching { Movies.shelf(shelf) }.getOrElse { emptyList() } }
        }
        specs.forEach { (title, load) ->
            io.submit {
                val titles = load()
                if (titles.isNotEmpty()) runOnUiThread { addHomeRail(title, titles) }
            }
        }
    }

    private fun addHomeRail(title: String, titles: List<Movies.Title>) {
        if (homeRails.any { it.first == title }) return
        homeRails = homeRails + (title to titles)
        if (tab != 0) return
        val box = homeBox ?: return
        // Continue watching always rides first.
        if (title == "Continue watching" && box.childCount > 0) {
            box.addView(rail(titles) { openDetail(it) }, 1)
            box.addView(section(title), 0)
        } else {
            box.addView(section(title))
            box.addView(if (title.startsWith("Top 10")) top10Rail(titles) else rail(titles) { openDetail(it) })
        }
    }

    // ---------- Live: each provider loads lazily ----------

    private fun providerPicked(): Int = prefs.getInt("live_provider", 0).coerceIn(0, providerTabs.size - 1)

    private fun showLive(body: ScrollView) {
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        body.addView(column)
        column.addView(section("Live TV"))
        val listBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        column.addView(searchField("Search channels…", liveQuery) { query ->
            liveQuery = query
            fillLiveList(listBox)
        })
        val picked = providerPicked()
        val chips = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@MobileHomeActivity, RecyclerView.HORIZONTAL, false)
            layoutParams = ViewGroup.LayoutParams(-1, dp(52)).apply {}
            setPadding(dp(10), dp(6), dp(10), 0)
        }
        chips.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun onCreateViewHolder(p: ViewGroup, t: Int): RecyclerView.ViewHolder {
                val shell = FrameLayout(this@MobileHomeActivity).apply {
                    setPadding(dp(4), 0, dp(4), 0)
                }
                return object : RecyclerView.ViewHolder(shell) {}
            }
            override fun getItemCount() = providerTabs.size
            override fun onBindViewHolder(h: RecyclerView.ViewHolder, position: Int) {
                val shell = h.itemView as FrameLayout
                shell.removeAllViews()
                val loading = position != 0 && liveCache[providerTabs[position]] == null
                shell.addView(chip(
                    providerTabs[position] + if (loading) " ⟳" else "",
                    position == picked
                ) {
                    prefs.edit().putInt("live_provider", position).apply()
                    liveCategory = "All"
                    ensureLiveLoaded(providerTabs[position])
                    showTab()
                })
            }
        }
        column.addView(chips)
        column.addView(listBox)
        ensureLiveLoaded(providerTabs[picked])
        fillLiveList(listBox)
    }

    /** The selected provider loads in the background; "All" trickles every provider in. */
    private fun ensureLiveLoaded(provider: String) {
        val wanted = if (provider == "All") providerTabs.drop(1) else listOf(provider)
        wanted.forEach { name ->
            if (liveCache[name] == null && liveLoading.add(name)) {
                io.submit {
                    try {
                        val items = loadProvider(name)
                        if (items.isEmpty()) throw java.io.IOException("empty $name list")
                        liveCache[name] = items
                        liveErrors.remove(name)
                    } catch (error: Exception) {
                        liveErrors[name] = MobileErrors.why(error)
                    }
                    liveLoading.remove(name)
                    runOnUiThread { if (tab == 1) showTab() }
                }
            }
        }
    }

    private fun currentLive(provider: String): List<LiveItem> =
        if (provider == "All") providerTabs.drop(1).flatMap { liveCache[it].orEmpty() }
        else liveCache[provider].orEmpty()

    private fun fillLiveList(listBox: LinearLayout) {
        listBox.removeAllViews()
        val provider = providerTabs[providerPicked()]
        val items = currentLive(provider)
        if (items.isEmpty()) {
            val stillLoading = if (provider == "All") liveLoading.isNotEmpty() else liveLoading.contains(provider)
            if (stillLoading) {
                listBox.addView(ProgressBar(this).apply { isIndeterminate = true })
                listBox.addView(hint("Loading $provider channels…"))
            } else {
                val reason = if (provider == "All") liveErrors.values.firstOrNull()
                    else liveErrors[provider]
                listBox.addView(hint("No $provider channels" +
                    (if (reason != null) " ($reason)." else " — check the connection,") + " then retry."))
                listBox.addView(pillButton("↻ Retry") {
                    if (provider == "All") providerTabs.drop(1).forEach { liveCache.remove(it); liveErrors.remove(it) }
                    else { liveCache.remove(provider); liveErrors.remove(provider) }
                    ensureLiveLoaded(provider)
                    showTab()
                }.apply {
                    layoutParams = LinearLayout.LayoutParams(-2, -2).apply {
                        leftMargin = dp(16); topMargin = dp(4)
                    }
                })
            }
            return
        }
        // Category chips ride above the rows.
        val categories = listOf("All") + items.map { it.category.ifBlank { "Other" } }.distinct().sorted().take(30)
        if (liveCategory !in categories) liveCategory = "All"
        val cats = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@MobileHomeActivity, RecyclerView.HORIZONTAL, false)
            layoutParams = ViewGroup.LayoutParams(-1, dp(50))
            setPadding(dp(10), dp(4), dp(10), 0)
        }
        cats.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun onCreateViewHolder(p: ViewGroup, t: Int): RecyclerView.ViewHolder {
                val shell = FrameLayout(this@MobileHomeActivity).apply { setPadding(dp(4), 0, dp(4), 0) }
                return object : RecyclerView.ViewHolder(shell) {}
            }
            override fun getItemCount() = categories.size
            override fun onBindViewHolder(h: RecyclerView.ViewHolder, position: Int) {
                val shell = h.itemView as FrameLayout
                shell.removeAllViews()
                shell.addView(chip(categories[position], categories[position] == liveCategory) {
                    liveCategory = categories[position]
                    fillLiveList(listBox)
                })
            }
        }
        listBox.addView(cats)
        val filtered = items.filter {
            (liveCategory == "All" || it.category.ifBlank { "Other" } == liveCategory) &&
                (liveQuery.isBlank() || it.name.contains(liveQuery, true))
        }
        val pending = if (provider == "All" && liveLoading.isNotEmpty()) " · still loading ${liveLoading.sorted().joinToString(", ")}…" else ""
        listBox.addView(hint("${filtered.size} channels$pending"))
        if (filtered.isEmpty()) {
            listBox.addView(hint("Nothing matches."))
            return
        }
        filtered.take(400).forEach { item -> listBox.addView(liveRow(item)) }
        if (filtered.size > 400) listBox.addView(hint("…showing the first 400 of ${filtered.size}. Search to narrow."))
    }

    private fun liveRow(item: LiveItem): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = Glass.card(16, 0x1E)
            setPadding(dp(10), dp(8), dp(6), dp(8))
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply {
                leftMargin = dp(12); rightMargin = dp(12); topMargin = dp(4); bottomMargin = dp(4)
            }
            isClickable = true
            isFocusable = true
        }
        val logo = ImageView(this).apply {
            layoutParams = ViewGroup.LayoutParams(dp(52), dp(52))
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        Glass.round(logo, 12)
        image.load(item.logo, logo)
        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(10) }
        }
        texts.addView(TextView(this).apply {
            text = item.name
            setTextColor(Color.parseColor(Glass.TEXT))
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 1
        })
        val meta = listOf(item.provider, item.category).filter { it.isNotBlank() }.joinToString(" · ")
        texts.addView(TextView(this).apply {
            text = meta
            setTextColor(Color.parseColor(Glass.SUB))
            textSize = 12f
            maxLines = 1
        })
        val play = pillButton("▶") { playLive(item) }
        val tv = pillButton("📺") { castLive(item) }
        row.addView(logo); row.addView(texts); row.addView(play); row.addView(tv)
        row.setOnClickListener { playLive(item) }
        row.setOnLongClickListener { castLive(item); true }
        return row
    }

    private fun loadProvider(name: String): List<LiveItem> {
        return when (name) {
            "TG" -> buildList {
                MobileCatalog.parseV1(tgGet("${TgServers.SITE}/api/public/channels", "mobile-v1.json"))
                    .forEach { add(LiveItem("TG", it.id, it.name, it.logo, it.category)) }
                MobileCatalog.parseV2(tgGet("${TgServers.SITE}/api/public/v2/channels", "mobile-v2.json"))
                    .forEach { add(LiveItem("TG", it.id, it.name, it.logo, it.category, v2 = true)) }
            }
            "Jio" -> run {
                val cached = runCatching { TgServers.cachedJioCatalogue() }.getOrElse { emptyList() }
                val entries = if (cached.isNotEmpty()) cached else TgServers.jioCatalogue()
                entries.filter { PortalLanguages.keepsJio(it) }.map {
                    LiveItem("Jio", it.id, it.name, it.logo, it.category.ifBlank { "Other" })
                }
            }
            "TATA" -> TgServers.tataCatalogue().map { LiveItem("TATA", it.id, it.name, it.logo, it.category) }
            "Portal" -> stalkerItems("Portal", StalkerAccount.DEFAULT, "portal_endpoint")
            "Portal 2" -> stalkerItems("Portal 2", StalkerAccount(
                StalkerAccount.FUSION_DEFAULT_PORTAL, StalkerAccount.FUSION_DEFAULT_MAC,
                StalkerAccount.FUSION_DEFAULT_SERIAL, ""), "portal2_endpoint")
            "Xtream" -> {
                val account = XtreamAccount.INBUILT
                if (!account.isConfigured) emptyList()
                else XtreamClient.fetchLiveCatalogue(account).map {
                    LiveItem("Xtream", it.streamId, it.name, it.logo, it.categoryName)
                }
            }
            else -> emptyList()
        }
    }

    /** The TV shelf's portal flow: disk cache first, network with the remembered endpoint after. */
    private fun stalkerItems(tag: String, account: StalkerAccount, endpointKey: String): List<LiveItem> {
        val dir = java.io.File(filesDir, "portal")
        val (cachedList, cachedGenres) = StalkerProtocol.loadCache(dir, account.cacheKey)
        if (cachedList.isNotEmpty()) {
            return cachedList.map { c ->
                LiveItem(tag, c.id, c.name,
                    if (c.logo.startsWith("/")) account.origin + c.logo else c.logo,
                    StalkerProtocol.categoryFor(cachedGenres, c.genre, c.name), cmd = c.cmd)
            }
        }
        val portal = StalkerPortal(account, knownEndpoint = prefs.getString(endpointKey, null))
        val listed = portal.channels()
        portal.workingEndpoint?.let { endpoint ->
            if (prefs.getString(endpointKey, null) != endpoint) prefs.edit().putString(endpointKey, endpoint).apply()
        }
        val genres = runCatching { portal.genres() }.getOrElse { emptyMap() }
        if (listed.isNotEmpty()) StalkerProtocol.saveCache(dir, account.cacheKey, listed, genres)
        return listed.map { c ->
            LiveItem(tag, c.id, c.name,
                if (c.logo.startsWith("/")) account.origin + c.logo else c.logo,
                StalkerProtocol.categoryFor(genres, c.genre, c.name), cmd = c.cmd)
        }
    }

    private fun playLive(item: LiveItem) {
        Toast.makeText(this, "Loading ${item.name}…", Toast.LENGTH_SHORT).show()
        io.submit {
            val command = liveCommand(item)
            runOnUiThread {
                if (command == null) {
                    Toast.makeText(this, "Couldn't load ${item.name}", Toast.LENGTH_SHORT).show()
                    return@runOnUiThread
                }
                MobilePlayerActivity.playLive(this, item.name, command, item.provider, item.category)
            }
        }
    }

    /** Resolve one channel to playable streams on a worker thread. Null when it stays dark. */
    private fun liveCommand(item: LiveItem): MobilePlayerActivity.LiveCommand? = runCatching {
        when (item.provider) {
            "Xtream" -> {
                val account = XtreamAccount.INBUILT
                val streams = XtreamClient.liveStreamsFor(account, item.id)
                if (streams.isEmpty()) return null
                MobilePlayerActivity.LiveCommand(item.name,
                    streams.map { MobilePlayerActivity.StreamRef(it.url, it.type, MobilePlayerActivity.extraOf(it)) })
            }
            "Portal", "Portal 2" -> {
                if (item.cmd.isBlank()) return null
                val portal = if (item.provider == "Portal 2") StalkerPortal(StalkerAccount(
                    StalkerAccount.FUSION_DEFAULT_PORTAL, StalkerAccount.FUSION_DEFAULT_MAC,
                    StalkerAccount.FUSION_DEFAULT_SERIAL, "")) else StalkerPortal()
                val url = portal.link(item.cmd).ifBlank { return null }
                MobilePlayerActivity.LiveCommand(item.name,
                    listOf(MobilePlayerActivity.StreamRef(url, MobilePlayerActivity.guessType(url))))
            }
            "Jio" -> {
                val streams = TgServers.fetchFrom(LiveProvider.JIOM3U, item.id)
                if (streams.isEmpty()) return null
                MobilePlayerActivity.LiveCommand(item.name,
                    streams.map { MobilePlayerActivity.StreamRef(it.url, it.type, MobilePlayerActivity.extraOf(it)) })
            }
            else -> {
                val streams = TgServers.fetch(item.id, v2 = item.v2)
                if (streams.isEmpty()) return null
                MobilePlayerActivity.LiveCommand(item.name,
                    streams.map { MobilePlayerActivity.StreamRef(it.url, it.type, MobilePlayerActivity.extraOf(it)) })
            }
        }
    }.getOrNull()

    /** Network first, the last good list when offline — the Live tab opens either way. */
    private fun tgGet(url: String, cacheName: String): String {
        val cache = java.io.File(filesDir, cacheName)
        val fresh = runCatching {
            val connection = java.net.URL(url).openConnection() as java.net.HttpURLConnection
            connection.connectTimeout = 10_000
            connection.readTimeout = 20_000
            connection.setRequestProperty("User-Agent", TgHttp.USER_AGENT)
            connection.setRequestProperty("Accept", "application/json")
            connection.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
                .takeIf { it.length in 100..8_000_000 }
        }.getOrNull()
        if (fresh != null) {
            runCatching { cache.writeText(fresh) }
            return fresh
        }
        return runCatching { cache.readText() }.getOrElse { "" }
    }

    // ---------- Movies / Series tabs ----------

    private fun showCatalog(body: ScrollView, movies: Boolean) {
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        body.addView(column)
        column.addView(section(if (movies) "Movies" else "Series"))
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        column.addView(searchField(if (movies) "Search movies…" else "Search series…",
            if (movies) movieQuery else seriesQuery) { query ->
            if (movies) movieQuery = query else seriesQuery = query
            searchHandler.removeCallbacks(searchRun)
            searchBox = box
            if (query.length >= 2) searchHandler.postDelayed(searchRun, 450)
            else fillCatalog(box, movies)
        })
        val shelves = if (movies) Movies.MOVIE_SHELVES else Movies.SERIES_SHELVES
        val genres = listOf("All") + shelves.mapNotNull { it.genre }.distinct()
        val picked = if (movies) movieGenre else seriesGenre
        val chips = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@MobileHomeActivity, RecyclerView.HORIZONTAL, false)
            layoutParams = ViewGroup.LayoutParams(-1, dp(52))
            setPadding(dp(10), dp(6), dp(10), 0)
        }
        chips.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun onCreateViewHolder(p: ViewGroup, t: Int): RecyclerView.ViewHolder {
                val shell = FrameLayout(this@MobileHomeActivity).apply { setPadding(dp(4), 0, dp(4), 0) }
                return object : RecyclerView.ViewHolder(shell) {}
            }
            override fun getItemCount() = genres.size
            override fun onBindViewHolder(h: RecyclerView.ViewHolder, position: Int) {
                val shell = h.itemView as FrameLayout
                shell.removeAllViews()
                shell.addView(chip(genres[position], genres[position] == picked) {
                    if (movies) movieGenre = genres[position] else seriesGenre = genres[position]
                    if (movies) movieQuery = "" else seriesQuery = ""
                    showTab()
                })
            }
        }
        column.addView(chips)
        column.addView(box)
        searchBox = box
        fillCatalog(box, movies)
    }

    private fun catalogQuery(movies: Boolean) = if (movies) movieQuery else seriesQuery
    private fun catalogSearching(movies: Boolean) = if (movies) movieSearching else seriesSearching
    private fun catalogResults(movies: Boolean) = if (movies) movieResults else seriesResults

    private fun runCatalogSearch() {
        val box = searchBox ?: return
        val movies = tab == 2
        if (tab != 2 && tab != 3) return
        if (movies) movieSearching = true else seriesSearching = true
        fillCatalog(box, movies)
        val query = catalogQuery(movies)
        io.submit {
            val results = runCatching { Movies.search(query, if (movies) "movie" else "series") }.getOrElse { emptyList() }
            if (movies) { movieResults = results; movieSearching = false } else { seriesResults = results; seriesSearching = false }
            runOnUiThread {
                val still = (tab == 2 && movies) || (tab == 3 && !movies)
                if (still && catalogQuery(movies) == query) searchBox?.let { fillCatalog(it, movies) }
            }
        }
    }

    private fun fillCatalog(box: LinearLayout, movies: Boolean) {
        box.removeAllViews()
        val query = catalogQuery(movies)
        if (query.length >= 2) {
            if (catalogSearching(movies)) box.addView(ProgressBar(this).apply { isIndeterminate = true })
            else if (catalogResults(movies).isEmpty()) box.addView(hint("No matches for “$query”."))
            else box.addView(posterGrid(catalogResults(movies)) { openDetail(it) })
            return
        }
        val shelves = if (movies) Movies.MOVIE_SHELVES else Movies.SERIES_SHELVES
        val genre = if (movies) movieGenre else seriesGenre
        val wanted = if (genre == "All") shelves.take(8) else shelves.filter { it.genre == genre }
        if (wanted.isEmpty()) {
            box.addView(hint("Nothing here yet."))
            return
        }
        var waiting = false
        wanted.forEach { shelf ->
            val cached = shelfCache[shelf.title]
            if (cached != null && cached.isNotEmpty()) {
                box.addView(section(shelf.title))
                box.addView(rail(cached) { openDetail(it) })
            } else if (cached == null) {
                waiting = true
                io.submit {
                    val titles = runCatching { Movies.shelf(shelf) }.getOrElse { emptyList() }
                    shelfCache[shelf.title] = titles
                    runOnUiThread {
                        val still = (tab == 2 && movies) || (tab == 3 && !movies)
                        val sameGenre = (if (movies) movieGenre else seriesGenre) == genre
                        if (still && sameGenre && catalogQuery(movies).length < 2) searchBox?.let { fillCatalog(it, movies) }
                    }
                }
            }
        }
        if (waiting) box.addView(ProgressBar(this).apply { isIndeterminate = true })
    }

    private fun posterGrid(titles: List<Movies.Title>, onTap: (Movies.Title) -> Unit): RecyclerView {
        val grid = RecyclerView(this).apply {
            layoutManager = GridLayoutManager(this@MobileHomeActivity, 3)
            layoutParams = ViewGroup.LayoutParams(-1, -2)
            isNestedScrollingEnabled = false
            setPadding(dp(8), 0, dp(8), 0)
        }
        grid.adapter = object : RecyclerView.Adapter<PosterVH>() {
            override fun onCreateViewHolder(p: ViewGroup, t: Int): PosterVH {
                val card = LinearLayout(this@MobileHomeActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(5), dp(4), dp(5), dp(4))
                }
                val art = ImageView(this@MobileHomeActivity).apply {
                    layoutParams = ViewGroup.LayoutParams(-1, dp(168))
                    scaleType = ImageView.ScaleType.CENTER_CROP
                }
                Glass.round(art, 12)
                val name = TextView(this@MobileHomeActivity).apply {
                    setTextColor(Color.parseColor(Glass.TEXT)); textSize = 11f
                    maxLines = 1
                    setPadding(0, dp(4), 0, 0)
                    layoutParams = ViewGroup.LayoutParams(-1, -2)
                }
                card.addView(art); card.addView(name)
                return PosterVH(card, art, name)
            }
            override fun getItemCount() = titles.size
            override fun onBindViewHolder(h: PosterVH, position: Int) {
                val title = titles[position]
                h.name.text = title.name
                image.load(title.poster, h.art)
                h.card.setOnClickListener { onTap(title) }
            }
        }
        return grid
    }

    // ---------- Detail ----------

    private fun openDetail(title: Movies.Title) {
        val detail = ScrollView(this)
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(24))
        }
        detail.addView(column)
        if (title.background.isNotBlank()) {
            val backdrop = ImageView(this).apply {
                layoutParams = ViewGroup.LayoutParams(-1, dp(190))
                scaleType = ImageView.ScaleType.CENTER_CROP
            }
            Glass.round(backdrop, 16)
            image.load(title.background, backdrop)
            column.addView(backdrop)
        }
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(12), 0, 0)
        }
        val art = ImageView(this).apply {
            layoutParams = ViewGroup.LayoutParams(dp(110), dp(160))
            scaleType = ImageView.ScaleType.CENTER_CROP
        }
        Glass.round(art, 12)
        image.load(title.poster, art)
        val info = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(14) }
        }
        info.addView(TextView(this).apply {
            text = title.name
            setTextColor(Color.parseColor(Glass.TEXT)); textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
        })
        val meta = listOf(title.year, title.rating, title.genres.take(3).joinToString(", "))
            .filter { it.isNotBlank() }.joinToString(" · ")
        if (meta.isNotBlank()) info.addView(TextView(this).apply {
            text = meta
            setTextColor(Color.parseColor(Glass.SUB)); textSize = 13f
            setPadding(0, dp(4), 0, 0)
        })
        top.addView(art); top.addView(info)
        column.addView(top)
        if (title.description.isNotBlank()) column.addView(TextView(this).apply {
            text = title.description
            setTextColor(Color.parseColor("#C9C4D6")); textSize = 14f
            setPadding(0, dp(12), 0, dp(12))
        })
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val play = pillButton("▶ Play", primary = true) { playTitle(title, 0, 0) }
        play.layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = dp(8) }
        val save = pillButton("⬇") { downloadTitle(title, 0, 0) }
        val tv = pillButton("📺") { castTitle(title, 0, 0) }
        actions.addView(play); actions.addView(save); actions.addView(tv)
        column.addView(actions)
        if (title.isSeries) {
            column.addView(TextView(this).apply {
                text = "Episodes"
                setTextColor(Color.parseColor(Glass.TEXT)); textSize = 17f
                typeface = Typeface.DEFAULT_BOLD
                setPadding(0, dp(16), 0, dp(4))
            })
            val spin = ProgressBar(this).apply { isIndeterminate = true }
            column.addView(spin)
            io.submit {
                val episodes = runCatching { Movies.details(title.type, title.tmdb).second }.getOrElse { emptyList() }
                runOnUiThread {
                    column.removeView(spin)
                    if (episodes.isEmpty()) {
                        column.addView(hint("Episode list didn't load."))
                        return@runOnUiThread
                    }
                    episodes.take(200).forEach { episode ->
                        val row = LinearLayout(this).apply {
                            orientation = LinearLayout.VERTICAL
                            background = Glass.card(14, 0x1E)
                            setPadding(dp(12), dp(8), dp(12), dp(8))
                            layoutParams = LinearLayout.LayoutParams(-1, -2).apply {
                                topMargin = dp(4); bottomMargin = dp(4)
                            }
                        }
                        row.addView(TextView(this).apply {
                            text = "S${episode.season} E${episode.episode} · ${episode.name}"
                            setTextColor(Color.parseColor(Glass.TEXT)); textSize = 14f
                            typeface = Typeface.DEFAULT_BOLD
                            maxLines = 2
                        })
                        val actions = LinearLayout(this).apply {
                            orientation = LinearLayout.HORIZONTAL
                            setPadding(0, dp(6), 0, 0)
                        }
                        val play = pillButton("▶ Play") { playTitle(title, episode.season, episode.episode) }
                        play.layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = dp(6) }
                        val save = pillButton("⬇") { downloadTitle(title, episode.season, episode.episode) }
                        val tv = pillButton("📺") { castTitle(title, episode.season, episode.episode) }
                        actions.addView(play); actions.addView(save); actions.addView(tv)
                        row.addView(actions)
                        column.addView(row)
                    }
                }
            }
        }
        val dialog = AlertDialog.Builder(this).setView(detail).setPositiveButton("Close", null).show()
        dialog.window?.setBackgroundDrawable(android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            cornerRadius = dp(24).toFloat()
            setColor(Color.parseColor("#1E1330"))
        })
    }

    private fun playTitle(title: Movies.Title, season: Int, episode: Int, preloaded: List<Movies.Episode> = emptyList()) {
        // Series-level Play starts at S01E01 — resolving a series without an episode is invalid.
        if (title.isSeries && season <= 0) {
            Toast.makeText(this, "Finding ${title.name}…", Toast.LENGTH_SHORT).show()
            io.submit {
                val episodes = runCatching { Movies.details(title.type, title.tmdb).second }.getOrElse { emptyList() }
                val first = episodes.minWithOrNull(compareBy({ it.season }, { it.episode }))
                runOnUiThread {
                    if (first == null) {
                        Toast.makeText(this, "Episode list didn't load for ${title.name}", Toast.LENGTH_SHORT).show()
                        return@runOnUiThread
                    }
                    playTitle(title, first.season, first.episode, episodes)
                }
            }
            return
        }
        Toast.makeText(this, "Finding ${title.name}…", Toast.LENGTH_SHORT).show()
        io.submit {
            val (sources, reason) = frostSources(title, season, episode)
            // The player needs the full episode list for its in-player episode menu.
            val eps = if (preloaded.isNotEmpty()) preloaded
                else if (title.isSeries) runCatching { Movies.details(title.type, title.tmdb).second }.getOrElse { emptyList() }
                else emptyList()
            runOnUiThread {
                if (sources.isEmpty()) {
                    Toast.makeText(this, "No playable source for ${title.name}" +
                        (if (reason != null) " ($reason)" else ""), Toast.LENGTH_LONG).show()
                    return@runOnUiThread
                }
                Movies.saveTitle(prefs, title)
                val type = if (title.isSeries) "tv" else "movie"
                MobilePlayerActivity.playVod(this, title.name, sources, title.tmdb, type, season, episode,
                    MobileVod.encodeEpisodes(eps.map { MobileVod.Episode(it.season, it.episode, it.name) }))
            }
        }
    }

    /** One shared VOD chain for the home and the player overlay — see [MobileVod]. */
    private fun frostSources(title: Movies.Title, season: Int, episode: Int): Pair<List<FrostSource>, String?> =
        MobileVod.resolve(if (title.isSeries) "tv" else "movie", title.tmdb, season, episode)

    private fun downloadTitle(title: Movies.Title, season: Int, episode: Int) {
        if (title.isSeries && season <= 0) {
            Toast.makeText(this, "Pick an episode below to download.", Toast.LENGTH_SHORT).show()
            return
        }
        val label = if (season > 0) "${title.name} S${season}E$episode" else title.name
        Toast.makeText(this, "Finding a downloadable file…", Toast.LENGTH_SHORT).show()
        io.submit {
            val (sources, reason) = frostSources(title, season, episode)
            if (sources.isEmpty()) {
                runOnUiThread {
                    Toast.makeText(this, "No downloadable source for $label" +
                        (if (reason != null) " ($reason)" else ""), Toast.LENGTH_LONG).show()
                }
                return@submit
            }
            val mp4 = sources.firstOrNull { it.format == VodFormat.MP4 }
            runOnUiThread {
                if (mp4 == null) {
                    Toast.makeText(this, "Only streamable sources found — downloads need an MP4 file.", Toast.LENGTH_LONG).show()
                    return@runOnUiThread
                }
                val id = MobileDownloads.enqueue(this, mp4.url, mp4.headers, "$label.mp4")
                if (id < 0) Toast.makeText(this, "Download didn't start.", Toast.LENGTH_SHORT).show()
                else {
                    Toast.makeText(this, "Downloading $label — see Saved.", Toast.LENGTH_SHORT).show()
                    tab = 4; buildTabs(); showTab()
                }
            }
        }
    }

    // ---------- Saved ----------

    private fun showSaved(body: ScrollView) {
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        body.addView(column)
        column.addView(section("Saved on this phone"))
        val downloads = MobileDownloads.list(this)
        if (downloads.isEmpty()) {
            column.addView(hint("Nothing saved yet. Open any movie or episode and tap ⬇ Download."))
            return
        }
        downloads.forEach { download ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = Glass.card(16, 0x1E)
                setPadding(dp(14), dp(10), dp(6), dp(10))
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply {
                    leftMargin = dp(12); rightMargin = dp(12); topMargin = dp(4); bottomMargin = dp(4)
                }
            }
            val name = TextView(this).apply {
                text = "${download.label}\n${download.size / 1_048_576} MB"
                setTextColor(Color.parseColor(Glass.TEXT)); textSize = 14f
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            }
            row.addView(name)
            row.addView(pillButton("▶") { MobilePlayerActivity.playFile(this, download.label, download.file) })
            row.addView(pillButton("✕") {
                MobileDownloads.remove(this, download.id)
                showTab()
            })
            column.addView(row)
        }
    }

    // ---------- Play on TV ----------

    private fun castLive(item: LiveItem) = pickTv { tv ->
        io.submit {
            val providerTag = when (item.provider) {
                "TG" -> "tg"
                "Jio" -> "jio"
                "TATA" -> "tata"
                "Portal" -> "portal"
                "Portal 2" -> "portal2"
                "Xtream" -> "xtream"
                else -> "tg"
            }
            val ok = CastSender.send(tv, CastProtocol.encodeLive(providerTag, item.id, item.name))
            runOnUiThread {
                Toast.makeText(this,
                    if (ok) "Playing ${item.name} on ${tv.name}" else "Couldn't reach ${tv.name}",
                    Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun castTitle(title: Movies.Title, season: Int, episode: Int) {
        if (title.isSeries && season <= 0) {
            Toast.makeText(this, "Pick an episode below to play on the TV.", Toast.LENGTH_SHORT).show()
            return
        }
        pickTv { tv ->
        io.submit {
            val type = if (title.isSeries) "tv" else "movie"
            val ok = CastSender.send(tv, CastProtocol.encodeVod("frost", title.tmdb, type, season, episode, title.name))
            runOnUiThread {
                Toast.makeText(this,
                    if (ok) "Playing ${title.name} on ${tv.name}" else "Couldn't reach ${tv.name}",
                    Toast.LENGTH_SHORT).show()
            }
        }
        }
    }

    private fun onWifi(): Boolean = runCatching {
        val manager = getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
        val network = manager?.activeNetwork ?: return false
        manager.getNetworkCapabilities(network)
            ?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) == true
    }.getOrDefault(false)

    private fun pickTv(onTv: (CastSender.Tv) -> Unit) {
        if (tvs.isEmpty()) {
            // Discovery is LAN multicast: on mobile data the TV is unreachable by physics, not by
            // bug. Say so, and offer the direct-IP escape hatch that needs no discovery at all.
            if (!onWifi()) {
                Toast.makeText(this, "Join the same Wi-Fi as your TV first — mobile data can't see it.",
                    Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(this, "Looking for your TV… keep STREAMR open on it, then try again.",
                    Toast.LENGTH_LONG).show()
            }
            discovery?.refresh()
            AlertDialog.Builder(this)
                .setTitle("TV not found")
                .setMessage("Make sure this phone is on the same Wi-Fi as the TV with STREAMR open. " +
                    "Or type the TV's IP — no discovery needed.")
                .setPositiveButton("Enter TV IP") { _, _ -> manualTvIp(onTv) }
                .setNegativeButton("Cancel", null)
                .show()
            return
        }
        if (tvs.size == 1) {
            onTv(tvs.first())
            return
        }
        val names = tvs.map { it.name }.toTypedArray() + "Enter TV IP…"
        AlertDialog.Builder(this)
            .setTitle("Play on TV")
            .setItems(names) { _, which ->
                if (which < tvs.size) onTv(tvs[which]) else manualTvIp(onTv)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun manualTvIp(onTv: (CastSender.Tv) -> Unit) {
        val input = EditText(this).apply {
            hint = "e.g. 192.168.1.20"
            setHintTextColor(Color.parseColor("#5E5A6B"))
            setTextColor(Color.parseColor(Glass.TEXT))
            setText(prefs.getString("tv_ip", ""))
            setPadding(dp(18), dp(14), dp(18), dp(14))
        }
        AlertDialog.Builder(this)
            .setTitle("TV address")
            .setView(input.apply {
                layoutParams = ViewGroup.LayoutParams(-1, -2).apply {}
            }.let { frameOf(it) })
            .setPositiveButton("Connect") { _, _ ->
                val ip = input.text.toString().trim()
                if (!ip.matches(Regex("[0-9A-Za-z.:]{3,64}"))) {
                    Toast.makeText(this, "That doesn't look like an address.", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                prefs.edit().putString("tv_ip", ip).apply()
                onTv(CastSender.Tv("TV", ip, CastProtocol.PORT))
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun frameOf(view: View): View =
        FrameLayout(this).apply {
            setPadding(dp(18), dp(8), dp(18), 0)
            addView(view)
        }
}
