package com.example.tiltrunner.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.example.tiltrunner.data.Settings
import com.example.tiltrunner.game.GameEngine
import com.example.tiltrunner.game.GameInput
import com.example.tiltrunner.sensor.MotionInput
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.floor
import kotlin.math.max

private enum class Phase { MENU, PLAYING, GAME_OVER }

@Composable
fun GameScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings = remember { Settings(context) }
    val motion = remember { MotionInput(context) }

    var phase by remember { mutableStateOf(Phase.MENU) }
    var engine by remember { mutableStateOf(GameEngine()) }
    var frame by remember { mutableLongStateOf(0L) } // ändert sich pro Frame und löst das Neuzeichnen aus
    var highScore by remember { mutableIntStateOf(0) }
    var jumpThreshold by remember { mutableStateOf(Settings.DEFAULT_JUMP_THRESHOLD) }

    LaunchedEffect(Unit) {
        highScore = settings.highScore.first()
        jumpThreshold = settings.jumpThreshold.first()
        motion.jumpThreshold = jumpThreshold
    }

    // Sensoren nur laufen lassen, solange die App sichtbar ist
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> motion.start()
                Lifecycle.Event.ON_PAUSE -> {
                    motion.stop()
                    if (phase == Phase.PLAYING) phase = Phase.MENU
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            motion.stop()
        }
    }

    // Spielschleife
    LaunchedEffect(phase) {
        if (phase != Phase.PLAYING) return@LaunchedEffect
        var last = 0L
        while (true) {
            androidx.compose.runtime.withFrameNanos { now ->
                val dt = if (last == 0L) 0f else (now - last) / 1_000_000_000f
                last = now
                engine.update(
                    dt,
                    GameInput(
                        steer = motion.steer(),
                        throttle = motion.throttle(),
                        jump = motion.consumeJump(),
                    ),
                )
                frame = now
            }
            if (engine.gameOver) {
                val finalScore = engine.score
                if (finalScore > highScore) highScore = finalScore
                scope.launch { settings.saveHighScore(finalScore) }
                phase = Phase.GAME_OVER
                break
            }
        }
    }

    fun startGame() {
        motion.calibrate() // aktuelle Haltung = neutral
        engine = GameEngine()
        phase = Phase.PLAYING
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(VOID)
            // Notfall/Emulator: Tippen löst ebenfalls einen Sprung aus
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                if (phase == Phase.PLAYING) engine.update(0f, GameInput(jump = true))
            },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            frame.let { drawWorld(engine) }
        }

        if (phase == Phase.PLAYING) {
            Text(
                "${engine.score} m",
                color = Color.White,
                fontSize = 32.sp,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 48.dp),
            )
        }

        if (phase != Phase.PLAYING) {
            Column(
                Modifier.align(Alignment.Center).padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    if (phase == Phase.GAME_OVER) "Game Over" else "Tilt Runner",
                    color = Color.White,
                    fontSize = 40.sp,
                )
                if (phase == Phase.GAME_OVER) Text("${engine.score} m", color = Color.White, fontSize = 28.sp)
                Text("Rekord: $highScore m", color = Color(0xFFB0BEC5))
                if (!motion.hasSensors) {
                    Text("Kein Beschleunigungssensor gefunden.", color = Color(0xFFFF8A80))
                }
                Text(
                    "Halte das Handy bequem und tippe auf Start: Diese Haltung ist \"geradeaus\".\n" +
                        "Links/rechts neigen = lenken\nNach vorn neigen = schneller, zurück = langsamer\n" +
                        "Ruck nach oben = springen",
                    color = Color(0xFFCFD8DC),
                )
                Text("Sprung-Empfindlichkeit", color = Color(0xFFB0BEC5))
                // Links = empfindlich (niedrige Schwelle), rechts = unempfindlich
                Slider(
                    value = jumpThreshold,
                    onValueChange = {
                        jumpThreshold = it
                        motion.jumpThreshold = it
                    },
                    onValueChangeFinished = { scope.launch { settings.saveJumpThreshold(jumpThreshold) } },
                    valueRange = 4f..16f,
                )
                Button(onClick = { startGame() }) {
                    Text(if (phase == Phase.GAME_OVER) "Nochmal" else "Start", style = MaterialTheme.typography.titleMedium)
                }
            }
        }
    }
}

