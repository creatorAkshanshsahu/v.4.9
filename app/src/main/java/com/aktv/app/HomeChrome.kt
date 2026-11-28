package com.aktv.app

import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

/** Shared native layout rules for real screens and offline layout/snapshot tests. No network or player. */
internal object HomeChrome {
    data class Metrics(val rail: Int, val header: Int, val hero: Int, val margin: Int, val compact: Boolean, val phone: Boolean)
    fun metrics(width: Int, height: Int, dp: Float, movies: Boolean): Metrics {
        val compact = height / dp < 420
        val phone = width / dp < 600 || (compact && width / dp < 900)
        val rail = if (phone) 0 else ((if (compact) 104 else 120) * dp).toInt()
        val header = if (phone) ((if (compact) 54 else 76) * dp).toInt() else 0
        val heroRatio = when {
            phone && compact -> if (movies) 0.26f else 0.30f
            phone -> if (movies) 0.39f else 0.41f
            movies -> 0.43f
            else -> 0.44f
        }
        val hero = (height * heroRatio).toInt()
        return Metrics(rail, header, hero, ((if (compact) 20 else 28) * dp).toInt(), compact, phone)
    }

    fun apply(root: View, screenWidth: Int, screenHeight: Int, dp: Float, movies: Boolean): Metrics {
        val m = metrics(screenWidth, screenHeight, dp, movies)
        fun px(value: Int) = (value * dp).toInt()
        val short = screenHeight / dp < 400
        val nav = if (m.compact) 64 else 76
        val side = if (m.compact) 12 else 24

        // The screen is the light gradient with one big rounded glass container holding everything.
        root.findViewById<View>(R.id.flixContainer).apply {
            (layoutParams as ViewGroup.MarginLayoutParams).apply {
                val mx = px(if (m.compact) 8 else 20); val my = px(if (m.compact) 6 else 12)
                setMargins(mx, my, mx, my)
            }
            setPadding(px(side), px(6), px(side), px(6))
        }

        // The bar: plain white wordmark left, dark tab pill centred, bell and profile right.
        root.findViewById<View>(R.id.navRail).apply {
            visibility = if (m.phone) View.GONE else View.VISIBLE
            layoutParams.height = px(nav)
        }
        root.findViewById<View>(R.id.brandMark).visibility = View.GONE
        root.findViewById<TextView>(R.id.akText).apply {
            visibility = View.VISIBLE
            textSize = if (m.compact) 17f else 22f
            setTextColor(0xFFFFFFFF.toInt())
            letterSpacing = -0.01f
            paint.shader = null
            typeface = androidx.core.content.res.ResourcesCompat.getFont(root.context, R.font.sora_bold)
        }
        root.findViewById<View>(R.id.railTagline).visibility = View.GONE
        val profileVisible = !m.phone && !short
        root.findViewById<View>(R.id.navProfile).apply {
            visibility = if (profileVisible) View.VISIBLE else View.GONE
        }
        root.findViewById<TextView>(R.id.profileAvatar).apply {
            layoutParams.width = px(34); layoutParams.height = px(34)
            text = "S"
        }
        root.findViewById<View>(R.id.flixBellBox).apply {
            visibility = if (profileVisible) View.VISIBLE else View.GONE
        }
        root.findViewById<TextView>(R.id.flixBell).apply {
            layoutParams.width = px(38); layoutParams.height = px(38)
            text = ""
            val icon = androidx.core.content.res.ResourcesCompat.getDrawable(root.resources, R.drawable.ic_bell, root.context.theme)
            val size = px(18)
            icon?.setBounds(0, 0, size, size)
            setCompoundDrawables(icon, null, null, null)
            compoundDrawablePadding = 0
            gravity = Gravity.CENTER_VERTICAL or Gravity.START
            setPadding(px(10), 0, 0, 0)
        }
        root.findViewById<TextView>(R.id.navVersion).visibility = View.GONE
        for (id in listOf(R.id.segLive, R.id.segJio, R.id.segPortal, R.id.segFusion, R.id.segSites, R.id.segSeries, R.id.segOg, R.id.segStar)) {
            root.findViewById<TextView>(id)?.apply {
                layoutParams.height = px(if (short) 32 else 34)
                setPadding(px(if (m.compact) 9 else 12), 0, px(if (m.compact) 9 else 12), 0)
                textSize = if (short) 10.5f else if (m.compact) 11.5f else 13f
                includeFontPadding = false
                typeface = androidx.core.content.res.ResourcesCompat.getFont(root.context, R.font.sora_semibold)
            }
        }
        val narrowPhone = m.phone && screenWidth / dp < 680
        for (id in listOf(R.id.phoneLive, R.id.phoneJio, R.id.phonePortal, R.id.phoneFusion, R.id.phoneMovies, R.id.phoneSeries, R.id.phoneOg, R.id.phoneStar, R.id.phoneSearch)) {
            root.findViewById<TextView>(id)?.apply {
                setPadding(px(if (narrowPhone) 9 else 11), 0, px(if (narrowPhone) 9 else 11), 0)
                textSize = if (narrowPhone) 10.5f else 11f
            }
        }
        root.findViewById<View>(R.id.navPills).apply {
            layoutParams.height = px(if (short) 40 else 42)
        }
        // The search glyph sits at the end of the dark pill, white on navy.
        root.findViewById<TextView>(R.id.searchBtn).apply {
            layoutParams.width = px(34); layoutParams.height = px(34)
            text = ""
            val icon = androidx.core.content.res.ResourcesCompat.getDrawable(root.resources, R.drawable.ic_search, root.context.theme)
            val size = px(16)
            icon?.setBounds(0, 0, size, size)
            setCompoundDrawables(icon, null, null, null)
            compoundDrawablePadding = 0
            gravity = Gravity.CENTER_VERTICAL or Gravity.START
            setPadding(px(9), 0, 0, 0)
            setTextColor(0xFFFFFFFF.toInt())
        }
        root.findViewById<View>(R.id.phoneNavScroll).visibility = if (m.phone) View.VISIBLE else View.GONE

        root.findViewById<View>(R.id.topBar).visibility = View.GONE
        root.findViewById<View>(R.id.librarySummary).visibility = View.GONE

        // Two hero cards: the feature narrow left, the next one wide right. Phones keep one card so
        // the shelves stay on screen.
        val singleHero = m.phone
        val heroHeight = (screenHeight * when {
            m.phone && m.compact -> 0.37f
            m.phone -> 0.30f
            m.compact -> 0.38f
            else -> 0.36f
        }).toInt().coerceAtLeast(px(132))
        root.findViewById<View>(R.id.heroArea).apply {
            layoutParams.height = heroHeight
            (this as LinearLayout).orientation = LinearLayout.HORIZONTAL
        }
        root.findViewById<View>(R.id.heroMedia).apply {
            val lp = layoutParams as LinearLayout.LayoutParams
            lp.weight = if (singleHero) 1f else 0.38f
            lp.width = 0
            (lp as ViewGroup.MarginLayoutParams).marginEnd = px(if (singleHero) 0 else 7)
        }
        root.findViewById<View>(R.id.heroCard2).apply {
            visibility = if (singleHero) View.GONE else View.VISIBLE
            val lp = layoutParams as LinearLayout.LayoutParams
            lp.weight = 0.62f
            lp.width = 0
            (lp as ViewGroup.MarginLayoutParams).marginStart = px(7)
        }
        root.findViewById<View>(R.id.heroGlow).visibility = View.GONE
        root.findViewById<View>(R.id.heroTagline).visibility = View.GONE
        root.findViewById<View>(R.id.heroLogoCaption).visibility = View.GONE
        root.findViewById<View>(R.id.heroChips).visibility = View.GONE
        // The cards carry the card: the trim in MainActivity re-hides these after every hero bind.
        for (id in listOf(R.id.heroDot, R.id.heroSubheading, R.id.heroProg, R.id.heroProgress, R.id.heroDesc, R.id.heroNext, R.id.heroMono)) {
            root.findViewById<View>(id)?.visibility = View.GONE
        }
        // The hero logo sits at the card's top-right, as the studio mark does in the reference.
        root.findViewById<View>(R.id.heroLogo).apply {
            (layoutParams as FrameLayout.LayoutParams).apply {
                width = px(if (m.compact) 44 else 56); height = px(if (m.compact) 44 else 56)
                gravity = Gravity.TOP or Gravity.END
                topMargin = px(14); marginEnd = px(16)
            }
        }
        root.findViewById<TextView>(R.id.heroTitle).apply {
            textSize = when { m.compact -> 20f; m.phone -> 24f; else -> 30f }
            maxLines = 2
        }
        root.findViewById<TextView>(R.id.heroBadge).apply {
            textSize = if (m.compact) 10f else 11f
            setTextColor(FlixColors.CAPTION)
        }
        root.findViewById<TextView>(R.id.heroPlayLabel).apply {
            textSize = if (m.compact) 11f else 13f
        }
        root.findViewById<TextView>(R.id.hero2Title).apply {
            textSize = when { m.compact -> 18f; else -> 26f }
            maxLines = 2
        }
        root.findViewById<TextView>(R.id.hero2Badge).apply {
            textSize = if (m.compact) 10f else 11f
        }
        root.findViewById<TextView>(R.id.hero2Play).apply {
            textSize = if (m.compact) 11f else 13f
        }
        // The chip strip and the section header ride between the heroes and the shelves.
        root.findViewById<View>(R.id.flixChipScroll).apply {
            layoutParams.height = px(if (m.compact) 42 else 46)
        }
        root.findViewById<View>(R.id.sectionHeader).apply {
            layoutParams.height = px(if (m.compact) 46 else 52)
        }
        root.findViewById<TextView>(R.id.sectionTitle).apply {
            textSize = if (m.compact) 18f else 24f
            setTextColor(0xFFFFFFFF.toInt())
        }
        root.findViewById<View>(R.id.flixViewPill).apply {
            layoutParams.height = px(if (m.compact) 36 else 40)
        }
        for (id in listOf(R.id.flixSortBtn, R.id.flixFilterBtn)) {
            root.findViewById<ImageView>(id)?.apply {
                val s = px(if (m.compact) 32 else 36)
                layoutParams.width = s; layoutParams.height = s
            }
        }
        root.findViewById<RecyclerView>(R.id.rows).setPadding(0, px(8), 0, px(10))
        (root.findViewById<View>(R.id.status).layoutParams as LinearLayout.LayoutParams).marginStart = px(if (m.compact) 12 else 16)
        root.requestLayout()
        return m
    }

