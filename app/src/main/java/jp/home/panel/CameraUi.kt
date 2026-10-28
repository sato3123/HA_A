@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)

package jp.home.panel

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import org.webrtc.EglBase
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/** ホーム画面のカメラ：静止画を10秒ごとに更新。タップで動画 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CameraTile(e: Entity, vm: MainViewModel) {
    var img by remember(e.entityId) { mutableStateOf<ImageBitmap?>(null) }
    var failed by remember(e.entityId) { mutableStateOf(false) }
    val viewing by vm.cameraFor.collectAsState()
    val saver by vm.screensaver.collectAsState()
    // 映像を開いている間とスクリーンセーバー中は、静止画の読み込みを休む
    val paused = viewing != null || saver || e.unavailable
    LaunchedEffect(e.entityId, paused) {
        while (!paused && isActive) {
            val next = vm.cameraSnapshot(e.entityId, 640)
            if (next != null) img = next
            failed = next == null && img == null
            delay(10_000)
        }
    }
    Card(
        onClick = { vm.openCamera(e.entityId) },
        enabled = !e.unavailable,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            EntityHeader(e, vm, large = false)
            Box(
                Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(8.dp)).background(Color.Black),
                contentAlignment = Alignment.Center,
            ) {
                val i = img
                when {
                    e.unavailable -> Text("カメラにつながりません", color = Color.White)
                    i != null -> {
                        Image(i, contentDescription = e.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                        Text(
                            "▶ タップで動画",
                            color = Color.White,
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp)
                                .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                    failed && vm.ipCamera(e.entityId)?.snapshotUrl?.isBlank() == true ->
                        Text("📷 タップで映像を表示", color = Color.White)
                    failed -> Text("映像を読めませんでした", color = Color.White)
                    else -> CircularProgressIndicator()
                }
            }
        }
    }
}

/** 画質の選択肢（key, 表示名, 説明） */
private val HA_MODES = listOf(
    Triple("auto", "自動", "使える中でいちばん遅れの少ない方式"),
    Triple("realtime", "リアルタイム", "遅れ1秒未満（WebRTC）"),
    Triple("hls", "高画質", "なめらか・数秒の遅れ（HLS）"),
    Triple("mjpeg", "簡易", "どのカメラでも・コマ数少なめ"),
)
private val DIRECT_MODES = listOf(
    Triple("main", "高画質", "メインの映像"),
    Triple("sub", "軽量", "解像度を下げた映像（通信が少ない）"),
)

/** 実際に再生している方式 */
private enum class Play { WEBRTC, HLS, MJPEG, RTSP }

