package com.x3dflappy

import android.app.Activity
import android.opengl.GLSurfaceView
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import com.x3dflappy.audio.Sfx
import com.x3dflappy.engine.Game
import com.x3dflappy.engine.GameHost
import com.x3dflappy.gl.GLRenderer
import kotlin.math.max

/**
 * X3DFlappy. Tap = flap (and drop/shoot in the relevant bonuses). In the glide
 * and Galaxian bonuses a trackpad SWIPE steers the bird up/down/left/right.
 * There is deliberately no settings menu / double-tap. The temple click arrives
 * as a KEY event; touchscreen taps work too.
 */
class MainActivity : Activity(), GameHost {

    private lateinit var store: SettingsStore
    private lateinit var sfx: Sfx
    private lateinit var game: Game
    private lateinit var glView: GLSurfaceView
    private lateinit var renderer: GLRenderer

    // Guard so one physical tap (which can arrive as both KEY and touch) flaps once.
    private var lastTap = 0L
    private var downX = 0f
    private var downY = 0f
    private var downT = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = SettingsStore(this)
        sfx = Sfx(this).also { it.loadAsync() }
        game = Game(store, this)
        renderer = GLRenderer(game).also { it.sbs = store.sbs }

        glView = object : GLSurfaceView(this) {}.apply {
            setEGLContextClientVersion(3)
            preserveEGLContextOnPause = true
            setRenderer(renderer)
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        }
        setContentView(glView)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemBars()
        game.boot()
    }

    // ------------------------------------------------------------ GameHost

    override fun sfx(id: Int, pitch: Float, vol: Float) = sfx.play(id, pitch, vol)
    override fun startDrone() = sfx.startDrone()
    override fun stopDrone() = sfx.stopDrone()

    // --------------------------------------------------------------- input

    private fun flap() {
        val now = SystemClock.uptimeMillis()
        if (now - lastTap < 60) return // de-dupe KEY+touch of the same physical tap
        lastTap = now
        glView.queueEvent { game.tap() } // run on the GL thread
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val isTap = event.keyCode == KeyEvent.KEYCODE_BUTTON_A ||
            event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
            event.keyCode == KeyEvent.KEYCODE_ENTER ||
            event.keyCode == KeyEvent.KEYCODE_SPACE
        if (isTap) {
            if (event.action == KeyEvent.ACTION_UP) flap()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        // Ignore the left temple volume pad.
        if (ev.device?.name?.contains("cyttsp6", ignoreCase = true) == true) return true
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = ev.x; downY = ev.y; downT = SystemClock.uptimeMillis() }
            MotionEvent.ACTION_UP -> {
                val dx = ev.x - downX; val dy = ev.y - downY
                val dist = kotlin.math.hypot(dx, dy)
                val thresh = max(48f, 0.09f * resources.displayMetrics.widthPixels)
                if (dist >= thresh) {
                    // Swipe: navigate (only the glide/galaxian bonuses use it).
                    // Horizontal is inverted vs the X3 trackpad's axis (vertical is not).
                    val dir = if (kotlin.math.abs(dx) >= kotlin.math.abs(dy)) { if (dx > 0) 2 else 3 } else { if (dy < 0) 0 else 1 }
                    glView.queueEvent { game.swipe(dir) }
                } else if (SystemClock.uptimeMillis() - downT <= 320) {
                    flap()
                }
            }
        }
        return true
    }

    // ------------------------------------------------------------ lifecycle

    override fun onResume() {
        super.onResume()
        hideSystemBars()
        glView.onResume()
    }

    override fun onPause() {
        sfx.stopDrone()
        glView.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        sfx.release()
        super.onDestroy()
    }

    private fun hideSystemBars() {
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let {
                it.hide(WindowInsets.Type.systemBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility =
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        }
    }
}
