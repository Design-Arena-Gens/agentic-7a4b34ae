package com.bd.rc

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.VibratorManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothDisabled
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    private val vm: RcCarViewModel by viewModels()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val granted = results.all { it.value }
        vm.refreshPermission(granted)
        if (granted) vm.autoConnect()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                OnboardingGate {
                    val state by vm.state.collectAsState()
                    LaunchedEffect(Unit) {
                        if (!PermissionHelper.hasAllPermissions(this@MainActivity)) {
                            PermissionHelper.launchPermissionRequest(permissionLauncher)
                        } else {
                            vm.refreshPermission(true)
                            vm.autoConnect()
                        }
                    }
                    RcCarScreen(state = state, onCommand = { c ->
                        vm.sendCommand(c)
                    }, onRetryConnect = { vm.autoConnect() }, onEmergency = {
                        repeat(3) { vm.sendCommand('S') }
                    })
                }
            }
        }
    }

    override fun onStop() {
        super.onStop()
        vm.sendCommand('S')
    }
}

@Composable
private fun RcCarScreen(
    state: UiState,
    onCommand: (Char) -> Unit,
    onRetryConnect: () -> Unit,
    onEmergency: () -> Unit
) {
    val context = LocalContext.current
    val vibrator = if (Build.VERSION.SDK_INT >= 31) {
        val vmgr = context.getSystemService(VibratorManager::class.java)
        vmgr?.defaultVibrator
    } else null

    fun haptic() {
        if (Build.VERSION.SDK_INT >= 26) {
            vibrator?.vibrate(VibrationEffect.createOneShot(25, VibrationEffect.DEFAULT_AMPLITUDE))
        }
    }

    val connected = state.connectionState == ConnectionState.Connected
    val alpha = if (connected) 1f else 0.4f

    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(state.errorMessage) {
        val msg = state.errorMessage
        if (msg != null) {
            val result = snackbarHostState.showSnackbar(
                message = msg,
                actionLabel = "Retry",
                withDismissAction = true,
                duration = SnackbarDuration.Short
            )
            if (result == SnackbarResult.ActionPerformed) {
                state.lastCommand?.let { onCommand(it) } ?: onRetryConnect()
            }
        }
    }

    var debugExpanded by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val dotColor = if (connected) Color(0xFF4CAF50) else Color(0xFFF44336)
                        Box(
                            Modifier.size(10.dp).background(dotColor, shape = MaterialTheme.shapes.small)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(if (connected) "Connected to HC-05" else "Disconnected")
                    }
                },
                actions = {
                    IconButton(onClick = onRetryConnect) {
                        Icon(if (connected) Icons.Default.Bluetooth else Icons.Default.BluetoothDisabled, contentDescription = "Reconnect")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            PulsingStopFab(enabled = connected, onClick = {
                onCommand('S'); haptic()
            }, onDoubleTap = { onEmergency(); haptic() })
        }
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .pointerInput(Unit) {
                    detectVerticalDragGestures(onVerticalDrag = { _, dragAmount ->
                        if (dragAmount > 30f) debugExpanded = true
                    })
                },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Spacer(Modifier.height(16.dp))
            StatusCard(state)
            Spacer(Modifier.height(24.dp))
            DPad(
                enabled = connected,
                onPress = { c -> onCommand(c); haptic() },
                modifier = Modifier.alpha(alpha)
            )
            Spacer(Modifier.height(24.dp))
            DebugPanel(state = state, expanded = debugExpanded, setExpanded = { debugExpanded = it })
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun StatusCard(state: UiState) {
    Card(Modifier.padding(16.dp).fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(16.dp)) {
            Text(text = if (state.connectionState == ConnectionState.Connected) "Connected to HC-05" else "Disconnected", fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Bolt, contentDescription = null, tint = Color(0xFF4CAF50))
                Spacer(Modifier.width(8.dp))
                Text(text = "Last command: " + (state.lastCommand?.let { prettyCommand(it) } ?: "None"))
            }
            state.errorMessage?.let { msg ->
                Spacer(Modifier.height(8.dp))
                Text(text = msg, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
            }
        }
    }
}

