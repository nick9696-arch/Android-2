package com.example.tiltrunner.sensor

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * Liest Neigung und Ruck aus den Sensoren.
 *
 * - Neigung: Schwerkraftvektor (TYPE_GRAVITY, Fallback: Tiefpass auf dem Beschleunigungssensor).
 * - Ruck: lineare Beschleunigung ohne Schwerkraft, projiziert auf "oben" (Gegenrichtung der Schwerkraft).
 *   So zählt der Ruck nach oben unabhängig davon, wie schräg das Handy gehalten wird,
 *   und Neigen löst keinen Sprung aus.
 */
class MotionInput(context: Context) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val gravitySensor = sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY)
    private val linearSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
    private val accelSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val useDedicatedSensors = gravitySensor != null && linearSensor != null

    /** Ab dieser Beschleunigung nach oben (m/s²) zählt ein Ruck als Sprung. */
    @Volatile var jumpThreshold = 8f

    val hasSensors: Boolean get() = useDedicatedSensors || accelSensor != null

    // Gefilterte Schwerkraft (zeigt in der Messkonvention von Android nach oben)
    private var gx = 0f
    private var gy = 9.81f
    private var gz = 0f
    private var haveGravity = false

    @Volatile private var roll = 0f
    @Volatile private var pitch = 0f
    @Volatile private var neutralRoll = 0f
    @Volatile private var neutralPitch = 0f

    private val jumpFlag = AtomicBoolean(false)
    private var lastJumpNs = 0L

    fun start() {
        haveGravity = false
        if (useDedicatedSensors) {
            sensorManager.registerListener(this, gravitySensor, SensorManager.SENSOR_DELAY_GAME)
            sensorManager.registerListener(this, linearSensor, SensorManager.SENSOR_DELAY_GAME)
        } else if (accelSensor != null) {
            sensorManager.registerListener(this, accelSensor, SensorManager.SENSOR_DELAY_GAME)
        }
    }

    fun stop() = sensorManager.unregisterListener(this)

    /** Aktuelle Handyhaltung als "neutral" (geradeaus, Grundtempo) merken. */
    fun calibrate() {
        neutralRoll = roll
        neutralPitch = pitch
        jumpFlag.set(false)
    }

    /** -1 (links) .. 1 (rechts). */
    fun steer(): Float = scaled(roll - neutralRoll, deadzone = DEADZONE, full = STEER_FULL)

    /** -1 (zurück, langsamer) .. 1 (nach vorn, schneller). */
    fun throttle(): Float = scaled(pitch - neutralPitch, deadzone = DEADZONE, full = THROTTLE_FULL)

    /** true, wenn seit dem letzten Aufruf ein Ruck erkannt wurde. */
    fun consumeJump(): Boolean = jumpFlag.getAndSet(false)

    override fun onSensorChanged(e: SensorEvent) {
        when (e.sensor.type) {
            Sensor.TYPE_GRAVITY -> updateGravity(e.values[0], e.values[1], e.values[2], smooth = SMOOTH)
            Sensor.TYPE_LINEAR_ACCELERATION -> detectJerk(e.values[0], e.values[1], e.values[2], e.timestamp)
            Sensor.TYPE_ACCELEROMETER -> {
                // Fallback: Schwerkraft per Tiefpass herausfiltern, Rest ist die lineare Beschleunigung
                val a = FALLBACK_LOWPASS
                val fx = if (haveGravity) a * gx + (1 - a) * e.values[0] else e.values[0]
                val fy = if (haveGravity) a * gy + (1 - a) * e.values[1] else e.values[1]
                val fz = if (haveGravity) a * gz + (1 - a) * e.values[2] else e.values[2]
                updateGravity(fx, fy, fz, smooth = 1f)
                detectJerk(e.values[0] - gx, e.values[1] - gy, e.values[2] - gz, e.timestamp)
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun updateGravity(x: Float, y: Float, z: Float, smooth: Float) {
        if (!haveGravity) {
            gx = x; gy = y; gz = z; haveGravity = true
        } else {
            gx += (x - gx) * smooth
            gy += (y - gy) * smooth
            gz += (z - gz) * smooth
        }
        val norm = sqrt(gx * gx + gy * gy + gz * gz)
        if (norm < 1f) return
        // Die Sensoren messen die Gegenkraft zur Schwerkraft: Ist die linke Seite tiefer, zeigt die
        // x-Achse (nach rechts) nach oben und gx wird positiv. Deshalb das Minus: links tiefer = negativ = links.
        // Nicken: Oberkante vom Körper weg = positiv = "nach vorn".
        roll = -asin((gx / norm).coerceIn(-1f, 1f))
        pitch = atan2(gz, hypot(gx, gy))
    }

    private fun detectJerk(lx: Float, ly: Float, lz: Float, timestampNs: Long) {
        if (!haveGravity) return
        val norm = sqrt(gx * gx + gy * gy + gz * gz)
        if (norm < 1f) return
        val up = (lx * gx + ly * gy + lz * gz) / norm
        if (up > jumpThreshold && timestampNs - lastJumpNs > JUMP_COOLDOWN_NS) {
            lastJumpNs = timestampNs
            jumpFlag.set(true)
        }
    }

    private fun scaled(angle: Float, deadzone: Float, full: Float): Float {
        val a = Math.abs(angle)
        if (a < deadzone) return 0f
        val v = ((a - deadzone) / (full - deadzone)).coerceIn(0f, 1f)
        return if (angle < 0) -v else v
    }

    private companion object {
        const val SMOOTH = 0.3f
        const val FALLBACK_LOWPASS = 0.9f
        const val JUMP_COOLDOWN_NS = 450_000_000L
        val DEADZONE = Math.toRadians(2.0).toFloat()
        val STEER_FULL = Math.toRadians(25.0).toFloat()
        val THROTTLE_FULL = Math.toRadians(20.0).toFloat()
    }
}
