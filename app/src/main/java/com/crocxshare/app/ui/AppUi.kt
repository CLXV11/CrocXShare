package com.crocxshare.app.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.core.LinearEasing
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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.CallReceived
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.crocxshare.app.AppContainer
import com.crocxshare.app.R
import com.crocxshare.app.core.protocol.Checksums
import com.crocxshare.app.core.security.Pairing
import com.crocxshare.app.core.security.PairingCode
import com.crocxshare.app.data.HistoryStore
import com.crocxshare.app.discovery.DeviceState
import com.crocxshare.app.discovery.DiscoveredDevice
import com.crocxshare.app.discovery.DiscoveryMethod
import com.crocxshare.app.discovery.DiscoveryPermissions
import com.crocxshare.app.transfer.FileSourceCompat
import com.crocxshare.app.transfer.TransferDirection
import com.crocxshare.app.transfer.TransferStatus
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val REPO_URL = "https://github.com/CLXV11/CrocXShare"

private enum class Screen { HOME, SEND, RECEIVE, TRANSFER, HISTORY, SETTINGS, BENCH, ABOUT, MEDIA, APPS, TEXT, WFD }

@Composable
private fun str(id: Int, vararg args: Any): String = stringResource(id, *args)

private fun openUrl(context: android.content.Context, url: String) {
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
}

@Composable
private fun DeviceAvatar(avatarPath: String?, size: Int = 56) {
    val bmp = remember(avatarPath) {
        avatarPath?.let {
            try { android.graphics.BitmapFactory.decodeFile(it)?.asImageBitmap() } catch (e: Exception) { null }
        }
    }
    val mod = Modifier.size(size.dp).clip(CircleShape)
        .border(2.dp, MaterialTheme.colorScheme.primary, CircleShape)
    if (bmp != null) {
        Image(bitmap = bmp, contentDescription = null, modifier = mod)
    } else {
        Image(painter = painterResource(R.drawable.ic_launcher_foreground),
            contentDescription = null, modifier = mod)
    }
}

private fun shareApp(context: android.content.Context) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, context.getString(R.string.share_app_text) + "\n" + REPO_URL)
    }
    runCatching { context.startActivity(Intent.createChooser(send, null)) }
}


/** Keep the display awake while composed (Compose 1.6 compatible). */
@Composable
private fun Modifier.keepScreenOnCompat(): Modifier {
    val view = LocalView.current
    DisposableEffect(Unit) {
        val window = (view.context as? android.app.Activity)?.window
        window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
    return this
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalAnimationApi::class)
@Composable
fun AppRoot(container: AppContainer, onPickFiles: () -> Unit, onRecreate: () -> Unit, onPickFolder: () -> Unit, onPickFolderSend: () -> Unit = {}) {
    var screen by remember { mutableStateOf(Screen.HOME) }
    var mediaType by remember { mutableStateOf("image") }
    var pendingSharedText by remember { mutableStateOf<String?>(null) }
    var showOnboarding by remember { mutableStateOf(!container.settings.onboardingDone) }
    val state by container.transfer.state.collectAsStateWithLifecycle()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        when (container.initialScreen) {
            "SEND" -> screen = Screen.SEND
            "RECEIVE" -> screen = Screen.RECEIVE
            "TEXT" -> screen = Screen.TEXT
        }
        container.initialScreen = null
        val shared = container.shareText
        if (shared != null) {
            container.shareText = null
            pendingSharedText = shared
            screen = Screen.TEXT
        }
    }

    LaunchedEffect(state.active, state.status) {
        if (state.active || state.status in setOf(
                TransferStatus.COMPLETED, TransferStatus.FAILED, TransferStatus.CANCELLED)) {
            screen = Screen.TRANSFER
        }
    }

        fun navigate(s: Screen) {
        screen = s
        scope.launch { drawerState.close() }
    }
    textSendNavigator = { navigate(Screen.SEND) }

    if (showOnboarding) {
        OnboardingScreen(onDone = {
            container.settings.onboardingDone = true
            showOnboarding = false
        })
    } else ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.padding(bottom = 12.dp)) {
                        DeviceAvatar(container.settings.avatarPath, 44)
                        Text(container.settings.deviceName,
                            style = MaterialTheme.typography.titleMedium)
                    }
                    DrawerItem(str(R.string.menu_home), Icons.Default.Home, screen == Screen.HOME) { navigate(Screen.HOME) }
                    DrawerItem(str(R.string.send), Icons.AutoMirrored.Filled.Send, screen == Screen.SEND) { navigate(Screen.SEND) }
                    DrawerItem(str(R.string.receive), Icons.Default.KeyboardArrowDown, screen == Screen.RECEIVE) { navigate(Screen.RECEIVE) }
                    DrawerItem(str(R.string.menu_text), Icons.AutoMirrored.Filled.Send, screen == Screen.TEXT) { navigate(Screen.TEXT) }
                    DrawerItem(str(R.string.wfd_title), Icons.Default.Share, screen == Screen.WFD) { navigate(Screen.WFD) }
                    DrawerItem(str(R.string.history), Icons.Default.List, screen == Screen.HISTORY) { navigate(Screen.HISTORY) }
                    DrawerItem(str(R.string.benchmark), Icons.Default.Speed, screen == Screen.BENCH) { navigate(Screen.BENCH) }
                    DrawerItem(str(R.string.settings), Icons.Default.Settings, screen == Screen.SETTINGS) { navigate(Screen.SETTINGS) }
                    DrawerItem(str(R.string.about), Icons.Default.Info, screen == Screen.ABOUT) { navigate(Screen.ABOUT) }
                }
            }
        }
    ) {
        Scaffold(topBar = {
            TopAppBar(
                title = {
                    Text(str(when (screen) {
                        Screen.HOME -> R.string.app_name
                        Screen.SEND -> R.string.send
                        Screen.RECEIVE -> R.string.receive
                        Screen.TRANSFER -> R.string.transfer
                        Screen.HISTORY -> R.string.history
                        Screen.SETTINGS -> R.string.settings
                        Screen.BENCH -> R.string.benchmark
                        Screen.ABOUT -> R.string.about
                        Screen.MEDIA -> R.string.pick_files
                        Screen.APPS -> R.string.filter_apps
                        Screen.TEXT -> R.string.menu_text
                        Screen.WFD -> R.string.wfd_title
                    }))
                },
                navigationIcon = {
                    if (screen == Screen.HOME) {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(Icons.Default.Menu, contentDescription = "Menu")
                        }
                    } else {
                        IconButton(onClick = { screen = Screen.HOME }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                })
        }, bottomBar = {
            if (screen in setOf(Screen.HOME, Screen.SEND, Screen.RECEIVE, Screen.HISTORY)) {
                NavigationBar {
                    NavigationBarItem(
                        selected = screen == Screen.HOME,
                        onClick = { navigate(Screen.HOME) },
                        icon = { Icon(Icons.Default.Home, contentDescription = null) },
                        label = { Text(str(R.string.menu_home)) })
                    NavigationBarItem(
                        selected = screen == Screen.SEND,
                        onClick = { navigate(Screen.SEND) },
                        icon = { Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null) },
                        label = { Text(str(R.string.send)) })
                    NavigationBarItem(
                        selected = screen == Screen.RECEIVE,
                        onClick = { navigate(Screen.RECEIVE) },
                        icon = { Icon(Icons.Default.KeyboardArrowDown, contentDescription = null) },
                        label = { Text(str(R.string.receive)) })
                    NavigationBarItem(
                        selected = screen == Screen.HISTORY,
                        onClick = { navigate(Screen.HISTORY) },
                        icon = { Icon(Icons.Default.List, contentDescription = null) },
                        label = { Text(str(R.string.history)) })
                }
            }
        }) { pad ->
            AnimatedContent(
                targetState = screen,
                transitionSpec = {
                    (fadeIn() + slideInHorizontally { it / 5 }) togetherWith
                        (fadeOut() + slideOutHorizontally { -it / 5 })
                },
                label = "screen"
            ) { s ->
                Box(Modifier.padding(pad).fillMaxSize()) {
                    when (s) {
                        Screen.HOME -> HomeScreen(container,
                            onSend = { navigate(Screen.SEND) },
                            onReceive = { navigate(Screen.RECEIVE) })
                        Screen.SEND -> SendScreen(container, onPickFiles, onPickFolderSend, navigateApps = { navigate(Screen.APPS) }, onPickMedia = { t ->
                            mediaType = t
                            navigate(Screen.MEDIA)
                        })
                        Screen.RECEIVE -> ReceiveScreen(container)
                        Screen.TRANSFER -> TransferScreen(container)
                        Screen.HISTORY -> HistoryScreen(container)
                        Screen.SETTINGS -> SettingsScreen(container, onPickFolder, onRecreate)
                        Screen.BENCH -> BenchScreen(container)
                        Screen.MEDIA -> MediaPickerScreen(container, mediaType, onPicked = { uris ->
                            container.pendingSourcesFlow.value = uris
                            screen = Screen.SEND
                        })
                        Screen.APPS -> AppsScreen(container, onPicked = { dirs ->
                            container.pendingFs.value = dirs
                            screen = Screen.SEND
                        })
                        Screen.TEXT -> TextSendScreen(container, initialText = pendingSharedText, onConsumed = { pendingSharedText = null })
                        Screen.WFD -> WifiDirectScreen(container)
                        Screen.ABOUT -> AboutScreen(container)
                    }
                }
            }
        }
    }
}


