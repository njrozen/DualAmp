package com.dualamp.library

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.net.URL

/**
 * Music from DualAmp Server on a PC (see pc-server/ in the project), streamed over Wi-Fi.
 * The PC library is merged into the normal library; its folders appear under "PC: <name>".
 */
object RemoteLibrary {
    private const val TAG = "RemoteLibrary"
    private const val DISCOVERY_PORT = 8338

    data class Server(val name: String, val url: String)

    /**
     * Set when the PC can't be reached (loading its library, or playing one of its songs); the library
     * screen shows a pop-up. After the user closes it, failed reloads stay quiet for a while.
     */
    private val _problem = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    val problem: kotlinx.coroutines.flow.StateFlow<String?> get() = _problem
    @Volatile private var quietUntil = 0L

    fun reportProblem(message: String, always: Boolean = false) {
        if (always || System.currentTimeMillis() >= quietUntil) {
            Log.i(TAG, "Pop-up: $message")
            _problem.value = message
        }
    }

    /**
     * Connected this launch. DualAmp only talks to the PC after you connect (Settings > PC music), each time
     * it starts, so opening the app never begins with a "can't reach your PC" message.
     */
    val active = kotlinx.coroutines.flow.MutableStateFlow(false)

    /** State of the link to the PC, shown on the PC tab: a spinner while checking or lost, a checkmark when connected. */
    enum class Connection { NONE, CHECKING, CONNECTED, LOST }

    private val _connection = kotlinx.coroutines.flow.MutableStateFlow(Connection.NONE)
    val connection: kotlinx.coroutines.flow.StateFlow<Connection> get() = _connection

    /**
     * Checks the PC every 1.5 s while one is set up. One failed check shows the spinner; a second one straight
     * after means the connection is lost (the pop-up appears within about 4 s). Each time the PC starts
     * answering, [onConnected] runs (wasLost: it had been lost), so its library can be reloaded.
     */
    suspend fun watch(serverUrl: () -> String, onConnected: (wasLost: Boolean) -> Unit) = withContext(Dispatchers.IO) {
        var fails = 0
        while (true) {
            val url = serverUrl()
            if (url.isBlank()) { _connection.value = Connection.NONE; fails = 0; kotlinx.coroutines.delay(1500); continue }
            val ok = runCatching { get("$url/api/info", 1200, connectMs = 1200) }.isSuccess
            if (ok) {
                val was = _connection.value
                _connection.value = Connection.CONNECTED
                val wasLost = fails >= 2 || was == Connection.LOST
                if (wasLost) _problem.value = null
                if (was != Connection.CONNECTED) onConnected(wasLost)
                fails = 0
                kotlinx.coroutines.delay(1500)
            } else {
                fails++
                val wasConnected = _connection.value == Connection.CONNECTED || _connection.value == Connection.CHECKING
                _connection.value = if (fails >= 2) Connection.LOST else Connection.CHECKING
                if (fails == 2) reportProblem(
                    "DualAmp lost the connection to your PC (${url.removePrefix("http://")}).", always = wasConnected,
                )
                // Check again straight away after the first miss, then every 1.5 s.
                if (fails > 1) kotlinx.coroutines.delay(1500)
            }
        }
    }

    /** "Try again": the next check runs now. */
    fun checkNow() { _connection.value = Connection.CHECKING }

    /** The pop-up was closed: [quietMs] without another one from background reloads. */
    fun dismissProblem(quietMs: Long = 10 * 60_000L) {
        _problem.value = null
        quietUntil = System.currentTimeMillis() + quietMs
    }

