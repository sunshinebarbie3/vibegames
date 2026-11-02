package com.vibe.app.data.repository

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import com.vibe.app.data.remote.VibeApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File

/** Persist selected photos before queuing; content URI permissions expire. */
class PhotoStore(context: Context) {
    private val resolver = context.applicationContext.contentResolver
    val directory = File(context.filesDir, "pending-photos").apply { mkdirs() }
    suspend fun persist(source: String): String = withContext(Dispatchers.IO) {
        if (!source.startsWith("content://")) return@withContext source
        val uri = Uri.parse(source)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "Choose a valid photo." }
        var sample = 1
        while (bounds.outWidth / sample > 2048 || bounds.outHeight / sample > 2048) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        val bitmap = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
            ?: error("Unable to read photo.")
        val rotation = resolver.openInputStream(uri)?.use {
            when (ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        } ?: 0f
        val matrix = Matrix().apply { postRotate(rotation); postScale(minOf(1f, 1600f / bitmap.width), minOf(1f, 1600f / bitmap.height)) }
        val normalized = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        val file = File(directory, "${java.util.UUID.randomUUID()}.jpg")
        file.outputStream().use { check(normalized.compress(Bitmap.CompressFormat.JPEG, 85, it)) }
        if (normalized !== bitmap) normalized.recycle()
        bitmap.recycle()
        Uri.fromFile(file).toString()
    }
    fun clear() { directory.listFiles()?.forEach { it.delete() } }
}

class MediaRepository(private val api: VibeApi, val photos: PhotoStore) {
    suspend fun uploadAll(uris: List<String>, groupId: String?): List<String> = uris.map { upload(it, groupId) }

    suspend fun upload(localUri: String, groupId: String?): String = withContext(Dispatchers.IO) {
        if (localUri.startsWith("vibe-blob://")) return@withContext localUri
        val file = File(Uri.parse(localUri).path ?: error("Invalid local photo"))
        check(file.exists()) { "The original photo is no longer on this device." }
        val part = MultipartBody.Part.createFormData("photo", file.name, file.asRequestBody("image/jpeg".toMediaType()))
        api.uploadPhoto(part, groupId?.toRequestBody("text/plain".toMediaType())).uri
    }
}
