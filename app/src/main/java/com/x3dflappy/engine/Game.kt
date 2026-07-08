package com.x3dflappy.engine

import com.x3dflappy.SettingsStore
import com.x3dflappy.audio.Sfx
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

enum class GameState { TITLE, PLAYING, BONUS_GLIDE, BONUS_CITY, DEAD }

interface GameHost {
    fun sfx(id: Int, pitch: Float = 1f, vol: Float = 1f)
    fun startDrone()
    fun stopDrone()
}

class Gate(var z: Float, val gapCenter: Float, var gapHalf: Float) {
    var passed = false
    var hit = false
    var flash = 0f
    var blasted = false
}

class Particle {
    var x = 0f; var y = 0f; var z = 0f
    var vx = 0f; var vy = 0f; var vz = 0f
    var life = 0f; var maxLife = 1f
    var hue = 0f; var size = 6f
}

class Star(var x: Float, var y: Float, var z: Float)

/** Bird "dropping" — blasts walls open (power-up) or bombs targets (city bonus). */
class Dropping {
    var x = 0f; var y = 0f; var z = 0f
    var vy = 0f
}

/** A collectible neon berry (10 = an extra life). */
class Berry(var x: Float, var y: Float, var z: Float, val hue: Float) { var collected = false }

/** A soft cloud puff for the glide bonus. */
class Cloud(var x: Float, var y: Float, var z: Float, val r: Float)

/** A ground target for the city bonus; bomb it for a berry. */
class Target(var z: Float, val x: Float, val topY: Float) { var hit = false }

/**
 * X3DFlappy game logic (no GL). Regular levels are 10 walls; clearing one leads
 * to a bonus level (glide-through-clouds, or bomb-the-city), alternating and
 * varied each time. Berries are collected in openings and bonuses; every 10
 * grants an extra life. Bonus levels can't hurt the bird.
 */
class Game(private val store: SettingsStore, private val host: GameHost) {

    companion object {
        const val GRAVITY = -24f
        const val DESCENT_MULT = 0.8f
        const val FLAP_V = 9.0f
        const val BIRD_R = 0.42f
        const val FLOOR = -3.7f
        const val CEIL = 3.9f
        const val SPAWN_Z = 44f
        const val DESPAWN_Z = -12f
        const val WALL_HALF_Z = 0.7f
        const val CAM_Z = -7f
        const val POWER_SECS = 5f
        const val DROP_INTERVAL = 0.22f
        const val DROP_VZ = 9f
        const val LEVEL_WALLS = 10
        const val LIVES_START = 3
        const val BERRIES_PER_LIFE = 10
        const val BERRY_R = 0.55f
        const val GLIDE_SECS = 15f
        const val CITY_TARGETS = 20
    }

    var state = GameState.TITLE; private set
    var birdY = 0f; private set
    var birdVy = 0f; private set
    var birdTilt = 0f; private set
    var wingPhase = 0f; private set
    private var flapAnim = 0f

    val gates = ArrayList<Gate>()
    val particles = ArrayList<Particle>()
    private val pool = ArrayDeque<Particle>()
    val stars = ArrayList<Star>()
    val droppings = ArrayList<Dropping>()
    private val dropPool = ArrayDeque<Dropping>()
    val berries = ArrayList<Berry>()
    val clouds = ArrayList<Cloud>()
    val targets = ArrayList<Target>()

    var powerupActive = false; private set
    var powerupTimer = 0f; private set
    private var dropTimer = 0f
    private var gatesSincePower = 0
    private var powerThreshold = 4

    var score = 0; private set
    var combo = 0; private set
    var highScore = 0; private set
    var newHigh = false; private set

    var lives = LIVES_START; private set
    var berryCount = 0; private set
    var level = 1; private set
    var wallsThisLevel = 0; private set
    val wallsRemaining get() = (LEVEL_WALLS - wallsThisLevel).coerceAtLeast(0)
    var bonusTimer = 0f; private set
    var cityHits = 0; private set
    var invulnTimer = 0f; private set

