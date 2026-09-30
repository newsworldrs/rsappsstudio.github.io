package com.rskusum.whocaller.firebase

import android.content.Context
import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.appcheck.playintegrity.PlayIntegrityAppCheckProviderFactory

/** Release builds attest with Play Integrity so the backend can reject modified or scripted clients. */
object AppCheckInstaller {
    fun install(context: Context) {
        if (!context.isFirebaseAvailable()) return
        FirebaseAppCheck.getInstance().installAppCheckProviderFactory(PlayIntegrityAppCheckProviderFactory.getInstance())
    }
}
