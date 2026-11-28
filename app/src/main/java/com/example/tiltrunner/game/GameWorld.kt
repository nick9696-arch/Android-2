package com.example.tiltrunner.game

import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/** Eingaben pro Frame. steer und throttle liegen jeweils in -1..1. */
data class GameInput(val steer: Float = 0f, val throttle: Float = 0f, val jump: Boolean = false)

/**
 * Reine Spiellogik ohne Android- und libGDX-Abhängigkeiten.
 * Die Kugel bewegt sich entlang der Strecke: s = Strecke, u = seitlicher Abstand von der Mitte
 * (positiv = rechts), y = Höhe relativ zur oberen Bahn (0 = auf der oberen Bahn, negativ = tiefer).
 */
class GameWorld(random: Random = Random.Default) {

    val track = Track(random)

    var s = 0f; private set
    var u = 0f; private set
    var vu = 0f; private set
    var y = 0f; private set
    var speed = MIN_SPEED; private set
    var gameOver = false; private set
    var inRondell = false; private set
    var rollAngle = 0f; private set
    var points = 0f; private set

    /** true, wenn die Kugel auf einer Bahn rollt (nicht springt oder fällt). */
    var grounded = true; private set

    /** Höhe der Bahn, auf der die Kugel zuletzt stand (0 = obere Bahn). */
    var groundLevel = 0f; private set

    /** Höhe der nächsten Bahn direkt unter der Kugel, oder NaN, wenn dort nichts mehr ist. */
    var supportBelow = Float.NaN; private set

    /** 0..1: wie weit die Kugel unter alle Bahnen ins Leere gefallen ist. */
    var deathProgress = 0f; private set

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

        // Seitlich: Neigung beschleunigt, Dämpfung bremst. In Kurven drückt die Fliehkraft nach außen
        // (Rechtskurve = nach links); im schrägen Rondell hebt sich das auf und die Schale hält die Kugel mittig.
        val steerGain = if (inRondell) 0.3f else 1f
        var a = input.steer.coerceIn(-1f, 1f) * STEER_ACCEL * steerGain - vu * LATERAL_DAMPING
        a -= if (inRondell) u * 2f else f.curvature * speed * speed
        vu += a * d
        u += vu * d

        if (input.jump && grounded && jumpAllowed()) {
            // Sprungweite ist unabhängig vom Tempo, damit Lücken immer gleich überspringbar sind
            val airTime = JUMP_DISTANCE / max(speed, MIN_SPEED)
            vh = 4f * JUMP_HEIGHT / airTime
            gravity = 8f * JUMP_HEIGHT / (airTime * airTime)
            grounded = false
        }

        // Vertikal: Schwerkraft, dann Landung auf der obersten Bahn, die die Kugel von oben durchquert hat
        val prevY = y
        vh -= gravity * d
        y += vh * d
        if (vh <= 0f) {
            var best = Float.NaN
            if (track.isSolid(s, u)) best = considerSupport(best, 0f, prevY)
            if (track.lowerSolid(s, u)) best = considerSupport(best, -track.depthAt(s), prevY)
            if (!best.isNaN()) {
                y = best
                vh = 0f
                grounded = true
                groundLevel = best
                gravity = FALL_GRAVITY
            } else {
                grounded = false
            }
        } else {
            grounded = false
        }

        updateSupportBelow()

        // Ganz ins Leere gefallen: unter alle Bahnen
        val lowest = -track.depthAt(s)
        deathProgress = ((lowest - y) / -FALL_DEATH_DEPTH).coerceIn(0f, 1f)
        if (y < lowest + FALL_DEATH_DEPTH) gameOver = true

        track.ensure(s + Track.LOOKAHEAD)
        track.prune(s)
    }

    /** Eine Bahn auf Höhe level trägt die Kugel, wenn sie von oben (höchstens STEP darunter) kam und jetzt darunter ist. */
    private fun considerSupport(best: Float, level: Float, prevY: Float): Float {
        if (level > prevY + STEP || level < y) return best
        return if (best.isNaN() || level > best) level else best
    }

    private fun updateSupportBelow() {
        var best = Float.NaN
        if (track.isSolid(s, u) && 0f <= y + 0.05f) best = 0f
        if (track.lowerSolid(s, u)) {
            val lvl = -track.depthAt(s)
            if (lvl <= y + 0.05f && (best.isNaN() || lvl > best)) best = lvl
        }
        supportBelow = best
    }

    /** Springen geht nur kurz vor einer komplett unterbrochenen Bahn, nicht bei Teil-Lücken oder Abzweigungen. */
    private fun jumpAllowed(): Boolean {
        val range = max(JUMP_ALLOW_RANGE, speed * 0.7f)
        val list = if (groundLevel < -0.5f) track.lowGaps else track.gaps
        return list.any { it.full && it.s1 > s && it.s0 - s <= range }
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
        const val STEP = 0.35f
        const val BALL_RADIUS = 0.22f
    }
}