    /** Broadcasts on the Wi-Fi network and collects the DualAmp Servers that answer. */
    suspend fun discover(context: Context, timeoutMs: Int = 2500): List<Server> = withContext(Dispatchers.IO) {
        val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
        val lock = wifi.createMulticastLock("dualamp-discovery").apply { setReferenceCounted(false); acquire() }
        val found = LinkedHashMap<String, Server>()
        try {
            DatagramSocket().use { socket ->
                socket.broadcast = true
                socket.soTimeout = 400
                val msg = "DUALAMP?".toByteArray()
                val deadline = System.currentTimeMillis() + timeoutMs
                var lastSend = 0L
                while (System.currentTimeMillis() < deadline) {
                    if (System.currentTimeMillis() - lastSend > 800) {
                        socket.send(DatagramPacket(msg, msg.size, InetAddress.getByName("255.255.255.255"), DISCOVERY_PORT))
                        lastSend = System.currentTimeMillis()
                    }
                    val buf = ByteArray(2048)
                    val packet = DatagramPacket(buf, buf.size)
                    try {
                        socket.receive(packet)
                        val o = JSONObject(String(packet.data, 0, packet.length))
                        if (o.optString("app") != "dualamp-server") continue
                        val url = "http://${packet.address.hostAddress}:${o.optInt("port", 8337)}"
                        found[url] = Server(o.optString("name", url), url)
                    } catch (_: SocketTimeoutException) {
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Discovery failed", e)
        } finally {
            lock.release()
        }
        found.values.toList()
    }

    /** Checks an address typed by hand ("192.168.1.20" or "http://...:8337"). */
    suspend fun probe(address: String): Server? = withContext(Dispatchers.IO) {
        val url = normalize(address)
        runCatching {
            val o = JSONObject(get("$url/api/info", 3000))
            if (o.optString("app") == "dualamp-server") Server(o.optString("name", url), url) else null
        }.getOrNull()
    }

    fun normalize(address: String): String {
        var a = address.trim().trimEnd('/')
        if (!a.startsWith("http://") && !a.startsWith("https://")) a = "http://$a"
        if (Regex("""^https?://[^/:]+$""").matches(a)) a += ":8337"
        return a
    }

    /**
     * Downloads the server's track list (cached on the Thor so the library still lists PC songs
     * briefly offline; they only play while the server is reachable).
     */
    suspend fun fetchSongs(context: Context, serverUrl: String): List<Song> = withContext(Dispatchers.IO) {
        val cache = File(context.cacheDir, "remote-library.json")
        val text = try {
            get("$serverUrl/api/library", 15000).also { cache.writeText(it); _problem.value = null }
        } catch (e: Exception) {
            Log.w(TAG, "Server $serverUrl not reachable: ${e.message}")
            reportProblem("DualAmp couldn't load the music on your PC (${serverUrl.removePrefix("http://")}).")
            cache.takeIf { it.exists() }?.readText() ?: return@withContext emptyList()
        }
        parse(text, serverUrl)
    }

    private fun parse(text: String, serverUrl: String): List<Song> {
        val root = JSONObject(text)
        val name = root.optString("name", "PC")
        val tracks = root.getJSONArray("tracks")
        return (0 until tracks.length()).map { i ->
            val t = tracks.getJSONObject(i)
            val id = t.getString("id")
            val artist = t.optString("artist").ifBlank { "Unknown Artist" }
            val albumArtist = t.optString("albumArtist")
            val album = t.optString("album").ifBlank { "Unknown Album" }
            val file = t.optString("file").replace('\\', '/')
            Song(
                // Negative ids never clash with Android media-store ids.
                id = -(java.lang.Long.parseUnsignedLong(id.take(15), 16) and Long.MAX_VALUE) - 1,
                title = t.optString("title").ifBlank { file.substringAfterLast('/') },
                artist = artist,
                album = album,
                albumId = -((("${albumArtist.ifBlank { artist }}|$album").lowercase().hashCode().toLong() and 0x7fffffff) + 1),
                albumArtist = albumArtist,
                genre = t.optString("genre"),
                year = t.optInt("year"),
                track = t.optInt("track"),
                disc = t.optInt("disc", 1).coerceAtLeast(1),
                durationMs = t.optLong("durationMs"),
                // Folders view: "/PC: <name>/<drive>/<path>".
                path = "/PC: $name/" + file.replace(":", ""),
                mime = t.optString("mime", "audio/mpeg"),
                dateAdded = (t.optDouble("mtime", 0.0) / 1000).toLong(),
                bitrate = t.optInt("bitrate"),
                remoteUrl = "$serverUrl/stream/$id",
                remoteArtUrl = "$serverUrl/art/$id",
            )
        }
    }

    /** [connectMs]: how long to wait for the PC to answer at all (kept short so a missing PC shows up fast). */
    private fun get(url: String, timeoutMs: Int, connectMs: Int = 3000): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = connectMs
        c.readTimeout = timeoutMs
        try {
            if (c.responseCode != 200) error("HTTP ${c.responseCode}")
            return c.inputStream.bufferedReader().readText()
        } finally {
            c.disconnect()
        }
    }

    /** Album art for a PC song. */
    fun downloadBytes(url: String): ByteArray? = runCatching {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 3000
        c.readTimeout = 8000
        try { if (c.responseCode == 200) c.inputStream.readBytes() else null } finally { c.disconnect() }
    }.getOrNull()
}