/** カメラの動画（全画面）。画質を選べる。だめなら遅れの少ない順にほかの方式へ切り替える */
@Composable
fun CameraViewer(vm: MainViewModel) {
    val id by vm.cameraFor.collectAsState()
    val entityId = id ?: return
    val states by vm.allStates.collectAsState()
    val modes by vm.cameraModes.collectAsState()
    val direct = vm.ipCamera(entityId)
    val name = direct?.name ?: states[entityId]?.name ?: entityId
    val mode = modes[entityId] ?: vm.cameraMode(entityId)
    // 再生する方式と、その再生アドレス（null = 確認中）
    var play by remember(entityId, mode) { mutableStateOf<Pair<Play, String>?>(null) }
    var note by remember(entityId, mode) { mutableStateOf<String?>(null) }
    var showModes by remember(entityId) { mutableStateOf(false) }
    var showMotion by remember(entityId) { mutableStateOf(false) }
    val recording by vm.camRecording.collectAsState()
    // 失敗した方式（次はとばす）
    val failed = remember(entityId, mode) { mutableSetOf<Play>() }
    var retry by remember(entityId, mode) { mutableStateOf(0) }

    if (showMotion) MotionDialog(vm, entityId, name) { showMotion = false }

    LaunchedEffect(entityId, mode, retry) {
        play = null
        if (direct != null) {
            play = Play.RTSP to vm.ipCameraUrl(direct, light = mode == "sub")
            return@LaunchedEffect
        }
        val order = when (mode) {
            "realtime" -> listOf(Play.WEBRTC, Play.HLS, Play.MJPEG)
            "hls" -> listOf(Play.HLS, Play.MJPEG)
            "mjpeg" -> listOf(Play.MJPEG)
            else -> listOf(Play.WEBRTC, Play.HLS, Play.MJPEG)
        }.filter { it !in failed }
        for (p in order) {
            when (p) {
                Play.WEBRTC -> if (vm.cameraWebRtcSupported(entityId)) { play = p to ""; return@LaunchedEffect }
                Play.HLS -> {
                    val u = try { vm.cameraHlsUrl(entityId) } catch (e: CancellationException) { throw e } catch (e: Exception) { null }
                    if (u != null) { play = p to u; return@LaunchedEffect }
                }
                else -> { play = p to ""; return@LaunchedEffect }
            }
        }
        play = Play.MJPEG to ""
    }
    val fallback: (Play, String) -> Unit = { p, why ->
        failed.add(p)
        note = "${label(p)}で再生できないため、ほかの方式に切り替えました（$why）"
        retry++
    }

    Dialog(onDismissRequest = { vm.closeCamera() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            when (val p = play) {
                null -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                else -> when (p.first) {
                    Play.WEBRTC -> WebRtcView(vm, entityId) { fallback(Play.WEBRTC, it) }
                    Play.HLS -> PlayerView(vm, p.second, rtsp = false, tcp = false) { fallback(Play.HLS, it) }
                    Play.RTSP -> PlayerView(vm, p.second, rtsp = true, tcp = direct?.tcp == true) {
                        note = "映像を受け取れませんでした（$it）。URL・ユーザー名・パスワードを確認してください。" +
                            if (direct?.tcp != true) "映像が乱れるときは「TCP で受け取る」をオンにしてください。" else ""
                    }
                    Play.MJPEG -> MjpegView(vm, entityId, name) { if (note == null) note = it }
                }
            }
            Column(Modifier.fillMaxWidth().background(Color.Black.copy(alpha = 0.45f)).padding(horizontal = 12.dp, vertical = 4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("📷 $name", color = Color.White, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    play?.let { p ->
                        TextButton(onClick = { showModes = !showModes }) {
                            Text("画質：" + (if (direct != null) DIRECT_MODES.first { it.first == mode }.second else label(p.first)) + " ▾", color = Color.White)
                        }
                    }
                    if (recording.any { it.entity == entityId }) TextButton(onClick = { showMotion = true }) { Text("🎞 記録", color = Color.White) }
                    TextButton(onClick = { vm.closeCamera() }) { Text("✕ 閉じる", color = Color.White) }
                }
                if (showModes) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        (if (direct != null) DIRECT_MODES else HA_MODES).forEach { (k, l, sub) ->
                            FilterChip(
                                selected = mode == k,
                                onClick = { vm.setCameraMode(entityId, k); showModes = false },
                                label = {
                                    Column {
                                        Text(l)
                                        Text(sub, style = MaterialTheme.typography.labelSmall)
                                    }
                                },
                                colors = FilterChipDefaults.filterChipColors(
                                    labelColor = Color.White,
                                    selectedContainerColor = MaterialTheme.colorScheme.primary,
                                    selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                                ),
                            )
                        }
                    }
                }
            }
            note?.let {
                Text(
                    it, color = Color.White, style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp)
                        .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(6.dp)).padding(8.dp),
                )
            }
        }
    }
}

private fun label(p: Play) = when (p) {
    Play.WEBRTC -> "リアルタイム"
    Play.HLS -> "高画質"
    Play.MJPEG -> "簡易"
    Play.RTSP -> "直接"
}

/** リアルタイム（WebRTC）。遅れが1秒未満 */
@Composable
private fun WebRtcView(vm: MainViewModel, entityId: String, onError: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val egl = remember { EglBase.create() }
    val renderer = remember {
        SurfaceViewRenderer(context).apply {
            init(egl.eglBaseContext, null)
            setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
            setEnableHardwareScaler(true)
        }
    }
    var connecting by remember(entityId) { mutableStateOf(true) }
    // 後に書いたものから先に片付けられるので、描画の片付けは接続より前に書く
    DisposableEffect(Unit) {
        onDispose {
            renderer.release()
            egl.release()
        }
    }
    DisposableEffect(entityId) {
        val s = WebRtcSession(context, egl, vm, entityId, renderer, scope, onConnected = { connecting = false }, onError = onError)
        s.start()
        onDispose { s.close() }
    }
    KeepAwake(vm)
    Box(Modifier.fillMaxSize()) {
        AndroidView(factory = { renderer }, modifier = Modifier.fillMaxSize())
        if (connecting) CircularProgressIndicator(Modifier.align(Alignment.Center))
    }
}

