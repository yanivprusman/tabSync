package com.automatelinux.tabSync

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Chat
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Dashboard
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Style
import androidx.compose.material.icons.rounded.WebAssetOff
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.automatelinux.tabSync.ui.theme.AppTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.Serializable
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

// ── data ────────────────────────────────────────────────────────────────────

@Serializable
data class Tab(val title: String, val url: String, val host: String = "", val icon: String? = null)

@Serializable
data class Skipped(val title: String, val reason: String)

@Serializable
data class TabList(val tabs: List<Tab> = emptyList(), val skipped: List<Skipped> = emptyList())

/**
 * Why loading failed — WHICH link broke. "Can't reach the desktop" is only true
 * for [Offline]; for the others the desktop answered and is telling us what is
 * wrong on its side, so the screen must say that instead.
 */
enum class FailureKind {
    /** No answer from the desktop at all (WireGuard down, desktop off, server down). */
    Offline,
    /** The desktop answered: its everyday Chrome is not running. */
    ChromeClosed,
    /** The desktop answered: Chrome runs, but without the DevTools port. */
    ChromeNoDebugging,
    /** The desktop answered with some other error. */
    Desktop;

    companion object {
        /** Maps the server's `reason` field. */
        fun fromReason(reason: String?): FailureKind = when (reason) {
            "chrome-closed" -> ChromeClosed
            "chrome-no-debugging" -> ChromeNoDebugging
            else -> Desktop
        }
    }
}

class LoadFailure(val kind: FailureKind, message: String) : Exception(message)

/** What the platform supplies. */
interface Platform {
    suspend fun loadTabs(): TabList
    fun open(url: String)
    /** Opens each URL in its own browser tab; [onOpened] gets the count so far. */
    suspend fun openAll(urls: List<String>, onOpened: (Int) -> Unit)
    fun decodeImage(bytes: ByteArray): ImageBitmap?
}

// ── palette (the icon's navy + the travelling tab's teal) ─────────────────────

private object C {
    val bg = Color(0xFF0B1033)
    val bgTop = Color(0xFF17205A)
    val card = Color(0xFF141B49)
    val cardEdge = Color(0xFF232C6B)
    val chip = Color(0xFF1E2766)
    val text = Color(0xFFEEF1FF)
    val muted = Color(0xFF98A2D8)
    val faint = Color(0xFF5F6AA6)
    val teal = Color(0xFF5EE6D3)
    val tealDeep = Color(0xFF1FA6C4)
    val onTeal = Color(0xFF05222B)
    val error = Color(0xFFFF9A8F)
}

private sealed interface State {
    data object Loading : State
    data class Loaded(val list: TabList, val at: String) : State
    data class Failed(val kind: FailureKind, val message: String) : State
}

private fun nowHm(): String {
    val t = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
    return "${t.hour.toString().padStart(2, '0')}:${t.minute.toString().padStart(2, '0')}"
}

