package com.aktv.app

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.View.MeasureSpec
import android.widget.FrameLayout

/**
 * The mobile edition's liquid-glass design language: deep violet ground, frosted translucent
 * cards with hairline light borders, rose/violet accent pills. On Android 12+ the frosted
 * surfaces get a real background blur; below that the translucency alone carries the look.
 */
internal object Glass {
    const val BG0 = "#0B0613"
    const val BG1 = "#1A0E2A"
    const val TEXT = "#F5F0E6"
    const val SUB = "#A7A2B8"
    const val ACCENT = "#E8B4B8"
    const val VIOLET = "#8E7BFF"

    var density = 2f

    fun dp(v: Int): Int = (v * density).toInt()

    fun ground(): GradientDrawable = GradientDrawable(
        GradientDrawable.Orientation.TOP_BOTTOM,
        intArrayOf(Color.parseColor(BG0), Color.parseColor(BG1))
    )

    /**
     * A frosted card: a top-lit vertical gloss, hairline light edge, rounded corners. The gradient
     * is the gloss — light falling off toward the bottom the way glass does.
     */
    fun card(radiusDp: Int = 18, fillAlpha: Int = 0x24): GradientDrawable =
        GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(
            Color.argb((fillAlpha + 18).coerceAtMost(0xFF), 0xFF, 0xFF, 0xFF),
            Color.argb(fillAlpha, 0xF2, 0xE8, 0xFF),
            Color.argb((fillAlpha * 3 / 4).coerceAtLeast(0), 0xC9, 0xB8, 0xFF)
        )).apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(radiusDp).toFloat()
            setStroke(dp(1).coerceAtLeast(1), Color.argb(0x55, 0xFF, 0xFF, 0xFF))
        }

    /** A filled accent pill, rose-to-violet, for primary actions. */
    fun accentPill(): GradientDrawable = GradientDrawable(
        GradientDrawable.Orientation.LEFT_RIGHT,
        intArrayOf(Color.parseColor(VIOLET), Color.parseColor("#C86B8A"))
    ).apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(24).toFloat()
    }

    /** Bottom-up scrim so text sits readable over artwork. */
    fun scrim(): GradientDrawable = GradientDrawable(
        GradientDrawable.Orientation.TOP_BOTTOM,
        intArrayOf(Color.TRANSPARENT, Color.argb(0xCC, 0x0B, 0x06, 0x13))
    )

    /**
     * A frosted-glass panel: a FrameLayout holding a blurred backing plus the content. The backing
     * must live in a frame — a match-parent view inside a LinearLayout eats the whole axis and
     * pushes the real children off-screen, which is how the hero info card vanished.
     */
    fun frostPanel(content: View, radiusDp: Int = 20, fillAlpha: Int = 0x30): FrameLayout {
        val panel = FrostPanelView(content.context)
        val bg = View(content.context).apply {
            background = card(radiusDp, fillAlpha)
            layoutParams = ViewGroup.LayoutParams(-1, -1)
        }
        frost(bg)
        panel.addView(bg)
        content.layoutParams = FrameLayout.LayoutParams(-1, -2)
        panel.addView(content)
        return panel
    }

    /** Frosted blur on 12+, a no-op below — the translucency still reads as glass. */
    fun frost(view: View, radiusDp: Int = 18) {
        if (Build.VERSION.SDK_INT >= 31) {
            runCatching {
                val r = dp(radiusDp).toFloat()
                view.setRenderEffect(android.graphics.RenderEffect.createBlurEffect(
                    r, r, android.graphics.Shader.TileMode.CLAMP))
            }
        }
    }

    /** Clip a view (usually artwork) to rounded corners. */
    fun round(view: View, radiusDp: Int) {
        view.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(radiusDp).toFloat()
            setColor(Color.argb(0x33, 0xFF, 0xFF, 0xFF))
        }
        view.clipToOutline = true
        view.outlineProvider = ViewOutlineProvider.BACKGROUND
    }
}


/**
 * A frame whose blurred backing fills the panel without taking part in measuring it. A plain
 * match-parent backing inside a wrap-content frame measures to the full available size — that
 * blew the tab bar up to the whole screen and squeezed Home to zero height. Here only the
 * content (children after the backing) is measured; the backing is then stretched to the result.
 */
internal class FrostPanelView(context: Context) : FrameLayout(context) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        var contentWidth = 0
        var contentHeight = 0
        for (i in 1 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == View.GONE) continue
            val params = child.layoutParams as? ViewGroup.MarginLayoutParams
            if (params != null) {
                measureChildWithMargins(child, widthMeasureSpec, 0, heightMeasureSpec, 0)
                contentWidth = maxOf(contentWidth, child.measuredWidth + params.leftMargin + params.rightMargin)
                contentHeight = maxOf(contentHeight, child.measuredHeight + params.topMargin + params.bottomMargin)
            } else {
                measureChild(child, widthMeasureSpec, heightMeasureSpec)
                contentWidth = maxOf(contentWidth, child.measuredWidth)
                contentHeight = maxOf(contentHeight, child.measuredHeight)
            }
        }
        setMeasuredDimension(
            View.resolveSize(contentWidth + paddingLeft + paddingRight, widthMeasureSpec),
            View.resolveSize(contentHeight + paddingTop + paddingBottom, heightMeasureSpec))
        if (childCount > 0) {
            getChildAt(0).measure(
                MeasureSpec.makeMeasureSpec(maxOf(0, measuredWidth - paddingLeft - paddingRight), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(maxOf(0, measuredHeight - paddingTop - paddingBottom), MeasureSpec.EXACTLY))
        }
    }
}
