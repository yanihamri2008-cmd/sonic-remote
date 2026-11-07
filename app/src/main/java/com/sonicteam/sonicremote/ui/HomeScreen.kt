package com.sonicteam.sonicremote.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sonicteam.sonicremote.AppViewModel
import com.sonicteam.sonicremote.R
import com.sonicteam.sonicremote.Screen

@Composable
private fun DeviceCard(vm: AppViewModel) {
    var confirmRevoke by rememberSaveable { mutableStateOf(false) }
    var copied by rememberSaveable { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    val d = vm.device

    InfoCard {
        Text(stringResource(R.string.device_title), color = SonicColors.Muted, fontSize = 12.sp)
        if (d == null) {
            val err = vm.deviceError
            if (err != null) {
                Text(stringResource(fnErrorRes(err)), color = SonicColors.Danger, modifier = Modifier.padding(top = 6.dp))
                OutlinedButton(onClick = { vm.refreshNow() }, modifier = Modifier.padding(top = 8.dp)) {
                    Text(stringResource(R.string.device_retry))
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text(stringResource(R.string.device_registering), modifier = Modifier.padding(start = 10.dp))
                }
            }
        } else {
            Text(d.name, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, modifier = Modifier.padding(top = 2.dp))
            Text(stringResource(R.string.device_code_label), color = SonicColors.Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 12.dp))
            Text(prettyCode(d.pairingCode), fontSize = 28.sp, fontWeight = FontWeight.Bold, letterSpacing = 4.sp, color = SonicColors.Accent)
            Text(stringResource(R.string.device_code_hint), color = SonicColors.Silver, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                OutlinedButton(onClick = { clipboard.setText(AnnotatedString(d.pairingCode)); copied = true }) {
                    Text(stringResource(if (copied) R.string.copied else R.string.copy))
                }
                TextButton(onClick = { confirmRevoke = true }) {
                    Text(stringResource(R.string.device_revoke), color = SonicColors.Danger)
                }
            }
        }
    }

    if (confirmRevoke) {
        AlertDialog(
            onDismissRequest = { confirmRevoke = false },
            title = { Text(stringResource(R.string.device_revoke_title)) },
            text = { Text(stringResource(R.string.device_revoke_body)) },
            confirmButton = {
                TextButton(onClick = { confirmRevoke = false; copied = false; vm.revokeThisDevice() }) {
                    Text(stringResource(R.string.confirm), color = SonicColors.Danger)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmRevoke = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

@Composable
private fun ModeCard(title: String, description: String, buttonText: String, enabled: Boolean, onOpen: () -> Unit) {
    InfoCard {
        Text(title, fontWeight = FontWeight.Bold, fontSize = 17.sp)
        Text(description, color = SonicColors.Silver, fontSize = 14.sp, modifier = Modifier.padding(top = 6.dp))
        Button(onClick = onOpen, enabled = enabled, modifier = Modifier.fillMaxWidth().padding(top = 10.dp)) { Text(buttonText) }
    }
}

@Composable
fun HomeScreen(vm: AppViewModel, onToggleLanguage: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 20.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                BrandLogo(40.dp)
                Column {
                    Text(stringResource(R.string.app_name), fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
                    Text(stringResource(R.string.by_team), color = SonicColors.Accent, fontSize = 11.sp, letterSpacing = 1.sp)
                }
            }
            LanguageButton(onToggleLanguage)
        }

        Column(
            modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(top = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            InfoCard {
                Text(stringResource(R.string.home_signed_in), color = SonicColors.Muted, fontSize = 12.sp)
                Text(vm.session?.email.orEmpty(), fontWeight = FontWeight.SemiBold, fontSize = 16.sp, modifier = Modifier.padding(top = 2.dp))
            }

            vm.activeSession?.let { s ->
                InfoCard {
                    Text(stringResource(R.string.active_session_banner, s.peerDeviceName), color = SonicColors.Accent, fontWeight = FontWeight.Bold)
                    Button(onClick = { vm.go(Screen.Session) }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                        Text(stringResource(R.string.btn_open_session))
                    }
                }
            }

            DeviceCard(vm)

            val ready = vm.device != null
            ModeCard(stringResource(R.string.agent_title), stringResource(R.string.agent_desc), stringResource(R.string.btn_open_agent), ready) { vm.go(Screen.Agent) }
            ModeCard(stringResource(R.string.controller_title), stringResource(R.string.controller_desc), stringResource(R.string.btn_open_controller), ready) { vm.go(Screen.Controller) }

            InfoCard {
                Text(stringResource(R.string.safety_title), fontWeight = FontWeight.Bold, color = SonicColors.Accent)
                listOf(R.string.safety_1, R.string.safety_2, R.string.safety_3, R.string.safety_4).forEach {
                    Text("✓  " + stringResource(it), fontSize = 14.sp, modifier = Modifier.padding(top = 8.dp))
                }
            }

            Button(onClick = { vm.go(Screen.About) }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.btn_about))
            }
            OutlinedButton(onClick = { vm.signOut() }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.btn_logout))
            }
        }
        TeamSignature()
    }
}
