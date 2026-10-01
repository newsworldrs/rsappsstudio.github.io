package com.rskusum.whocaller

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rskusum.whocaller.core.common.analytics.AnalyticsEvent
import com.rskusum.whocaller.core.common.analytics.AnalyticsTracker
import com.rskusum.whocaller.core.domain.repository.AuthRepository
import com.rskusum.whocaller.core.domain.repository.LocalProfileRepository
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
    /** Sign in with Google or email (required for the WhoCaller ID). */
    data object Registration : StartState
    /** Name + OTP-verified mobile number (the WhoCaller ID). */
    data object CompleteProfile : StartState
    data class Ready(val settings: AppSettings) : StartState
}

@HiltViewModel
class MainViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val analytics: AnalyticsTracker,
    private val authRepository: AuthRepository,
    localProfileRepository: LocalProfileRepository,
) : ViewModel() {

    /** Keeps the branded splash on screen briefly on cold start. */
    private val splashDone = MutableStateFlow(false)

    val settings: StateFlow<AppSettings?> = settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** Test builds only: lets a tester continue when Firebase sign-in isn't set up yet. */
    private val registrationSkipped = MutableStateFlow(false)

    private val account = combine(
        authRepository.currentUser,
        localProfileRepository.profile,
        registrationSkipped,
    ) { user, profile, skipped -> Triple(user, profile, skipped) }

    val startState: StateFlow<StartState> = combine(settings, splashDone, account) { s, done, (user, profile, skipped) ->
        val accountsOn = authRepository.isBackendAvailable && !skipped
        when {
            s == null || !done -> StartState.Loading
            !s.onboardingCompleted -> StartState.Onboarding
            !s.permissionSetupCompleted -> StartState.Permissions
            accountsOn && user.isGuest -> StartState.Registration
            // The verified number must belong to the signed-in account (sign-out / account switch).
            accountsOn && !(profile.isComplete && profile.phoneNumber == user.phone) -> StartState.CompleteProfile
            else -> StartState.Ready(s)
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, StartState.Loading)

    fun skipRegistration() {
        registrationSkipped.value = true
    }

    fun signOut() = viewModelScope.launch { authRepository.signOut() }

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
