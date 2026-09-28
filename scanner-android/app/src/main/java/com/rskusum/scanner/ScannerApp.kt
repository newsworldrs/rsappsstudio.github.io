package com.rskusum.scanner

import android.app.Application
import android.util.Log
import org.opencv.android.OpenCVLoader

class ScannerApp : Application() {
    override fun onCreate() {
        super.onCreate()
        openCvError = try {
            if (OpenCVLoader.initLocal()) null else "OpenCV native library failed to load"
        } catch (t: Throwable) {
            "OpenCV: ${t.javaClass.simpleName}: ${t.message}"
        }
        openCvError?.let { Log.e("ScannerApp", it) }
    }

    companion object {
        /** Non-null when the vision engine is unavailable; shown on the camera screen. */
        @Volatile var openCvError: String? = null
            private set
    }
}
