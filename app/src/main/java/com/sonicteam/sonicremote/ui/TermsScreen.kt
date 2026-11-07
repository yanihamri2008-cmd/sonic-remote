package com.sonicteam.sonicremote.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.activity.compose.BackHandler
import com.sonicteam.sonicremote.R

private val sections = listOf(
    R.string.t1_title to R.string.t1_body,
    R.string.t2_title to R.string.t2_body,
    R.string.t3_title to R.string.t3_body,
    R.string.t4_title to R.string.t4_body,
    R.string.t5_title to R.string.t5_body,
    R.string.t6_title to R.string.t6_body,
    R.string.t7_title to R.string.t7_body,
    R.string.t8_title to R.string.t8_body,
)

/**
 * First-launch terms and disclaimer. In [readOnly] mode (opened from About) it only shows the text.
 * The user cannot continue until the agreement box is checked.
 */
@Composable
fun TermsScreen(
    readOnly: Boolean,
    onAccept: () -> Unit,
    onDecline: () -> Unit,
    onBack: () -> Unit,
    onToggleLanguage: () -> Unit,
) {
    var agreed by rememberSaveable { mutableStateOf(false) }
    if (readOnly) BackHandler(onBack = onBack)

    Column(modifier = Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 20.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                BrandLogo(36.dp)
                Text(stringResource(R.string.terms_title), fontSize = 20.sp, fontWeight = FontWeight.Bold)
            }
            LanguageButton(onToggleLanguage)
        }

        InfoCard(modifier = Modifier.weight(1f).padding(top = 8.dp)) {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.terms_intro), color = SonicColors.Silver, fontSize = 14.sp)
                sections.forEach { (title, body) ->
                    Text(
                        stringResource(title),
                        fontWeight = FontWeight.Bold,
                        color = SonicColors.Accent,
                        modifier = Modifier.padding(top = 16.dp),
                    )
                    Text(stringResource(body), fontSize = 14.sp, lineHeight = 21.sp, modifier = Modifier.padding(top = 4.dp))
                }
                Text(
                    stringResource(R.string.terms_version_note),
                    color = SonicColors.Muted,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 20.dp),
                )
            }
        }

        if (readOnly) {
            Button(onClick = onBack, modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
                Text(stringResource(R.string.btn_back))
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth().clickable { agreed = !agreed }.padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(checked = agreed, onCheckedChange = { agreed = it })
                Text(stringResource(R.string.terms_agree), fontSize = 13.sp, lineHeight = 18.sp)
            }
            Button(
                onClick = onAccept,
                enabled = agreed,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            ) { Text(stringResource(R.string.terms_accept)) }
            OutlinedButton(
                onClick = onDecline,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 8.dp),
            ) { Text(stringResource(R.string.terms_decline)) }
        }
    }
}
