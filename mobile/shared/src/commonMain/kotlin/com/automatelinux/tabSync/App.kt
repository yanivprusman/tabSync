package com.automatelinux.tabSync

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Chat
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Collections
import androidx.compose.material.icons.rounded.Computer
import androidx.compose.material.icons.rounded.Dashboard
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Style
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.automatelinux.tabSync.ui.theme.AppTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import kotlinx.serialization.Serializable
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.math.roundToInt

// ── data ────────────────────────────────────────────────────────────────────

@Serializable
data class Tab(val title: String, val url: String, val host: String = "", val icon: String? = null)

@Serializable
data class Skipped(val title: String, val reason: String)

@Serializable
data class TabList(val tabs: List<Tab> = emptyList(), val skipped: List<Skipped> = emptyList())

@Serializable
data class SoundStatus(val onPhone: Boolean, val percent: Int, val muted: Boolean)

@Serializable
data class SentPhoto(val name: String)

@Serializable
data class UsbReset(val keyboard: Boolean, val mouse: Boolean)

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

/** What the platform supplies. Photos are identified by an opaque id. */
interface Platform {
    suspend fun loadTabs(): TabList
    fun open(url: String)
    /** Opens each URL in its own browser tab; [onOpened] gets the count so far. */
    suspend fun openAll(urls: List<String>, onOpened: (Int) -> Unit)
    fun decodeImage(bytes: ByteArray): ImageBitmap?

    suspend fun loadSound(): SoundStatus
    /** Moves the computer's sound; answers with where it ended up. */
    suspend fun setSound(onPhone: Boolean): SoundStatus
    /** The volume of the output in use, 0 to 100. Answers with the state it ended up in. */
    suspend fun setVolume(percent: Int): SoundStatus
    suspend fun setMuted(muted: Boolean): SoundStatus

    fun hasPhotoAccess(): Boolean
    suspend fun requestPhotoAccess(): Boolean
    fun openAppSettings()
    /** Newest first. */
    suspend fun recentPhotos(limit: Int): List<String>
    suspend fun thumbnail(photo: String): ImageBitmap?
    /** The system picker, for anything older than the strip. Empty if nothing was chosen. */
    suspend fun pickPhotos(): List<String>
    /** Copies them to the desktop's Screenshots folder, in order. */
    suspend fun sendPhotos(photos: List<String>, onProgress: (done: Int, total: Int) -> Unit): List<SentPhoto>

    /** Power-cycles the USB port behind the desktop's keyboard and mouse; answers with what came back. */
    suspend fun resetUsb(): UsbReset
}

// ── palette (the icon's navy + the travelling tab's teal). Teal only ever means state or action. ──

private object C {
    val bg = Color(0xFF0B1033)
    val surface = Color(0xFF1A2259)
    val line = Color(0xFF232C6B)
    val text = Color(0xFFEEF1FF)
    val muted = Color(0xFFA5AEE0)
    val teal = Color(0xFF5EE6D3)
    val tealDeep = Color(0xFF1FA6C4)
    val onTeal = Color(0xFF05222B)
    val error = Color(0xFFFF9A8F)
}

private sealed interface TabsState {
    data object Loading : TabsState
    data class Loaded(val list: TabList) : TabsState
    data class Failed(val kind: FailureKind, val message: String) : TabsState
}

private sealed interface Sound {
    data object Loading : Sound
    data class Known(val status: SoundStatus) : Sound
    data class Failed(val message: String) : Sound
}

private sealed interface PhotoSend {
    data object Idle : PhotoSend
    data class Sending(val done: Int, val total: Int) : PhotoSend
    data class Sent(val text: String) : PhotoSend
    data class Failed(val message: String) : PhotoSend
}

private sealed interface UsbFix {
    data object Idle : UsbFix
    data object Running : UsbFix
    data class Done(val result: UsbReset) : UsbFix
    data class Failed(val message: String) : UsbFix
}

private enum class Link { Checking, Connected, Offline }

/** Everything on screen follows the desktop by itself, only while the app is visible. */
private const val SOUND_POLL_MS = 3000L
private const val TABS_POLL_MS = 5000L
private const val PHOTO_STRIP_SIZE = 12

@Composable
fun App(platform: Platform, font: FontFamily = FontFamily.Default, onScreen: Boolean = true) {
    AppTheme {
        Box(Modifier.fillMaxSize().background(C.bg)) { Screen(platform, font, onScreen) }
    }
}

