package ai.fd.thinklet.camerax.vision.httpserver

import ai.fd.thinklet.camerax.vision.ClientConnectionListener

interface VisionRepository {
    fun setClientConnectionListener(listener: ClientConnectionListener?)
    fun start(port: Int)
    fun stop()
    fun updateJpeg(bytes: ByteArray)
}