@Composable
fun App(platform: Platform, font: FontFamily = FontFamily.Default) {
    AppTheme {
        Box(
            Modifier.fillMaxSize().background(Brush.verticalGradient(0f to C.bgTop, 0.45f to C.bg))
        ) { TabsScreen(platform, font) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TabsScreen(platform: Platform, font: FontFamily) {
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf<State>(State.Loading) }
    var refreshing by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf<Int?>(null) } // tabs opened so far during "open all"
    val opened = remember { mutableStateMapOf<String, Boolean>() }

    suspend fun load(userPulled: Boolean) {
        if (userPulled) refreshing = true else state = State.Loading
        val started = Clock.System.now()
        val next = try {
            State.Loaded(platform.loadTabs(), nowHm())
        } catch (e: LoadFailure) {
            State.Failed(e.kind, e.message ?: e.kind.name)
        } catch (e: Exception) {
            State.Failed(FailureKind.Desktop, e.message ?: e.toString())
        }
        // A refresh that answers in 20 ms looks ignored — hold the spinner long enough to read.
        val spent = (Clock.System.now() - started).inWholeMilliseconds
        if (userPulled && spent < 650) delay(650 - spent)
        state = next
        refreshing = false
    }

    LaunchedEffect(Unit) { load(userPulled = false) }

    val t = TextStyles(font)
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    Box(Modifier.fillMaxSize()) {
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = { scope.launch { load(userPulled = true) } },
            modifier = Modifier.fillMaxSize(),
        ) {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(top = top + 8.dp, bottom = bottom + 120.dp),
            ) {
                item { Header(state, t, onRefresh = { scope.launch { load(userPulled = true) } }) }
                when (val s = state) {
                    is State.Loading -> items(4) { SkeletonCard() }
                    is State.Failed -> item { ErrorCard(s.kind, s.message, t) { scope.launch { load(false) } } }
                    is State.Loaded -> {
                        if (s.list.tabs.isEmpty()) item { EmptyCard(t) }
                        items(s.list.tabs, key = { it.url }) { tab ->
                            TabCard(tab, opened[tab.url] == true, platform, t) {
                                platform.open(tab.url)
                                opened[tab.url] = true
                            }
                        }
                        if (s.list.skipped.isNotEmpty()) item { SkippedSection(s.list.skipped, t) }
                    }
                }
            }
        }

        val loaded = state as? State.Loaded
        if (loaded != null && loaded.list.tabs.isNotEmpty()) {
            val tabs = loaded.list.tabs
            BottomCta(
                total = tabs.size,
                progress = progress,
                allOpened = tabs.all { opened[it.url] == true },
                t = t,
                bottomInset = bottom,
                modifier = Modifier.align(Alignment.BottomCenter),
            ) {
                if (progress != null) return@BottomCta
                progress = 0
                scope.launch {
                    try {
                        platform.openAll(tabs.map { it.url }) { n ->
                            progress = n
                            opened[tabs[n - 1].url] = true
                        }
                    } finally {
                        delay(400)
                        progress = null
                    }
                }
            }
        }
    }
}

private class TextStyles(f: FontFamily) {
    val overline = TextStyle(fontFamily = f, fontWeight = FontWeight.Medium, fontSize = 12.sp, letterSpacing = 1.6.sp, color = C.muted)
    val hero = TextStyle(fontFamily = f, fontWeight = FontWeight.Bold, fontSize = 44.sp, lineHeight = 48.sp, color = C.text)
    val heroUnit = TextStyle(fontFamily = f, fontWeight = FontWeight.Medium, fontSize = 22.sp, color = C.muted)
    val sub = TextStyle(fontFamily = f, fontSize = 14.sp, color = C.muted)
    val title = TextStyle(fontFamily = f, fontWeight = FontWeight.Medium, fontSize = 16.sp, lineHeight = 21.sp, color = C.text)
    val host = TextStyle(fontFamily = f, fontSize = 13.sp, color = C.muted)
    val section = TextStyle(fontFamily = f, fontWeight = FontWeight.Medium, fontSize = 13.sp, letterSpacing = 1.2.sp, color = C.faint)
    val skipped = TextStyle(fontFamily = f, fontSize = 14.sp, color = C.muted)
    val cta = TextStyle(fontFamily = f, fontWeight = FontWeight.Bold, fontSize = 17.sp, color = C.onTeal)
    val body = TextStyle(fontFamily = f, fontSize = 15.sp, lineHeight = 21.sp, color = C.muted)
    val h2 = TextStyle(fontFamily = f, fontWeight = FontWeight.Bold, fontSize = 20.sp, color = C.text)
}

// ── header: the desktop → phone strip and the one number that matters ────────

