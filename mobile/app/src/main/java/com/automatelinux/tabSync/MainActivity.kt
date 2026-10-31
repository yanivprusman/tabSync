package com.automatelinux.tabSync

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Browser
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { App(AndroidPlatform(this)) }
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

    override suspend fun openAll(urls: List<String>) {
        urls.forEachIndexed { i, url ->
            start(intentFor(url, i))
            delay(350) // let Chrome take each intent before the next one lands
        }
    }

    private fun start(intent: Intent) {
        try {
            context.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            throw IllegalStateException("Chrome is not installed on this phone")
        }
    }

    companion object { const val CHROME = "com.android.chrome" }
}
