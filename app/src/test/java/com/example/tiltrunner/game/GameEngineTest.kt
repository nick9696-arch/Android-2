package com.example.tiltrunner.game

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class GameEngineTest {

    private fun run(engine: GameEngine, seconds: Float, input: (GameEngine) -> GameInput) {
        var t = 0f
        while (t < seconds && !engine.gameOver) {
            engine.update(1f / 60f, input(engine))
            t += 1f / 60f
        }
    }

    @Test
    fun runningWithoutJumpingEndsInGap() {
        val e = GameEngine(Random(1))
        run(e, 60f) { GameInput(throttle = 0f) }
        assertTrue(e.gameOver)
    }

    @Test
    fun jumpingBeforeEachGapSurvives() {
        val e = GameEngine(Random(1))
        // Springt, sobald eine Lücke in Reichweite kommt, und bleibt dabei in der Mitte
        run(e, 30f) { eng ->
            val next = eng.gaps.firstOrNull { it.z1 > eng.z }
            val shouldJump = next != null && next.z0 - eng.z in 0f..(eng.speed * 0.2f)
            GameInput(jump = shouldJump)
        }
        assertFalse(e.gameOver)
        assertTrue(e.score > 100)
    }

    @Test
    fun steeringOffTheTrackIsFatal() {
        val e = GameEngine(Random(1))
        run(e, 20f) { GameInput(steer = 1f) }
        assertTrue(e.gameOver)
    }

    @Test
    fun tiltingForwardIsFasterThanBackward() {
        val fast = GameEngine(Random(1)).also { run(it, 2f) { GameInput(throttle = 1f) } }
        val slow = GameEngine(Random(1)).also { run(it, 2f) { GameInput(throttle = -1f) } }
        assertTrue(fast.speed > slow.speed)
    }
}