// ---------------------------------------------------------------- Rendering

private val VOID = Color(0xFF0B1020)
private val SKY_TOP = Color(0xFF1B2A5A)
private val SKY_BOTTOM = Color(0xFF6C7BD0)
private val TRACK_A = Color(0xFF3FA796)
private val TRACK_B = Color(0xFF379084)
private val EDGE = Color(0xFFE8F5F2)
private val BALL = Color(0xFFFF7043)

private const val CAM_BACK = 4f
private const val CAM_HEIGHT = 2.2f
private const val NEAR_DZ = -3f
private const val FAR_DZ = 90f
private const val STRIPE = 2f
private const val BALL_RADIUS = 0.3f

private fun DrawScope.drawWorld(e: GameEngine) {
    val w = size.width
    val hgt = size.height
    val horizon = hgt * 0.28f
    val focal = w * 1.6f
    val camX = e.x * 0.5f

    fun scale(dz: Float) = focal / (dz + CAM_BACK)
    fun sx(x: Float, dz: Float) = w / 2f + (x - camX) * scale(dz)
    fun sy(dz: Float, y: Float = 0f) = horizon + (CAM_HEIGHT - y) * scale(dz)

    // Himmel
    drawRect(
        brush = androidx.compose.ui.graphics.Brush.verticalGradient(listOf(SKY_TOP, SKY_BOTTOM), 0f, horizon),
        size = androidx.compose.ui.geometry.Size(w, horizon),
    )

    fun quad(z0: Float, z1: Float, x0: Float, x1: Float, color: Color) {
        val a = max(z0 - e.z, NEAR_DZ)
        val b = (z1 - e.z).coerceAtMost(FAR_DZ)
        if (b <= a) return
        val p = Path().apply {
            moveTo(sx(x0, a), sy(a))
            lineTo(sx(x1, a), sy(a))
            lineTo(sx(x1, b), sy(b))
            lineTo(sx(x0, b), sy(b))
            close()
        }
        drawPath(p, color)
    }

    // Strecke in abwechselnden Streifen (vermittelt Tempo)
    val firstStripe = floor((e.z + NEAR_DZ) / STRIPE).toInt()
    val lastStripe = floor((e.z + FAR_DZ) / STRIPE).toInt()
    for (i in firstStripe..lastStripe) {
        quad(i * STRIPE, (i + 1) * STRIPE, -GameEngine.TRACK_HALF, GameEngine.TRACK_HALF, if (i % 2 == 0) TRACK_A else TRACK_B)
    }
    // Lücken ausstanzen
    for (g in e.gaps) {
        quad(g.z0, g.z1, g.x0.coerceAtLeast(-1f), g.x1.coerceAtMost(1f), VOID)
    }
    // Randlinien
    for (side in listOf(-1f, 1f)) {
        drawLine(
            EDGE,
            Offset(sx(side, NEAR_DZ), sy(NEAR_DZ)),
            Offset(sx(side, FAR_DZ), sy(FAR_DZ)),
            strokeWidth = 3f,
        )
    }

    // Kugel (liegt immer bei dz = 0)
    val s = scale(0f)
    val cx = sx(e.x, 0f)
    val fallShrink = if (e.falling) (1f + e.h / 6f).coerceIn(0.3f, 1f) else 1f
    val r = BALL_RADIUS * s * fallShrink
    if (!e.falling && e.h >= 0f) {
        // Schatten am Boden
        drawOval(
            Color(0x55000000),
            topLeft = Offset(cx - r, sy(0f) - r * 0.35f),
            size = androidx.compose.ui.geometry.Size(r * 2f, r * 0.7f),
        )
    }
    val cy = sy(0f, e.h + BALL_RADIUS)
    drawCircle(BALL, r, Offset(cx, cy))
    drawCircle(Color(0x66FFFFFF), r * 0.35f, Offset(cx - r * 0.3f, cy - r * 0.3f))
}
