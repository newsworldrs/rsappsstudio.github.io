package com.rskusum.scanner

import android.app.Application
import android.util.Log
import org.opencv.android.OpenCVLoader

class ScannerApp : Application() {
    override fun onCreate() {
        super.onCreate()
        if (!OpenCVLoader.initLocal()) Log.e("ScannerApp", "OpenCV failed to load")
    }
}
