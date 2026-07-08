package com.x3dflappy.engine

import com.x3dflappy.SettingsStore
import com.x3dflappy.audio.Sfx
import kotlin.math.abs
import kotlin.random.Random

enum class GameState { TITLE, PLAYING, DEAD }

interface GameHost {
    fun sfx(id: Int, pitch: Float = 1f, vol: Float = 1f)
    fun startDrone()
    fun stopDrone()
}

class Gate(var z: Float, val gapCenter: Float, val gapHalf: Float) {
    var passed = false
    var hit = false
    var flash = 0f
}

class Particle {
    var x = 0f; var y = 0f; var z = 0f
    var vx = 0f; var vy = 0f; var vz = 0f
    var life = 0f; var maxLife = 1f
    var hue = 0f; var size = 6f
}

class Star(var x: Float, var y: Float, var z: Float)

/**
 * X3DFlappy game logic (no GL). A neon creature falls under gravity; each tap
 * flaps it upward. Vector-wireframe gates scroll toward the camera; fly through
 * the gaps. Endless, with a rising difficulty ramp.
 */
class Game(private val store: SettingsStore, private val host: GameHost) {

    companion object {
        const val GRAVITY = -24f
        const val DESCENT_MULT = 0.8f // rate of descent cut 20% after a flap (falling phase)
        const val FLAP_V = 9.0f
        const val BIRD_R = 0.42f
        const val FLOOR = -3.7f
        const val CEIL = 3.9f
        const val SPAWN_Z = 44f
        const val DESPAWN_Z = -12f
        const val WALL_HALF_Z = 0.7f
        const val CAM_Z = -7f
    }

    var state = GameState.TITLE
        private set
    var birdY = 0f; private set
    var birdVy = 0f; private set
    var birdTilt = 0f; private set
    var wingPhase = 0f; private set
    private var flapAnim = 0f

    val gates = ArrayList<Gate>()
    val particles = ArrayList<Particle>()
    private val pool = ArrayDeque<Particle>()
    val stars = ArrayList<Star>()

    var score = 0; private set
    var combo = 0; private set
    var highScore = 0; private set
    var newHigh = false; private set

    var time = 0f; private set
    val hue get() = (time * 0.07f) % 1f
    var shake = 0f; private set
    var flash = 0f; private set
    private var deadTime = 0f
    private var spawnTimer = 0f
    private var flapToggle = false
    private val rng = Random(System.nanoTime())

    fun boot() {
        highScore = store.highScore
        initStars()
        toTitle()
    }

    private fun initStars() {
        stars.clear()
        repeat(140) {
            stars.add(Star(rng.nextFloat() * 16f - 8f, rng.nextFloat() * 12f - 6f, rng.nextFloat() * (SPAWN_Z - CAM_Z) + CAM_Z))
        }
    }

    // --------------------------------------------------------------- input

    fun tap() {
        when (state) {
            GameState.TITLE -> { startGame(); flap() }
            GameState.PLAYING -> flap()
            GameState.DEAD -> if (deadTime > 0.7f) startGame()
        }
    }

    private fun flap() {
        birdVy = FLAP_V
        flapAnim = 1f
        flapToggle = !flapToggle
        host.sfx(if (flapToggle) Sfx.FLAP else Sfx.THRUST, 0.92f + rng.nextFloat() * 0.16f)
        // little burst behind the bird
        repeat(6) {
            val p = obtain()
            p.x = -0.2f + rng.nextFloat() * 0.2f; p.y = birdY - 0.1f; p.z = -0.3f
            p.vx = -1f - rng.nextFloat(); p.vy = -1f + rng.nextFloat() * 2f; p.vz = -3f - rng.nextFloat() * 2f
            p.life = 0.5f; p.maxLife = 0.5f; p.hue = hue; p.size = 6f
            particles.add(p)
        }
    }

    // -------------------------------------------------------------- update

    fun update(dt: Float) {
        time += dt
        shake = maxOf(0f, shake - dt * 3f)
        flash = maxOf(0f, flash - dt * 3f)
        flapAnim = maxOf(0f, flapAnim - dt * 5f)
        wingPhase += dt * (6f + flapAnim * 30f)
        updateStars(dt)
        updateParticles(dt)

        when (state) {
            GameState.TITLE -> {
                birdY = kotlin.math.sin(time * 2f) * 0.5f
                birdTilt = kotlin.math.cos(time * 2f) * 12f
            }
            GameState.PLAYING -> updatePlaying(dt)
            GameState.DEAD -> {
                deadTime += dt
                birdVy += GRAVITY * dt
                birdY += birdVy * dt
                birdTilt = (birdTilt - dt * 200f).coerceAtLeast(-90f)
                if (birdY < FLOOR - 2f) birdY = FLOOR - 2f
            }
        }
    }

