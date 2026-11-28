package com.example.tiltrunner.game

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
    val uMin: Float,
    val uMax: Float,
    val banked: Boolean,
) {
    val dirX = cos(yaw)
    val dirZ = sin(yaw)
    val rightX = -sin(yaw)
    val rightZ = cos(yaw)
}

/**
 * Lücke in der Strecke: Bereich s0..s1 entlang der Strecke, u0..u1 quer (u > 0 = rechts von der Mitte).
 * full = die Bahn ist komplett unterbrochen: nur dann ist Springen möglich. Sonst muss man auf der
 * durchgehenden Bahn bleiben.
 */
class Gap(val s0: Float, val s1: Float, val u0: Float, val u1: Float, val full: Boolean = false)

/** Zwischen zwei Samples interpolierter Zustand der Strecke (wiederverwendbar, um Speicher zu sparen). */
class Frame {
    var x = 0f; var y = 0f; var z = 0f
    var dirX = 1f; var dirZ = 0f
    var rightX = 0f; var rightZ = 1f
    var bank = 0f
    var uMin = 0f
    var uMax = 0f
    var curvature = 0f
    var banked = false
}

/**
 * Doppeldecker-Abschnitt: Unter der oberen Strecke liegt von s0 bis s1 eine zweite Bahn, die etwas breiter ist.
 * Wer von der oberen Bahn fällt (über den Rand oder durch eine Lücke), landet darauf. Zum Ende hin
 * (ramp) steigt sie an und trifft wieder auf die obere Bahn.
 */
class Deck(val s0: Float, val s1: Float, val depth: Float, val ramp: Float, val overhang: Float)

/** Eine Abzweigung: von s0 bis s1; nur die Spur mit dem seitlichen Abstand survivorU führt weiter. */
class Fork(val s0: Float, val s1: Float, val survivorU: Float)

/**
 * Endlose, zufällig erzeugte Strecke aus Geraden, Kurven und Rondellen.
 * Die Strecke wird nach Bedarf vor dem Spieler erzeugt und hinter ihm verworfen.
 */
class Track(private val random: Random) {

    private val samples = ArrayList<Sample>()
    private var baseIdx = 0
    val gaps = ArrayList<Gap>()
    val forks = ArrayList<Fork>()
    val decks = ArrayList<Deck>()
    val lowGaps = ArrayList<Gap>() // Lücken in der unteren Bahn

    // Zustand des Generators
    private var gx = 0f
    private var gy = 0f
    private var gz = 0f
    private var gyaw = 0f
    private var gs = 0f
    private var curv = 0f
    private var slope = 0f
    private var bank = 0f
    private var edgeL = -BASE_HALF_WIDTH
    private var edgeR = BASE_HALF_WIDTH

    private var pieceLeft = 0f
    private var pieceCurv = 0f
    private var pieceSlope = 0f
    private var pieceBanked = false
    private var pieceEdgeL = -BASE_HALF_WIDTH
    private var pieceEdgeR = BASE_HALF_WIDTH
    private var pieceEdgeRate = NORMAL_EDGE_RATE
    private var pieceCount = 0
    private var afterRondell = false

    // Abzweigung: wird in drei aufeinanderfolgenden Stücken erzeugt (öffnen, Spuren, schließen)
    private var forkStage = 0
    private var forkLanes = 2
    private var forkSurvivor = 0
    private var forkStartS = 0f
    private var lastForkPiece = -100
    private var lastDeckPiece = -100

    init {
        samples += Sample(0f, 0f, 0f, 0f, 0f, 0f, 0f, -BASE_HALF_WIDTH, BASE_HALF_WIDTH, false)
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
        out.uMin = a.uMin + (b.uMin - a.uMin) * t
        out.uMax = a.uMax + (b.uMax - a.uMax) * t
        out.curvature = a.curvature + (b.curvature - a.curvature) * t
        out.banked = a.banked
    }

