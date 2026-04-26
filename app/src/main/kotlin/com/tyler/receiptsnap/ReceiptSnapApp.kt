package com.tyler.receiptsnap

import android.app.Application
import android.util.Log
import com.tyler.receiptsnap.data.SendLog
import com.tyler.receiptsnap.data.SentTracker
import com.tyler.receiptsnap.data.SettingsStore
import org.opencv.android.OpenCVLoader

class ReceiptSnapApp : Application() {

    lateinit var settings: SettingsStore
        private set

    lateinit var sentTracker: SentTracker
        private set

    lateinit var sendLog: SendLog
        private set

    override fun onCreate() {
        super.onCreate()
        settings = SettingsStore(this)
        sentTracker = SentTracker(this)
        sendLog = SendLog(this)
        if (!OpenCVLoader.initLocal()) {
            Log.e("ReceiptSnap", "OpenCV init failed")
        } else {
            Log.i("ReceiptSnap", "OpenCV ${OpenCVLoader.OPENCV_VERSION} loaded")
        }
    }
}
