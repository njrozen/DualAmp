package com.dualamp.viz

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.net.ConnectivityManager
import android.opengl.GLES30
import android.os.SystemClock
import android.util.Log
import com.dualamp.settings.Prefs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Streams the visualizer to any browser on the same Wi-Fi (Settings > Stream visualizer): a TV's browser, a PC or
 * a phone opens http://<device>:8340 and gets the visualizer full screen, live, as a stream of JPEG frames.
 *
 * Frames come from the renderer ([GlCapture], on the GL thread): scaled down on the GPU to [WIDTH] wide and read
 * back through pixel buffers one frame late, so the screen on the device never waits for it. Nothing is captured
 * while no one is watching.
 */
object VizStream {
    private const val TAG = "VizStream"
    const val PORT = 8340
    const val WIDTH = 1280
    private const val FPS = 30

    private val _viewers = MutableStateFlow(0)
    /** Browsers watching now. */
    val viewers: StateFlow<Int> get() = _viewers

    private var server: ServerSocket? = null
    private val clients = CopyOnWriteArrayList<Client>()

    /** True while the renderer should hand over frames. */
    val wanted: Boolean get() = clients.isNotEmpty()

    private class Client(val socket: Socket, val out: OutputStream) {
        val busy = AtomicBoolean(false)
        /** Released when the browser has gone (a write failed) or streaming stops. */
        val done = java.util.concurrent.CountDownLatch(1)
    }

    /** Starts or stops the server to match Settings. */
    fun apply() {
        if (Prefs.vizStream.value) start() else stop()
    }

    @Synchronized private fun start() {
        if (server != null) return
        val s = try { ServerSocket(PORT) } catch (e: Exception) { Log.w(TAG, "Port $PORT is busy", e); return }
        server = s
        thread(name = "VizStream", isDaemon = true) {
            while (!s.isClosed) {
                val socket = try { s.accept() } catch (e: SocketException) { break }
                thread(name = "VizStream client", isDaemon = true) { serve(socket) }
            }
        }
        Log.i(TAG, "Streaming at ${address()}")
    }

    @Synchronized private fun stop() {
        runCatching { server?.close() }
        server = null
        clients.forEach { runCatching { it.socket.close() }; it.done.countDown() }
        clients.clear()
        _viewers.value = 0
    }

    /** "http://192.168.1.20:8340", or null without Wi-Fi. */
    fun address(context: Context? = appContext): String? {
        val cm = context?.getSystemService(ConnectivityManager::class.java) ?: return null
        val props = cm.getLinkProperties(cm.activeNetwork) ?: return null
        val ip = props.linkAddresses.map { it.address }.firstOrNull { it is Inet4Address && !it.isLoopbackAddress } ?: return null
        return "http://${ip.hostAddress}:$PORT"
    }

    @Volatile var appContext: Context? = null

