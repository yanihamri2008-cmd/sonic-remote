package com.sonicteam.sonicremote.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sonicteam.sonicremote.AppViewModel
import com.sonicteam.sonicremote.R
import com.sonicteam.sonicremote.Screen
import com.sonicteam.sonicremote.data.Outgoing

@Composable
fun ControllerScreen(vm: AppViewModel, onToggleLanguage: () -> Unit) {
    var code by rememberSaveable { mutableStateOf("") }
    val now = rememberNowMs()
    val state = vm.outgoing
    val busy = state is Outgoing.Sending || state is Outgoing.Waiting

    ScreenFrame(
        title = stringResource(R.string.controller_screen_title),
        onBack = { vm.go(Screen.Home) },
        onToggleLanguage = onToggleLanguage,
    ) {
        InfoCard {
            Text(stringResource(R.string.controller_hint), color = SonicColors.Silver, fontSize = 14.sp)
            OutlinedTextField(
                value = code,
                onValueChange = { code = it.uppercase().filter { c -> c.isLetterOrDigit() }.take(8) },
                label = { Text(stringResource(R.string.enter_code)) },
                singleLine = true,
                enabled = !busy,
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 22.sp, letterSpacing = 4.sp, fontWeight = FontWeight.Bold),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, keyboardType = KeyboardType.Ascii),
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            )
            Button(
                onClick = { vm.sendRequest(code) },
                enabled = !busy && code.length == 8 && vm.device != null,
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            ) {
                if (state is Outgoing.Sending) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Text(stringResource(R.string.send_request))
                }
            }
        }

        when (state) {
            is Outgoing.Waiting -> InfoCard {
                Text(
                    stringResource(R.string.waiting_for, state.targetName, formatRemaining(state.expiresAtMs - now)),
                    color = SonicColors.Accent,
                )
            }
            Outgoing.Rejected -> InfoCard {
                Text(stringResource(R.string.req_rejected), color = SonicColors.Danger)
                OutlinedButton(onClick = { vm.dismissOutgoing() }, modifier = Modifier.padding(top = 8.dp)) { Text(stringResource(R.string.btn_dismiss)) }
            }
            Outgoing.Expired -> InfoCard {
                Text(stringResource(R.string.req_expired), color = SonicColors.Warning)
                OutlinedButton(onClick = { vm.dismissOutgoing() }, modifier = Modifier.padding(top = 8.dp)) { Text(stringResource(R.string.btn_dismiss)) }
            }
            is Outgoing.Failed -> InfoCard {
                Text(stringResource(fnErrorRes(state.errorKey)), color = SonicColors.Danger)
                OutlinedButton(onClick = { vm.dismissOutgoing() }, modifier = Modifier.padding(top = 8.dp)) { Text(stringResource(R.string.btn_dismiss)) }
            }
            Outgoing.None, Outgoing.Sending -> Unit
        }
    }
}
