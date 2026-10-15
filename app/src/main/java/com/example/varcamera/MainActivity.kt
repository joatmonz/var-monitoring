package com.example.varcamera

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.ui.draw.alpha
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.FastRewind
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.SlowMotionVideo
import androidx.compose.material.icons.rounded.ArrowDropUp
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material.icons.rounded.ZoomIn
import androidx.compose.material.icons.rounded.ZoomOutMap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import androidx.compose.ui.viewinterop.AndroidView
import com.example.varcamera.recording.ClipStore
import com.example.varcamera.review.ClipView
import com.example.varcamera.review.MjpegClip
import com.example.varcamera.review.ReviewPlayerManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "VARCamera"
        private const val PERMISSION_REQUEST_CODE = 1001
    }

    private var reviewPlayerManager: ReviewPlayerManager? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        com.example.varcamera.diag.DiagnosticLog.init(this)

        // Keep screen always on
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Full screen: every row of pixels counts on a phone held sideways.
        androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        // Set the XML layout
        setContentView(R.layout.activity_main)
        supportFragmentManager.addOnBackStackChangedListener(backStackListener)

        // Request runtime permissions first, then load camera fragment
        requestPermissionsIfNeeded()
    }

    private fun requestPermissionsIfNeeded() {
        val permissionsNeeded = mutableListOf<String>()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            permissionsNeeded.add(Manifest.permission.CAMERA)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            permissionsNeeded.add(Manifest.permission.RECORD_AUDIO)
        }

        if (permissionsNeeded.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, permissionsNeeded.toTypedArray(), PERMISSION_REQUEST_CODE)
        } else {
            // Permissions already granted, load camera
            loadCameraFragment()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            // Load camera fragment regardless (camera permission helps but USB access is separate)
            loadCameraFragment()
        }
    }

    private fun loadCameraFragment() {
        val existingFragment = supportFragmentManager.findFragmentByTag("camera_fragment")
        if (existingFragment == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.main_container, UVCCameraFragment.newInstance(), "camera_fragment")
                .commit()
        }
    }

    /**
     * Called by UVCCameraFragment when the user taps REVIEW.
     */
    fun switchToReview(clips: List<File>, clipStore: ClipStore) {
        if (clips.isEmpty()) {
            Toast.makeText(this, "No clips yet - press REC first", Toast.LENGTH_SHORT).show()
            return
        }

        reviewPlayerManager = ReviewPlayerManager().apply { loadClips(clips) }

        val reviewFragment = ReviewFragment.newInstance()
        reviewFragment.reviewPlayerManager = reviewPlayerManager!!
        reviewFragment.clipStore = clipStore

        // Added on top rather than replacing the live screen: the camera fragment stays
        // alive, so the capture card keeps streaming and the buffer keeps recording
        // while the referee reviews.
        cameraFragment()?.setReviewShowing(true)
        supportFragmentManager.beginTransaction()
            .add(R.id.main_container, reviewFragment, "review_fragment")
            .addToBackStack("review")
            .commit()
    }

    /**
     * Return to live camera view from review.
     */
    fun returnToLive() {
        supportFragmentManager.popBackStack()
    }

    private fun cameraFragment(): UVCCameraFragment? =
        supportFragmentManager.findFragmentByTag("camera_fragment") as? UVCCameraFragment

    /** Covers both the LIVE button and the system back gesture. */
    private val backStackListener = androidx.fragment.app.FragmentManager.OnBackStackChangedListener {
        if (supportFragmentManager.backStackEntryCount == 0) {
            reviewPlayerManager?.release()
            reviewPlayerManager = null
            cameraFragment()?.setReviewShowing(false)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        reviewPlayerManager?.release()
    }
}

// ============================================================================
// REVIEW FRAGMENT
// ============================================================================

class ReviewFragment : androidx.fragment.app.Fragment() {

    companion object {
        fun newInstance(): ReviewFragment = ReviewFragment()
    }

    lateinit var reviewPlayerManager: ReviewPlayerManager
    lateinit var clipStore: ClipStore

    override fun onCreateView(
        inflater: android.view.LayoutInflater,
        container: android.view.ViewGroup?,
        savedInstanceState: Bundle?
    ): android.view.View {
        return androidx.compose.ui.platform.ComposeView(requireContext()).apply {
            // The live screen is still underneath; touches must not fall through to it.
            isClickable = true
            setContent {
                VarTheme {
                    ReviewScreen(
                        reviewPlayerManager = reviewPlayerManager,
                        onReturnToLive = {
                            (activity as? MainActivity)?.returnToLive()
                        },
                        onDeleteClip = { file -> clipStore.delete(file) }
                    )
                }
            }
        }
    }
}

// ============================================================================
// REVIEW SCREEN
// ============================================================================