// State lives here and not in the sections: a LazyColumn disposes what scrolls away, and a
// selection must not vanish because the user scrolled down to the tabs.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Screen(platform: Platform, font: FontFamily, onScreen: Boolean) {
    val scope = rememberCoroutineScope()
    val t = remember(font) { TextStyles(font) }
    var link by remember { mutableStateOf(Link.Checking) }

    // ── tabs
    var tabs by remember { mutableStateOf<TabsState>(TabsState.Loading) }
    var refreshing by remember { mutableStateOf(false) }
    var openingAll by remember { mutableStateOf<Int?>(null) }
    var skippedOpen by remember { mutableStateOf(false) }
    val opened = remember { mutableStateMapOf<String, Boolean>() }

    suspend fun readTabs() {
        try {
            val list = platform.loadTabs()
            link = Link.Connected
            tabs = TabsState.Loaded(list)
        } catch (e: CancellationException) {
            throw e
        } catch (e: LoadFailure) {
            link = if (e.kind == FailureKind.Offline) Link.Offline else Link.Connected
            // A dropped connection does not close any tab: keep showing the list we have.
            if (e.kind != FailureKind.Offline || tabs !is TabsState.Loaded) {
                tabs = TabsState.Failed(e.kind, e.message ?: e.kind.name)
            }
        } catch (e: Exception) {
            tabs = TabsState.Failed(FailureKind.Desktop, e.message ?: e.toString())
        }
    }

    LaunchedEffect(onScreen) {
        if (!onScreen) return@LaunchedEffect
        while (true) {
            readTabs()
            delay(TABS_POLL_MS)
        }
    }

    // ── sound
    var sound by remember { mutableStateOf<Sound>(Sound.Loading) }
    var soundTarget by remember { mutableStateOf<Boolean?>(null) }
    var soundError by remember { mutableStateOf<String?>(null) }
    var volumeTarget by remember { mutableStateOf<Int?>(null) }
    var volumeSending by remember { mutableStateOf(false) }
    var muteTarget by remember { mutableStateOf<Boolean?>(null) }
    var soundGeneration by remember { mutableStateOf(0) } // a read older than a change is stale

    LaunchedEffect(onScreen) {
        if (!onScreen) return@LaunchedEffect
        while (true) {
            val generation = soundGeneration
            val next: Sound? = try {
                Sound.Known(platform.loadSound())
            } catch (e: CancellationException) {
                throw e
            } catch (e: LoadFailure) {
                // Offline is the status line's to say; the last known position stays visible.
                if (e.kind == FailureKind.Offline && sound is Sound.Known) null else Sound.Failed(e.message ?: e.kind.name)
            } catch (e: Exception) {
                Sound.Failed(e.message ?: e.toString())
            }
            val changing = soundTarget != null || volumeTarget != null || muteTarget != null
            if (next != null && !changing && generation == soundGeneration) sound = next
            delay(SOUND_POLL_MS)
        }
    }

    fun moveSound(onPhone: Boolean) {
        if (soundTarget != null) return
        soundTarget = onPhone
        soundError = null
        soundGeneration++
        scope.launch {
            try {
                sound = Sound.Known(platform.setSound(onPhone))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                soundError = e.message ?: e.toString()
            } finally {
                soundGeneration++
                soundTarget = null
            }
        }
    }

    // A drag produces far more values than the desktop can apply: one request at a time, and
    // each one carries wherever the finger is by then, so the sound follows without a backlog.
    fun changeVolume(percent: Int) {
        if (percent == volumeTarget) return
        volumeTarget = percent
        soundError = null
        soundGeneration++
        if (volumeSending) return
        volumeSending = true
        scope.launch {
            try {
                while (true) {
                    val sent = volumeTarget ?: break
                    val status = platform.setVolume(sent)
                    if (volumeTarget == sent) {
                        sound = Sound.Known(status)
                        break
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                soundError = e.message ?: e.toString()
            } finally {
                soundGeneration++
                volumeTarget = null
                volumeSending = false
            }
        }
    }

    fun changeMute(muted: Boolean) {
        if (muteTarget != null) return
        muteTarget = muted
        soundError = null
        soundGeneration++
        scope.launch {
            try {
                sound = Sound.Known(platform.setMuted(muted))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                soundError = e.message ?: e.toString()
            } finally {
                soundGeneration++
                muteTarget = null
            }
        }
    }

    // ── photos
    var photoAccess by remember { mutableStateOf(platform.hasPhotoAccess()) }
    var photoAccessRefused by remember { mutableStateOf(false) }
    var recent by remember { mutableStateOf<List<String>>(emptyList()) }
    val selected = remember { mutableStateListOf<String>() }
    val onDesktop = remember { mutableStateMapOf<String, Boolean>() }
    var photoSend by remember { mutableStateOf<PhotoSend>(PhotoSend.Idle) }

    suspend fun readRecent(): List<String> {
        val fresh = platform.recentPhotos(PHOTO_STRIP_SIZE)
        recent = fresh
        selected.retainAll(fresh)
        return fresh
    }

    // Coming back from the camera is the moment the strip is out of date.
    LaunchedEffect(onScreen) {
        if (!onScreen) return@LaunchedEffect
        photoAccess = platform.hasPhotoAccess()
        if (photoAccess) {
            photoAccessRefused = false
            try {
                readRecent()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                photoSend = PhotoSend.Failed(e.message ?: e.toString())
            }
        }
    }

    suspend fun send(photos: List<String>) {
        photoSend = PhotoSend.Sending(0, photos.size)
        val sent = platform.sendPhotos(photos) { done, total -> photoSend = PhotoSend.Sending(done, total) }
        photos.forEach { onDesktop[it] = true }
        selected.clear()
        photoSend = PhotoSend.Sent(if (sent.size == 1) "Sent ${sent[0].name} to Screenshots" else "Sent ${sent.size} photos to Screenshots")
    }

    fun photoAction(action: suspend () -> Unit) {
        scope.launch {
            try {
                action()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                photoSend = PhotoSend.Failed(e.message ?: e.toString())
            }
        }
    }

    suspend fun askForPhotos(): Boolean {
        photoAccess = platform.requestPhotoAccess()
        photoAccessRefused = !photoAccess
        return photoAccess
    }

    // ── more: keyboard and mouse
    var moreOpen by remember { mutableStateOf(false) }
    var usbFix by remember { mutableStateOf<UsbFix>(UsbFix.Idle) }

    fun fixUsb() {
        if (usbFix == UsbFix.Running) return
        usbFix = UsbFix.Running
        scope.launch {
            usbFix = try {
                UsbFix.Done(platform.resetUsb())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                UsbFix.Failed(e.message ?: e.toString())
            }
        }
    }

    val top =WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    PullToRefreshBox(
        isRefreshing = refreshing,
        onRefresh = {
            scope.launch {
                refreshing = true
                val started = Clock.System.now()
                readTabs()
                // A refresh that answers in 20 ms looks ignored.
                val spent = (Clock.System.now() - started).inWholeMilliseconds
                if (spent < 500) delay(500 - spent)
                refreshing = false
            }
        },
        modifier = Modifier.fillMaxSize(),
    ) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = top + 6.dp, bottom = bottom + 28.dp)) {
            item { TopBar(link, t) }
            when (val s = tabs) {
                is TabsState.Loading -> {
                    item { TabsHeading(null, null, t) {} }
                    items(3) { SkeletonRow() }
                }
                is TabsState.Failed -> {
                    item { TabsHeading(null, null, t) {} }
                    item { TabsProblem(s.kind, s.message, t) }
                }
                is TabsState.Loaded -> {
                    val list = s.list.tabs
                    item {
                        TabsHeading(list.size, openingAll?.let { "Opening $it of ${list.size}…" }, t) {
                            if (openingAll == null) {
                                openingAll = 0
                                scope.launch {
                                    try {
                                        platform.openAll(list.map { it.url }) { n ->
                                            openingAll = n
                                            opened[list[n - 1].url] = true
                                        }
                                    } finally {
                                        delay(400)
                                        openingAll = null
                                    }
                                }
                            }
                        }
                    }
                    if (list.isEmpty()) item { NoTabs(t) }
                    items(list, key = { it.url }) { tab ->
                        Column(Modifier.animateItem()) {
                            TabRow(tab, opened[tab.url] == true, platform, t) {
                                platform.open(tab.url)
                                opened[tab.url] = true
                            }
                        }
                    }
                }
            }
            item {
                SoundSection(sound, soundTarget, volumeTarget, muteTarget, link != Link.Offline, soundError, t, ::moveSound, ::changeVolume, ::changeMute)
            }
            item {
                PhotosSection(
                    platform = platform,
                    access = photoAccess,
                    accessRefused = photoAccessRefused,
                    recent = recent,
                    selected = selected,
                    onDesktop = onDesktop,
                    send = photoSend,
                    t = t,
                    onToggle = {
                        if (it in selected) selected.remove(it) else selected.add(it)
                        photoSend = PhotoSend.Idle // the last result says nothing about this selection
                    },
                    onAllow = {
                        if (photoAccessRefused) {
                            platform.openAppSettings()
                        } else {
                            photoAction { if (askForPhotos()) readRecent() }
                        }
                    },
                    onChoose = {
                        photoAction {
                            val chosen = platform.pickPhotos()
                            if (chosen.isNotEmpty()) send(chosen)
                        }
                    },
                    onSend = {
                        photoAction {
                            if (selected.isNotEmpty()) {
                                send(selected.toList())
                            } else {
                                if (!photoAccess && !askForPhotos()) {
                                    throw Exception("To send the latest photo, TabSync needs access to all photos.")
                                }
                                // Read again: a screenshot taken a second ago is the one that was meant.
                                val newest = readRecent().firstOrNull()
                                    ?: throw Exception("There are no photos in the phone's camera or screenshots folders.")
                                send(listOf(newest))
                            }
                        }
                    },
                )
            }
            // Last on the screen, whatever state the tabs are in: rarely needed, but the USB fix is
            // needed exactly when the desktop has no other way to be reached.
            item {
                MoreSection(
                    open = moreOpen,
                    fix = usbFix,
                    reachable = link != Link.Offline,
                    skipped = (tabs as? TabsState.Loaded)?.list?.skipped.orEmpty(),
                    skippedOpen = skippedOpen,
                    t = t,
                    onToggle = { moreOpen = !moreOpen },
                    onFix = ::fixUsb,
                    onToggleSkipped = { skippedOpen = !skippedOpen },
                )
            }
        }
    }
}