@Composable
private fun Header(state: State, t: TextStyles, onRefresh: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 22.dp).padding(top = 10.dp, bottom = 18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TransferGlyph(active = state is State.Loading, modifier = Modifier.width(112.dp).height(34.dp))
            Spacer(Modifier.weight(1f))
            Box(
                Modifier.size(42.dp).clip(CircleShape).background(C.chip).clickable(onClick = onRefresh)
                    .testTag("refresh-tabs"),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Rounded.Refresh, "Refresh", tint = C.text, modifier = Modifier.size(22.dp)) }
        }
        Spacer(Modifier.height(22.dp))
        Text("ON YOUR DESKTOP", style = t.overline)
        Spacer(Modifier.height(2.dp))
        AnimatedContent(
            targetState = state,
            contentKey = { it::class },
            transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(160)) },
        ) { s ->
            Column {
                when (s) {
                    is State.Loading -> {
                        Text("…", style = t.hero)
                        Text("Reading the desktop's Chrome", style = t.sub)
                    }
                    is State.Failed -> {
                        Text("—", style = t.hero)
                        Text(
                            if (s.kind == FailureKind.Offline) "Not connected" else "Connected · can't read Chrome",
                            style = t.sub,
                        )
                    }
                    is State.Loaded -> {
                        val n = s.list.tabs.size
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text("$n", style = t.hero)
                            Spacer(Modifier.width(8.dp))
                            Text(if (n == 1) "tab" else "tabs", style = t.heroUnit, modifier = Modifier.padding(bottom = 6.dp))
                        }
                        Text("Updated ${s.at} · pull down to refresh", style = t.sub)
                    }
                }
            }
        }
    }
}

/** Monitor ⋯⋯ phone, with a teal tab that travels along the dashes while loading. */
@Composable
private fun TransferGlyph(active: Boolean, modifier: Modifier) {
    val phase by rememberInfiniteTransition().animateFloat(
        0f, 1f, infiniteRepeatable(tween(1100, easing = FastOutSlowInEasing), RepeatMode.Restart)
    )
    val p = if (active) phase else 1f
    Canvas(modifier) {
        val h = size.height
        val u = h / 34f
        // monitor
        drawRoundRect(Color(0xFFE8EEFF), Offset(0f, 2 * u), Size(40 * u, 26 * u), CornerRadius(4 * u))
        drawRoundRect(C.bgTop, Offset(3 * u, 8 * u), Size(34 * u, 17 * u), CornerRadius(1.5f * u))
        drawRoundRect(C.teal, Offset(15 * u, 4.5f * u), Size(9 * u, 3.5f * u), CornerRadius(1.2f * u))
        drawRect(Color(0xFFC9D4F5), Offset(17 * u, 28 * u), Size(6 * u, 3 * u))
        drawRoundRect(Color(0xFFC9D4F5), Offset(12 * u, 31 * u), Size(16 * u, 2.5f * u), CornerRadius(1.2f * u))
        // dashes
        val x0 = 46 * u
        val x1 = size.width - 26 * u
        drawLine(
            C.faint, Offset(x0, 16 * u), Offset(x1, 16 * u), strokeWidth = 2 * u, cap = StrokeCap.Round,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(3 * u, 4 * u)),
        )
        // the travelling tab
        val tx = x0 + (x1 - x0 - 8 * u) * p
        drawRoundRect(C.teal, Offset(tx, 12 * u), Size(8 * u, 8 * u), CornerRadius(2 * u), alpha = if (active) 1f else 0f)
        // phone
        val px = size.width - 20 * u
        drawRoundRect(
            Brush.linearGradient(listOf(C.teal, C.tealDeep), Offset(px, 0f), Offset(px + 20 * u, h)),
            Offset(px, 0f), Size(20 * u, h), CornerRadius(5 * u),
        )
        drawRoundRect(C.bg, Offset(px + 3 * u, 4 * u), Size(14 * u, 26 * u), CornerRadius(2 * u))
        drawRoundRect(C.teal, Offset(px + 5 * u, 8 * u), Size(10 * u, 18 * u), CornerRadius(1.2f * u))
    }
}

