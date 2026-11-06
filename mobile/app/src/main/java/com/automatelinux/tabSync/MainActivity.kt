package com.automatelinux.tabSync

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.provider.Settings
import android.util.Size
import android.provider.Browser
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.SystemBarStyle
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.enableEdgeToEdge
import android.graphics.BitmapFactory
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    private var onScreen by mutableStateOf(false)

    private var photoAccessReply: CompletableDeferred<Boolean>? = null
    private val photoAccessPrompt = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        photoAccessReply?.complete(hasPhotoAccess())
    }

    /** Full access only: with "Select photos" the app could not tell which photo is the newest. */
    private fun hasPhotoAccess() = checkSelfPermission(fullPhotoPermission()) == PackageManager.PERMISSION_GRANTED

    private fun fullPhotoPermission() =
        if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE

    private suspend fun askForPhotoAccess(): Boolean {
        if (hasPhotoAccess()) return true
        val reply = CompletableDeferred<Boolean>()
        photoAccessReply = reply
        val asked = if (Build.VERSION.SDK_INT >= 34) {
            arrayOf(fullPhotoPermission(), Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        } else {
            arrayOf(fullPhotoPermission())
        }
        photoAccessPrompt.launch(asked)
        return reply.await()
    }

    private var pickerReply: CompletableDeferred<List<Uri>>? = null
    private val photoPicker = registerForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) {
        pickerReply?.complete(it)
    }

    /** The system photo picker: one or many, and it needs no permission. Empty when cancelled. */
    private suspend fun pickFromSystem(): List<Uri> {
        val reply = CompletableDeferred<List<Uri>>()
        pickerReply = reply
        photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        return reply.await()
    }

    override fun onResume() {
        super.onResume()
        onScreen = true
    }

    override fun onPause() {
        onScreen = false
        super.onPause()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Dark navy UI: force light system-bar icons whatever the phone's day/night setting.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        val heebo = FontFamily(
            Font(R.font.heebo_regular, FontWeight.Normal),
            Font(R.font.heebo_medium, FontWeight.Medium),
            Font(R.font.heebo_bold, FontWeight.Bold),
        )
        val platform = AndroidPlatform(this, ::hasPhotoAccess, ::askForPhotoAccess, ::pickFromSystem)
        setContent { App(platform, heebo, onScreen) }
    }
}

private val json = Json { ignoreUnknownKeys = true }

