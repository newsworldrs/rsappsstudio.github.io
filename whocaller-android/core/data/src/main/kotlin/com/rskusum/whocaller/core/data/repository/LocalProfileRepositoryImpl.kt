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
            )
            val next = transform(current)
            p[PHONE] = next.phoneNumber.trim().take(MAX_FIELD)
            p[PHONE_VERIFIED] = next.phoneVerified
            p[SHOW_NAME] = next.showNameToCallers
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
            val source = Uri.parse(uri)
            val resolver = context.contentResolver
            // First pass: read the size only, then decode downsampled to keep memory low on 2 GB devices.
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(source)?.use { BitmapFactory.decodeStream(it, null, bounds) }
                ?: return@withContext AppResult.Failure(AppError.STORAGE)
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= TARGET_PX && bounds.outHeight / (sample * 2) >= TARGET_PX) sample *= 2
            val bitmap = resolver.openInputStream(source)?.use {
                BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
            } ?: return@withContext AppResult.Failure(AppError.STORAGE)

            val dir = File(context.filesDir, "profile").apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() }
            // New file name each time so image caches never show a stale photo.
            val file = File(dir, "photo_${clock.now()}.jpg")
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 88, it) }
            bitmap.recycle()
            update { it.copy(photoPath = file.absolutePath) }
            AppResult.Success(file.absolutePath)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            AppResult.Failure(AppError.STORAGE, e)
        }
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
        val UPDATED = longPreferencesKey("profile_updated")
        const val MAX_FIELD = 80
        const val TARGET_PX = 512
    }
}
