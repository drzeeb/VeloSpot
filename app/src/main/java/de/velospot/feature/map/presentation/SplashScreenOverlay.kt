package de.velospot.feature.map.presentation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseInOutSine
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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.draw.alpha
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
import kotlinx.coroutines.launch

// VeloSpot brand greens (mirrors the launcher background gradient).
private val SplashGreenLight = Color(0xFF3FE0A2)
private val SplashGreenMid   = Color(0xFF10C68C)
private val SplashGreenDark  = Color(0xFF019875)

/**
 * Full-screen, **animated launch overlay** shown on top of the map while it loads,
 * so the user sees the branded VeloSpot logo instead of a bare white screen during
 * the brief style/tile load.
 *
 * The animation is themed around the app's purpose — a location pin that **drops in
 * with a bounce**, gently **breathes**, and emits expanding **GPS-ping rings** (as if
 * acquiring a fix) — finished with the app name and a row of pulsing loading dots.
 * When [visible] flips to `false` (the map is ready) the whole overlay **fades and
 * scales away** to reveal the live map underneath.
 */
@Composable
fun BoxScope.VeloSpotSplash(visible: Boolean) {
    AnimatedVisibility(
        visible = visible,
        // No enter transition: the content runs its own bouncy entrance animation.
        enter = androidx.compose.animation.EnterTransition.None,
        exit = fadeOut(tween(420, easing = FastOutSlowInEasing)) +
            scaleOut(targetScale = 1.10f, animationSpec = tween(420, easing = FastOutSlowInEasing))
    ) {
        SplashContent()
    }
}

@Composable
private fun SplashContent() {
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
        // ── Bouncy entrance (scale + fade), driven once on first composition ──
        val entranceScale = remember { Animatable(0.45f) }
        val entranceAlpha = remember { Animatable(0f) }
        LaunchedEffect(Unit) {
            launch { entranceAlpha.animateTo(1f, tween(360, easing = LinearEasing)) }
            entranceScale.animateTo(
                targetValue = 1f,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessLow
                )
            )
        }

        // ── Continuous "breathing" pulse once settled ──
        val infinite = rememberInfiniteTransition(label = "splash")
        val breathe by infinite.animateFloat(
            initialValue = 1f,
            targetValue = 1.06f,
            animationSpec = infiniteRepeatable(
                animation = tween(1400, easing = EaseInOutSine),
                repeatMode = RepeatMode.Reverse
            ),
            label = "breathe"
        )

        // ── GPS-ping ring expansion (0→1 looped; three staggered rings) ──
        val ping by infinite.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(2200, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "ping"
        )

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.alpha(entranceAlpha.value)
        ) {
            Box(contentAlignment = Alignment.Center) {
                // Expanding GPS rings behind the logo.
                Canvas(modifier = Modifier.size(240.dp)) {
                    val maxR = size.minDimension / 2f
                    val center = Offset(size.width / 2f, size.height / 2f)
                    for (i in 0 until 3) {
                        val p = (ping + i / 3f) % 1f
                        val radius = maxR * (0.32f + 0.68f * p)
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
                        .scale(entranceScale.value * breathe)
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

            Spacer(Modifier.height(28.dp))

            LoadingDots(infinite)
        }
    }
}

/** Three softly pulsing dots that ripple left→right, signalling work in progress. */
@Composable
private fun LoadingDots(
    infinite: androidx.compose.animation.core.InfiniteTransition
) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        for (i in 0 until 3) {
            val phase by infinite.animateFloat(
                initialValue = 0f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(900, delayMillis = i * 160, easing = EaseInOutSine),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "dot$i"
            )
            Box(
                modifier = Modifier
                    .size(11.dp)
                    .scale(0.7f + 0.5f * phase)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.55f + 0.45f * phase))
            )
        }
    }
}





