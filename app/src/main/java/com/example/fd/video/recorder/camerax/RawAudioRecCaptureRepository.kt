package com.example.fd.video.recorder.camerax

import com.example.fd.video.recorder.util.Logging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException

internal class RawAudioRecCaptureRepository(
    private val coroutineScope: CoroutineScope,
    private val audioRecordWrapperRepository: ThinkletAudioRecordWrapperRepository
) {
    private var recordingJob: Job? = null

    fun startRecording(
        outputFile: File,
    ) {
        stopRecording()
        if (!outputFile.createNewFile()) throw IOException("Failed to create output file: ${outputFile.absolutePath}")

        recordingJob = coroutineScope.launch {
            try {
                val audioDataFlow = createAudioDataFlow()
                audioDataFlow.collect { writeToFile(outputFile, it) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                Logging.e("Recording failed $e")
            }
        }
    }

    fun stopRecording() {
        recordingJob?.cancel()
        recordingJob = null
        audioRecordWrapperRepository.setCallback(null)
    }

    private fun createAudioDataFlow(): Flow<ByteArray> = callbackFlow {
        audioRecordWrapperRepository.setCallback { data ->
            if (data.isNotEmpty()) {
                trySend(data.copyOf())
            }
        }

        awaitClose {
            audioRecordWrapperRepository.setCallback(null)
        }
    }

    private fun writeToFile(outputFile: File, data: ByteArray) {
        if (outputFile.exists()) {
            outputFile.appendBytes(data)
        }
    }
}
