package com.example.tiltrunner.game

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * Ein Punkt der Streckenmittellinie (alle 0,5 m).
 * Welt: Y ist oben. Laufrichtung in der XZ-Ebene ist (cos yaw, sin yaw), "rechts" ist (-sin yaw, cos yaw).
 * curvature > 0 = Rechtskurve (1/Radius), bank = Höhenabfall pro Meter nach rechts (Schräglage).
 */
class Sample(
    val s: Float,
    val x: Float,
    val y: Float,
    val z: Float,
    val yaw: Float,
    val curvature: Float,
    val bank: Float,
    val halfWidth: Float,
    val banked: Boolean,
) {
    val dirX = cos(yaw)
    val dirZ = sin(yaw)
    val rightX = -sin(yaw)
    val rightZ = cos(yaw)
}

/** Lücke in der Strecke: Bereich s0..s1 entlang der Strecke, u0..u1 quer (u > 0 = rechts von der Mitte). */
class Gap(val s0: Float, val s1: Float, val u0: Float, val u1: Float)

/** Zwischen zwei Samples interpolierter Zustand der Strecke (wiederverwendbar, um Speicher zu sparen). */
class Frame {
    var x = 0f; var y = 0f; var z = 0f
    var dirX = 1f; var dirZ = 0f
    var rightX = 0f; var rightZ = 1f
    var bank = 0f
    var halfWidth = 0f
    var curvature = 0f
    var banked = false
}

/**
 * Endlose, zufällig erzeugte Strecke aus Geraden, Kurven und Rondellen.
 * Die Strecke wird nach Bedarf vor dem Spieler erzeugt und hinter ihm verworfen.
 */
class Track(private val random: Random) {

    private val samples = ArrayList<Sample>()
    private var baseIdx = 0
    val gaps = ArrayList<Gap>()

    // Zustand des Generators
    private var gx = 0f
    private var gy = 0f
    private var gz = 0f
    private var gyaw = 0f
    private var gs = 0f
    private var curv = 0f
    private var slope = 0f
    private var bank = 0f
    private var width = BASE_HALF_WIDTH

    private var pieceLeft = 0f
    private var pieceCurv = 0f
    private var pieceSlope = 0f
    private var pieceBanked = false
    private var pieceWidth = BASE_HALF_WIDTH
    private var pieceCount = 0
    private var afterRondell = false

    init {
        samples += Sample(0f, 0f, 0f, 0f, 0f, 0f, 0f, BASE_HALF_WIDTH, false)
        ensure(LOOKAHEAD)
    }

    val firstIndex: Int get() = baseIdx

    fun indexOf(s: Float): Int = floor(s / DS).toInt()

    fun sampleAtIndex(i: Int): Sample = samples[(i - baseIdx).coerceIn(0, samples.size - 1)]

    /** Interpolierter Streckenzustand an der Stelle s. */
    fun frame(s: Float, out: Frame) {
        val f = s / DS
        val i0 = floor(f).toInt()
        val t = f - i0
        val a = sampleAtIndex(i0)
        val b = sampleAtIndex(i0 + 1)
        out.x = a.x + (b.x - a.x) * t
        out.y = a.y + (b.y - a.y) * t
        out.z = a.z + (b.z - a.z) * t
        var dx = a.dirX + (b.dirX - a.dirX) * t
        var dz = a.dirZ + (b.dirZ - a.dirZ) * t
        val n = hypot(dx, dz)
        if (n > 1e-4f) { dx /= n; dz /= n }
        out.dirX = dx; out.dirZ = dz
        out.rightX = -dz; out.rightZ = dx
        out.bank = a.bank + (b.bank - a.bank) * t
        out.halfWidth = a.halfWidth + (b.halfWidth - a.halfWidth) * t
        out.curvature = a.curvature + (b.curvature - a.curvature) * t
        out.banked = a.banked
    }

    fun halfWidthAt(s: Float): Float {
        val f = s / DS
        val i0 = floor(f).toInt()
        val t = f - i0
        val a = sampleAtIndex(i0)
        val b = sampleAtIndex(i0 + 1)
        return a.halfWidth + (b.halfWidth - a.halfWidth) * t
    }

    /** true, wenn an der Stelle (s, u) Boden ist (innerhalb der Breite und nicht in einer Lücke). */
    fun isSolid(s: Float, u: Float): Boolean {
        if (abs(u) > halfWidthAt(s)) return false
        for (g in gaps) {
            if (s >= g.s0 && s <= g.s1 && u >= g.u0 && u <= g.u1) return false
        }
        return true
    }

    fun ensure(sMax: Float) {
        while (gs < sMax) {
            if (pieceLeft <= 0f) newPiece()
            step()
        }
    }