@Composable
fun ReviewScreen(
    reviewPlayerManager: ReviewPlayerManager,
    onReturnToLive: () -> Unit,
    onDeleteClip: (File) -> Unit
) {
    var currentSpeed by remember { mutableStateOf(1.0f) }
    var isLooping by remember { mutableStateOf(false) }
    var isPlaying by remember { mutableStateOf(false) }
    var scale by remember { mutableStateOf(1f) }
    var offsetX by remember { mutableStateOf(0f) }
    var offsetY by remember { mutableStateOf(0f) }
    var showGallery by remember { mutableStateOf(false) }

    var positionMs by remember { mutableStateOf(0L) }
    var durationMs by remember { mutableStateOf(0L) }
    var clipIndex by remember { mutableStateOf(0) }
    var timelineExpanded by remember { mutableStateOf(false) }
    var filmstrip by remember { mutableStateOf<List<ImageBitmap>>(emptyList()) }
    var pendingDeleteIndex by remember { mutableStateOf<Int?>(null) }
    var controlsVisible by remember { mutableStateOf(true) }
    var timecode by remember { mutableStateOf("0:00.00") }
    var durationTimecode by remember { mutableStateOf("0:00.00") }
    var range by remember { mutableStateOf(ReviewPlayerManager.LoopRange(0L, 0L, false, false, -1L)) }
    var clipInfo by remember { mutableStateOf<ReviewPlayerManager.ClipInfo?>(null) }

    // The mark span is a setup choice, not a per-incident one, so it is remembered.
    val preferences = androidx.compose.ui.platform.LocalContext.current
        .getSharedPreferences("review", android.content.Context.MODE_PRIVATE)
    var markSpanMs by remember {
        mutableStateOf(preferences.getLong("mark_span_ms", ReviewPlayerManager.DEFAULT_MARK_SPAN_MS))
    }
    LaunchedEffect(markSpanMs) {
        reviewPlayerManager.setMarkSpan(markSpanMs)
        preferences.edit().putLong("mark_span_ms", markSpanMs).apply()
    }
    var soundOn by remember { mutableStateOf(preferences.getBoolean("sound_on", true)) }
    LaunchedEffect(soundOn) {
        reviewPlayerManager.setSoundOn(soundOn)
        preferences.edit().putBoolean("sound_on", soundOn).apply()
    }

    LaunchedEffect(Unit) {
        while (true) {
            positionMs = reviewPlayerManager.displayPositionMs()
            durationMs = reviewPlayerManager.getDurationMs().coerceAtLeast(0L)
            clipIndex = reviewPlayerManager.currentClipIndex()
            isPlaying = reviewPlayerManager.isPlaying()
            timecode = reviewPlayerManager.displayTimecode()
            durationTimecode = reviewPlayerManager.durationTimecode()
            range = reviewPlayerManager.loopRange()
            clipInfo = reviewPlayerManager.clipInfo()
            // Fast enough that every frame step shows its new timecode at once.
            delay(40)
        }
    }

    var clips by remember { mutableStateOf(reviewPlayerManager.clips) }
    LaunchedEffect(clipIndex, clips) {
        val file = clips.getOrNull(clipIndex)
        filmstrip = if (file == null) emptyList() else withContext(Dispatchers.IO) { loadFilmstrip(file) }
    }

    // With the controls up, the picture shrinks into the space between them so the
    // frame being scrubbed to is never hidden; with them hidden it fills the screen.
    var topBarHeight by remember { mutableStateOf(0f) }
    var bottomPanelHeight by remember { mutableStateOf(0f) }
    val videoInsetTop by animateFloatAsState(
        targetValue = if (controlsVisible) topBarHeight else 0f,
        animationSpec = tween(durationMillis = 250, easing = FastOutSlowInEasing),
        label = "videoInsetTop"
    )
    val videoInsetBottom by animateFloatAsState(
        targetValue = if (controlsVisible) bottomPanelHeight else 0f,
        animationSpec = tween(durationMillis = 250, easing = FastOutSlowInEasing),
        label = "videoInsetBottom"
    )

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {

        AndroidView(
            factory = { ctx -> ClipView(ctx).also { reviewPlayerManager.attachView(it) } },
            // Zoom is drawn from the full-resolution frame instead of scaling the view.
            update = { view ->
                view.setZoom(scale, offsetX, offsetY)
                view.setInsets(videoInsetTop, videoInsetBottom)
            },
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        scale = (scale * zoom).coerceIn(1f, 5f)
                        if (scale > 1f) {
                            offsetX += pan.x
                            offsetY += pan.y
                        } else {
                            offsetX = 0f
                            offsetY = 0f
                        }
                    }
                }
                // A plain tap on the picture hides the controls for an unobstructed view.
                .pointerInput(Unit) {
                    detectTapGestures(onTap = { controlsVisible = !controlsVisible })
                }
        )

        if (controlsVisible) {
            // TOP BAR: what is on screen, zoom state, where the playhead is.
            Row(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .onSizeChanged { topBarHeight = it.height.toFloat() }
                    .fillMaxWidth()
                    .background(VarColors.Panel.copy(alpha = 0.9f))
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Rounded.SlowMotionVideo,
                        contentDescription = null,
                        tint = VarColors.Accent,
                        modifier = Modifier.size(26.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("VAR REVIEW", color = VarColors.Accent, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                    Spacer(modifier = Modifier.width(16.dp))
                    Text(
                        text = reviewHudDetails(clipInfo, clipIndex, clips),
                        color = Color.White.copy(alpha = 0.75f),
                        fontSize = 15.sp
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (scale > 1.01f) {
                        Icon(
                            imageVector = Icons.Rounded.ZoomIn,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "${String.format(java.util.Locale.US, "%.1f", scale)}x",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        CompactButton(
                            text = "ZOOM RESET",
                            icon = Icons.Rounded.ZoomOutMap,
                            onClick = { scale = 1f; offsetX = 0f; offsetY = 0f }
                        )
                        Spacer(modifier = Modifier.width(20.dp))
                    }
                    Text(
                        text = "$timecode / $durationTimecode   ·   ${String.format(java.util.Locale.US, "%.2f", currentSpeed)}x",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp
                    )
                }
            }

            // BOTTOM: timeline, transport, then tools on the left and navigation on the right.
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .onSizeChanged { bottomPanelHeight = it.height.toFloat() }
                    .fillMaxWidth()
                    .background(VarColors.Panel.copy(alpha = 0.9f))
                    .padding(12.dp)
            ) {
                if (showGallery) {
                    SegmentGallery(
                        segments = clips,
                        currentIndex = clipIndex,
                        onSelect = { index ->
                            reviewPlayerManager.jumpToClip(index)
                            showGallery = false
                        },
                        onRequestDelete = { index -> pendingDeleteIndex = index }
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                }

                ClipTimeline(
                    positionMs = positionMs,
                    durationMs = durationMs,
                    timecode = timecode,
                    durationTimecode = durationTimecode,
                    range = range,
                    frames = filmstrip,
                    expanded = timelineExpanded,
                    onToggle = { timelineExpanded = !timelineExpanded },
                    onScrubStart = { reviewPlayerManager.setScrubbing(true) },
                    onSeekDelta = { reviewPlayerManager.seekByMs(it) },
                    onScrubTo = { reviewPlayerManager.scrubTo(it) },
                    onScrubEnd = { reviewPlayerManager.setScrubbing(false) }
                )

                Spacer(modifier = Modifier.height(10.dp))

                // One row, so the panel takes as little of the picture as possible: the
                // set-up tools fold into TOOLS, transport sits in the middle bracketed by
                // In and Out, and leaving the clip stays in the right-hand corner.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val hasAudio = (clipInfo?.audioRate ?: 0) > 0
                    ToolsMenu(
                        range = range,
                        isLooping = isLooping,
                        markSpanMs = markSpanMs,
                        speed = currentSpeed,
                        hasAudio = hasAudio,
                        soundOn = soundOn,
                        onMark = { reviewPlayerManager.setMark() },
                        onClearRange = { reviewPlayerManager.clearRange() },
                        onMarkSpan = { markSpanMs = it },
                        onToggleLoop = { isLooping = reviewPlayerManager.toggleLoop() },
                        onSpeedChange = {
                            currentSpeed = it
                            reviewPlayerManager.setSpeed(it)
                        },
                        onToggleSound = { soundOn = !soundOn }
                    )

                    Spacer(modifier = Modifier.weight(1f))

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        VARButton(
                            text = "[  IN",
                            tone = if (range.inSet) ButtonTone.Range else ButtonTone.Neutral,
                            onClick = { reviewPlayerManager.setIn() },
                            minWidth = 80.dp
                        )
                        VARButton(
                            text = "-1s",
                            icon = Icons.Rounded.FastRewind,
                            onClick = { reviewPlayerManager.seekBackSeconds(1) },
                            minWidth = 88.dp
                        )
                        VARButton(
                            text = "Frame",
                            icon = Icons.Rounded.ChevronLeft,
                            onClick = { reviewPlayerManager.stepBackward() },
                            minWidth = 100.dp
                        )
                        VARButton(
                            text = if (isPlaying) "PAUSE" else "PLAY",
                            icon = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                            tone = ButtonTone.Primary,
                            onClick = { reviewPlayerManager.togglePlayPause() },
                            minWidth = 128.dp
                        )
                        VARButton(
                            text = "Frame",
                            trailingIcon = Icons.Rounded.ChevronRight,
                            onClick = { reviewPlayerManager.stepForward() },
                            minWidth = 100.dp
                        )
                        VARButton(
                            text = "+1s",
                            trailingIcon = Icons.Rounded.FastForward,
                            onClick = { reviewPlayerManager.seekForwardSeconds(1) },
                            minWidth = 88.dp
                        )
                        VARButton(
                            text = "OUT  ]",
                            tone = if (range.outSet) ButtonTone.Range else ButtonTone.Neutral,
                            onClick = { reviewPlayerManager.setOut() },
                            minWidth = 80.dp
                        )
                    }

                    Spacer(modifier = Modifier.weight(1f))

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        VARButton(
                            text = if (showGallery) "CLOSE" else "CLIPS",
                            icon = if (showGallery) Icons.Rounded.Close else Icons.Rounded.VideoLibrary,
                            tone = if (showGallery) ButtonTone.Active else ButtonTone.Neutral,
                            onClick = { showGallery = !showGallery },
                            minWidth = 100.dp
                        )
                        VARButton(
                            text = "LIVE",
                            icon = Icons.Rounded.Videocam,
                            tone = ButtonTone.Live,
                            onClick = onReturnToLive,
                            minWidth = 96.dp
                        )
                    }
                }
            }
        }

        pendingDeleteIndex?.let { index ->
            val file = clips.getOrNull(index)
            AlertDialog(
                onDismissRequest = { pendingDeleteIndex = null },
                containerColor = Color(0xFF1A1A2E),
                title = {
                    Text("Delete clip?", color = Color.White, fontWeight = FontWeight.Bold)
                },
                text = {
                    Text(
                        text = "Are you sure you want to delete the clip recorded at " +
                            "${file?.let { segmentTimeLabel(it) } ?: "-"}? This cannot be undone.",
                        color = Color.White.copy(alpha = 0.85f)
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        reviewPlayerManager.removeClip(index)?.let { onDeleteClip(it) }
                        clips = reviewPlayerManager.clips
                        pendingDeleteIndex = null
                    }) {
                        Text("DELETE", color = Color(0xFFFF5252), fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { pendingDeleteIndex = null }) {
                        Text("CANCEL", color = Color.White)
                    }
                }
            )
        }
    }
}

