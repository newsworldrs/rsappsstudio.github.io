package com.rskusum.whocaller.core.data.repository

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.rskusum.whocaller.core.common.Clock
import com.rskusum.whocaller.core.common.IoDispatcher
import com.rskusum.whocaller.core.common.result.AppError
import com.rskusum.whocaller.core.common.result.AppResult
import com.rskusum.whocaller.core.domain.repository.LocalProfileRepository
import com.rskusum.whocaller.core.model.LocalProfile
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/** The user's own profile (name, profession, institute, email, photo/avatar). Kept on this device. */
@Singleton
class LocalProfileRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dataStore: DataStore<Preferences>,
    private val clock: Clock,
    @IoDispatcher private val io: CoroutineDispatcher,
) : LocalProfileRepository {

    override val profile: Flow<LocalProfile> = dataStore.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { p ->
            LocalProfile(
                name = p[NAME].orEmpty(),
                profession = p[PROFESSION].orEmpty(),
                institute = p[INSTITUTE].orEmpty(),
                email = p[EMAIL].orEmpty(),
                photoPath = p[PHOTO]?.takeIf { File(it).exists() },
                avatarId = p[AVATAR],
                updatedAt = p[UPDATED] ?: 0L,
                phoneNumber = p[PHONE].orEmpty(),
                phoneVerified = p[PHONE_VERIFIED] ?: false,
                showNameToCallers = p[SHOW_NAME] ?: true,
                idSetupSkippedAt = p[ID_SKIPPED] ?: 0L,
            )
        }

    override suspend fun update(transform: (LocalProfile) -> LocalProfile) {
        dataStore.edit { p ->
            val current = LocalProfile(
                name = p[NAME].orEmpty(),
                profession = p[PROFESSION].orEmpty(),
                institute = p[INSTITUTE].orEmpty(),
                email = p[EMAIL].orEmpty(),
                photoPath = p[PHOTO],
                avatarId = p[AVATAR],
                phoneNumber = p[PHONE].orEmpty(),
                phoneVerified = p[PHONE_VERIFIED] ?: false,
                showNameToCallers = p[SHOW_NAME] ?: true,
                idSetupSkippedAt = p[ID_SKIPPED] ?: 0L,
            )
            val next = transform(current)
            p[PHONE] = next.phoneNumber.trim().take(MAX_FIELD)
            p[PHONE_VERIFIED] = next.phoneVerified
            p[SHOW_NAME] = next.showNameToCallers
            p[ID_SKIPPED] = next.idSetupSkippedAt
            p[NAME] = next.name.trim().take(MAX_FIELD)
            p[PROFESSION] = next.profession.trim().take(MAX_FIELD)
            p[INSTITUTE] = next.institute.trim().take(MAX_FIELD)
            p[EMAIL] = next.email.trim().take(MAX_FIELD)
            val photo = next.photoPath
            if (photo == null) p.remove(PHOTO) else p[PHOTO] = photo
            val avatar = next.avatarId
            if (avatar == null) p.remove(AVATAR) else p[AVATAR] = avatar
            p[UPDATED] = clock.now()
        }
    }

    override suspend fun importPhoto(uri: String): AppResult<String> = withContext(io) {
        try {
            val bitmap = decodeSquare(Uri.parse(uri)) ?: return@withContext AppResult.Failure(AppError.STORAGE)
            val dir = File(context.filesDir, "profile").apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() }
            // New file name each time so image caches never show a stale photo.
            val file = File(dir, "photo_${clock.now()}.jpg")
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 88, it) }
            bitmap.recycle()
            update { it.copy(photoPath = file.absolutePath, avatarId = null) }
            AppResult.Success(file.absolutePath)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            AppResult.Failure(AppError.STORAGE, e)
        }
    }

    /**
     * Decodes any gallery image (JPEG, PNG, WebP, HEIC…) downsampled, upright (EXIF rotation applied)
     * and centre-cropped to a square of at most TARGET_PX. Null if it can't be read.
     */
    private fun decodeSquare(source: Uri): Bitmap? {
        val resolver = context.contentResolver
        val decoded: Bitmap = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            // ImageDecoder applies EXIF orientation itself and reads HEIC.
            android.graphics.ImageDecoder.decodeBitmap(android.graphics.ImageDecoder.createSource(resolver, source)) { decoder, info, _ ->
                val w = info.size.width
                val h = info.size.height
                val scale = (TARGET_PX.toFloat() / minOf(w, h)).coerceAtMost(1f)
                decoder.setTargetSize((w * scale).toInt().coerceAtLeast(1), (h * scale).toInt().coerceAtLeast(1))
                decoder.allocator = android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE
            }
        } else {
            // Size only first: decodeStream returns null in this mode, that's expected.
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(source)?.use { BitmapFactory.decodeStream(it, null, bounds) } ?: Unit
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            var sample = 1
            while (minOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= TARGET_PX) sample *= 2
            val raw = resolver.openInputStream(source)?.use {
                BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
            } ?: return null
            val degrees = runCatching {
                resolver.openInputStream(source)?.use { androidx.exifinterface.media.ExifInterface(it).rotationDegrees }
            }.getOrNull() ?: 0
            if (degrees == 0) raw else {
                val m = android.graphics.Matrix().apply { postRotate(degrees.toFloat()) }
                Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true).also { if (it !== raw) raw.recycle() }
            }
        }
        val side = minOf(decoded.width, decoded.height)
        val square = Bitmap.createBitmap(decoded, (decoded.width - side) / 2, (decoded.height - side) / 2, side, side)
        if (square !== decoded) decoded.recycle()
        if (side <= TARGET_PX) return square
        return Bitmap.createScaledBitmap(square, TARGET_PX, TARGET_PX, true).also { if (it !== square) square.recycle() }
    }

    override suspend fun removePhoto() = withContext(io) {
        File(context.filesDir, "profile").listFiles()?.forEach { it.delete() }
        update { it.copy(photoPath = null) }
    }

    private companion object {
        val NAME = stringPreferencesKey("profile_name")
        val PROFESSION = stringPreferencesKey("profile_profession")
        val INSTITUTE = stringPreferencesKey("profile_institute")
        val EMAIL = stringPreferencesKey("profile_email")
        val PHOTO = stringPreferencesKey("profile_photo")
        val AVATAR = intPreferencesKey("profile_avatar")
        val PHONE = stringPreferencesKey("profile_phone")
        val PHONE_VERIFIED = booleanPreferencesKey("profile_phone_verified")
        val SHOW_NAME = booleanPreferencesKey("profile_show_name")
        val ID_SKIPPED = longPreferencesKey("profile_id_skipped_at")
        val UPDATED = longPreferencesKey("profile_updated")
        const val MAX_FIELD = 80
        const val TARGET_PX = 512
    }
}
