package com.rskusum.whocaller.firebase

import android.content.Context
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.rskusum.whocaller.core.common.result.AppError
import com.rskusum.whocaller.core.common.result.AppResult
import com.rskusum.whocaller.core.domain.repository.WhoCallerIdRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

/**
 * WhoCaller ID in Firestore:
 *  - whocallerUsers/{uid}          the account: name, verified number, email (owner only)
 *  - registeredCallers/{E.164}     public caller ID: the name other users see when this person calls
 * Security rules only accept a number equal to the account's verified phone (auth token), so nobody
 * can put their name on someone else's number.
 */
@Singleton
class FirestoreWhoCallerIdRepository @Inject constructor(
    @ApplicationContext context: Context,
) : WhoCallerIdRepository {

    private val available = context.isFirebaseAvailable()
    private val auth: FirebaseAuth? = if (available) FirebaseAuth.getInstance() else null
    private val db: FirebaseFirestore? = if (available) FirebaseFirestore.getInstance() else null

    override val isAvailable: Boolean get() = available

    override suspend fun save(name: String, e164: String, showName: Boolean): AppResult<Unit> = call { a, db ->
        val user = a.currentUser ?: return@call AppResult.Failure(AppError.UNAUTHORIZED)
        if (user.phoneNumber != e164) return@call AppResult.Failure(AppError.UNAUTHORIZED)
        // Fresh token so the rules see the newly verified phone number.
        user.getIdToken(true).await()
        val cleanName = name.trim().take(MAX_NAME)
        val account = db.collection(USERS).document(user.uid)
        val exists = account.get().await().exists()
        account.set(
            buildMap {
                put("name", cleanName)
                put("phoneNumber", e164)
                put("email", user.email.orEmpty())
                put("showNameToCallers", showName)
                put("updatedAt", FieldValue.serverTimestamp())
                if (!exists) put("createdAt", FieldValue.serverTimestamp())
            },
            SetOptions.merge(),
        ).await()
        val caller = db.collection(CALLERS).document(e164)
        if (showName) {
            caller.set(mapOf("name" to cleanName, "uid" to user.uid, "updatedAt" to FieldValue.serverTimestamp())).await()
        } else {
            runCatching { caller.delete().await() }
        }
        AppResult.Success(Unit)
    }

    override suspend fun load(): AppResult<Pair<String, String>?> = call { a, db ->
        val user = a.currentUser ?: return@call AppResult.Success(null)
        val doc = db.collection(USERS).document(user.uid).get().await()
        val name = doc.getString("name")
        val phone = doc.getString("phoneNumber")
        AppResult.Success(if (name != null && phone != null && phone == user.phoneNumber) name to phone else null)
    }

    private suspend fun <T> call(block: suspend (FirebaseAuth, FirebaseFirestore) -> AppResult<T>): AppResult<T> {
        val a = auth ?: return AppResult.Failure(AppError.BACKEND_NOT_CONFIGURED)
        val d = db ?: return AppResult.Failure(AppError.BACKEND_NOT_CONFIGURED)
        return try {
            block(a, d)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppResult.Failure(AppError.SERVER, e)
        }
    }

    companion object {
        const val USERS = "whocallerUsers"
        const val CALLERS = "registeredCallers"
        private const val MAX_NAME = 60

        /** Removes the account's WhoCaller ID (account deletion). */
        suspend fun deleteFor(db: FirebaseFirestore, uid: String, phone: String?) {
            if (phone != null) {
                runCatching {
                    val caller = db.collection(CALLERS).document(phone).get().await()
                    if (caller.getString("uid") == uid) caller.reference.delete().await()
                }
            }
            runCatching { db.collection(USERS).document(uid).delete().await() }
        }
    }
}
