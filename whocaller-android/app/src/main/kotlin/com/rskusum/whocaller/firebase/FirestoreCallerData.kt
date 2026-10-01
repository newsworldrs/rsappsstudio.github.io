package com.rskusum.whocaller.firebase

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.Query
import com.rskusum.whocaller.BuildConfig
import com.rskusum.whocaller.core.common.result.AppError
import com.rskusum.whocaller.core.common.result.AppResult
import com.rskusum.whocaller.core.model.ReportCallType
import com.rskusum.whocaller.core.model.ReportCategory
import com.rskusum.whocaller.core.model.SpamCategory
import com.rskusum.whocaller.core.network.NetworkDataSource
import com.rskusum.whocaller.core.network.SmsModelDto
import com.rskusum.whocaller.core.network.model.NumberInfoDto
import com.rskusum.whocaller.core.network.model.ReportRequestDto
import com.rskusum.whocaller.core.network.model.ReportResponseDto
import com.rskusum.whocaller.core.network.model.SpamListResponseDto
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.tasks.await
import kotlin.math.ln
import kotlin.math.min

/**
 * Caller data straight from Firestore (see backend/FIRESTORE.md):
 *  - lookups read `callerNumbers/{E.164}` (your uploaded dataset + community summary),
 *  - reports write `reports/{E.164 digits}_{uid}` (one per user per number).
 * Users are signed in anonymously when they have no account. Everything else (businesses,
 * billing, …) still goes to [base], the REST/dev backend from BuildConfig.
 */