    /** Verwirft alles weit hinter s. */
    fun prune(s: Float) {
        val drop = indexOf(s - KEEP_BEHIND) - baseIdx
        if (drop > 100) {
            samples.subList(0, drop).clear()
            baseIdx += drop
        }
        gaps.removeAll { it.s1 < s - KEEP_BEHIND }
    }

    // ------------------------------------------------------------ Erzeugung

    private fun step() {
        curv += clampDelta(pieceCurv - curv, CURV_RATE * DS)
        slope += clampDelta(pieceSlope - slope, SLOPE_RATE * DS)
        val bankTarget = if (pieceBanked) (if (pieceCurv > 0f) BANK_SLOPE else -BANK_SLOPE) else 0f
        bank += clampDelta(bankTarget - bank, BANK_RATE * DS)
        width += clampDelta(pieceWidth - width, WIDTH_RATE * DS)

        gyaw += curv * DS
        gx += cos(gyaw) * DS
        gz += sin(gyaw) * DS
        gy += slope * DS
        gs += DS
        pieceLeft -= DS
        samples += Sample(gs, gx, gy, gz, gyaw, curv, bank, width, pieceBanked)
    }

    private fun newPiece() {
        pieceCount++
        val d = min(gs / 4000f, 1f)
        when {
            pieceCount == 1 -> startPiece(45f, 0f, 0f, false, BASE_HALF_WIDTH, gapChance = 0f, d = d)
            afterRondell -> {
                afterRondell = false
                startPiece(25f, 0f, 0f, false, BASE_HALF_WIDTH, gapChance = 0f, d = d)
            }
            pieceCount >= 4 && random.nextFloat() < 0.22f -> {
                // Rondell: breit, schräg, spiralförmig bergab. Hier gibt es Pause zum Durchatmen.
                val sign = if (random.nextBoolean()) 1f else -1f
                val radius = 6.5f
                val turns = 1.25f + random.nextFloat() * 0.5f
                val length = 2f * Math.PI.toFloat() * radius * turns
                afterRondell = true
                startPiece(length, sign / radius, -0.10f, true, RONDELL_HALF_WIDTH, gapChance = 0f, d = d)
            }
            random.nextFloat() < 0.4f -> {
                val length = 30f + random.nextFloat() * 40f
                startPiece(length, 0f, -0.07f + random.nextFloat() * 0.12f, false, BASE_HALF_WIDTH, gapChance = 1f, d = d)
            }
            else -> {
                val sign = if (random.nextBoolean()) 1f else -1f
                val radius = max(10f, 18f + random.nextFloat() * 14f - d * 8f)
                val angle = Math.toRadians((50 + random.nextInt(70)).toDouble()).toFloat()
                startPiece(angle * radius, sign / radius, 0f, false, BASE_HALF_WIDTH, gapChance = 0.5f, d = d)
            }
        }
    }

    private fun startPiece(
        length: Float, curvature: Float, slopeTarget: Float, banked: Boolean,
        halfWidth: Float, gapChance: Float, d: Float,
    ) {
        pieceLeft = length
        pieceCurv = curvature
        pieceSlope = slopeTarget
        pieceBanked = banked
        pieceWidth = halfWidth
        if (gapChance > 0f) placeGaps(gs, gs + length, gapChance, d)
    }

    private fun placeGaps(from: Float, to: Float, chance: Float, d: Float) {
        var s = from + 12f
        while (true) {
            val len = 2f + random.nextFloat() + d * 0.4f
            if (s + len > to - 8f) break
            if (random.nextFloat() < chance) addGap(s, s + len)
            s += len + 12f + random.nextFloat() * 9f - d * 3f
        }
    }

    private fun addGap(s0: Float, s1: Float) {
        // Auf ganze Meter runden, damit die Darstellung sauber an Segmentgrenzen liegt
        val a = floor(s0)
        val b = ceil(s1)
        if (random.nextFloat() < 0.55f) {
            gaps += Gap(a, b, -BASE_HALF_WIDTH - 2.5f, BASE_HALF_WIDTH + 2.5f) // ganze Breite: springen
        } else {
            val w = 1.2f + random.nextFloat() * 0.6f
            val u0 = -BASE_HALF_WIDTH + random.nextFloat() * (2f * BASE_HALF_WIDTH - w)
            gaps += Gap(a, b, u0, u0 + w) // Teil-Lücke: ausweichen oder springen
        }
    }

    private fun clampDelta(delta: Float, maxStep: Float) = delta.coerceIn(-maxStep, maxStep)

    companion object {
        const val DS = 0.5f
        const val BASE_HALF_WIDTH = 1.6f
        const val RONDELL_HALF_WIDTH = 2.6f
        const val LOOKAHEAD = 160f
        const val KEEP_BEHIND = 40f

        private const val CURV_RATE = 0.02f
        private const val SLOPE_RATE = 0.02f
        private const val BANK_RATE = 0.05f
        private const val WIDTH_RATE = 0.1f
        private const val BANK_SLOPE = 0.5f
    }
}
