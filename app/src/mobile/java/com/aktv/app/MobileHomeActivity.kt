package com.aktv.app

import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.os.Build
import android.os.Bundle
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
 * STREAMR Mobile: the second app. Same accounts, same catalogues, same network code as the TV
 * shelf — [TgServers], [XtreamClient], [Movies], [JioM3uProtocol], [M3uPlaylist] — but a phone
 * home: bottom tabs, poster grids, and per-item **Play / ⬇ Download / 📺 Play-on-TV** actions.
 * Everything visual lives here; nothing here is shared with the TV edition.
 */
class MobileHomeActivity : AppCompatActivity() {
    private val io = Executors.newFixedThreadPool(4) { r -> Thread(r, "aktv-mobile").apply { isDaemon = true } }
    private val image = MobileImage()
    private val prefs by lazy { getSharedPreferences("aktv_mobile", Context.MODE_PRIVATE) }
    private var root: LinearLayout? = null
    private var body: ScrollView? = null
    private var tabRow: LinearLayout? = null
    private var tab = 0
    private var tvs: List<CastSender.Tv> = emptyList()
    private var discovery: CastSender.Discovery? = null
    private var searching: Runnable? = null

    private data class LiveItem(val provider: String, val id: String, val name: String, val logo: String, val category: String, val v2: Boolean = false, val cmd: String = "")
    private data class Download(val id: Long, val label: String, val file: String, val size: Long)

    private var homeMovies: List<Movies.Title> = emptyList()
    private var live: List<LiveItem> = emptyList()
    private var liveLoaded = false
    private var liveLoading = false
    private var liveQuery = ""
    private var providerTabs = listOf("All", "TG", "Jio", "TATA", "Portal", "Portal 2", "Xtream")

