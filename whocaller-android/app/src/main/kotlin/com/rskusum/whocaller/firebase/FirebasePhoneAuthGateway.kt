package com.rskusum.whocaller.firebase

import android.app.Activity
import android.content.Context
import com.google.firebase.FirebaseException
import com.google.firebase.FirebaseTooManyRequestsException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.PhoneAuthCredential
import com.google.firebase.auth.PhoneAuthOptions
import com.google.firebase.auth.PhoneAuthProvider
import com.rskusum.whocaller.core.common.result.AppError
import com.rskusum.whocaller.core.common.result.AppResult
import com.rskusum.whocaller.core.model.UserProfile
import com.rskusum.whocaller.feature.profile.PhoneAuthGateway
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.tasks.await
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/** Firebase phone-number verification (SMS code). Firebase applies its own abuse protection and quotas. */
@Singleton
class FirebasePhoneAuthGateway @Inject constructor(
    @ApplicationContext context: Context,
) : PhoneAuthGateway {

    private val auth: FirebaseAuth? = if (context.isFirebaseAvailable()) FirebaseAuth.getInstance() else null
    @Volatile private var verificationId: String? = null

    override val isAvailable: Boolean get() = auth != null

    override suspend fun sendCode(activity: Activity, e164: String): AppResult<Boolean> {
        val a = auth ?: return AppResult.Failure(AppError.BACKEND_NOT_CONFIGURED)
        return suspendCancellableCoroutine { cont ->
            val callbacks = object : PhoneAuthProvider.OnVerificationStateChangedCallbacks() {
                override fun onVerificationCompleted(credential: PhoneAuthCredential) {
                    // Instant verification / auto-retrieval: sign in directly.
                    a.signInWithCredential(credential)
                        .addOnSuccessListener { if (cont.isActive) cont.resume(AppResult.Success(true)) }
                        .addOnFailureListener { if (cont.isActive) cont.resume(AppResult.Failure(map(it), it)) }
                }

                override fun onVerificationFailed(e: FirebaseException) {
                    if (cont.isActive) cont.resume(AppResult.Failure(map(e), e))
                }

                override fun onCodeSent(id: String, token: PhoneAuthProvider.ForceResendingToken) {
                    verificationId = id
                    if (cont.isActive) cont.resume(AppResult.Success(false))
                }
            }
            val options = PhoneAuthOptions.newBuilder(a)
                .setPhoneNumber(e164)
                .setTimeout(60L, TimeUnit.SECONDS)
                .setActivity(activity)
                .setCallbacks(callbacks)
                .build()
            PhoneAuthProvider.verifyPhoneNumber(options)
        }
    }

    override suspend fun verifyCode(code: String): AppResult<UserProfile> {
        val a = auth ?: return AppResult.Failure(AppError.BACKEND_NOT_CONFIGURED)
        val id = verificationId ?: return AppResult.Failure(AppError.UNAUTHORIZED)
        return try {
            val user = a.signInWithCredential(PhoneAuthProvider.getCredential(id, code)).await().user
                ?: return AppResult.Failure(AppError.UNAUTHORIZED)
            AppResult.Success(UserProfile(user.uid, user.displayName, user.email, user.phoneNumber, isGuest = false))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppResult.Failure(map(e), e)
        }
    }

    private fun map(e: Exception): AppError = when (e) {
        is FirebaseTooManyRequestsException -> AppError.RATE_LIMITED
        is FirebaseAuthInvalidCredentialsException -> AppError.INVALID_NUMBER
        is com.google.firebase.FirebaseNetworkException -> AppError.NETWORK_UNAVAILABLE
        else -> AppError.UNKNOWN
    }
}
