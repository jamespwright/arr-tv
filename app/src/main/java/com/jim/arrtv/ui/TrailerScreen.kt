package com.jim.arrtv.ui

import android.annotation.SuppressLint
import android.graphics.Color as AndroidColor
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import kotlinx.coroutines.delay
import java.util.Locale

/** How long the title, progress and hints stay up after a key press while the trailer plays. */
private const val OVERLAY_MS = 4_000L

private const val SKIP_SECONDS = 10

/** Full screen over the page it was opened from. Back closes it. */
@Composable
fun TrailerScreen(state: TrailerState, onFailed: (String) -> Unit, onClose: () -> Unit) {
    // Stop the sound if the TV goes to the home screen or to sleep with a trailer playing.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val close by rememberUpdatedState(onClose)
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) close() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    val videoId = state.videoId
    val videos = state.videos
    when {
        videoId != null -> YouTubePlayer(videoId, state.title, onFailed, onClose)
        videos is Loadable.Loading -> TrailerMessage("Finding a trailer for ${state.title}…", null)
        videos is Loadable.Error -> TrailerMessage("Couldn't find a trailer: ${videos.message}", onClose)
        state.index == 0 -> TrailerMessage("Couldn't find a trailer for ${state.title}.", onClose)
        else -> TrailerMessage("YouTube won't let any of the trailers for ${state.title} play outside its own app.", onClose)
    }
}

@Composable
private fun TrailerMessage(text: String, onBack: (() -> Unit)?) {
    val focus = remember { FocusRequester() }
    Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Text(text, textAlign = TextAlign.Center, color = Color.White)
            if (onBack != null) Button(onClick = onBack, modifier = Modifier.focusRequester(focus)) { Text("Back") }
        }
    }
    LaunchedEffect(onBack != null) { if (onBack != null) runCatching { focus.requestFocus() } }
}

/**
 * YouTube's own embedded player, in a WebView that never takes focus, so the remote's keys come here:
 * OK (or play/pause) pauses, left and right skip 10 seconds, and Back closes.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun YouTubePlayer(videoId: String, title: String, onFailed: (String) -> Unit, onClose: () -> Unit) {
    // Not keyed on the video: the page's callbacks are set up once, so they have to keep writing to these.
    var playing by remember { mutableStateOf(false) }
    var position by remember { mutableFloatStateOf(0f) }
    var duration by remember { mutableFloatStateOf(0f) }
    var keyPresses by remember { mutableIntStateOf(0) }
    var recentKey by remember { mutableStateOf(true) }
    val player = remember { PlayerView() }
    val focus = remember { FocusRequester() }
    val failed by rememberUpdatedState(onFailed)
    val ended by rememberUpdatedState(onClose)

    fun run(script: String) = player.view?.evaluateJavascript(script, null)

    LaunchedEffect(keyPresses) {
        recentKey = true
        delay(OVERLAY_MS)
        recentKey = false
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(focus)
            .focusable()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.Back -> return@onPreviewKeyEvent false // closes the dialog
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter, Key.MediaPlayPause, Key.Spacebar -> run("toggle()")
                    Key.MediaPlay -> run("player.playVideo()")
                    Key.MediaPause -> run("player.pauseVideo()")
                    Key.DirectionLeft, Key.MediaRewind -> run("skip(-$SKIP_SECONDS)")
                    Key.DirectionRight, Key.MediaFastForward -> run("skip($SKIP_SECONDS)")
                    else -> {} // up and down just bring the overlay back
                }
                keyPresses++
                true
            },
    ) {
        AndroidView(
            factory = { context ->
                WebView(context).apply {
                    // AndroidView's default is wrap_content, which makes WebView give the page a height of 0.
                    layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                    isFocusable = false
                    isFocusableInTouchMode = false
                    setBackgroundColor(AndroidColor.BLACK)
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.mediaPlaybackRequiresUserGesture = false
                    webChromeClient = WebChromeClient()
                    val main = Handler(Looper.getMainLooper())
                    // Called from the page, on a WebView thread.
                    addJavascriptInterface(
                        object {
                            @JavascriptInterface
                            fun progress(state: Int, current: Double, total: Double) = main.post {
                                playing = state == PLAYING || state == BUFFERING
                                position = current.toFloat()
                                duration = total.toFloat()
                                if (state == ENDED) ended()
                            }

                            @JavascriptInterface
                            fun error(id: String, code: Int) = main.post { failed(id) }
                        },
                        "Android",
                    )
                    player.view = this
                }
            },
            update = { view ->
                if (view.tag != videoId) {
                    view.tag = videoId
                    playing = false
                    position = 0f
                    duration = 0f
                    view.loadDataWithBaseURL(ORIGIN, playerPage(videoId), "text/html", "utf-8", null)
                }
            },
            onRelease = { view ->
                view.loadUrl("about:blank")
                view.destroy()
                player.view = null
            },
            modifier = Modifier.fillMaxSize(),
        )

        if (recentKey || !playing) {
            Overlay(title, playing, position, duration, Modifier.align(Alignment.BottomCenter))
        }
    }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
}

private class PlayerView {
    var view: WebView? = null
}

@Composable
private fun Overlay(title: String, playing: Boolean, position: Float, duration: Float, modifier: Modifier) {
    Column(
        modifier
            .fillMaxWidth()
            // Solid at the bottom, to cover the "More videos" bar YouTube shows while paused.
            .background(Brush.verticalGradient(0f to Color.Transparent, 0.4f to Color.Black))
            .padding(horizontal = 64.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(if (playing) title else "$title · Paused", style = MaterialTheme.typography.titleLarge, color = Color.White)
        Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(Color.White.copy(alpha = 0.3f))) {
            if (duration > 0) {
                Box(Modifier.fillMaxWidth((position / duration).coerceIn(0f, 1f)).height(4.dp).background(Color.White))
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                "OK ${if (playing) "pauses" else "plays"} · ◀ ▶ skip $SKIP_SECONDS seconds · Back closes",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.7f),
            )
            if (duration > 0) {
                Text("${clock(position)} / ${clock(duration)}", style = MaterialTheme.typography.bodyMedium, color = Color.White)
            }
        }
    }
}

private fun clock(seconds: Float): String {
    val s = seconds.toInt()
    return "%d:%02d".format(Locale.ROOT, s / 60, s % 60)
}

/** YouTube's player states, as sent to the page's onStateChange. */
private const val ENDED = 0
private const val PLAYING = 1
private const val BUFFERING = 3

