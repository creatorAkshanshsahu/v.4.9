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
        val nav = if (m.phone) 0 else if (m.compact) 68 else 88
        val side = if (m.compact) 20 else 48

        // The bar: wordmark left, glass pill tabs centred, round search and profile right.
        root.findViewById<View>(R.id.navRail).apply {
            visibility = if (m.phone) View.GONE else View.VISIBLE
            layoutParams.height = px(nav)
            setPadding(px(side), 0, px(side), 0)
        }
        root.findViewById<TextView>(R.id.akText).apply {
            visibility = View.VISIBLE
            textSize = if (m.compact) 16f else 22f
            setTextColor(Accent.AMBER)
            letterSpacing = -0.02f
            // The wordmark in the user's logo is a gradient (amber into gold), not a flat colour. Paint the
            // glyphs with the same two stops once the text is measured, and leave the flat amber as fallback.
            post {
                val paint = paint
                val width = paint.measureText(text.toString())
                if (width > 0f) paint.shader = android.graphics.LinearGradient(
                    0f, 0f, width, 0f, intArrayOf(0xFFF2912B.toInt(), 0xFFF7C64A.toInt()), null,
                    android.graphics.Shader.TileMode.CLAMP)
            }
        }
        // Mark plus wordmark, as in the site header — the mark is no longer hidden.
        root.findViewById<View>(R.id.brandMark).apply {
            visibility = View.VISIBLE
            layoutParams.width = px(if (m.compact) 26 else 36)
            layoutParams.height = px(if (m.compact) 26 else 36)
            (layoutParams as ViewGroup.MarginLayoutParams).marginEnd = px(if (m.compact) 8 else 10)
        }
        root.findViewById<View>(R.id.railTagline).visibility = View.GONE
        root.findViewById<View>(R.id.navProfile).apply {
            visibility = if (m.phone || short) View.GONE else View.VISIBLE
            layoutParams.width = px(38); layoutParams.height = px(38)
        }
        root.findViewById<View>(R.id.profileAvatar).apply {
            layoutParams.width = px(38); layoutParams.height = px(38)
        }
        root.findViewById<TextView>(R.id.navVersion).visibility = View.GONE
        for (id in listOf(R.id.segLive, R.id.segJio, R.id.segSites, R.id.segSeries)) {
            root.findViewById<TextView>(id).apply {
                layoutParams.height = px(if (short) 34 else 36)
                setPadding(px(if (m.compact) 12 else 16), 0, px(if (m.compact) 12 else 16), 0)
                textSize = if (short) 11f else if (m.compact) 12f else 14f
                includeFontPadding = false
                typeface = androidx.core.content.res.ResourcesCompat.getFont(root.context, R.font.sora_semibold)
            }
        }
        root.findViewById<View>(R.id.navPills).apply {
            layoutParams.height = px(if (short) 40 else 42)
        }
        // Round search button: the magnifier is a glyph, not a word, as on the site.
        root.findViewById<TextView>(R.id.searchBtn).apply {
            layoutParams.width = px(38); layoutParams.height = px(38)
            text = ""
            val icon = androidx.core.content.res.ResourcesCompat.getDrawable(root.resources, R.drawable.ic_search, root.context.theme)
            val size = px(17)
            icon?.setBounds(0, 0, size, size)
            setCompoundDrawables(icon, null, null, null)
            compoundDrawablePadding = 0
            gravity = android.view.Gravity.CENTER_VERTICAL or android.view.Gravity.START
            setPadding(px(11), 0, 0, 0)
            setTextColor(Accent.TEXT)
            background = androidx.core.content.res.ResourcesCompat.getDrawable(root.resources, R.drawable.circle_glass, root.context.theme)
        }
        root.findViewById<View>(R.id.phoneNavScroll).visibility = if (m.phone) View.VISIBLE else View.GONE

        (root.findViewById<View>(R.id.content).layoutParams as FrameLayout.LayoutParams).marginStart = 0
        root.findViewById<View>(R.id.content).setPadding(0, 0, 0, 0)
        root.findViewById<View>(R.id.topBar).visibility = View.GONE
        root.findViewById<View>(R.id.topBar).layoutParams.height = 0
        root.findViewById<View>(R.id.librarySummary).visibility = View.GONE

        // Hero: full-bleed artwork behind the bar, copy anchored bottom-left, as on the site. No card,
        // no rounding, no inset — the still runs to every edge of the screen.
        val heroHeight = (screenHeight * when {
            m.phone && m.compact -> 0.34f
            m.phone -> 0.46f
            // A 2:3 poster shelf is the tallest row the app has, but the hero still has to be tall enough
            // to hold its own copy clear of the bar: the rail peeks, the hero never collides.
            movies -> 0.46f
            m.compact -> 0.58f
            else -> 0.60f
        }).toInt()
        root.findViewById<View>(R.id.heroMedia).apply {
            (layoutParams as FrameLayout.LayoutParams).apply {
                height = heroHeight + px(nav)
                topMargin = 0; marginStart = 0; marginEnd = 0
            }
            background = null
            clipToOutline = false
        }
        root.findViewById<View>(R.id.heroGlow).visibility = View.GONE
        root.findViewById<View>(R.id.heroTagline).visibility = View.GONE
        // The shelves ride up over the still by 40dp, the way the site pulls its rails with -mt-10.
        root.findViewById<View>(R.id.heroArea).layoutParams.height =
            heroHeight + px(nav) - (if (m.compact || m.phone) 0 else px(40))
        root.findViewById<View>(R.id.heroText).apply {
            (layoutParams as FrameLayout.LayoutParams).apply {
                // max-w-2xl on the site: the copy never runs wider than 672dp.
                width = minOf(px(672), (screenWidth - px(side) * 2).coerceAtLeast(px(260)))
                marginStart = px(side)
                bottomMargin = px(if (m.compact) 24 else 48)
            }
        }
        root.findViewById<TextView>(R.id.heroTitle).apply {
            textSize = when { m.compact -> 28f; m.phone -> 32f; movies -> 44f; else -> 48f }
            maxLines = if (m.compact) 1 else 2
            setTextColor(Accent.TEXT)
        }
        root.findViewById<TextView>(R.id.heroDesc).apply {
            maxLines = if (m.compact) 2 else 3
            textSize = if (m.compact) 11f else 13f
            setTextColor(Accent.DIM)
        }
        root.findViewById<TextView>(R.id.heroBadge).apply {
            textSize = if (m.compact) 11f else 13f
            setTextColor(Accent.DIM)
        }
        root.findViewById<TextView>(R.id.heroDot).apply {
            textSize = if (m.compact) 9f else 11f
            setTextColor(Accent.AMBER)
        }
        root.findViewById<View>(R.id.heroChips).let {
            (it.layoutParams as LinearLayout.LayoutParams).topMargin = px(if (m.compact) 12 else 20)
        }
        // The hero logo sits at the card's top-right, small, the way the site shows a title mark.
        root.findViewById<View>(R.id.heroLogo).apply {
            (layoutParams as FrameLayout.LayoutParams).apply {
                width = px(if (m.compact) 84 else 104); height = px(if (m.compact) 54 else 66)
                gravity = android.view.Gravity.TOP or android.view.Gravity.END
                topMargin = px(nav) + px(24)
                marginEnd = px(if (m.compact) 20 else 48)
            }
        }
        root.findViewById<View>(R.id.heroLogoCaption).visibility = View.GONE
        root.findViewById<View>(R.id.heroMono).apply {
            (layoutParams as FrameLayout.LayoutParams).apply {
                gravity = android.view.Gravity.TOP or android.view.Gravity.END
                topMargin = px(nav) + px(24); marginEnd = px(if (m.compact) 20 else 56)
            }
        }
        root.findViewById<RecyclerView>(R.id.rows).setPadding(0, px(if (m.compact) 8 else 12), 0, px(if (m.compact) 8 else 10))
        (root.findViewById<View>(R.id.status).layoutParams as LinearLayout.LayoutParams).marginStart = px(side)
        root.requestLayout()
        return m
    }

    fun styleRow(row: View, compact: Boolean, dp: Float) {
        val title = row.findViewById<TextView>(R.id.rowTitle)
        title.textSize = if (compact) 16f else 21f
        title.setTextColor(Accent.TEXT)
        (title.parent as View).let { (it.layoutParams as ViewGroup.MarginLayoutParams).marginStart = ((if (compact) 20 else 48) * dp).toInt() }
        val rv = row.findViewById<RecyclerView>(R.id.rowList)
        rv.setPadding(((if (compact) 18 else 48) * dp).toInt(), (10 * dp).toInt(), (48 * dp).toInt(), (10 * dp).toInt())
    }

    fun styleTile(tile: View, poster: Boolean, compact: Boolean, dp: Float, landscape: Boolean = false) {
        val lp = tile.layoutParams as ViewGroup.MarginLayoutParams
        lp.setMargins((6 * dp).toInt(), (4 * dp).toInt(), (6 * dp).toInt(), (4 * dp).toInt())
        lp.marginStart = (10 * dp).toInt(); lp.marginEnd = (10 * dp).toInt()
        if (landscape) {
            lp.width = ((if (compact) 176 else 200) * dp).toInt()
            lp.height = ((if (compact) 99 else 113) * dp).toInt()
        } else if (!poster) {
            // 16:9 artwork with the copy on a scrim, matching the poster rails beside it.
            lp.width = ((if (compact) 168 else 216) * dp).toInt()
            lp.height = ((if (compact) 95 else 122) * dp).toInt()
            tile.findViewById<ImageView>(R.id.logo)?.layoutParams?.apply { width = ((if (compact) 46 else 62) * dp).toInt(); height = width }
            tile.findViewById<TextView>(R.id.name)?.textSize = if (compact) 11f else 13f
        } else {
            // 2:3 artwork with the copy inside it: the site's rail cards carry the kind eyebrow and the
            // title over a bottom scrim, and the tile is the artwork — no plate, no border, no caption row.
            val width = ((if (compact) 104 else 116) * dp).toInt()
            lp.width = width
            lp.height = (width * 1.5f).toInt()
            tile.findViewById<View>(R.id.posterContent)?.layoutParams?.width = width
            tile.findViewById<TextView>(R.id.posterRank)?.apply {
                textSize = if (compact) 43f else 62f
                layoutParams.height = (width * 1.5f).toInt()
            }
            tile.findViewById<TextView>(R.id.posterTitle)?.textSize = if (compact) 10f else 12f
            tile.findViewById<TextView>(R.id.posterMeta)?.textSize = if (compact) 8f else 9f
        }
    }
}
