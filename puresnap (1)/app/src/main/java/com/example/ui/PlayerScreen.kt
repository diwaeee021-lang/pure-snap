package com.example.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.media.AudioManager
import android.net.Uri
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.BrightnessHigh
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.Equalizer
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.ui.PlayerView
import com.example.player.AbLoopState
import com.example.player.AudioFxController
import com.example.player.MediaTechnicalInfo
import com.example.player.MediaTrackOption
import com.example.player.PlayerAspectMode
import com.example.player.PlayerTrackHelper
import com.example.player.SupportedFormatCatalog
import kotlinx.coroutines.delay
import okhttp3.OkHttpClient
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.roundToInt

private enum class ActivePlayerSheet {
    NONE, EQUALIZER, TRACKS, QUEUE, TOOLS
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

@OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(
    queue: List<PlayerTarget>,
    currentIndex: Int,
    onSelectQueueIndex: (Int) -> Unit,
    onRemoveQueueIndex: (Int) -> Unit,
    onAddLocalMedia: (List<Uri>) -> Unit,
    onAttachSubtitle: (Uri) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (queue.isEmpty()) return
    val safeIndex = currentIndex.coerceIn(0, queue.lastIndex)
    val activeTarget = queue[safeIndex]

    val context = LocalContext.current
    val audioManager = remember { context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager }
    val audioFxController = remember { AudioFxController() }
    val fxState by audioFxController.state.collectAsStateWithLifecycle()

    BackHandler(onBack = onClose)

    val subtitlePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
        onResult = { uri -> if (uri != null) onAttachSubtitle(uri) }
    )
    val addQueueMediaLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments(),
        onResult = { uris -> if (uris.isNotEmpty()) onAddLocalMedia(uris) }
    )

    val exoPlayer = remember {
        val okHttpClient = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
        val okHttpFactory = OkHttpDataSource.Factory(okHttpClient)
            .setUserAgent("PureSnap-MediaEngine/2.0")
            .setDefaultRequestProperties(mapOf("DNT" to "1"))
        val dataSourceFactory = DefaultDataSource.Factory(context, okHttpFactory)
        val extractorsFactory = DefaultExtractorsFactory()
            .setConstantBitrateSeekingEnabled(true)
        val renderersFactory = DefaultRenderersFactory(context)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
            .setEnableDecoderFallback(true)

        ExoPlayer.Builder(context, renderersFactory)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory, extractorsFactory))
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .setUsage(C.USAGE_MEDIA)
                    .build(),
                true
            )
            .setHandleAudioBecomingNoisy(true)
            .build()
    }

    var isPlaying by remember { mutableStateOf(true) }
    var durationMs by remember { mutableLongStateOf(0L) }
    var positionMs by remember { mutableLongStateOf(0L) }
    var isSeeking by remember { mutableStateOf(false) }
    var sliderPosition by remember { mutableFloatStateOf(0f) }
    var playbackSpeed by remember { mutableFloatStateOf(1f) }
    var repeatMode by remember { mutableIntStateOf(Player.REPEAT_MODE_OFF) }
    var shuffleEnabled by remember { mutableStateOf(false) }
    var aspectMode by remember { mutableStateOf(PlayerAspectMode.FIT) }
    var controlsLocked by remember { mutableStateOf(false) }
    var controlsVisible by remember { mutableStateOf(true) }
    var activeSheet by remember { mutableStateOf(ActivePlayerSheet.NONE) }
    var abLoopState by remember { mutableStateOf(AbLoopState()) }
    var sleepTimerRemainingMs by remember { mutableStateOf<Long?>(null) }
    var playerErrorText by remember { mutableStateOf<String?>(null) }

    var audioTracks by remember { mutableStateOf<List<MediaTrackOption>>(emptyList()) }
    var subtitleTracks by remember { mutableStateOf<List<MediaTrackOption>>(emptyList()) }
    var currentTracksSnapshot by remember { mutableStateOf(Tracks.EMPTY) }
    var subtitlesDisabled by remember { mutableStateOf(false) }
    var technicalInfo by remember {
        mutableStateOf(
            PlayerTrackHelper.extractTechnicalInfo(
                Tracks.EMPTY,
                activeTarget.mediaUri,
                activeTarget.mimeType
            )
        )
    }

    // Gesture HUD indicator
    var gestureHudLabel by remember { mutableStateOf<String?>(null) }
    var brightnessFraction by remember {
        mutableFloatStateOf(
            context.findActivity()?.window?.attributes?.screenBrightness
                ?.takeIf { it in 0.01f..1f } ?: 0.55f
        )
    }
    var dragSeekStartMs by remember { mutableLongStateOf(-1L) }
    var dragSeekAccumDx by remember { mutableFloatStateOf(0f) }

    // Sync MediaItems queue into ExoPlayer cleanly when queue or subtitles change
    LaunchedEffect(queue) {
        val builtItems = queue.map { SupportedFormatCatalog.buildMediaItem(it) }
        val existingCount = exoPlayer.mediaItemCount
        val sameUris = existingCount == builtItems.size &&
            (0 until existingCount).all { idx ->
                exoPlayer.getMediaItemAt(idx).mediaId == builtItems[idx].mediaId
            }

        if (sameUris && existingCount > 0) {
            // Only subtitle attachment or metadata updated for active item
            val curIdx = exoPlayer.currentMediaItemIndex.coerceIn(0, builtItems.lastIndex)
            val updated = builtItems[curIdx]
            if (exoPlayer.getMediaItemAt(curIdx).localConfiguration?.subtitleConfigurations !=
                updated.localConfiguration?.subtitleConfigurations
            ) {
                val resumePos = exoPlayer.currentPosition
                val wasPlaying = exoPlayer.playWhenReady
                exoPlayer.replaceMediaItem(curIdx, updated)
                exoPlayer.seekTo(curIdx, resumePos)
                exoPlayer.playWhenReady = wasPlaying
            }
        } else {
            exoPlayer.setMediaItems(builtItems, safeIndex, 0L)
            exoPlayer.prepare()
            exoPlayer.playWhenReady = true
        }
    }

    LaunchedEffect(safeIndex) {
        if (exoPlayer.mediaItemCount > safeIndex && exoPlayer.currentMediaItemIndex != safeIndex) {
            exoPlayer.seekTo(safeIndex, 0L)
            abLoopState = AbLoopState()
        }
    }

    DisposableEffect(exoPlayer) {
        val initAudioSession = exoPlayer.audioSessionId
        if (initAudioSession != C.AUDIO_SESSION_ID_UNSET && initAudioSession > 0) {
            audioFxController.attachToSession(initAudioSession)
        }

        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (exoPlayer.duration > 0L) durationMs = exoPlayer.duration
                if (playbackState == Player.STATE_READY) playerErrorText = null
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                val newIdx = exoPlayer.currentMediaItemIndex
                if (newIdx in queue.indices && newIdx != safeIndex) {
                    abLoopState = AbLoopState()
                    onSelectQueueIndex(newIdx)
                }
            }

            override fun onAudioSessionIdChanged(audioSessionId: Int) {
                if (audioSessionId != C.AUDIO_SESSION_ID_UNSET && audioSessionId > 0) {
                    audioFxController.attachToSession(audioSessionId)
                }
            }

            override fun onTracksChanged(tracks: Tracks) {
                currentTracksSnapshot = tracks
                val (aTracks, tTracks) = PlayerTrackHelper.extractTracks(tracks)
                audioTracks = aTracks
                subtitleTracks = tTracks
                technicalInfo = PlayerTrackHelper.extractTechnicalInfo(
                    tracks,
                    activeTarget.mediaUri,
                    activeTarget.mimeType
                )
            }

            override fun onPlayerError(error: PlaybackException) {
                playerErrorText = "Format error (${error.errorCodeName}): ${error.localizedMessage ?: "Unsupported codec"}"
            }
        }
        exoPlayer.addListener(listener)
        onDispose {
            exoPlayer.removeListener(listener)
            audioFxController.release()
            exoPlayer.release()
            // Restore system auto brightness when closing player
            context.findActivity()?.window?.let { win ->
                val lp = win.attributes
                if (lp.screenBrightness >= 0f) {
                    lp.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                    win.attributes = lp
                }
            }
        }
    }

    // Position poller + A-B loop check + sleep timer countdown
    LaunchedEffect(exoPlayer, isSeeking, abLoopState, sleepTimerRemainingMs) {
        while (true) {
            if (!isSeeking) {
                val dur = exoPlayer.duration.coerceAtLeast(0L)
                val pos = exoPlayer.currentPosition.coerceAtLeast(0L)
                durationMs = dur
                positionMs = pos
                sliderPosition = if (dur > 0L) pos.toFloat() / dur.toFloat() else 0f

                if (abLoopState.shouldLoopBack(pos)) {
                    exoPlayer.seekTo(abLoopState.pointA ?: 0L)
                }
            }
            val sleepLeft = sleepTimerRemainingMs
            if (sleepLeft != null) {
                val updated = (sleepLeft - 250L).coerceAtLeast(0L)
                if (updated == 0L) {
                    sleepTimerRemainingMs = null
                    exoPlayer.pause()
                    gestureHudLabel = "Sleep timer paused playback"
                } else {
                    sleepTimerRemainingMs = updated
                }
            }
            delay(250L)
        }
    }

    // Auto-clear ephemeral gesture HUD label
    LaunchedEffect(gestureHudLabel) {
        if (gestureHudLabel != null) {
            delay(1300L)
            gestureHudLabel = null
        }
    }

    val formatBadge = remember(activeTarget.mediaUri, activeTarget.mimeType) {
        SupportedFormatCatalog.detectDisplayFormatLabel(activeTarget.mediaUri, activeTarget.mimeType)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF07090D))
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .testTag("player_screen")
    ) {
        // Top Header (hidden if controls locked or toggled off on video)
        AnimatedVisibility(
            visible = !controlsLocked && (controlsVisible || activeTarget.isAudioOnly),
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                IconButton(
                    onClick = onClose,
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("player_back_button")
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = Color.White
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = activeTarget.title,
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = activeTarget.subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF9BA7BE),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = formatBadge,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
                IconButton(
                    onClick = { controlsLocked = true },
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("player_lock_button")
                ) {
                    Icon(
                        imageVector = Icons.Filled.LockOpen,
                        contentDescription = "Lock player controls",
                        tint = Color(0xFF9BA7BE)
                    )
                }
            }
        }

        // Video Viewport or Studio Audio Stage with Touch Gestures
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 10.dp),
            contentAlignment = Alignment.Center
        ) {
            if (activeTarget.isAudioOnly) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                    modifier = Modifier.padding(20.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(216.dp)
                            .clip(RoundedCornerShape(42.dp))
                            .background(
                                Brush.linearGradient(
                                    colors = listOf(
                                        MaterialTheme.colorScheme.primaryContainer,
                                        MaterialTheme.colorScheme.secondaryContainer
                                    )
                                )
                            )
                            .clickable {
                                if (exoPlayer.isPlaying) exoPlayer.pause() else exoPlayer.play()
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (isPlaying) Icons.Filled.GraphicEq else Icons.Filled.MusicNote,
                            contentDescription = "Audio playback stage",
                            tint = MaterialTheme.colorScheme.secondary,
                            modifier = Modifier.size(92.dp)
                        )
                    }
                    Surface(
                        color = Color(0xFF151C28),
                        shape = RoundedCornerShape(14.dp),
                        border = BorderStroke(1.dp, Color(0xFF2B374D))
                    ) {
                        Text(
                            text = "EQ: ${fxState.preset.label} • ${technicalInfo.audioCodec} • ${technicalInfo.audioSpec}",
                            style = MaterialTheme.typography.labelMedium,
                            color = Color(0xFFC5D0E4),
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                        )
                    }
                }
            } else {
                AndroidView(
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(18.dp)),
                    factory = { ctx ->
                        PlayerView(ctx).apply {
                            player = exoPlayer
                            useController = false
                            keepScreenOn = true
                            resizeMode = aspectMode.toResizeMode()
                            layoutParams = FrameLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT
                            )
                        }
                    },
                    update = { view ->
                        view.player = exoPlayer
                        view.resizeMode = aspectMode.toResizeMode()
                    }
                )

                // Gesture overlay for tap, double-tap +/-10s, brightness/volume/horizontal scrub
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(controlsLocked, durationMs) {
                            detectTapGestures(
                                onTap = {
                                    if (!controlsLocked) controlsVisible = !controlsVisible
                                },
                                onDoubleTap = { offset ->
                                    if (controlsLocked) return@detectTapGestures
                                    val w = size.width.toFloat().coerceAtLeast(1f)
                                    val ratio = offset.x / w
                                    when {
                                        ratio < 0.35f -> {
                                            val next = (exoPlayer.currentPosition - 10_000L).coerceAtLeast(0L)
                                            exoPlayer.seekTo(next)
                                            gestureHudLabel = "-10s (${FormatUtils.formatDuration(next)})"
                                        }
                                        ratio > 0.65f -> {
                                            val cap = if (durationMs > 0L) durationMs else Long.MAX_VALUE
                                            val next = (exoPlayer.currentPosition + 10_000L).coerceAtMost(cap)
                                            exoPlayer.seekTo(next)
                                            gestureHudLabel = "+10s (${FormatUtils.formatDuration(next)})"
                                        }
                                        else -> {
                                            if (exoPlayer.isPlaying) {
                                                exoPlayer.pause()
                                                gestureHudLabel = "Paused"
                                            } else {
                                                exoPlayer.play()
                                                gestureHudLabel = "Playing"
                                            }
                                        }
                                    }
                                }
                            )
                        }
                        .pointerInput(controlsLocked) {
                            if (controlsLocked) return@pointerInput
                            detectDragGestures(
                                onDragStart = { startOffset ->
                                    dragSeekStartMs = exoPlayer.currentPosition
                                    dragSeekAccumDx = 0f
                                    brightnessFraction = context.findActivity()?.window?.attributes?.screenBrightness
                                        ?.takeIf { it in 0.01f..1f } ?: brightnessFraction
                                },
                                onDragEnd = {
                                    if (dragSeekStartMs >= 0L && abs(dragSeekAccumDx) > 28f && durationMs > 0L) {
                                        val deltaMs = ((dragSeekAccumDx / size.width.coerceAtLeast(1)) * 90_000f).toLong()
                                        val target = (dragSeekStartMs + deltaMs).coerceIn(0L, durationMs)
                                        exoPlayer.seekTo(target)
                                    }
                                    dragSeekStartMs = -1L
                                    dragSeekAccumDx = 0f
                                },
                                onDrag = { change, dragAmount ->
                                    change.consume()
                                    val h = size.height.toFloat().coerceAtLeast(1f)
                                    val w = size.width.toFloat().coerceAtLeast(1f)
                                    if (abs(dragAmount.x) > abs(dragAmount.y) * 1.2f && durationMs > 0L) {
                                        dragSeekAccumDx += dragAmount.x
                                        val deltaMs = ((dragSeekAccumDx / w) * 90_000f).toLong()
                                        val previewMs = (dragSeekStartMs + deltaMs).coerceIn(0L, durationMs)
                                        val sign = if (deltaMs >= 0) "+" else "-"
                                        gestureHudLabel = "Seek $sign${abs(deltaMs) / 1000}s → ${FormatUtils.formatDuration(previewMs)}"
                                    } else if (abs(dragAmount.y) > abs(dragAmount.x)) {
                                        val deltaFrac = -dragAmount.y / (h * 0.7f)
                                        if (change.position.x < w * 0.5f) {
                                            brightnessFraction = (brightnessFraction + deltaFrac).coerceIn(0.05f, 1f)
                                            context.findActivity()?.window?.let { win ->
                                                val lp = win.attributes
                                                lp.screenBrightness = brightnessFraction
                                                win.attributes = lp
                                            }
                                            gestureHudLabel = "Brightness ${(brightnessFraction * 100).roundToInt()}%"
                                        } else {
                                            audioManager?.let { am ->
                                                val maxVol = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
                                                val curVol = am.getStreamVolume(AudioManager.STREAM_MUSIC)
                                                val step = (deltaFrac * maxVol).roundToInt()
                                                if (step != 0) {
                                                    val nextVol = (curVol + step).coerceIn(0, maxVol)
                                                    am.setStreamVolume(AudioManager.STREAM_MUSIC, nextVol, 0)
                                                    val pct = ((nextVol.toFloat() / maxVol.toFloat()) * 100).roundToInt()
                                                    gestureHudLabel = "Volume $pct%"
                                                }
                                            }
                                        }
                                    }
                                }
                            )
                        }
                )
            }

            // Ephemeral Gesture / Lock / Error Badges
            if (gestureHudLabel != null) {
                Surface(
                    color = Color(0xCC0D131D),
                    shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.secondary.copy(alpha = 0.6f))
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        val icon = when {
                            gestureHudLabel!!.startsWith("Brightness") -> Icons.Filled.BrightnessHigh
                            gestureHudLabel!!.startsWith("Volume") -> Icons.Filled.VolumeUp
                            else -> Icons.Filled.GraphicEq
                        }
                        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.secondary)
                        Text(gestureHudLabel!!, style = MaterialTheme.typography.titleSmall, color = Color.White)
                    }
                }
            }

            if (playerErrorText != null) {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(14.dp)
                ) {
                    Text(
                        text = playerErrorText!!,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }

            if (controlsLocked) {
                Surface(
                    color = Color(0xCC111620),
                    shape = CircleShape,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)),
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(12.dp)
                ) {
                    IconButton(
                        onClick = { controlsLocked = false },
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("player_unlock_button")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Lock,
                            contentDescription = "Unlock player controls",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }

        // Bottom Transport & Feature Chips (hidden when locked or auto-hidden on video)
        AnimatedVisibility(
            visible = !controlsLocked && (controlsVisible || activeTarget.isAudioOnly),
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            Surface(
                color = Color(0xFF111620),
                shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Quick feature pills row (EQ, Tracks/CC, Aspect, Playlist, Tools)
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = PaddingValues(horizontal = 2.dp)
                    ) {
                        item {
                            AssistChip(
                                onClick = { activeSheet = ActivePlayerSheet.EQUALIZER },
                                label = { Text(if (fxState.enabled) "EQ: ${fxState.preset.label}" else "EQ Off") },
                                leadingIcon = {
                                    Icon(Icons.Filled.Equalizer, contentDescription = null, modifier = Modifier.size(16.dp))
                                },
                                colors = AssistChipDefaults.assistChipColors(containerColor = Color(0xFF1A2232)),
                                modifier = Modifier
                                    .height(48.dp)
                                    .testTag("player_eq_button")
                            )
                        }
                        item {
                            AssistChip(
                                onClick = { activeSheet = ActivePlayerSheet.TRACKS },
                                label = { Text("Audio & CC") },
                                leadingIcon = {
                                    Icon(Icons.Filled.ClosedCaption, contentDescription = null, modifier = Modifier.size(16.dp))
                                },
                                colors = AssistChipDefaults.assistChipColors(containerColor = Color(0xFF1A2232)),
                                modifier = Modifier
                                    .height(48.dp)
                                    .testTag("player_tracks_button")
                            )
                        }
                        if (!activeTarget.isAudioOnly) {
                            item {
                                AssistChip(
                                    onClick = { aspectMode = aspectMode.next() },
                                    label = { Text("Aspect: ${aspectMode.label}") },
                                    leadingIcon = {
                                        Icon(Icons.Filled.AspectRatio, contentDescription = null, modifier = Modifier.size(16.dp))
                                    },
                                    colors = AssistChipDefaults.assistChipColors(containerColor = Color(0xFF1A2232)),
                                    modifier = Modifier
                                        .height(48.dp)
                                        .testTag("player_aspect_button")
                                )
                            }
                        }
                        item {
                            AssistChip(
                                onClick = { activeSheet = ActivePlayerSheet.QUEUE },
                                label = { Text("Queue (${safeIndex + 1}/${queue.size})") },
                                leadingIcon = {
                                    Icon(Icons.AutoMirrored.Filled.QueueMusic, contentDescription = null, modifier = Modifier.size(16.dp))
                                },
                                colors = AssistChipDefaults.assistChipColors(containerColor = Color(0xFF1A2232)),
                                modifier = Modifier
                                    .height(48.dp)
                                    .testTag("player_queue_button")
                            )
                        }
                        item {
                            AssistChip(
                                onClick = { activeSheet = ActivePlayerSheet.TOOLS },
                                label = {
                                    val abTag = if (abLoopState.isActive) " • A-B" else ""
                                    Text("${String.format(Locale.US, "%.2gx", playbackSpeed)}$abTag • Tools")
                                },
                                leadingIcon = {
                                    Icon(Icons.Filled.Tune, contentDescription = null, modifier = Modifier.size(16.dp))
                                },
                                colors = AssistChipDefaults.assistChipColors(containerColor = Color(0xFF1A2232)),
                                modifier = Modifier
                                    .height(48.dp)
                                    .testTag("player_tools_button")
                            )
                        }
                    }

                    // Seekbar + Time
                    Slider(
                        value = sliderPosition.coerceIn(0f, 1f),
                        onValueChange = { fraction ->
                            isSeeking = true
                            sliderPosition = fraction
                        },
                        onValueChangeFinished = {
                            if (durationMs > 0L) {
                                exoPlayer.seekTo((sliderPosition * durationMs).toLong())
                            }
                            isSeeking = false
                        },
                        colors = SliderDefaults.colors(
                            thumbColor = MaterialTheme.colorScheme.primary,
                            activeTrackColor = MaterialTheme.colorScheme.primary,
                            inactiveTrackColor = Color(0xFF2D384D)
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("player_seek_slider")
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        val displayedPos = if (isSeeking && durationMs > 0L) {
                            (sliderPosition * durationMs).toLong()
                        } else {
                            positionMs
                        }
                        Text(
                            text = FormatUtils.formatDuration(displayedPos),
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFF9BA7BE)
                        )
                        if (abLoopState.pointA != null) {
                            Text(
                                text = "A:${FormatUtils.formatDuration(abLoopState.pointA!!)}" +
                                    (abLoopState.pointB?.let { " → B:${FormatUtils.formatDuration(it)}" } ?: " → …"),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.secondary
                            )
                        }
                        Text(
                            text = FormatUtils.formatDuration(durationMs),
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFF9BA7BE)
                        )
                    }

                    // Transport Controls
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = {
                                shuffleEnabled = !shuffleEnabled
                                exoPlayer.shuffleModeEnabled = shuffleEnabled
                            },
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("player_shuffle_button")
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Shuffle,
                                contentDescription = "Toggle shuffle",
                                tint = if (shuffleEnabled) MaterialTheme.colorScheme.secondary else Color(0xFF9BA7BE)
                            )
                        }

                        IconButton(
                            onClick = {
                                if (exoPlayer.hasPreviousMediaItem()) exoPlayer.seekToPreviousMediaItem()
                                else exoPlayer.seekTo(0L)
                            },
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("player_previous_button")
                        ) {
                            Icon(
                                imageVector = Icons.Filled.SkipPrevious,
                                contentDescription = "Previous track",
                                tint = Color.White,
                                modifier = Modifier.size(28.dp)
                            )
                        }

                        IconButton(
                            onClick = {
                                exoPlayer.seekTo((exoPlayer.currentPosition - 10_000L).coerceAtLeast(0L))
                            },
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("player_replay_10")
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Replay10,
                                contentDescription = "Rewind 10 seconds",
                                tint = Color.White,
                                modifier = Modifier.size(28.dp)
                            )
                        }

                        FilledIconButton(
                            onClick = {
                                if (exoPlayer.isPlaying) exoPlayer.pause() else exoPlayer.play()
                            },
                            modifier = Modifier
                                .size(62.dp)
                                .clip(CircleShape)
                                .testTag("player_play_pause")
                        ) {
                            Icon(
                                imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                                contentDescription = if (isPlaying) "Pause" else "Play",
                                modifier = Modifier.size(34.dp)
                            )
                        }

                        IconButton(
                            onClick = {
                                val targetPos = exoPlayer.currentPosition + 10_000L
                                exoPlayer.seekTo(if (durationMs > 0L) targetPos.coerceAtMost(durationMs) else targetPos)
                            },
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("player_forward_10")
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Forward10,
                                contentDescription = "Forward 10 seconds",
                                tint = Color.White,
                                modifier = Modifier.size(28.dp)
                            )
                        }

                        IconButton(
                            onClick = {
                                if (exoPlayer.hasNextMediaItem()) exoPlayer.seekToNextMediaItem()
                            },
                            enabled = exoPlayer.hasNextMediaItem(),
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("player_next_button")
                        ) {
                            Icon(
                                imageVector = Icons.Filled.SkipNext,
                                contentDescription = "Next track",
                                tint = if (exoPlayer.hasNextMediaItem()) Color.White else Color(0xFF4F5B70),
                                modifier = Modifier.size(28.dp)
                            )
                        }

                        IconButton(
                            onClick = {
                                repeatMode = when (repeatMode) {
                                    Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                                    Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                                    else -> Player.REPEAT_MODE_OFF
                                }
                                exoPlayer.repeatMode = repeatMode
                            },
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("player_loop_button")
                        ) {
                            val icon = if (repeatMode == Player.REPEAT_MODE_ONE) {
                                Icons.Filled.RepeatOne
                            } else {
                                Icons.Filled.Repeat
                            }
                            Icon(
                                imageVector = icon,
                                contentDescription = "Cycle repeat mode",
                                tint = if (repeatMode != Player.REPEAT_MODE_OFF) {
                                    MaterialTheme.colorScheme.secondary
                                } else {
                                    Color(0xFF9BA7BE)
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    // Active Modal Sheets
    when (activeSheet) {
        ActivePlayerSheet.EQUALIZER -> EqualizerSheet(
            fxState = fxState,
            onDismiss = { activeSheet = ActivePlayerSheet.NONE },
            onToggleEnabled = audioFxController::setEnabled,
            onSelectPreset = audioFxController::applyPreset,
            onBandLevelChange = audioFxController::setBandLevel,
            onBassChange = audioFxController::setBassStrength,
            onLoudnessGainChange = audioFxController::setLoudnessGain
        )
        ActivePlayerSheet.TRACKS -> TracksAndSubtitlesSheet(
            audioTracks = audioTracks,
            subtitleTracks = subtitleTracks,
            subtitlesDisabled = subtitlesDisabled,
            onSelectAudioTrack = { opt ->
                val group = currentTracksSnapshot.groups.getOrNull(opt.groupIndex)
                if (group != null) {
                    exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters
                        .buildUpon()
                        .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, opt.trackIndex))
                        .build()
                }
            },
            onSelectSubtitleTrack = { opt ->
                val group = currentTracksSnapshot.groups.getOrNull(opt.groupIndex)
                if (group != null) {
                    subtitlesDisabled = false
                    exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters
                        .buildUpon()
                        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                        .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, opt.trackIndex))
                        .build()
                }
            },
            onDisableSubtitles = {
                subtitlesDisabled = true
                exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters
                    .buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                    .build()
            },
            onPickExternalSubtitle = {
                subtitlePickerLauncher.launch(
                    arrayOf("application/x-subrip", "text/vtt", "text/plain", "*/*")
                )
            },
            onDismiss = { activeSheet = ActivePlayerSheet.NONE }
        )
        ActivePlayerSheet.QUEUE -> PlaylistQueueSheet(
            queue = queue,
            currentIndex = safeIndex,
            onSelectIndex = onSelectQueueIndex,
            onRemoveIndex = onRemoveQueueIndex,
            onAddMoreLocalMedia = {
                addQueueMediaLauncher.launch(
                    arrayOf("video/*", "audio/*", "application/x-matroska", "application/ogg")
                )
            },
            onDismiss = { activeSheet = ActivePlayerSheet.NONE }
        )
        ActivePlayerSheet.TOOLS -> PlayerToolsAndInfoSheet(
            playbackSpeed = playbackSpeed,
            abLoopState = abLoopState,
            currentPositionMs = positionMs,
            sleepTimerRemainingMs = sleepTimerRemainingMs,
            technicalInfo = technicalInfo,
            isAudioOnlyMode = activeTarget.isAudioOnly,
            onSpeedChange = { spd ->
                playbackSpeed = spd
                exoPlayer.setPlaybackSpeed(spd)
            },
            onSetLoopPointA = { abLoopState = AbLoopState(pointA = positionMs, pointB = null) },
            onSetLoopPointB = {
                val a = abLoopState.pointA
                if (a != null && positionMs > a + 400L) {
                    abLoopState = AbLoopState(pointA = a, pointB = positionMs)
                }
            },
            onClearAbLoop = { abLoopState = AbLoopState() },
            onSelectSleepMinutes = { mins ->
                sleepTimerRemainingMs = mins?.let { it * 60_000L }
            },
            onDismiss = { activeSheet = ActivePlayerSheet.NONE }
        )
        ActivePlayerSheet.NONE -> Unit
    }
}