@Composable
private fun OnboardingScreen(onDone: () -> Unit) {
    var page by remember { mutableStateOf(0) }
    val titles = listOf(R.string.ob1_title, R.string.ob2_title, R.string.ob3_title)
    val bodies = listOf(R.string.ob1_body, R.string.ob2_body, R.string.ob3_body)
    Column(Modifier.fillMaxSize().padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween) {
        TextButton(onClick = onDone, modifier = Modifier.align(Alignment.Start)) {
            Text(str(R.string.onboarding_skip))
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Image(painter = painterResource(R.drawable.ic_launcher_foreground),
                contentDescription = null, modifier = Modifier.size(120.dp))
            Text(str(titles[page]), style = MaterialTheme.typography.headlineSmall)
            Text(str(bodies[page]), style = MaterialTheme.typography.bodyMedium)
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                repeat(3) { i ->
                    Box(Modifier.size(8.dp).clip(CircleShape)
                        .background(if (i == page) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.surfaceVariant))
                }
            }
            Button(onClick = {
                if (page < 2) page++ else onDone()
            }, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                Text(str(if (page < 2) R.string.onboarding_next else R.string.onboarding_done))
            }
        }
    }
}

@Composable
private fun DrawerItem(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector,
                       selected: Boolean, onClick: () -> Unit) {
    NavigationDrawerItem(
        label = { Text(label) },
        icon = { Icon(icon, contentDescription = null) },
        selected = selected,
        onClick = onClick)
}


/** Button that subtly scales down while pressed — premium tactile feedback. */
@Composable
private fun PressScaleButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    outlined: Boolean = false,
    content: @Composable () -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.96f else 1f, label = "press")
    val m = modifier.scale(scale)
    if (outlined) {
        OutlinedButton(onClick = onClick, modifier = m, interactionSource = interaction,
            shape = MaterialTheme.shapes.large) { content() }
    } else {
        Button(onClick = onClick, modifier = m, interactionSource = interaction,
            shape = MaterialTheme.shapes.large) { content() }
    }
}

/** Animated aurora gradient used behind the home hero card. */
@Composable
private fun AuroraBackground(modifier: Modifier = Modifier) {
    val inf = rememberInfiniteTransition(label = "aurora")
    val t by inf.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(7000, easing = LinearEasing)),
        label = "t")
    val c1 = MaterialTheme.colorScheme.primary.copy(alpha = 0.85f)
    val c2 = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.55f)
    val c3 = MaterialTheme.colorScheme.primary.copy(alpha = 0.30f)
    Box(modifier.background(
        Brush.linearGradient(
            colors = listOf(c1, c2, c3),
            start = androidx.compose.ui.geometry.Offset(t * 900f, 0f),
            end = androidx.compose.ui.geometry.Offset(1400f - t * 900f, 700f))))
}