    private fun serve(socket: Socket) {
        try {
            socket.tcpNoDelay = true
            val request = socket.getInputStream().bufferedReader().readLine() ?: return
            val path = request.split(' ').getOrNull(1) ?: "/"
            val out = socket.getOutputStream()
            if (path.startsWith("/now")) {
                // The playing song, for the caption the page shows when it changes.
                val st = com.dualamp.playback.PlayerConnection.state.value
                val song = st.songId?.let { com.dualamp.library.LibraryRepository.library.value?.songById?.get(it) }
                val body = org.json.JSONObject().put("id", st.songId ?: -1).put("title", song?.title ?: "").put("artist", song?.artist ?: "")
                    .put("playing", st.isPlaying).toString().toByteArray()
                out.write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nCache-Control: no-cache\r\n" +
                    "Content-Length: ${body.size}\r\nConnection: close\r\n\r\n").toByteArray())
                out.write(body); out.flush()
            } else if (path.startsWith("/stream")) {
                out.write(("HTTP/1.1 200 OK\r\nContent-Type: multipart/x-mixed-replace; boundary=frame\r\n" +
                    "Cache-Control: no-cache\r\nConnection: close\r\n\r\n").toByteArray())
                out.flush()
                val c = Client(socket, out)
                clients += c
                _viewers.value = clients.size
                // Frames are written by [send]; this thread waits until writing to the browser fails.
                c.done.await()
                clients -= c
                _viewers.value = clients.size
            } else {
                val body = PAGE.toByteArray()
                out.write(("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${body.size}\r\n" +
                    "Connection: close\r\n\r\n").toByteArray())
                out.write(body)
                out.flush()
            }
        } catch (_: Exception) {
        } finally {
            runCatching { socket.close() }
        }
    }

    // --- encoding --------------------------------------------------------------------------------------------

    private val encoding = AtomicBoolean(false)
    private var lastFrameAt = 0L

    /** Whether the renderer should capture this frame (viewers, and at most [FPS] a second). */
    fun dueNow(): Boolean {
        if (!wanted) return false
        val now = SystemClock.uptimeMillis()
        if (now - lastFrameAt < 1000L / FPS || encoding.get()) return false
        lastFrameAt = now
        return true
    }

    /** RGBA rows bottom-up (as OpenGL reads them); copied, so the caller may reuse [pixels]. */
    fun submit(pixels: ByteBuffer, width: Int, height: Int) {
        if (!encoding.compareAndSet(false, true)) return
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        pixels.rewind()
        bitmap.copyPixelsFromBuffer(pixels)
        thread(name = "VizStream encode", isDaemon = true) {
            try {
                val upright = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                Canvas(upright).drawBitmap(bitmap, Matrix().apply { setScale(1f, -1f); postTranslate(0f, height.toFloat()) }, null)
                bitmap.recycle()
                val jpeg = ByteArrayOutputStream(200_000).also { upright.compress(Bitmap.CompressFormat.JPEG, 60, it) }.toByteArray()
                upright.recycle()
                send(jpeg)
            } finally {
                encoding.set(false)
            }
        }
    }

    private fun send(jpeg: ByteArray) {
        val head = "--frame\r\nContent-Type: image/jpeg\r\nContent-Length: ${jpeg.size}\r\n\r\n".toByteArray()
        for (c in clients) {
            // A slow browser skips frames instead of holding up the others.
            if (!c.busy.compareAndSet(false, true)) continue
            thread(isDaemon = true) {
                try {
                    c.out.write(head); c.out.write(jpeg); c.out.write("\r\n".toByteArray()); c.out.flush()
                } catch (e: Exception) {
                    c.done.countDown()
                    clients -= c; _viewers.value = clients.size; runCatching { c.socket.close() }
                } finally {
                    c.busy.set(false)
                }
            }
        }
    }

    private const val PAGE = """<!doctype html>
<html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>DualAmp visualizer</title>
<style>html,body{margin:0;height:100%;background:#000;overflow:hidden;cursor:none}
img{width:100%;height:100%;object-fit:contain;display:block}
#msg{position:fixed;inset:0;display:flex;align-items:center;justify-content:center;color:#888;font:18px sans-serif;pointer-events:none}
#now{position:fixed;left:4vw;bottom:5vh;max-width:80vw;padding:1.2vh 2vw;border-radius:1.5vh;background:rgba(0,0,0,.55);color:#fff;font-family:sans-serif;opacity:0;transition:opacity .8s;pointer-events:none}
#now b{display:block;font-size:3.6vh}#now span{font-size:2.6vh;color:#ccc}</style>
</head><body>
<img id="v" alt="">
<div id="msg">Connecting to DualAmp…</div>
<div id="now"><b id="t"></b><span id="a"></span></div>
<script>
var v=document.getElementById('v'),m=document.getElementById('msg');
function go(){v.src='/stream?'+Date.now();}
v.onload=function(){m.style.display='none';};
v.onerror=function(){m.style.display='flex';m.textContent='Waiting for DualAmp… (the visualizer shows while DualAmp is open)';setTimeout(go,2000);};
document.body.ondblclick=function(){var d=document.documentElement;(d.requestFullscreen||d.webkitRequestFullscreen).call(d);};
go();
var last=null,hide=null,n=document.getElementById('now');
function poll(){fetch('/now').then(function(r){return r.json();}).then(function(s){
 if(s.id!==last){last=s.id;if(s.title){document.getElementById('t').textContent=s.title;document.getElementById('a').textContent=s.artist;
  n.style.opacity=1;clearTimeout(hide);hide=setTimeout(function(){n.style.opacity=0;},6000);}}
}).catch(function(){});}
setInterval(poll,2000);poll();
</script></body></html>"""
}