// ============================================================================
// TIMELINE
// ============================================================================

/**
 * One filmstrip thumbnail covers one second, so this is both the fine scrub scale
 * (drag this far to move a second) and the tile width.
 */
private const val SCRUB_DP_PER_SECOND = 72f

private const val THUMB_HEIGHT_DP = 54f
private const val OVERVIEW_PADDING_DP = 10f

/**
 * Two-level timeline sharing one set of thumbnails.
 *
 * Collapsed, it is an overview: the whole clip squeezed into the bar, where dragging
 * moves the playhead straight to the finger — quick coarse navigation. Tapping opens
 * the filmstrip: one tile per second around the playhead, where dragging moves the
 * strip about a second per tile — precise enough to land on an incident.
 *
 * The two are one layout interpolated by [expansion], so opening stretches every
 * tile from its overview slot into its filmstrip slot rather than jumping.
 */
@Composable
private fun ClipTimeline(
    positionMs: Long,
    durationMs: Long,
    timecode: String,
    durationTimecode: String,
    range: ReviewPlayerManager.LoopRange,
    frames: List<ImageBitmap>,
    expanded: Boolean,
    onToggle: () -> Unit,
    onScrubStart: () -> Unit,
    onSeekDelta: (Long) -> Unit,
    onScrubTo: (Long) -> Unit,
    onScrubEnd: () -> Unit
) {
    val density = LocalDensity.current
    val pxPerSecond = with(density) { SCRUB_DP_PER_SECOND.dp.toPx() }
    val overviewPadding = with(density) { OVERVIEW_PADDING_DP.dp.toPx() }
    val currentExpanded by rememberUpdatedState(expanded)
    val currentDuration by rememberUpdatedState(durationMs)
    val expansion by animateFloatAsState(
        targetValue = if (expanded) 1f else 0f,
        animationSpec = tween(durationMillis = 320, easing = FastOutSlowInEasing),
        label = "timelineExpansion"
    )

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .height((58 + (112 - 58) * expansion).dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xFF12122A))
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var lastX = down.position.x
                    var travelled = 0f
                    var scrubbing = false

                    fun overviewMs(x: Float): Long {
                        val usable = (size.width - 2 * overviewPadding).coerceAtLeast(1f)
                        val fraction = ((x - overviewPadding) / usable).coerceIn(0f, 1f)
                        return (fraction * currentDuration).toLong()
                    }

                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break

                        val dx = change.position.x - lastX
                        if (dx != 0f) {
                            lastX = change.position.x
                            travelled += dx
                            if (!scrubbing && kotlin.math.abs(travelled) > viewConfiguration.touchSlop) {
                                scrubbing = true
                                onScrubStart()
                            }
                            if (scrubbing) {
                                if (currentExpanded) {
                                    onSeekDelta((-dx / pxPerSecond * 1000f).toLong())
                                } else {
                                    onScrubTo(overviewMs(change.position.x))
                                }
                                change.consume()
                            }
                        }
                    }

                    // A drag scrubs; a plain tap opens or closes the filmstrip.
                    if (scrubbing) onScrubEnd() else onToggle()
                }
            }
    ) {
        if (durationMs <= 0) return@BoxWithConstraints

        fun mix(collapsed: Float, open: Float) = collapsed + (open - collapsed) * expansion

        val widthPx = with(density) { maxWidth.toPx() }
        val usable = widthPx - 2 * overviewPadding
        val overviewTile = if (frames.isEmpty()) 0f else usable / frames.size
        val centerX = widthPx / 2f
        val originX = centerX - (positionMs / 1000f) * pxPerSecond

        fun overviewX(ms: Long) = overviewPadding + usable * (ms.toFloat() / durationMs).coerceIn(0f, 1f)
        fun filmstripX(ms: Long) = originX + ms / 1000f * pxPerSecond

        val top = with(density) { mix(9.dp.toPx(), 12.dp.toPx()) }
        val tileHeight = with(density) { mix(40.dp.toPx(), THUMB_HEIGHT_DP.dp.toPx()) }
        val tileWidth = mix(overviewTile, pxPerSecond)

        frames.forEachIndexed { index, frame ->
            val x = mix(overviewPadding + index * overviewTile, originX + index * pxPerSecond)
            if (x + tileWidth >= 0f && x <= widthPx) {
                Image(
                    bitmap = frame,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .offset { IntOffset(x.roundToInt(), top.roundToInt()) }
                        .size(with(density) { tileWidth.toDp() }, with(density) { tileHeight.toDp() })
                        .padding(horizontal = 1.dp)
                        .clip(RoundedCornerShape(3.dp))
                )
            }
        }

        TimelineMarks(
            timeToX = { ms -> mix(overviewX(ms), filmstripX(ms)) },
            top = top,
            bottom = top + tileHeight,
            playheadX = mix(overviewX(positionMs), centerX),
            range = range
        )

        if (expansion > 0.01f) {
            Text(
                text = "$timecode / $durationTimecode",
                color = PLAYHEAD_COLOR,
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 6.dp)
                    .alpha(expansion)
            )
        }
    }
}