@Composable
private fun HomeScreen(c: AppContainer, onSend: () -> Unit, onReceive: () -> Unit) {
    val context = LocalContext.current
    val tState by c.transfer.state.collectAsStateWithLifecycle()
    val active = tState.active && tState.status == TransferStatus.TRANSFERRING
    val moved = remember { c.history.all().fold(0L) { a, r -> a + r.sizeBytes } }
    Column(Modifier.padding(20.dp).fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Card(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.extraLarge) {
            Box(Modifier.fillMaxWidth().height(200.dp)) {
                AuroraBackground(Modifier.fillMaxSize())
                Column(Modifier.fillMaxSize().padding(20.dp),
                    verticalArrangement = Arrangement.SpaceBetween) {
                    Row(Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically) {
                        DeviceAvatar(c.settings.avatarPath, 46)
                        TextButton(onClick = { shareApp(context) }) {
                            Icon(Icons.Default.Share, contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimary)
                        }
                    }
                    Column {
                        Text(c.settings.deviceName,
                            style = MaterialTheme.typography.headlineSmall,
                            color = MaterialTheme.colorScheme.onPrimary)
                        Text(
                            str(if (active) R.string.status_active else R.string.status_ready) +
                                "  •  " + HistoryStore.formatSize(moved),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.85f))
                    }
                }
            }
        }
        PressScaleButton(onClick = onSend, modifier = Modifier.fillMaxWidth().height(66.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null)
                Spacer(Modifier.width(12.dp))
                Column(horizontalAlignment = Alignment.Start) {
                    Text(str(R.string.send), style = MaterialTheme.typography.titleMedium)
                    Text(str(R.string.send_sub), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        PressScaleButton(onClick = onReceive, modifier = Modifier.fillMaxWidth().height(66.dp),
            outlined = true) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.KeyboardArrowDown, contentDescription = null)
                Spacer(Modifier.width(12.dp))
                Column(horizontalAlignment = Alignment.Start) {
                    Text(str(R.string.receive), style = MaterialTheme.typography.titleMedium)
                    Text(str(R.string.recv_sub), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(str(R.string.nearby), style = MaterialTheme.typography.titleMedium)
                Text(str(R.string.nearby_hint), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun SendScreen(c: AppContainer, onPickFiles: () -> Unit, onPickFolderSend: () -> Unit, onPickMedia: (String) -> Unit, navigateApps: () -> Unit) {
    val context = LocalContext.current
    val pickedUris by c.pendingSourcesFlow.collectAsStateWithLifecycle()
    val uris = remember { mutableStateOf(c.pendingSourcesFlow.value) }
    LaunchedEffect(pickedUris) { uris.value = pickedUris }
    var code by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    var canDiscover by remember { mutableStateOf(false) }
    var pendingDevice by remember { mutableStateOf<DiscoveredDevice?>(null) }
    var codeInput by remember { mutableStateOf("") }
    var mimeLabel by remember { mutableStateOf("filter_all") }
    var note by remember { mutableStateOf("") }
    val folderPicks by c.folderPickCount.collectAsStateWithLifecycle()

    val fsPaths by c.pendingFs.collectAsStateWithLifecycle()
    val sources = remember(uris.value, fsPaths) {
        uris.value.map { FileSourceCompat.query(context, it) } +
            fsPaths.map { com.crocxshare.app.transfer.FsFileSource(java.io.File(it)) }
    }
    val total = sources.sumOf { it.sizeBytes }
    val devices by c.nsd.devices.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        canDiscover = grants.values.all { it }
    }
    val qrLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        result.contents?.let { code = it; error = "" }
    }

    LaunchedEffect(Unit) { permLauncher.launch(DiscoveryPermissions.requiredForDiscovery()) }

    DisposableEffect(canDiscover) {
        if (canDiscover) c.nsd.startDiscovery()
        onDispose { c.nsd.stopDiscovery() }
    }

    fun launch(device: DiscoveredDevice, rawCode: String) {
        val clean = rawCode.trim()
        val full = Pairing.decodePayload(clean)
        val payload = if (full != null) {
            full
        } else {
            val token = PairingCode.tokenFor(clean)
            val dec = PairingCode.decode(clean)
            val port = device.port
            if (token == null || dec == null || port == null) {
                error = context.getString(R.string.bad_pairing)
                return
            }
            Pairing.Payload(
                version = 1, ip = device.address, port = port, token = token,
                certSha256 = Checksums.hex(dec.certFingerprintPrefix),
                sessionId = Pairing.newSessionId(),
                deviceName = device.name, deviceModel = device.model.ifEmpty { "?" },
                method = "LAN")
        }
        c.transfer.sendToDevice(payload, sources, "share")
    }

    LazyColumn(Modifier.padding(20.dp).fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            fun applyChip(key: String) {
                mimeLabel = key
                when (key) {
                    "filter_images" -> onPickMedia("image")
                    "filter_video" -> onPickMedia("video")
                    "filter_apps" -> navigateApps()
                    else -> {
                        c.pendingMime = when (key) {
                            "filter_audio" -> arrayOf("audio/*", "application/ogg", "application/x-flac")
                            "filter_docs" -> arrayOf(
                                "text/*", "application/pdf", "application/zip",
                                "application/msword", "application/vnd.ms-excel",
                                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
                            else -> arrayOf("*/*")
                        }
                        onPickFiles()
                    }
                }
            }
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("filter_all", "filter_images", "filter_video", "filter_audio", "filter_docs", "filter_apps")
                    .forEach { key ->
                        if (mimeLabel == key) {
                            Button(onClick = { applyChip(key) }) { Text(str(resFor(key))) }
                        } else {
                            OutlinedButton(onClick = { applyChip(key) }) { Text(str(resFor(key))) }
                        }
                    }
            }
        }
                item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = onPickFiles, modifier = Modifier.weight(1f)) {
                    Text(str(R.string.pick_files))
                }
                if (uris.value.isNotEmpty()) {
                    TextButton(onClick = { c.pendingSourcesFlow.value = emptyList(); uris.value = emptyList(); c.pendingFs.value = emptyList() }) {
                        Text(str(R.string.clear_selection))
                    }
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(
                    enabled = sources.isNotEmpty(),
                    onClick = {
                        scope.launch {
                            val out = withContext(Dispatchers.IO) {
                                com.crocxshare.app.transfer.ZipPackager.zipSources(
                                    sources,
                                    java.io.File(context.cacheDir, "crocxshare-send.zip"))
                            }
                            c.pendingSourcesFlow.value = emptyList()
                            uris.value = emptyList()
                            c.pendingFs.value = listOf(out.absolutePath)
                            note = context.getString(
                                R.string.zip_ready,
                                HistoryStore.formatSize(out.length()))
                        }
                    },
                    modifier = Modifier.weight(1f)) {
                    Text(str(R.string.action_zip))
                }
                OutlinedButton(onClick = onPickFolderSend, modifier = Modifier.weight(1f)) {
                    Text(str(R.string.action_folder))
                }
            }
        }
        item {
            LaunchedEffect(folderPicks) {
                if (folderPicks > 0) {
                    val tree = c.pendingFolderTreeUri
                    c.pendingFolderTreeUri = null
                    if (tree != null) {
                        runCatching {
                        val out = withContext(Dispatchers.IO) {
                            com.crocxshare.app.transfer.ZipPackager.zipFolder(
                                context, tree,
                                java.io.File(context.cacheDir, "crocxshare-folder.zip"))
                        }
                        c.pendingSourcesFlow.value = emptyList()
                        uris.value = emptyList()
                        c.pendingFs.value = listOf(
                            java.io.File(context.cacheDir, "crocxshare-folder.zip").absolutePath)
                        note = context.getString(
                            R.string.folder_zip_ready, out.first,
                            HistoryStore.formatSize(out.second))
                        }.onFailure { e -> note = e.message ?: e.javaClass.simpleName }
                    }
                }
            }
        }
        item {
            val totalText = if (sources.any { it.sizeBytes < 0 })
                str(R.string.unknown_size) else HistoryStore.formatSize(total)
            Text(str(R.string.selected_count, sources.size, totalText),
                style = MaterialTheme.typography.bodyMedium)
        }
        item {
            OutlinedButton(onClick = {
                qrLauncher.launch(ScanOptions().apply {
                    setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                    setPrompt(context.getString(R.string.scan_prompt))
                    setBeepEnabled(false)
                })
            }, modifier = Modifier.fillMaxWidth()) {
                Text(str(R.string.scan_qr))
            }
        }
        item {
            OutlinedTextField(
                value = code,
                onValueChange = { code = it; error = "" },
                label = { Text(str(R.string.pairing_code)) },
                modifier = Modifier.fillMaxWidth())
        }
        item {
            if (canDiscover) {
                Text(str(R.string.discovered_devices), style = MaterialTheme.typography.titleMedium)
                if (devices.isEmpty()) {
                    Text(str(R.string.no_devices_hint), style = MaterialTheme.typography.bodySmall)
                }
            } else {
                Text(str(R.string.permission_rationale), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error)
            }
        }
        items(devices, key = { it.id }) { dev ->
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth().padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically) {
                    Column {
                        Text(dev.name, style = MaterialTheme.typography.titleSmall)
                        Text("${dev.model}  •  ${dev.address}:${dev.port ?: "?"}",
                            style = MaterialTheme.typography.bodySmall)
                    }
                    when (dev.state) {
                        DeviceState.AVAILABLE -> Button(
                            enabled = sources.isNotEmpty(),
                            onClick = { pendingDevice = dev; codeInput = "" }) {
                            Text(str(R.string.connect))
                        }
                        DeviceState.CONNECTED -> Icon(Icons.Default.CheckCircle, contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary)
                        else -> Icon(Icons.Default.PlayArrow, contentDescription = null)
                    }
                }
            }
        }
        item {
            Button(
                enabled = sources.isNotEmpty() && code.isNotBlank(),
                onClick = {
                    launch(DiscoveredDevice("", "", "", DiscoveryMethod.LAN_NSD, "", null), code)
                },
                modifier = Modifier.fillMaxWidth()) {
                Text(str(R.string.send))
            }
        }
        item {
            if (error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error)
            if (note.isNotEmpty()) Text(note, color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.bodySmall)
        }
    }

    pendingDevice?.let { dev ->
        AlertDialog(
            onDismissRequest = { pendingDevice = null },
            title = { Text(str(R.string.pair_with, dev.name)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(str(R.string.enter_code_hint))
                    OutlinedTextField(
                        value = codeInput,
                        onValueChange = { codeInput = it },
                        label = { Text(str(R.string.short_code_label)) })
                }
            },
            confirmButton = {
                TextButton(enabled = codeInput.isNotBlank(), onClick = {
                    val d = dev
                    pendingDevice = null
                    launch(d, codeInput)
                }) { Text(str(R.string.connect)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDevice = null }) { Text(str(R.string.cancel)) }
            })
    }
}

