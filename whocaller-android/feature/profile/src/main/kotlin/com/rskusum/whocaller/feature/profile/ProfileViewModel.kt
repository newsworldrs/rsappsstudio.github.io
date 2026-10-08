package com.rskusum.whocaller.feature.profile

import android.app.Activity
import android.util.Patterns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rskusum.whocaller.core.common.phone.NormalizationResult
import com.rskusum.whocaller.core.common.phone.PhoneNumberNormalizer
import com.rskusum.whocaller.core.common.result.AppError
import com.rskusum.whocaller.core.common.result.AppResult
import com.rskusum.whocaller.core.domain.repository.AuthRepository
import com.rskusum.whocaller.core.domain.repository.CountryRepository
import com.rskusum.whocaller.core.domain.repository.LocalProfileRepository
import com.rskusum.whocaller.core.model.LocalProfile
import com.rskusum.whocaller.core.domain.repository.StatsRepository
import com.rskusum.whocaller.core.model.UserProfile
import com.rskusum.whocaller.core.model.UserStats
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Phone-number sign-in needs an Activity (reCAPTCHA / Play Integrity), so the app module supplies
 * the implementation (Firebase PhoneAuthProvider).
 */
interface PhoneAuthGateway {
    val isAvailable: Boolean
    /** Sends an SMS code. Success(true) means the device verified automatically and the user is signed in. */
    suspend fun sendCode(activity: Activity, e164: String): AppResult<Boolean>
    suspend fun verifyCode(code: String): AppResult<UserProfile>
}

/** Build-time configuration passed from the app module. */
data class SignInConfig(
    val googleWebClientId: String,
    /** App logo shown on the welcome screen (0 = a generic icon). */
    @androidx.annotation.DrawableRes val logoRes: Int = 0,
)

data class SignInUiState(
    val email: String = "",
    val password: String = "",
    val phone: String = "",
    val code: String = "",
    val codeSent: Boolean = false,
    val busy: Boolean = false,
    val error: AppError? = null,
    val validation: Int? = null,
    val info: Int? = null,
    val signedIn: Boolean = false,
)