private val MARK_SPAN_OPTIONS_MS = listOf(500L, 1000L, 1500L, 2000L, 2500L, 3000L)

private fun formatSpan(ms: Long) = String.format(java.util.Locale.US, "±%.1fs", ms / 1000.0)

private val SPEED_PRESETS = listOf(0.1f, 0.25f, 0.5f, 1f, 1.5f, 2f)

private fun formatSpeed(speed: Float) = String.format(java.util.Locale.US, "%.2fx", speed)

private fun isNormalSpeed(speed: Float) = kotlin.math.abs(speed - 1f) < 0.005f

/**
 * The set-up tools — mark, loop range, loop, speed and sound — folded behind one
 * button so the controls fit in a single row. They are set once per incident rather
 * than tapped constantly, so a menu costs little. While the menu is closed the
 * button shows whatever is switched on, so slow motion or a muted clip never goes
 * unnoticed.
 */
@Composable
private fun ToolsMenu(
    range: ReviewPlayerManager.LoopRange,
    isLooping: Boolean,
    markSpanMs: Long,
    speed: Float,
    hasAudio: Boolean,
    soundOn: Boolean,
    onMark: () -> Unit,
    onClearRange: () -> Unit,
    onMarkSpan: (Long) -> Unit,
    onToggleLoop: () -> Unit,
    onSpeedChange: (Float) -> Unit,
    onToggleSound: () -> Unit
) {
    var open by remember { mutableStateOf(false) }
    Box {
        VARButton(
            text = "TOOLS",
            icon = Icons.Rounded.Tune,
            trailingIcon = if (open) Icons.Rounded.ArrowDropDown else Icons.Rounded.ArrowDropUp,
            tone = if (open) ButtonTone.Active else ButtonTone.Neutral,
            onClick = { open = !open },
            minWidth = 120.dp,
            badges = {
                if (range.markMs >= 0) ToolBadge(Icons.Rounded.PushPin, VarColors.Range)
                if (isLooping) ToolBadge(Icons.Rounded.Repeat, VarColors.Range)
                if (!isNormalSpeed(speed)) {
                    Text(
                        text = formatSpeed(speed),
                        color = VarColors.Accent,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        modifier = Modifier.padding(start = 6.dp)
                    )
                }
                if (hasAudio && !soundOn) ToolBadge(Icons.AutoMirrored.Rounded.VolumeOff, Color.White.copy(alpha = 0.7f))
            }
        )
        // Half see-through, so the picture stays visible behind the menu. The menu's
        // own surface takes the theme's surface colour, so that is made transparent.
        MaterialTheme(
            colorScheme = MaterialTheme.colorScheme.copy(surface = Color.Transparent, surfaceTint = Color.Transparent)
        ) {
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            modifier = Modifier.background(VarColors.Panel.copy(alpha = 0.5f))
        ) {
            // Two columns side by side: wide and short, so it fits above the controls
            // even on a phone held sideways.
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(28.dp)
            ) {
                Column(modifier = Modifier.width(430.dp)) {
                    ToolsSectionTitle("MARK & LOOP")
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        // With a range set, the same button clears it back to the whole clip.
                        VARButton(
                            text = if (range.isWholeClip) "MARK" else "CLEAR",
                            icon = if (range.isWholeClip) Icons.Rounded.PushPin else Icons.Rounded.Close,
                            tone = if (range.isWholeClip) ButtonTone.Neutral else ButtonTone.Range,
                            onClick = { if (range.isWholeClip) onMark() else onClearRange() },
                            minWidth = 140.dp
                        )
                        VARButton(
                            text = if (isLooping) "LOOP ON" else "LOOP",
                            icon = Icons.Rounded.Repeat,
                            tone = if (isLooping) ButtonTone.Range else ButtonTone.Neutral,
                            onClick = onToggleLoop,
                            minWidth = 140.dp
                        )
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    Text("In / Out around the mark", color = Color.White.copy(alpha = 0.6f), fontSize = 13.sp)
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        MARK_SPAN_OPTIONS_MS.forEach { option ->
                            ChoiceChip(
                                text = formatSpan(option),
                                selected = option == markSpanMs,
                                selectedColor = VarColors.Range,
                                onClick = { onMarkSpan(option) },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }

                Column(modifier = Modifier.width(430.dp)) {
                    ToolsSectionTitle("SPEED  ${formatSpeed(speed)}")
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        SPEED_PRESETS.forEach { preset ->
                            ChoiceChip(
                                text = if (preset >= 1f) "${preset.toString().removeSuffix(".0")}x" else formatSpeed(preset),
                                selected = kotlin.math.abs(preset - speed) < 0.005f,
                                selectedColor = VarColors.Accent,
                                onClick = { onSpeedChange(preset) },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                    Slider(
                        value = speed,
                        onValueChange = onSpeedChange,
                        valueRange = 0.1f..2.0f,
                        colors = SliderDefaults.colors(
                            thumbColor = VarColors.Accent,
                            activeTrackColor = VarColors.Accent,
                            inactiveTrackColor = Color(0xFF444466)
                        )
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        VARButton(
                            text = when {
                                !hasAudio -> "NO AUDIO"
                                soundOn -> "SOUND ON"
                                else -> "MUTED"
                            },
                            icon = if (hasAudio && soundOn) {
                                Icons.AutoMirrored.Rounded.VolumeUp
                            } else {
                                Icons.AutoMirrored.Rounded.VolumeOff
                            },
                            tone = if (hasAudio && soundOn) ButtonTone.Active else ButtonTone.Neutral,
                            onClick = onToggleSound,
                            enabled = hasAudio,
                            minWidth = 140.dp
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text("Sound plays at 1x only", color = Color.White.copy(alpha = 0.6f), fontSize = 13.sp)
                    }
                }
            }
        }
        }
    }
}

@Composable
private fun ToolsSectionTitle(text: String) {
    Text(text = text, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
    Spacer(modifier = Modifier.height(8.dp))
}

@Composable
private fun ToolBadge(icon: ImageVector, tint: Color) {
    Icon(
        imageVector = icon,
        contentDescription = null,
        tint = tint,
        modifier = Modifier.padding(start = 6.dp).size(18.dp)
    )
}

/** One option in a row of mutually exclusive choices. */
@Composable
private fun ChoiceChip(
    text: String,
    selected: Boolean,
    selectedColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .height(44.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) selectedColor else VarColors.Surface)
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = if (selected) VarColors.OnAccent else Color.White,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            fontSize = 14.sp
        )
    }
}

/** Small pill button for the top bar, where full-size controls would crowd the HUD. */
@Composable
private fun CompactButton(text: String, onClick: () -> Unit, icon: ImageVector? = null) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(VarColors.Surface)
            .border(1.dp, Color.White.copy(alpha = 0.25f), RoundedCornerShape(16.dp))
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            Icon(imageVector = icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(6.dp))
        }
        Text(text = text, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
    }
}

