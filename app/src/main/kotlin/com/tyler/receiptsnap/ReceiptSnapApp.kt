package com.tyler.receiptsnap

import android.app.Application
import android.util.Log
import com.tyler.receiptsnap.data.SettingsStore
import org.opencv.android.OpenCVLoader

class ReceiptSnapApp : Application() {

    lateinit var settings: SettingsStore
        private set

    override fun onCreate() {
        super.onCreate()
        settings = SettingsStore(this)
        if (!OpenCVLoader.initLocal()) {
            Log.e("ReceiptSnap", "OpenCV init failed")
        } else {
            Log.i("ReceiptSnap", "OpenCV ${OpenCVLoader.OPENCV_VERSION} loaded")
        }
    }
}