/**
 * YouTube's embedded player refuses to play (error 153) without a referrer, so the page is given a web
 * address of its own. Nothing is ever fetched from it.
 */
private const val ORIGIN = "https://arrtv.local"

/** YouTube's iframe player API, with its own controls hidden: the overlay above stands in for them. */
private fun playerPage(videoId: String) = """
<!DOCTYPE html>
<html>
<head>
<meta name="viewport" content="width=device-width, initial-scale=1">
<meta name="referrer" content="strict-origin-when-cross-origin">
<style>
html, body { margin: 0; width: 100%; height: 100%; background: #000; overflow: hidden; }
#player { position: fixed; top: 0; left: 0; width: 100vw; height: 100vh; display: block; border: 0; }
</style>
</head>
<body>
<div id="player"></div>
<script>
var player;
var tag = document.createElement('script');
tag.src = 'https://www.youtube.com/iframe_api';
document.head.appendChild(tag);
function onYouTubeIframeAPIReady() {
  player = new YT.Player('player', {
    videoId: '$videoId',
    width: '100%',
    height: '100%',
    playerVars: { autoplay: 1, controls: 0, disablekb: 1, fs: 0, rel: 0, iv_load_policy: 3, playsinline: 1, cc_load_policy: 0, origin: '$ORIGIN' },
    events: {
      onReady: function (e) { e.target.playVideo(); },
      onStateChange: function (e) { if (e.data === 1) hideCaptions(); report(); },
      onError: function (e) { Android.error('$videoId', e.data); }
    }
  });
  setInterval(report, 500);
}
// YouTube turns captions on by itself (following the TV's caption setting), and only once playback starts.
function hideCaptions() {
  player.unloadModule('captions');
  player.unloadModule('cc');
}
function report() {
  if (player && player.getPlayerState) Android.progress(player.getPlayerState(), player.getCurrentTime(), player.getDuration());
}
function toggle() {
  if (!player || !player.getPlayerState) return;
  if (player.getPlayerState() === 1) player.pauseVideo(); else player.playVideo();
}
function skip(seconds) {
  if (!player || !player.getCurrentTime) return;
  player.seekTo(Math.max(0, player.getCurrentTime() + seconds), true);
}
</script>
</body>
</html>
"""
