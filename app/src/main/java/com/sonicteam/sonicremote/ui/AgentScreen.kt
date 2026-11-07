package com.sonicteam.sonicremote.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sonicteam.sonicremote.AppViewModel
import com.sonicteam.sonicremote.R
import com.sonicteam.sonicremote.Screen
import com.sonicteam.sonicremote.data.IncomingRequest

@Composable
private fun RequestCard(r: IncomingRequest, now: Long, busy: Boolean, onApprove: () -> Unit, onReject: () -> Unit) {
    InfoCard {
        Text(stringResource(R.string.incoming_title), color = SonicColors.Accent, fontWeight = FontWeight.Bold)
        Text(stringResource(R.string.incoming_from, r.deviceName), fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
        Text(stringResource(R.string.incoming_account, r.accountLabel), color = SonicColors.Silver, fontSize = 14.sp)
        Text(
            stringResource(R.string.incoming_expires, formatRemaining(r.expiresAtMs - now)),
            color = SonicColors.Warning,
            fontSize = 13.sp,
            modifier = Modifier.padding(top = 4.dp),
        )
        Row(modifier = Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onApprove, enabled = !busy, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.approve)) }
            OutlinedButton(onClick = onReject, enabled = !busy, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.reject)) }
        }
    }
}

@Composable
fun AgentScreen(vm: AppViewModel, onToggleLanguage: () -> Unit) {
    val now = rememberNowMs()
    ScreenFrame(
        title = stringResource(R.string.agent_screen_title),
        onBack = { vm.clearPairingMessage(); vm.go(Screen.Home) },
        onToggleLanguage = onToggleLanguage,
    ) {
        vm.device?.let { d ->
            InfoCard {
                Text(stringResource(R.string.device_code_label), color = SonicColors.Muted, fontSize = 12.sp)
                Text(prettyCode(d.pairingCode), fontSize = 30.sp, fontWeight = FontWeight.Bold, letterSpacing = 4.sp, color = SonicColors.Accent)
                Text(stringResource(R.string.device_code_hint), color = SonicColors.Silver, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
            }
        }

        val live = vm.incoming.filter { it.expiresAtMs > now }
        if (live.isEmpty()) {
            InfoCard { Text(stringResource(R.string.agent_waiting), color = SonicColors.Silver) }
        } else {
            live.forEach { r ->
                RequestCard(
                    r = r, now = now, busy = vm.pairingBusy,
                    onApprove = { vm.respond(r.id, true) },
                    onReject = { vm.respond(r.id, false) },
                )
            }
            Text(stringResource(R.string.approve_note), color = SonicColors.Muted, fontSize = 12.sp)
        }

        vm.pairingMessage?.let {
            Text(stringResource(fnErrorRes(it)), color = SonicColors.Danger, fontSize = 13.sp)
        }
    }
}
