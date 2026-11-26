package com.example.tiltrunner.game

import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/** Eingaben pro Frame. steer und throttle liegen jeweils in -1..1. */
data class GameInput(val steer: Float = 0f, val throttle: Float = 0f, val jump: Boolean = false)

/**
 * Reine Spiellogik ohne Android- und libGDX-Abhängigkeiten.
 * Die Kugel bewegt sich entlang der Strecke: s = Strecke, u = seitlicher Abstand von der Mitte
 * (positiv = rechts), h = Höhe über dem Boden.
 */
class GameWorld(random: Random = Random.Default) {

    val track = Track(random)

    var s = 0f; private set
    var u = 0f; private set
    var vu = 0f; private set
    var h = 0f; private set
    var speed = MIN_SPEED; private set
    var falling = false; private set
    var gameOver = false; private set
    var inRondell = false; private set
    var rollAngle = 0f; private set
    var points = 0f; private set

    private var vh = 0f
    private var gravity = FALL_GRAVITY
    private val f = Frame()

    val score: Int get() = points.toInt()

    /** Wächst mit der Strecke: macht die Kugel etwas schneller. */
    val difficulty: Float get() = min(s / 4000f, 0.3f)

    fun update(dt: Float, input: GameInput) {
        if (gameOver) return
        val d = min(dt, 0.05f) // Aussetzer (z. B. nach Pause) nicht in einen Riesenschritt verwandeln

        track.frame(s, f)
        inRondell = f.banked

        // Tempo: Neigung nach vorn = schneller, zurück = langsamer. Im Rondell rollt die Kugel von allein.
        val throttle = input.throttle.coerceIn(-1f, 1f)
        val target = if (inRondell) {
            RONDELL_SPEED
        } else {
            (MIN_SPEED + (MAX_SPEED - MIN_SPEED) * (throttle + 1f) / 2f) * (1f + difficulty)
        }
        speed += (target - speed) * min(1f, d * SPEED_RESPONSE)
        s += speed * d
        // Schneller fahren bringt mehr Punkte
        points += speed * speed * d / 10f
        rollAngle += speed * d / BALL_RADIUS

        if (!falling) {
            // Seitlich: Neigung beschleunigt, Dämpfung bremst. In Kurven drückt die Fliehkraft nach außen
            // (Rechtskurve = nach links); im schrägen Rondell hebt sich das auf und die Schale hält die Kugel mittig.
            val steerGain = if (inRondell) 0.3f else 1f
            var a = input.steer.coerceIn(-1f, 1f) * STEER_ACCEL * steerGain - vu * LATERAL_DAMPING
            a -= if (inRondell) u * 2f else f.curvature * speed * speed
            vu += a * d
            u += vu * d

            if (input.jump && h <= 0f && jumpAllowed()) {
                // Sprungweite ist unabhängig vom Tempo, damit Lücken immer gleich überspringbar sind
                val airTime = JUMP_DISTANCE / max(speed, MIN_SPEED)
                vh = 4f * JUMP_HEIGHT / airTime
                gravity = 8f * JUMP_HEIGHT / (airTime * airTime)
            }
        }

        vh -= gravity * d
        h += vh * d
        if (h <= 0f && !falling) {
            if (!track.isSolid(s, u)) {
                falling = true
                gravity = FALL_GRAVITY
            } else {
                h = 0f
                vh = 0f
            }
        }
        if (falling && h < FALL_DEATH_DEPTH) gameOver = true

        track.ensure(s + Track.LOOKAHEAD)
        track.prune(s)
    }

    /** Springen geht nur kurz vor einer komplett unterbrochenen Bahn, nicht bei Teil-Lücken oder Abzweigungen. */
    private fun jumpAllowed(): Boolean {
        val range = max(JUMP_ALLOW_RANGE, speed * 0.7f)
        return track.gaps.any { it.full && it.s1 > s && it.s0 - s <= range }
    }

    companion object {
        const val MIN_SPEED = 5f
        const val MAX_SPEED = 21f
        const val SPEED_RESPONSE = 6f
        const val RONDELL_SPEED = 15f
        const val STEER_ACCEL = 28f
        const val LATERAL_DAMPING = 5.5f
        const val JUMP_ALLOW_RANGE = 14f
        const val JUMP_DISTANCE = 9f
        const val JUMP_HEIGHT = 1.1f
        const val FALL_GRAVITY = 25f
        const val FALL_DEATH_DEPTH = -6f
        const val BALL_RADIUS = 0.35f
    }
}
