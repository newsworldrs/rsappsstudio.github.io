package com.rskusum.whocaller.firebase

import android.app.Activity
import android.content.Context
import com.google.firebase.FirebaseException
import com.google.firebase.FirebaseTooManyRequestsException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.PhoneAuthCredential
import com.google.firebase.auth.PhoneAuthOptions
import com.google.firebase.auth.PhoneAuthProvider
import com.rskusum.whocaller.core.common.result.AppError
import com.rskusum.whocaller.core.common.result.AppResult
import com.rskusum.whocaller.core.model.UserProfile
import com.rskusum.whocaller.feature.profile.PhoneAuthGateway
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
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
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    @Volatile private var verificationId: String? = null

    override val isAvailable: Boolean get() = auth != null

    override suspend fun sendCode(activity: Activity, e164: String): AppResult<Boolean> {
        val a = auth ?: return AppResult.Failure(AppError.BACKEND_NOT_CONFIGURED)
        return suspendCancellableCoroutine { cont ->
            val callbacks = object : PhoneAuthProvider.OnVerificationStateChangedCallbacks() {
                override fun onVerificationCompleted(credential: PhoneAuthCredential) {
                    // Instant verification / auto-retrieval: link to the account (or sign in).
                    scope.launch {
                        val r = try {
                            useCredential(a, credential); AppResult.Success(true)
                        } catch (e: Exception) {
                            AppResult.Failure(map(e), e)
                        }
                        if (cont.isActive) cont.resume(r)
                    }
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
            val user = useCredential(a, PhoneAuthProvider.getCredential(id, code))
                ?: return AppResult.Failure(AppError.UNAUTHORIZED)
            AppResult.Success(UserProfile(user.uid, user.displayName, user.email, user.phoneNumber, isGuest = false))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppResult.Failure(map(e), e)
        }
    }

    /**
     * Signed in with Google or email: add the phone number to that account (so it becomes the
     * user's verified WhoCaller ID). Otherwise sign in with the phone number itself.
     */
    private suspend fun useCredential(a: FirebaseAuth, credential: PhoneAuthCredential): FirebaseUser? {
        val user = a.currentUser?.takeIf { !it.isAnonymous }
        when {
            user == null -> a.signInWithCredential(credential).await()
            user.phoneNumber != null -> user.updatePhoneNumber(credential).await()
            else -> user.linkWithCredential(credential).await()
        }
        a.currentUser?.reload()?.await()
        // New token so Firestore rules see the verified phone_number claim.
        a.currentUser?.getIdToken(true)?.await()
        return a.currentUser
    }

    private fun map(e: Exception): AppError = when (e) {
        is com.google.firebase.auth.FirebaseAuthUserCollisionException -> AppError.DUPLICATE
        is FirebaseTooManyRequestsException -> AppError.RATE_LIMITED
        is FirebaseAuthInvalidCredentialsException -> AppError.INVALID_NUMBER
        is com.google.firebase.FirebaseNetworkException -> AppError.NETWORK_UNAVAILABLE
        else -> AppError.UNKNOWN
    }
}
