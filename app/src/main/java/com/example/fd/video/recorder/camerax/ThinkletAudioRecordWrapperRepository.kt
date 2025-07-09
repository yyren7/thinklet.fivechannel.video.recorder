package com.example.fd.video.recorder.camerax

import ai.fd.thinklet.camerax.ThinkletAudioRecordWrapperFactory

interface ThinkletAudioRecordWrapperRepository : ThinkletAudioRecordWrapperFactory {
    fun interface Listener {
        fun onRawAudioData(data: ByteArray)
    }

    fun setCallback(listener: Listener?)
}
