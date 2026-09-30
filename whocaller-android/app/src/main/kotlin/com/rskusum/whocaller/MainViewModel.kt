package com.rskusum.whocaller

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rskusum.whocaller.core.common.analytics.AnalyticsEvent
import com.rskusum.whocaller.core.common.analytics.AnalyticsTracker
import com.rskusum.whocaller.core.domain.repository.SettingsRepository
import com.rskusum.whocaller.core.model.AppSettings
import com.rskusum.whocaller.core.permissions.AppPermission
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface StartState {
    data object Loading : StartState
    data object Onboarding : StartState
    data object Permissions : StartState
    data class Ready(val settings: AppSettings) : StartState
}

@HiltViewModel
class MainViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val analytics: AnalyticsTracker,
) : ViewModel() {

    /** Keeps the branded splash on screen briefly on cold start. */
    private val splashDone = MutableStateFlow(false)

    val settings: StateFlow<AppSettings?> = settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val startState: StateFlow<StartState> = combine(settings, splashDone) { s, done ->
        when {
            s == null || !done -> StartState.Loading
            !s.onboardingCompleted -> StartState.Onboarding
            !s.permissionSetupCompleted -> StartState.Permissions
            else -> StartState.Ready(s)
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, StartState.Loading)

    init {
        viewModelScope.launch {
            delay(SPLASH_MS)
            splashDone.value = true
            analytics.track(AnalyticsEvent.AppOpened)
        }
    }

    fun completeOnboarding() = viewModelScope.launch {
        settingsRepository.update { it.copy(onboardingCompleted = true) }
    }

    fun completePermissionSetup() = viewModelScope.launch {
        settingsRepository.update { it.copy(permissionSetupCompleted = true) }
    }

    fun onPermissionResult(permission: AppPermission, granted: Boolean) {
        analytics.track(
            if (granted) AnalyticsEvent.PermissionGranted(permission.analyticsName) else AnalyticsEvent.PermissionDenied(permission.analyticsName),
        )
    }

    private companion object {
        const val SPLASH_MS = 700L
    }
}