    private var wallsSpawnedThisLevel = 0
    private var berryGateA = -1
    private var berryGateB = -1
    private var bonusIndex = 0
    private var citySpawnTimer = 0f
    private var cityTargetsSpawned = 0
    private var berrySpawnTimer = 0f

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
        repeat(140) { stars.add(Star(rng.nextFloat() * 16f - 8f, rng.nextFloat() * 12f - 6f, rng.nextFloat() * (SPAWN_Z - CAM_Z) + CAM_Z)) }
    }

    // --------------------------------------------------------------- input

    fun tap() {
        when (state) {
            GameState.TITLE -> { startGame(); flap() }
            GameState.PLAYING -> flap()
            GameState.BONUS_GLIDE -> glideFlap()
            GameState.BONUS_CITY -> dropBomb()
            GameState.DEAD -> if (deadTime > 0.7f) startGame()
        }
    }

    private fun flap() {
        birdVy = FLAP_V
        flapAnim = 1f
        flapToggle = !flapToggle
        host.sfx(if (flapToggle) Sfx.FLAP else Sfx.THRUST, 0.92f + rng.nextFloat() * 0.16f)
        repeat(6) {
            val p = obtain()
            p.x = -0.2f + rng.nextFloat() * 0.2f; p.y = birdY - 0.1f; p.z = -0.3f
            p.vx = -1f - rng.nextFloat(); p.vy = -1f + rng.nextFloat() * 2f; p.vz = -3f - rng.nextFloat() * 2f
            p.life = 0.5f; p.maxLife = 0.5f; p.hue = hue; p.size = 6f
            particles.add(p)
        }
    }

    private fun glideFlap() {
        birdVy = FLAP_V * 0.55f
        flapAnim = 1f
        host.sfx(Sfx.FLAP, 1.1f, 0.7f)
    }

    private fun dropBomb() {
        val d = dropPool.removeFirstOrNull() ?: Dropping()
        d.x = 0f; d.y = birdY - 0.2f; d.z = 0.2f; d.vy = -2f
        droppings.add(d)
        host.sfx(Sfx.THRUST, 0.8f, 0.7f)
    }

    // -------------------------------------------------------------- update

    fun update(dt: Float) {
        time += dt
        shake = maxOf(0f, shake - dt * 3f)
        flash = maxOf(0f, flash - dt * 3f)
        flapAnim = maxOf(0f, flapAnim - dt * 5f)
        wingPhase += dt * (6f + flapAnim * 30f)
        if (invulnTimer > 0f) invulnTimer -= dt
        updateStars(dt)
        updateParticles(dt)

        when (state) {
            GameState.TITLE -> { birdY = sin(time * 2f) * 0.5f; birdTilt = cos(time * 2f) * 12f }
            GameState.PLAYING -> updatePlaying(dt)
            GameState.BONUS_GLIDE -> updateGlide(dt)
            GameState.BONUS_CITY -> updateCity(dt)
            GameState.DEAD -> {
                deadTime += dt
                birdVy += GRAVITY * dt; birdY += birdVy * dt
                birdTilt = (birdTilt - dt * 200f).coerceAtLeast(-90f)
                if (birdY < FLOOR - 2f) birdY = FLOOR - 2f
            }
        }
    }

    // ----------------------------------------------------- regular level

    private fun updatePlaying(dt: Float) {
        val g = if (birdVy <= 0f) GRAVITY * DESCENT_MULT else GRAVITY
        birdVy += g * dt
        birdY += birdVy * dt
        birdTilt = (birdVy * 4.5f).coerceIn(-70f, 35f)

        if (birdY + BIRD_R > CEIL) { birdY = CEIL - BIRD_R; birdVy = minOf(birdVy, 0f) }
        if (birdY - BIRD_R < FLOOR) {
            if (invulnTimer > 0f) { birdY = FLOOR + BIRD_R; birdVy = 0f } else { loseLife(); return }
        }

        val speed = gateSpeed()
        if (wallsSpawnedThisLevel < LEVEL_WALLS) {
            spawnTimer -= dt
            if (spawnTimer <= 0f) { spawnGate(); spawnTimer = 13f / speed }
        }

        var i = gates.size - 1
        while (i >= 0) {
            val gt = gates[i]
            gt.z -= speed * dt
            gt.flash = maxOf(0f, gt.flash - dt * 4f)
            if (!gt.passed && gt.z <= 0f) {
                gt.passed = true
                score++; combo++; wallsThisLevel++
                host.sfx(Sfx.SCORE, 1f + combo.coerceAtMost(8) * 0.03f)
                if (combo >= 3) host.sfx(Sfx.COMBO, 1f + combo * 0.02f, 0.7f)
                if (gt.gapHalf - abs(birdY - gt.gapCenter) < 0.5f) host.sfx(Sfx.NEARMISS)
                flash = 0.4f; celebrate(gt.gapCenter)
                if (score > highScore) { highScore = score; newHigh = true; store.highScore = score }
                gatesSincePower++
                if (!powerupActive && gatesSincePower >= powerThreshold) startPowerup()
                if (wallsThisLevel >= LEVEL_WALLS) { endRegularLevel(); return }
            }
            if (!gt.hit && invulnTimer <= 0f && abs(gt.z) < WALL_HALF_Z + BIRD_R) {
                val top = gt.gapCenter + gt.gapHalf
                val bot = gt.gapCenter - gt.gapHalf
                // Crash only if NO part of the bird is inside the opening.
                if (birdY - BIRD_R >= top || birdY + BIRD_R <= bot) { gt.hit = true; loseLife(); return }
            }
            if (gt.z < DESPAWN_Z) gates.removeAt(i)
            i--
        }

        updateBerries(dt, speed)
        updatePowerup(dt)
    }

    private fun gateSpeed() = (9f + score * 0.22f).coerceAtMost(17f)

    private fun spawnGate() {
        val gapHalf = (1.5f - level * 0.06f).coerceAtLeast(0.95f)
        val center = (rng.nextFloat() * 2f - 1f) * (CEIL - gapHalf - 0.6f)
        val gt = Gate(SPAWN_Z, center, gapHalf)
        gates.add(gt)
        // Two random walls per level hide a berry in their opening.
        if (wallsSpawnedThisLevel == berryGateA || wallsSpawnedThisLevel == berryGateB) {
            val by = center + (rng.nextFloat() * 2f - 1f) * (gapHalf - 0.35f)
            berries.add(Berry(0f, by, SPAWN_Z, (hue + rng.nextFloat() * 0.4f) % 1f))
        }
        wallsSpawnedThisLevel++
        host.sfx(Sfx.WHOOSH, 0.8f + rng.nextFloat() * 0.3f, 0.5f)
    }

    private fun updateBerries(dt: Float, speed: Float) {
        var i = berries.size - 1
        while (i >= 0) {
            val b = berries[i]
            b.z -= speed * dt
            if (!b.collected && abs(b.z) < 1f && abs(birdY - b.y) < BIRD_R + BERRY_R) {
                b.collected = true; collectBerry(b.x, b.y, b.z)
            }
            if (b.collected || b.z < DESPAWN_Z) berries.removeAt(i)
            i--
        }
    }

    // ---------------------------------------------------------- power-up

    private fun startPowerup() {
        powerupActive = true; powerupTimer = POWER_SECS; dropTimer = 0f; gatesSincePower = 0
        host.sfx(Sfx.POWER); host.sfx(Sfx.HISCORE, 1.2f, 0.5f); celebrate(birdY)
    }

    private fun updatePowerup(dt: Float) {
        if (powerupActive) {
            powerupTimer -= dt; dropTimer -= dt
            if (dropTimer <= 0f) { emitDropping(); dropTimer = DROP_INTERVAL }
            if (powerupTimer <= 0f) { powerupActive = false; powerupTimer = 0f; powerThreshold = 3 + rng.nextInt(4) }
        }
        var i = droppings.size - 1
        while (i >= 0) {
            val d = droppings[i]
            d.vy += GRAVITY * 0.5f * dt; d.y += d.vy * dt; d.z += DROP_VZ * dt
            var consumed = false
            for (gt in gates) {
                if (!gt.blasted && !gt.passed && abs(d.z - gt.z) < 1.2f && d.z <= SPAWN_Z) { blastGate(gt); consumed = true; break }
            }
            if (consumed || d.z > SPAWN_Z || d.y < FLOOR - 3f) { droppings.removeAt(i); dropPool.addLast(d) }
            i--
        }
    }

    private fun emitDropping() {
        val d = dropPool.removeFirstOrNull() ?: Dropping()
        d.x = 0f; d.y = birdY - 0.2f; d.z = 0.2f; d.vy = -1.5f
        droppings.add(d); host.sfx(Sfx.CHIRP, 0.7f, 0.5f)
    }

    private fun blastGate(gt: Gate) {
        gt.blasted = true
        gt.gapHalf = (gt.gapHalf * 2f).coerceAtMost(CEIL - 0.4f)
        gt.flash = 1.6f; host.sfx(Sfx.ZAP); celebrate(gt.gapCenter)
    }

    // ------------------------------------------------------ bonus: glide

    private fun startGlide() {
        state = GameState.BONUS_GLIDE
        bonusTimer = GLIDE_SECS
        berrySpawnTimer = 0f
        berries.clear(); clouds.clear(); droppings.clear()
        birdY = 0f; birdVy = 2f
        repeat(16) { clouds.add(Cloud(rng.nextFloat() * 10f - 5f, rng.nextFloat() * 7f - 3.5f, rng.nextFloat() * (SPAWN_Z - 2f) + 2f, 0.8f + rng.nextFloat() * 1.6f)) }
        host.sfx(Sfx.POWER, 0.9f); host.startDrone()
    }

    private fun updateGlide(dt: Float) {
        bonusTimer -= dt
        // Microgravity glide.
        birdVy += GRAVITY * 0.12f * dt
        birdY += birdVy * dt
        birdTilt = (birdVy * 3f).coerceIn(-30f, 30f)
        if (birdY + BIRD_R > CEIL) { birdY = CEIL - BIRD_R; birdVy = 0f }
        if (birdY - BIRD_R < FLOOR) { birdY = FLOOR + BIRD_R; birdVy = 0f }

        val speed = 7f
        berrySpawnTimer -= dt
        if (berrySpawnTimer <= 0f && bonusTimer > 2f) {
            berries.add(Berry(0f, rng.nextFloat() * 6f - 3f, SPAWN_Z, rng.nextFloat()))
            berrySpawnTimer = 0.55f + rng.nextFloat() * 0.25f
        }
        var i = berries.size - 1
        while (i >= 0) {
            val b = berries[i]
            b.z -= speed * dt
            if (!b.collected && abs(b.z) < 1.2f && abs(birdY - b.y) < BIRD_R + BERRY_R) { b.collected = true; collectBerry(b.x, b.y, b.z) }
            if (b.collected || b.z < DESPAWN_Z) berries.removeAt(i)
            i--
        }
        for (c in clouds) { c.z -= speed * dt; if (c.z < CAM_Z) { c.z = SPAWN_Z; c.x = rng.nextFloat() * 10f - 5f; c.y = rng.nextFloat() * 7f - 3.5f } }
        if (bonusTimer <= 0f) nextRegularLevel()
    }

    // ------------------------------------------------------- bonus: city

    private fun startCity() {
        state = GameState.BONUS_CITY
        targets.clear(); droppings.clear(); berries.clear()
        cityTargetsSpawned = 0; citySpawnTimer = 0.5f; cityHits = 0
        birdY = 1.5f; birdVy = 0f
        host.sfx(Sfx.POWER, 1.1f); host.startDrone()
    }

    private fun updateCity(dt: Float) {
        birdY = sin(time * 1.4f) * 1.6f
        birdTilt = cos(time * 1.4f) * 10f
        val speed = 8f

        if (cityTargetsSpawned < CITY_TARGETS) {
            citySpawnTimer -= dt
            if (citySpawnTimer <= 0f) {
                targets.add(Target(SPAWN_Z, (rng.nextFloat() * 2f - 1f) * 0.6f, FLOOR + 0.5f + rng.nextFloat() * 2.5f))
                cityTargetsSpawned++; citySpawnTimer = 0.85f
            }
        }
        var t = targets.size - 1
        while (t >= 0) { val tg = targets[t]; tg.z -= speed * dt; if (tg.z < DESPAWN_Z) targets.removeAt(t); t-- }

        var i = droppings.size - 1
        while (i >= 0) {
            val d = droppings[i]
            d.vy += GRAVITY * dt; d.y += d.vy * dt
            var consumed = false
            for (tg in targets) {
                if (!tg.hit && abs(d.z - tg.z) < 1.6f && d.y <= tg.topY + 0.4f) {
                    tg.hit = true; cityHits++; collectBerry(tg.x, tg.topY, tg.z); host.sfx(Sfx.ZAP); consumed = true; break
                }
            }
            if (consumed || d.y < FLOOR - 1.5f) { droppings.removeAt(i); dropPool.addLast(d) }
            i--
        }
        if (cityTargetsSpawned >= CITY_TARGETS && targets.isEmpty()) nextRegularLevel()
    }

    // ---------------------------------------------------- level flow

    private fun endRegularLevel() {
        gates.clear(); berries.clear(); droppings.clear(); powerupActive = false
        host.sfx(Sfx.HISCORE, 1f, 0.8f)
        bonusIndex++
        if (bonusIndex % 2 == 1) startGlide() else startCity()
    }

    private fun nextRegularLevel() {
        level++
        beginRegularLevel()
        host.sfx(Sfx.START)
    }

    private fun beginRegularLevel() {
        state = GameState.PLAYING
        gates.clear(); berries.clear(); droppings.clear(); clouds.clear(); targets.clear()
        wallsThisLevel = 0; wallsSpawnedThisLevel = 0
        pickBerryGates()
        powerupActive = false; powerupTimer = 0f; gatesSincePower = 0
        powerThreshold = 3 + rng.nextInt(4)
        birdY = 0f; birdVy = FLAP_V * 0.4f; birdTilt = 0f
        invulnTimer = 1.0f
        spawnTimer = 0.8f
        host.startDrone()
    }

    private fun pickBerryGates() {
        berryGateA = rng.nextInt(LEVEL_WALLS)
        do { berryGateB = rng.nextInt(LEVEL_WALLS) } while (berryGateB == berryGateA)
    }

    private fun startGame() {
        level = 1; lives = LIVES_START; berryCount = 0
        score = 0; combo = 0; newHigh = false; bonusIndex = 0
        deadTime = 0f
        beginRegularLevel()
        host.sfx(Sfx.START)
    }

    fun toTitle() {
        state = GameState.TITLE
        birdY = 0f; birdVy = 0f
        gates.clear(); berries.clear(); droppings.clear(); clouds.clear(); targets.clear()
        powerupActive = false; powerupTimer = 0f
        deadTime = 0f
        host.stopDrone()
    }

    // --------------------------------------------------- lives & berries

    private fun collectBerry(x: Float, y: Float, z: Float) {
        berryCount++
        if (berryCount % BERRIES_PER_LIFE == 0) { lives++; host.sfx(Sfx.HISCORE, 1.3f, 0.9f) }
        else host.sfx(Sfx.SCORE, 1.4f)
        repeat(10) {
            val p = obtain()
            p.x = x; p.y = y; p.z = z
            val a = rng.nextFloat() * 6.2832f; val sp = 2f + rng.nextFloat() * 3f
            p.vx = cos(a) * sp; p.vy = sin(a) * sp; p.vz = -1f - rng.nextFloat() * 2f
            p.life = 0.6f; p.maxLife = 0.6f; p.hue = (hue + rng.nextFloat() * 0.3f) % 1f; p.size = 7f
            particles.add(p)
        }
    }

    private fun loseLife() {
        lives--
        shake = 12f; flash = 1f
        explode()
        if (lives <= 0) { die(); return }
        host.sfx(Sfx.CRASH, 1.1f, 0.9f)
        birdY = 0f; birdVy = FLAP_V * 0.4f; birdTilt = 0f
        invulnTimer = 1.6f
    }

    private fun die() {
        state = GameState.DEAD; deadTime = 0f
        powerupActive = false; droppings.clear()
        host.sfx(Sfx.CRASH); host.sfx(Sfx.GAMEOVER, 1f, 0.8f); host.stopDrone()
        store.games = store.games + 1
        if (newHigh) host.sfx(Sfx.HISCORE, 1f, 0.9f)
    }

    // ---------------------------------------------------------- particles

    private fun obtain(): Particle = pool.removeFirstOrNull() ?: Particle()

    private fun celebrate(y: Float) {
        repeat(16) {
            val p = obtain()
            p.x = rng.nextFloat() * 2f - 1f; p.y = y + rng.nextFloat() * 2f - 1f; p.z = 0.2f
            val a = rng.nextFloat() * 6.2832f
            p.vx = cos(a) * 3f; p.vy = sin(a) * 3f; p.vz = -2f - rng.nextFloat() * 3f
            p.life = 0.8f; p.maxLife = 0.8f; p.hue = (hue + rng.nextFloat() * 0.3f) % 1f; p.size = 8f
            particles.add(p)
        }
    }

    private fun explode() {
        repeat(80) {
            val p = obtain()
            p.x = 0f; p.y = birdY; p.z = 0f
            val a = rng.nextFloat() * 6.2832f; val sp = 3f + rng.nextFloat() * 7f
            p.vx = cos(a) * sp; p.vy = sin(a) * sp; p.vz = (rng.nextFloat() - 0.5f) * 8f
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
            else { p.vy += GRAVITY * 0.35f * dt; p.x += p.vx * dt; p.y += p.vy * dt; p.z += p.vz * dt; p.vx *= 0.98f; p.vy *= 0.98f }
            i--
        }
    }

    private fun updateStars(dt: Float) {
        val sp = if (state == GameState.PLAYING) gateSpeed() * 0.6f else 4f
        for (s in stars) {
            s.z -= sp * dt
            if (s.z < CAM_Z) { s.z = SPAWN_Z; s.x = rng.nextFloat() * 16f - 8f; s.y = rng.nextFloat() * 12f - 6f }
        }
    }
}