private class TextStyles(f: FontFamily) {
    val status = TextStyle(fontFamily = f, fontWeight = FontWeight.Medium, fontSize = 13.sp, color = C.muted)
    val h2 = TextStyle(fontFamily = f, fontWeight = FontWeight.Bold, fontSize = 19.sp, lineHeight = 25.sp, color = C.text)
    val count = TextStyle(fontFamily = f, fontWeight = FontWeight.Medium, fontSize = 19.sp, lineHeight = 25.sp, color = C.muted)
    val title = TextStyle(fontFamily = f, fontWeight = FontWeight.Medium, fontSize = 16.sp, lineHeight = 21.sp, color = C.text)
    val small = TextStyle(fontFamily = f, fontSize = 13.sp, lineHeight = 18.sp, color = C.muted)
    val body = TextStyle(fontFamily = f, fontSize = 15.sp, lineHeight = 21.sp, color = C.muted)
    val side = TextStyle(fontFamily = f, fontWeight = FontWeight.Medium, fontSize = 15.sp)
    val button = TextStyle(fontFamily = f, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = C.onTeal)
    val pill = TextStyle(fontFamily = f, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = C.teal)
    val mark = TextStyle(fontFamily = f, fontWeight = FontWeight.Bold, fontSize = 11.sp, color = Color.White)
}

