package com.aktv.app

import android.graphics.Color
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
        // Give the lead artwork room to breathe, while keeping the first shelf visible on TV and mobile.
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
        // The nav is a top bar now: mark and wordmark left, pill tabs centre, search and profile right.
        val nav = if (m.phone) 0 else if (m.compact) 76 else 92
        val side = if (m.compact) 24 else 48

        root.findViewById<View>(R.id.navRail).apply {
            visibility = if (m.phone) View.GONE else View.VISIBLE
            layoutParams.height = px(nav)
            setPadding(px(side), 0, px(side), 0)
        }
        root.findViewById<ImageView>(R.id.brandMark).layoutParams.apply {
            width = px(if (m.compact) 36 else 44); height = width
        }
        root.findViewById<TextView>(R.id.akText).apply {
            visibility = View.VISIBLE
            textSize = if (m.compact) 17f else 21f
        }
        root.findViewById<TextView>(R.id.railTagline).apply {
            text = "BY AKSHANSH"
            textSize = if (m.compact) 6.5f else 8f
            visibility = if (short) View.GONE else View.VISIBLE
        }
        root.findViewById<View>(R.id.navProfile).visibility = if (m.phone || short) View.GONE else View.VISIBLE
        root.findViewById<TextView>(R.id.navVersion).visibility = View.GONE
        for (id in listOf(R.id.segLive, R.id.segV2, R.id.segJio, R.id.segSites, R.id.segSeries, R.id.searchBtn)) {
            root.findViewById<TextView>(id).apply {
                layoutParams.height = px(if (short) 38 else 44)
                setPadding(px(if (m.compact) 14 else 22), 0, px(if (m.compact) 14 else 22), 0)
                textSize = if (short) 11f else if (m.compact) 12f else 13f
                includeFontPadding = false
            }
        }
        root.findViewById<View>(R.id.phoneNavScroll).visibility = if (m.phone) View.VISIBLE else View.GONE

        (root.findViewById<View>(R.id.content).layoutParams as FrameLayout.LayoutParams).marginStart = 0
        root.findViewById<View>(R.id.content).setPadding(0, px(nav), 0, 0)
        // The tab bar is the section title on a television; the phone keeps the line above the hero.
        root.findViewById<View>(R.id.topBar).apply {
            visibility = if (m.phone) View.VISIBLE else View.GONE
            layoutParams.height = if (m.phone) m.header else 0
            setPadding(px(if (m.compact) 16 else 48), 0, px(if (m.compact) 16 else 48), 0)
        }
        root.findViewById<TextView>(R.id.sectionTitle).textSize = if (m.compact) 20f else 26f
        root.findViewById<TextView>(R.id.sectionKicker).textSize = if (m.compact) 8f else 9f
        root.findViewById<View>(R.id.librarySummary).visibility =
            if (screenWidth / dp < 900) View.GONE else View.VISIBLE
        root.findViewById<View>(R.id.heroMedia).apply {
            (layoutParams as FrameLayout.LayoutParams).apply {
                height = (screenHeight * if (m.phone) 0.78f else 0.94f).toInt().coerceAtLeast(m.hero)
                topMargin = 0; marginStart = 0; marginEnd = 0
            }
        }
        root.findViewById<View>(R.id.heroTagline).visibility = View.GONE
        root.findViewById<View>(R.id.heroArea).layoutParams.height = m.hero
        root.findViewById<View>(R.id.heroText).apply {
            (layoutParams as FrameLayout.LayoutParams).apply {
                width = ((screenWidth - px(side) * 2) * 0.56f).toInt().coerceAtLeast(px(240))
                marginStart = px(side) + px(if (m.compact) 8 else 16)
                bottomMargin = px(if (m.compact) 14 else 26)
            }
        }
        root.findViewById<TextView>(R.id.heroTitle).apply {
            // Ten-foot type: the title is the loudest thing on the screen.
            textSize = when { m.compact -> 26f; m.phone -> 32f; movies -> 42f; else -> 46f }
            maxLines = if (m.compact) 1 else 2
            setTextColor(Accent.TEXT)
        }
        root.findViewById<TextView>(R.id.heroSubheading).textSize = if (m.compact) 13f else 19f
        root.findViewById<TextView>(R.id.heroDesc).apply {
            maxLines = if (m.compact) 1 else 3
            textSize = if (m.compact) 11f else 14f
            setTextColor(Accent.DIM)
        }
        root.findViewById<View>(R.id.heroChips).let {
            (it.layoutParams as LinearLayout.LayoutParams).topMargin = px(if (m.compact) 10 else 18)
        }
        root.findViewById<View>(R.id.heroLogo).apply {
            (layoutParams as FrameLayout.LayoutParams).apply {
                width = px(if (m.compact) 108 else 156); height = px(if (m.compact) 70 else 100)
                gravity = android.view.Gravity.TOP or android.view.Gravity.END
                topMargin = nav + ((m.hero - height).coerceAtLeast(0) / 2) + px(12)
                marginEnd = px(side)
            }
        }
        val heroLogoLp = root.findViewById<View>(R.id.heroLogo).layoutParams as FrameLayout.LayoutParams
        root.findViewById<TextView>(R.id.heroLogoCaption).apply {
            (layoutParams as FrameLayout.LayoutParams).apply {
                width = heroLogoLp.width; height = px(20)
                gravity = android.view.Gravity.TOP or android.view.Gravity.END
                topMargin = heroLogoLp.topMargin + heroLogoLp.height
                marginEnd = heroLogoLp.marginEnd
            }
            textSize = if (m.compact) 9f else 11f
        }
        root.findViewById<View>(R.id.heroMono).let {
            (it.layoutParams as FrameLayout.LayoutParams).apply {
                gravity = android.view.Gravity.TOP or android.view.Gravity.END
                topMargin = nav + ((m.hero - px(if (m.compact) 70 else 100)).coerceAtLeast(0) / 2) + px(12)
                marginEnd = px(side + 16)
            }
        }
        root.findViewById<RecyclerView>(R.id.rows).setPadding(0, px(if (m.compact) 6 else 10), 0, px(24))
        (root.findViewById<View>(R.id.status).layoutParams as LinearLayout.LayoutParams).marginStart = px(side)
        root.requestLayout()
        return m
    }

    fun styleRow(row: View, compact: Boolean, dp: Float) {
        val title = row.findViewById<TextView>(R.id.rowTitle)
        title.textSize = if (compact) 16f else 22f
        title.setTextColor(Accent.TEXT)
        (title.parent as View).let { (it.layoutParams as ViewGroup.MarginLayoutParams).marginStart = ((if (compact) 24 else 48) * dp).toInt() }
        val rv = row.findViewById<RecyclerView>(R.id.rowList)
        rv.setPadding(((if (compact) 18 else 42) * dp).toInt(), (10 * dp).toInt(), (48 * dp).toInt(), (10 * dp).toInt())
    }

    fun styleTile(tile: View, poster: Boolean, compact: Boolean, dp: Float, landscape: Boolean = false) {
        val lp = tile.layoutParams as ViewGroup.MarginLayoutParams
        lp.setMargins((6 * dp).toInt(), (4 * dp).toInt(), (6 * dp).toInt(), (4 * dp).toInt())
        lp.marginStart = (8 * dp).toInt(); lp.marginEnd = (8 * dp).toInt()
        if (landscape) {
            lp.width = ((if (compact) 196 else 300) * dp).toInt()
            lp.height = ((if (compact) 110 else 169) * dp).toInt()
        } else if (!poster) {
            lp.width = ((if (compact) 148 else 180) * dp).toInt()
            lp.height = ((if (compact) 82 else 101) * dp).toInt()
            tile.findViewById<ImageView>(R.id.logo)?.layoutParams?.apply { width = ((if (compact) 44 else 62) * dp).toInt(); height = width }
            tile.findViewById<TextView>(R.id.name)?.textSize = if (compact) 11f else 14f
        } else {
            val width = ((if (compact) 118 else 176) * dp).toInt()
            lp.width = width
            val contentWidth = (width - (24 * dp).toInt()).coerceAtLeast((72 * dp).toInt())
            tile.findViewById<View>(R.id.posterContent)?.layoutParams?.width = contentWidth
            tile.findViewById<ImageView>(R.id.poster)?.let { posterView ->
                val box = posterView.parent as View
                val posterWidth = contentWidth - (8 * dp).toInt()
                box.layoutParams.apply { this.width = posterWidth; height = (posterWidth * 1.5f).toInt() }
            }
            tile.findViewById<TextView>(R.id.posterRank)?.apply {
                textSize = if (compact) 43f else 62f
                layoutParams.height = ((contentWidth - (8 * dp).toInt()) * 1.5f).toInt()
            }
            tile.findViewById<TextView>(R.id.posterTitle)?.textSize = if (compact) 9f else 11f
            tile.findViewById<TextView>(R.id.posterMeta)?.textSize = if (compact) 8f else 9f
        }
    }
}
