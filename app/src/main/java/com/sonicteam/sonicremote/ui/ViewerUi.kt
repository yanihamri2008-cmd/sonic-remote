package com.sonicteam.sonicremote.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.sonicteam.sonicremote.R
import com.sonicteam.sonicremote.rtc.ViewerEngine
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoFrame
import org.webrtc.VideoSink

/** Zoom / pan / geometry of the remote screen inside the viewer. */
class ViewState {
    var scale by mutableFloatStateOf(1f)
    var offset by mutableStateOf(Offset.Zero)
    var size by mutableStateOf(IntSize.Zero)
    var frameW by mutableIntStateOf(0)
    var frameH by mutableIntStateOf(0)
    var frameRot by mutableIntStateOf(0)

    fun clampOffset() {
        val mx = size.width * (scale - 1f) / 2f
        val my = size.height * (scale - 1f) / 2f
        offset = Offset(offset.x.coerceIn(-mx, mx), offset.y.coerceIn(-my, my))
    }

    fun zoomBy(f: Float) {
        scale = (scale * f).coerceIn(1f, 6f)
        clampOffset()
    }

    fun reset() {
        scale = 1f
        offset = Offset.Zero
    }
}

/** Rotates every frame by [degrees] (multiples of 90) before it reaches the renderer. */
class RotatingSink(private val target: VideoSink) : VideoSink {
    @Volatile
    var degrees = 0

    override fun onFrame(frame: VideoFrame) {
        val d = degrees
        if (d == 0) {
            target.onFrame(frame)
            return
        }
        frame.buffer.retain()
        val rotated = VideoFrame(frame.buffer, (frame.rotation + d) % 360, frame.timestampNs)
        target.onFrame(rotated)
        rotated.release()
    }
}

/** Maps a touch point (container coordinates) to normalized coordinates of the Agent's screen. */
private fun toRemote(vs: ViewState, p: Offset): Pair<Float, Float>? {
    val w = vs.size.width.toFloat()
    val h = vs.size.height.toFloat()
    if (w <= 0f || h <= 0f || vs.frameW <= 0 || vs.frameH <= 0) return null
    val s = vs.scale
    val cx = w / 2f
    val cy = h / 2f
    val qx = cx + (p.x - cx - vs.offset.x) / s // undo zoom/pan
    val qy = cy + (p.y - cy - vs.offset.y) / s
    val rot = ((vs.frameRot % 360) + 360) % 360
    val swapped = rot % 180 != 0
    val dw = if (swapped) vs.frameH else vs.frameW
    val dh = if (swapped) vs.frameW else vs.frameH
    val aspect = dw.toFloat() / dh
    val rw: Float
    val rh: Float
    if (w / h > aspect) { rh = h; rw = rh * aspect } else { rw = w; rh = rw / aspect }
    val u = (qx - (w - rw) / 2f) / rw
    val v = (qy - (h - rh) / 2f) / rh
    if (u < 0f || u > 1f || v < 0f || v > 1f) return null // touched the black bars
    return when (rot) { // undo the display rotation to get back to the Agent's own screen
        90 -> v to (1f - u)
        180 -> (1f - u) to (1f - v)
        270 -> (1f - v) to u
        else -> u to v
    }
}

@Composable
fun RemoteVideo(engine: ViewerEngine, rotation: Int, vs: ViewState, modifier: Modifier = Modifier) {
    val track = engine.remoteTrack
    val context = LocalContext.current
    val renderer = remember { SurfaceViewRenderer(context) }
    val sink = remember { RotatingSink(renderer) }
    SideEffect { sink.degrees = rotation }
    DisposableEffect(Unit) {
        renderer.init(engine.eglBase.eglBaseContext, object : RendererCommon.RendererEvents {
            override fun onFirstFrameRendered() {}
            override fun onFrameResolutionChanged(videoWidth: Int, videoHeight: Int, rotation: Int) {
                vs.frameW = videoWidth
                vs.frameH = videoHeight
                vs.frameRot = rotation
            }
        })
        renderer.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
        renderer.setEnableHardwareScaler(true)
        onDispose { renderer.release() }
    }
    DisposableEffect(track) {
        track?.addSink(sink)
        onDispose { track?.removeSink(sink) }
    }
    AndroidView(factory = { renderer }, modifier = modifier)
}

/** The remote screen with pinch-zoom/pan (View mode) or tap/swipe forwarding (Control mode). */
@Composable
fun RemoteScreenView(engine: ViewerEngine, vs: ViewState, rotation: Int, controlMode: Boolean, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .onSizeChanged { vs.size = it; vs.clampOffset() }
            .clipToBounds()
            .background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.fillMaxSize().graphicsLayer(
                scaleX = vs.scale, scaleY = vs.scale,
                translationX = vs.offset.x, translationY = vs.offset.y,
            )
        ) {
            if (engine.remoteTrack != null) RemoteVideo(engine, rotation, vs, Modifier.fillMaxSize())
        }

        val gestures = if (!controlMode) {
            Modifier.pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    vs.scale = (vs.scale * zoom).coerceIn(1f, 6f)
                    vs.offset += pan
                    vs.clampOffset()
                }
            }
        } else {
            Modifier
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { p -> toRemote(vs, p)?.let { engine.sendTap(it.first, it.second) } },
                        onLongPress = { p -> toRemote(vs, p)?.let { engine.sendLong(it.first, it.second) } },
                    )
                }
                .pointerInput(Unit) {
                    var start: Offset? = null
                    var last = Offset.Zero
                    var t0 = 0L
                    detectDragGestures(
                        onDragStart = { start = it; last = it; t0 = System.currentTimeMillis() },
                        onDrag = { change, _ -> last = change.position },
                        onDragEnd = {
                            val s = start
                            if (s != null) {
                                val a = toRemote(vs, s)
                                val b = toRemote(vs, last)
                                if (a != null && b != null) {
                                    engine.sendSwipe(a.first, a.second, b.first, b.second, System.currentTimeMillis() - t0)
                                }
                            }
                            start = null
                        },
                        onDragCancel = { start = null },
                    )
                }
        }
        Box(Modifier.fillMaxSize().then(gestures))

        if (engine.state != ViewerEngine.State.Live) {
            val msg = when (engine.state) {
                ViewerEngine.State.Failed -> R.string.viewer_failed
                ViewerEngine.State.Connecting -> R.string.viewer_connecting
                else -> R.string.viewer_waiting
            }
            Text(
                stringResource(msg),
                color = if (engine.state == ViewerEngine.State.Failed) SonicColors.Danger else SonicColors.Silver,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(24.dp),
            )
        }
    }
}

