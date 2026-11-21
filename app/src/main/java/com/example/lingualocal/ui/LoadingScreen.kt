package com.example.lingualocal.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.lingualocal.R
import com.example.lingualocal.Startup
import kotlinx.coroutines.delay

private val Bg = Color(0xFF0D0F13)
private val English = Color(0xFF7AB4FF)
private val Track = Color(0xFF22262F)

/**
 * Minimal loading screen: the logo (same size and spot as the system splash, so the handoff is seamless),
 * a breathing glow and a thin progress line. Leaves when every [Startup] step is done, or after
 * [MAX_WAIT_MS] at the latest; anything still loading keeps going in the background.
 */
@Composable
fun LoadingScreen(onReady: () -> Unit) {
    val steps by Startup.steps.collectAsStateWithLifecycle()
    val finished = steps.count { it.state != Startup.State.RUNNING }
    val allDone = finished == steps.size
    val progress by animateFloatAsState(finished / steps.size.toFloat(), tween(400), label = "progress")

    val shownAt = remember { System.currentTimeMillis() }
    var left by remember { mutableStateOf(false) }
    val leave = { if (!left) { left = true; onReady() } }
    LaunchedEffect(Unit) { delay(MAX_WAIT_MS); leave() }
    LaunchedEffect(allDone) {
        if (allDone) { delay((MIN_SHOW_MS - (System.currentTimeMillis() - shownAt)).coerceAtLeast(300)); leave() }
    }

    val pulse = rememberInfiniteTransition(label = "pulse")
    val glow by pulse.animateFloat(
        0.85f, 1.15f,
        infiniteRepeatable(tween(1400, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "glow",
    )

    Box(Modifier.fillMaxSize().background(Bg), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            // Spacer above balances the bar below so the logo sits exactly at screen centre, like the splash.
            Spacer(Modifier.height(BAR_GAP + BAR_HEIGHT))
            Box(Modifier.size(LOGO), contentAlignment = Alignment.Center) {
                Canvas(Modifier.size(LOGO * 0.75f).scale(glow)) {
                    drawCircle(
                        Brush.radialGradient(listOf(English.copy(alpha = 0.28f), Color.Transparent)),
                        radius = size.minDimension / 2,
                    )
                }
                Image(painterResource(R.drawable.ic_launcher_foreground), contentDescription = "LinguaLocal", modifier = Modifier.size(LOGO))
            }
            Spacer(Modifier.height(BAR_GAP))
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.width(120.dp).height(BAR_HEIGHT),
                color = English,
                trackColor = Track,
                strokeCap = StrokeCap.Round,
                drawStopIndicator = {},
            )
        }
    }
}

private val LOGO = 288.dp // same as the system splash icon (no icon background), so the handoff lines up
private val BAR_GAP = 0.dp
private val BAR_HEIGHT = 3.dp
private const val MIN_SHOW_MS = 1_200L
private const val MAX_WAIT_MS = 10_000L
