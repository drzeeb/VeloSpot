package de.velospot.feature.map.presentation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.Image
import de.velospot.R

// VeloSpot brand greens (mirrors the launcher background gradient).
private val SplashGreenLight = Color(0xFF3FE0A2)
private val SplashGreenMid   = Color(0xFF10C68C)
private val SplashGreenDark  = Color(0xFF019875)

/**
 * Full-screen **branded launch overlay** shown on top of the map while it loads.
 *
 * Compose animations run on the main thread, so anything animating *while the map's
 * native renderer initialises* (a heavy main-thread block) would visibly stutter.
 * This splash therefore works in two phases:
 *
 *  1. **Loading** (`mapReady == false`) — a **static** logo on the brand gradient,
 *     seamlessly matching the pre-Compose window background. Nothing animates, so
 *     nothing can hitch while the thread is busy.
 *  2. **Reveal** (`mapReady == true`) — the main thread is free again, so the logo
 *     gives a satisfying **"GPS-lock" heartbeat** while expanding **ping rings**
 *     radiate outward. After a short beat the whole overlay fades + scales away to
 *     the live map ([visible] flips to `false`).
 */
@Composable
fun BoxScope.VeloSpotSplash(visible: Boolean, mapReady: Boolean) {
    AnimatedVisibility(
        visible = visible,
        // The content runs its own reveal animation; no enter transition needed.
        enter = EnterTransition.None,
        exit = fadeOut(tween(420, easing = FastOutSlowInEasing)) +
            scaleOut(targetScale = 1.10f, animationSpec = tween(420, easing = FastOutSlowInEasing))
    ) {
        SplashContent(mapReady = mapReady)
    }
}

@Composable
private fun SplashContent(mapReady: Boolean) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.linearGradient(
                    0.0f to SplashGreenLight,
                    0.55f to SplashGreenMid,
                    1.0f to SplashGreenDark
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        // ── "GPS-lock" heartbeat once the map is ready (thread is free) ──
        val pulse = remember { Animatable(1f) }
        LaunchedEffect(mapReady) {
            if (mapReady) {
                pulse.animateTo(1.16f, tween(240, easing = FastOutSlowInEasing))
                pulse.animateTo(
                    targetValue = 1f,
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioMediumBouncy,
                        stiffness = Spring.StiffnessMedium
                    )
                )
            }
        }

        // ── Expanding ping rings — only drawn/animated during the reveal phase ──
        val infinite = rememberInfiniteTransition(label = "splash")
        val ping by infinite.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(1500, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "ping"
        )

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(contentAlignment = Alignment.Center) {
                Canvas(modifier = Modifier.size(240.dp)) {
                    if (!mapReady) return@Canvas
                    val maxR = size.minDimension / 2f
                    val center = Offset(size.width / 2f, size.height / 2f)
                    for (i in 0 until 3) {
                        val p = (ping + i / 3f) % 1f
                        val radius = maxR * (0.34f + 0.66f * p)
                        val ringAlpha = (1f - p) * 0.45f
                        drawCircle(
                            color = Color.White.copy(alpha = ringAlpha),
                            radius = radius,
                            center = center,
                            style = Stroke(width = 3.dp.toPx())
                        )
                    }
                }

                // Soft white disc + the launcher logo (white pin + green bike).
                Box(
                    modifier = Modifier
                        .size(132.dp)
                        .scale(pulse.value)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.16f)),
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        painter = painterResource(R.drawable.ic_launcher_foreground),
                        contentDescription = null,
                        modifier = Modifier.size(150.dp)
                    )
                }
            }

            Spacer(Modifier.height(20.dp))

            Text(
                text = stringResource(R.string.app_name),
                color = Color.White,
                fontSize = 30.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                letterSpacing = 1.sp
            )
        }
    }
}