    fun uMinAt(s: Float): Float {
        val f = s / DS
        val i0 = floor(f).toInt()
        val t = f - i0
        val a = sampleAtIndex(i0)
        val b = sampleAtIndex(i0 + 1)
        return a.uMin + (b.uMin - a.uMin) * t
    }

    fun uMaxAt(s: Float): Float {
        val f = s / DS
        val i0 = floor(f).toInt()
        val t = f - i0
        val a = sampleAtIndex(i0)
        val b = sampleAtIndex(i0 + 1)
        return a.uMax + (b.uMax - a.uMax) * t
    }

    /**
     * Seitliche Position, auf der man sicher weiterkommt: in einer Abzweigung die weiterführende Spur,
     * sonst die Mitte des begehbaren Bereichs.
     */
    fun targetU(s: Float): Float {
        for (f in forks) if (s >= f.s0 && s <= f.s1) return f.survivorU
        return (uMinAt(s) + uMaxAt(s)) / 2f
    }

    // ------------------------------------------------------------ Untere Bahn

    private fun deckAt(s: Float): Deck? {
        for (d in decks) if (s >= d.s0 && s <= d.s1) return d
        return null
    }

    /** Wie tief die untere Bahn unter der oberen liegt (0 = keine untere Bahn). */
    fun depthAt(s: Float): Float {
        val d = deckAt(s) ?: return 0f
        return if (s <= d.s1 - d.ramp) d.depth else d.depth * ((d.s1 - s) / d.ramp)
    }

    private fun overhangAt(s: Float): Float {
        val d = deckAt(s) ?: return 0f
        return d.overhang * depthAt(s) / d.depth
    }

    fun lowerMinAt(s: Float): Float = uMinAt(s) - overhangAt(s)

    fun lowerMaxAt(s: Float): Float = uMaxAt(s) + overhangAt(s)

    /** true, wenn an der Stelle (s, u) die untere Bahn Boden hat. */
    fun lowerSolid(s: Float, u: Float): Boolean {
        if (deckAt(s) == null) return false
        if (u < lowerMinAt(s) || u > lowerMaxAt(s)) return false
        for (g in lowGaps) {
            if (s >= g.s0 && s <= g.s1 && u >= g.u0 && u <= g.u1) return false
        }
        return true
    }

    /** true, wenn an der Stelle (s, u) Boden ist (innerhalb der Breite und nicht in einer Lücke). */
    fun isSolid(s: Float, u: Float): Boolean {
        if (u < uMinAt(s) || u > uMaxAt(s)) return false
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
        forks.removeAll { it.s1 < s - KEEP_BEHIND }
        decks.removeAll { it.s1 < s - KEEP_BEHIND }
        lowGaps.removeAll { it.s1 < s - KEEP_BEHIND }
    }

    // ------------------------------------------------------------ Erzeugung

    private fun step() {
        curv += clampDelta(pieceCurv - curv, CURV_RATE * DS)
        slope += clampDelta(pieceSlope - slope, SLOPE_RATE * DS)
        val bankTarget = if (pieceBanked) (if (pieceCurv > 0f) BANK_SLOPE else -BANK_SLOPE) else 0f
        bank += clampDelta(bankTarget - bank, BANK_RATE * DS)
        edgeL += clampDelta(pieceEdgeL - edgeL, pieceEdgeRate * DS)
        edgeR += clampDelta(pieceEdgeR - edgeR, pieceEdgeRate * DS)

        gyaw += curv * DS
        gx += cos(gyaw) * DS
        gz += sin(gyaw) * DS
        gy += slope * DS
        gs += DS
        pieceLeft -= DS
        samples += Sample(gs, gx, gy, gz, gyaw, curv, bank, edgeL, edgeR, pieceBanked)
    }