/**
 * GL-thread side of [VizStream]: after each frame, scales the screen down into a small framebuffer and reads it
 * into one of two pixel buffers; the other one (last frame's) is handed to [VizStream.submit].
 */
internal class GlCapture {
    private var fbo = 0
    private var rbo = 0
    private val pbo = IntArray(2)
    private var w = 0
    private var h = 0
    private var index = 0
    private var primed = false
    private var copy: ByteBuffer? = null

    fun afterFrame(screenW: Int, screenH: Int) {
        if (!VizStream.wanted) { primed = false; return }
        if (!VizStream.dueNow()) return
        val tw = minOf(VizStream.WIDTH, screenW)
        val th = (screenH.toLong() * tw / screenW).toInt() and 1.inv()
        if (tw != w || th != h) setUp(tw, th)
        if (fbo == 0) return

        GLES30.glBindFramebuffer(GLES30.GL_READ_FRAMEBUFFER, 0)
        GLES30.glBindFramebuffer(GLES30.GL_DRAW_FRAMEBUFFER, fbo)
        GLES30.glBlitFramebuffer(0, 0, screenW, screenH, 0, 0, w, h, GLES30.GL_COLOR_BUFFER_BIT, GLES30.GL_LINEAR)
        GLES30.glBindFramebuffer(GLES30.GL_READ_FRAMEBUFFER, fbo)
        GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, pbo[index])
        GLES30.glReadPixels(0, 0, w, h, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, 0)
        // The other buffer holds the previous capture, finished by now.
        if (primed) {
            GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, pbo[1 - index])
            val mapped = GLES30.glMapBufferRange(GLES30.GL_PIXEL_PACK_BUFFER, 0, w * h * 4, GLES30.GL_MAP_READ_BIT) as? ByteBuffer
            if (mapped != null) {
                val out = copy ?: ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder()).also { copy = it }
                out.clear(); out.put(mapped); out.flip()
                GLES30.glUnmapBuffer(GLES30.GL_PIXEL_PACK_BUFFER)
                VizStream.submit(out, w, h)
            }
        }
        GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, 0)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        primed = true
        index = 1 - index
    }

    private fun setUp(tw: Int, th: Int) {
        release()
        w = tw; h = th
        val ids = IntArray(1)
        GLES30.glGenFramebuffers(1, ids, 0); fbo = ids[0]
        GLES30.glGenRenderbuffers(1, ids, 0); rbo = ids[0]
        GLES30.glBindRenderbuffer(GLES30.GL_RENDERBUFFER, rbo)
        GLES30.glRenderbufferStorage(GLES30.GL_RENDERBUFFER, GLES30.GL_RGBA8, w, h)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo)
        GLES30.glFramebufferRenderbuffer(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_RENDERBUFFER, rbo)
        GLES30.glGenBuffers(2, pbo, 0)
        for (b in pbo) {
            GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, b)
            GLES30.glBufferData(GLES30.GL_PIXEL_PACK_BUFFER, w * h * 4, null, GLES30.GL_STREAM_READ)
        }
        GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, 0)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        primed = false
        copy = null
    }

    /** Forget GL objects (a new EGL context has none of them). */
    fun reset() { fbo = 0; rbo = 0; pbo[0] = 0; pbo[1] = 0; w = 0; h = 0; primed = false }

    private fun release() {
        if (fbo != 0) GLES30.glDeleteFramebuffers(1, intArrayOf(fbo), 0)
        if (rbo != 0) GLES30.glDeleteRenderbuffers(1, intArrayOf(rbo), 0)
        if (pbo[0] != 0) GLES30.glDeleteBuffers(2, pbo, 0)
        reset()
    }
}
