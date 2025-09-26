package com.example.fd.video.recorder.device.audio

import ai.fd.thinklet.sdk.audio.MultiChannelAudioRecord
import ai.fd.thinklet.sdk.audio.RawAudioRecordWrapper
import android.content.Context
import com.example.fd.video.recorder.util.Logging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException

internal class RawAudioRecCaptureRepository(
    private val coroutineScope: CoroutineScope,
    private val context: Context
) {
    private var recordingJob: Job? = null
    private var rawAudioRecorder: RawAudioRecordWrapper? = null
    private var outputStream: FileOutputStream? = null

    fun startRecording(
        outputFile: File,
    ) {
        stopRecording()
        if (!outputFile.createNewFile()) throw IOException("Failed to create output file: ${outputFile.absolutePath}")

        recordingJob = coroutineScope.launch {
            try {
                Logging.d("Raw mode: Creating RawAudioRecordWrapper with 6-channel configuration (5ch + 1ch empty)")
                
                // 创建官方的 RawAudioRecordWrapper，使用6通道配置(5ch实用通道 + 1ch空通道)
                rawAudioRecorder = RawAudioRecordWrapper(
                    channel = MultiChannelAudioRecord.Channel.CHANNEL_SIX,
                    sampleRate = MultiChannelAudioRecord.SampleRate.SAMPLING_RATE_48000,
                    outputChannel = RawAudioRecordWrapper.RawAudioOutputChannel.ORIGINAL
                )
                
                // 准备录制器
                if (!rawAudioRecorder!!.prepare(context)) {
                    throw IOException("Failed to prepare RawAudioRecordWrapper")
                }
                
                // 创建输出流
                outputStream = FileOutputStream(outputFile)
                
                Logging.d("Starting raw audio recording...")
                
                // 开始录制，使用回调来监控数据
                rawAudioRecorder!!.start(
                    outputStream,
                    object : RawAudioRecordWrapper.IRawAudioRecorder {
                        override fun onReceivedPcmData(pcmData: ByteArray) {
                            // 记录音频数据用于调试
                            val sampleData = pcmData.take(8).joinToString { "%02x".format(it) }
                            val rmsValue = if (pcmData.isNotEmpty()) {
                                kotlin.math.sqrt(pcmData.map { (it.toInt() * it.toInt()).toDouble() }.average())
                            } else 0.0
                            Logging.d("Raw audio data - Size: ${pcmData.size} bytes, Sample: [$sampleData], RMS: %.2f".format(rmsValue))
                        }
                        
                        override fun onFailed(throwable: Throwable) {
                            Logging.e("Raw audio recording failed: ${throwable.message}")
                        }
                    }
                )
                
                Logging.d("Raw audio recording started successfully")
                
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logging.e("Raw audio recording failed: $e")
                throw IOException("Failed to start raw audio recording", e)
            }
        }
    }

    fun stopRecording() {
        recordingJob?.cancel()
        recordingJob = null
        
        try {
            rawAudioRecorder?.stop()
            Logging.d("Raw audio recording stopped")
        } catch (e: Exception) {
            Logging.e("Error stopping raw audio recorder: $e")
        }
        
        try {
            outputStream?.close()
            outputStream = null
        } catch (e: Exception) {
            Logging.e("Error closing output stream: $e")
        }
        
        rawAudioRecorder = null
    }

}