/** "1080p · 50 fps · MJPEG · 48 kHz audio · Clip 2/3 · 23:56:30" */
private fun reviewHudDetails(info: ReviewPlayerManager.ClipInfo?, clipIndex: Int, clips: List<File>): String {
    val parts = mutableListOf<String>()
    if (info != null && info.height > 0) {
        parts += "${info.height}p"
        parts += "${info.fps} fps"
        parts += "MJPEG"
        parts += if (info.audioRate > 0) "${info.audioRate / 1000} kHz audio" else "no audio"
    }
    if (clips.isNotEmpty()) {
        parts += "Clip ${clipIndex + 1}/${clips.size}"
        clips.getOrNull(clipIndex)?.let { parts += segmentTimeLabel(it) }
    }
    return parts.joinToString("  ·  ")
}

/** Loop range shading, In/Out and mark lines, and the playhead, over either strip. */
@Composable
private fun TimelineMarks(
    timeToX: (Long) -> Float,
    top: Float,
    bottom: Float,
    playheadX: Float,
    range: ReviewPlayerManager.LoopRange
) {
    Canvas(modifier = Modifier.fillMaxSize()) {
        val height = bottom - top
        // Dim everything the loop will not play.
        val shade = Color.Black.copy(alpha = 0.6f)
        if (range.inSet) {
            val inX = timeToX(range.inMs)
            drawRect(shade, Offset(0f, top), Size(inX.coerceAtLeast(0f), height))
            drawLine(LOOP_RANGE_COLOR, Offset(inX, top - 6), Offset(inX, bottom + 6), 4f)
        }
        if (range.outSet) {
            val outX = timeToX(range.outMs)
            drawRect(shade, Offset(outX, top), Size((size.width - outX).coerceAtLeast(0f), height))
            drawLine(LOOP_RANGE_COLOR, Offset(outX, top - 6), Offset(outX, bottom + 6), 4f)
        }
        if (range.markMs >= 0) {
            val markX = timeToX(range.markMs)
            drawLine(MARK_COLOR, Offset(markX, top - 8), Offset(markX, bottom + 8), 5f)
        }
        drawLine(PLAYHEAD_COLOR, Offset(playheadX, top - 6), Offset(playheadX, bottom + 6), 5f)
        drawCircle(PLAYHEAD_COLOR, radius = 6f, center = Offset(playheadX, top - 6))
    }
}