private fun resFor(key: String): Int = when (key) {
    "filter_images" -> R.string.filter_images
    "filter_video" -> R.string.filter_video
    "filter_audio" -> R.string.filter_audio
    "filter_docs" -> R.string.filter_docs
    "filter_apps" -> R.string.filter_apps
    else -> R.string.filter_all
}

@Composable
private fun ReceiveScreen(c: AppContainer) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var payload by remember { mutableStateOf<Pairing.Payload?>(null) }
    var copied by remember { mutableStateOf(false) }
    var webUrl by remember { mutableStateOf<String?>(null) }
    var webNote by remember { mutableStateOf("") }
    var web by remember { mutableStateOf<com.crocxshare.app.transfer.WebReceiver?>(null) }
    val scope = rememberCoroutineScope()

    Column(Modifier.padding(20.dp).fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Button(
            onClick = { payload = c.transfer.startReceiving(); copied = false },
            modifier = Modifier.fillMaxWidth()) {
            Text(str(R.string.start_receiving))
        }
        payload?.let { p ->
            val link = remember(p) { Pairing.encodePayload(p) }
            val shortCode = remember(p) { PairingCode.encode(p.token, p.certSha256) }
            Card(Modifier.fillMaxWidth()) {
                Column(
                    Modifier.fillMaxWidth().padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("${p.deviceName}  •  ${p.ip}:${p.port}",
                        style = MaterialTheme.typography.bodyMedium)
                    QrBitmap.encode(link)?.let { img ->
                        Image(bitmap = img, contentDescription = "QR",
                            modifier = Modifier.fillMaxWidth().height(240.dp))
                    }
                    Text(str(R.string.short_code_label), style = MaterialTheme.typography.bodySmall)
                    Text(shortCode, style = MaterialTheme.typography.headlineSmall)
                    Text(str(R.string.token_label, p.token), style = MaterialTheme.typography.bodySmall)
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = {
                    clipboard.setText(AnnotatedString(link))
                    copied = true
                }, modifier = Modifier.weight(1f)) {
                    Text(str(if (copied) R.string.copied else R.string.copy_link))
                }
                OutlinedButton(onClick = {
                    clipboard.setText(AnnotatedString(shortCode))
                    copied = true
                }, modifier = Modifier.weight(1f)) {
                    Text(str(R.string.copy_code))
                }
            }
            OutlinedButton(onClick = { shareApp(context) }, modifier = Modifier.fillMaxWidth()) {
                Text(str(R.string.share))
            }
            OutlinedButton(
                onClick = { c.transfer.stopReceiving(); payload = null },
                modifier = Modifier.fillMaxWidth()) {
                Text(str(R.string.stop))
            }
        }
        OutlinedButton(onClick = {
            val dir = c.settings.receiveDirUri
            if (dir == null) {
                webNote = context.getString(R.string.web_need_dir)
            } else {
                scope.launch {
                    val token = Pairing.newToken()
                    val receiver = withContext(Dispatchers.IO) {
                        com.crocxshare.app.transfer.WebReceiver(
                            context, Uri.parse(dir), token,
                            c.settings.deviceName, c.appScope)
                    }
                    val port = withContext(Dispatchers.IO) { receiver.start() }
                    web = receiver
                    val ip = com.crocxshare.app.transfer.NetworkInfo.getLocalIpAddress() ?: "127.0.0.1"
                    webUrl = "http://" + ip + ":" + port + "/" + token + "/"
                    webNote = ""
                }
            }
        }, modifier = Modifier.fillMaxWidth()) {
            Text(str(R.string.web_receiver))
        }
        webUrl?.let { url ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(str(R.string.web_started, url), style = MaterialTheme.typography.bodySmall)
                    QrBitmap.encode(url)?.let { img ->
                        Image(bitmap = img, contentDescription = "QR",
                            modifier = Modifier.fillMaxWidth().height(180.dp))
                    }
                    OutlinedButton(onClick = {
                        web?.close()
                        web = null
                        webUrl = null
                    }, modifier = Modifier.fillMaxWidth()) {
                        Text(str(R.string.web_stop))
                    }
                }
            }
        }
        if (webNote.isNotEmpty()) {
            Text(webNote, color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun TransferScreen(c: AppContainer) {
    val s by c.transfer.state.collectAsStateWithLifecycle()
    // Keep the screen awake while a transfer is active so progress never pauses.
    val progress by animateFloatAsState(
        targetValue = if (s.totalBytes > 0)
            (s.transferredBytes.toFloat() / s.totalBytes).coerceIn(0f, 1f) else 0f,
        label = "progress")
    Column(Modifier.padding(20.dp).fillMaxSize().keepScreenOnCompat(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(s.peerName, style = MaterialTheme.typography.titleMedium)
        if (s.message.isNotEmpty()) Text(s.message, style = MaterialTheme.typography.bodySmall)

        if (s.files.isNotEmpty()) {
            Text(str(R.string.transfer_files), style = MaterialTheme.typography.titleSmall)
            s.files.take(6).forEach { f ->
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(f.name, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                    val fp by animateFloatAsState(
                        targetValue = if (f.sizeBytes > 0)
                            (f.transferredBytes.toFloat() / f.sizeBytes).coerceIn(0f, 1f) else 0f,
                        label = "fp")
                    LinearProgressIndicator(
                        progress = { fp },
                        color = when (f.status) {
                            TransferStatus.COMPLETED -> MaterialTheme.colorScheme.primary
                            TransferStatus.FAILED -> MaterialTheme.colorScheme.error
                            else -> MaterialTheme.colorScheme.tertiary
                        },
                        modifier = Modifier.fillMaxWidth().height(4.dp))
                }
            }
        }
        val speedHist by c.transfer.speedHistory.collectAsStateWithLifecycle()
        if (speedHist.size >= 2) {
            val barColor = MaterialTheme.colorScheme.tertiary
            Canvas(Modifier.fillMaxWidth().height(48.dp)) {
                val maxV = (speedHist.maxOrNull() ?: 1.0).coerceAtLeast(1.0)
                val bw = size.width / 40f
                speedHist.forEachIndexed { i, v ->
                    val h = ((v / maxV) * size.height).toFloat().coerceAtLeast(2f)
                    drawRect(
                        color = barColor,
                        topLeft = Offset(i * bw, size.height - h),
                        size = Size(bw * 0.7f, h))
                }
            }
        }
        if (s.totalBytes > 0) {
            LinearProgressIndicator(
                progress = { progress },
                color = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.fillMaxWidth())
            Text(str(
                R.string.progress_line,
                HistoryStore.formatSize(s.transferredBytes),
                HistoryStore.formatSize(s.totalBytes),
                HistoryStore.formatSpeed(s.currentSpeedBps),
                HistoryStore.formatSpeed(s.avgSpeedBps),
                if (s.etaMs >= 0) HistoryStore.formatTime(s.etaMs) else "—"),
                style = MaterialTheme.typography.bodyMedium)
            if (s.startedAtMs > 0 && (s.active || s.status == TransferStatus.COMPLETED)) {
                Text(
                    str(R.string.method_label) + ": " + s.method.name + "   " +
                        str(R.string.elapsed_label) + ": " +
                        HistoryStore.formatTime(
                            (System.currentTimeMillis() - s.startedAtMs).coerceAtLeast(0)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        s.incomingManifest?.let { m ->
            if (s.status == TransferStatus.WAITING_ACCEPT) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(str(R.string.incoming_from, s.incomingFrom),
                            style = MaterialTheme.typography.titleMedium)
                        Text(str(R.string.incoming_size, m.fileCount, HistoryStore.formatSize(m.totalBytes)))
                        m.fileNames.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Button(onClick = { c.transfer.approveIncoming(true) }) {
                                Text(str(R.string.accept))
                            }
                            OutlinedButton(onClick = { c.transfer.approveIncoming(false) }) {
                                Text(str(R.string.reject))
                            }
                        }
                    }
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (s.status == TransferStatus.TRANSFERRING) {
                OutlinedButton(onClick = { c.transfer.pause() }) { Text(str(R.string.pause)) }
            }
            if (s.status == TransferStatus.PAUSED) {
                Button(onClick = { c.transfer.resumeTransfer() }) { Text(str(R.string.resume)) }
            }
            if (s.active || s.status == TransferStatus.PAUSED) {
                TextButton(onClick = { c.transfer.cancel() }) { Text(str(R.string.cancel)) }
            }
        }
        if (s.direction == TransferDirection.RECEIVE && s.status == TransferStatus.COMPLETED
            && c.settings.receiveDirUri != null) {
            val ctx = LocalContext.current
            OutlinedButton(onClick = {
                runCatching {
                    ctx.startActivity(Intent(Intent.ACTION_VIEW,
                        Uri.parse(c.settings.receiveDirUri)))
                }
            }, modifier = Modifier.fillMaxWidth()) {
                Text(str(R.string.open_folder))
            }
        }
        if (s.status == TransferStatus.FAILED && s.direction == TransferDirection.SEND) {
            TextButton(onClick = { c.transfer.resumeLastSend() }) { Text(str(R.string.retry_resume)) }
            Text(str(R.string.resume_note), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun HistoryScreen(c: AppContainer) {
    var records by remember { mutableStateOf<List<HistoryStore.TransferRecord>>(emptyList()) }
    LaunchedEffect(Unit) {
        records = withContext(Dispatchers.IO) { c.history.all() }
    }
    val dateFmt = remember { SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()) }
    val sentN = records.count { it.direction == "SEND" }
    val recvN = records.size - sentN
    val totalBytes = records.fold(0L) { a, r -> a + r.sizeBytes }
    val best = records.maxByOrNull { it.avgSpeedBps }?.avgSpeedBps ?: 0.0
    Column(Modifier.padding(16.dp).fillMaxSize()) {
        Card(Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(str(R.string.stats_title), style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary)
                Text(str(R.string.stats_summary, sentN, recvN,
                    HistoryStore.formatSize(totalBytes), HistoryStore.formatSpeed(best)),
                    style = MaterialTheme.typography.bodySmall)
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = {
                c.history.clear()
                records = emptyList()
            }) { Text(str(R.string.clear)) }
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(records, key = { it.id }) { r ->
                val isSend = r.direction == "SEND"
                val resultColor = when (r.result) {
                    "SUCCESS" -> MaterialTheme.colorScheme.primary
                    "FAILED" -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                }
                Card(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            if (isSend) Icons.AutoMirrored.Filled.Send else Icons.Default.CallReceived,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary)
                        Column(Modifier.weight(1f)) {
                            Text(r.fileName, style = MaterialTheme.typography.titleSmall)
                            Text(
                                str(if (isSend) R.string.sent_to else R.string.received_from_badge, r.peerName),
                                style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "${HistoryStore.formatSize(r.sizeBytes)}  •  " +
                                    HistoryStore.formatSpeed(r.avgSpeedBps) + "  •  " +
                                    dateFmt.format(Date(r.timestamp)),
                                style = MaterialTheme.typography.bodySmall)
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text(r.result, style = MaterialTheme.typography.labelMedium,
                                color = resultColor)
                            TextButton(onClick = {
                                c.history.remove(r.id)
                                records = records.filterNot { it.id == r.id }
                            }) { Text(str(R.string.delete)) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsScreen(c: AppContainer, onPickFolder: () -> Unit, onRecreate: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf(c.settings.deviceName) }
    var avatarPath by remember { mutableStateOf(c.settings.avatarPath) }
    var boost by remember { mutableStateOf(c.settings.boostMode) }
    var themeMode by remember { mutableStateOf(c.settings.theme) }
    var themeColor by remember { mutableStateOf(c.settings.themeColor) }
    var language by remember { mutableStateOf(c.settings.language) }
    var showThemeDialog by remember { mutableStateOf(false) }
    var showColorDialog by remember { mutableStateOf(false) }
    var showLanguageDialog by remember { mutableStateOf(false) }

    val avatarPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()) { uri ->
        uri?.let { picked ->
            scope.launch {
                withContext(Dispatchers.IO) {
                    runCatching {
                        val dest = java.io.File(context.filesDir, "avatar.jpg")
                        context.contentResolver.openInputStream(picked)?.use { input ->
                            dest.outputStream().use { output -> input.copyTo(output) }
                        }
                        c.settings.avatarPath = dest.absolutePath
                    }
                }
                avatarPath = c.settings.avatarPath
            }
        }
    }

    Column(Modifier.padding(20.dp).fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(str(R.string.profile), style = MaterialTheme.typography.titleMedium)
        Card(Modifier.fillMaxWidth()) {
            Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                DeviceAvatar(avatarPath, 64)
                Column(Modifier.weight(1f)) {
                    Text(name, style = MaterialTheme.typography.titleSmall)
                    Text(str(R.string.avatar_hint), style = MaterialTheme.typography.bodySmall)
                }
                TextButton(onClick = { avatarPicker.launch("image/*") }) {
                    Text(str(R.string.change_avatar))
                }
            }
        }
        OutlinedTextField(
            value = name,
            onValueChange = { name = it; c.settings.deviceName = it },
            label = { Text(str(R.string.device_name)) },
            modifier = Modifier.fillMaxWidth())
        OutlinedButton(onClick = onPickFolder, modifier = Modifier.fillMaxWidth()) {
            Text(str(R.string.pick_receive_dir))
        }

        Text(str(R.string.settings_appearance), style = MaterialTheme.typography.titleMedium)
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(str(R.string.settings_theme_theme))
                    TextButton(onClick = { showThemeDialog = true }) {
                        Text(str(when (themeMode) {
                            "LIGHT" -> R.string.theme_light
                            "DARK" -> R.string.theme_dark
                            else -> R.string.theme_system
                        }))
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(str(R.string.settings_color))
                    TextButton(onClick = { showColorDialog = true }) {
                        Text(str(colorRes(themeColor)))
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(str(R.string.settings_language))
                    TextButton(onClick = { showLanguageDialog = true }) {
                        Text(str(langRes(language)))
                    }
                }
            }
        }

        Text(str(R.string.settings_transfer), style = MaterialTheme.typography.titleMedium)
        Card(Modifier.fillMaxWidth()) {
            Row(Modifier.padding(14.dp), horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(str(R.string.boost_mode))
                    Text(str(R.string.boost_hint), style = MaterialTheme.typography.bodySmall)
                }
                Switch(checked = boost, onCheckedChange = {
                    boost = it; c.settings.boostMode = it
                })
            }
        }
        Text(str(R.string.approval_required), style = MaterialTheme.typography.bodySmall)
    }

    if (showThemeDialog) {
        PickerDialog(title = str(R.string.settings_theme_theme), options = listOf(
            Triple("SYSTEM", R.string.theme_system, { themeMode = "SYSTEM"; c.settings.theme = "SYSTEM"; onRecreate() }),
            Triple("LIGHT", R.string.theme_light, { themeMode = "LIGHT"; c.settings.theme = "LIGHT"; onRecreate() }),
            Triple("DARK", R.string.theme_dark, { themeMode = "DARK"; c.settings.theme = "DARK"; onRecreate() })
        ), onDismiss = { showThemeDialog = false })
    }
    if (showColorDialog) {
        PickerDialog(title = str(R.string.settings_color), options = listOf(
            Triple("GREEN", R.string.color_green, { themeColor = "GREEN"; c.settings.themeColor = "GREEN"; onRecreate() }),
            Triple("BLUE", R.string.color_blue, { themeColor = "BLUE"; c.settings.themeColor = "BLUE"; onRecreate() }),
            Triple("PURPLE", R.string.color_purple, { themeColor = "PURPLE"; c.settings.themeColor = "PURPLE"; onRecreate() }),
            Triple("ORANGE", R.string.color_orange, { themeColor = "ORANGE"; c.settings.themeColor = "ORANGE"; onRecreate() }),
            Triple("RED", R.string.color_red, { themeColor = "RED"; c.settings.themeColor = "RED"; onRecreate() })
        ), onDismiss = { showColorDialog = false })
    }
    if (showLanguageDialog) {
        PickerDialog(title = str(R.string.settings_language), options = listOf(
            Triple("SYSTEM", R.string.lang_system, { language = "SYSTEM"; c.settings.language = "SYSTEM"; onRecreate() }),
            Triple("EN", R.string.lang_en, { language = "EN"; c.settings.language = "EN"; onRecreate() }),
            Triple("AR", R.string.lang_ar, { language = "AR"; c.settings.language = "AR"; onRecreate() }),
            Triple("ES", R.string.lang_es, { language = "ES"; c.settings.language = "ES"; onRecreate() }),
            Triple("RU", R.string.lang_ru, { language = "RU"; c.settings.language = "RU"; onRecreate() })
        ), onDismiss = { showLanguageDialog = false })
    }
}

@Composable
private fun PickerDialog(title: String, options: List<Triple<String, Int, () -> Unit>>,
                         onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                options.forEach { (_, labelRes, action) ->
                    TextButton(onClick = { action(); onDismiss() },
                        modifier = Modifier.fillMaxWidth()) {
                        Text(str(labelRes))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(str(R.string.cancel)) } })
}

private fun colorRes(c: String): Int = when (c) {
    "BLUE" -> R.string.color_blue
    "PURPLE" -> R.string.color_purple
    "ORANGE" -> R.string.color_orange
    "RED" -> R.string.color_red
    else -> R.string.color_green
}

private fun langRes(l: String): Int = when (l) {
    "EN" -> R.string.lang_en
    "AR" -> R.string.lang_ar
    "ES" -> R.string.lang_es
    "RU" -> R.string.lang_ru
    else -> R.string.lang_system
}

@Composable
private fun AboutScreen(c: AppContainer) {
    val context = LocalContext.current
    val version = remember {
        try {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "1.0"
        } catch (e: Exception) { "1.0" }
    }
    Column(Modifier.padding(24.dp).fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Image(painter = painterResource(R.drawable.ic_launcher_foreground),
            contentDescription = null, modifier = Modifier.size(110.dp))
        Text(str(R.string.app_name), style = MaterialTheme.typography.headlineMedium)
        Text(str(R.string.version, version), style = MaterialTheme.typography.bodyMedium)
        Text(str(R.string.about_text), style = MaterialTheme.typography.bodySmall)
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(str(R.string.perm_title), style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary)
                Text(str(R.string.perm_location), style = MaterialTheme.typography.bodySmall)
                Text(str(R.string.perm_camera), style = MaterialTheme.typography.bodySmall)
                Text(str(R.string.perm_media), style = MaterialTheme.typography.bodySmall)
                Text(str(R.string.perm_apps), style = MaterialTheme.typography.bodySmall)
                Text(str(R.string.perm_notif), style = MaterialTheme.typography.bodySmall)
                Text(str(R.string.perm_footer), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary)
            }
        }
        Button(onClick = { openUrl(context, REPO_URL) }, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.Info, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(str(R.string.github))
        }
        OutlinedButton(onClick = { openUrl(context, "$REPO_URL/issues") },
            modifier = Modifier.fillMaxWidth()) {
            Text(str(R.string.support))
        }
        OutlinedButton(onClick = { shareApp(context) }, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.Share, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(str(R.string.share_app))
        }
    }
}


private data class MediaItem(val uri: Uri, val name: String, val size: Long)

private fun queryMedia(context: android.content.Context, type: String): List<MediaItem> {
    val collection = if (type == "video")
        android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI
    else android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI
    val proj = arrayOf(
        android.provider.MediaStore.MediaColumns._ID,
        android.provider.MediaStore.MediaColumns.DISPLAY_NAME,
        android.provider.MediaStore.MediaColumns.SIZE)
    val out = ArrayList<MediaItem>()
    try {
        context.contentResolver.query(
            collection, proj, null, null,
            android.provider.MediaStore.MediaColumns.DATE_MODIFIED + " DESC LIMIT 400"
        )?.use { c ->
            val idi = c.getColumnIndexOrThrow(android.provider.MediaStore.MediaColumns._ID)
            val ni = c.getColumnIndexOrThrow(android.provider.MediaStore.MediaColumns.DISPLAY_NAME)
            val si = c.getColumnIndexOrThrow(android.provider.MediaStore.MediaColumns.SIZE)
            while (c.moveToNext()) {
                val id = c.getLong(idi)
                val uri = android.content.ContentUris.withAppendedId(collection, id)
                out.add(MediaItem(uri, c.getString(ni) ?: "media", c.getLong(si)))
            }
        }
    } catch (e: Exception) {}
    return out
}

private fun loadThumb(context: android.content.Context, uri: Uri): androidx.compose.ui.graphics.ImageBitmap? {
    return try {
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            context.contentResolver.loadThumbnail(
                uri, android.util.Size(256, 256), null)?.asImageBitmap()
        } else {
            val pfd1 = context.contentResolver.openFileDescriptor(uri, "r") ?: return null
            val opts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeFileDescriptor(pfd1.fileDescriptor, null, opts)
            pfd1.close()
            var sample = 1
            while (opts.outWidth / sample > 256 || opts.outHeight / sample > 256) sample *= 2
            val pfd2 = context.contentResolver.openFileDescriptor(uri, "r") ?: return null
            val opts2 = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
            val bmp = android.graphics.BitmapFactory.decodeFileDescriptor(pfd2.fileDescriptor, null, opts2)
            pfd2.close()
            bmp?.asImageBitmap()
        }
    } catch (e: Exception) { null }
}

@Composable
private fun MediaPickerScreen(c: AppContainer, type: String, onPicked: (List<Uri>) -> Unit) {
    val context = LocalContext.current
    var items by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
    var selected by remember { mutableStateOf(setOf<Uri>()) }
    var canRead by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        canRead = grants.values.all { it }
        if (!canRead) loading = false
    }

    LaunchedEffect(Unit) {
        permLauncher.launch(
            if (android.os.Build.VERSION.SDK_INT >= 33)
                arrayOf(android.Manifest.permission.READ_MEDIA_IMAGES,
                        android.Manifest.permission.READ_MEDIA_VIDEO)
            else arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE))
    }

    LaunchedEffect(canRead, type) {
        if (canRead) {
            loading = true
            items = withContext(Dispatchers.IO) { queryMedia(context, type) }
            loading = false
        }
    }

    Column(Modifier.fillMaxSize()) {
        Text(
            str(if (type == "video") R.string.media_videos else R.string.media_photos),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(16.dp))
        when {
            loading -> LinearProgressIndicator(Modifier.fillMaxWidth().padding(16.dp))
            items.isEmpty() -> Text(
                str(R.string.no_devices_hint),
                modifier = Modifier.padding(16.dp),
                style = MaterialTheme.typography.bodySmall)
            else -> LazyVerticalGrid(columns = GridCells.Fixed(3), modifier = Modifier.weight(1f)) {
                items(items, key = { it.uri.toString() }) { item ->
                    val isSel = selected.contains(item.uri)
                    val thumb = produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, item.uri) {
                        value = withContext(Dispatchers.IO) { loadThumb(context, item.uri) }
                    }.value
                    Box(
                        Modifier
                            .padding(2.dp)
                            .aspectRatio(1f)
                            .clip(MaterialTheme.shapes.small)
                            .border(
                                width = if (isSel) 3.dp else 1.dp,
                                color = if (isSel) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.surfaceVariant,
                                shape = MaterialTheme.shapes.small)
                            .clickable {
                                selected = if (isSel) selected - item.uri else selected + item.uri
                            }
                    ) {
                        if (thumb != null) {
                            Image(bitmap = thumb, contentDescription = item.name,
                                modifier = Modifier.fillMaxSize())
                        } else {
                            Text(item.name, style = MaterialTheme.typography.labelSmall,
                                maxLines = 3, modifier = Modifier.padding(8.dp))
                        }
                        if (isSel) {
                            Icon(Icons.Default.CheckCircle, contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).size(20.dp))
                        }
                    }
                }
            }
        }
        Button(
            onClick = { onPicked(selected.toList()) },
            enabled = selected.isNotEmpty(),
            modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Text(str(R.string.send_selected, selected.size))
        }
    }
}


