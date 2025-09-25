package com.example.fd.video.recorder.camerax.impl

import ai.fd.thinklet.camerax.mic.multichannel.MultiChannelAudioCompressor
import ai.fd.thinklet.sdk.audio.MultiChannelAudioRecord
import ai.fd.thinklet.sdk.audio.MultiChannelAudioRecord.Channel
import ai.fd.thinklet.sdk.audio.MultiChannelAudioRecord.SampleRate
import android.Manifest
import android.media.AudioManager.AudioRecordingCallback
import android.media.AudioRecord
import android.media.AudioRecordingConfiguration
import android.media.AudioTimestamp
import androidx.annotation.RequiresApi
import androidx.annotation.RequiresPermission
import androidx.camera.video.audio.wrapper.AudioRecordWrapper
import com.example.fd.video.recorder.device.audio.ThinkletAudioRecordWrapperRepository
import com.example.fd.video.recorder.util.Logging
import java.nio.ByteBuffer
import java.util.concurrent.Executor
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.math.min

internal class ThinkletAudioRecordWrapperRepositoryImpl(
    private val sourceChannelCount: Channel = Channel.CHANNEL_FIVE,
) : ThinkletAudioRecordWrapperRepository {

    private val listenerLock = ReentrantLock()
    private var listener: ThinkletAudioRecordWrapperRepository.Listener? = null

    override fun setCallback(listener: ThinkletAudioRecordWrapperRepository.Listener?) {
        listenerLock.withLock { this.listener = listener }
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    override fun create(
        audioSource: Int, audioFormat: Int, channelCount: Int,
        sampleRate: Int
    ): AudioRecordWrapper {
        val audioRecordWithBufferSize = MultiChannelAudioRecord().get(
            sourceChannelCount,
            getMultiChannelAudioRecordSampleRate(sampleRate)
        )
        return MultiChannelAudioRecordWrapper(
            { listenerLock.withLock { listener } },
            audioRecordWithBufferSize.audioRecord,
            sourceChannelCount,
            audioRecordWithBufferSize.bufferSize,
            channelCount == 2
        )
    }

    private fun getMultiChannelAudioRecordSampleRate(sampleRate: Int): SampleRate =
        when (sampleRate) {
            16000 -> SampleRate.SAMPLING_RATE_16000
            32000 -> SampleRate.SAMPLING_RATE_32000
            48000 -> SampleRate.SAMPLING_RATE_48000
            else -> throw IllegalArgumentException("Unsupported sample rate. $sampleRate")
        }
}

internal class MultiChannelAudioRecordWrapper(
    private val listener: () -> ThinkletAudioRecordWrapperRepository.Listener?,
    private val audioRecord: AudioRecord,
    private val sourceChannelCount: Channel,
    private val sourceBufferSize: Int,
    private val isStereoOutput: Boolean
) : AudioRecordWrapper() {

    private val sourceBuffer = ByteArray(sourceBufferSize)

    override fun getState(): Int = audioRecord.state

    override fun release() = audioRecord.release()

    override fun startRecording() = audioRecord.startRecording()

    override fun getRecordingState(): Int = audioRecord.recordingState

    override fun stop() = audioRecord.stop()

    override fun read(byteBuffer: ByteBuffer, bufferSize: Int): Int {
        val readCount = audioRecord.read(sourceBuffer, 0, sourceBufferSize)
        if (readCount > 0) {
            val validData = sourceBuffer.copyOf(readCount)
            listener()?.onRawAudioData(validData)
            return compressChannel(
                validData, sourceChannelCount, isStereoOutput, byteBuffer,
                bufferSize
            )
        }
        Logging.w("Read data from multi channel audio is empty!")
        return 0
    }

    override fun getAudioSessionId(): Int = audioRecord.audioSessionId

    override fun getTimestamp(audioTimestamp: AudioTimestamp, timeBase: Int): Int =
        audioRecord.getTimestamp(audioTimestamp, timeBase)

    @RequiresApi(29)
    override fun getActiveRecordingConfiguration(): AudioRecordingConfiguration? =
        audioRecord.activeRecordingConfiguration

    @RequiresApi(29)
    override fun registerAudioRecordingCallback(
        executor: Executor,
        callback: AudioRecordingCallback
    ) = audioRecord.registerAudioRecordingCallback(executor, callback)

    @RequiresApi(29)
    override fun unregisterAudioRecordingCallback(callback: AudioRecordingCallback) =
        audioRecord.unregisterAudioRecordingCallback(callback)

    companion object {
        private fun compressChannel(
            sourceByteArray: ByteArray,
            sourceChannelCount: Channel,
            isStereoOutput: Boolean,
            outByteBuffer: ByteBuffer,
            outBufferSize: Int
        ): Int {
            val compressedData = MultiChannelAudioCompressor
                .compressPcm16bitAudio(sourceByteArray, sourceChannelCount, isStereoOutput)
            if (compressedData == null) {
                Logging.w("Compressed data is empty!")
                return 0
            }
            val writtenDataSize = min(compressedData.size, outBufferSize)
            outByteBuffer.put(compressedData, 0, writtenDataSize)
            return writtenDataSize
        }
    }
}