@Composable
private fun ToolButton(text: String, selected: Boolean = false, onClick: () -> Unit) {
    val pad = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
    if (selected) {
        Button(onClick = onClick, contentPadding = pad) { Text(text, fontSize = 13.sp) }
    } else {
        OutlinedButton(onClick = onClick, contentPadding = pad) { Text(text, fontSize = 13.sp) }
    }
}

@Composable
private fun ViewerToolbar(
    vs: ViewState,
    controlMode: Boolean,
    onMode: (Boolean) -> Unit,
    onRotate: () -> Unit,
    fullscreen: Boolean,
    onFullscreen: () -> Unit,
    engine: ViewerEngine,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ToolButton(stringResource(R.string.vw_view_mode), selected = !controlMode) { onMode(false) }
            ToolButton(stringResource(R.string.vw_control_mode), selected = controlMode) { onMode(true) }
            ToolButton("+") { vs.zoomBy(1.3f) }
            ToolButton("−") { vs.zoomBy(1f / 1.3f) }
            ToolButton(stringResource(R.string.vw_rotate)) { onRotate() }
            ToolButton(stringResource(R.string.vw_reset)) { vs.reset() }
            ToolButton(stringResource(if (fullscreen) R.string.vw_close else R.string.vw_fullscreen)) { onFullscreen() }
        }
        if (controlMode) {
            if (!engine.controlAllowed) {
                Text(stringResource(R.string.vw_control_waiting), color = SonicColors.Warning, fontSize = 13.sp)
            } else {
                Text(stringResource(R.string.vw_control_hint), color = SonicColors.Silver, fontSize = 13.sp)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ToolButton(stringResource(R.string.key_back)) { engine.sendKey("back") }
                    ToolButton(stringResource(R.string.key_home)) { engine.sendKey("home") }
                    ToolButton(stringResource(R.string.key_recents)) { engine.sendKey("recents") }
                }
            }
        }
    }
}

@Composable
fun TerminalPanel(engine: ViewerEngine) {
    var input by rememberSaveable { mutableStateOf("") }
    InfoCard {
        Text(stringResource(R.string.term_title), color = SonicColors.Accent, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
        if (!engine.termAllowed) {
            Text(stringResource(R.string.term_waiting), color = SonicColors.Warning, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
            return@InfoCard
        }
        Text(stringResource(R.string.term_hint), color = SonicColors.Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
        Box(
            Modifier.fillMaxWidth().height(180.dp).padding(top = 8.dp).clip(RoundedCornerShape(10.dp)).background(Color.Black)
        ) {
            val scroll = rememberScrollState()
            LaunchedEffect(engine.termLog.size) { scroll.animateScrollTo(scroll.maxValue) }
            Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(10.dp)) {
                engine.termLog.forEach { line ->
                    Text(line, color = if (line.startsWith("$ ")) SonicColors.Accent else Color(0xFFD7DEE9), fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                }
            }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("help", "info", "battery", "network", "storage", "memory", "screen").forEach { c ->
                OutlinedButton(onClick = { engine.runTerm(c) }, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp)) {
                    Text(c, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it.take(40) },
                singleLine = true,
                placeholder = { Text(stringResource(R.string.term_input)) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { engine.runTerm(input); input = "" }),
                modifier = Modifier.weight(1f),
            )
            Button(onClick = { engine.runTerm(input); input = "" }) { Text(stringResource(R.string.term_send)) }
        }
    }
}

@Composable
fun ViewerPanel(engine: ViewerEngine) {
    val vs = remember { ViewState() }
    var rotation by rememberSaveable { mutableIntStateOf(0) }
    var controlMode by rememberSaveable { mutableStateOf(false) }
    var full by rememberSaveable { mutableStateOf(false) }
    val rotate = { rotation = (rotation + 90) % 360; vs.reset() }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (!full) {
            RemoteScreenView(
                engine, vs, rotation, controlMode,
                Modifier.fillMaxWidth().height(440.dp).clip(RoundedCornerShape(18.dp)),
            )
        } else {
            Box(Modifier.fillMaxWidth().height(80.dp), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.vw_in_fullscreen), color = SonicColors.Muted)
            }
        }
        ViewerToolbar(vs, controlMode, { controlMode = it }, rotate, full, { full = !full }, engine)
        TerminalPanel(engine)
    }

    if (full) {
        Dialog(
            onDismissRequest = { full = false },
            properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
        ) {
            Box(Modifier.fillMaxSize().background(Color.Black)) {
                RemoteScreenView(engine, vs, rotation, controlMode, Modifier.fillMaxSize())
                Column(
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color(0xCC0C1019)).padding(12.dp),
                ) {
                    ViewerToolbar(vs, controlMode, { controlMode = it }, rotate, true, { full = false }, engine)
                }
            }
        }
    }
}