private val SIDE = 20.dp
private val SECTION_GAP = 30.dp

// ── top: the app's mark, and whether anything below can be believed ───────────

@Composable
private fun TopBar(link: Link, t: TextStyles) {
    Row(Modifier.fillMaxWidth().padding(horizontal = SIDE, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        TransferGlyph(active = link == Link.Checking, modifier = Modifier.width(92.dp).height(28.dp))
        Spacer(Modifier.weight(1f))
        val tint by animateColorAsState(
            when (link) {
                Link.Checking -> C.muted
                Link.Connected -> C.teal
                Link.Offline -> C.error
            },
            tween(200),
        )
        Box(Modifier.size(8.dp).clip(CircleShape).background(tint))
        Spacer(Modifier.width(8.dp))
        Text(
            when (link) {
                Link.Checking -> "Looking for your desktop"
                Link.Connected -> "Desktop connected"
                Link.Offline -> "Can't reach the desktop"
            },
            style = t.status.copy(color = if (link == Link.Offline) C.error else C.muted),
        )
    }
}

// ── sound: two places it can be, and a knob that sits in one of them ──────────

@Composable
private fun SoundSection(
    sound: Sound,
    target: Boolean?,
    volumeTarget: Int?,
    muteTarget: Boolean?,
    reachable: Boolean,
    error: String?,
    t: TextStyles,
    onMove: (Boolean) -> Unit,
    onVolume: (Int) -> Unit,
    onMute: (Boolean) -> Unit,
) {
    val status = (sound as? Sound.Known)?.status
    val onPhone = target ?: status?.onPhone
    val enabled = reachable && target == null && sound is Sound.Known
    Column(Modifier.fillMaxWidth().padding(start = SIDE, end = SIDE, top = SECTION_GAP)) {
        Text("Computer sound plays on", style = t.h2)
        Spacer(Modifier.height(12.dp))
        BoxWithConstraints(
            Modifier.fillMaxWidth().height(56.dp).clip(RoundedCornerShape(28.dp)).background(C.surface).selectableGroup(),
        ) {
            val half = maxWidth / 2
            val knobX by animateDpAsState(if (onPhone == true) half else 0.dp, tween(220, easing = FastOutSlowInEasing))
            val knobAlpha by animateFloatAsState(if (target != null || !reachable) .55f else 1f, tween(150))
            if (onPhone != null) {
                Box(
                    Modifier.offset(x = knobX).width(half).fillMaxHeight().padding(4.dp).alpha(knobAlpha)
                        .clip(RoundedCornerShape(24.dp)).background(C.teal),
                )
            }
            Row(Modifier.fillMaxSize()) {
                SoundSide("Computer", Icons.Rounded.Computer, onPhone == false, enabled, "sound-on-computer", t, Modifier.weight(1f)) { onMove(false) }
                SoundSide("This phone", Icons.Rounded.PhoneAndroid, onPhone == true, enabled, "sound-on-phone", t, Modifier.weight(1f)) { onMove(true) }
            }
        }
        if (status != null) {
            Spacer(Modifier.height(6.dp))
            VolumeRow(
                percent = volumeTarget ?: status.percent,
                // Moving the slider unmutes, so it reads as unmuted from the first touch.
                muted = muteTarget ?: (status.muted && (volumeTarget ?: 0) == 0),
                enabled = enabled,
                t = t,
                onVolume = onVolume,
                onMute = onMute,
            )
        }
        val problem = error ?: (sound as? Sound.Failed)?.message
        if (problem != null) {
            Spacer(Modifier.height(8.dp))
            Text(problem, style = t.small.copy(color = C.error))
        }
    }
}

@Composable
private fun SoundSide(
    label: String,
    icon: ImageVector,
    selected: Boolean,
    enabled: Boolean,
    tag: String,
    t: TextStyles,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val tint by animateColorAsState(if (selected) C.onTeal else C.text, tween(200))
    Row(
        modifier.fillMaxHeight().clip(RoundedCornerShape(28.dp))
            .selectable(selected = selected, enabled = enabled && !selected, role = Role.RadioButton, onClick = onClick)
            .testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(19.dp))
        Spacer(Modifier.width(8.dp))
        Text(label, style = t.side.copy(color = tint))
    }
}

