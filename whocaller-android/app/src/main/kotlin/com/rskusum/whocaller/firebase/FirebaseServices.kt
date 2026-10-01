package com.rskusum.whocaller.firebase

import android.content.Context
import android.os.Bundle
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.FirebaseTooManyRequestsException
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthInvalidUserException
import com.google.firebase.auth.FirebaseAuthRecentLoginRequiredException
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import com.google.firebase.auth.FirebaseAuthWeakPasswordException
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.auth.UserProfileChangeRequest
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.rskusum.whocaller.core.common.analytics.AnalyticsEvent
import com.rskusum.whocaller.core.common.analytics.AnalyticsTracker
import com.rskusum.whocaller.core.common.analytics.CrashReporter
import com.rskusum.whocaller.core.common.result.AppError
import com.rskusum.whocaller.core.common.result.AppResult
import com.rskusum.whocaller.core.domain.repository.AuthRepository
import com.rskusum.whocaller.core.model.UserProfile
import com.rskusum.whocaller.core.network.AppCheckTokenProvider
import com.rskusum.whocaller.core.network.NetworkDataSource
import dagger.Lazy
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/** True when google-services.json was present at build time and Firebase initialised. */
fun Context.isFirebaseAvailable(): Boolean = FirebaseApp.getApps(this).isNotEmpty()

/**
 * Firebase Authentication. Without Firebase config every user is a guest and local features keep
 * working. The backend receives the Firebase ID token as a Bearer token (short-lived, auto-refreshed).
 */