class AndroidPlatform(
    private val context: Context,
    private val photoAccess: () -> Boolean,
    private val askForPhotoAccess: suspend () -> Boolean,
    private val pickFromSystem: suspend () -> List<Uri>,
) : Platform {

    // The list is polled, and carries every favicon: the desktop answers "unchanged" instead.
    private var lastTabs: TabList? = null
    private var lastTabsEtag: String? = null

    override suspend fun loadTabs(): TabList {
        val reply = request("api/tabs", etag = lastTabsEtag)
        lastTabs?.takeIf { reply.notModified }?.let { return it }
        return json.decodeFromString(TabList.serializer(), reply.body).also {
            lastTabs = it
            lastTabsEtag = reply.etag
        }
    }

    override suspend fun loadSound(): SoundStatus =
        json.decodeFromString(SoundStatus.serializer(), request("api/sound").body)

    override suspend fun setSound(onPhone: Boolean) = postSound("""{"on":$onPhone}""")

    override suspend fun setVolume(percent: Int) = postSound("""{"percent":$percent}""")

    override suspend fun setMuted(muted: Boolean) = postSound("""{"muted":$muted}""")

    private suspend fun postSound(body: String): SoundStatus = json.decodeFromString(
        SoundStatus.serializer(),
        request("api/sound", "application/json") { it.write(body.toByteArray()) }.body,
    )

    override fun hasPhotoAccess() = photoAccess()

    override suspend fun requestPhotoAccess() = askForPhotoAccess()

    override fun openAppSettings() {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)),
        )
    }

    /**
     * Newest images in DCIM, which is the camera and screenshots. Not the whole gallery: chat
     * apps save every image they receive, and those are not photos taken on this phone.
     */
    override suspend fun recentPhotos(limit: Int): List<String> = withContext(Dispatchers.IO) {
        val images = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val cursor = context.contentResolver.query(
            images,
            arrayOf(MediaStore.Images.Media._ID),
            "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?",
            arrayOf("DCIM/%"),
            "${MediaStore.Images.Media.DATE_ADDED} DESC",
        ) ?: throw Exception("The phone's photo library did not answer")
        cursor.use {
            buildList {
                while (size < limit && it.moveToNext()) add(ContentUris.withAppendedId(images, it.getLong(0)).toString())
            }
        }
    }

    override suspend fun thumbnail(photo: String): ImageBitmap? = withContext(Dispatchers.IO) {
        context.contentResolver.loadThumbnail(Uri.parse(photo), Size(288, 288), null).asImageBitmap()
    }

    override suspend fun pickPhotos(): List<String> = pickFromSystem().map { it.toString() }

    override suspend fun sendPhotos(photos: List<String>, onProgress: (Int, Int) -> Unit): List<SentPhoto> =
        withContext(Dispatchers.IO) {
            val sent = mutableListOf<SentPhoto>()
            for (photo in photos) {
                onProgress(sent.size, photos.size)
                try {
                    sent += upload(Uri.parse(photo))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    throw Exception(if (sent.isEmpty()) e.message else "Sent ${sent.size} of ${photos.size}, then: ${e.message}")
                }
            }
            sent
        }

    private suspend fun upload(uri: Uri): SentPhoto {
        val resolver = context.contentResolver
        val type = resolver.getType(uri) ?: throw Exception("The phone did not say what type of file $uri is")
        val cursor = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, MediaStore.Images.Media.DATE_TAKEN), null, null, null)
            ?: throw Exception("The phone did not say what $uri is called")
        val name = cursor.use {
            if (!it.moveToFirst()) throw Exception("The phone did not say what $uri is called")
            desktopName(it.getString(0), it.getLong(1))
        }
        val input = resolver.openInputStream(uri) ?: throw Exception("The phone would not open $name")
        val reply = input.use { source ->
            request("api/photo?name=${Uri.encode(name)}", type) { source.copyTo(it) }
        }
        return json.decodeFromString(SentPhoto.serializer(), reply.body)
    }

    /** The system picker hides real file names and reports "19.jpg", which means nothing on the desktop. */
    private fun desktopName(name: String, takenAtMs: Long): String {
        val stem = name.substringBeforeLast('.')
        if (takenAtMs <= 0 || stem.any { !it.isDigit() }) return name
        val taken = SimpleDateFormat("yyyy-MM-dd HH-mm-ss", Locale.ROOT).format(Date(takenAtMs))
        return "Phone photo $taken.${name.substringAfterLast('.')}"
    }

    private class Reply(val body: String, val etag: String?, val notModified: Boolean)

    private suspend fun request(
        path: String,
        contentType: String? = null,
        etag: String? = null,
        writeBody: ((OutputStream) -> Unit)? = null,
    ): Reply = withContext(Dispatchers.IO) {
        val conn = URL(BuildConfig.API_BASE_URL + path).openConnection() as HttpURLConnection
        conn.connectTimeout = 5000
        conn.readTimeout = 20000
        conn.setRequestProperty("Authorization", "Bearer ${BuildConfig.API_TOKEN}")
        if (etag != null) conn.setRequestProperty("If-None-Match", etag)
        try {
            if (writeBody != null) {
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.setChunkedStreamingMode(64 * 1024) // a photo must not be buffered in memory
                conn.setRequestProperty("Content-Type", contentType)
                conn.outputStream.use(writeBody)
            }
            val code = conn.responseCode
            if (code == HttpURLConnection.HTTP_NOT_MODIFIED) return@withContext Reply("", etag, true)
            val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                val obj = runCatching { json.parseToJsonElement(body) as JsonObject }.getOrNull()
                val msg = (obj?.get("error") as? JsonPrimitive)?.content
                val reason = (obj?.get("reason") as? JsonPrimitive)?.content
                throw LoadFailure(
                    if (code == 401) FailureKind.Desktop else FailureKind.fromReason(reason),
                    msg ?: "Desktop answered HTTP $code",
                )
            }
            Reply(body, conn.getHeaderField("ETag"), false)
        } catch (e: java.io.IOException) {
            throw LoadFailure(
                FailureKind.Offline,
                "No answer from ${BuildConfig.API_BASE_URL} — is WireGuard on, and is the desktop awake? (${e.message})",
            )
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Each URL in its OWN new Chrome tab. Chrome reuses the tab that an app
     * opened last when the intent carries the same application id, so every
     * URL gets a distinct id plus EXTRA_CREATE_NEW_TAB.
     */
    private fun intentFor(url: String, n: Int) = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
        setPackage(CHROME)
        putExtra(Browser.EXTRA_CREATE_NEW_TAB, true)
        putExtra(Browser.EXTRA_APPLICATION_ID, "${context.packageName}.$n.${System.nanoTime()}")
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    override fun open(url: String) = start(intentFor(url, 0))

    /**
     * All intents go out in ONE synchronous burst, while this app is still in the
     * foreground. The first one brings Chrome to the front; from then on this app
     * is in the background, and Android's background-activity-start rules silently
     * drop further startActivity calls (measured: with a 350 ms gap, only 2 of 5
     * tabs opened). Chrome queues a burst fine.
     */
    override suspend fun openAll(urls: List<String>, onOpened: (Int) -> Unit) {
        urls.forEachIndexed { i, url ->
            start(intentFor(url, i))
            onOpened(i + 1)
        }
    }

    override fun decodeImage(bytes: ByteArray): ImageBitmap? =
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()

    private fun start(intent: Intent) {
        try {
            context.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            throw IllegalStateException("Chrome is not installed on this phone")
        }
    }

    companion object { const val CHROME = "com.android.chrome" }
}