// ── cards ─────────────────────────────────────────────────────────────────────

@Composable
private fun TabCard(tab: Tab, opened: Boolean, platform: Platform, t: TextStyles, onOpen: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 5.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(C.card)
            .border(1.dp, if (opened) C.teal.copy(alpha = .45f) else C.cardEdge, RoundedCornerShape(18.dp))
            .clickable(onClick = onOpen)
            .testTag("open-tab")
            .padding(horizontal = 14.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Favicon(tab, platform, t)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(tab.title, style = t.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(3.dp))
            Text(tab.host, style = t.host, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(10.dp))
        Box(
            Modifier.size(36.dp).clip(CircleShape).background(if (opened) C.teal else C.chip),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (opened) Icons.Rounded.Check else Icons.AutoMirrored.Rounded.ArrowForward,
                contentDescription = if (opened) "Opened" else "Open on phone",
                tint = if (opened) C.onTeal else C.text,
                modifier = Modifier.size(19.dp),
            )
        }
    }
}

@OptIn(ExperimentalEncodingApi::class)
@Composable
private fun Favicon(tab: Tab, platform: Platform, t: TextStyles) {
    val bitmap = remember(tab.icon) {
        tab.icon?.substringAfter("base64,", "")?.takeIf { it.isNotEmpty() }
            ?.let { runCatching { platform.decodeImage(Base64.decode(it)) }.getOrNull() }
    }
    Box(
        Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(if (bitmap != null) Color(0xFFF4F6FF) else monogramColor(tab.host)),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            Image(bitmap, contentDescription = null, modifier = Modifier.size(26.dp))
        } else {
            val letter = tab.title.firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "•"
            Text(letter, style = t.h2.copy(fontSize = 19.sp, color = Color.White))
        }
    }
}

/** A stable, muted colour per site so tabs without a favicon are still told apart. */
private fun monogramColor(host: String): Color {
    val palette = listOf(0xFF3D4FB8, 0xFF2E7D8C, 0xFF7A4FB8, 0xFFB8644F, 0xFF4F8CB8, 0xFF8C6E2E)
    return Color(palette[(host.hashCode() and 0x7fffffff) % palette.size])
}

@Composable
private fun SkeletonCard() {
    val a by rememberInfiniteTransition().animateFloat(
        .35f, .7f, infiniteRepeatable(tween(800, easing = LinearEasing), RepeatMode.Reverse)
    )
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 5.dp)
            .clip(RoundedCornerShape(18.dp)).background(C.card).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(C.chip.copy(alpha = a)))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Box(Modifier.fillMaxWidth(.75f).height(14.dp).clip(RoundedCornerShape(7.dp)).background(C.chip.copy(alpha = a)))
            Spacer(Modifier.height(8.dp))
            Box(Modifier.fillMaxWidth(.4f).height(11.dp).clip(RoundedCornerShape(6.dp)).background(C.chip.copy(alpha = a)))
        }
    }
}

