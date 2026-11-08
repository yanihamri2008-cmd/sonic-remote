package com.sonicteam.sonicremote.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.sonicteam.sonicremote.AppViewModel
import com.sonicteam.sonicremote.R
import com.sonicteam.sonicremote.Screen
import com.sonicteam.sonicremote.data.ActiveSession
import com.sonicteam.sonicremote.data.Role
import com.sonicteam.sonicremote.rtc.ViewerEngine
import com.sonicteam.sonicremote.share.ControlState
import com.sonicteam.sonicremote.share.ScreenShareService
import com.sonicteam.sonicremote.share.ShareStatus
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer

@Composable
private fun AgentControls(vm: AppViewModel, s: ActiveSession) {
    val ctx = LocalContext.current
    val share by ShareStatus.state.collectAsState()
    var notifDenied by remember { mutableStateOf(false) }

    val projectionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        val data = res.data
        if (res.resultCode == Activity.RESULT_OK && data != null) {
            ScreenShareService.start(ctx, res.resultCode, data, s.id, vm.device?.id.orEmpty(), s.peerDeviceName)
        }
    }
    fun launchProjection() {
        val mpm = ctx.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projectionLauncher.launch(mpm.createScreenCaptureIntent()) // the official Android permission dialog
    }
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) launchProjection() else notifDenied = true
    }
    fun begin() {
        notifDenied = false
        val needs = Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        if (needs) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) else launchProjection()
    }

    InfoCard {
        when (share) {
            ShareStatus.State.Idle, ShareStatus.State.Error -> {
                Text(stringResource(R.string.share_note_agent), color = SonicColors.Silver, fontSize = 14.sp)
                if (share == ShareStatus.State.Error) {
                    Text(stringResource(R.string.share_error), color = SonicColors.Danger, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
                }
                if (notifDenied) {
                    Text(stringResource(R.string.notif_perm_needed), color = SonicColors.Warning, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
                }
                Button(onClick = { begin() }, modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
                    Text(stringResource(R.string.share_start))
                }
            }
            ShareStatus.State.Starting -> {
                Text(stringResource(R.string.share_connecting), color = SonicColors.Warning, fontWeight = FontWeight.SemiBold)
                OutlinedButton(onClick = { ScreenShareService.requestStop(ctx) }, modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
                    Text(stringResource(R.string.share_stop))
                }
            }
            ShareStatus.State.Live -> {
                Text("● " + stringResource(R.string.share_live), color = SonicColors.Accent, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Button(
                    onClick = { ScreenShareService.requestStop(ctx) },
                    colors = ButtonDefaults.buttonColors(containerColor = SonicColors.Danger, contentColor = Color.White),
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                ) { Text(stringResource(R.string.share_stop)) }
            }
        }
    }
}


@Composable
private fun AgentConsentCards() {
    val ctx = LocalContext.current
    val share by ShareStatus.state.collectAsState()
    val serviceOn by ControlState.serviceConnected.collectAsState()
    val control by ControlState.control.collectAsState()
    val terminal by ControlState.terminal.collectAsState()
    val log by ControlState.termLog.collectAsState()
    val live = share == ShareStatus.State.Live

    InfoCard {
        Text(stringResource(R.string.ctl_title), color = SonicColors.Accent, fontWeight = FontWeight.Bold)
        if (!serviceOn) {
            Text(stringResource(R.string.ctl_need_service), color = SonicColors.Silver, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
            Text(stringResource(R.string.ctl_restricted_note), color = SonicColors.Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
            OutlinedButton(
                onClick = { ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) },
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
            ) { Text(stringResource(R.string.ctl_open_settings)) }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                Text(stringResource(R.string.ctl_allow), fontSize = 14.sp, modifier = Modifier.weight(1f))
                Switch(checked = control, enabled = live, onCheckedChange = { ControlState.control.value = it })
            }
            if (!live) Text(stringResource(R.string.ctl_start_first), color = SonicColors.Muted, fontSize = 12.sp)
            if (control) {
                Text(
                    "● " + stringResource(R.string.ctl_on_banner),
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp).clip(RoundedCornerShape(10.dp)).background(SonicColors.Danger).padding(12.dp),
                )
                Button(
                    onClick = { ControlState.control.value = false },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                ) { Text(stringResource(R.string.ctl_revoke)) }
            }
        }
    }

    InfoCard {
        Text(stringResource(R.string.term_title), color = SonicColors.Accent, fontWeight = FontWeight.Bold)
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
            Text(stringResource(R.string.term_allow), fontSize = 14.sp, modifier = Modifier.weight(1f))
            Switch(checked = terminal, enabled = live, onCheckedChange = { ControlState.terminal.value = it })
        }
        Text(stringResource(R.string.term_log_title), color = SonicColors.Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
        Text(
            if (log.isEmpty()) stringResource(R.string.term_log_empty) else log.takeLast(8).joinToString("\n") { "$ $it" },
            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
            fontSize = 12.sp,
            color = SonicColors.Silver,
        )
    }
}

@Composable
fun SessionScreen(vm: AppViewModel, onToggleLanguage: () -> Unit) {
    val s = vm.activeSession
    val now = rememberNowMs()
    ScreenFrame(
        title = stringResource(R.string.session_title),
        onBack = { vm.go(Screen.Home) },
        onToggleLanguage = onToggleLanguage,
    ) {
        if (s == null) return@ScreenFrame
        InfoCard {
            Text(
                stringResource(if (s.role == Role.Agent) R.string.session_role_agent else R.string.session_role_controller),
                color = SonicColors.Accent,
                fontWeight = FontWeight.Bold,
            )
            Text(stringResource(R.string.session_with, s.peerDeviceName), fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
            Text(
                stringResource(R.string.session_expires, formatRemaining(s.expiresAtMs - now)),
                color = SonicColors.Warning,
                fontSize = 13.sp,
                modifier = Modifier.padding(top = 4.dp),
            )
        }

        if (s.role == Role.Agent) {
            AgentControls(vm, s)
            AgentConsentCards()
        } else {
            ViewerPanel(vm.viewer)
        }

        vm.pairingMessage?.let { Text(stringResource(fnErrorRes(it)), color = SonicColors.Danger, fontSize = 13.sp) }

        Button(
            onClick = { vm.endSession(false) },
            enabled = !vm.pairingBusy,
            colors = ButtonDefaults.buttonColors(containerColor = SonicColors.Danger, contentColor = Color.White),
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(R.string.end_session)) }

        if (s.role == Role.Agent) {
            OutlinedButton(onClick = { vm.endSession(true) }, enabled = !vm.pairingBusy, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.revoke_consent))
            }
        }
    }
}
