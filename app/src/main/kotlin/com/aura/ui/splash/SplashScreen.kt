package com.aura.ui.splash

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.aura.R
import kotlinx.coroutines.delay

/**
 * Brand splash: the Aurora Messenger logo on its own warm-white field.
 *
 * The logo dwell plays only on the first launch of a freshly initialized app
 * ([showLogo] = true). On every later launch the route hands straight off to the
 * main screen with no dwell and nothing drawn.
 */
@Composable
fun SplashScreen(showLogo: Boolean, onFinished: () -> Unit) {
    LaunchedEffect(Unit) {
        // Long enough that the full logo remains visible after the system
        // (cold-start) splash hands off — on real devices that handoff is fast.
        if (showLogo) delay(2400)
        onFinished()
    }

    // Nothing to draw once the splash has already been shown: route immediately.
    if (!showLogo) return

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFFF8F2F6)),   // matches the logo's own background
        contentAlignment = Alignment.Center
    ) {
        Image(
            painter = painterResource(R.drawable.aurora_splash),
            contentDescription = "Aurora Messenger",
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp),
            contentScale = ContentScale.Fit
        )
    }
}