    private val downloadReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (tab == 3) showTab()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MobileImage.init(this)
        runCatching { TgServers.init(this) }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0D0714"))
        }
        this.root = root
        val title = TextView(this).apply {
            text = "STREAMR Mobile"
            setTextColor(Color.parseColor("#F5F0E6"))
            textSize = 22f
            setPadding(dp(16), dp(14), dp(16), dp(6))
        }
        root.addView(title, LinearLayout.LayoutParams(-1, -2))
        val body = ScrollView(this).apply { isFillViewport = true }
        this.body = body
        root.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
        val tabRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#170E24"))
            setPadding(0, dp(6), 0, dp(10))
        }
        this.tabRow = tabRow
        root.addView(tabRow, LinearLayout.LayoutParams(-1, -2))
        setContentView(root)
        buildTabs()
        showTab()
        runCatching {
            val filter = IntentFilter(android.app.DownloadManager.ACTION_DOWNLOAD_COMPLETE)
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(downloadReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            else @Suppress("UnspecifiedRegisterReceiverFlags") registerReceiver(downloadReceiver, filter)
        }
        discovery = CastSender.Discovery(this) { found ->
            tvs = found
            runOnUiThread { searching?.run() }
        }.also { it.start() }
    }

    override fun onResume() {
        super.onResume()
        if (tab == 3) showTab()
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(downloadReceiver) }
        discovery?.stop()
        io.shutdownNow()
        super.onDestroy()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun buildTabs() {
        val names = listOf("🏠 Home", "📡 Live", "🎬 Movies", "⬇ Saved")
        val row = tabRow ?: return
        row.removeAllViews()
        names.forEachIndexed { i, name ->
            val button = Button(this, null, android.R.attr.borderlessButtonStyle).apply {
                text = name
                textSize = 13f
                setTextColor(if (i == tab) Color.parseColor("#E8B4B8") else Color.parseColor("#8E8A99"))
            }
            button.setOnClickListener { if (tab != i) { tab = i; buildTabs(); showTab() } }
            row.addView(button, LinearLayout.LayoutParams(0, -2, 1f))
        }
    }

    private fun showTab() {
        val body = body ?: return
        body.removeAllViews()
        when (tab) {
            0 -> showHome(body)
            1 -> showLive(body)
            2 -> showMovies(body)
            3 -> showSaved(body)
        }
    }

    private fun section(title: String): TextView = TextView(this).apply {
        text = title
        setTextColor(Color.parseColor("#F5F0E6"))
        textSize = 17f
        setPadding(dp(16), dp(14), dp(16), dp(8))
    }

    private fun hint(text: String): TextView = TextView(this).apply {
        this.text = text
        setTextColor(Color.parseColor("#8E8A99"))
        textSize = 14f
        setPadding(dp(16), dp(8), dp(16), dp(8))
    }

    private fun actionRow(vararg buttons: Button): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            buttons.forEach { addView(it, LinearLayout.LayoutParams(0, -2, 1f).apply {
                leftMargin = dp(4); rightMargin = dp(4)
            }) }
        }

    private fun smallButton(text: String, onTap: () -> Unit): Button =
        Button(this, null, android.R.attr.borderlessButtonStyle).apply {
            this.text = text
            textSize = 13f
            setTextColor(Color.parseColor("#E8B4B8"))
            setOnClickListener { onTap() }
        }

    // ---------- Home: trending + continue ----------

    private fun showHome(body: ScrollView) {
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        body.addView(column)
        column.addView(section("Continue watching"))
        val recent = Movies.recent(prefs)
        if (recent.isEmpty()) column.addView(hint("Nothing yet — what you start lands here."))
        else column.addView(posterRow(recent) { openDetail(it) })
        column.addView(section("Trending this week"))
        if (homeMovies.isEmpty()) {
            val spin = ProgressBar(this).apply { isIndeterminate = true }
            column.addView(spin)
            io.submit {
                val shelf = Movies.shelves("movie").firstOrNull()
                val titles = runCatching { if (shelf != null) Movies.shelf(shelf) else emptyList() }.getOrElse { emptyList() }
                homeMovies = titles
                runOnUiThread { if (tab == 0) showTab() }
            }
        } else {
            column.addView(posterRow(homeMovies) { openDetail(it) })
            column.addView(section("Popular series"))
            column.addView(hint("Series live under Movies — same shelves, same downloads."))
        }
    }

    private fun posterRow(titles: List<Movies.Title>, onTap: (Movies.Title) -> Unit): RecyclerView {
        val list = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@MobileHomeActivity, RecyclerView.HORIZONTAL, false)
            layoutParams = ViewGroup.LayoutParams(-1, dp(190))
        }
        list.adapter = object : RecyclerView.Adapter<PosterVH>() {
            override fun onCreateViewHolder(p: ViewGroup, t: Int): PosterVH {
                val card = LinearLayout(this@MobileHomeActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(6), dp(2), dp(6), dp(2))
                }
                val art = ImageView(this@MobileHomeActivity).apply {
                    layoutParams = ViewGroup.LayoutParams(dp(110), dp(150))
                    scaleType = ImageView.ScaleType.CENTER_CROP
                }
                val name = TextView(this@MobileHomeActivity).apply {
                    setTextColor(Color.parseColor("#F5F0E6")); textSize = 11f
                    maxLines = 1
                    layoutParams = ViewGroup.LayoutParams(dp(110), -2)
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

    private class PosterVH(val card: View, val art: ImageView, val name: TextView) : RecyclerView.ViewHolder(card)

    // ---------- Live: all seven TV sources, one list ----------

    private fun showLive(body: ScrollView) {
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        body.addView(column)
        column.addView(section("Live TV"))
        val listBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val search = EditText(this).apply {
            hint = "Search channels…"
            setHintTextColor(Color.parseColor("#5E5A6B"))
            setTextColor(Color.parseColor("#F5F0E6"))
            setText(liveQuery)
            setPadding(dp(16), dp(10), dp(16), dp(10))
        }
        column.addView(search)
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                liveQuery = s?.toString().orEmpty()
                if (liveLoaded && !liveLoading) fillLiveList(listBox)
            }
        })
        val providerRow = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@MobileHomeActivity, RecyclerView.HORIZONTAL, false)
            layoutParams = ViewGroup.LayoutParams(-1, dp(48))
        }
        var picked = providerPicked()
        providerRow.adapter = object : RecyclerView.Adapter<ChipVH>() {
            override fun onCreateViewHolder(p: ViewGroup, t: Int): ChipVH {
                val chip = smallButton("") {}
                return ChipVH(chip)
            }
            override fun getItemCount() = providerTabs.size
            override fun onBindViewHolder(h: ChipVH, position: Int) {
                h.chip.text = providerTabs[position]
                h.chip.setTextColor(if (position == picked) Color.parseColor("#E8B4B8") else Color.parseColor("#8E8A99"))
                h.chip.setOnClickListener {
                    picked = position
                    prefs.edit().putInt("live_provider", position).apply()
                    showTab()
                }
            }
        }
        column.addView(providerRow)
        column.addView(listBox)
        if (!liveLoaded && !liveLoading) {
            liveLoading = true
            fillLiveList(listBox)
            io.submit {
                val items = loadAllLive()
                live = items
                liveLoaded = true
                liveLoading = false
                runOnUiThread { if (tab == 1) showTab() }
            }
            return
        }
        fillLiveList(listBox)
    }

    private fun fillLiveList(listBox: LinearLayout) {
        listBox.removeAllViews()
        if (liveLoading) {
            listBox.addView(ProgressBar(this).apply { isIndeterminate = true })
            listBox.addView(hint("Loading TG · Jio · TATA · portals · Xtream…"))
            return
        }
        val provider = providerTabs.getOrElse(providerPicked()) { "All" }
        val filtered = live.filter {
            (provider == "All" || it.provider == provider) &&
                (liveQuery.isBlank() || it.name.contains(liveQuery, true))
        }
        if (filtered.isEmpty()) {
            listBox.addView(hint(if (live.isEmpty()) "No channels loaded — check the connection, then retry." else "Nothing matches “$liveQuery”."))
            if (live.isEmpty()) listBox.addView(smallButton("↻ Retry") { liveLoaded = false; showTab() })
            return
        }
        listBox.addView(hint("${filtered.size} channels"))
        filtered.take(600).forEach { item ->
            listBox.addView(liveRow(item))
        }
        if (filtered.size > 600) listBox.addView(hint("…showing the first 600 of ${filtered.size}. Search to narrow."))
    }

    private class ChipVH(val chip: Button) : RecyclerView.ViewHolder(chip)

    private fun providerPicked(): Int = prefs.getInt("live_provider", 0).coerceIn(0, providerTabs.size - 1)

    private fun liveRow(item: LiveItem): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
            isClickable = true
            isFocusable = true
        }
        val logo = ImageView(this).apply {
            layoutParams = ViewGroup.LayoutParams(dp(52), dp(52))
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        image.load(item.logo, logo)
        val name = TextView(this).apply {
            text = item.name
            setTextColor(Color.parseColor("#F5F0E6"))
            textSize = 15f
            setPadding(dp(12), 0, dp(8), 0)
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        }
        val play = smallButton("▶") { playLive(item) }
        val tv = smallButton("📺") { castLive(item) }
        row.addView(logo); row.addView(name); row.addView(play); row.addView(tv)
        row.setOnClickListener { playLive(item) }
        row.setOnLongClickListener { castLive(item); true }
        return row
    }

    private fun loadAllLive(): List<LiveItem> {
        val out = ArrayList<LiveItem>(4000)
        // TG v1 + v2: same endpoints and shapes as the TV shelf's loadChannels.
        runCatching {
            MobileCatalog.parseV1(tgGet("${TgServers.SITE}/api/public/channels", "mobile-v1.json"))
                .forEach { out += LiveItem("TG", it.id, it.name, it.logo, it.category) }
        }
        runCatching {
            MobileCatalog.parseV2(tgGet("${TgServers.SITE}/api/public/v2/channels", "mobile-v2.json"))
                .forEach { out += LiveItem("TG", it.id, it.name, it.logo, it.category, v2 = true) }
        }
        // Jio.
        runCatching {
            JioM3uResolver().catalogue().forEach { out += LiveItem("Jio", it.id, it.name, it.logo, it.category) }
        }
        // TATA.
        runCatching {
            TgServers.tataCatalogue().forEach { out += LiveItem("TATA", it.id, it.name, it.logo, it.category) }
        }
        // StarShare.
        runCatching {
            val account = XtreamAccount.INBUILT
            if (account.isConfigured) {
                XtreamClient.fetchLiveCatalogue(account).forEach {
                    out += LiveItem("Xtream", it.streamId, it.name, it.logo, it.categoryName)
                }
            }
        }
        // Stalker portals: the same inbuilt subscriptions as the TV shelf out of the box.
        runCatching {
            val portal = StalkerPortal()
            val genres = portal.genres()
            portal.channels().forEach { c ->
                out += LiveItem("Portal", c.id, c.name, c.logo,
                    StalkerProtocol.categoryFor(genres, c.genre, c.name), cmd = c.cmd)
            }
        }
        runCatching {
            val fusion = StalkerPortal(StalkerAccount(
                StalkerAccount.FUSION_DEFAULT_PORTAL, StalkerAccount.FUSION_DEFAULT_MAC,
                StalkerAccount.FUSION_DEFAULT_SERIAL, ""))
            val genres = fusion.genres()
            fusion.channels().forEach { c ->
                out += LiveItem("Portal 2", c.id, c.name, c.logo,
                    StalkerProtocol.categoryFor(genres, c.genre, c.name), cmd = c.cmd)
            }
        }
        return out
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
                MobilePlayerActivity.playLive(this, item.name, command)
            }
        }
    }

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
                val streams = JioM3uResolver().resolve(item.id)
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

    // ---------- Movies: shelves, search, detail ----------

    private var movieShelves: List<Pair<String, List<Movies.Title>>> = emptyList()
    private var movieQuery = ""
    private var movieResults: List<Movies.Title> = emptyList()
    private var movieSearching = false

    private fun showMovies(body: ScrollView) {
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        body.addView(column)
        column.addView(section("Movies & series"))
        val search = EditText(this).apply {
            hint = "Search TMDB…"
            setHintTextColor(Color.parseColor("#5E5A6B"))
            setTextColor(Color.parseColor("#F5F0E6"))
            setText(movieQuery)
            setPadding(dp(16), dp(10), dp(16), dp(10))
        }
        column.addView(search)
        val resultsBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        column.addView(resultsBox)
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                movieQuery = s?.toString().orEmpty()
                movieSearchHandler.removeCallbacks(movieSearchRun)
                if (movieQuery.length >= 2) movieSearchHandler.postDelayed(movieSearchRun, 450)
                else fillMovies(resultsBox)
            }
        })
        movieSearchBox = resultsBox
        fillMovies(resultsBox)
    }

    private val movieSearchHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var movieSearchBox: LinearLayout? = null
    private val movieSearchRun = Runnable {
        val box = movieSearchBox ?: return@Runnable
        if (tab != 2) return@Runnable
        movieSearching = true
        fillMovies(box)
        val query = movieQuery
        io.submit {
            val results = runCatching { Movies.search(query) }.getOrElse { emptyList() }
            movieResults = results
            movieSearching = false
            runOnUiThread { if (tab == 2 && movieQuery == query) movieSearchBox?.let { fillMovies(it) } }
        }
    }

    private fun fillMovies(box: LinearLayout) {
        box.removeAllViews()
        if (movieQuery.length >= 2) {
            if (movieSearching) box.addView(ProgressBar(this).apply { isIndeterminate = true })
            else if (movieResults.isEmpty()) box.addView(hint("No matches for “$movieQuery”."))
            else box.addView(posterGrid(movieResults) { openDetail(it) })
            return
        }
        if (movieShelves.isEmpty()) {
            box.addView(ProgressBar(this).apply { isIndeterminate = true })
            io.submit {
                val shelves = ArrayList<Pair<String, List<Movies.Title>>>()
                runCatching {
                    Movies.MOVIE_SHELVES.take(3).forEach { shelf ->
                        val titles = Movies.shelf(shelf)
                        if (titles.isNotEmpty()) shelves += shelf.title to titles
                    }
                    Movies.SERIES_SHELVES.take(2).forEach { shelf ->
                        val titles = Movies.shelf(shelf)
                        if (titles.isNotEmpty()) shelves += shelf.title to titles
                    }
                }
                movieShelves = shelves
                runOnUiThread { if (tab == 2 && movieQuery.length < 2) movieSearchBox?.let { fillMovies(it) } }
            }
            return
        }
        movieShelves.forEach { (title, titles) ->
            box.addView(section(title))
            box.addView(posterRow(titles) { openDetail(it) })
        }
    }

    private fun posterGrid(titles: List<Movies.Title>, onTap: (Movies.Title) -> Unit): RecyclerView {
        val grid = RecyclerView(this).apply {
            layoutManager = GridLayoutManager(this@MobileHomeActivity, 3)
            layoutParams = ViewGroup.LayoutParams(-1, -2)
            isNestedScrollingEnabled = false
        }
        grid.adapter = object : RecyclerView.Adapter<PosterVH>() {
            override fun onCreateViewHolder(p: ViewGroup, t: Int): PosterVH {
                val card = LinearLayout(this@MobileHomeActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(6), dp(4), dp(6), dp(4))
                }
                val art = ImageView(this@MobileHomeActivity).apply {
                    layoutParams = ViewGroup.LayoutParams(-1, dp(170))
                    scaleType = ImageView.ScaleType.CENTER_CROP
                }
                val name = TextView(this@MobileHomeActivity).apply {
                    setTextColor(Color.parseColor("#F5F0E6")); textSize = 11f
                    maxLines = 1
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

    private fun openDetail(title: Movies.Title) {
        val detail = ScrollView(this)
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(24))
        }
        detail.addView(column)
        val art = ImageView(this).apply {
            layoutParams = ViewGroup.LayoutParams(dp(180), dp(260))
            scaleType = ImageView.ScaleType.CENTER_CROP
        }
        image.load(title.poster, art)
        column.addView(art)
        column.addView(TextView(this).apply {
            text = title.name
            setTextColor(Color.parseColor("#F5F0E6")); textSize = 20f
            setPadding(0, dp(12), 0, 0)
        })
        val meta = listOf(title.year, title.rating, title.genres.take(3).joinToString(", "))
            .filter { it.isNotBlank() }.joinToString(" · ")
        if (meta.isNotBlank()) column.addView(TextView(this).apply {
            text = meta
            setTextColor(Color.parseColor("#8E8A99")); textSize = 13f
        })
        if (title.description.isNotBlank()) column.addView(TextView(this).apply {
            text = title.description
            setTextColor(Color.parseColor("#C9C4D6")); textSize = 14f
            setPadding(0, dp(10), 0, dp(10))
        })
        val play = smallButton("▶ Play") { playTitle(title, 0, 0) }
        val save = smallButton("⬇ Download") { downloadTitle(title, 0, 0) }
        val tv = smallButton("📺 Play on TV") { castTitle(title, 0, 0) }
        column.addView(actionRow(play, save, tv))
        if (title.isSeries) {
            column.addView(TextView(this).apply {
                text = "Episodes"
                setTextColor(Color.parseColor("#F5F0E6")); textSize = 17f
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
                            orientation = LinearLayout.HORIZONTAL
                            gravity = Gravity.CENTER_VERTICAL
                            setPadding(0, dp(6), 0, dp(6))
                        }
                        val label = TextView(this).apply {
                            text = "S${episode.season} E${episode.episode} · ${episode.name}"
                            setTextColor(Color.parseColor("#F5F0E6")); textSize = 14f
                            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
                        }
                        row.addView(label)
                        row.addView(smallButton("▶") { playTitle(title, episode.season, episode.episode) })
                        row.addView(smallButton("⬇") { downloadTitle(title, episode.season, episode.episode) })
                        row.addView(smallButton("📺") { castTitle(title, episode.season, episode.episode) })
                        column.addView(row)
                    }
                }
            }
        }
        AlertDialog.Builder(this).setView(detail)
            .setPositiveButton("Close", null)
            .show()
    }

    private fun playTitle(title: Movies.Title, season: Int, episode: Int) {
        Toast.makeText(this, "Finding ${title.name}…", Toast.LENGTH_SHORT).show()
        io.submit {
            val sources = runCatching { frostSources(title, season, episode) }.getOrElse { emptyList() }
            runOnUiThread {
                if (sources.isEmpty()) {
                    Toast.makeText(this, "No playable source for ${title.name}", Toast.LENGTH_SHORT).show()
                    return@runOnUiThread
                }
                Movies.saveTitle(prefs, title)
                val subs = sources.firstOrNull()?.subtitles.orEmpty()
                    .map { it.url to it.language.orEmpty().ifBlank { "en" } }
                MobilePlayerActivity.playVod(this, title.name,
                    sources.map { MobilePlayerActivity.StreamRef(it.url, it.format.name.lowercase(), gsonOf(it.headers)) }, subs)
            }
        }
    }

    private fun frostSources(title: Movies.Title, season: Int, episode: Int): List<FrostSource> {
        val type = if (title.isSeries) "tv" else "movie"
        val frostTitle = FrostTitle(title.tmdb, type, season, episode)
        val first = runCatching { FrostResolver().resolve(frostTitle) }.getOrElse { emptyList() }
        if (first.isNotEmpty()) return first
        return runCatching { FrostResolver().resolve(frostTitle, FrostProvider.OG) }.getOrElse { emptyList() }
    }

    private fun gsonOf(headers: Map<String, String>): String =
        headers.entries.joinToString(",", "{", "}") { (k, v) ->
            "\"${k.replace("\"", "")}\":\"${v.replace("\"", "")}\""
        }

    private fun downloadTitle(title: Movies.Title, season: Int, episode: Int) {
        val label = if (season > 0) "${title.name} S${season}E$episode" else title.name
        Toast.makeText(this, "Finding a downloadable file…", Toast.LENGTH_SHORT).show()
        io.submit {
            val sources = runCatching { frostSources(title, season, episode) }.getOrElse { emptyList() }
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
                    tab = 3; buildTabs(); showTab()
                }
            }
        }
    }

    // ---------- Saved: finished downloads ----------

    private fun showSaved(body: ScrollView) {
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        body.addView(column)
        column.addView(section("Saved on this phone"))
        val downloads = MobileDownloads.list(this)
        if (downloads.isEmpty()) {
            column.addView(hint("Nothing saved yet. Open any movie and tap ⬇ Download."))
            return
        }
        downloads.forEach { download ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(8), dp(12), dp(8))
            }
            val name = TextView(this).apply {
                text = "${download.label}\n${download.size / 1_048_576} MB"
                setTextColor(Color.parseColor("#F5F0E6")); textSize = 14f
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            }
            row.addView(name)
            row.addView(smallButton("▶") { MobilePlayerActivity.playFile(this, download.label, download.file) })
            row.addView(smallButton("✕") {
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
                "Playlist" -> "m3u"
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

    private fun castTitle(title: Movies.Title, season: Int, episode: Int) = pickTv { tv ->
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

    private fun pickTv(onTv: (CastSender.Tv) -> Unit) {
        if (tvs.isEmpty()) {
            Toast.makeText(this, "Looking for your TV… keep STREAMR open on it, then try again.", Toast.LENGTH_LONG).show()
            discovery?.refresh()
            return
        }
        if (tvs.size == 1) {
            onTv(tvs.first())
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Play on TV")
            .setItems(tvs.map { it.name }.toTypedArray()) { _, which -> onTv(tvs[which]) }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
