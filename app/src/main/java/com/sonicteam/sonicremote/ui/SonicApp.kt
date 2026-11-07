package com.sonicteam.sonicremote.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.sonicteam.sonicremote.AppViewModel
import com.sonicteam.sonicremote.Screen

@Composable
fun SonicApp(vm: AppViewModel, onToggleLanguage: () -> Unit, onExit: () -> Unit) {
    SonicTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            when (vm.screen) {
                Screen.Splash -> SplashScreen(onDone = { vm.onSplashDone() })
                Screen.Terms -> TermsScreen(
                    readOnly = false,
                    onAccept = { vm.acceptTerms() },
                    onDecline = onExit,
                    onBack = {},
                    onToggleLanguage = onToggleLanguage,
                )
                Screen.TermsView -> TermsScreen(
                    readOnly = true,
                    onAccept = {},
                    onDecline = {},
                    onBack = { vm.go(Screen.About) },
                    onToggleLanguage = onToggleLanguage,
                )
                Screen.Auth -> AuthScreen(vm, onToggleLanguage)
                Screen.Home -> HomeScreen(vm, onToggleLanguage)
                Screen.Agent -> AgentScreen(vm, onToggleLanguage)
                Screen.Controller -> ControllerScreen(vm, onToggleLanguage)
                Screen.Session -> SessionScreen(vm, onToggleLanguage)
                Screen.About -> AboutScreen(
                    onBack = { vm.go(Screen.Home) },
                    onViewTerms = { vm.go(Screen.TermsView) },
                )
            }
        }
    }
}