    private fun updatePlaying(dt: Float) {
        // Each flap pops the bird up at full gravity, but the ensuing descent
        // is gentler — the rate of descent (while falling) is reduced by 20%.
        val g = if (birdVy <= 0f) GRAVITY * DESCENT_MULT else GRAVITY
        birdVy += g * dt
        birdY += birdVy * dt
        birdTilt = (birdVy * 4.5f).coerceIn(-70f, 35f)

        if (birdY + BIRD_R > CEIL) { birdY = CEIL - BIRD_R; birdVy = minOf(birdVy, 0f) }
        if (birdY - BIRD_R < FLOOR) { die(); return }

        // Spawn gates on a cadence that tightens with speed.
        val speed = gateSpeed()
        spawnTimer -= dt
        if (spawnTimer <= 0f) {
            spawnGate()
            spawnTimer = 13f / speed
        }

        var i = gates.size - 1
        while (i >= 0) {
            val g = gates[i]
            g.z -= speed * dt
            g.flash = maxOf(0f, g.flash - dt * 4f)
            // Scoring as the gate plane crosses the bird.
            if (!g.passed && g.z <= 0f) {
                g.passed = true
                score++
                combo++
                host.sfx(Sfx.SCORE, 1f + combo.coerceAtMost(8) * 0.03f)
                if (combo >= 3) host.sfx(Sfx.COMBO, 1f + combo * 0.02f, 0.7f)
                val edge = g.gapHalf - abs(birdY - g.gapCenter)
                if (edge < 0.5f) host.sfx(Sfx.NEARMISS)
                flash = 0.4f
                celebrate(g.gapCenter)
                if (score > highScore) { highScore = score; newHigh = true; store.highScore = score }
            }
            // Collision while crossing.
            if (!g.hit && abs(g.z) < WALL_HALF_Z + BIRD_R) {
                val top = g.gapCenter + g.gapHalf
                val bot = g.gapCenter - g.gapHalf
                if (birdY + BIRD_R > top || birdY - BIRD_R < bot) { g.hit = true; die(); return }
            }
            if (g.z < DESPAWN_Z) gates.removeAt(i)
            i--
        }
    }

    private fun gateSpeed() = (9f + score * 0.22f).coerceAtMost(17f)

    private fun spawnGate() {
        val gapHalf = (1.5f - score * 0.018f).coerceAtLeast(0.95f)
        val center = (rng.nextFloat() * 2f - 1f) * (CEIL - gapHalf - 0.6f)
        gates.add(Gate(SPAWN_Z, center, gapHalf))
        host.sfx(Sfx.WHOOSH, 0.8f + rng.nextFloat() * 0.3f, 0.5f)
    }

    private fun die() {
        state = GameState.DEAD
        deadTime = 0f
        shake = 12f
        flash = 1f
        host.sfx(Sfx.CRASH)
        host.sfx(Sfx.GAMEOVER, 1f, 0.8f)
        host.stopDrone()
        explode()
        store.games = store.games + 1
        if (newHigh) host.sfx(Sfx.HISCORE, 1f, 0.9f)
    }

    private fun startGame() {
        state = GameState.PLAYING
        birdY = 0f; birdVy = FLAP_V * 0.4f; birdTilt = 0f
        score = 0; combo = 0; newHigh = false
        gates.clear()
        spawnTimer = 0.8f
        deadTime = 0f
        host.sfx(Sfx.START)
        host.startDrone()
    }

    fun toTitle() {
        state = GameState.TITLE
        birdY = 0f; birdVy = 0f
        gates.clear()
        deadTime = 0f
        host.stopDrone()
    }

    // ---------------------------------------------------------- particles

    private fun obtain(): Particle = pool.removeFirstOrNull() ?: Particle()

    private fun celebrate(y: Float) {
        repeat(16) {
            val p = obtain()
            p.x = rng.nextFloat() * 2f - 1f; p.y = y + rng.nextFloat() * 2f - 1f; p.z = 0.2f
            val a = rng.nextFloat() * 6.2832f
            p.vx = kotlin.math.cos(a) * 3f; p.vy = kotlin.math.sin(a) * 3f; p.vz = -2f - rng.nextFloat() * 3f
            p.life = 0.8f; p.maxLife = 0.8f; p.hue = (hue + rng.nextFloat() * 0.3f) % 1f; p.size = 8f
            particles.add(p)
        }
    }

    private fun explode() {
        repeat(80) {
            val p = obtain()
            p.x = 0f; p.y = birdY; p.z = 0f
            val a = rng.nextFloat() * 6.2832f
            val sp = 3f + rng.nextFloat() * 7f
            p.vx = kotlin.math.cos(a) * sp; p.vy = kotlin.math.sin(a) * sp; p.vz = (rng.nextFloat() - 0.5f) * 8f
            p.life = 1.1f + rng.nextFloat() * 0.6f; p.maxLife = p.life; p.hue = rng.nextFloat(); p.size = 9f
            particles.add(p)
        }
    }

    private fun updateParticles(dt: Float) {
        var i = particles.size - 1
        while (i >= 0) {
            val p = particles[i]
            p.life -= dt
            if (p.life <= 0f) { particles.removeAt(i); pool.addLast(p) }
            else {
                p.vy += GRAVITY * 0.35f * dt
                p.x += p.vx * dt; p.y += p.vy * dt; p.z += p.vz * dt
                p.vx *= 0.98f; p.vy *= 0.98f
            }
            i--
        }
    }

    private fun updateStars(dt: Float) {
        val sp = if (state == GameState.PLAYING) gateSpeed() * 0.6f else 3f
        for (s in stars) {
            s.z -= sp * dt
            if (s.z < CAM_Z) {
                s.z = SPAWN_Z
                s.x = rng.nextFloat() * 16f - 8f
                s.y = rng.nextFloat() * 12f - 6f
            }
        }
    }
}
