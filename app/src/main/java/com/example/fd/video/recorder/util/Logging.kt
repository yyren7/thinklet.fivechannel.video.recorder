package com.example.fd.video.recorder.util

import android.util.Log

object Logging {
    const val TAG = "MultiMicVideoRecorder"
    fun d(s: String) {
        Log.d(TAG, s)
    }

    fun w(s: String) {
        Log.w(TAG, s)
    }

    fun e(s: String) {
        Log.e(TAG, s)
    }
}
