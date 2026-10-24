package com.example.remotecontrol

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.ElevatedButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                RemoteControlScreen()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RemoteControlScreen() {
    val context = LocalContext.current
    val prefs = remember {
        context.getSharedPreferences(RemoteControlService.PREFS, Context.MODE_PRIVATE)
    }
    val projectionManager = remember {
        context.getSystemService(MediaProjectionManager::class.java)
    }

    var notice by rememberSaveable { mutableStateOf("") }

    var hasLocation by remember { mutableStateOf(checkLocation(context)) }
    var hasNotifications by remember { mutableStateOf(checkNotifications(context)) }
    var accessibilityOn by remember { mutableStateOf(isAccessibilityEnabled(context)) }
    var sessionEnabled by remember {
        mutableStateOf(prefs.getBoolean(RemoteControlService.KEY_ENABLED, false))
    }

    val isConnected by RemoteControlState.isConnected.collectAsStateWithLifecycle()
    val sessionId by RemoteControlState.sessionId.collectAsStateWithLifecycle()
    val status by RemoteControlState.status.collectAsStateWithLifecycle()
    val lastMessage by RemoteControlState.lastMessage.collectAsStateWithLifecycle()

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        hasLocation = checkLocation(context)
        hasNotifications = checkNotifications(context)
        notice = if (results.values.all { it }) "Permissions granted"
        else "Some permissions were denied – commands may fail"
    }

    val projectionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            RemoteControlService.pendingProjection = result.resultCode to (result.data!!)
            ContextCompat.startForegroundService(
                context,
                Intent(context, RemoteControlService::class.java)
                    .setAction(RemoteControlService.ACTION_ATTACH_PROJECTION)
            )
            notice = "Screen capture granted"
        } else {
            notice = "Screen capture cancelled"
        }
    }

    fun requestCorePermissions() {
        val wanted = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= 33) {
            wanted += Manifest.permission.POST_NOTIFICATIONS
        }
        val missing = wanted.filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            notice = "Permissions already granted"
        } else {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    fun startSession() {
        if (!checkLocation(context)) {
            requestCorePermissions()
            notice = "Location permission is required to run the session"
            return
        }
        prefs.edit().putBoolean(RemoteControlService.KEY_ENABLED, true).apply()
        try {
            ContextCompat.startForegroundService(
                context,
                Intent(context, RemoteControlService::class.java)
            )
            sessionEnabled = true
            notice = "Session starting…"
        } catch (e: Exception) {
            notice = "Could not start service: ${e.message}"
        }
    }

    fun stopSession() {
        prefs.edit().putBoolean(RemoteControlService.KEY_ENABLED, false).apply()
        context.stopService(Intent(context, RemoteControlService::class.java))
        sessionEnabled = false
        RemoteControlState.isConnected.value = false
        RemoteControlState.status.value = "Stopped"
        notice = "Session stopped"
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasLocation = checkLocation(context)
                hasNotifications = checkNotifications(context)
                accessibilityOn = isAccessibilityEnabled(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(Unit) { requestCorePermissions() }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Remote Control") }) }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState())
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Spacer(Modifier.height(4.dp))

            // ------------------------------------------------ status + session id
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = if (isConnected) "● Connected" else "○ $status",
                        color = if (isConnected) Color(0xFF66BB6A) else Color(0xFFEF5350),
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = "Session ID",
                        style = MaterialTheme.typography.labelSmall
                    )
                    SelectionContainer {
                        Text(
                            text = sessionId.ifEmpty { "— start the session —" },
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    if (lastMessage.isNotEmpty()) {
                        Text(
                            text = "Last message: $lastMessage",
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 2
                        )
                    }
                }
            }

            // ----------------------------------------------------- server + toggle
            // Relay URL is embedded in the app — no entry field needed.
            Text(
                text = "Relay: ${RemoteControlService.DEFAULT_URL}",
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall
            )

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Switch(
                    checked = sessionEnabled,
                    onCheckedChange = { if (it) startSession() else stopSession() }
                )
                Spacer(Modifier.width(12.dp))
                Text(if (sessionEnabled) "Session running" else "Session stopped")
            }

            // ------------------------------------------------------- capabilities
            Text("Capabilities", style = MaterialTheme.typography.titleSmall)

            ElevatedButton(
                onClick = {
                    projectionLauncher.launch(projectionManager.createScreenCaptureIntent())
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Grant screen capture (for \"screenshot\")")
            }

            OutlinedButton(
                onClick = {
                    context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    notice = "Enable \"Remote Control taps\" so the \"click\" command can work"
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (accessibilityOn) "Taps: accessibility enabled ✓" else "Enable accessibility for taps")
            }

            OutlinedButton(
                onClick = ::requestCorePermissions,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Request permissions")
            }

            Text(
                text = buildString {
                    append("Location: ")
                    append(if (hasLocation) "granted ✓" else "missing ✗")
                    append("   •   Notifications: ")
                    append(if (hasNotifications) "granted ✓" else "missing ✗")
                    append("   •   Taps: ")
                    append(if (accessibilityOn) "enabled ✓" else "disabled ✗")
                },
                style = MaterialTheme.typography.bodySmall
            )

            if (notice.isNotEmpty()) {
                Text(
                    text = notice,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
            }

            Text(
                text = "Accepted commands: " +
                    """{"action":"click","x":…,"y":…} (x/y optional), {"action":"screenshot"}, {"action":"location"}, {"action":"kill"}, {"action":"ping"}""",
                style = MaterialTheme.typography.bodySmall
            )

            Spacer(Modifier.height(8.dp))
        }
    }
}

private fun checkLocation(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

private fun checkNotifications(context: Context): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

private fun isAccessibilityEnabled(context: Context): Boolean {
    val enabled = Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
    ) ?: return false
    return enabled.split(':').any { component ->
        component.startsWith(context.packageName) &&
            component.contains("TapAccessibilityService")
    }
}