@Composable
private fun VolumeRow(
    percent: Int,
    muted: Boolean,
    enabled: Boolean,
    t: TextStyles,
    onVolume: (Int) -> Unit,
    onMute: (Boolean) -> Unit,
) {
    val active = if (muted) C.muted else C.teal
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(48.dp).offset(x = (-12).dp).clip(CircleShape)
                .clickable(enabled = enabled, role = Role.Switch) { onMute(!muted) }
                .testTag("sound-mute"),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (muted) Icons.AutoMirrored.Rounded.VolumeOff else Icons.AutoMirrored.Rounded.VolumeUp,
                if (muted) "Unmute the computer" else "Mute the computer",
                tint = if (muted) C.muted else C.text,
                modifier = Modifier.size(24.dp),
            )
        }
        Slider(
            // Something else may have pushed it past 100; the track ends there, the label stays true.
            value = percent.coerceIn(0, 100).toFloat(),
            onValueChange = { onVolume(it.roundToInt()) },
            valueRange = 0f..100f,
            enabled = enabled,
            colors = SliderDefaults.colors(
                thumbColor = active,
                activeTrackColor = active,
                inactiveTrackColor = C.surface,
                disabledThumbColor = active.copy(alpha = .55f),
                disabledActiveTrackColor = active.copy(alpha = .55f),
                disabledInactiveTrackColor = C.surface,
            ),
            modifier = Modifier.weight(1f).offset(x = (-8).dp).testTag("sound-volume"),
        )
        Text(
            if (muted) "Muted" else "$percent%",
            style = t.side.copy(color = if (muted) C.muted else C.text),
            textAlign = TextAlign.End,
            modifier = Modifier.width(52.dp),
        )
    }
}

// ── photos: see what is about to leave the phone ──────────────────────────────

