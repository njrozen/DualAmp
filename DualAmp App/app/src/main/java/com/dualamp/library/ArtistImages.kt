package com.dualamp.library

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.dualamp.settings.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest

/**
 * Pictures of artists: your own (picked from your photos), or, with Settings > Fetch artist pictures on and the Thor on
 * Wi-Fi, one found online (Deezer's artist search; only the artist's name is sent). Without either, artists show one
 * of their album covers as before.
 */
object ArtistImages {
    private const val TAG = "ArtistImages"
    /** Don't ask again for an artist that wasn't found for this long. */
    private const val RETRY_MS = 14L * 24 * 3600 * 1000

    private lateinit var app: Context
    private lateinit var customDir: File
    private lateinit var onlineDir: File
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val work = Channel<String>(Channel.UNLIMITED)
    private val queued = HashSet<String>()
    private val _revision = MutableStateFlow(0)
    /** Goes up whenever a picture is added, found or removed. */
    val revision: StateFlow<Int> = _revision.asStateFlow()
    private val cache = object : LruCache<String, ImageBitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 4
    }

    fun init(context: Context) {
        if (::app.isInitialized) return
        app = context.applicationContext
        customDir = File(app.filesDir, "artists/custom").apply { mkdirs() }
        onlineDir = File(app.filesDir, "artists/online").apply { mkdirs() }
        scope.launch {
            for (name in work) {
                try { fetch(name) } catch (e: Exception) { Log.w(TAG, "Could not fetch a picture of $name", e) }
                synchronized(queued) { queued -= artistKey(name) }
                delay(250)
            }
        }
    }

    private fun hash(name: String): String =
        MessageDigest.getInstance("SHA-1").digest(artistKey(name).toByteArray()).joinToString("") { "%02x".format(it) }

    private fun customFile(name: String) = File(customDir, hash(name) + ".jpg")
    private fun onlineFile(name: String) = File(onlineDir, hash(name) + ".jpg")
    private fun missFile(name: String) = File(onlineDir, hash(name) + ".none")

    fun hasCustom(name: String) = ::app.isInitialized && customFile(name).exists()

    /** The picture to show for [name]: yours, or the one found online (while that's turned on); null: use a cover. */
    fun file(name: String): File? {
        if (!::app.isInitialized) return null
        customFile(name).takeIf { it.exists() }?.let { return it }
        if (Prefs.fetchArtistArt.value) onlineFile(name).takeIf { it.exists() }?.let { return it }
        return null
    }

    /** [name] is being shown: look for a picture online if there is none yet (and fetching is on, on Wi-Fi). */
    fun want(name: String) {
        if (!::app.isInitialized || !Prefs.fetchArtistArt.value || name.isBlank()) return
        if (customFile(name).exists() || onlineFile(name).exists()) return
        val miss = missFile(name)
        if (miss.exists() && System.currentTimeMillis() - miss.lastModified() < RETRY_MS) return
        if (!onWifi()) return
        val add = synchronized(queued) { queued.add(artistKey(name)) }
        if (add) work.trySend(name)
    }

    private fun onWifi(): Boolean {
        val cm = app.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ||
                !cm.isActiveNetworkMetered)
    }

    /** Fetching was turned on: artists that weren't found before are tried again. */
    fun retryMissing() {
        if (!::app.isInitialized) return
        scope.launch { onlineDir.listFiles { f -> f.name.endsWith(".none") }?.forEach { it.delete() } }
        _revision.value++
    }

    private fun simple(s: String) = s.lowercase().replace(Regex("^the\\s+"), "").filter { it.isLetterOrDigit() }

    private fun fetch(name: String) {
        if (!Prefs.fetchArtistArt.value || !onWifi()) return
        val q = URLEncoder.encode(name, "UTF-8")
        val json = get("https://api.deezer.com/search/artist?q=$q&limit=8")?.toString(Charsets.UTF_8)
        val data = json?.let { runCatching { JSONObject(it).optJSONArray("data") }.getOrNull() }
        if (data == null) return // no answer (offline?): try again another time
        val want = simple(name)
        var url: String? = null
        for (i in 0 until data.length()) {
            val o = data.optJSONObject(i) ?: continue
            if (simple(o.optString("name")) != want) continue
            // Deezer's stand-in for artists without a photo has an empty id in its address ("/artist//").
            val pic = o.optString("picture_xl").ifBlank { o.optString("picture_big") }
            if (pic.isNotBlank() && !pic.contains("/artist//")) { url = pic; break }
        }
        if (url == null) { missFile(name).writeText(""); return }
        val bytes = get(url) ?: return
        if (save(bytes, onlineFile(name))) _revision.value++
    }

    private fun get(url: String): ByteArray? {
        val c = URL(url).openConnection() as HttpURLConnection
        return try {
            c.connectTimeout = 8000; c.readTimeout = 12000
            c.setRequestProperty("User-Agent", "DualAmp (https://github.com/njrozen/DualAmp)")
            if (c.responseCode != 200) null else c.inputStream.use { it.readBytes() }
        } catch (e: Exception) { null } finally { c.disconnect() }
    }

    /** Scales a picture down to at most 900 px and keeps it as a JPEG. */
    private fun save(bytes: ByteArray, to: File): Boolean {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0) return false
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 900) sample *= 2
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return false
        val tmp = File(to.parentFile, to.name + ".tmp")
        tmp.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 88, it) }
        bmp.recycle()
        return tmp.renameTo(to)
    }

    /** Your own picture for [name] (from your photos). */
    suspend fun setCustom(name: String, bytes: ByteArray): Boolean = withContext(Dispatchers.IO) {
        save(bytes, customFile(name)).also { if (it) _revision.value++ }
    }

    /** Back to the picture found online, or a cover. */
    fun removeCustom(name: String) {
        if (customFile(name).delete()) _revision.value++
    }

    // --- Playlist pictures: your own photo for a playlist (Y on a playlist > Choose playlist picture) ---------------------

    private fun playlistFile(id: String) = File(File(app.filesDir, "playlists").apply { mkdirs() },
        MessageDigest.getInstance("SHA-1").digest(id.toByteArray()).joinToString("") { "%02x".format(it) } + ".jpg")

    /** Your picture for the playlist [id], or null (it shows its first song's cover). */
    fun playlistPicture(id: String): File? = if (!::app.isInitialized) null else playlistFile(id).takeIf { it.exists() }

    suspend fun setPlaylistPicture(id: String, bytes: ByteArray): Boolean = withContext(Dispatchers.IO) {
        save(bytes, playlistFile(id)).also { if (it) _revision.value++ }
    }

    fun removePlaylistPicture(id: String) {
        if (::app.isInitialized && playlistFile(id).delete()) _revision.value++
    }

    /** The picture in [f], about [px] wide (cached). */
    suspend fun load(f: File, px: Int): ImageBitmap? {
        val key = "${f.path}#${f.lastModified()}#$px"
        cache.get(key)?.let { return it }
        return withContext(Dispatchers.IO) {
            runCatching {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(f.path, bounds)
                var sample = 1
                while (minOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= px) sample *= 2
                BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
            }.getOrNull()?.also { cache.put(key, it) }
        }
    }
}
