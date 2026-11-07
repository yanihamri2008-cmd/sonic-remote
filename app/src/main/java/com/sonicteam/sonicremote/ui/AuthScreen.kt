package com.sonicteam.sonicremote.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sonicteam.sonicremote.AppViewModel
import com.sonicteam.sonicremote.R
import com.sonicteam.sonicremote.data.AuthError

@StringRes
fun authErrorRes(e: AuthError): Int = when (e) {
    AuthError.InvalidCredentials -> R.string.err_invalid_credentials
    AuthError.EmailNotConfirmed -> R.string.err_email_not_confirmed
    AuthError.AlreadyRegistered -> R.string.err_already_registered
    AuthError.WeakPassword -> R.string.err_weak_password
    AuthError.RateLimited -> R.string.err_rate_limited
    AuthError.SignupDisabled -> R.string.err_signup_disabled
    AuthError.Network -> R.string.err_network
    AuthError.NotConfigured -> R.string.err_not_configured
    AuthError.Unknown -> R.string.err_unknown
}

private val EMAIL = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")

@Composable
fun AuthScreen(vm: AppViewModel, onToggleLanguage: () -> Unit) {
    var mode by rememberSaveable { mutableIntStateOf(0) } // 0 = sign in, 1 = register
    var email by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var confirm by rememberSaveable { mutableStateOf("") }
    var showPassword by rememberSaveable { mutableStateOf(false) }
    var localError by rememberSaveable { mutableStateOf<Int?>(null) }

    fun submit() {
        localError = when {
            !EMAIL.matches(email.trim()) -> R.string.err_invalid_email
            password.length < 8 -> R.string.err_short_password
            mode == 1 && password != confirm -> R.string.err_password_mismatch
            else -> null
        }
        if (localError != null) return
        if (mode == 0) vm.signIn(email, password) else vm.signUp(email, password)
    }

    Column(
        modifier = Modifier.fillMaxSize().systemBarsPadding().imePadding().padding(horizontal = 20.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            LanguageButton(onToggleLanguage)
        }
        Column(
            modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            BrandLogo(96.dp)
            Text(
                stringResource(R.string.app_name),
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 3.sp,
                modifier = Modifier.padding(top = 12.dp),
            )
            Text(stringResource(R.string.by_team), color = SonicColors.Accent, fontSize = 13.sp, letterSpacing = 2.sp)

            InfoCard(modifier = Modifier.padding(top = 20.dp)) {
                TabRow(selectedTabIndex = mode, containerColor = androidx.compose.ui.graphics.Color.Transparent) {
                    Tab(selected = mode == 0, onClick = { mode = 0; localError = null; vm.clearAuthMessages() },
                        text = { Text(stringResource(R.string.tab_login)) })
                    Tab(selected = mode == 1, onClick = { mode = 1; localError = null; vm.clearAuthMessages() },
                        text = { Text(stringResource(R.string.tab_register)) })
                }
                Text(
                    stringResource(R.string.auth_hint),
                    color = SonicColors.Muted,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 12.dp),
                )
                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it },
                    label = { Text(stringResource(R.string.field_email)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text(stringResource(R.string.field_password)) },
                    singleLine = true,
                    visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = if (mode == 1) ImeAction.Next else ImeAction.Done,
                    ),
                    trailingIcon = {
                        TextButton(onClick = { showPassword = !showPassword }) {
                            Text(stringResource(if (showPassword) R.string.hide else R.string.show))
                        }
                    },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
                if (mode == 1) {
                    OutlinedTextField(
                        value = confirm,
                        onValueChange = { confirm = it },
                        label = { Text(stringResource(R.string.field_confirm)) },
                        singleLine = true,
                        visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    )
                }

                val message: Int? = localError ?: vm.authError?.let { authErrorRes(it) }
                if (message != null) {
                    Text(stringResource(message), color = SonicColors.Danger, fontSize = 13.sp, modifier = Modifier.padding(top = 10.dp))
                }
                if (!vm.isConfigured && message == null) {
                    Text(stringResource(R.string.err_not_configured), color = SonicColors.Warning, fontSize = 13.sp, modifier = Modifier.padding(top = 10.dp))
                }
                if (vm.confirmNotice) {
                    Text(stringResource(R.string.info_confirm_email), color = SonicColors.Accent, fontSize = 13.sp, modifier = Modifier.padding(top = 10.dp))
                }

                Button(
                    onClick = { submit() },
                    enabled = !vm.authBusy && vm.isConfigured,
                    modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
                ) {
                    if (vm.authBusy) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        Text(stringResource(if (mode == 0) R.string.btn_login else R.string.btn_register))
                    }
                }
            }
            Text(
                stringResource(R.string.tagline),
                color = SonicColors.Muted,
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 16.dp),
            )
        }
        TeamSignature()
    }
}