private data class AppEntry(
    val label: String, val pkg: String, val sourceDir: String,
    val size: Long, val isSystem: Boolean, val version: String)

private fun queryInstalledApps(context: android.content.Context): List<AppEntry> {
    val pm = context.packageManager
    val list = try {
        if (android.os.Build.VERSION.SDK_INT >= 33)
            pm.getInstalledApplications(android.content.pm.PackageManager.ApplicationInfoFlags.of(0))
        else {
            @Suppress("DEPRECATION")
            pm.getInstalledApplications(0)
        }
    } catch (e: Exception) { emptyList() }
    return list.mapNotNull { ai ->
        if (ai.sourceDir.isNullOrEmpty()) return@mapNotNull null
        runCatching {
            val size = runCatching { java.io.File(ai.sourceDir).length() }.getOrDefault(-1L)
            val version = runCatching {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(ai.packageName, 0).versionName ?: ""
            }.getOrDefault("")
            AppEntry(
                label = pm.getApplicationLabel(ai).toString(),
                pkg = ai.packageName,
                sourceDir = ai.sourceDir,
                size = size,
                isSystem = (ai.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0,
                version = version)
        }.getOrNull()
    }.sortedBy { it.label.lowercase() }
}

private fun drawableToBitmap(d: android.graphics.drawable.Drawable, size: Int = 96): android.graphics.Bitmap {
    if (d is android.graphics.drawable.BitmapDrawable && d.bitmap != null) return d.bitmap
    val bmp = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bmp)
    d.setBounds(0, 0, size, size)
    d.draw(canvas)
    return bmp
}

