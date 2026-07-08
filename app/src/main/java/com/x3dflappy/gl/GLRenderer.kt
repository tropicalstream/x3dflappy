package com.x3dflappy.gl

import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.util.Log
import com.x3dflappy.engine.Game
import com.x3dflappy.engine.GameState
import com.x3dflappy.engine.Gate
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * OpenGL ES 3.0 renderer. Everything is drawn as additive glowing lines and
 * points on black (black = transparent on the waveguide). The scene renders in
 * perspective; the HUD renders in an orthographic overlay. On the X3 the frame
 * is drawn once per eye into side-by-side viewports.
 */
class GLRenderer(private val game: Game) : GLSurfaceView.Renderer {

    var sbs = false

    private var program = 0
    private var aPos = 0
    private var aColor = 0
    private var uMVP = 0
    private var uPointSize = 0
    private var uPoint = 0

    private var width = 1
    private var height = 1
    private var lastNanos = 0L

    private val proj = FloatArray(16)
    private val view = FloatArray(16)
    private val mvp = FloatArray(16)
    private val ortho = FloatArray(16)
    private val model = FloatArray(16)
    private val vin = FloatArray(4)
    private val vout = FloatArray(4)
    private val rgb = FloatArray(3)

    private val lines = Batch(14000)
    private val stars = Batch(400)
    private val fx = Batch(2600)
    private val hud = Batch(4000)

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES30.glClearColor(0f, 0f, 0f, 1f)
        program = buildProgram(VERT, FRAG)
        aPos = GLES30.glGetAttribLocation(program, "aPos")
        aColor = GLES30.glGetAttribLocation(program, "aColor")
        uMVP = GLES30.glGetUniformLocation(program, "uMVP")
        uPointSize = GLES30.glGetUniformLocation(program, "uPointSize")
        uPoint = GLES30.glGetUniformLocation(program, "uPoint")
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE) // additive glow
        lastNanos = 0L
    }

    override fun onSurfaceChanged(gl: GL10?, w: Int, h: Int) {
        width = w; height = h
        Matrix.orthoM(ortho, 0, 0f, 640f, 480f, 0f, -1f, 1f)
    }

    override fun onDrawFrame(gl: GL10?) {
        val now = System.nanoTime()
        val dt = if (lastNanos == 0L) 0.016f else ((now - lastNanos) / 1e9f).coerceIn(0f, 0.05f)
        lastNanos = now
        game.update(dt)

        buildScene()
        buildHud()

        GLES30.glViewport(0, 0, width, height)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        GLES30.glUseProgram(program)

        // Camera follows the bird's height a little for a sense of altitude.
        val camY = game.birdY * 0.32f + 0.9f
        Matrix.setLookAtM(view, 0, 0f, camY, Game.CAM_Z, 0f, game.birdY * 0.4f, 14f, 0f, 1f, 0f)

        val eyes = if (sbs) 2 else 1
        val vw = if (sbs) width / 2 else width
        val aspect = vw.toFloat() / height.toFloat()
        Matrix.perspectiveM(proj, 0, 54f, aspect, 0.5f, 80f)
        Matrix.multiplyMM(mvp, 0, proj, 0, view, 0)

        for (e in 0 until eyes) {
            GLES30.glViewport(e * vw, 0, vw, height)
            GLES30.glUniformMatrix4fv(uMVP, 1, false, mvp, 0)
            GLES30.glUniform1f(uPoint, 0f)
            lines.draw(GLES30.GL_LINES)
            GLES30.glUniform1f(uPoint, 1f)
            GLES30.glUniform1f(uPointSize, 4f); stars.draw(GLES30.GL_POINTS)
            GLES30.glUniform1f(uPointSize, 13f); fx.draw(GLES30.GL_POINTS)
            GLES30.glUniformMatrix4fv(uMVP, 1, false, ortho, 0)
            GLES30.glUniform1f(uPoint, 0f)
            hud.draw(GLES30.GL_LINES)
        }
    }

    // ------------------------------------------------------- scene build

    private fun buildScene() {
        lines.reset(); stars.reset(); fx.reset()
        val h = game.hue
        buildTunnel(h)
        for (g in game.gates) buildGate(g, h)
        for (s in game.stars) {
            val k = ((s.z - Game.CAM_Z) / (Game.SPAWN_Z - Game.CAM_Z)).coerceIn(0f, 1f)
            val b = 0.4f + 0.6f * (1f - k)
            stars.v(s.x, s.y, s.z, b * 0.7f, b * 0.9f, b, 0.9f)
        }
        for (p in game.particles) {
            val k = (p.life / p.maxLife).coerceIn(0f, 1f)
            hsv(p.hue, 1f, 1f)
            fx.v(p.x, p.y, p.z, rgb[0], rgb[1], rgb[2], k)
        }
        buildBird(h)
    }

    private fun buildTunnel(h: Float) {
        val floorY = Game.FLOOR - 0.1f; val ceilY = Game.CEIL + 0.1f
        val near = Game.CAM_Z + 1.5f; val far = Game.SPAWN_Z
        val spacing = 4f
        val scroll = (game.time * 7f) % spacing
        var z = near + scroll
        while (z <= far) {
            hsv((h + z * 0.012f) % 1f, 0.9f, 0.5f)
            val a = 0.5f * (1f - ((z - near) / (far - near)))
            lines.line(-6f, floorY, z, 6f, floorY, z, rgb[0], rgb[1], rgb[2], a + 0.15f)
            lines.line(-6f, ceilY, z, 6f, ceilY, z, rgb[0], rgb[1], rgb[2], a + 0.15f)
            z += spacing
        }
        var x = -6f
        while (x <= 6f) {
            hsv((h + 0.5f) % 1f, 0.8f, 0.35f)
            lines.line(x, floorY, near, x, floorY, far, rgb[0], rgb[1], rgb[2], 0.28f)
            lines.line(x, ceilY, near, x, ceilY, far, rgb[0], rgb[1], rgb[2], 0.28f)
            x += 3f
        }
    }

    private fun buildGate(g: Gate, h: Float) {
        val z = g.z
        if (z < Game.CAM_Z + 0.5f || z > Game.SPAWN_Z + 1f) return
        val top = g.gapCenter + g.gapHalf
        val bot = g.gapCenter - g.gapHalf
        val x0 = -3.2f; val x1 = 3.2f
        val topY = Game.CEIL + 0.5f; val botY = Game.FLOOR - 0.5f
        val depthK = 1f - ((z - (Game.CAM_Z + 1f)) / (Game.SPAWN_Z)).coerceIn(0f, 1f)
        val bright = min(1f, 0.55f + depthK * 0.45f + g.flash)
        hsv((h + z * 0.02f) % 1f, 0.85f, bright)
        val r = rgb[0]; val gg = rgb[1]; val b = rgb[2]
        panel(x0, x1, top, topY, z, r, gg, b)
        panel(x0, x1, botY, bot, z, r, gg, b)
        // bright gap edges (the ring the bird flies through)
        lines.line(x0, top, z, x1, top, z, 1f, 1f, 1f, 0.9f)
        lines.line(x0, bot, z, x1, bot, z, 1f, 1f, 1f, 0.9f)
        lines.line(x0, bot, z, x0, top, z, r, gg, b, 0.8f)
        lines.line(x1, bot, z, x1, top, z, r, gg, b, 0.8f)
    }

    private fun panel(x0: Float, x1: Float, y0: Float, y1: Float, z: Float, r: Float, g: Float, b: Float) {
        val a = 0.7f
        lines.line(x0, y0, z, x1, y0, z, r, g, b, a)
        lines.line(x0, y1, z, x1, y1, z, r, g, b, a)
        lines.line(x0, y0, z, x0, y1, z, r, g, b, a)
        lines.line(x1, y0, z, x1, y1, z, r, g, b, a)
        // interior grid lines
        for (i in 1..2) {
            val yy = y0 + (y1 - y0) * i / 3f
            lines.line(x0, yy, z, x1, yy, z, r * 0.7f, g * 0.7f, b * 0.7f, 0.4f)
        }
        for (i in 1..3) {
            val xx = x0 + (x1 - x0) * i / 4f
            lines.line(xx, y0, z, xx, y1, z, r * 0.7f, g * 0.7f, b * 0.7f, 0.4f)
        }
    }

    private fun buildBird(h: Float) {
        Matrix.setIdentityM(model, 0)
        Matrix.translateM(model, 0, 0f, game.birdY, 0f)
        Matrix.rotateM(model, 0, game.birdTilt, 1f, 0f, 0f)
        val wl = sin(game.wingPhase) * 0.28f
        val nose = floatArrayOf(0f, 0f, 0.55f)
        val tail = floatArrayOf(0f, 0.06f, -0.55f)
        val lwt = floatArrayOf(-0.85f, wl, -0.1f)
        val rwt = floatArrayOf(0.85f, wl, -0.1f)
        val fin = floatArrayOf(0f, 0.5f, -0.35f)
        val bel = floatArrayOf(0f, -0.22f, -0.05f)
        hsv((h + 0.15f) % 1f, 0.7f, 1f)
        val r = rgb[0]; val g = rgb[1]; val b = rgb[2]
        birdLine(nose, tail, r, g, b); birdLine(nose, lwt, r, g, b); birdLine(nose, rwt, r, g, b)
        birdLine(tail, lwt, r, g, b); birdLine(tail, rwt, r, g, b); birdLine(lwt, rwt, r, g, b)
        birdLine(tail, fin, r, g, b); birdLine(nose, bel, r, g, b); birdLine(bel, lwt, r, g, b); birdLine(bel, rwt, r, g, b)
        // glowing eye
        world(nose)
        fx.v(vout[0], vout[1], vout[2], 1f, 1f, 1f, 1f)
    }

    private fun world(v: FloatArray) {
        vin[0] = v[0]; vin[1] = v[1]; vin[2] = v[2]; vin[3] = 1f
        Matrix.multiplyMV(vout, 0, model, 0, vin, 0)
    }

    private fun birdLine(a: FloatArray, b: FloatArray, r: Float, g: Float, bl: Float) {
        world(a); val ax = vout[0]; val ay = vout[1]; val az = vout[2]
        world(b); lines.line(ax, ay, az, vout[0], vout[1], vout[2], r, g, bl, 1f)
    }

    // ------------------------------------------------------- hud build

    private val sink = object : StrokeFont.LineSink {
        var cr = 1f; var cg = 1f; var cb = 1f; var ca = 1f
        override fun line(x0: Float, y0: Float, x1: Float, y1: Float) {
            hud.line(x0, y0, 0f, x1, y1, 0f, cr, cg, cb, ca)
        }
    }

    private fun text(s: String, cx: Float, y: Float, scale: Float, r: Float, g: Float, b: Float, a: Float = 1f, center: Boolean = true) {
        val x = if (center) cx - StrokeFont.width(s, scale) / 2f else cx
        sink.cr = r; sink.cg = g; sink.cb = b; sink.ca = a
        StrokeFont.draw(s, x, y, scale, sink)
    }

    private fun buildHud() {
        hud.reset()
        val pulse = 0.55f + 0.45f * sin(game.time * 4f)
        hsv(game.hue, 0.8f, 1f); val hr = rgb[0]; val hg = rgb[1]; val hb = rgb[2]
        when (game.state) {
            GameState.TITLE -> {
                text("FLAPTRON", 320f, 150f, 5.5f, hr, hg, hb)
                text("TAP TO FLAP", 320f, 250f, 2.4f, 1f, 1f, 1f, pulse)
                if (game.highScore > 0) text("BEST ${game.highScore}", 320f, 300f, 1.8f, 0.6f, 1f, 0.7f)
            }
            GameState.PLAYING -> {
                text("${game.score}", 320f, 70f, 5f, 1f, 1f, 1f)
            }
            GameState.DEAD -> {
                text("${game.score}", 320f, 90f, 6f, 1f, 0.5f, 0.4f)
                text("GAME OVER", 320f, 180f, 3.2f, 1f, 0.35f, 0.3f)
                text("BEST ${game.highScore}", 320f, 240f, 2.2f, 0.6f, 1f, 0.7f)
                if (game.newHigh) text("NEW BEST!", 320f, 290f, 2.6f, 1f, 1f, 0.4f, pulse)
                text("TAP", 320f, 380f, 2.2f, 1f, 1f, 1f, pulse * 0.9f)
            }
        }
    }

    // ------------------------------------------------------- gl helpers

    private fun hsv(hh: Float, s: Float, v: Float) {
        val h6 = ((hh % 1f + 1f) % 1f) * 6f
        val i = h6.toInt(); val f = h6 - i
        val p = v * (1 - s); val q = v * (1 - s * f); val t = v * (1 - s * (1 - f))
        when (i % 6) {
            0 -> { rgb[0] = v; rgb[1] = t; rgb[2] = p }
            1 -> { rgb[0] = q; rgb[1] = v; rgb[2] = p }
            2 -> { rgb[0] = p; rgb[1] = v; rgb[2] = t }
            3 -> { rgb[0] = p; rgb[1] = q; rgb[2] = v }
            4 -> { rgb[0] = t; rgb[1] = p; rgb[2] = v }
            else -> { rgb[0] = v; rgb[1] = p; rgb[2] = q }
        }
    }

    private fun buildProgram(vs: String, fs: String): Int {
        val v = compile(GLES30.GL_VERTEX_SHADER, vs)
        val f = compile(GLES30.GL_FRAGMENT_SHADER, fs)
        val p = GLES30.glCreateProgram()
        GLES30.glAttachShader(p, v); GLES30.glAttachShader(p, f); GLES30.glLinkProgram(p)
        val ok = IntArray(1); GLES30.glGetProgramiv(p, GLES30.GL_LINK_STATUS, ok, 0)
        if (ok[0] == 0) Log.e("X3DFlappy", "link: " + GLES30.glGetProgramInfoLog(p))
        return p
    }

    private fun compile(type: Int, src: String): Int {
        val s = GLES30.glCreateShader(type)
        GLES30.glShaderSource(s, src); GLES30.glCompileShader(s)
        val ok = IntArray(1); GLES30.glGetShaderiv(s, GLES30.GL_COMPILE_STATUS, ok, 0)
        if (ok[0] == 0) Log.e("X3DFlappy", "compile: " + GLES30.glGetShaderInfoLog(s))
        return s
    }

    /** Interleaved (pos xyz + color rgba) client-side vertex batch. */
    inner class Batch(maxVerts: Int) {
        private val fb: FloatBuffer = ByteBuffer.allocateDirect(maxVerts * 7 * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer()
        private val cap = maxVerts
        var count = 0; private set

        fun reset() { fb.position(0); count = 0 }

        fun v(x: Float, y: Float, z: Float, r: Float, g: Float, b: Float, a: Float) {
            if (count >= cap) return
            fb.put(x); fb.put(y); fb.put(z); fb.put(r); fb.put(g); fb.put(b); fb.put(a); count++
        }

        fun line(x0: Float, y0: Float, z0: Float, x1: Float, y1: Float, z1: Float, r: Float, g: Float, b: Float, a: Float) {
            v(x0, y0, z0, r, g, b, a); v(x1, y1, z1, r, g, b, a)
        }

        fun draw(mode: Int) {
            if (count == 0) return
            fb.position(0)
            GLES30.glVertexAttribPointer(aPos, 3, GLES30.GL_FLOAT, false, 28, fb)
            GLES30.glEnableVertexAttribArray(aPos)
            fb.position(3)
            GLES30.glVertexAttribPointer(aColor, 4, GLES30.GL_FLOAT, false, 28, fb)
            GLES30.glEnableVertexAttribArray(aColor)
            GLES30.glDrawArrays(mode, 0, count)
        }
    }

    companion object {
        private const val VERT = """#version 300 es
        in vec3 aPos;
        in vec4 aColor;
        uniform mat4 uMVP;
        uniform float uPointSize;
        out vec4 vColor;
        void main() {
            gl_Position = uMVP * vec4(aPos, 1.0);
            gl_PointSize = uPointSize;
            vColor = aColor;
        }"""

        private const val FRAG = """#version 300 es
        precision mediump float;
        in vec4 vColor;
        uniform float uPoint;
        out vec4 fragColor;
        void main() {
            if (uPoint > 0.5) {
                vec2 d = gl_PointCoord - vec2(0.5);
                float r2 = dot(d, d);
                if (r2 > 0.25) discard;
                fragColor = vec4(vColor.rgb, vColor.a * (1.0 - r2 * 4.0));
            } else {
                fragColor = vColor;
            }
        }"""
    }
}
