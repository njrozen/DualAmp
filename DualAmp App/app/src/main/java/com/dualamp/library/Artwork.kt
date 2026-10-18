package com.dualamp.library

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.provider.MediaStore
import android.util.LruCache
import android.util.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/** Album art loading with an in-memory cache. Tries MediaStore thumbnails, then embedded art. */
object Artwork {
    private val cache = object : LruCache<String, ImageBitmap>(48 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 4
    }
    private val missing = HashSet<String>()
    private val limiter = Semaphore(4)

    fun cached(albumId: Long, sizePx: Int): ImageBitmap? = cache.get(key(albumId, sizePx))

    /** Art addresses for albums streamed from a PC (negative album ids). */
    private val remoteArt = java.util.concurrent.ConcurrentHashMap<Long, String>()

    fun registerRemote(albumId: Long, url: String?) {
        if (url != null) remoteArt.putIfAbsent(albumId, url)
    }

    private fun fromRemote(albumId: Long, sizePx: Int): Bitmap? {
        val bytes = remoteArt[albumId]?.let(RemoteLibrary::downloadBytes) ?: return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= bucket(sizePx)) sample *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    suspend fun load(context: Context, albumId: Long, songId: Long, sizePx: Int): ImageBitmap? {
        val key = key(albumId, sizePx)
        cache.get(key)?.let { return it }
        synchronized(missing) { if (key in missing) return null }
        return limiter.withPermit {
            withContext(Dispatchers.IO) {
                val bmp = if (albumId < 0) fromRemote(albumId, sizePx)
                    else fromMediaStore(context, albumId, songId, sizePx) ?: fromEmbedded(context, songId, sizePx)
                val image = bmp?.asImageBitmap()
                if (image != null) cache.put(key, image) else synchronized(missing) { missing += key }
                image
            }
        }
    }

    /** Rounds the requested size up so nearby sizes share one cache entry. */
    private fun bucket(sizePx: Int) = when {
        sizePx <= 128 -> 128
        sizePx <= 256 -> 256
        sizePx <= 512 -> 512
        else -> 1024
    }

    private fun key(albumId: Long, sizePx: Int) = "$albumId@${bucket(sizePx)}"

    private fun fromMediaStore(context: Context, albumId: Long, songId: Long, sizePx: Int): Bitmap? {
        val size = Size(bucket(sizePx), bucket(sizePx))
        val uris = listOf(
            ContentUris.withAppendedId(MediaStore.Audio.Albums.EXTERNAL_CONTENT_URI, albumId),
            Song.contentUri(songId),
        )
        for (uri in uris) {
            try {
                return context.contentResolver.loadThumbnail(uri, size, null)
            } catch (_: Exception) {
            }
        }
        return null
    }

    private fun fromEmbedded(context: Context, songId: Long, sizePx: Int): Bitmap? {
        if (songId < 0) return null
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, Song.contentUri(songId))
            val bytes = retriever.embeddedPicture ?: return null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= bucket(sizePx)) sample *= 2
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
        } catch (_: Exception) {
            null
        } finally {
            retriever.release()
        }
    }
}
