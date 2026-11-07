package com.sonicteam.sonicremote.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sonicteam.sonicremote.BuildConfig
import com.sonicteam.sonicremote.R

@Composable
fun AboutScreen(onBack: () -> Unit, onViewTerms: () -> Unit) {
    BackHandler(onBack = onBack)
    Column(modifier = Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 20.dp)) {
        Column(
            modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            BrandLogo(130.dp, Modifier.padding(top = 24.dp))
            Text(stringResource(R.string.app_name), fontSize = 26.sp, fontWeight = FontWeight.Bold, letterSpacing = 3.sp, modifier = Modifier.padding(top = 16.dp))
            Text(stringResource(R.string.by_team), color = SonicColors.Accent, letterSpacing = 3.sp)
            Text(stringResource(R.string.about_version, BuildConfig.VERSION_NAME), color = SonicColors.Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))

            InfoCard(modifier = Modifier.padding(top = 20.dp)) {
                Text(stringResource(R.string.about_desc), textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            }
            InfoCard(modifier = Modifier.padding(top = 12.dp)) {
                Text(stringResource(R.string.safety_title), fontWeight = FontWeight.Bold, color = SonicColors.Accent)
                listOf(R.string.safety_1, R.string.safety_2, R.string.safety_3, R.string.safety_4).forEach {
                    Text("✓  " + stringResource(it), fontSize = 14.sp, modifier = Modifier.padding(top = 8.dp))
                }
            }
            OutlinedButton(onClick = onViewTerms, modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
                Text(stringResource(R.string.about_view_terms))
            }
        }
        Button(onClick = onBack, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
            Text(stringResource(R.string.btn_back))
        }
        TeamSignature()
    }
}