@Composable
private fun PhotosSection(
    platform: Platform,
    access: Boolean,
    accessRefused: Boolean,
    recent: List<String>,
    selected: List<String>,
    onDesktop: Map<String, Boolean>,
    send: PhotoSend,
    t: TextStyles,
    onToggle: (String) -> Unit,
    onAllow: () -> Unit,
    onChoose: () -> Unit,
    onSend: () -> Unit,
) {
    val sending = send is PhotoSend.Sending
    Column(Modifier.fillMaxWidth().padding(top = SECTION_GAP)) {
        Text("Send photos to your desktop", style = t.h2, modifier = Modifier.padding(horizontal = SIDE))
        Spacer(Modifier.height(12.dp))
        if (access) {
            LazyRow(contentPadding = PaddingValues(horizontal = SIDE), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(recent, key = { it }) { photo ->
                    PhotoTile(
                        photo = photo,
                        platform = platform,
                        isSelected = photo in selected,
                        // With nothing selected the button sends this one, so the strip says so.
                        isLatestTarget = selected.isEmpty() && photo == recent.first(),
                        isOnDesktop = onDesktop[photo] == true,
                        enabled = !sending,
                        t = t,
                    ) { onToggle(photo) }
                }
                item { ChooseTile(!sending, t, onChoose) }
            }
        } else {
            Row(Modifier.padding(horizontal = SIDE), verticalAlignment = Alignment.CenterVertically) {
                ChooseTile(!sending, t, onChoose)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        if (accessRefused) "Turn on access to all photos in Settings to see recent ones here." else "See your recent photos here, ready to send.",
                        style = t.small,
                    )
                    Spacer(Modifier.height(6.dp))
                    Box(
                        Modifier.clip(RoundedCornerShape(50)).clickable(onClick = onAllow).testTag("allow-photos")
                            .padding(vertical = 6.dp),
                    ) { Text(if (accessRefused) "Open Settings" else "Allow access", style = t.pill) }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        val empty = access && recent.isEmpty()
        val canSend = !sending && !empty
        val fraction by animateFloatAsState(
            if (send is PhotoSend.Sending) (send.done + .5f) / send.total else 0f,
            tween(250),
        )
        Box(
            Modifier.padding(horizontal = SIDE).fillMaxWidth().height(52.dp)
                .alpha(if (empty) .5f else 1f)
                .clip(RoundedCornerShape(26.dp))
                .background(if (sending) C.tealDeep else C.teal)
                .clickable(enabled = canSend, onClick = onSend)
                .testTag("send-photos"),
            contentAlignment = Alignment.Center,
        ) {
            if (sending) {
                Box(Modifier.fillMaxHeight().fillMaxWidth(fraction).align(Alignment.CenterStart).background(C.teal))
            }
            Text(
                when {
                    send is PhotoSend.Sending && send.total > 1 -> "Sending ${send.done + 1} of ${send.total}…"
                    send is PhotoSend.Sending -> "Sending…"
                    empty -> "No photos yet"
                    selected.isEmpty() -> "Send latest photo"
                    selected.size == 1 -> "Send this photo"
                    else -> "Send ${selected.size} photos"
                },
                style = t.button,
            )
        }
        when (send) {
            is PhotoSend.Sent -> Text(send.text, style = t.small.copy(color = C.teal), modifier = Modifier.padding(start = SIDE, end = SIDE, top = 8.dp))
            is PhotoSend.Failed -> Text(send.message, style = t.small.copy(color = C.error), modifier = Modifier.padding(start = SIDE, end = SIDE, top = 8.dp))
            else -> {}
        }
    }
}

private val TILE = 96.dp

@Composable
private fun PhotoTile(
    photo: String,
    platform: Platform,
    isSelected: Boolean,
    isLatestTarget: Boolean,
    isOnDesktop: Boolean,
    enabled: Boolean,
    t: TextStyles,
    onClick: () -> Unit,
) {
    val bitmap by produceState<ImageBitmap?>(null, photo) {
        value = try {
            platform.thumbnail(photo)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null // a photo the phone can't preview can still be sent
        }
    }
    val ring by animateColorAsState(if (isSelected || isLatestTarget) C.teal else Color.Transparent, tween(150))
    Box(
        Modifier.size(TILE).clip(RoundedCornerShape(12.dp)).background(C.surface)
            .clickable(enabled = enabled, onClick = onClick).testTag("photo-tile"),
    ) {
        bitmap?.let { Image(it, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
        Box(Modifier.fillMaxSize().border(if (isSelected) 3.dp else 2.dp, ring, RoundedCornerShape(12.dp)))
        if (isSelected) {
            Box(
                Modifier.align(Alignment.TopEnd).padding(6.dp).size(24.dp).clip(CircleShape).background(C.teal),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Rounded.Check, "Selected", tint = C.onTeal, modifier = Modifier.size(17.dp)) }
        }
        if (isLatestTarget) {
            Box(
                Modifier.align(Alignment.BottomStart).padding(6.dp).clip(RoundedCornerShape(6.dp))
                    .background(Color.Black.copy(alpha = .62f)).padding(horizontal = 6.dp, vertical = 2.dp),
            ) { Text("Latest", style = t.mark) }
        }
        if (isOnDesktop) {
            Box(
                Modifier.align(Alignment.BottomEnd).padding(6.dp).size(24.dp).clip(CircleShape)
                    .background(Color.Black.copy(alpha = .62f)),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Rounded.Computer, "Sent to the desktop", tint = C.teal, modifier = Modifier.size(15.dp)) }
        }
    }
}

@Composable
private fun ChooseTile(enabled: Boolean, t: TextStyles, onClick: () -> Unit) {
    Column(
        Modifier.size(TILE).clip(RoundedCornerShape(12.dp)).background(C.surface)
            .clickable(enabled = enabled, onClick = onClick).testTag("choose-photos"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.Rounded.Collections, null, tint = C.text, modifier = Modifier.size(24.dp))
        Spacer(Modifier.height(6.dp))
        Text("All photos", style = t.small.copy(color = C.text))
    }
}

// ── tabs: a plain live list ───────────────────────────────────────────────────

@Composable
private fun TabsHeading(count: Int?, progress: String?, t: TextStyles, onOpenAll: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = SIDE, end = SIDE, top = 14.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Tabs on your desktop", style = t.h2)
        if (count != null && count > 0) {
            Spacer(Modifier.width(8.dp))
            Text("$count", style = t.count)
        }
        Spacer(Modifier.weight(1f))
        if (progress != null) {
            Text(progress, style = t.small)
        } else if (count != null && count > 1) {
            Box(
                Modifier.clip(RoundedCornerShape(50)).clickable(onClick = onOpenAll).testTag("open-all-tabs")
                    .padding(horizontal = 8.dp, vertical = 6.dp),
            ) { Text("Open all", style = t.pill) }
        }
    }
}

