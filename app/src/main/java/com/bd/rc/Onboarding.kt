package com.bd.rc

import android.content.Context
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private val Context.dataStore by preferencesDataStore(name = "settings")
private val KEY_ONBOARDED = booleanPreferencesKey("onboarded")

@Composable
fun OnboardingGate(content: @Composable () -> Unit) {
    val context = LocalContext.current
    var done by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        val prefs = context.dataStore.data.first()
        done = prefs[KEY_ONBOARDED] == true
    }
    if (done) content() else OnboardingScreen(onGetStarted = {
        val scope = rememberCoroutineScope()
        scope.launch {
            context.dataStore.edit { it[KEY_ONBOARDED] = true }
        }
    })
}

@Composable
private fun OnboardingScreen(onGetStarted: () -> Unit) {
    Scaffold { padding ->
        Column(Modifier.fillMaxSize().padding(padding), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Text("Pair your HC-05", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(16.dp))
            AnimatedBluetooth()
            Spacer(Modifier.height(16.dp))
            Text("We will auto-detect a bonded HC-05. If not found, you can pair it in Settings.")
            Spacer(Modifier.height(24.dp))
            Button(onClick = onGetStarted) { Text("Get Started") }
        }
    }
}

@Composable
private fun AnimatedBluetooth() {
    val infinite = rememberInfiniteTransition(label = "bt")
    val radius by infinite.animateFloat(20f, 40f, animationSpec = infiniteRepeatable(tween(1200), RepeatMode.Reverse), label = "r")
    Canvas(Modifier.size(120.dp)) {
        drawCircle(color = Color(0xFF2196F3).copy(alpha = 0.2f), radius = radius)
        drawCircle(color = Color(0xFF2196F3), radius = 12f)
    }
}