@HiltViewModel
class ProfileViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val localProfileRepository: LocalProfileRepository,
    private val phoneAuth: PhoneAuthGateway,
    private val normalizer: PhoneNumberNormalizer,
    private val countryRepository: CountryRepository,
    private val whoCallerId: com.rskusum.whocaller.core.domain.repository.WhoCallerIdRepository,
    statsRepository: StatsRepository,
) : ViewModel() {

    val user: StateFlow<UserProfile> = authRepository.currentUser
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UserProfile.GUEST)

    val localProfile: StateFlow<LocalProfile> = localProfileRepository.profile
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LocalProfile())

    // NonCancellable: the screen closes right after Save, which clears this ViewModel.
    fun saveProfile(name: String, profession: String, institute: String, email: String, avatarId: Int?, showName: Boolean = true) = viewModelScope.launch(kotlinx.coroutines.NonCancellable) {
        val cleanEmail = email.trim()
        if (cleanEmail.isNotEmpty() && !validEmail(cleanEmail)) {
            _toasts.emit(R.string.signin_invalid_email)
            return@launch
        }
        val before = localProfileRepository.profile.first()
        localProfileRepository.update {
            it.copy(name = name, profession = profession, institute = institute, email = cleanEmail, avatarId = avatarId, showNameToCallers = showName)
        }
        // Keep the account display name and the WhoCaller ID (Firestore) in sync when signed in.
        if (!user.value.isGuest && name.isNotBlank()) authRepository.updateDisplayName(name)
        if (before.phoneVerified && before.phoneNumber.isNotBlank() && name.trim().length >= 2 &&
            (before.name != name || before.showNameToCallers != showName) && whoCallerId.isAvailable
        ) {
            whoCallerId.save(name, before.phoneNumber, showName)
        }
        _toasts.emit(R.string.profile_saved)
    }

    fun choosePhoto(uri: String) = viewModelScope.launch {
        if (localProfileRepository.importPhoto(uri) is AppResult.Failure) _toasts.emit(R.string.profile_photo_failed)
    }

    fun removePhoto() = viewModelScope.launch { localProfileRepository.removePhoto() }

    val stats: StateFlow<UserStats> = statsRepository.stats
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UserStats())

    val accountsAvailable: Boolean get() = authRepository.isBackendAvailable
    val phoneAvailable: Boolean get() = phoneAuth.isAvailable

    private val _signIn = MutableStateFlow(SignInUiState())
    val signIn: StateFlow<SignInUiState> = _signIn.asStateFlow()

    private val _toasts = MutableSharedFlow<Int>(extraBufferCapacity = 2)
    val toasts: SharedFlow<Int> = _toasts.asSharedFlow()

    fun edit(transform: (SignInUiState) -> SignInUiState) =
        _signIn.update { transform(it).copy(error = null, validation = null, info = null) }

    fun signInWithGoogleToken(idToken: String) = authenticate { authRepository.signInWithGoogleIdToken(idToken) }

    fun googleFailed(error: AppError) = _signIn.update { it.copy(error = error, busy = false) }

    fun signInWithEmail(create: Boolean) {
        val s = _signIn.value
        if (!validEmail(s.email)) return _signIn.update { it.copy(validation = R.string.signin_invalid_email) }
        if (s.password.length < MIN_PASSWORD) return _signIn.update { it.copy(validation = R.string.signin_weak_password) }
        authenticate {
            if (create) authRepository.createAccountWithEmail(s.email, s.password) else authRepository.signInWithEmail(s.email, s.password)
        }
    }

    fun resetPassword() {
        val s = _signIn.value
        if (!validEmail(s.email)) return _signIn.update { it.copy(validation = R.string.signin_invalid_email) }
        viewModelScope.launch {
            _signIn.update { it.copy(busy = true) }
            // Same message whether or not the account exists (no account enumeration).
            authRepository.sendPasswordReset(s.email)
            _signIn.update { it.copy(busy = false, info = R.string.signin_reset_sent) }
        }
    }

    fun sendCode(activity: Activity) {
        viewModelScope.launch {
            val parsed = normalizer.normalize(_signIn.value.phone, countryRepository.defaultRegion()) as? NormalizationResult.Parsed
            val number = parsed?.number
            val e164 = number?.e164
            if (number == null || e164 == null || !number.isValid) {
                _signIn.update { it.copy(error = AppError.INVALID_NUMBER) }
                return@launch
            }
            _signIn.update { it.copy(busy = true) }
            when (val r = phoneAuth.sendCode(activity, e164)) {
                is AppResult.Success -> _signIn.update {
                    if (r.data) it.copy(busy = false, signedIn = true) else it.copy(busy = false, codeSent = true, info = R.string.signin_code_sent)
                }
                is AppResult.Failure -> _signIn.update { it.copy(busy = false, error = r.error) }
            }
        }
    }

    fun verifyCode() = authenticate { phoneAuth.verifyCode(_signIn.value.code.trim()) }

    fun signOut() = viewModelScope.launch { authRepository.signOut() }

    fun updateName(name: String) = viewModelScope.launch {
        if (name.isNotBlank()) authRepository.updateDisplayName(name)
    }

    private fun authenticate(block: suspend () -> AppResult<*>) {
        viewModelScope.launch {
            _signIn.update { it.copy(busy = true, error = null) }
            when (val r = block()) {
                is AppResult.Success -> {
                    _signIn.update { it.copy(busy = false, signedIn = true) }
                    _toasts.emit(R.string.signin_success)
                }
                is AppResult.Failure -> _signIn.update { it.copy(busy = false, error = r.error) }
            }
        }
    }

    private fun validEmail(email: String) = Patterns.EMAIL_ADDRESS.matcher(email.trim()).matches()

    private companion object {
        const val MIN_PASSWORD = 8
    }
}