@Singleton
class FirebaseAuthRepository @Inject constructor(
    @ApplicationContext context: Context,
    private val network: Lazy<NetworkDataSource>,
) : AuthRepository {

    private val auth: FirebaseAuth? = if (context.isFirebaseAvailable()) FirebaseAuth.getInstance() else null

    override val isBackendAvailable: Boolean = auth != null

    override val currentUser: Flow<UserProfile> = auth?.let { a ->
        callbackFlow {
            // ID-token listener: also fires after a phone number is linked (token refresh), not only on sign-in/out.
            val listener = FirebaseAuth.IdTokenListener { trySend(it.currentUser.toProfile()) }
            a.addIdTokenListener(listener)
            awaitClose { a.removeIdTokenListener(listener) }
        }.conflate().distinctUntilChanged()
    } ?: flowOf(UserProfile.GUEST)

    override suspend fun signInWithGoogleIdToken(idToken: String): AppResult<UserProfile> = authCall { a ->
        a.signInWithCredential(GoogleAuthProvider.getCredential(idToken, null)).await().user.toProfile()
    }

    override suspend fun signInWithEmail(email: String, password: String): AppResult<UserProfile> = authCall { a ->
        a.signInWithEmailAndPassword(email.trim(), password).await().user.toProfile()
    }

    override suspend fun createAccountWithEmail(email: String, password: String): AppResult<UserProfile> = authCall { a ->
        a.createUserWithEmailAndPassword(email.trim(), password).await().user.toProfile()
    }

    override suspend fun sendPasswordReset(email: String): AppResult<Unit> = authCall { a ->
        a.sendPasswordResetEmail(email.trim()).await()
        Unit
    }

    override suspend fun updateDisplayName(name: String): AppResult<Unit> = authCall { a ->
        val user = a.currentUser ?: throw FirebaseAuthInvalidUserException("no_user", "Not signed in")
        user.updateProfile(UserProfileChangeRequest.Builder().setDisplayName(name.trim().take(60)).build()).await()
        Unit
    }

    override suspend fun signOut() {
        auth?.signOut()
    }

    override suspend fun deleteAccount(): AppResult<Unit> = authCall { a ->
        val user = a.currentUser ?: throw FirebaseAuthInvalidUserException("no_user", "Not signed in")
        // Ask the backend to delete/anonymise server-side data first, then remove the auth account.
        network.get().deleteAccount()
        user.delete().await()
        Unit
    }

    override suspend fun idToken(forceRefresh: Boolean): String? {
        val user = auth?.currentUser ?: return null
        return try {
            withTimeoutOrNull(TOKEN_TIMEOUT_MS) { user.getIdToken(forceRefresh).await().token }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun <T> authCall(block: suspend (FirebaseAuth) -> T): AppResult<T> {
        val a = auth ?: return AppResult.Failure(AppError.BACKEND_NOT_CONFIGURED)
        return try {
            AppResult.Success(block(a))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppResult.Failure(mapError(e), e)
        }
    }

    private fun mapError(e: Exception): AppError = when (e) {
        is FirebaseNetworkException -> AppError.NETWORK_UNAVAILABLE
        is FirebaseTooManyRequestsException -> AppError.RATE_LIMITED
        is FirebaseAuthUserCollisionException -> AppError.DUPLICATE
        is FirebaseAuthWeakPasswordException -> AppError.UNKNOWN
        is FirebaseAuthRecentLoginRequiredException -> AppError.UNAUTHORIZED
        is FirebaseAuthInvalidCredentialsException -> AppError.UNAUTHORIZED
        is FirebaseAuthInvalidUserException -> AppError.UNAUTHORIZED
        else -> AppError.UNKNOWN
    }

    // Anonymous sign-in (used for caller lookups and reports) still counts as a guest.
    private fun FirebaseUser?.toProfile(): UserProfile = if (this == null || isAnonymous) {
        UserProfile.GUEST
    } else {
        UserProfile(
            uid = uid,
            name = displayName,
            email = email,
            phone = phoneNumber,
            isGuest = false,
            photoUrl = photoUrl?.toString(),
        )
    }

    private companion object {
        const val TOKEN_TIMEOUT_MS = 3_000L
    }
}

/** Firebase Analytics, off until the user opts in (see manifest meta-data and Privacy settings). */
@Singleton
class FirebaseAnalyticsTracker @Inject constructor(
    @ApplicationContext private val context: Context,
) : AnalyticsTracker {
    private val analytics: FirebaseAnalytics? by lazy {
        if (context.isFirebaseAvailable()) FirebaseAnalytics.getInstance(context) else null
    }
    @Volatile private var enabled = false

    override fun track(event: AnalyticsEvent) {
        if (!enabled) return
        val params = Bundle().apply { event.params.forEach { (k, v) -> putString(k, v.take(100)) } }
        analytics?.logEvent(event.name, params)
    }

    override fun setEnabled(enabled: Boolean) {
        this.enabled = enabled
        analytics?.setAnalyticsCollectionEnabled(enabled)
    }
}

/** Crashlytics, off until the user opts in. Never given phone numbers, contacts or message text. */
@Singleton
class FirebaseCrashReporter @Inject constructor(
    @ApplicationContext private val context: Context,
) : CrashReporter {
    private val crashlytics: FirebaseCrashlytics? by lazy {
        if (context.isFirebaseAvailable()) FirebaseCrashlytics.getInstance() else null
    }

    override fun recordNonFatal(throwable: Throwable, context: String) {
        crashlytics?.let {
            it.setCustomKey("where", context)
            it.recordException(throwable)
        }
    }

    override fun log(message: String) {
        crashlytics?.log(message)
    }

    override fun setEnabled(enabled: Boolean) {
        crashlytics?.setCrashlyticsCollectionEnabled(enabled)
    }
}

/** Firebase App Check token for the backend, or null when Firebase isn't configured. */
@Singleton
class FirebaseAppCheckTokenProvider @Inject constructor(
    @ApplicationContext private val context: Context,
) : AppCheckTokenProvider {
    override suspend fun token(): String? {
        if (!context.isFirebaseAvailable()) return null
        return try {
            withTimeoutOrNull(3_000L) { FirebaseAppCheck.getInstance().getAppCheckToken(false).await().token }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }
}
