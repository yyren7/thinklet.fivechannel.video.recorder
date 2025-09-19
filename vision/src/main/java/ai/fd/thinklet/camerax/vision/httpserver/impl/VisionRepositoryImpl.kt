package ai.fd.thinklet.camerax.vision.httpserver.impl

import ai.fd.thinklet.camerax.vision.ClientConnectionListener
import ai.fd.thinklet.camerax.vision.httpserver.VisionRepository
import android.util.Log
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import java.util.Timer
import java.util.TimerTask
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.thread
import kotlin.concurrent.withLock

internal class VisionRepositoryImpl : VisionRepository {
    private var serverThread: Thread? = null
    private val cacheLock = ReentrantLock()
    private var cacheImage: ByteArray? = null
    private var listener: ClientConnectionListener? = null
    private var disconnectTimer: TimerTask? = null

    override fun setClientConnectionListener(listener: ClientConnectionListener?) {
        this.listener = listener
    }

    override fun start(port: Int) {
        serverThread = createServer(port)
    }

    override fun stop() {
        serverThread?.interrupt()
        serverThread = null
    }

    override fun updateJpeg(bytes: ByteArray) {
        if (serverThread == null) {
            Log.w("Vision", "server is not running")
            return
        }
        cacheLock.withLock {
            cacheImage = bytes
        }
    }

    private fun createServer(port: Int): Thread {
        val server = embeddedServer(Netty, port = port) {
            routing {
                get("/") {
                    val html = buildIndexHtml()
                    call.respondBytes(
                        bytes = html,
                        contentType = ContentType.Text.Html,
                        status = HttpStatusCode.OK
                    )
                }
                get("/image") {
                    listener?.onClientConnected()
                    resetDisconnectTimer()
                    val img = cacheLock.withLock { cacheImage?.copyOf() }
                    if (img != null) {
                        call.respondBytes(
                            bytes = img,
                            contentType = ContentType.Image.JPEG,
                            status = HttpStatusCode.OK
                        )
                    } else {
                        call.respond(HttpStatusCode.InternalServerError)
                    }
                }
            }
        }

        return thread {
            kotlin.runCatching {
                server.start(true)
            }.onFailure {
                server.stop()
                if (it is InterruptedException) {
                    Log.i("Vision", "server shutdown")
                } else {
                    Log.e("Vision", "unexpected stop")
                }
            }
        }
    }

    private fun resetDisconnectTimer() {
        disconnectTimer?.cancel()
        disconnectTimer = object : TimerTask() {
            override fun run() {
                listener?.onClientDisconnected()
            }
        }
        Timer().schedule(disconnectTimer, 5000)
    }

    private fun buildIndexHtml(reload: Int = 1000): ByteArray {
        return indexHtml
            .replace("{{interval}}", "$reload")
            .toByteArray()
    }

    private val indexHtml: String
        get() = """
        <!DOCTYPE html>
        <html lang="ja">
        <head>
            <meta charset="UTF-8">
            <meta name="viewport" content="width=device-width, initial-scale=1.0">
            <meta http-equiv="X-UA-Compatible" content="ie=edge">
            <title>THINKLET Vision</title>
        
            <style>
                * {
                    padding: 0;
                    margin: 0;
                }
                #header {
                    padding: 32px;
                    width: 100%;
                    background-color: #404040;
                    color: #ffffff;
                }
                #contents img {
                    padding: 32px;
                    max-width: 100%;
                    height: auto;
                }
            </style>
        </head>
        <body>
        <header id="header">
            <h1>THINKLET Vision</h1>
        </header>
        <div id="contents">
            <img id="image" src="/image" alt="THINKLET vision"/>
        
            <script>
                const imageElement = document.getElementById('image');
                function updateImage() {
                  fetch('/image')
                    .then(response => response.blob())
                    .then(blob => {
                      const imageUrl = URL.createObjectURL(blob);
                      imageElement.src = imageUrl;
                    });
                }
                const interval = {{interval}};
                if (interval > 0) {
                    setInterval(updateImage, interval);
                }
            </script>
        </div>
        </body>
        </html>
        """.trimIndent()
}
