package com.remodroid.android

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.remodroid.android.host.ApprovalCoordinator
import com.remodroid.android.input.RemoteControlAccessibilityService
import com.remodroid.android.service.ProjectionConsent
import com.remodroid.android.service.ScreenShareService
import com.remodroid.android.ui.HomeActions
import com.remodroid.android.ui.HomeScreen
import com.remodroid.android.ui.IncomingRequestDialog
import com.remodroid.android.ui.RemoDroidTheme
import com.remodroid.android.ui.RemoteSessionScreen
import com.remodroid.android.ui.SettingsScreen
import com.remodroid.shared.session.SessionState
import com.remodroid.shared.validation.Validation

class MainActivity : ComponentActivity() {
    private val container by lazy { (application as RemoDroidApp).container }

    private val approval by lazy {
        ApprovalCoordinator(
            host = object : ApprovalCoordinator.HostActions {
                override fun approve(level: com.remodroid.shared.models.PermissionLevel) { container.host.approve(level) }
                override fun reject() { container.host.reject() }
            },
            storeConsent = { ProjectionConsent.set(it) },
            startService = { ScreenShareService.start(this) },
            toast = { Toast.makeText(this, it, Toast.LENGTH_LONG).show() },
        )
    }

    private val projectionLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        approval.onConsentResult(result.resultCode == Activity.RESULT_OK, result.data)
    }

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            RemoDroidTheme {
                Surface(Modifier.fillMaxSize()) { App() }
            }
        }
    }

    @androidx.compose.runtime.Composable
    private fun App() {
        val host by container.host.ui.collectAsStateWithLifecycle()
        val controller by container.controller.ui.collectAsStateWithLifecycle()
        val settings by container.settings.settings.collectAsStateWithLifecycle()
        val remoteControl by RemoteControlAccessibilityService.connected.collectAsStateWithLifecycle()
        var showSettings by rememberSaveable { mutableStateOf(false) }
        var explainAccessibility by rememberSaveable { mutableStateOf(false) }

        val inRemoteSession = controller.state in setOf(
            SessionState.NEGOTIATING, SessionState.CONNECTING_MEDIA, SessionState.CONNECTED_VIEW_ONLY,
            SessionState.CONNECTED_CONTROL_ENABLED, SessionState.RECONNECTING, SessionState.DISCONNECTING,
        )

        Box(Modifier.fillMaxSize()) {
            when {
                inRemoteSession -> {
                    BackHandler { container.controller.disconnect() }
                    RemoteSessionScreen(controller, container.controller, container.rtcFactory.eglBase, onRotate = ::toggleOrientation)
                }
                showSettings -> {
                    BackHandler { showSettings = false }
                    SettingsScreen(
                        current = settings,
                        onSave = {
                            container.settings.update { _ -> it }
                            showSettings = false
                        },
                        onBack = { showSettings = false },
                        onOpenAccessibility = { explainAccessibility = true },
                    )
                }
                else -> HomeScreen(
                    host = host,
                    controller = controller,
                    remoteControlEnabled = remoteControl,
                    actions = HomeActions(
                        onToggleSharing = { on ->
                            if (on) {
                                requestNotificationPermission()
                                container.host.startSharing()
                            } else {
                                container.host.stopSharing()
                            }
                        },
                        onToggleAccepting = { container.host.setAcceptingRequests(it) },
                        onRefreshPassword = { container.host.refreshPassword() },
                        onCopy = ::copy,
                        onShare = ::share,
                        onOpenAccessibility = { explainAccessibility = true },
                        onConnect = { id, pw, level -> container.controller.connect(id, pw, level) },
                        onCancelConnect = { container.controller.disconnect() },
                        onOpenSettings = { showSettings = true },
                        onHostEnd = { container.host.endSession() },
                        onHostPause = { container.host.setPaused(it) },
                        onHostPermission = { container.host.setPermission(it) },
                        onDismissNotice = { container.host.clearNotice() },
                    ),
                )
            }

            host.incoming?.takeIf { host.state == SessionState.INCOMING_REQUEST }?.let { request ->
                IncomingRequestDialog(
                    request = request,
                    remoteControlEnabled = remoteControl,
                    onApprove = { level -> approval.beginApproval(level) { launchProjectionConsent() } },
                    onReject = { container.host.reject() },
                )
            }

            if (explainAccessibility) {
                AlertDialog(
                    onDismissRequest = { explainAccessibility = false },
                    title = { Text("Allow remote control") },
                    text = {
                        Text(
                            "To let an approved partner tap, swipe and type on this device, turn on " +
                                "\"RemoDroid remote control\" in Accessibility settings.\n\n" +
                                "RemoDroid only acts during a session you approved, with the access level you chose, " +
                                "and you can revoke control or end the session at any time. It does not read your " +
                                "screen through this service and cannot operate the lock screen or protected windows.",
                        )
                    },
                    confirmButton = {
                        Button(onClick = {
                            explainAccessibility = false
                            startActivity(RemoteControlAccessibilityService.settingsIntent())
                        }) { Text("Open settings") }
                    },
                    dismissButton = { TextButton(onClick = { explainAccessibility = false }) { Text("Not now") } },
                )
            }
        }
    }

    private fun launchProjectionConsent() {
        val mpm = getSystemService(MediaProjectionManager::class.java)
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // Whole display only: single-app capture would break coordinate mapping for remote input.
            mpm.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
        } else {
            mpm.createScreenCaptureIntent()
        }
        projectionLauncher.launch(intent)
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun copy(label: String, value: String) {
        val cm = getSystemService(ClipboardManager::class.java)
        cm.setPrimaryClip(ClipData.newPlainText(label, value))
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) Toast.makeText(this, "$label copied", Toast.LENGTH_SHORT).show()
    }

    private fun share(id: String, password: String) {
        val text = "Connect to my device with RemoDroid.\nID: ${Validation.formatId(id)}\nPassword: $password\n" +
            "The password is temporary and I will approve the connection on my device."
        startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text),
                "Share connection details",
            ),
        )
    }

    private fun toggleOrientation() {
        requestedOrientation = if (requestedOrientation == ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE) {
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        } else {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
    }
}