private fun prettyCommand(c: Char): String = when (c) {
    'F' -> "↑ Forward"
    'B' -> "↓ Backward"
    'L' -> "← Left"
    'R' -> "→ Right"
    'S' -> "■ Stop"
    else -> c.toString()
}

@Composable
private fun PulsingStopFab(enabled: Boolean, onClick: () -> Unit, onDoubleTap: () -> Unit) {
    val transition = rememberInfiniteTransition(label = "pulse")
    val scale by transition.animateFloat(
        initialValue = 1f, targetValue = if (enabled) 1.07f else 1f,
        animationSpec = infiniteRepeatable(tween(1000, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "scale"
    )
    val alpha by transition.animateFloat(
        initialValue = 0.6f, targetValue = if (enabled) 1f else 0.6f,
        animationSpec = infiniteRepeatable(tween(1000), RepeatMode.Reverse),
        label = "alpha"
    )
    FloatingActionButton(
        onClick = onClick,
        modifier = Modifier
            .size(80.dp)
            .semantics { contentDescription = "Stop" }
            .pointerInput(Unit) {
                detectTapGestures(
                    onDoubleTap = { onDoubleTap() }
                )
            }
            .graphicsLayerCompat(scaleX = scale, scaleY = scale, alpha = alpha),
        containerColor = Color(0xFFB00020),
        contentColor = Color.White
    ) { Icon(Icons.Default.Stop, contentDescription = null) }
}

private fun Modifier.graphicsLayerCompat(
    scaleX: Float = 1f,
    scaleY: Float = 1f,
    alpha: Float = 1f
): Modifier = this.graphicsLayer(scaleX = scaleX, scaleY = scaleY, alpha = alpha)

@Composable
private fun DPad(enabled: Boolean, onPress: (Char) -> Unit, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        val size = 280.dp
        DPadCircle(size = size, enabled = enabled)
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            DPadButton(direction = "F", enabled = enabled) { onPress('F') }
            Row(verticalAlignment = Alignment.CenterVertically) {
                DPadButton(direction = "L", enabled = enabled) { onPress('L') }
                Spacer(Modifier.width(64.dp))
                DPadButton(direction = "R", enabled = enabled) { onPress('R') }
            }
            DPadButton(direction = "B", enabled = enabled) { onPress('B') }
        }
    }
}

@Composable
private fun DPadCircle(size: Dp, enabled: Boolean) {
    val color = if (enabled) MaterialTheme.colorScheme.primary.copy(alpha = 0.2f) else Color.Gray.copy(alpha = 0.15f)
    Canvas(Modifier.size(size)) {
        drawCircle(color = color)
        drawCircle(color = Color.White.copy(alpha = 0.05f), radius = size.toPx() * 0.45f)
    }
}

@Composable
private fun DPadButton(direction: String, enabled: Boolean, onClick: () -> Unit) {
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun Inner() {
        val label = when (direction) {
            "F" -> "Forward"
            "B" -> "Backward"
            "L" -> "Left"
            "R" -> "Right"
            else -> direction
        }
        val tooltipState = rememberTooltipState()
        TooltipBox(
            positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
            tooltip = { PlainTooltip { Text("'$direction'") } },
            state = tooltipState
        ) {
            Button(
                onClick = onClick,
                enabled = enabled,
                modifier = Modifier
                    .size(96.dp)
                    .semantics { contentDescription = label },
                shape = MaterialTheme.shapes.extraLarge
            ) { Text(label) }
        }
    }
    Inner()
}

@Composable
private fun DebugPanel(state: UiState, expanded: Boolean, setExpanded: (Boolean) -> Unit) {
    // Toggle via bug icon or swipe-down gesture from parent
    Card(Modifier.padding(16.dp).fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Text("Debug Panel", fontWeight = FontWeight.SemiBold)
                IconButton(onClick = { setExpanded(!expanded) }) { Icon(Icons.Default.BugReport, contentDescription = null) }
            }
            AnimatedVisibility(visible = expanded) {
                Column {
                    Text("State: ${state.connectionState}")
                    Text("Permission: ${state.permissionGranted}")
                    Text("Device: ${state.deviceName ?: "None"}")
                    Spacer(Modifier.height(8.dp))
                    Text("History:")
                    state.commandHistory.forEach { c -> Text(" • ${prettyCommand(c)}") }
                }
            }
        }
    }
}