@Composable
private fun TabRow(tab: Tab, opened: Boolean, platform: Platform, t: TextStyles, onOpen: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onOpen).testTag("open-tab").padding(horizontal = SIDE, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Favicon(tab, platform, t)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(tab.title, style = t.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(2.dp))
            Text(tab.host, style = t.small, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (opened) {
            Spacer(Modifier.width(10.dp))
            Icon(Icons.Rounded.Check, "Opened on this phone", tint = C.teal, modifier = Modifier.size(20.dp))
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
        Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)).background(if (bitmap != null) Color(0xFFF4F6FF) else monogramColor(tab.host)),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            Image(bitmap, contentDescription = null, modifier = Modifier.size(24.dp))
        } else {
            val letter = tab.title.firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "•"
            Text(letter, style = t.h2.copy(fontSize = 18.sp, color = Color.White))
        }
    }
}

/** A stable, muted colour per site so tabs without a favicon are still told apart. */
private fun monogramColor(host: String): Color {
    val palette = listOf(0xFF3D4FB8, 0xFF2E7D8C, 0xFF7A4FB8, 0xFFB8644F, 0xFF4F8CB8, 0xFF8C6E2E)
    return Color(palette[(host.hashCode() and 0x7fffffff) % palette.size])
}

@Composable
private fun SkeletonRow() {
    val a by rememberInfiniteTransition().animateFloat(
        .35f, .7f, infiniteRepeatable(tween(800, easing = LinearEasing), RepeatMode.Reverse)
    )
    Row(Modifier.fillMaxWidth().padding(horizontal = SIDE, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)).background(C.surface.copy(alpha = a)))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Box(Modifier.fillMaxWidth(.75f).height(14.dp).clip(RoundedCornerShape(7.dp)).background(C.surface.copy(alpha = a)))
            Spacer(Modifier.height(8.dp))
            Box(Modifier.fillMaxWidth(.4f).height(11.dp).clip(RoundedCornerShape(6.dp)).background(C.surface.copy(alpha = a)))
        }
    }
}

/** No "try again" button: the screen is already trying again every few seconds. */
@Composable
private fun TabsProblem(kind: FailureKind, message: String, t: TextStyles) {
    val title = when (kind) {
        FailureKind.Offline -> "No connection to the desktop"
        FailureKind.ChromeClosed -> "Chrome is closed on the desktop"
        FailureKind.ChromeNoDebugging -> "Chrome on the desktop isn't sharing its tabs"
        FailureKind.Desktop -> "The desktop couldn't list its tabs"
    }
    val body = when (kind) {
        FailureKind.ChromeClosed -> "Open it there and its tabs will appear here."
        else -> message
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = SIDE, vertical = 8.dp)) {
        Text(title, style = t.title)
        Spacer(Modifier.height(4.dp))
        Text(body, style = t.body)
    }
}