    private fun newPiece() {
        pieceCount++
        val d = min(gs / 4000f, 1f)
        if (forkStage > 0) {
            nextForkStage(d)
            return
        }
        when {
            pieceCount == 1 -> normalPiece(45f, 0f, 0f, 0f, d)
            afterRondell -> {
                afterRondell = false
                normalPiece(25f, 0f, 0f, 0f, d)
            }
            pieceCount >= 4 && random.nextFloat() < 0.22f -> {
                // Rondell: breit, schräg, spiralförmig bergab. Hier gibt es Pause zum Durchatmen.
                val sign = if (random.nextBoolean()) 1f else -1f
                val radius = 6.5f
                val turns = 1.25f + random.nextFloat() * 0.5f
                val length = 2f * Math.PI.toFloat() * radius * turns
                afterRondell = true
                startPiece(length, sign / radius, -0.10f, true, -RONDELL_HALF_WIDTH, RONDELL_HALF_WIDTH, 0f, d, 0.1f)
            }
            pieceCount >= 5 && pieceCount - lastForkPiece >= 3 && random.nextFloat() < 0.2f -> beginFork(d)
            pieceCount >= 6 && pieceCount - lastForkPiece >= 3 && pieceCount - lastDeckPiece >= 4 &&
                random.nextFloat() < 0.2f -> deckPiece(d)
            random.nextFloat() < 0.4f -> {
                val length = 30f + random.nextFloat() * 40f
                normalPiece(length, 0f, -0.07f + random.nextFloat() * 0.12f, 1f, d)
            }
            else -> {
                val sign = if (random.nextBoolean()) 1f else -1f
                val radius = max(10f, 18f + random.nextFloat() * 14f - d * 8f)
                val angle = Math.toRadians((50 + random.nextInt(70)).toDouble()).toFloat()
                normalPiece(angle * radius, sign / radius, 0f, 0.5f, d)
            }
        }
    }

    private fun normalPiece(length: Float, curvature: Float, slopeTarget: Float, gapChance: Float, d: Float) =
        startPiece(length, curvature, slopeTarget, false, -BASE_HALF_WIDTH, BASE_HALF_WIDTH, gapChance, d, NORMAL_EDGE_RATE)

    private fun startPiece(
        length: Float, curvature: Float, slopeTarget: Float, banked: Boolean,
        edgeLeft: Float, edgeRight: Float, gapChance: Float, d: Float, edgeRate: Float,
    ) {
        pieceLeft = length
        pieceCurv = curvature
        pieceSlope = slopeTarget
        pieceBanked = banked
        pieceEdgeL = edgeLeft
        pieceEdgeR = edgeRight
        pieceEdgeRate = edgeRate
        if (gapChance > 0f) placeGaps(gs, gs + length, gapChance, d)
    }

    // ------------------------------------------------------------ Doppeldecker

    private fun deckPiece(d: Float) {
        lastDeckPiece = pieceCount
        val s0 = gs
        val length = DECK_LENGTH
        // Obere Bahn: gerade, mit normalen Lücken. Fällt man hinein, landet man auf der unteren Bahn.
        startPiece(length, 0f, 0f, false, -BASE_HALF_WIDTH, BASE_HALF_WIDTH, 0.7f, d, NORMAL_EDGE_RATE)
        decks += Deck(s0, s0 + length, DECK_DEPTH, DECK_RAMP, DECK_OVERHANG)
        // Untere Bahn: ein bis zwei durchgehende Lücken, nicht im ansteigenden Endstück
        var cursor = s0 + 16f
        val last = s0 + length - DECK_RAMP - 8f
        while (cursor < last) {
            val len = 2.2f + random.nextFloat() * 0.8f
            if (random.nextFloat() < 0.8f && cursor + len < last) {
                lowGaps += Gap(floor(cursor), ceil(cursor + len), -FULL_GAP_HALF_WIDTH, FULL_GAP_HALF_WIDTH, full = true)
            }
            cursor += len + 18f + random.nextFloat() * 8f
        }
    }

    // ------------------------------------------------------------ Abzweigung

    private fun laneCenter(lane: Int, lanes: Int): Float =
        (lane - (lanes - 1) / 2f) * (LANE_WIDTH + VOID_WIDTH)