private val PLAYHEAD_COLOR = VarColors.Accent
private val MARK_COLOR = Color(0xFFFFEB3B)
private val LOOP_RANGE_COLOR = VarColors.Range

/** One thumbnail per second of the clip, for the expanded scrubber. */
private fun loadFilmstrip(clipDir: File): List<ImageBitmap> {
    val clip = MjpegClip.open(clipDir) ?: return emptyList()
    return try {
        val count = ((clip.durationMs + 999) / 1000).toInt().coerceIn(1, 40)
        (0 until count).mapNotNull { second ->
            clip.thumbnail(clip.indexAt(second * 1000L))?.asImageBitmap()
        }
    } finally {
        clip.close()
    }
}


// ============================================================================
// CLIP GALLERY
// ============================================================================

/**
 * Horizontal strip of recorded clips so the operator can pick the right moment
 * instead of scrubbing blindly while the referee waits.
 */
@Composable
fun SegmentGallery(
    segments: List<File>,
    currentIndex: Int,
    onSelect: (Int) -> Unit,
    onRequestDelete: (Int) -> Unit
) {
    if (segments.isEmpty()) {
        Text(
            text = "No clips recorded yet",
            color = Color.White.copy(alpha = 0.7f),
            fontSize = 16.sp,
            modifier = Modifier.padding(16.dp)
        )
        return
    }

    val listState = rememberLazyListState()
    LaunchedEffect(currentIndex) {
        if (currentIndex in segments.indices) listState.scrollToItem(currentIndex)
    }

    LazyRow(
        state = listState,
        modifier = Modifier.fillMaxWidth().height(124.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        itemsIndexed(segments) { index, file ->
            SegmentThumbnail(
                file = file,
                isCurrent = index == currentIndex,
                onClick = { onSelect(index) },
                onDelete = { onRequestDelete(index) }
            )
        }
    }
}

@Composable
private fun SegmentThumbnail(
    file: File,
    isCurrent: Boolean,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    var preview by remember(file.path) { mutableStateOf<ClipPreview?>(null) }

    LaunchedEffect(file.path) {
        preview = withContext(Dispatchers.IO) { loadClipPreview(file) }
    }

    Column(
        modifier = Modifier
            .width(150.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (isCurrent) VarColors.ActiveSurface else VarColors.Surface)
            .border(
                width = if (isCurrent) 3.dp else 1.dp,
                color = if (isCurrent) Color(0xFF00D4FF) else Color.White.copy(alpha = 0.2f),
                shape = RoundedCornerShape(10.dp)
            )
            .clickable { onClick() }
            .padding(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(74.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            preview?.image?.let {
                Image(bitmap = it, contentDescription = null, modifier = Modifier.fillMaxSize())
            }
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .size(34.dp)
                    .clip(RoundedCornerShape(bottomStart = 8.dp))
                    .background(Color.Black.copy(alpha = 0.65f))
                    .clickable { onDelete() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.Delete,
                    contentDescription = "Delete clip",
                    tint = Color.White,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
        Spacer(modifier = Modifier.height(5.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = formatClipLength(preview?.durationMs ?: 0L),
                color = Color(0xFF00D4FF),
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = segmentTimeLabel(file),
                color = Color.White.copy(alpha = 0.85f),
                fontSize = 14.sp
            )
        }
    }
}

private data class ClipPreview(val image: ImageBitmap?, val durationMs: Long)

private fun formatClipLength(ms: Long): String =
    if (ms <= 0) "--" else String.format(java.util.Locale.US, "%.0fs", ms / 1000.0)

/** Thumbnail from just before the REVIEW press, where the incident is. */
private fun loadClipPreview(clipDir: File): ClipPreview {
    val clip = MjpegClip.open(clipDir) ?: return ClipPreview(null, 0L)
    return try {
        ClipPreview(
            image = clip.thumbnail(clip.indexAt(clip.durationMs - 1000))?.asImageBitmap(),
            durationMs = clip.durationMs
        )
    } finally {
        clip.close()
    }
}

/** "clip_20260922_182020" -> "18:20:20" */
private fun segmentTimeLabel(file: File): String {
    val stamp = file.name.split("_").getOrNull(2)
    return if (stamp != null && stamp.length >= 6) {
        "${stamp.substring(0, 2)}:${stamp.substring(2, 4)}:${stamp.substring(4, 6)}"
    } else {
        file.name
    }
}

// ============================================================================
// REUSABLE COMPONENTS
// ============================================================================

/**
 * The app's theme, with every control scaled to the screen: the HUD is laid out for
 * the 1280×800 dp tablet, so on a smaller screen (a phone held sideways is about
 * 900×400) everything shrinks in proportion instead of overflowing. It stops at 65%,
 * below which buttons get too small for a thumb.
 */
@Composable
fun VarTheme(content: @Composable () -> Unit) {
    val configuration = androidx.compose.ui.platform.LocalConfiguration.current
    val factor = minOf(
        1f,
        configuration.screenWidthDp / 1280f,
        configuration.screenHeightDp / 720f
    ).coerceAtLeast(0.65f)
    val density = LocalDensity.current
    MaterialTheme {
        CompositionLocalProvider(
            LocalDensity provides androidx.compose.ui.unit.Density(density.density * factor, density.fontScale),
            content = content
        )
    }
}

/**
 * One palette for every control: slate buttons, cyan for the main action and the
 * playhead, amber for the loop range (the colour of In and Out on the timeline),
 * red for live.
 */
object VarColors {
    val Panel = Color(0xFF1A1A2E)
    val Surface = Color(0xFF282C42)
    val Accent = Color(0xFF00D4FF)
    val OnAccent = Color(0xFF04141B)
    val ActiveSurface = Color(0xFF0C3342)
    val Range = Color(0xFFFFA726)
    val RangeSurface = Color(0xFF3D2B10)
    val Live = Color(0xFFE53935)
}

/** What a button means, which decides its colours. */
enum class ButtonTone {
    /** An ordinary tool. */
    Neutral,

    /** The main action on the screen: PLAY, REVIEW. */
    Primary,

    /** A tool that is switched on, such as SOUND. */
    Active,

    /** Loop range tools while a range is set: IN, OUT, LOOP, CLEAR. */
    Range,

    /** Recording and going back to live. */
    Live
}

@Composable
fun VARButton(
    text: String,
    onClick: () -> Unit,
    icon: ImageVector? = null,
    tone: ButtonTone = ButtonTone.Neutral,
    enabled: Boolean = true,
    minWidth: androidx.compose.ui.unit.Dp = 100.dp,
    trailingIcon: ImageVector? = null,
    badges: (@Composable () -> Unit)? = null
) {
    val (container, content, border) = when (tone) {
        ButtonTone.Neutral -> Triple(VarColors.Surface, Color.White, Color.White.copy(alpha = 0.12f))
        ButtonTone.Primary -> Triple(VarColors.Accent, VarColors.OnAccent, VarColors.Accent)
        ButtonTone.Active -> Triple(VarColors.ActiveSurface, VarColors.Accent, VarColors.Accent.copy(alpha = 0.7f))
        ButtonTone.Range -> Triple(VarColors.RangeSurface, VarColors.Range, VarColors.Range.copy(alpha = 0.7f))
        ButtonTone.Live -> Triple(VarColors.Live, Color.White, VarColors.Live)
    }
    Box(
        modifier = Modifier
            .defaultMinSize(minWidth = minWidth, minHeight = 64.dp)
            .alpha(if (enabled) 1f else 0.4f)
            .clip(RoundedCornerShape(12.dp))
            .background(container)
            .border(1.5.dp, border, RoundedCornerShape(12.dp))
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Icon(imageVector = icon, contentDescription = null, tint = content, modifier = Modifier.size(24.dp))
                Spacer(modifier = Modifier.width(8.dp))
            }
            Text(
                text = text,
                color = content,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
                textAlign = TextAlign.Center
            )
            badges?.invoke()
            if (trailingIcon != null) {
                Spacer(modifier = Modifier.width(4.dp))
                Icon(imageVector = trailingIcon, contentDescription = null, tint = content, modifier = Modifier.size(22.dp))
            }
        }
    }
}

fun formatTime(totalSeconds: Int): String {
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return String.format("%02d:%02d", minutes, seconds)
}