@Composable
private fun NoTabs(t: TextStyles) {
    Text(
        "Nothing to bring over. Tabs you open on the desktop will appear here.",
        style = t.body,
        modifier = Modifier.padding(horizontal = SIDE, vertical = 8.dp),
    )
}

/** Why a tab is missing from the list has an answer, but not one worth a screenful. */
@Composable
private fun StayingBehind(skipped: List<Skipped>, open: Boolean, t: TextStyles, onToggle: () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onToggle).testTag("toggle-staying-tabs")
                .padding(horizontal = SIDE, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (skipped.size == 1) "1 tab stays on the desktop" else "${skipped.size} tabs stay on the desktop",
                style = t.body,
                modifier = Modifier.weight(1f),
            )
            Icon(if (open) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null, tint = C.muted, modifier = Modifier.size(22.dp))
        }
        if (open) {
            skipped.forEach { s ->
                Row(Modifier.fillMaxWidth().padding(horizontal = SIDE, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(reasonIcon(s.reason), null, tint = C.muted, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(12.dp))
                    Text(s.title, style = t.small, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

/** Collapsed at the very bottom: what is in here is for rare days, not for every visit. */
@Composable
private fun MoreSection(
    open: Boolean,
    fix: UsbFix,
    reachable: Boolean,
    skipped: List<Skipped>,
    skippedOpen: Boolean,
    t: TextStyles,
    onToggle: () -> Unit,
    onFix: () -> Unit,
    onToggleSkipped: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(top = SECTION_GAP)) {
        HorizontalDivider(Modifier.padding(horizontal = SIDE), color = C.line)
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onToggle).testTag("toggle-more")
                .padding(horizontal = SIDE, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("More", style = t.body, modifier = Modifier.weight(1f))
            Icon(if (open) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null, tint = C.muted, modifier = Modifier.size(22.dp))
        }
        if (open) {
            UsbFixRow(fix, reachable, t, onFix)
            if (skipped.isNotEmpty()) StayingBehind(skipped, skippedOpen, t, onToggleSkipped)
        }
    }
}

/** It acts on the first tap, so it says what it costs before the tap. */
@Composable
private fun UsbFixRow(fix: UsbFix, reachable: Boolean, t: TextStyles, onFix: () -> Unit) {
    val running = fix == UsbFix.Running
    Column(Modifier.fillMaxWidth()) {
        Column(
            Modifier.fillMaxWidth().alpha(if (reachable) 1f else .5f)
                .clickable(enabled = reachable && !running, role = Role.Button, onClick = onFix)
                .testTag("fix-keyboard-mouse")
                .padding(horizontal = SIDE, vertical = 10.dp),
        ) {
            Text(if (running) "Reconnecting keyboard and mouse…" else "Fix desktop keyboard and mouse", style = t.title)
            Spacer(Modifier.height(2.dp))
            Text("Resets their USB hub. They drop for about 10 seconds.", style = t.small)
        }
        val message: Pair<String, Color>? = when (fix) {
            is UsbFix.Done -> when {
                fix.result.keyboard && fix.result.mouse -> "Keyboard and mouse are back." to C.teal
                fix.result.keyboard -> "The keyboard is back, but the mouse receiver didn't return." to C.error
                fix.result.mouse -> "The mouse is back, but the keyboard didn't return." to C.error
                else -> "Neither came back. Is the USB hub plugged in?" to C.error
            }
            is UsbFix.Failed -> fix.message to C.error
            else -> null
        }
        if (message != null) {
            Text(message.first, style = t.small.copy(color = message.second), modifier = Modifier.padding(horizontal = SIDE))
        }
    }
}

private fun reasonIcon(reason: String): ImageVector = when (reason) {
    "whatsapp" -> Icons.AutoMirrored.Rounded.Chat
    "dashboard" -> Icons.Rounded.Dashboard
    else -> Icons.Rounded.Style
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
        drawRoundRect(C.surface, Offset(3 * u, 8 * u), Size(34 * u, 17 * u), CornerRadius(1.5f * u))
        drawRoundRect(C.teal, Offset(15 * u, 4.5f * u), Size(9 * u, 3.5f * u), CornerRadius(1.2f * u))
        drawRect(Color(0xFFC9D4F5), Offset(17 * u, 28 * u), Size(6 * u, 3 * u))
        drawRoundRect(Color(0xFFC9D4F5), Offset(12 * u, 31 * u), Size(16 * u, 2.5f * u), CornerRadius(1.2f * u))
        // dashes
        val x0 = 46 * u
        val x1 = size.width - 26 * u
        drawLine(
            C.muted, Offset(x0, 16 * u), Offset(x1, 16 * u), strokeWidth = 2 * u, cap = StrokeCap.Round,
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

