package com.automatelinux.tabSync

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.automatelinux.tabSync.ui.theme.AppTheme
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

@Serializable
data class Tab(val title: String, val url: String)

@Serializable
data class Skipped(val title: String, val reason: String)

@Serializable
data class TabList(val tabs: List<Tab> = emptyList(), val skipped: List<Skipped> = emptyList())

/** What the platform supplies: fetch the desktop's tabs, open URLs in the phone's browser. */
interface Platform {
    suspend fun loadTabs(): TabList
    fun open(url: String)
    suspend fun openAll(urls: List<String>)
}

private sealed interface State {
    data object Loading : State
    data class Loaded(val list: TabList) : State
    data class Failed(val message: String) : State
}

@Composable
fun App(platform: Platform) {
    AppTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            TabsScreen(platform)
        }
    }
}

@Composable
private fun TabsScreen(platform: Platform) {
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf<State>(State.Loading) }
    var opening by remember { mutableStateOf(false) }
    var reloadKey by remember { mutableStateOf(0) }

    LaunchedEffect(reloadKey) {
        state = State.Loading
        state = try {
            State.Loaded(platform.loadTabs())
        } catch (e: Exception) {
            State.Failed(e.message ?: e.toString())
        }
    }

    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 12.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Desktop tabs", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                val sub = when (val s = state) {
                    is State.Loaded -> "${s.list.tabs.size} open on the desktop"
                    is State.Loading -> "Reading the desktop's Chrome…"
                    is State.Failed -> "Couldn't reach the desktop"
                }
                Text(sub, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = { reloadKey++ }, modifier = Modifier.testTag("refresh-tabs")) {
                Icon(Icons.Default.Refresh, contentDescription = "Refresh")
            }
        }

        when (val s = state) {
            is State.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            is State.Failed -> Column(Modifier.padding(20.dp)) {
                Text(s.message, color = MaterialTheme.colorScheme.error)
                Spacer(Modifier.height(12.dp))
                Button(onClick = { reloadKey++ }, modifier = Modifier.testTag("retry-tabs")) { Text("Try again") }
            }
            is State.Loaded -> {
                val tabs = s.list.tabs
                Button(
                    onClick = {
                        opening = true
                        scope.launch {
                            try { platform.openAll(tabs.map { it.url }) } finally { opening = false }
                        }
                    },
                    enabled = tabs.isNotEmpty() && !opening,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp).height(52.dp)
                        .testTag("open-all-tabs"),
                ) {
                    if (opening) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                        Spacer(Modifier.width(10.dp))
                        Text("Opening…")
                    } else {
                        Text(if (tabs.isEmpty()) "Nothing to open" else "Open all ${tabs.size} on this phone")
                    }
                }
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                    items(tabs, key = { it.url }) { tab -> TabRow(tab) { platform.open(tab.url) } }
                    if (s.list.skipped.isNotEmpty()) {
                        item {
                            val why = s.list.skipped.map { it.reason }.distinct().joinToString(", ")
                            Text(
                                "Left on the desktop: ${s.list.skipped.size} ($why)",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TabRow(tab: Tab, onOpen: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onOpen).testTag("open-tab")
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(tab.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                tab.url.substringAfter("://").trimEnd('/'),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = "Open", tint = MaterialTheme.colorScheme.primary)
    }
    HorizontalDivider(Modifier.padding(start = 20.dp))
}