    private fun forkHalfWidth(lanes: Int): Float = (lanes * LANE_WIDTH + (lanes - 1) * VOID_WIDTH) / 2f

    private fun beginFork(d: Float) {
        forkLanes = if (gs > 600f && random.nextBoolean()) 3 else 2
        forkSurvivor = random.nextInt(forkLanes)
        forkStartS = gs
        forkStage = 1
        val h = forkHalfWidth(forkLanes)
        // 1. Die Strecke wird breiter
        startPiece(20f, 0f, 0f, false, -h, h, 0f, d, FORK_EDGE_RATE)
    }

    private fun nextForkStage(d: Float) {
        val h = forkHalfWidth(forkLanes)
        if (forkStage == 1) {
            forkStage = 2
            // 2. Mehrere Spuren mit Lücken dazwischen. Nur eine Spur führt weiter, die anderen enden im Leeren.
            val length = 45f
            startPiece(length, 0f, 0f, false, -h, h, 0f, d, FORK_EDGE_RATE)
            val cut = gs + 28f
            for (i in 0 until forkLanes - 1) {
                val c = laneCenter(i, forkLanes)
                gaps += Gap(gs, gs + length, c + LANE_WIDTH / 2f, c + LANE_WIDTH / 2f + VOID_WIDTH)
            }
            for (i in 0 until forkLanes) {
                if (i == forkSurvivor) continue
                val c = laneCenter(i, forkLanes)
                val u0 = if (i == 0) -h - 2f else c - LANE_WIDTH / 2f
                val u1 = if (i == forkLanes - 1) h + 2f else c + LANE_WIDTH / 2f
                gaps += Gap(cut, gs + length + CLOSE_LENGTH, u0, u1) // bis zum Ende der Abzweigung
            }
        } else {
            forkStage = 0
            lastForkPiece = pieceCount // danach mindestens zwei normale Stücke, damit sich die Strecke wieder zentrieren kann
            // 3. Die Strecke verengt sich auf die überlebende Spur
            val c = laneCenter(forkSurvivor, forkLanes)
            val length = CLOSE_LENGTH
            startPiece(length, 0f, 0f, false, c - BASE_HALF_WIDTH, c + BASE_HALF_WIDTH, 0f, d, FORK_EDGE_RATE)
            forks += Fork(forkStartS, gs + length, c)
        }
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
            // Ganze Breite (großzügig, auch wenn die Strecke seitlich versetzt ist): springen ist Pflicht
            gaps += Gap(a, b, -FULL_GAP_HALF_WIDTH, FULL_GAP_HALF_WIDTH, full = true)
        } else {
            // Teil-Lücke: springen geht nicht, man muss auf der durchgehenden Bahn bleiben.
            // Es bleibt immer mindestens ein Streifen von gut einem Meter Breite frei.
            val w = 1.2f + random.nextFloat() * 0.4f
            val h = BASE_HALF_WIDTH
            val u0 = when (random.nextInt(3)) {
                0 -> -h + random.nextFloat() * 0.3f // links dicht am Rand: rechts ist frei
                1 -> h - w - random.nextFloat() * 0.3f // rechts dicht am Rand: links ist frei
                else -> -w / 2f // in der Mitte: beide Seiten sind frei
            }
            gaps += Gap(a, b, u0, u0 + w)
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
        private const val NORMAL_EDGE_RATE = 0.08f
        private const val FORK_EDGE_RATE = 0.15f
        private const val CLOSE_LENGTH = 25f
        private const val DECK_LENGTH = 80f
        private const val DECK_DEPTH = 4f
        private const val DECK_RAMP = 15f
        private const val DECK_OVERHANG = 1.5f
        private const val FULL_GAP_HALF_WIDTH = 8f
        const val LANE_WIDTH = 2.0f
        const val VOID_WIDTH = 0.8f
        private const val BANK_SLOPE = 0.5f
    }
}
