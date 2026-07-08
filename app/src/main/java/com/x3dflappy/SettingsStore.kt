package com.x3dflappy

import android.content.Context
import android.os.Build

/**
 * Tiny persistent store. This game has no settings menu (tap is only ever
 * flap, so there's no double-tap to open one) — it just remembers the high
 * score and whether to render side-by-side for the glasses.
 */
class SettingsStore(context: Context) {
    private val p = context.getSharedPreferences("x3dflappy", Context.MODE_PRIVATE)

    private val deviceText = listOf(
        Build.MODEL, Build.MANUFACTURER, Build.BRAND, Build.DEVICE, Build.PRODUCT
    ).joinToString(" ").lowercase()

    // FABLE_X3_STARTER_GUIDE gotcha #24: the X3 Pro reports Build.MODEL=ARGF20;
    // detect RayNeo hardware by manufacturer/brand/product instead.
    val isRayNeoX3 =
        "rayneo" in deviceText || "leiniao" in deviceText || "ffalcon" in deviceText ||
            ("x3" in deviceText && ("tcl" in deviceText || "falcon" in deviceText))

    val sbs get() = isRayNeoX3 // side-by-side for the glasses, single view elsewhere

    var highScore: Int
        get() = p.getInt("hi", 0)
        set(v) { if (v > highScore) p.edit().putInt("hi", v).apply() }

    var games: Int
        get() = p.getInt("games", 0)
        set(v) { p.edit().putInt("games", v).apply() }
}