/** 見ている間はスクリーンセーバーを出さない */
@Composable
private fun KeepAwake(vm: MainViewModel) {
    LaunchedEffect(Unit) {
        while (isActive) {
            vm.onUserInteraction()
            delay(20_000)
        }
    }
}

/**
 * HLS（Home Assistant の高画質）と RTSP（直接つなぐカメラ）の再生。
 * 遅れを減らすため、ためる映像を最小限にする。
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
private fun PlayerView(vm: MainViewModel, url: String, rtsp: Boolean, tcp: Boolean, onError: (String) -> Unit) {
    val context = LocalContext.current
    val player = remember(url) {
        val load = DefaultLoadControl.Builder()
            .setBufferDurationsMs(if (rtsp) 500 else 1_000, if (rtsp) 2_000 else 4_000, 100, if (rtsp) 200 else 500)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()
        ExoPlayer.Builder(context)
            .setLoadControl(load)
            .setMediaSourceFactory(DefaultMediaSourceFactory(OkHttpDataSource.Factory(vm.playerHttp)))
            .build().apply {
                if (rtsp) {
                    setMediaSource(
                        RtspMediaSource.Factory().setForceUseRtpTcp(tcp).setTimeoutMs(8_000)
                            .createMediaSource(MediaItem.fromUri(url)),
                    )
                } else {
                    setMediaItem(
                        MediaItem.Builder().setUri(url).setMimeType(MimeTypes.APPLICATION_M3U8)
                            // ライブの先頭近くを再生し、遅れたら少し速めて追いつく
                            .setLiveConfiguration(
                                MediaItem.LiveConfiguration.Builder()
                                    .setTargetOffsetMs(1_500).setMinOffsetMs(500)
                                    .setMinPlaybackSpeed(0.97f).setMaxPlaybackSpeed(1.15f).build(),
                            )
                            .build(),
                    )
                }
                playWhenReady = true
                prepare()
            }
    }
    var buffering by remember(url) { mutableStateOf(true) }
    DisposableEffect(player) {
        val l = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                onError(error.errorCodeName)
            }
            override fun onPlaybackStateChanged(state: Int) {
                buffering = state == Player.STATE_BUFFERING || state == Player.STATE_IDLE
            }
        }
        player.addListener(l)
        onDispose {
            player.removeListener(l)
            player.release()
        }
    }
    KeepAwake(vm)
    Box(Modifier.fillMaxSize()) {
        AndroidView(
            factory = { ctx ->
                androidx.media3.ui.PlayerView(ctx).apply {
                    useController = false
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    setShutterBackgroundColor(android.graphics.Color.BLACK)
                    this.player = player
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
        if (buffering) CircularProgressIndicator(Modifier.align(Alignment.Center))
    }
}

/** 簡易表示（MJPEG）。どのカメラでも見られるが、コマ数が少なく音声は出ない */
@Composable
private fun MjpegView(vm: MainViewModel, entityId: String, name: String, onNote: (String) -> Unit) {
    var frame by remember(entityId) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(entityId) {
        // 静止画ですぐに何か出してから、動画につなぐ
        vm.cameraSnapshot(entityId, 1920)?.let { if (frame == null) frame = it }
        while (isActive) {
            try {
                vm.cameraStream(entityId).collect {
                    frame = it
                    vm.onUserInteraction()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 動画がだめなら、しばらく静止画を1秒ごとに更新してから、もう一度つなぐ
                onNote("動画につなげないため、静止画を1秒ごとに更新しています（${e.message}）")
                repeat(20) {
                    vm.cameraSnapshot(entityId, 1920)?.let { frame = it }
                    vm.onUserInteraction()
                    delay(1000)
                }
            }
        }
    }
    Box(Modifier.fillMaxSize()) {
        frame?.let { Image(it, contentDescription = name, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize()) }
            ?: CircularProgressIndicator(Modifier.align(Alignment.Center))
    }
}