@Composable
private fun AppsScreen(c: AppContainer, onPicked: (List<String>) -> Unit) {
    val context = LocalContext.current
    var apps by remember { mutableStateOf<List<AppEntry>>(emptyList()) }
    var selected by remember { mutableStateOf(setOf<String>()) }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        apps = withContext(Dispatchers.IO) { queryInstalledApps(context) }
        loading = false
    }

    Column(Modifier.fillMaxSize()) {
        Text(str(R.string.apps_title), style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(16.dp))
        Text(str(R.string.apps_hint), style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp))
        when {
            loading -> LinearProgressIndicator(Modifier.fillMaxWidth().padding(16.dp))
            apps.isEmpty() -> Text(str(R.string.apps_empty), modifier = Modifier.padding(16.dp),
                style = MaterialTheme.typography.bodySmall)
            else -> LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(apps, key = { it.pkg }) { app ->
                    val isSel = selected.contains(app.pkg)
                    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                        Row(Modifier.fillMaxWidth().padding(10.dp)
                            .clickable {
                                selected = if (isSel) selected - app.pkg else selected + app.pkg
                            },
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            val iconBmp = produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, app.pkg) {
                                value = withContext(Dispatchers.IO) {
                                    runCatching {
                                        val ai = context.packageManager.getApplicationInfo(app.pkg, 0)
                                        drawableToBitmap(context.packageManager.getApplicationIcon(ai)).asImageBitmap()
                                    }.getOrNull()
                                }
                            }.value
                            if (iconBmp != null) {
                                Image(bitmap = iconBmp, contentDescription = null,
                                    modifier = Modifier.size(40.dp).clip(MaterialTheme.shapes.small))
                            } else {
                                Box(Modifier.size(40.dp))
                            }
                            Column(Modifier.weight(1f)) {
                                Text(app.label, style = MaterialTheme.typography.titleSmall)
                                Text(
                                    "${app.pkg} ${app.version}",
                                    style = MaterialTheme.typography.bodySmall)
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                if (app.isSystem) {
                                    Text(str(R.string.apps_system),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                if (app.size >= 0) {
                                    Text(HistoryStore.formatSize(app.size),
                                        style = MaterialTheme.typography.bodySmall)
                                }
                                if (isSel) {
                                    Icon(Icons.Default.CheckCircle, contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(20.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
        Button(
            onClick = { onPicked(apps.filter { selected.contains(it.pkg) }.map { it.sourceDir }) },
            enabled = selected.isNotEmpty(),
            modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Text(str(R.string.apps_send, selected.size))
        }
    }
}


@Composable
private fun TextSendScreen(c: AppContainer, initialText: String?, onConsumed: () -> Unit) {
    val context = LocalContext.current
    var text by remember { mutableStateOf(initialText ?: "") }
    var ready by remember { mutableStateOf(false) }
    LaunchedEffect(initialText) { if (initialText != null) onConsumed() }

    Column(Modifier.padding(20.dp).fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it; ready = false },
            label = { Text(str(R.string.text_hint)) },
            modifier = Modifier.fillMaxWidth().weight(1f))
        if (ready) {
            Text(str(R.string.text_ready), color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.bodySmall)
        }
        Button(
            enabled = text.isNotBlank(),
            onClick = {
                runCatching {
                    val f = java.io.File(context.cacheDir, "shared-text.txt")
                    f.writeText(text)
                    c.pendingFs.value = listOf(f.absolutePath)
                    c.pendingSourcesFlow.value = emptyList()
                    ready = true
                }
            },
            modifier = Modifier.fillMaxWidth()) {
            Text(str(R.string.menu_text))
        }
        OutlinedButton(
            enabled = ready,
            onClick = { navigateTextToSend() },
            modifier = Modifier.fillMaxWidth()) {
            Text(str(R.string.send))
        }
    }
}

// route helper replaced at build position below
private var textSendNavigator: (() -> Unit)? = null
private fun navigateTextToSend() { textSendNavigator?.invoke() }

@Composable
private fun WifiDirectScreen(c: AppContainer) {
    val context = LocalContext.current
    val peers by c.wifiDirect.peers.collectAsStateWithLifecycle()
    var status by remember { mutableStateOf("") }
    var discovering by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        if (grants.values.all { it }) {
            c.wifiDirect.start()
            c.wifiDirect.startDiscovery()
            discovering = true
        } else {
            status = context.getString(R.string.permission_rationale)
        }
    }

    LaunchedEffect(Unit) {
        c.wifiDirect.events.collectLatest { ev ->
            status = when (ev) {
                is com.crocxshare.app.discovery.WifiDirectDiscovery.Event.PeersChanged ->
                    context.getString(R.string.discovered_devices) + ": " + ev.devices.size
                is com.crocxshare.app.discovery.WifiDirectDiscovery.Event.InviteResult ->
                    if (ev.success) context.getString(R.string.wfd_invite) + " OK"
                    else ev.message
                is com.crocxshare.app.discovery.WifiDirectDiscovery.Event.Connected ->
                    context.getString(R.string.wfd_on)
                is com.crocxshare.app.discovery.WifiDirectDiscovery.Event.Disconnected ->
                    ev.reason
                is com.crocxshare.app.discovery.WifiDirectDiscovery.Event.WifiP2pEnabled ->
                    if (ev.enabled) context.getString(R.string.wfd_on) else context.getString(R.string.wfd_off)
                else -> ""
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose { if (discovering) c.wifiDirect.stopDiscovery() }
    }

    Column(Modifier.padding(20.dp).fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(str(R.string.wfd_title), style = MaterialTheme.typography.titleMedium)
        Text(str(R.string.wfd_note), style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = {
                permLauncher.launch(DiscoveryPermissions.requiredForDiscovery())
            }) { Text(str(R.string.wfd_start)) }
            OutlinedButton(enabled = discovering, onClick = {
                c.wifiDirect.stopDiscovery(); discovering = false
            }) { Text(str(R.string.wfd_stop)) }
        }
        if (status.isNotEmpty()) Text(status, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(peers, key = { it.id }) { dev ->
                Card(Modifier.fillMaxWidth()) {
                    Row(Modifier.fillMaxWidth().padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically) {
                        Column {
                            Text(dev.name, style = MaterialTheme.typography.titleSmall)
                            Text(dev.address, style = MaterialTheme.typography.bodySmall)
                        }
                        Button(onClick = { c.wifiDirect.invite(dev) }) {
                            Text(str(R.string.wfd_invite))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BenchScreen(c: AppContainer) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var size by remember { mutableStateOf("100") }
    var out by remember { mutableStateOf("") }
    var running by remember { mutableStateOf(false) }
    Column(Modifier.padding(20.dp).fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(
            value = size,
            onValueChange = { size = it.filter(Char::isDigit) },
            label = { Text(str(R.string.bench_size)) },
            modifier = Modifier.fillMaxWidth())
        Button(
            enabled = !running,
            onClick = {
                running = true
                scope.launch {
                    val results = com.crocxshare.app.benchmark.BenchmarkRunner.run(
                        context, size.toIntOrNull() ?: 100, c.settings.chunkBytes() / 1024)
                    out = results.joinToString("\n") {
                        "size=" + (it.sizeBytes / 1048576) + "MB time=" + it.durationMs + "ms " +
                            "avg=" + String.format("%.1f", it.throughputMBps) + " MB/s " +
                            "ok=" + it.success + " sha=" + it.shaOk + " " + it.message
                    }
                    running = false
                }
            },
            modifier = Modifier.fillMaxWidth()) {
            Text(str(R.string.run_benchmark))
        }
        Text(out, style = MaterialTheme.typography.bodySmall)
    }
}
