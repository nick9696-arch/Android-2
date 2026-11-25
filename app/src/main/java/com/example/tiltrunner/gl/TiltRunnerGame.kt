package com.example.tiltrunner.gl

import com.badlogic.gdx.ApplicationAdapter
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.PerspectiveCamera
import com.badlogic.gdx.graphics.g2d.BitmapFont
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import com.badlogic.gdx.graphics.glutils.ImmediateModeRenderer20
import com.badlogic.gdx.utils.Align
import com.example.tiltrunner.data.Settings
import com.example.tiltrunner.game.Frame
import com.example.tiltrunner.game.GameInput
import com.example.tiltrunner.game.GameWorld
import com.example.tiltrunner.game.Sample
import com.example.tiltrunner.game.Track
import com.example.tiltrunner.sensor.MotionInput
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

private enum class Phase { READY, COUNTDOWN, PLAYING, GAME_OVER }

/**
 * Das eigentliche Spiel: 3D-Darstellung der Murmelbahn mit libGDX (ohne Modelle und Texturen,
 * alles wird aus farbigen Dreiecken zusammengesetzt) plus Bedienung per Neigung und Ruck.
 */
class TiltRunnerGame(
    private val motion: MotionInput,
    private val settings: Settings,
) : ApplicationAdapter() {

    private lateinit var camera: PerspectiveCamera
    private lateinit var gfx: ImmediateModeRenderer20
    private lateinit var batch: SpriteBatch
    private lateinit var font: BitmapFont

    private var world = GameWorld()
    private var phase = Phase.READY
    private var phaseTime = 0f
    private var highScore = 0
    private var newRecord = false

    private var vertexCount = 0
    private val ballFrame = Frame()
    private val camFrame = Frame()
    private val lookFrame = Frame()

    // Zwischenspeicher für die Streckenzeichnung (vermeidet Speicherallokation pro Frame)
    private val lo = FloatArray(8)
    private val hi = FloatArray(8)
    private val keepLo = FloatArray(8)
    private val keepHi = FloatArray(8)

    // Einheitskugel als Dreiecksliste: pro Dreieck 3 Eckpunkte x (x, y, z)
    private val sphere = buildSphere(SPHERE_STACKS, SPHERE_SLICES)

    override fun create() {
        camera = PerspectiveCamera(FOV, Gdx.graphics.width.toFloat(), Gdx.graphics.height.toFloat())
        camera.near = 0.3f
        camera.far = 200f
        gfx = ImmediateModeRenderer20(MAX_VERTICES, false, true, 0)
        batch = SpriteBatch()
        font = BitmapFont()
        highScore = settings.highScore
        resize(Gdx.graphics.width, Gdx.graphics.height)
    }

    override fun resize(width: Int, height: Int) {
        camera.viewportWidth = width.toFloat()
        camera.viewportHeight = height.toFloat()
        camera.update()
        font.data.setScale(height / 520f)
    }

    override fun pause() {
        // Beim Wegwechseln nicht weiterlaufen lassen
        if (phase == Phase.PLAYING || phase == Phase.COUNTDOWN) {
            world = GameWorld()
            phase = Phase.READY
        }
    }

    override fun render() {
        val dt = Gdx.graphics.deltaTime
        update(dt)

        Gdx.gl.glClearColor(SKY_R, SKY_G, SKY_B, 1f)
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT or GL20.GL_DEPTH_BUFFER_BIT)
        Gdx.gl.glEnable(GL20.GL_DEPTH_TEST)

        updateCamera()
        gfx.begin(camera.combined, GL20.GL_TRIANGLES)
        vertexCount = 0
        drawTrack()
        drawShadow()
        drawBall()
        gfx.end()

        Gdx.gl.glDisable(GL20.GL_DEPTH_TEST)
        drawHud()
    }

    // ------------------------------------------------------------------ Ablauf

    private fun update(dt: Float) {
        phaseTime += dt
        val touched = Gdx.input.justTouched()
        when (phase) {
            Phase.READY -> if (touched) setPhase(Phase.COUNTDOWN)
            Phase.COUNTDOWN -> if (phaseTime >= COUNTDOWN_SECONDS) {
                // Erst jetzt kalibrieren: Die Haltung am Ende des Countdowns gilt als "geradeaus"
                world = GameWorld()
                motion.calibrate()
                newRecord = false
                setPhase(Phase.PLAYING)
            }
            Phase.PLAYING -> {
                // Ohne Beschleunigungssensor (z. B. Emulator) springt ein Tipp auf den Bildschirm
                val tapJump = touched && !motion.hasSensors
                world.update(dt, GameInput(motion.steer(), motion.throttle(), motion.consumeJump() || tapJump))
                if (world.gameOver) {
                    if (world.score > highScore) {
                        highScore = world.score
                        newRecord = true
                        settings.saveHighScore(world.score)
                    }
                    setPhase(Phase.GAME_OVER)
                }
            }
            Phase.GAME_OVER -> if (touched && phaseTime > 0.8f) setPhase(Phase.COUNTDOWN)
        }
    }

    private fun setPhase(p: Phase) {
        phase = p
        phaseTime = 0f
    }

    // ------------------------------------------------------------------ Kamera

    private fun updateCamera() {
        val t = world.track
        val u = world.u
        t.frame(world.s - CAM_BACK, camFrame)
        t.frame(world.s + CAM_AHEAD, lookFrame)

        val camU = u * 0.6f
        val camX = camFrame.x + camFrame.rightX * camU
        val camZ = camFrame.z + camFrame.rightZ * camU
        val camY = camFrame.y - camFrame.bank * camU + CAM_HEIGHT + max(world.h, -1f) * 0.3f

        val lookU = u * 0.3f
        val lx = lookFrame.x + lookFrame.rightX * lookU
        val lz = lookFrame.z + lookFrame.rightZ * lookU
        val ly = lookFrame.y - lookFrame.bank * lookU + 0.5f

        camera.position.set(camX, camY, camZ)
        camera.up.set(0f, 1f, 0f)
        camera.lookAt(lx, ly, lz)
        camera.update()
    }

    // ------------------------------------------------------------------ Zeichnen: Grundbausteine

    private fun fogged(dist: Float, c: Float, sky: Float): Float {
        var f = ((dist - FOG_START) / (FOG_END - FOG_START)).coerceIn(0f, 1f)
        f *= f
        return c + (sky - c) * f
    }

    private fun vert(x: Float, y: Float, z: Float, r: Float, g: Float, b: Float) {
        gfx.color(r, g, b, 1f)
        gfx.vertex(x, y, z)
        vertexCount++
    }

    private fun ensureRoom(n: Int) {
        if (vertexCount + n > MAX_VERTICES) {
            gfx.end()
            gfx.begin(camera.combined, GL20.GL_TRIANGLES)
            vertexCount = 0
        }
    }

    private fun quad(
        ax: Float, ay: Float, az: Float,
        bx: Float, by: Float, bz: Float,
        cx: Float, cy: Float, cz: Float,
        dx: Float, dy: Float, dz: Float,
        r: Float, g: Float, b: Float,
    ) {
        ensureRoom(6)
        vert(ax, ay, az, r, g, b); vert(bx, by, bz, r, g, b); vert(cx, cy, cz, r, g, b)
        vert(ax, ay, az, r, g, b); vert(cx, cy, cz, r, g, b); vert(dx, dy, dz, r, g, b)
    }

    // Punkt auf der Strecke: seitlicher Abstand u von der Mitte des Samples, dy über der Oberfläche
    private fun px(sm: Sample, u: Float) = sm.x + sm.rightX * u
    private fun py(sm: Sample, u: Float, dy: Float) = sm.y - sm.bank * u + dy
    private fun pz(sm: Sample, u: Float) = sm.z + sm.rightZ * u

    /** Waagerechte Fläche zwischen zwei Samples, von u1 bis u2 (je Sample), in der Höhe dy. */
    private fun flat(
        a: Sample, ua1: Float, ua2: Float, b: Sample, ub1: Float, ub2: Float, dy: Float,
        r: Float, g: Float, bl: Float,
    ) = quad(
        px(a, ua1), py(a, ua1, dy), pz(a, ua1),
        px(a, ua2), py(a, ua2, dy), pz(a, ua2),
        px(b, ub2), py(b, ub2, dy), pz(b, ub2),
        px(b, ub1), py(b, ub1, dy), pz(b, ub1),
        r, g, bl,
    )

    /** Senkrechte Fläche entlang der Strecke bei festem u (je Sample) von Höhe y0 bis y1. */
    private fun strip(
        a: Sample, ua: Float, b: Sample, ub: Float, y0: Float, y1: Float,
        r: Float, g: Float, bl: Float,
    ) = quad(
        px(a, ua), py(a, ua, y0), pz(a, ua),
        px(a, ua), py(a, ua, y1), pz(a, ua),
        px(b, ub), py(b, ub, y1), pz(b, ub),
        px(b, ub), py(b, ub, y0), pz(b, ub),
        r, g, bl,
    )

    /** Senkrechte Fläche quer zur Strecke an einem Sample von u1 bis u2 und von y0 bis y1. */
    private fun cap(
        sm: Sample, u1: Float, u2: Float, y0: Float, y1: Float,
        r: Float, g: Float, bl: Float,
    ) = quad(
        px(sm, u1), py(sm, u1, y0), pz(sm, u1),
        px(sm, u1), py(sm, u1, y1), pz(sm, u1),
        px(sm, u2), py(sm, u2, y1), pz(sm, u2),
        px(sm, u2), py(sm, u2, y0), pz(sm, u2),
        r, g, bl,
    )

    // ------------------------------------------------------------------ Zeichnen: Strecke

    private fun drawTrack() {
        val t = world.track
        val sBall = world.s
        var i = max(t.indexOf(sBall - VIEW_BEHIND), t.firstIndex)
        val iEnd = t.indexOf(sBall + VIEW_AHEAD)
        while (i < iEnd) {
            val dist = i * Track.DS - sBall
            // In der Ferne gröber zeichnen (nur auf geraden Indizes, damit Lücken-Grenzen passen)
            val k = if (dist > NEAR_DETAIL && i % 2 == 0) 2 else 1
            drawSlab(t, t.sampleAtIndex(i), t.sampleAtIndex(i + k), dist)
            i += k
        }
    }

    private fun drawSlab(t: Track, a: Sample, b: Sample, dist: Float) {
        val sMid = (a.s + b.s) / 2f
        val hw = (a.halfWidth + b.halfWidth) / 2f

        // Bodenstücke dieses Abschnitts: Breite minus aktive Lücken
        var n = 1
        lo[0] = -hw; hi[0] = hw
        for (g in t.gaps) {
            if (!(g.s0 < sMid && g.s1 > sMid)) continue
            var m = 0
            for (j in 0 until n) {
                if (g.u1 <= lo[j] || g.u0 >= hi[j]) {
                    keepLo[m] = lo[j]; keepHi[m] = hi[j]; m++
                } else {
                    if (g.u0 > lo[j]) { keepLo[m] = lo[j]; keepHi[m] = g.u0; m++ }
                    if (g.u1 < hi[j]) { keepLo[m] = g.u1; keepHi[m] = hi[j]; m++ }
                }
            }
            n = min(m, lo.size)
            for (j in 0 until n) { lo[j] = keepLo[j]; hi[j] = keepHi[j] }
        }
        if (n == 0) return

        // Farben: abwechselnde Streifen vermitteln Tempo, das Rondell ist warm gefärbt
        val band = floor(sMid / 3f).toInt() and 1
        val baseR: Float; val baseG: Float; val baseB: Float
        if (a.banked) {
            if (band == 0) { baseR = 0.93f; baseG = 0.62f; baseB = 0.30f } else { baseR = 0.86f; baseG = 0.52f; baseB = 0.24f }
        } else {
            if (band == 0) { baseR = 0.25f; baseG = 0.65f; baseB = 0.59f } else { baseR = 0.21f; baseG = 0.56f; baseB = 0.52f }
        }
        val topR = fogged(dist, baseR, SKY_R); val topG = fogged(dist, baseG, SKY_G); val topB = fogged(dist, baseB, SKY_B)
        val sideR = fogged(dist, baseR * 0.55f, SKY_R); val sideG = fogged(dist, baseG * 0.55f, SKY_G); val sideB = fogged(dist, baseB * 0.55f, SKY_B)
        val capR = fogged(dist, baseR * 0.72f, SKY_R); val capG = fogged(dist, baseG * 0.72f, SKY_G); val capB = fogged(dist, baseB * 0.72f, SKY_B)
        val rimR = fogged(dist, 0.92f, SKY_R); val rimG = fogged(dist, 0.96f, SKY_G); val rimB = fogged(dist, 0.95f, SKY_B)

        for (j in 0 until n) {
            val leftOuter = lo[j] <= -hw + 0.001f
            val rightOuter = hi[j] >= hw - 0.001f
            val uLa = if (leftOuter) -a.halfWidth else lo[j]
            val uLb = if (leftOuter) -b.halfWidth else lo[j]
            val uRa = if (rightOuter) a.halfWidth else hi[j]
            val uRb = if (rightOuter) b.halfWidth else hi[j]

            flat(a, uLa, uRa, b, uLb, uRb, 0f, topR, topG, topB)
            strip(a, uLa, b, uLb, 0f, -THICKNESS, sideR, sideG, sideB)
            strip(a, uRa, b, uRb, 0f, -THICKNESS, sideR, sideG, sideB)

            // Randleisten wie bei einer Murmelbahn
            if (leftOuter) {
                strip(a, uLa + RIM_W, b, uLb + RIM_W, 0f, RIM_H, rimR * 0.8f, rimG * 0.8f, rimB * 0.8f)
                flat(a, uLa, uLa + RIM_W, b, uLb, uLb + RIM_W, RIM_H, rimR, rimG, rimB)
                strip(a, uLa, b, uLb, 0f, RIM_H, rimR * 0.7f, rimG * 0.7f, rimB * 0.7f)
            }
            if (rightOuter) {
                strip(a, uRa - RIM_W, b, uRb - RIM_W, 0f, RIM_H, rimR * 0.8f, rimG * 0.8f, rimB * 0.8f)
                flat(a, uRa - RIM_W, uRa, b, uRb - RIM_W, uRb, RIM_H, rimR, rimG, rimB)
                strip(a, uRa, b, uRb, 0f, RIM_H, rimR * 0.7f, rimG * 0.7f, rimB * 0.7f)
            }

            // Stirnflächen an Lückenrändern
            val mid = (lo[j] + hi[j]) / 2f
            if (!t.isSolid(a.s - 0.25f, mid)) cap(a, uLa, uRa, 0f, -THICKNESS, capR, capG, capB)
            if (!t.isSolid(b.s + 0.25f, mid)) cap(b, uLb, uRb, 0f, -THICKNESS, capR, capG, capB)
        }
    }

    // ------------------------------------------------------------------ Zeichnen: Kugel

    private fun drawShadow() {
        if (world.falling || !world.track.isSolid(world.s, world.u)) return
        world.track.frame(world.s, ballFrame)
        val bf = ballFrame
        val cx = bf.x + bf.rightX * world.u
        val cz = bf.z + bf.rightZ * world.u
        val cy = bf.y - bf.bank * world.u + 0.03f
        val radius = BALL_R * (1f - min(world.h, 2f) * 0.2f)
        val segs = 14
        ensureRoom(segs * 3)
        for (i in 0 until segs) {
            val a0 = (2.0 * PI * i / segs).toFloat()
            val a1 = (2.0 * PI * (i + 1) / segs).toFloat()
            vert(cx, cy, cz, 0.04f, 0.1f, 0.1f)
            vert(cx + cos(a0) * radius, cy, cz + sin(a0) * radius, 0.04f, 0.1f, 0.1f)
            vert(cx + cos(a1) * radius, cy, cz + sin(a1) * radius, 0.04f, 0.1f, 0.1f)
        }
    }

    private fun drawBall() {
        val bf = ballFrame
        world.track.frame(world.s, bf)
        val cx = bf.x + bf.rightX * world.u
        val cz = bf.z + bf.rightZ * world.u
        val cy = bf.y - bf.bank * world.u + world.h + BALL_R
        val shrink = if (world.falling) (1f + world.h / 8f).coerceIn(0.3f, 1f) else 1f
        val r = BALL_R * shrink

        val phi = world.rollAngle
        val cosP = cos(phi)
        val sinP = sin(phi)
        val triCount = sphere.size / 9
        ensureRoom(triCount * 3)
        for (t in 0 until triCount) {
            val o = t * 9
            // Mittelpunkt des Dreiecks als Normale
            var nx = (sphere[o] + sphere[o + 3] + sphere[o + 6]) / 3f
            var ny = (sphere[o + 1] + sphere[o + 4] + sphere[o + 7]) / 3f
            var nz = (sphere[o + 2] + sphere[o + 5] + sphere[o + 8]) / 3f
            val len = sqrt(nx * nx + ny * ny + nz * nz)
            nx /= len; ny /= len; nz /= len

            // Muster auf der Kugel dreht sich mit dem Rollwinkel
            val p0y = ny * cosP + nz * sinP
            val p0z = -ny * sinP + nz * cosP
            val ang = atan2(p0z, p0y)
            val stripe = floor((ang + PI.toFloat()) / (PI.toFloat() / 3f)).toInt() and 1

            // Einfache Beleuchtung in Weltkoordinaten (x: rechts, y: oben, z: vorwärts der Kugel)
            val wx = bf.rightX * nx + bf.dirX * nz
            val wz = bf.rightZ * nx + bf.dirZ * nz
            val light = (0.45f + 0.55f * max(0f, wx * LIGHT_X + ny * LIGHT_Y + wz * LIGHT_Z)).coerceIn(0f, 1f)
            val cr = (if (stripe == 0) 1f else 0.97f) * light
            val cg = (if (stripe == 0) 0.45f else 0.93f) * light
            val cb = (if (stripe == 0) 0.2f else 0.85f) * light

            for (v in 0 until 3) {
                val lx = sphere[o + v * 3]
                val ly = sphere[o + v * 3 + 1]
                val lz = sphere[o + v * 3 + 2]
                vert(
                    cx + (bf.rightX * lx + bf.dirX * lz) * r,
                    cy + ly * r,
                    cz + (bf.rightZ * lx + bf.dirZ * lz) * r,
                    cr, cg, cb,
                )
            }
        }
    }

    // ------------------------------------------------------------------ Anzeige (HUD)

    private fun drawHud() {
        val w = Gdx.graphics.width.toFloat()
        val h = Gdx.graphics.height.toFloat()
        batch.projectionMatrix.setToOrtho2D(0f, 0f, w, h)
        batch.begin()

        if (phase == Phase.PLAYING) {
            font.setColor(1f, 1f, 1f, 1f)
            font.data.setScale(h / 380f)
            font.draw(batch, "${world.score}", 0f, h - h * 0.05f, w, Align.center, false)
            font.data.setScale(h / 700f)
            font.setColor(0.8f, 0.87f, 0.9f, 1f)
            font.draw(batch, "${(world.speed * 3.6f).toInt()} km/h   ${world.s.toInt()} m", 0f, h - h * 0.12f, w, Align.center, false)
            font.data.setScale(h / 520f)
        } else {
            val title: String
            val body: String
            when (phase) {
                Phase.READY -> {
                    title = "Tilt Runner"
                    body = "Rekord: $highScore\n\nTippen zum Starten.\nHalte das Handy so, wie du spielen willst:\n" +
                        "Diese Haltung ist \"geradeaus\"."
                }
                Phase.COUNTDOWN -> {
                    val left = (COUNTDOWN_SECONDS - phaseTime).coerceAtLeast(0f)
                    title = "${floor(left).toInt() + 1}"
                    body = "Haltung einnehmen ..."
                }
                else -> {
                    title = "Game Over"
                    body = "${world.score} Punkte  (${world.s.toInt()} m)\n" +
                        (if (newRecord) "Neuer Rekord!" else "Rekord: $highScore") + "\n\nTippen für neuen Versuch."
                }
            }
            font.setColor(1f, 1f, 1f, 1f)
            font.data.setScale(h / 260f)
            font.draw(batch, title, 0f, h * 0.7f, w, Align.center, false)
            font.data.setScale(h / 650f)
            font.setColor(0.8f, 0.87f, 0.9f, 1f)
            font.draw(batch, body, w * 0.08f, h * 0.58f, w * 0.84f, Align.center, true)
            font.data.setScale(h / 520f)
        }
        batch.end()
    }

    override fun dispose() {
        gfx.dispose()
        batch.dispose()
        font.dispose()
    }

    private companion object {
        const val FOV = 67f
        const val MAX_VERTICES = 30000
        const val COUNTDOWN_SECONDS = 2.5f

        const val CAM_BACK = 7f
        const val CAM_AHEAD = 10f
        const val CAM_HEIGHT = 3.4f
        const val VIEW_BEHIND = 15f
        const val VIEW_AHEAD = 130f
        const val NEAR_DETAIL = 45f
        const val FOG_START = 50f
        const val FOG_END = 130f

        const val THICKNESS = 0.6f
        const val RIM_W = 0.14f
        const val RIM_H = 0.22f
        const val BALL_R = GameWorld.BALL_RADIUS

        const val SPHERE_STACKS = 8
        const val SPHERE_SLICES = 14

        const val SKY_R = 0.07f
        const val SKY_G = 0.10f
        const val SKY_B = 0.22f

        // Lichtrichtung (normiert): von oben rechts hinten
        const val LIGHT_X = 0.3f
        const val LIGHT_Y = 0.85f
        const val LIGHT_Z = -0.43f

        /** Erzeugt eine Kugel mit Radius 1 als Dreiecksliste (x: rechts, y: oben, z: vorwärts). */
        fun buildSphere(stacks: Int, slices: Int): FloatArray {
            val out = ArrayList<Float>()
            fun p(i: Int, j: Int): FloatArray {
                val theta = (PI * i / stacks).toFloat()
                val phi = (2.0 * PI * j / slices).toFloat()
                return floatArrayOf(sin(theta) * cos(phi), cos(theta), sin(theta) * sin(phi))
            }
            for (i in 0 until stacks) {
                for (j in 0 until slices) {
                    val a = p(i, j); val b = p(i + 1, j); val c = p(i + 1, j + 1); val d = p(i, j + 1)
                    for (v in a + b + c + a + c + d) out.add(v)
                }
            }
            return out.toFloatArray()
        }
    }
}
