package com.sonicteam.sonicremote.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sonicteam.sonicremote.AppViewModel
import com.sonicteam.sonicremote.R
import com.sonicteam.sonicremote.Screen

@Composable
private fun ModeCard(title: String, description: String, status: String) {
    InfoCard {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(title, fontWeight = FontWeight.Bold, fontSize = 17.sp, modifier = Modifier.weight(1f))
            Text(
                status,
                color = SonicColors.Warning,
                fontSize = 11.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(SonicColors.SurfaceHigh)
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }
        Text(description, color = SonicColors.Silver, fontSize = 14.sp, modifier = Modifier.padding(top = 6.dp))
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

        Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            InfoCard {
                Text(stringResource(R.string.home_signed_in), color = SonicColors.Muted, fontSize = 12.sp)
                Text(vm.session?.email.orEmpty(), fontWeight = FontWeight.SemiBold, fontSize = 16.sp, modifier = Modifier.padding(top = 2.dp))
            }

            ModeCard(stringResource(R.string.agent_title), stringResource(R.string.agent_desc), stringResource(R.string.coming_soon))
            ModeCard(stringResource(R.string.controller_title), stringResource(R.string.controller_desc), stringResource(R.string.coming_soon))

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
