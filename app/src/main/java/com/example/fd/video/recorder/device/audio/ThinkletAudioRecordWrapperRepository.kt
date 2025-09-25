package com.example.fd.video.recorder.device.audio

import ai.fd.thinklet.camerax.ThinkletAudioRecordWrapperFactory

interface ThinkletAudioRecordWrapperRepository : ThinkletAudioRecordWrapperFactory {
    fun interface Listener {
        fun onRawAudioData(data: ByteArray)
    }

    fun setCallback(listener: Listener?)
}
