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
        val rail = if (phone) 0 else ((if (compact) 116 else 144) * dp).toInt()
        // The selected TV design begins directly in the cinematic hero; phone keeps a compact title bar.
        val header = if (phone) ((if (compact) 54 else 80) * dp).toInt() else 0
        val heroRatio = if (phone && compact) { if (movies) 0.24f else 0.28f } else if (movies) 0.32f else 0.40f
        val hero = (height * heroRatio).toInt()
        return Metrics(rail, header, hero, ((if (compact) 20 else 28) * dp).toInt(), compact, phone)
    }

    fun apply(root: View, screenWidth: Int, screenHeight: Int, dp: Float, movies: Boolean): Metrics {
        val m = metrics(screenWidth, screenHeight, dp, movies)
        fun px(value: Int) = (value * dp).toInt()
        val rail = root.findViewById<LinearLayout>(R.id.navRail)
        rail.visibility = if (m.phone) View.GONE else View.VISIBLE
        root.findViewById<View>(R.id.phoneNavScroll).visibility = if (m.phone) View.VISIBLE else View.GONE
        rail.layoutParams.width = m.rail
        rail.setPadding(px(if (m.compact) 7 else 10), px(if (m.compact) 10 else 20), px(if (m.compact) 7 else 10), px(10))
        root.findViewById<ImageView>(R.id.brandMark).layoutParams.apply {
            width = px(if (m.compact) 64 else 92); height = px(if (m.compact) 40 else 58)
        }
        (root.findViewById<TextView>(R.id.akText).layoutParams as LinearLayout.LayoutParams).bottomMargin = px(if (m.compact) 6 else 10)
        root.findViewById<TextView>(R.id.railTagline).apply {
            textSize = if (m.compact) 5.5f else 6.5f
            visibility = if (m.compact && screenHeight / dp < 380) View.GONE else View.VISIBLE
        }
        for (id in listOf(R.id.segLive, R.id.segV2, R.id.segSites, R.id.segSeries, R.id.searchBtn)) {
            root.findViewById<TextView>(id).apply {
                val shortScreen = screenHeight / dp < 350
                layoutParams.height = px(if (shortScreen) 44 else if (m.compact) 48 else 58)
                includeFontPadding = false
                setPadding(0, px(if (shortScreen) 3 else 5), 0, px(if (shortScreen) 3 else 5))
                compoundDrawablePadding = px(3)
                val icon = px(if (shortScreen) 16 else if (m.compact) 18 else 20)
                compoundDrawables.filterNotNull().forEach { it.setBounds(0, 0, icon, icon) }
                textSize = if (shortScreen) 9f else if (m.compact) 10f else 11f
            }
        }
        root.findViewById<TextView>(R.id.navVersion).visibility = if (screenHeight / dp < 380) View.GONE else View.VISIBLE
        (root.findViewById<View>(R.id.content).layoutParams as FrameLayout.LayoutParams).marginStart = m.rail
        root.findViewById<View>(R.id.topBar).apply {
            visibility = if (m.header == 0) View.GONE else View.VISIBLE
            layoutParams.height = m.header; setPadding(m.margin, 0, m.margin, 0)
        }
        root.findViewById<View>(R.id.heroTagline).visibility = if (m.phone) View.GONE else View.VISIBLE
        root.findViewById<TextView>(R.id.sectionTitle).textSize = if (m.compact) 21f else 27f
        root.findViewById<TextView>(R.id.sectionKicker).textSize = if (m.compact) 8f else 9f
        root.findViewById<View>(R.id.librarySummary).visibility = if (screenWidth / dp < 800) View.GONE else View.VISIBLE
        root.findViewById<View>(R.id.heroMedia).apply {
            // The selected art is an edge-to-edge backdrop behind the title bar and the first shelf,
            // rather than a floating thumbnail card. Keep the shelf reservation below unchanged.
            (layoutParams as FrameLayout.LayoutParams).apply {
                val backdropFraction = when { m.phone && !m.compact -> 0.56f; m.compact -> 0.72f; else -> 0.78f }
                height = maxOf((screenHeight * backdropFraction).toInt(), m.header + m.hero)
                topMargin = 0; marginStart = m.rail; marginEnd = 0
            }
            background = null
            clipToOutline = false
        }
        root.findViewById<View>(R.id.heroArea).layoutParams.height = m.hero
        root.findViewById<View>(R.id.heroText).apply {
            (layoutParams as FrameLayout.LayoutParams).apply {
                width = ((screenWidth - m.rail - m.margin * 2) * 0.70f).toInt().coerceAtLeast(px(220))
                marginStart = m.margin + px(if (m.compact) 16 else 24)
                bottomMargin = px(if (m.compact) 13 else 22)
            }
        }
        root.findViewById<TextView>(R.id.heroTitle).apply { textSize = if (m.compact) 24f else if (movies) 34f else 38f; maxLines = if (m.compact) 1 else 2 }
        root.findViewById<TextView>(R.id.heroDesc).maxLines = if (m.compact || !movies) 1 else 2
        root.findViewById<View>(R.id.heroChips).let { (it.layoutParams as LinearLayout.LayoutParams).topMargin = px(if (m.compact) 10 else 16) }
        root.findViewById<View>(R.id.heroLogo).apply {
            (layoutParams as FrameLayout.LayoutParams).apply {
                width = px(if (m.compact) 128 else 210); height = px(if (m.compact) 80 else 132)
                marginEnd = px(if (m.compact) 24 else 56)
            }
        }
        root.findViewById<View>(R.id.heroMono).let { (it.layoutParams as FrameLayout.LayoutParams).marginEnd = px(if (m.compact) 36 else 68) }
        root.findViewById<View>(R.id.heroTagline).let { (it.layoutParams as FrameLayout.LayoutParams).topMargin = px(if (m.compact) 46 else 72) }
        root.findViewById<RecyclerView>(R.id.rows).setPadding(0, px(if (m.compact) 8 else 16), 0, px(24))
        (root.findViewById<View>(R.id.status).layoutParams as LinearLayout.LayoutParams).marginStart = m.margin
        root.requestLayout()
        return m
    }

    fun styleRow(row: View, compact: Boolean, dp: Float) {
        val title = row.findViewById<TextView>(R.id.rowTitle)
        title.textSize = if (compact) 15f else 19f
        (title.parent as View).let { (it.layoutParams as ViewGroup.MarginLayoutParams).marginStart = ((if (compact) 20 else 28) * dp).toInt() }
        val rv = row.findViewById<RecyclerView>(R.id.rowList)
        rv.setPadding(((if (compact) 14 else 20) * dp).toInt(), (8 * dp).toInt(), (40 * dp).toInt(), (12 * dp).toInt())
    }

    fun styleTile(tile: View, poster: Boolean, compact: Boolean, dp: Float) {
        val lp = tile.layoutParams as ViewGroup.MarginLayoutParams
        lp.setMargins((6 * dp).toInt(), (4 * dp).toInt(), (6 * dp).toInt(), (4 * dp).toInt())
        if (!poster) {
            lp.width = ((if (compact) 142 else 158) * dp).toInt()
            lp.height = ((if (compact) 80 else 96) * dp).toInt()
            tile.findViewById<ImageView>(R.id.logo)?.layoutParams?.apply { width = ((if (compact) 44 else 62) * dp).toInt(); height = width }
            tile.findViewById<TextView>(R.id.name)?.textSize = if (compact) 11f else 14f
        } else {
            val width = ((if (compact) 114 else 156) * dp).toInt()
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
