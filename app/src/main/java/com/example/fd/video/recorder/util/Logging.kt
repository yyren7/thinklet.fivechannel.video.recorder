package com.example.fd.video.recorder.util

import android.util.Log

object Logging {
    fun v(s: String) {
        Log.v("VideoRecorder", s)
    }

    fun v(s: String, e: Throwable?) {
        Log.v("VideoRecorder", s, e)
    }

    fun d(s: String) {
        Log.d("VideoRecorder", s)
    }

    fun d(s: String, e: Throwable?) {
        Log.d("VideoRecorder", s, e)
    }

    fun i(s: String) {
        Log.i("VideoRecorder", s)
    }

    fun i(s: String, e: Throwable?) {
        Log.i("VideoRecorder", s, e)
    }

    fun w(s: String) {
        Log.w("VideoRecorder", s)
    }

    fun w(s: String, e: Throwable?) {
        Log.w("VideoRecorder", s, e)
    }

    fun e(s: String) {
        Log.e("VideoRecorder", s)
    }

    fun e(s: String, e: Throwable?) {
        Log.e("VideoRecorder", s, e)
    }
}