    fun styleRow(row: View, compact: Boolean, dp: Float) {
        val title = row.findViewById<TextView>(R.id.rowTitle)
        title.textSize = if (compact) 15f else 20f
        title.setTextColor(0xFFFFFFFF.toInt())
        (title.parent as View).let { (it.layoutParams as ViewGroup.MarginLayoutParams).marginStart = ((if (compact) 12 else 16) * dp).toInt() }
        row.findViewById<TextView>(R.id.rowCount).setTextColor(FlixColors.FAINT)
        val rv = row.findViewById<RecyclerView>(R.id.rowList)
        rv.setPadding(((if (compact) 8 else 12) * dp).toInt(), (8 * dp).toInt(), (40 * dp).toInt(), (12 * dp).toInt())
    }

    /** Poster art is 7:10: a 116dp card carries 165dp of artwork, well above the 150dp touch floor. */
    fun posterArtHeight(widthPx: Int): Int = (widthPx * 10 / 7)

    fun styleTile(tile: View, poster: Boolean, compact: Boolean, dp: Float, landscape: Boolean = false) {
        val lp = tile.layoutParams as ViewGroup.MarginLayoutParams
        lp.setMargins((6 * dp).toInt(), (4 * dp).toInt(), (6 * dp).toInt(), (4 * dp).toInt())
        lp.marginStart = (10 * dp).toInt(); lp.marginEnd = (10 * dp).toInt()
        if (landscape) {
            lp.width = ((if (compact) 176 else 200) * dp).toInt()
            lp.height = ((if (compact) 99 else 113) * dp).toInt()
        } else if (!poster) {
            // 16:9 artwork with navy ink copy straight on the white glass card.
            lp.width = ((if (compact) 168 else 216) * dp).toInt()
            lp.height = ((if (compact) 95 else 122) * dp).toInt()
            tile.findViewById<ImageView>(R.id.logo)?.layoutParams?.apply { width = ((if (compact) 46 else 62) * dp).toInt(); height = width }
            tile.findViewById<TextView>(R.id.name)?.textSize = if (compact) 11f else 13f
        } else {
            // 7:10 artwork with the title and counters beneath it, as in the reference rail.
            val width = ((if (compact) 104 else 116) * dp).toInt()
            lp.width = width
            lp.height = ViewGroup.LayoutParams.WRAP_CONTENT
            tile.findViewById<View>(R.id.posterArt)?.layoutParams?.apply { this.width = width; height = posterArtHeight(width) }
            tile.findViewById<TextView>(R.id.posterRank)?.apply {
                textSize = if (compact) 34f else 40f
            }
            tile.findViewById<TextView>(R.id.posterTitle)?.textSize = if (compact) 11f else 12f
            tile.findViewById<TextView>(R.id.posterMeta)?.textSize = if (compact) 9f else 10f
        }
    }
}
