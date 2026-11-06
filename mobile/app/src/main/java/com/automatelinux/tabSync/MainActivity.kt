package com.automatelinux.tabSync

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Browser
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.SystemBarStyle
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : ComponentActivity() {
    private var onScreen by mutableStateOf(false)

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
        val platform = AndroidPlatform(this)
        setContent { App(platform, heebo, onScreen) }
    }
}

private val json = Json { ignoreUnknownKeys = true }

class AndroidPlatform(private val context: Context) : Platform {

    override suspend fun loadTabs(): TabList = json.decodeFromString(TabList.serializer(), get("api/tabs"))

    override suspend fun loadSound(): SoundStatus = json.decodeFromString(SoundStatus.serializer(), get("api/sound"))

    override suspend fun setSound(onPhone: Boolean): SoundStatus =
        json.decodeFromString(SoundStatus.serializer(), get("api/sound", postBody = """{"on":$onPhone}"""))

    private suspend fun get(path: String, postBody: String? = null): String = withContext(Dispatchers.IO) {
        val conn = URL(BuildConfig.API_BASE_URL + path).openConnection() as HttpURLConnection
        conn.connectTimeout = 5000
        conn.readTimeout = 20000
        conn.setRequestProperty("Authorization", "Bearer ${BuildConfig.API_TOKEN}")
        try {
            if (postBody != null) {
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(postBody.toByteArray()) }
            }
            val code = conn.responseCode
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
            body
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
