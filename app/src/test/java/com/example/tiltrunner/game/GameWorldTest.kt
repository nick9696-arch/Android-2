package com.example.tiltrunner.game

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class GameWorldTest {

    private val frame = Frame()

    /** Einfacher Autopilot: hält die Mitte (inklusive Ausgleich der Fliehkraft) und springt vor jeder Lücke. */
    private fun pilot(w: GameWorld, jumping: Boolean = true, throttle: Float = -1f): GameInput {
        w.track.frame(w.s, frame)
        val centrifugal = if (frame.banked) 0f else frame.curvature * w.speed * w.speed
        // Zielspur: in Abzweigungen die weiterführende Spur, sonst die Mitte
        val target = w.track.targetU(w.s + 4f)
        val steer = ((centrifugal + 4f * (target - w.u)) / GameWorld.STEER_ACCEL).coerceIn(-1f, 1f)
        // Nächste Lücke, die auf der eigenen Spur liegt
        val next = w.track.gaps
            .filter { it.s1 > w.s && w.u >= it.u0 - 1f && w.u <= it.u1 + 1f }
            .minByOrNull { it.s0 }
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
