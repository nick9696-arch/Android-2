package com.example.tiltrunner.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.example.tiltrunner.data.Settings

@Composable
fun MenuScreen(onStart: () -> Unit) {
    val context = LocalContext.current
    val settings = remember { Settings(context) }
    var highScore by remember { mutableIntStateOf(settings.highScore) }
    var jumpThreshold by remember { mutableFloatStateOf(settings.jumpThreshold) }

    // Rekord aktualisieren, wenn man aus dem Spiel zurückkommt
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) highScore = settings.highScore
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Column(
        Modifier.fillMaxSize().background(Color(0xFF0B1020)).padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
    ) {
        Text("Tilt Runner", color = Color.White, fontSize = 40.sp)
        Text("Rekord: $highScore", color = Color(0xFFB0BEC5))
        Text(
            "Tippe im Spiel auf den Bildschirm und halte das Handy so, wie du spielen willst: " +
                "Diese Haltung ist \"geradeaus\".\n\n" +
                "Links/rechts neigen: lenken\n" +
                "Nach vorn neigen: schneller, zurück: langsamer\n" +
                "Ruck nach oben: springen (nur vor Lücken, die die ganze Bahn unterbrechen)\n\n" +
                "In Kurven drückt die Fliehkraft nach außen: Je schneller, desto stärker musst du gegenneigen.\n\n" +
                "Manchmal liegt unter dir eine zweite, violette Bahn: Rolle über den Rand oder fall durch eine Lücke, um dort zu landen.",
            color = Color(0xFFCFD8DC),
        )
        Text("Sprung-Empfindlichkeit (links = empfindlicher)", color = Color(0xFFB0BEC5))
        Slider(
            value = jumpThreshold,
            onValueChange = { jumpThreshold = it },
            onValueChangeFinished = { settings.jumpThreshold = jumpThreshold },
            valueRange = 4f..16f,
        )
        Button(onClick = onStart) { Text("Start", style = MaterialTheme.typography.titleMedium) }
    }
}
