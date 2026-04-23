package com.tyler.receiptsnap

import android.app.Application
import android.util.Log
import org.opencv.android.OpenCVLoader

class ReceiptSnapApp : Application() {
    override fun onCreate() {
        super.onCreate()
        if (!OpenCVLoader.initLocal()) {
            Log.e("ReceiptSnap", "OpenCV init failed")
        } else {
            Log.i("ReceiptSnap", "OpenCV ${OpenCVLoader.OPENCV_VERSION} loaded")
        }
    }
}