@Composable
private fun ErrorCard(kind: FailureKind, message: String, t: TextStyles, onRetry: () -> Unit) {
    val icon = when (kind) {
        FailureKind.Offline -> Icons.Rounded.WifiOff
        FailureKind.ChromeClosed, FailureKind.ChromeNoDebugging -> Icons.Rounded.WebAssetOff
        FailureKind.Desktop -> Icons.Rounded.ErrorOutline
    }
    val title = when (kind) {
        FailureKind.Offline -> "Can't reach the desktop"
        FailureKind.ChromeClosed -> "Chrome is closed on the desktop"
        FailureKind.ChromeNoDebugging -> "Chrome on the desktop isn't sharing its tabs"
        FailureKind.Desktop -> "The desktop couldn't list its tabs"
    }
    val body = when (kind) {
        FailureKind.ChromeClosed -> "The desktop is reachable — there are just no tabs to read. Open Chrome there, then try again."
        else -> message
    }
    Column(
        Modifier.fillMaxWidth().padding(16.dp).clip(RoundedCornerShape(20.dp)).background(C.card)
            .border(1.dp, C.cardEdge, RoundedCornerShape(20.dp)).padding(22.dp),
    ) {
        Icon(icon, null, tint = C.error, modifier = Modifier.size(30.dp))
        Spacer(Modifier.height(12.dp))
        Text(title, style = t.h2)
        Spacer(Modifier.height(6.dp))
        Text(body, style = t.body)
        Spacer(Modifier.height(18.dp))
        Box(
            Modifier.clip(RoundedCornerShape(50)).background(C.chip).clickable(onClick = onRetry)
                .testTag("retry-tabs").padding(horizontal = 22.dp, vertical = 12.dp),
        ) { Text("Try again", style = t.title) }
    }
}

@Composable
private fun EmptyCard(t: TextStyles) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 12.dp)) {
        Text("Nothing to bring over", style = t.h2)
        Spacer(Modifier.height(6.dp))
        Text("Every tab open on the desktop is one that stays there.", style = t.body)
    }
}

@Composable
private fun SkippedSection(skipped: List<Skipped>, t: TextStyles) {
    Column(Modifier.fillMaxWidth().padding(start = 22.dp, end = 22.dp, top = 26.dp)) {
        Text("STAYING ON THE DESKTOP", style = t.section)
        Spacer(Modifier.height(10.dp))
        skipped.forEach { s ->
            Row(Modifier.fillMaxWidth().padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(30.dp).clip(RoundedCornerShape(9.dp)).background(C.chip), contentAlignment = Alignment.Center) {
                    Icon(reasonIcon(s.reason), null, tint = C.muted, modifier = Modifier.size(17.dp))
                }
                Spacer(Modifier.width(12.dp))
                Text(s.title, style = t.skipped, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            }
        }
    }
}

private fun reasonIcon(reason: String): ImageVector = when (reason) {
    "whatsapp" -> Icons.Rounded.Chat
    "dashboard" -> Icons.Rounded.Dashboard
    "solitaire" -> Icons.Rounded.Style
    else -> Icons.Rounded.Style
}

// ── the one action ───────────────────────────────────────────────────────────

@Composable
private fun BottomCta(
    total: Int,
    progress: Int?,
    allOpened: Boolean,
    t: TextStyles,
    bottomInset: androidx.compose.ui.unit.Dp,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val frac by animateFloatAsState(if (progress == null) 0f else progress / total.toFloat(), tween(250))
    Box(
        modifier.fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, C.bg.copy(alpha = .92f), C.bg)))
            .padding(start = 16.dp, end = 16.dp, top = 28.dp, bottom = bottomInset + 14.dp),
    ) {
        Box(
            Modifier.fillMaxWidth().height(60.dp).clip(RoundedCornerShape(30.dp))
                .background(Brush.horizontalGradient(listOf(C.teal, C.tealDeep)))
                .clickable(enabled = progress == null, onClick = onClick)
                .testTag("open-all-tabs"),
            contentAlignment = Alignment.Center,
        ) {
            if (progress != null) {
                Box(Modifier.fillMaxHeight().fillMaxWidth(frac).align(Alignment.CenterStart).background(Color.White.copy(alpha = .28f)))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (allOpened && progress == null) Icons.Rounded.Check else Icons.Rounded.PhoneAndroid,
                    null, tint = C.onTeal, modifier = Modifier.size(21.dp),
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    when {
                        progress != null -> "Opening ${progress.coerceAtLeast(1).coerceAtMost(total)} of $total…"
                        allOpened -> "All $total open · open again"
                        total == 1 -> "Open it on this phone"
                        else -> "Open all $total on this phone"
                    },
                    style = t.cta,
                )
            }
        }
    }
}
