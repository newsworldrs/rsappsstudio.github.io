package com.rskusum.whocaller.core.data.sync

import android.content.Context
import com.rskusum.whocaller.core.common.result.AppResult
import com.rskusum.whocaller.core.domain.sms.SpamTextModel
import com.rskusum.whocaller.core.network.NetworkDataSource
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Keeps the on-device SMS spam model up to date without an app update. A newer model published in
 * the backend (Firestore appConfig/smsSpamModel) is downloaded once into private app storage — the
 * user never sees it — and used for every incoming message. Falls back to the model in the APK.
 * Only the model (word weights) is downloaded; message text never leaves the phone.
 */
@Singleton
class SmsModelStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val network: NetworkDataSource,
) {
    private val file = File(context.filesDir, "sms_spam_model.txt")
    private val prefs = context.getSharedPreferences("sms_model", Context.MODE_PRIVATE)

    /** Call at app start: activates a previously downloaded model. */
    fun loadInstalled() {
        if (!file.exists()) return
        runCatching { file.useLines { SpamTextModel.parse(it) } }.getOrNull()?.let(SpamTextModel::install)
    }

    /** Downloads a newer model when one is published; checks at most once a week. */
    suspend fun refresh(now: Long = System.currentTimeMillis()) {
        if (now - prefs.getLong(KEY_CHECKED, 0L) < CHECK_INTERVAL_MS) return
        val result = network.getSmsSpamModel()
        if (result !is AppResult.Success) return
        prefs.edit().putLong(KEY_CHECKED, now).apply()
        val dto = result.data
        if (dto.version <= prefs.getInt(KEY_VERSION, 0)) return
        val model = SpamTextModel.parse(dto.model.lineSequence()) ?: return
        val tmp = File(context.filesDir, "sms_spam_model.tmp")
        tmp.writeText(dto.model)
        if (!tmp.renameTo(file)) {
            file.writeText(dto.model)
            tmp.delete()
        }
        prefs.edit().putInt(KEY_VERSION, dto.version).apply()
        SpamTextModel.install(model)
    }

    private companion object {
        const val KEY_CHECKED = "checked_at"
        const val KEY_VERSION = "version"
        const val CHECK_INTERVAL_MS = 7L * 24 * 60 * 60 * 1000
    }
}
