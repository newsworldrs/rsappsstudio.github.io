package com.rskusum.whocaller.firebase

import android.content.Context
import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory

/** Debug builds use the App Check debug provider (register the printed debug token in the Firebase console). */
object AppCheckInstaller {
    fun install(context: Context) {
        if (!context.isFirebaseAvailable()) return
        FirebaseAppCheck.getInstance().installAppCheckProviderFactory(DebugAppCheckProviderFactory.getInstance())
    }
}