class FirestoreNetworkDataSource(
    private val base: NetworkDataSource,
    private val db: FirebaseFirestore = FirebaseFirestore.getInstance(),
    private val auth: FirebaseAuth = FirebaseAuth.getInstance(),
) : NetworkDataSource by base {

    override val isConfigured: Boolean = true

    /** Current uid, signing in anonymously if needed. Null when anonymous sign-in isn't enabled. */
    private suspend fun uid(): String? = auth.currentUser?.uid ?: try {
        auth.signInAnonymously().await().user?.uid
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    override suspend fun getNumber(e164: String): AppResult<NumberInfoDto> = firestore {
        if (!E164.matches(e164)) return@firestore AppResult.Failure(AppError.INVALID_NUMBER)
        uid()
        val snap = db.collection(CALLERS).document(e164).get().await()
        // Name and current network a WhoCaller user published for their own (verified) number.
        val registeredDoc = runCatching {
            db.collection(FirestoreWhoCallerIdRepository.CALLERS).document(e164).get().await()
        }.getOrNull()
        val registered = registeredDoc?.getString("name")?.takeIf { it.isNotBlank() }
        // Confirmed by the number's owner from their SIM: correct even after the number was ported.
        val currentCarrier = registeredDoc?.getString("carrier")?.takeIf { it.isNotBlank() }
        when {
            snap.exists() -> {
                val info = snap.toNumberInfo(e164)
                // A business name from the dataset wins; otherwise the person's own registered name.
                val named = if (registered != null && info.identityType != "BUSINESS") info.copy(name = registered, identityType = "PERSON") else info
                AppResult.Success(named.copy(carrier = currentCarrier ?: named.carrier))
            }
            registered != null || currentCarrier != null -> AppResult.Success(
                NumberInfoDto(
                    number = e164,
                    name = registered,
                    identityType = if (registered != null) "PERSON" else "UNKNOWN",
                    confidence = if (registered != null) 0.9f else null,
                    carrier = currentCarrier,
                ),
            )
            base.isConfigured -> base.getNumber(e164)
            else -> AppResult.Failure(AppError.NOT_FOUND)
        }
    }

    override suspend fun reportNumber(e164: String, request: ReportRequestDto): AppResult<ReportResponseDto> = firestore {
        if (!E164.matches(e164)) return@firestore AppResult.Failure(AppError.INVALID_NUMBER)
        val uid = uid() ?: return@firestore AppResult.Failure(AppError.UNAUTHORIZED)
        val categories = request.categories.mapNotNull { ReportCategory.fromWire(it)?.name }.distinct().take(ReportCategory.MAX_PER_REPORT)
        if (categories.isEmpty()) return@firestore AppResult.Failure(AppError.UNKNOWN)
        val callType = ReportCallType.entries.firstOrNull { it.name == request.callType } ?: ReportCallType.MANUAL
        val id = e164.removePrefix("+") + "_" + uid
        // Field set must match the `validReport` security rule exactly.
        db.collection(REPORTS).document(id).set(
            mapOf(
                "phoneNumber" to e164,
                "userId" to uid,
                "categories" to categories,
                "callType" to callType.name,
                "callAnswered" to (request.callAnswered ?: false),
                "reportedAt" to FieldValue.serverTimestamp(),
                "appVersion" to BuildConfig.VERSION_NAME.take(32),
            ),
        ).await()
        AppResult.Success(ReportResponseDto(reportId = id, accepted = true))
    }

    /** Removes this user's reports (they're the only per-user data in Firestore), then the base backend's data. */
    override suspend fun deleteAccount(): AppResult<Unit> = firestore {
        val uid = auth.currentUser?.uid
        if (uid != null) FirestoreWhoCallerIdRepository.deleteFor(db, uid, auth.currentUser?.phoneNumber)
        if (uid != null) {
            val mine = db.collection(REPORTS).whereEqualTo("userId", uid).get().await()
            mine.documents.chunked(400).forEach { chunk ->
                val batch = db.batch()
                chunk.forEach { batch.delete(it.reference) }
                batch.commit().await()
            }
        }
        if (base.isConfigured) base.deleteAccount() else AppResult.Success(Unit)
    }

    /** Published SMS spam model (appConfig/smsSpamModel: version + model text). */
    override suspend fun getSmsSpamModel(): AppResult<SmsModelDto> = firestore {
        uid()
        val snap = db.collection("appConfig").document("smsSpamModel").get().await()
        val version = snap.getLong("version")?.toInt()
        val model = snap.getString("model")
        if (!snap.exists() || version == null || model.isNullOrBlank()) {
            AppResult.Failure(AppError.NOT_FOUND)
        } else {
            AppResult.Success(SmsModelDto(version, model))
        }
    }

    /** Most-reported numbers of a region, cached on the phone for offline protection. */
    override suspend fun getSpamList(region: String, since: Long?): AppResult<SpamListResponseDto> = firestore {
        uid()
        val snap = db.collection(CALLERS)
            .whereEqualTo("region", region.uppercase())
            .whereGreaterThanOrEqualTo("spamScore", SPAM_LIST_MIN_SCORE)
            .orderBy("spamScore", Query.Direction.DESCENDING)
            .limit(SPAM_LIST_LIMIT)
            .get()
            .await()
        AppResult.Success(
            SpamListResponseDto(
                region = region,
                generatedAt = System.currentTimeMillis(),
                entries = snap.documents.map { it.toNumberInfo(it.id) },
            ),
        )
    }

    private suspend fun <T> firestore(block: suspend () -> AppResult<T>): AppResult<T> = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: FirebaseFirestoreException) {
        AppResult.Failure(
            when (e.code) {
                FirebaseFirestoreException.Code.PERMISSION_DENIED, FirebaseFirestoreException.Code.UNAUTHENTICATED -> AppError.UNAUTHORIZED
                FirebaseFirestoreException.Code.UNAVAILABLE -> AppError.NETWORK_UNAVAILABLE
                FirebaseFirestoreException.Code.DEADLINE_EXCEEDED -> AppError.TIMEOUT
                FirebaseFirestoreException.Code.RESOURCE_EXHAUSTED -> AppError.RATE_LIMITED
                FirebaseFirestoreException.Code.NOT_FOUND -> AppError.NOT_FOUND
                else -> AppError.SERVER
            },
            e,
        )
    } catch (e: Exception) {
        AppResult.Failure(AppError.UNKNOWN, e)
    }

    companion object {
        private const val CALLERS = "callerNumbers"
        private const val REPORTS = "reports"
        private const val SPAM_LIST_MIN_SCORE = 70
        private const val SPAM_LIST_LIMIT = 500L
        private val E164 = Regex("^\\+[1-9]\\d{6,14}$")

        /** Maps a callerNumbers document to the shape the rest of the app already understands. */
        internal fun DocumentSnapshot.toNumberInfo(e164: String): NumberInfoDto {
            val businessName = getString("businessName")?.takeIf { it.isNotBlank() }
            val displayName = getString("displayName")?.takeIf { it.isNotBlank() }
            val categories = (get("categories") as? List<*>).orEmpty().mapNotNull { ReportCategory.fromWire(it as? String) }
            val counts = (get("categoryCounts") as? Map<*, *>).orEmpty()
            val votes = mutableMapOf<String, Int>()
            counts.forEach { (k, v) ->
                val cat = ReportCategory.fromWire(k as? String) ?: return@forEach
                votes[cat.category.name] = (votes[cat.category.name] ?: 0) + ((v as? Number)?.toInt() ?: 0)
            }
            val totalReports = getLong("totalReports")?.toInt() ?: 0
            val score = getLong("spamScore")?.toInt()
            val seeded = (getLong("seedSpamScore") ?: 0L) > 0L
            val verified = getBoolean("isVerified") == true
            val category = when {
                categories.isNotEmpty() -> categories.first().category
                businessName != null || verified -> SpamCategory.BUSINESS
                else -> null
            }
            // Outside spam list still in force (fewer than two users said "Not spam").
            val listedBy = if (getBoolean("externalActive") == true) getString("externalListName")?.takeIf { it.isNotBlank() } ?: "outside list" else null
            val confidence = maxOf(
                min(1.0, ln(1.0 + totalReports) / ln(51.0)),
                if (seeded) 0.8 else 0.0,
                if (verified) 0.9 else 0.0,
                if (listedBy != null) LISTED_CONFIDENCE else 0.0,
            ).toFloat()
            return NumberInfoDto(
                number = e164,
                name = businessName ?: displayName,
                identityType = when {
                    businessName != null -> "BUSINESS"
                    displayName != null -> "PERSON"
                    else -> "UNKNOWN"
                },
                category = category?.name,
                spamScore = score,
                confidence = confidence,
                reportCount = totalReports,
                reportsLast24h = getLong("reportsLast24h")?.toInt() ?: 0,
                reportsLast7d = getLong("reportsLast7d")?.toInt() ?: 0,
                lastReportedAt = getTimestamp("lastReportedAt")?.toDate()?.time,
                categoryVotes = votes,
                verified = verified,
                region = getString("region"),
                updatedAt = getTimestamp("updatedAt")?.toDate()?.time,
                listedBy = listedBy,
            )
        }

        /** Enough for a list score of 60 to show "Possible spam", not enough to block or say "scam". */
        private const val LISTED_CONFIDENCE = 0.85
    }
}
