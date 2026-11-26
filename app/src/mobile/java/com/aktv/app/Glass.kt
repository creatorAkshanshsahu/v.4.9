package com.aktv.app

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.View
import android.view.ViewOutlineProvider

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

    /** A frosted card: translucent white fill, hairline light edge, rounded corners. */
    fun card(radiusDp: Int = 18, fillAlpha: Int = 0x24): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(radiusDp).toFloat()
            setColor(Color.argb(fillAlpha, 0xFF, 0xFF, 0xFF))
            setStroke(dp(1).coerceAtLeast(1), Color.argb(0x40, 0xFF, 0xFF, 0xFF))
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
