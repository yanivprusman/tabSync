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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : ComponentActivity() {
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
        setContent { App(AndroidPlatform(this), heebo) }
    }
}

private val json = Json { ignoreUnknownKeys = true }

class AndroidPlatform(private val context: Context) : Platform {

    override suspend fun loadTabs(): TabList = withContext(Dispatchers.IO) {
        val conn = URL(BuildConfig.API_BASE_URL + "api/tabs").openConnection() as HttpURLConnection
        conn.connectTimeout = 5000
        conn.readTimeout = 20000
        conn.setRequestProperty("Authorization", "Bearer ${BuildConfig.API_TOKEN}")
        try {
            val code = conn.responseCode
            val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                val msg = runCatching {
                    json.parseToJsonElement(body).let { (it as kotlinx.serialization.json.JsonObject)["error"] }
                }.getOrNull()?.toString()?.trim('"')
                throw IllegalStateException("Desktop answered HTTP $code${msg?.let { ": $it" } ?: ""}")
            }
            json.decodeFromString(TabList.serializer(), body)
        } catch (e: java.io.IOException) {
            throw IllegalStateException("Can't reach the desktop at ${BuildConfig.API_BASE_URL} — is WireGuard on? (${e.message})")
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
