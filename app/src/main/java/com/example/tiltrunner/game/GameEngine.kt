package com.example.tiltrunner.game

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/** Eine Lücke in der Strecke: z0..z1 in Laufrichtung, x0..x1 quer (Strecke ist -1..1 breit). */
data class Gap(val z0: Float, val z1: Float, val x0: Float, val x1: Float)

/** Eingaben pro Frame. steer und throttle liegen jeweils in -1..1. */
data class GameInput(val steer: Float = 0f, val throttle: Float = 0f, val jump: Boolean = false)

/**
 * Reine Spiellogik ohne Android-Abhängigkeiten.
 * Koordinaten: z = Laufrichtung (Meter), x = quer (-1..1 = Streckenbreite), h = Höhe der Kugel.
 */
class GameEngine(private val random: Random = Random.Default) {

    var z = 0f; private set
    var x = 0f; private set
    var h = 0f; private set
    var speed = MIN_SPEED; private set
    var falling = false; private set
    var gameOver = false; private set
    val gaps = ArrayList<Gap>()

    private var vh = 0f
    private var nextGapZ = FIRST_GAP_Z

    val score: Int get() = z.toInt()

    /** Wird im Lauf des Spiels größer und macht alles schneller und dichter. */
    val difficulty: Float get() = min(z / 2500f, 0.45f)

    init {
        generateAhead()
    }

    fun update(dt: Float, input: GameInput) {
        if (gameOver) return
        val d = min(dt, 0.05f) // Aussetzer (z. B. nach Pause) nicht in einen Riesenschritt verwandeln

        // Tempo: Neigung nach vorn = schneller, zurück = langsamer.
        val scale = 1f + difficulty
        val target = lerp(MIN_SPEED, MAX_SPEED, (input.throttle.coerceIn(-1f, 1f) + 1f) / 2f) * scale
        speed += (target - speed) * min(1f, d * 3f)
        z += speed * d

        if (!falling) {
            x += input.steer.coerceIn(-1f, 1f) * LATERAL_SPEED * d
            if (input.jump && h <= 0f) {
                vh = JUMP_VELOCITY
            }
        }

        // Vertikale Bewegung
        vh += GRAVITY * d
        h += vh * d
        if (h <= 0f && !falling) {
            if (isOverVoid()) {
                falling = true
            } else {
                h = 0f
                vh = 0f
            }
        }
        // Wer in der Luft ist, darf über Lücken und neben die Strecke fliegen,
        // entscheidend ist nur die Landung.
        if (falling && h < FALL_DEATH_DEPTH) gameOver = true

        generateAhead()
        gaps.removeAll { it.z1 < z - 10f }
    }

    private fun isOverVoid(): Boolean {
        if (abs(x) > TRACK_HALF) return true
        return gaps.any { z in it.z0..it.z1 && x in it.x0..it.x1 }
    }

    private fun generateAhead() {
        while (nextGapZ < z + LOOKAHEAD) {
            val length = 2f + speedForGeneration() * 0.12f + random.nextFloat() * 0.8f
            val width = if (random.nextFloat() < 0.6f) 2.6f else 1.0f + random.nextFloat() * 0.5f
            val x0: Float
            val x1: Float
            if (width >= 2.6f) {
                x0 = -1.3f; x1 = 1.3f // ganze Breite: springen ist Pflicht
            } else {
                x0 = -1.3f + random.nextFloat() * (2.6f - width) // Teil-Lücke: ausweichen oder springen
                x1 = x0 + width
            }
            gaps += Gap(nextGapZ, nextGapZ + length, x0, x1)
            val spacing = max(7f, 18f - difficulty * 20f) + random.nextFloat() * 8f
            nextGapZ += length + spacing
        }
    }

    private fun speedForGeneration() = max(speed, MIN_SPEED)

    companion object {
        const val TRACK_HALF = 1f
        const val MIN_SPEED = 6f
        const val MAX_SPEED = 20f
        const val LATERAL_SPEED = 2.4f
        const val GRAVITY = -22f
        const val JUMP_VELOCITY = 9f
        const val FALL_DEATH_DEPTH = -4f
        const val FIRST_GAP_Z = 30f
        const val LOOKAHEAD = 100f

        private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t
    }
}
