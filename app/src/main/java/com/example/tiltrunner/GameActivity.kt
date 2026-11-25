package com.example.tiltrunner

import android.os.Bundle
import android.view.WindowManager
import com.badlogic.gdx.backends.android.AndroidApplication
import com.badlogic.gdx.backends.android.AndroidApplicationConfiguration
import com.example.tiltrunner.data.Settings
import com.example.tiltrunner.gl.TiltRunnerGame
import com.example.tiltrunner.sensor.MotionInput

/** Hostet das libGDX-Spiel. Sensoren laufen nur, solange die Activity sichtbar ist. */
class GameActivity : AndroidApplication() {

    private lateinit var motion: MotionInput

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val settings = Settings(this)
        motion = MotionInput(this).also { it.jumpThreshold = settings.jumpThreshold }

        val config = AndroidApplicationConfiguration().apply {
            // Die Sensoren liest MotionInput selbst, libGDX soll keine eigenen anmelden
            useAccelerometer = false
            useCompass = false
            useGyroscope = false
            useImmersiveMode = true
            numSamples = 2
        }
        initialize(TiltRunnerGame(motion, settings), config)
    }

    override fun onResume() {
        super.onResume()
        motion.start()
    }

    override fun onPause() {
        motion.stop()
        super.onPause()
    }
}
