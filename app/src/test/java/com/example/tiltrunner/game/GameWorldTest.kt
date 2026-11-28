package com.example.tiltrunner.game

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class GameWorldTest {

    private val frame = Frame()

    /**
     * Einfacher Autopilot: hält die Spur (inklusive Ausgleich der Fliehkraft), weicht Teil-Lücken aus
     * und springt vor jeder durchgehenden Lücke.
     */
    private fun pilot(w: GameWorld, jumping: Boolean = true, throttle: Float = -1f): GameInput {
        w.track.frame(w.s, frame)
        val centrifugal = if (frame.banked) 0f else frame.curvature * w.speed * w.speed

        // Zielspur: in Abzweigungen die weiterführende Spur, sonst die Mitte
        var target = w.track.targetU(w.s + 4f)

        // Teil-Lücke voraus, die auf der Zielspur liegt: auf der freien Seite vorbeifahren
        val partial = w.track.gaps
            .filter { !it.full && it.s1 > w.s && it.s0 - w.s < 14f && target in (it.u0 - 0.9f)..(it.u1 + 0.9f) }
            .minByOrNull { it.s0 }
        if (partial != null) {
            val lo = w.track.uMinAt(partial.s0)
            val hi = w.track.uMaxAt(partial.s0)
            val leftRoom = partial.u0 - lo
            val rightRoom = hi - partial.u1
            target = if (leftRoom >= rightRoom) (lo + partial.u0) / 2f else (partial.u1 + hi) / 2f
        }

        val steer = ((centrifugal + 4f * (target - w.u)) / GameWorld.STEER_ACCEL).coerceIn(-1f, 1f)
        val level = if (w.groundLevel < -0.5f) w.track.lowGaps else w.track.gaps
        val next = level.filter { it.full && it.s1 > w.s }.minByOrNull { it.s0 }
        val jump = jumping && next != null && next.s0 - w.s in 0f..2.5f
        return GameInput(steer = steer, throttle = throttle, jump = jump)
    }

    private fun run(w: GameWorld, seconds: Float, input: (GameWorld) -> GameInput) {
        var t = 0f
        while (t < seconds && !w.gameOver) {
            w.update(1f / 60f, input(w))
            t += 1f / 60f
        }
    }

    @Test
    fun autopilotSurvivesManySeeds() {
        for (seed in 1..8) {
            val w = GameWorld(Random(seed))
            run(w, 120f) { pilot(w) }
            assertFalse("Seed $seed ist bei s=${w.s} gestorben", w.gameOver)
            assertTrue("Seed $seed ist zu kurz gekommen: ${w.s}", w.s > 400f)
        }
    }

    @Test
    fun runningWithoutJumpingEndsInGap() {
        val w = GameWorld(Random(1))
        run(w, 120f) { pilot(w, jumping = false) }
        assertTrue(w.gameOver)
    }

    @Test
    fun jumpingIsOnlyPossibleBeforeFullGaps() {
        // In den ersten 30 m gibt es keine Lücke: der Sprung-Befehl muss wirkungslos bleiben
        val w = GameWorld(Random(1))
        repeat(60) { w.update(1f / 60f, GameInput(jump = true)) }
        assertTrue(w.y <= 0f)
        // Direkt vor einer durchgehenden Lücke geht der Sprung
        val full = w.track.gaps.first { it.full }
        while (full.s0 - w.s > 3f && !w.gameOver) w.update(1f / 60f, pilot(w, jumping = false))
        w.update(1f / 60f, GameInput(jump = true))
        assertTrue(w.y > 0f)
    }

    @Test
    fun partialGapsAlwaysLeaveAFreeStrip() {
        for (seed in 1..10) {
            val t = Track(Random(seed))
            t.ensure(3000f)
            for (g in t.gaps.filter { !it.full && it.u1 - it.u0 < 2f && it.u0 > -1.7f && it.u1 < 1.7f }) {
                val room = maxOf(g.u0 - (-1.6f), 1.6f - g.u1)
                assertTrue("Seed $seed: nur $room m frei", room >= 0.7f)
            }
        }
    }

    @Test
    fun steeringOffTheTrackIsFatal() {
        val w = GameWorld(Random(1))
        run(w, 20f) { GameInput(steer = 1f) }
        assertTrue(w.gameOver)
    }

    @Test
    fun tiltingForwardIsFasterThanBackward() {
        val fast = GameWorld(Random(1)).also { run(it, 2f) { _ -> GameInput(throttle = 1f) } }
        val slow = GameWorld(Random(1)).also { run(it, 2f) { _ -> GameInput(throttle = -1f) } }
        assertTrue(fast.speed > slow.speed)
    }

    @Test
    fun tooFastIntoTightCurveFliesOut() {
        // Voller Speed ohne Gegenlenken: spätestens in der ersten Kurve oder Lücke ist Schluss
        for (seed in 1..6) {
            val w = GameWorld(Random(seed))
            run(w, 90f) { GameInput(throttle = 1f, jump = false) }
            assertTrue("Seed $seed hat überlebt", w.gameOver)
        }
    }

    @Test
    fun trackIsContinuous() {
        val t = Track(Random(3))
        t.ensure(500f)
        for (i in 0 until 900) {
            val a = t.sampleAtIndex(i)
            val b = t.sampleAtIndex(i + 1)
            val dist = kotlin.math.hypot(b.x - a.x, b.z - a.z)
            assertTrue("Sprung bei $i: $dist", dist in 0.45f..0.55f)
        }
    }

    @Test
    fun forksExistAndDeadLanesEnd() {
        var forks = 0
        for (seed in 1..10) {
            val t = Track(Random(seed))
            t.ensure(2500f)
            forks += t.forks.size
            for (f in t.forks) {
                // Auf der weiterführenden Spur ist am Ende der Abzweigung Boden, daneben nicht
                assertTrue(t.isSolid(f.s1 - 5f, f.survivorU))
                val dead = if (f.survivorU > 0.1f) f.survivorU - 2.8f else f.survivorU + 2.8f
                assertFalse("Seed $seed: tote Spur hat Boden", t.isSolid(f.s1 - 5f, dead))
            }
        }
        assertTrue("Keine Abzweigung gefunden", forks > 0)
    }

    @Test
    fun decksExist() {
        var decks = 0
        for (seed in 1..10) {
            val t = Track(Random(seed))
            t.ensure(3000f)
            decks += t.decks.size
        }
        assertTrue("Kein Doppeldecker gefunden", decks > 0)
    }

    @Test
    fun droppingOffTheEdgeLandsOnTheLowerDeck() {
        // Erst ein Doppeldecker-Abschnitt suchen, dann am Anfang seitlich über den Rand rollen
        var tested = 0
        for (seed in 1..10) {
            val w = GameWorld(Random(seed))
            val t = w.track
            t.ensure(3000f)
            val deck = t.decks.firstOrNull { it.s0 < 900f } ?: continue
            // Bis kurz hinter den Anfang des Abschnitts normal fahren
            var time = 0f
            while (w.s < deck.s0 + 6f && !w.gameOver && time < 400f) {
                w.update(1f / 60f, pilot(w)); time += 1f / 60f
            }
            if (w.gameOver) continue
            // Dann nach rechts über den Rand rollen und fallen lassen
            time = 0f
            while (time < 3f && !w.gameOver) {
                w.update(1f / 60f, GameInput(throttle = -1f, steer = if (w.u < 2.2f) 1f else 0f)); time += 1f / 60f
            }
            if (!w.gameOver) {
                assertTrue("Seed $seed: Kugel nicht auf der unteren Bahn (y=${w.y})", w.y < -2f && w.grounded)
                tested++
            }
        }
        assertTrue("Kein Test lief durch", tested > 0)
    }

    @Test
    fun rondellExistsAndIsGapFree() {
        val t = Track(Random(5))
        t.ensure(1500f)
        var banked = 0
        for (i in 0 until 3000) {
            val sm = t.sampleAtIndex(i)
            if (sm.banked) {
                banked++
                assertTrue(t.gaps.none { sm.s in it.s0..it.s1 })
            }
        }
        assertTrue("Kein Rondell in 1500 m", banked > 0)
    }
}
