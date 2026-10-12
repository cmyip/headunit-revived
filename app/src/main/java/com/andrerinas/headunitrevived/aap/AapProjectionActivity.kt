package com.andrerinas.headunitrevived.aap

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.TextureView
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.andrerinas.headunitrevived.App
import com.andrerinas.headunitrevived.R
import com.andrerinas.headunitrevived.aap.protocol.messages.TouchEvent
import com.andrerinas.headunitrevived.aap.protocol.messages.VideoFocusEvent
import com.andrerinas.headunitrevived.app.SurfaceActivity
import com.andrerinas.headunitrevived.connection.CommManager
import com.andrerinas.headunitrevived.contract.KeyIntent
import kotlinx.coroutines.launch
import com.andrerinas.headunitrevived.decoder.SoftwareYuvFrameSink
import com.andrerinas.headunitrevived.decoder.VideoDecoder
import com.andrerinas.headunitrevived.decoder.VideoDimensionsListener
import com.andrerinas.headunitrevived.utils.AppLog
import com.andrerinas.headunitrevived.utils.IntentFilters
import com.andrerinas.headunitrevived.view.IProjectionView
import com.andrerinas.headunitrevived.view.GlProjectionView
import com.andrerinas.headunitrevived.view.ProjectionView
import com.andrerinas.headunitrevived.view.TextureProjectionView
import com.andrerinas.headunitrevived.utils.Settings
import com.andrerinas.headunitrevived.view.OverlayTouchView
import com.andrerinas.headunitrevived.utils.HeadUnitScreenConfig
import com.andrerinas.headunitrevived.utils.SystemUI
import com.andrerinas.headunitrevived.aap.AapService
import android.content.IntentFilter
import com.andrerinas.headunitrevived.view.ProjectionViewScaler
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.widget.ImageView
import android.widget.VideoView
import com.bumptech.glide.Glide
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.andrerinas.headunitrevived.main.QuickSettingsFragment
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

class AapProjectionActivity : SurfaceActivity(), IProjectionView.Callbacks, VideoDimensionsListener {

    private enum class OverlayState { STARTING, RECONNECTING, HIDDEN }

    private lateinit var projectionView: IProjectionView
    private val videoDecoder: VideoDecoder by lazy { App.provide(this).videoDecoder }
    private val settings: Settings by lazy { Settings(this) }
    private val cachedKeyCodes: Map<Int, Int> by lazy { settings.keyCodes }
    private var isSurfaceSet = false
    private var overlayState = OverlayState.STARTING
    private val watchdogHandler = android.os.Handler(android.os.Looper.getMainLooper())

    /**
     * Ken Burns scale animation applied to a static image loading screen.
     * Stored in a field so it can be cancelled when the loading overlay is
     * torn down or the activity is destroyed — otherwise the infinite-repeat
     * animator keeps consuming frame callbacks even when the view is gone.
     */
    private var kenBurnsAnimator: ObjectAnimator? = null

    private var initialX = 0f
    private var initialY = 0f
    private var isPotentialGesture = false

    // Activity-local override for fullscreen mode. If non-null, setFullscreen() will use this
    // instead of persisting to Settings. This keeps toggles local to the Activity lifecycle.
    private var activityFullscreenOverride: Settings.FullscreenMode? = null
    private var fpsTextView: TextView? = null
    private var touchOverlayView: OverlayTouchView? = null
    private var currentFps: Int? = null
    private val performanceHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val performanceSampler = PerformanceSampler()
    private val performanceExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "PerformanceSampler").apply {
            priority = Thread.MIN_PRIORITY
        }
    }
    private val performanceSampleInFlight = AtomicBoolean(false)
    private val performanceOverlayRunnable = object : Runnable {
        override fun run() {
            requestPerformanceOverlayUpdate()
            performanceHandler.postDelayed(this, 1000L)
        }
    }

    private var isOrientationReceiverRegistered = false
    private var isNightModeReceiverRegistered = false
    private var isFinishReceiverRegistered = false
    private var isKeyEventReceiverRegistered = false
    private var isSettingsReceiverRegistered = false

    private val videoWatchdogRunnable = object : Runnable {
        override fun run() {
            val loadingOverlay = findViewById<View>(R.id.loading_overlay)
            if (loadingOverlay?.visibility == View.VISIBLE && commManager.isConnected) {
                // If the decoder already rendered something, hide the overlay immediately
                if (videoDecoder.lastFrameRenderedMs > 0) {
                    AppLog.i("Watchdog: Decoder is already rendering frames. Hiding overlay.")
                    hideLoadingOverlay(loadingOverlay)
                    return
                }
                // QDLink-only output never decodes locally, so the decoder stays idle.
                // Video reaching the head unit counts; asking for focus again here
                // would restart Android Auto's stream every 1.5 seconds.
                if (settings.videoOutputMode == Settings.VideoOutputMode.QDLINK &&
                    App.provide(this@AapProjectionActivity).qdLinkBridge.hasAndroidAutoVideo) {
                    AppLog.i("Watchdog: Video is being forwarded to QDLink. Hiding overlay.")
                    hideLoadingOverlay(loadingOverlay)
                    return
                }

                AppLog.w("Watchdog: No video received yet. Requesting Keyframe (Unsolicited Focus)...")
                commManager.send(VideoFocusEvent(gain = true, unsolicited = true))
                watchdogHandler.postDelayed(this, 1500)
            }
        }
    }
    private val reconnectingWatchdog = object : Runnable {
        override fun run() {
            // Only run watchdog if we are actually supposed to be connected
            if (commManager.connectionState.value !is CommManager.ConnectionState.HandshakeComplete) {
                return
            }
            // QDLink-only output has no local frames to time; the head unit shows the picture.
            if (settings.videoOutputMode == Settings.VideoOutputMode.QDLINK) return
            val lastFrame = videoDecoder.lastFrameRenderedMs
            if (lastFrame == 0L) {
                // First frame hasn't arrived yet — handled by the starting overlay
                watchdogHandler.postDelayed(this, 2000)
                return
            }
            val gap = SystemClock.elapsedRealtime() - lastFrame
            if (overlayState == OverlayState.HIDDEN && gap > 10000) {
                showReconnectingOverlay()
            } else if (overlayState == OverlayState.RECONNECTING && gap < 2000) {
                hideReconnectingOverlay()
            }
            watchdogHandler.postDelayed(this, 2000)
        }
    }
    private val exitRunnable = Runnable {
        if (commManager.connectionState.value is CommManager.ConnectionState.Disconnected) {
            AppLog.i("AapProjectionActivity: Reconnect timed out (20s). Finishing activity.")
            hideReconnectingOverlay()
            finish()
        }
    }
    private val watchdogRunnable = Runnable {
        if (!isSurfaceSet) {
            AppLog.w("Watchdog: Surface not set after 2s. Checking view state...")
            checkAndForceSurface()
        }
    }
    private fun checkAndForceSurface() {
        AppLog.i("Watchdog: checkAndForceSurface executing...")
        if (projectionView is TextureView) {
            val tv = projectionView as TextureView
            if (tv.isAvailable) {
                AppLog.w("Watchdog: TextureView IS available. Forcing onSurfaceChanged.")
                onSurfaceChanged(android.view.Surface(tv.surfaceTexture), tv.width, tv.height)
            } else {
                AppLog.e("Watchdog: TextureView NOT available. Vis=${tv.visibility}, W=${tv.width}, H=${tv.height}")
            }
        } else if (projectionView is GlProjectionView) {
             val gles = projectionView as GlProjectionView
             if (gles.isSurfaceValid()) {
                 AppLog.w("Watchdog: GlProjectionView IS valid. Forcing onSurfaceChanged.")
                 onSurfaceChanged(gles.getSurface()!!, gles.width, gles.height)
             } else {
                 AppLog.e("Watchdog: GlProjectionView NOT valid.")
             }
        } else if (projectionView is ProjectionView) {
             val sv = projectionView as ProjectionView
             if (sv.holder.surface.isValid) {
                 AppLog.w("Watchdog: SurfaceView IS valid. Forcing onSurfaceChanged.")
                 onSurfaceChanged(sv.holder.surface, sv.width, sv.height)
             } else {
                 AppLog.e("Watchdog: SurfaceView NOT valid.")
             }
        }
    }

    private val settingsReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val needsViewRecreate = intent.getBooleanExtra(QuickSettingsFragment.EXTRA_NEEDS_VIEW_RECREATE, false)
            val needsAudioRestart = intent.getBooleanExtra(QuickSettingsFragment.EXTRA_NEEDS_AUDIO_RESTART, false)
            val sensorRefresh = intent.getBooleanExtra(QuickSettingsFragment.EXTRA_SENSOR_REFRESH, false)

            if (needsViewRecreate) {
                recreateProjectionView()
            }
            if (sensorRefresh) {
                sendBroadcast(Intent(AapService.ACTION_REFRESH_SENSORS).apply {
                    setPackage(packageName)
                })
            }
            if (needsAudioRestart) {
                sendBroadcast(Intent(AapService.ACTION_RESTART_AUDIO).apply {
                    setPackage(packageName)
                })
            }

            updateDesaturation(com.andrerinas.headunitrevived.utils.NightMode(settings, false).current)

            if (settings.showFpsCounter && fpsTextView == null) {
                setupFpsCounter()
            } else if (!settings.showFpsCounter && fpsTextView != null) {
                fpsTextView?.visibility = View.GONE
                stopPerformanceOverlayUpdates()
            } else if (settings.showFpsCounter && fpsTextView != null) {
                fpsTextView?.visibility = View.VISIBLE
                startPerformanceOverlayUpdates()
            }
        }
    }

    private fun recreateProjectionView() {
        runOnUiThread {
            AppLog.i("Recreating projection view due to settings change...")
            val container = findViewById<FrameLayout>(R.id.container)
            if (::projectionView.isInitialized) {
                videoDecoder.softwareYuvFrameSink = null
                videoDecoder.stop("projectionViewRecreate")
                container.removeView(projectionView as View)
            }
            isSurfaceSet = false
            setupProjectionView()
        }
    }

    private val nightModeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val isNight = intent.getBooleanExtra("isNight", false)
            updateDesaturation(isNight)
        }
    }

    private fun updateDesaturation(isNight: Boolean) {
        if (settings.aaMonochromeEnabled && projectionView is GlProjectionView) {
            val level = if (isNight) settings.aaDesaturationLevel / 100f else 0f
            (projectionView as GlProjectionView).setDesaturation(level)
        } else if (projectionView is GlProjectionView) {
            (projectionView as GlProjectionView).setDesaturation(0f)
        }
    }

    private val orientationReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == AapService.ACTION_ORIENTATION_CHANGED) {
                AppLog.i("AapProjectionActivity: Orientation change broadcast received. Updating.")
                applyOrientationSettings()
            }
        }
    }

    private val keyEventReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val event: KeyEvent? = IntentCompat.getParcelableExtra(intent, KeyIntent.extraEvent, KeyEvent::class.java)
            event?.let {
                onKeyEvent(it.keyCode, it.action == KeyEvent.ACTION_DOWN)
            }
        }
    }

    private val finishReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == "com.andrerinas.headunitrevived.ACTION_FINISH_ACTIVITIES") {
                AppLog.i("AapProjectionActivity: Received finish request. Closing.")
                finish()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        applyOrientationSettings()
        super.onCreate(savedInstanceState)


        setContentView(R.layout.activity_headunit)

        if (settings.showFpsCounter) {
            setupFpsCounter()
        }

        videoDecoder.dimensionsListener = this

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                showExitDialog()
            }
        })

        var isFirstEmission = true
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                commManager.connectionState.collect { state ->
                    val first = isFirstEmission
                    isFirstEmission = false

                    if (first && state is CommManager.ConnectionState.Disconnected) {
                        AppLog.i("AapProjectionActivity: Ignoring initial Disconnected state from StateFlow replay.")
                        return@collect
                    }

                    when (state) {
                        is CommManager.ConnectionState.Disconnected -> {
                            watchdogHandler.removeCallbacksAndMessages(null)
                            if (!state.isClean && !state.isUserExit) {
                                AppLog.w("AapProjectionActivity: Disconnected unexpectedly.")
                                Toast.makeText(this@AapProjectionActivity, getString(R.string.wifi_disconnect_toast), Toast.LENGTH_LONG).show()
                            }
                            // Only finish immediately if the user explicitly exited, it was a clean close, or killOnDisconnect is enabled.
                            if (state.isUserExit || state.isClean || settings.killOnDisconnect) {
                                AppLog.i("AapProjectionActivity: Finishing because state isUserExit=${state.isUserExit}, isClean=${state.isClean}, killOnDisconnect=${settings.killOnDisconnect}")
                                hideReconnectingOverlay()
                                finish()
                            } else {
                                // For unexpected disconnects (especially Wireless), show the reconnecting overlay immediately
                                // and wait up to 20 seconds (or 8 seconds for USB) to see if the connection recovers.
                                val timeoutMs = if (settings.lastConnectionType == Settings.CONNECTION_TYPE_USB) 8000L else 20000L
                                AppLog.i("AapProjectionActivity: Unexpected disconnect. Showing reconnecting overlay and waiting up to ${timeoutMs / 1000}s for recovery.")
                                showReconnectingOverlay()

                                // Re-initialize the first frame listener to hide the reconnecting overlay when video starts flowing
                                videoDecoder.onFirstFrameListener = {
                                    runOnUiThread {
                                        hideReconnectingOverlay()
                                    }
                                }

                                watchdogHandler.removeCallbacks(exitRunnable)
                                watchdogHandler.postDelayed(exitRunnable, timeoutMs)
                            }
                        }
                        is CommManager.ConnectionState.HandshakeComplete -> {
                            watchdogHandler.removeCallbacks(exitRunnable)

                            // Restart the video watchdog so it can request keyframes for the new session
                            watchdogHandler.removeCallbacks(videoWatchdogRunnable)
                            watchdogHandler.postDelayed(videoWatchdogRunnable, 1000)

                            // Lock the resolution so that orientation changes don't cause re-negotiation
                            HeadUnitScreenConfig.lockResolution()

                            // Handshake done. If the surface is already ready (e.g. reconnect
                            // while the activity is in the foreground), start reading immediately.
                            // If not, onSurfaceChanged() will call startReading() when the surface
                            // becomes available.
                            if (isSurfaceSet) {
                                commManager.startReading()
                            }
                        }
                        is CommManager.ConnectionState.TransportStarted -> {
                            watchdogHandler.removeCallbacks(exitRunnable)
                        }
                        else -> {}
                    }
                }
            }
        }

        ContextCompat.registerReceiver(this, finishReceiver, android.content.IntentFilter("com.andrerinas.headunitrevived.ACTION_FINISH_ACTIVITIES"), ContextCompat.RECEIVER_NOT_EXPORTED)
        isFinishReceiverRegistered = true

        AppLog.i("HeadUnit for Android Auto (tm) - Copyright 2011-2015 Michael A. Reid., since 2025 André Rinas All Rights Reserved...")

        val container = findViewById<FrameLayout>(R.id.container)
        setupProjectionView()

        val overlayView = OverlayTouchView(this)
        this.touchOverlayView = overlayView
        overlayView.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        )
        overlayView.isFocusable = true
        overlayView.isFocusableInTouchMode = true

        overlayView.setOnTouchListener { _, event ->
                if (event.action == MotionEvent.ACTION_DOWN) {
                    overlayView.requestFocus()
                }
                sendTouchEvent(event)
                true
            }

        container.addView(overlayView)
        overlayView.requestFocus()
        setFullscreen() // Call setFullscreen here as well

        val loadingOverlay = findViewById<View>(R.id.loading_overlay)

        // [FIX] If we are already connected and frames are flowing (e.g. activity recreation),
        // hide the overlay immediately to prevent the "Android Auto is starting" flicker.
        if (commManager.isConnected && videoDecoder.lastFrameRenderedMs > 0) {
            loadingOverlay?.visibility = View.GONE
            overlayState = OverlayState.HIDDEN
        }

        // Ensure loading overlay is on top of everything
        loadingOverlay?.bringToFront()

        // Set up custom loading screen if configured
        setupCustomLoadingScreen()

        findViewById<Button>(R.id.disconnect_button)?.setOnClickListener {
            commManager.disconnect()
        }

        videoDecoder.onFirstFrameListener = {
            runOnUiThread {
                hideLoadingOverlay(loadingOverlay)

                // Show one-time gesture hint
                if (!settings.gestureHintShown) {
                    Toast.makeText(this@AapProjectionActivity, R.string.gesture_hint, Toast.LENGTH_LONG).show()
                    settings.gestureHintShown = true
                }
            }
        }

        commManager.onUpdateUiConfigReplyReceived = {
            AppLog.i("[UI_DEBUG_FIX] UpdateUiConfig reply received. AA acknowledged new margins.")
        }
    }

    override fun onPause() {
        isForeground = false
        AppLog.i("AapProjectionActivity: onPause")
        super.onPause()
        // Clear any activity-local fullscreen override when leaving the Activity so
        // the stored settings remain authoritative on next resume.
        activityFullscreenOverride = null
        watchdogHandler.removeCallbacks(watchdogRunnable)
        watchdogHandler.removeCallbacks(videoWatchdogRunnable)
        watchdogHandler.removeCallbacks(reconnectingWatchdog)
        watchdogHandler.removeCallbacks(exitRunnable)
        if (isOrientationReceiverRegistered) {
            unregisterReceiver(orientationReceiver)
            isOrientationReceiverRegistered = false
        }
        if (isNightModeReceiverRegistered) {
            unregisterReceiver(nightModeReceiver)
            isNightModeReceiverRegistered = false
        }
        if (isKeyEventReceiverRegistered) {
            unregisterReceiver(keyEventReceiver)
            isKeyEventReceiverRegistered = false
        }
        if (isSettingsReceiverRegistered) {
            LocalBroadcastManager.getInstance(this).unregisterReceiver(settingsReceiver)
            isSettingsReceiverRegistered = false
        }
    }

    override fun onResume() {
        super.onResume()
        isForeground = true
        AppLog.i("AapProjectionActivity: onResume")
        applyStickyOrientation()
        applyQdLinkUsbWindowMode()
        watchdogHandler.postDelayed(watchdogRunnable, 2000)
        watchdogHandler.postDelayed(videoWatchdogRunnable, 3000)
        watchdogHandler.postDelayed(reconnectingWatchdog, 5000)


        if (!isKeyEventReceiverRegistered) {
            ContextCompat.registerReceiver(this, keyEventReceiver, IntentFilters.keyEvent, ContextCompat.RECEIVER_EXPORTED)
            isKeyEventReceiverRegistered = true
        }

        // Register orientation receiver
        ContextCompat.registerReceiver(this, orientationReceiver, IntentFilter(AapService.ACTION_ORIENTATION_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
        isOrientationReceiverRegistered = true

        // Register night mode receiver for AA monochrome filter
        ContextCompat.registerReceiver(this, nightModeReceiver, IntentFilter(AapService.ACTION_NIGHT_MODE_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
        isNightModeReceiverRegistered = true

        if (!isSettingsReceiverRegistered) {
            LocalBroadcastManager.getInstance(this).registerReceiver(settingsReceiver, IntentFilter(QuickSettingsFragment.ACTION_SETTINGS_CHANGED))
            isSettingsReceiverRegistered = true
        }

        // Request current night mode state for initial desaturation
        sendBroadcast(Intent(AapService.ACTION_REQUEST_NIGHT_MODE_UPDATE).apply {
            setPackage(packageName)
        })

        setFullscreen()
    }


    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        AppLog.i("AapProjectionActivity: onNewIntent received")
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            setFullscreen() // Reapply fullscreen mode if window gains focus
            touchOverlayView?.requestFocus()
        }
    }

    private fun showReconnectingOverlay() {
        AppLog.i("Showing reconnecting overlay")
        overlayState = OverlayState.RECONNECTING
        val overlay = findViewById<View>(R.id.loading_overlay) ?: return

        // Ensure default content is shown, custom media is hidden
        findViewById<View>(R.id.loading_default_content)?.visibility = View.VISIBLE
        findViewById<View>(R.id.loading_custom_image)?.visibility = View.GONE
        findViewById<View>(R.id.loading_custom_text_overlay)?.visibility = View.GONE
        stopCustomLoadingMedia()
        findViewById<View>(R.id.loading_custom_video)?.visibility = View.GONE
        overlay.setBackgroundColor(Color.parseColor("#CC000000"))

        val title = findViewById<TextView>(R.id.overlay_text)
        val detail = findViewById<TextView>(R.id.overlay_detail)
        val button = findViewById<Button>(R.id.disconnect_button)
        overlay.visibility = View.VISIBLE
        title?.text = getString(R.string.connection_interrupted)
        detail?.text = getString(R.string.connection_interrupted_detail)
        detail?.visibility = View.VISIBLE
        button?.visibility = View.VISIBLE
    }

    private fun hideReconnectingOverlay() {
        AppLog.i("Hiding reconnecting overlay — frames resumed")
        overlayState = OverlayState.HIDDEN
        val overlay = findViewById<View>(R.id.loading_overlay) ?: return
        val detail = findViewById<TextView>(R.id.overlay_detail)
        val button = findViewById<Button>(R.id.disconnect_button)
        overlay.visibility = View.GONE
        detail?.visibility = View.GONE
        button?.visibility = View.GONE
        stopCustomLoadingMedia()
        touchOverlayView?.requestFocus()
    }

    private fun setupCustomLoadingScreen() {
        // Apply any context-specific status text handed over by MainActivity
        // (e.g. "Connecting to Pixel 8…") to BOTH the default-content text and
        // the custom-media text overlay. Done before the early-return paths so
        // the override applies whether or not custom media is configured. Read
        // once and cleared so the value can't leak into a later connection.
        val handover = pendingStatusText
        pendingStatusText = null
        if (handover != null) {
            findViewById<TextView>(R.id.overlay_text)?.text = handover
            findViewById<TextView>(R.id.loading_custom_text)?.text = handover
        }

        val mediaPath = settings.loadingScreenMediaPath
        val mediaType = settings.loadingScreenMediaType
        if (mediaPath.isEmpty() || mediaType.isEmpty()) return

        val file = File(mediaPath)
        if (!file.exists()) {
            settings.loadingScreenMediaPath = ""
            settings.loadingScreenMediaType = ""
            return
        }

        val defaultContent = findViewById<View>(R.id.loading_default_content)
        val customTextOverlay = findViewById<View>(R.id.loading_custom_text_overlay)
        val customImage = findViewById<ImageView>(R.id.loading_custom_image)
        val customVideo = findViewById<VideoView>(R.id.loading_custom_video)
        val overlay = findViewById<View>(R.id.loading_overlay)

        // Always hide the default content when custom media is active
        defaultContent?.visibility = View.GONE
        overlay?.setBackgroundColor(Color.BLACK)

        // Show the dedicated custom text overlay if the user wants status text
        if (settings.loadingScreenShowText) {
            customTextOverlay?.visibility = View.VISIBLE
        }

        val keepRatio = settings.loadingScreenKeepAspectRatio
        val scalePercent = settings.loadingScreenScalePercent
        val scale = scalePercent / 100f

        val ov = overlay
        val img = customImage
        if (ov != null && img != null) {
            ov.post {
                val cw = ov.width
                val ch = ov.height
                if (cw > 0 && ch > 0) {
                    val lp = img.layoutParams as? FrameLayout.LayoutParams
                    if (lp != null) {
                        lp.width = (cw * scale).toInt()
                        lp.height = (ch * scale).toInt()
                        lp.gravity = android.view.Gravity.CENTER
                        img.layoutParams = lp
                    }
                }
            }
        }
        customImage?.scaleType = if (keepRatio) ImageView.ScaleType.FIT_CENTER else ImageView.ScaleType.FIT_XY

        try {
            when (mediaType) {
                "image" -> {
                    customImage?.visibility = View.VISIBLE
                    customImage?.let { Glide.with(this).load(file).into(it) }
                    if (keepRatio) {
                        customImage?.let { imageView ->
                            kenBurnsAnimator?.cancel()
                            val scaleAnim = ObjectAnimator.ofPropertyValuesHolder(
                                imageView,
                                PropertyValuesHolder.ofFloat("scaleX", 1.0f, 1.05f),
                                PropertyValuesHolder.ofFloat("scaleY", 1.0f, 1.05f)
                            )
                            scaleAnim.duration = 8000
                            scaleAnim.repeatMode = ObjectAnimator.REVERSE
                            scaleAnim.repeatCount = ObjectAnimator.INFINITE
                            scaleAnim.start()
                            kenBurnsAnimator = scaleAnim
                        }
                    }
                }
                "gif" -> {
                    customImage?.visibility = View.VISIBLE
                    customImage?.let { Glide.with(this).asGif().load(file).into(it) }
                }
                "video" -> {
                    customVideo?.visibility = View.VISIBLE
                    customVideo?.setVideoPath(file.absolutePath)
                    customVideo?.setOnPreparedListener { mp ->
                        mp.isLooping = settings.loadingScreenLoopVideo
                        mp.setVolume(0f, 0f)

                        try {
                            val vw = mp.videoWidth
                            val vh = mp.videoHeight
                            val ov = overlay
                            val cv = customVideo
                            if (ov != null && cv != null) {
                                val cw = ov.width
                                val ch = ov.height
                                if (cw > 0 && ch > 0) {
                                    val lp = cv.layoutParams as FrameLayout.LayoutParams
                                    if (keepRatio && vw > 0 && vh > 0) {
                                        val videoRatio = vw.toFloat() / vh
                                        val containerRatio = cw.toFloat() / ch
                                        val baseWidth: Int
                                        val baseHeight: Int
                                        if (videoRatio > containerRatio) {
                                            baseWidth = cw
                                            baseHeight = (cw / videoRatio).toInt()
                                        } else {
                                            baseHeight = ch
                                            baseWidth = (ch * videoRatio).toInt()
                                        }
                                        lp.width = (baseWidth * scale).toInt()
                                        lp.height = (baseHeight * scale).toInt()
                                    } else {
                                        lp.width = (cw * scale).toInt()
                                        lp.height = (ch * scale).toInt()
                                    }
                                    lp.gravity = android.view.Gravity.CENTER
                                    cv.layoutParams = lp
                                }
                            }
                        } catch (e: Exception) {
                            AppLog.w("Could not resize video: ${e.message}")
                        }
                    }
                    customVideo?.setOnErrorListener { _, _, _ ->
                        AppLog.e("Error playing custom loading video")
                        fallbackToDefaultOverlay()
                        true
                    }
                    customVideo?.start()
                }
                else -> return
            }
        } catch (e: Exception) {
            AppLog.e("Failed to load custom loading screen: ${e.message}")
            fallbackToDefaultOverlay()
        }
    }

    override fun onRetainCustomNonConfigurationInstance(): Any? {
        return true
    }

    private fun applyVirtualDisplayFix() {
        // fixes projected picture being frozen within DUDU PiP
        // does not fix the root cause, where there is a redraw (or something?) of the whole launcher
        //  right before the first frame is shown
        // there is also no public API to get the type of the display
        // if this also causes issues with other virtual displays, try to obtain #getType() via reflection
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R)
            return
        if (lastCustomNonConfigurationInstance as? Boolean == true)
            return
        if (display?.name?.startsWith("DUDU-launcher-split") != true)
            return

        AppLog.i("Detected VirtualDisplay: Recreating projection to fix stuck picture shortly")

        lifecycleScope.launch {
            kotlinx.coroutines.delay(1000)
            if (!isFinishing && !isDestroyed) {
                recreate()
            }
        }
    }

    private fun hideLoadingOverlay(loadingOverlay: View?) {
        overlayState = OverlayState.HIDDEN
        AppLog.i("Hiding loading overlay after first video frame")

        // CRITICAL: Stop custom video FIRST — VideoView/SurfaceView has its own
        // rendering layer that ignores parent alpha animations and can stay visible
        // even when the parent is animated to alpha=0
        stopCustomLoadingMedia()
        findViewById<View>(R.id.loading_custom_video)?.visibility = View.GONE
        findViewById<View>(R.id.loading_custom_image)?.visibility = View.GONE
        findViewById<View>(R.id.loading_custom_text_overlay)?.visibility = View.GONE

        // Now hide the overlay — if no custom video, do a smooth fade
        val hasCustomVideo = settings.loadingScreenMediaType == "video"
        if (hasCustomVideo) {
            // Direct hide — animation won't work with SurfaceView
            loadingOverlay?.visibility = View.GONE
            touchOverlayView?.requestFocus()
        } else {
            // Smooth fade for images/GIFs
            loadingOverlay?.animate()
                ?.alpha(0f)
                ?.setDuration(300)
                ?.withEndAction {
                    loadingOverlay?.visibility = View.GONE
                    loadingOverlay?.alpha = 1f
                    touchOverlayView?.requestFocus()
                }?.start()
                ?: run {
                    loadingOverlay?.visibility = View.GONE
                    touchOverlayView?.requestFocus()
                }
        }

        applyVirtualDisplayFix()
        touchOverlayView?.requestFocus()
    }

    private fun fallbackToDefaultOverlay() {
        findViewById<View>(R.id.loading_custom_image)?.visibility = View.GONE
        stopCustomLoadingMedia()
        findViewById<View>(R.id.loading_custom_video)?.visibility = View.GONE
        findViewById<View>(R.id.loading_custom_text_overlay)?.visibility = View.GONE
        findViewById<View>(R.id.loading_default_content)?.visibility = View.VISIBLE
        findViewById<View>(R.id.loading_overlay)?.setBackgroundColor(Color.parseColor("#CC000000"))
    }

    private fun stopCustomLoadingMedia() {
        kenBurnsAnimator?.cancel()
        kenBurnsAnimator = null
        findViewById<VideoView>(R.id.loading_custom_video)?.let {
            try {
                if (it.isPlaying) it.stopPlayback()
                it.suspend()
            } catch (_: Exception) {}
        }
    }

    private fun setFullscreen() {
        val container = findViewById<View>(R.id.container)

        // Use activity-local override if present, otherwise use stored settings
        val mode = activityFullscreenOverride ?: settings.fullscreenMode

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.KITKAT && mode != Settings.FullscreenMode.NONE) {
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN)
        }

        SystemUI.apply(window, container, mode) {
            if (::projectionView.isInitialized) {
                ProjectionViewScaler.updateScale(projectionView as View, videoDecoder.videoWidth, videoDecoder.videoHeight)
            }
        }

        // Workaround for API < 19 (Jelly Bean) where Sticky Immersive Mode doesn't exist.
        // If bars appear (e.g. on touch), hide them again after a delay.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.KITKAT && mode != Settings.FullscreenMode.NONE) {
            window.decorView.setOnSystemUiVisibilityChangeListener { visibility ->
                if ((visibility and View.SYSTEM_UI_FLAG_FULLSCREEN) == 0) {
                    // Bars are visible. Hide them again.
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        val effectiveMode = activityFullscreenOverride ?: settings.fullscreenMode
                        SystemUI.apply(window, container, effectiveMode) {
                            if (::projectionView.isInitialized) {
                                ProjectionViewScaler.updateScale(projectionView as View, videoDecoder.videoWidth, videoDecoder.videoHeight)
                            }
                        }
                    }, 2000)
                }
            }
        }
    }

    private data class ExitOption(val titleResId: Int, val iconResId: Int, val iconColor: Int)

    private fun showExitDialog() {
        val options = mutableListOf<ExitOption>()
        options.add(ExitOption(R.string.exit_dialog_stop, R.drawable.ic_stop, Color.RED))

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            options.add(ExitOption(R.string.exit_dialog_pip, R.drawable.ic_pip, Color.LTGRAY))
        }

        options.add(ExitOption(R.string.exit_dialog_background, R.drawable.ic_home, Color.LTGRAY))
        options.add(ExitOption(R.string.exit_dialog_settings, R.drawable.ic_settings_quick, Color.LTGRAY))

        val adapter = object : android.widget.BaseAdapter() {
            override fun getCount(): Int = options.size
            override fun getItem(position: Int): Any = options[position]
            override fun getItemId(position: Int): Long = position.toLong()
            override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup): View {
                val view = convertView ?: layoutInflater.inflate(R.layout.dialog_exit_item, parent, false)
                val option = options[position]
                val iconView = view.findViewById<android.widget.ImageView>(R.id.icon)
                val textView = view.findViewById<android.widget.TextView>(R.id.text)

                textView.setText(option.titleResId)
                iconView.setImageResource(option.iconResId)
                iconView.setColorFilter(option.iconColor)

                return view
            }
        }

        MaterialAlertDialogBuilder(this, R.style.DarkAlertDialog)
            .setTitle(R.string.exit_dialog_title)
            .setAdapter(adapter) { _, which ->
                val selected = options[which]
                when (selected.titleResId) {
                    R.string.exit_dialog_stop -> {
                        commManager.disconnect(sendByeBye = true)
                        finish()
                    }
                    R.string.exit_dialog_pip -> {
                        enterPiP()
                    }
                    R.string.exit_dialog_background -> {
                        moveToBackground()
                    }
                    R.string.exit_dialog_settings -> {
                        showQuickSettings()
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showQuickSettings() {
        // We will implement QuickSettingsFragment as a DialogFragment for easy overlay
        val quickSettings = com.andrerinas.headunitrevived.main.QuickSettingsFragment()
        quickSettings.show(supportFragmentManager, "quick_settings")
    }

    private fun enterPiP() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                var width = videoDecoder.videoWidth.coerceAtLeast(1).toFloat()
                var height = videoDecoder.videoHeight.coerceAtLeast(1).toFloat()
                val ratio = width / height

                // Android supports PiP aspect ratios between 1/2.39 (0.418) and 2.39.
                // If we exceed this (e.g. on ultrawide headunits), PiP entry will fail.
                if (ratio > 2.39f) {
                    AppLog.i("PiP: Aspect ratio $ratio is too wide, clamping to 2.39")
                    width = height * 2.39f
                } else if (ratio < 0.418f) {
                    AppLog.i("PiP: Aspect ratio $ratio is too narrow, clamping to 0.418")
                    height = width / 0.418f
                }

                val paramsBuilder = android.app.PictureInPictureParams.Builder()
                    .setAspectRatio(android.util.Rational(width.toInt(), height.toInt()))

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    // Smooth transition for Android 12+
                    paramsBuilder.setAutoEnterEnabled(true)
                    paramsBuilder.setSeamlessResizeEnabled(true)
                }

                App.isPiPActive = true
                enterPictureInPictureMode(paramsBuilder.build())
            } catch (e: Exception) {
                AppLog.e("Failed to enter PiP mode: ${e.message}")
                e.printStackTrace()
                Toast.makeText(this, "PiP failed: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
            }
        } else {
            AppLog.w("PiP mode not supported on this Android version (SDK < 26)")
        }
    }

    private fun moveToBackground() {
        val startMain = Intent(Intent.ACTION_MAIN)
        startMain.addCategory(Intent.CATEGORY_HOME)
        startMain.flags = Intent.FLAG_ACTIVITY_NEW_TASK
        startActivity(startMain)
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: android.content.res.Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        App.isPiPActive = isInPictureInPictureMode
        if (isInPictureInPictureMode) {
            // Hide UI elements during PiP (like FPS counter, loading overlay)
            findViewById<View>(R.id.loading_overlay)?.visibility = View.GONE
            stopCustomLoadingMedia()
            fpsTextView?.visibility = View.GONE
        } else {
            // Restore UI if needed
            fpsTextView?.visibility = if (settings.showFpsCounter) View.VISIBLE else View.GONE
            setFullscreen()
        }
    }

    override fun onUserLeaveHint() {
        // Optional: Auto-enter PiP if user presses home

        // For now, we only enter via dialog as requested.
        super.onUserLeaveHint()
    }

    private val commManager get() = App.provide(this).commManager

    private var headUnitTouchLogged = 0

    /** True while this phone is cabled to a QDLink head unit and video goes to it. */
    private fun qdLinkUsbActive(): Boolean =
        settings.videoOutputMode != Settings.VideoOutputMode.LOCAL &&
            App.provide(this).qdLinkBridge.usbAccessory() != null

    /**
     * A QDLink head unit on USB delivers touch as a HID touchscreen attached to this
     * phone (vendor/product ffff:ffff), scaled to the whole phone display. Those
     * events belong to the picture on the head unit, not to whatever is laid out
     * on this screen, so map every finger by screen fraction straight into Android
     * Auto's active area.
     */
    private fun forwardHeadUnitTouch(ev: MotionEvent): Boolean {
        val device = ev.device ?: return false
        if (device.vendorId != 0xffff || device.productId != 0xffff) return false
        if (settings.videoOutputMode == Settings.VideoOutputMode.LOCAL) return false
        val action = TouchEvent.motionEventToAction(ev) ?: return true
        val bridge = App.provide(this).qdLinkBridge

        val size = android.graphics.Point()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealSize(size)
        val displayW = size.x.coerceAtLeast(1).toFloat()
        val displayH = size.y.coerceAtLeast(1).toFloat()
        // Same active area the Wi-Fi touch path targets: the QDLink canvas inside
        // the standard frame Android Auto was asked to encode.
        val aaW = HeadUnitScreenConfig.getNegotiatedWidth().coerceAtLeast(1)
        val aaH = HeadUnitScreenConfig.getNegotiatedHeight().coerceAtLeast(1)
        val activeW = if (settings.qdLinkActiveAreaMargins) minOf(aaW, bridge.width).coerceAtLeast(1) else aaW
        val activeH = if (settings.qdLinkActiveAreaMargins) minOf(aaH, bridge.height).coerceAtLeast(1) else aaH
        // getX/getY are window-relative; the offset to the screen is the same for every finger.
        val offsetX = ev.rawX - ev.x
        val offsetY = ev.rawY - ev.y

        val pointerData = mutableListOf<Triple<Int, Int, Int>>()
        repeat(ev.pointerCount) { index ->
            var fx = (ev.getX(index) + offsetX) / displayW
            var fy = (ev.getY(index) + offsetY) / displayH
            // The head unit addresses a landscape picture. If this screen is upright,
            // Android has turned its coordinates with the display; turn them back.
            if (displayH > displayW) {
                val px = fx; val py = fy
                fx = py; fy = 1f - px
            }
            val x = (fx * activeW).toInt().coerceIn(0, activeW - 1)
            val y = (fy * activeH).toInt().coerceIn(0, activeH - 1)
            pointerData.add(Triple(ev.getPointerId(index), x, y))
        }
        if (headUnitTouchLogged++ < 200 && ev.actionMasked != MotionEvent.ACTION_MOVE) AppLog.i(
            "Head unit touch: $action index=${ev.actionIndex} fingers=${ev.pointerCount} raw=${ev.rawX},${ev.rawY} " +
                "display=${size.x}x${size.y} -> AA $pointerData of ${activeW}x${activeH} connected=${commManager.isConnected}")
        commManager.send(TouchEvent(SystemClock.elapsedRealtime(), action, ev.actionIndex, pointerData))
        return true
    }

    /**
     * Head unit touches only reach this activity while the display is on and
     * this screen is in front, turned the way the head unit expects. So on a
     * QDLink cable: lie flat in landscape, stay awake, and with nothing to show
     * locally (QDLink-only output) dim the panel as far as it goes.
     */
    private fun applyQdLinkUsbWindowMode() {
        val active = qdLinkUsbActive()
        if (active) {
            if (requestedOrientation != android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE) {
                AppLog.i("QDLink USB: forcing landscape so head unit touches line up")
                requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            }
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        val dim = active && settings.videoOutputMode == Settings.VideoOutputMode.QDLINK
        val wanted = if (dim) 0.01f else android.view.WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        if (window.attributes.screenBrightness != wanted) {
            window.attributes = window.attributes.apply { screenBrightness = wanted }
            AppLog.i("QDLink USB: screen ${if (dim) "dimmed to minimum, kept on for head unit touch" else "brightness restored"}")
        }
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (forwardHeadUnitTouch(ev)) return true

        // 1. 2-finger swipe detection from the left or right edge (to open exit menu or toggle fullscreen)
        if (ev.pointerCount == 2) {
            when (ev.actionMasked) {
                MotionEvent.ACTION_POINTER_DOWN -> {
                    initialX = ev.getX(0)
                    initialY = ev.getY(0)
                    isPotentialGesture = initialX < 100 || initialX > HeadUnitScreenConfig.getUsableWidth() - 100
                }
                MotionEvent.ACTION_MOVE -> {
                    if (isPotentialGesture) {
                        val deltaX = ev.getX(0) - initialX
                        val deltaY = abs(ev.getY(0) - initialY)
                        if (deltaX > 200 && deltaY < 100) {
                            isPotentialGesture = false
                            showExitDialog()
                            return true // Consume
                        }

                        if (deltaX < -200 && deltaY < 100) {
                            isPotentialGesture = false
                            val currentEffective = activityFullscreenOverride ?: settings.fullscreenMode
                            if (currentEffective != Settings.FullscreenMode.NONE) {
                                activityFullscreenOverride = Settings.FullscreenMode.NONE
                                setFullscreen()
                            } else {
                                activityFullscreenOverride = null
                                setFullscreen()
                            }
                            return true // Consume
                        }
                    }
                }
            }
        }

        // 2. Legacy Touch handling for older devices (API < 19)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.KITKAT) {
            sendTouchEvent(ev)
        }

        return super.dispatchTouchEvent(ev)
    }

    override fun onSurfaceCreated(surface: android.view.Surface) {
        AppLog.i("[UI_DEBUG] [AapProjectionActivity] onSurfaceCreated")
        // Decoder configuration is now in onSurfaceChanged
    }

    override fun onSurfaceChanged(surface: android.view.Surface, width: Int, height: Int) {
        AppLog.i("[UI_DEBUG] [AapProjectionActivity] onSurfaceChanged. Actual surface dimensions: width=$width, height=$height")
        isSurfaceSet = true

        videoDecoder.setSurface(surface)

        // --- Surface Mismatch Detection ---
        // Compare actual surface dimensions with what HeadUnitScreenConfig negotiated.
        // If they differ (e.g. system bars appeared/disappeared), update margins.
        val prevUsableW = HeadUnitScreenConfig.getUsableWidth()
        val prevUsableH = HeadUnitScreenConfig.getUsableHeight()

        if (HeadUnitScreenConfig.updateSurfaceDimensions(width, height)) {
            AppLog.i("[UI_DEBUG_FIX] Surface mismatch! Expected: ${prevUsableW}x${prevUsableH}, Actual: ${width}x${height}")

            // Cache the real surface size for next session
            settings.cachedSurfaceWidth = width
            settings.cachedSurfaceHeight = height
            settings.cachedSurfaceSettingsHash = HeadUnitScreenConfig.computeSettingsHash(settings)

            if (commManager.connectionState.value is CommManager.ConnectionState.TransportStarted) {
                // AA is already running → send corrected per-side margins dynamically
                commManager.sendUpdateUiConfigRequest(
                    HeadUnitScreenConfig.getLeftMargin(),
                    HeadUnitScreenConfig.getTopMargin(),
                    HeadUnitScreenConfig.getRightMargin(),
                    HeadUnitScreenConfig.getBottomMargin()
                )
                AppLog.i("[UI_DEBUG_FIX] AA is already running, send corrected via sendUpdateUiConfigRequest")
            }
            // If transport not started yet, ServiceDiscoveryResponse will use the corrected values automatically.
        }

        when (commManager.connectionState.value) {
            is CommManager.ConnectionState.Connected -> {
                // AapService should have started the handshake already, but as a fallback
                // (e.g. service restarted) kick it off here. The HandshakeComplete observer
                // will call startReading() once the handshake finishes.
                lifecycleScope.launch { commManager.startHandshake() }
            }
            is CommManager.ConnectionState.StartingTransport -> {
                // Handshake is in progress. The HandshakeComplete observer will call
                // startReading() when it finishes.
            }
            is CommManager.ConnectionState.HandshakeComplete -> {
                // Handshake already done before surface was ready — start reading now.
                lifecycleScope.launch { commManager.startReading() }
            }
            is CommManager.ConnectionState.TransportStarted -> {
                // Surface recreated while transport was already running; request a keyframe.
                commManager.send(VideoFocusEvent(gain = true, unsolicited = true))
            }
            else -> {
                commManager.send(VideoFocusEvent(gain = true, unsolicited = false))
            }
        }

        // Explicitly check and set video dimensions if already known by the decoder
        // This handles cases where the activity is recreated but the decoder already has dimensions
        val currentVideoWidth = videoDecoder.videoWidth
        val currentVideoHeight = videoDecoder.videoHeight

        if (currentVideoWidth > 0 && currentVideoHeight > 0) {
            AppLog.i("[AapProjectionActivity] Decoder already has dimensions: ${currentVideoWidth}x$currentVideoHeight. Applying to view.")
            runOnUiThread {
                projectionView.setVideoSize(currentVideoWidth, currentVideoHeight)
                ProjectionViewScaler.updateScale(projectionView as View, currentVideoWidth, currentVideoHeight)
            }
        }
    }

    override fun onSurfaceDestroyed(surface: android.view.Surface) {
        AppLog.i("SurfaceCallback: onSurfaceDestroyed. Surface: $surface")
        isSurfaceSet = false
        commManager.send(VideoFocusEvent(gain = false, unsolicited = false))
        videoDecoder.stop("surfaceDestroyed")
    }


    override fun onVideoDimensionsChanged(width: Int, height: Int) {
        AppLog.i("[AapProjectionActivity] Received video dimensions: ${width}x$height")
        runOnUiThread {
            projectionView.setVideoSize(width, height)
            ProjectionViewScaler.updateScale(projectionView as View, width, height)
        }
    }

    private fun sendTouchEvent(event: MotionEvent) {
        val action = TouchEvent.motionEventToAction(event) ?: return
        val ts = SystemClock.elapsedRealtime()

        val videoW = HeadUnitScreenConfig.getNegotiatedWidth()
        val videoH = HeadUnitScreenConfig.getNegotiatedHeight()

        if (videoW <= 0 || videoH <= 0 || projectionView !is View) {
            AppLog.w("sendTouchEvent: Ignoring touch, screen config or view not ready.")
            return
        }

        val view = projectionView as View
        // Use the container's "Anchor" dimensions (full touch surface) as the reference,
        // not the potentially resized projectionView's dimensions.
        val viewW = HeadUnitScreenConfig.getUsableWidth().toFloat()
        val viewH = HeadUnitScreenConfig.getUsableHeight().toFloat()

        if (viewW <= 0 || viewH <= 0) return

        val marginW = HeadUnitScreenConfig.getWidthMargin().toFloat()
        val marginH = HeadUnitScreenConfig.getHeightMargin().toFloat()

        val uiW = videoW - marginW
        val uiH = videoH - marginH

        // Logic check: When forcedScale is active, the visual behavior of 'stretchToFill'
        // is inverted (True = Aspect Ratio Centered, False = Stretched to Screen).
        // We adjust the touch mapping to match this visual reality.
        val isStretch = if (HeadUnitScreenConfig.forcedScale) {
            !settings.stretchToFill
        } else {
            settings.stretchToFill
        }

        val pointerData = mutableListOf<Triple<Int, Int, Int>>()
        repeat(event.pointerCount) { pointerIndex ->
            val pointerId = event.getPointerId(pointerIndex)
            val rawPx = event.getX(pointerIndex)
            val px = if (settings.hudMirroring) (viewW - rawPx) else rawPx
            val py = event.getY(pointerIndex)

            var videoX = 0f
            var videoY = 0f

            if (isStretch) {
                videoX = (px / viewW) * uiW
                videoY = (py / viewH) * uiH
            } else {
                val uiRatio = uiW / uiH
                val viewRatio = viewW / viewH

                var displayedUiW = viewW
                var displayedUiH = viewH

                if (viewRatio > uiRatio) {
                    displayedUiW = viewH * uiRatio
                } else {
                    displayedUiH = viewW / uiRatio
                }

                val uiLeft = (viewW - displayedUiW) / 2f
                val uiTop = (viewH - displayedUiH) / 2f

                val localX = px - uiLeft
                val localY = py - uiTop

                videoX = (localX / displayedUiW) * uiW
                videoY = (localY / displayedUiH) * uiH
            }

            // Clamp to negotiated bounds to prevent out-of-bounds touches
            val correctedX = videoX.toInt().coerceIn(0, videoW)
            val correctedY = videoY.toInt().coerceIn(0, videoH)

            pointerData.add(Triple(pointerId, correctedX, correctedY))
        }

        commManager.send(TouchEvent(ts, action, event.actionIndex, pointerData))
    }


    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val action = event.action
        if (action != KeyEvent.ACTION_DOWN && action != KeyEvent.ACTION_UP) {
            return super.dispatchKeyEvent(event)
        }

        // 1. Let the system handle volume keys and unmapped back keys.
        // If Back was explicitly learned in Keymap and transport is running,
        // route it through CommManager so it can be remapped and sent to Android Auto.
        if (commManager.connectionState.value is CommManager.ConnectionState.TransportStarted &&
            ProjectionKeyPolicy.shouldRouteBackKeyToProjection(cachedKeyCodes, event.keyCode)) {
            commManager.sendKey(event.keyCode, action == KeyEvent.ACTION_DOWN)
            return true
        }

        if (event.keyCode == KeyEvent.KEYCODE_BACK ||
            event.keyCode == KeyEvent.KEYCODE_VOLUME_UP ||
            event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN ||
            event.keyCode == KeyEvent.KEYCODE_VOLUME_MUTE) {
            return super.dispatchKeyEvent(event)
        }

        // 2. Funnel all other keys to CommManager
        commManager.sendKey(event.keyCode, event.action == KeyEvent.ACTION_DOWN)
        return true
    }

    private fun onKeyEvent(keyCode: Int, isPress: Boolean) {
        // Broadcasts (e.g. from CarKeyReceiver) still use this path.
        commManager.sendKey(keyCode, isPress)
    }

    private fun applyStickyOrientation() {
        if (qdLinkUsbActive()) return // applyQdLinkUsbWindowMode() owns the orientation
        if (settings.screenOrientation == Settings.ScreenOrientation.AUTO && HeadUnitScreenConfig.isResolutionLocked) {
            val target = if (HeadUnitScreenConfig.getNegotiatedWidth() > HeadUnitScreenConfig.getNegotiatedHeight()) {
                android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            } else {
                android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            }
            if (requestedOrientation != target) {
                AppLog.i("[UI_DEBUG] Sticky Orientation: Session active, forcing orientation to $target")
                requestedOrientation = target
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishReceiverRegistered) {
            unregisterReceiver(finishReceiver)
            isFinishReceiverRegistered = false
        }
        if (isKeyEventReceiverRegistered) {
            unregisterReceiver(keyEventReceiver)
            isKeyEventReceiverRegistered = false
        }
        // Defensive cleanup: if the activity is destroyed while the loading
        // overlay is still up (early connection failure, system kill,
        // configuration change before first frame), the VideoView's surface
        // and the Ken Burns animator outlive the view hierarchy briefly.
        // stopCustomLoadingMedia releases both.
        stopCustomLoadingMedia()
        stopPerformanceOverlayUpdates()
        performanceExecutor.shutdownNow()
        AppLog.i("AapProjectionActivity.onDestroy called. isFinishing=$isFinishing")
        App.isPiPActive = false
        videoDecoder.onFpsChanged = null
        videoDecoder.softwareYuvFrameSink = null
        videoDecoder.dimensionsListener = null
    }

    companion object {
        const val EXTRA_FOCUS = "focus"
        @Volatile var isForeground = false

        /**
         * Optional one-shot override for the loading-screen status text. Set by
         * MainActivity when it begins an auto-connect with a context-specific
         * label (e.g. "Connecting to Pixel 8…" from the Nearby selector). Read
         * and cleared by [setupCustomLoadingScreen] on the next launch so the
         * value can't leak into a subsequent connection attempt.
         */
        @Volatile var pendingStatusText: String? = null

        fun intent(context: Context): Intent {
            val aapIntent = Intent(context, AapProjectionActivity::class.java)
            aapIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            return aapIntent
        }
    }
    private fun applyOrientationSettings() {
        if (qdLinkUsbActive()) { applyQdLinkUsbWindowMode(); return }
        val screenOrientation = settings.screenOrientation
        if (screenOrientation == Settings.ScreenOrientation.AUTO) {
            applyStickyOrientation()
            if (!HeadUnitScreenConfig.isResolutionLocked) {
                // Initial start: lock to current orientation at launch
                if (Build.VERSION.SDK_INT >= 18) {
                    requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LOCKED
                } else {
                    requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_NOSENSOR
                }
            }
        } else {
            requestedOrientation = screenOrientation.androidOrientation
        }
    }

    private fun setupProjectionView() {
        val container = findViewById<FrameLayout>(R.id.container)
        val displayMetrics = resources.displayMetrics

        if (settings.viewMode == Settings.ViewMode.TEXTURE) {
            AppLog.i("Using TextureView")
            val textureView = TextureProjectionView(this)
            textureView.layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            projectionView = textureView
            container.setBackgroundColor(Color.BLACK)
        } else if (settings.viewMode == Settings.ViewMode.GLES) {
            AppLog.i("Using GlProjectionView")
            val glView = GlProjectionView(this)
            glView.layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            projectionView = glView
            container.setBackgroundColor(Color.BLACK)
        } else {
            AppLog.i("Using SurfaceView")
            projectionView = ProjectionView(this)
            (projectionView as View).layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        // Use the same screen conf for both views for negotiation
        HeadUnitScreenConfig.init(this, displayMetrics, settings)

        val view = projectionView as View
        container.addView(view)
        videoDecoder.softwareYuvFrameSink = projectionView as? SoftwareYuvFrameSink
        if (videoDecoder.softwareYuvFrameSink != null) {
            AppLog.i("Using GLES YUV sink for bundled software HEVC")
        }

        projectionView.addCallback(this)
    }

    private fun setupFpsCounter() {
        val container = findViewById<FrameLayout>(R.id.container)
        fpsTextView = TextView(this).apply {
            setTextColor(Color.YELLOW)
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
            setBackgroundColor(Color.parseColor("#80000000"))
            setPadding(10, 5, 10, 5)
            text = "FPS: --\nCPU: -- / --\nTemp: --\nFrame: --"
            // Lift it above everything
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                elevation = 100f
                translationZ = 100f
            }
        }
        val params = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            setMargins(20, 20, 0, 0)
        }
        container.addView(fpsTextView, params)

        videoDecoder.onFpsChanged = { fps ->
            currentFps = fps
        }
        startPerformanceOverlayUpdates()
    }

    private fun startPerformanceOverlayUpdates() {
        performanceHandler.removeCallbacks(performanceOverlayRunnable)
        performanceOverlayRunnable.run()
    }

    private fun stopPerformanceOverlayUpdates() {
        performanceHandler.removeCallbacks(performanceOverlayRunnable)
    }

    private fun requestPerformanceOverlayUpdate() {
        if (!performanceSampleInFlight.compareAndSet(false, true)) return

        val fpsSnapshot = currentFps
        val lastFrameSnapshot = videoDecoder.lastFrameRenderedMs
        try {
            performanceExecutor.execute {
                try {
                    val text = buildPerformanceOverlayText(fpsSnapshot, lastFrameSnapshot)
                    runOnUiThread {
                        if (!isFinishing && fpsTextView?.visibility == View.VISIBLE) {
                            fpsTextView?.text = text
                        }
                    }
                } catch (e: Exception) {
                    AppLog.w("Performance overlay update failed: ${e.message}")
                } finally {
                    performanceSampleInFlight.set(false)
                }
            }
        } catch (e: java.util.concurrent.RejectedExecutionException) {
            performanceSampleInFlight.set(false)
        }
    }

    private fun buildPerformanceOverlayText(fpsSnapshot: Int?, lastFrameSnapshot: Long): String {
        val metrics = performanceSampler.sample()
        val frameAgeText = if (lastFrameSnapshot > 0L) {
            "${SystemClock.elapsedRealtime() - lastFrameSnapshot}ms"
        } else {
            "--"
        }
        val fpsText = fpsSnapshot?.toString() ?: "--"
        val appCpuText = metrics.appCpuPercent?.let { "${it}%" } ?: "--"
        val totalCpuText = metrics.totalCpuPercent?.let { "${it}%" }
            ?: metrics.loadAverage?.let { String.format(java.util.Locale.US, "%.2f load", it) }
            ?: "--"
        val tempText = metrics.temperatureC?.let { "${it}C" } ?: "--"
        return "FPS: $fpsText\nCPU: app $appCpuText / sys $totalCpuText\nTemp: $tempText\nFrame: $frameAgeText"
    }

    private class PerformanceSampler {
        private data class TotalCpuSnapshot(
            val totalJiffies: Long,
            val idleJiffies: Long
        )

        data class Metrics(
            val appCpuPercent: Int?,
            val totalCpuPercent: Int?,
            val loadAverage: Double?,
            val temperatureC: Int?
        )

        private var previousTotalCpu: TotalCpuSnapshot? = null
        private var previousProcessCpuMs: Long? = null
        private var previousElapsedMs: Long? = null

        fun sample(): Metrics {
            val nowElapsedMs = SystemClock.elapsedRealtime()
            val nowProcessCpuMs = android.os.Process.getElapsedCpuTime()
            val previousProcess = previousProcessCpuMs
            val previousElapsed = previousElapsedMs
            previousProcessCpuMs = nowProcessCpuMs
            previousElapsedMs = nowElapsedMs

            val appCpu = if (previousProcess != null && previousElapsed != null) {
                val cpuDelta = (nowProcessCpuMs - previousProcess).coerceAtLeast(0L)
                val elapsedDelta = (nowElapsedMs - previousElapsed).coerceAtLeast(1L)
                ((cpuDelta.toDouble() / elapsedDelta) * 100.0).toInt().coerceAtLeast(0)
            } else {
                null
            }

            val currentTotalCpu = readTotalCpuSnapshot()
            val previousTotal = previousTotalCpu
            previousTotalCpu = currentTotalCpu
            val totalCpu = if (currentTotalCpu != null && previousTotal != null) {
                val totalDelta = (currentTotalCpu.totalJiffies - previousTotal.totalJiffies).coerceAtLeast(1L)
                val idleDelta = (currentTotalCpu.idleJiffies - previousTotal.idleJiffies).coerceAtLeast(0L)
                (((totalDelta - idleDelta).toDouble() / totalDelta) * 100.0).toInt().coerceIn(0, 100)
            } else {
                null
            }

            return Metrics(appCpu, totalCpu, readLoadAverage(), readTemperatureC())
        }

        private fun readTotalCpuSnapshot(): TotalCpuSnapshot? {
            return try {
                val cpuLine = File("/proc/stat").useLines { lines ->
                    lines.firstOrNull { it.startsWith("cpu ") }
                } ?: return null
                val cpuValues = cpuLine.trim().split(Regex("\\s+")).drop(1).mapNotNull { it.toLongOrNull() }
                if (cpuValues.size < 5) return null
                val idle = cpuValues.getOrElse(3) { 0L } + cpuValues.getOrElse(4) { 0L }
                val total = cpuValues.take(8).sum()
                TotalCpuSnapshot(total, idle)
            } catch (e: Exception) {
                null
            }
        }

        private fun readLoadAverage(): Double? {
            return try {
                File("/proc/loadavg")
                    .readText()
                    .trim()
                    .split(Regex("\\s+"))
                    .firstOrNull()
                    ?.toDoubleOrNull()
            } catch (e: Exception) {
                null
            }
        }

        private fun readTemperatureC(): Int? {
            return try {
                val thermalRoot = File("/sys/class/thermal")
                val values = thermalRoot.listFiles()
                    ?.filter { it.name.startsWith("thermal_zone") }
                    ?.mapNotNull { zone ->
                        val raw = zone.resolve("temp").readText().trim().toIntOrNull() ?: return@mapNotNull null
                        when {
                            raw in 10000..125000 -> raw / 1000
                            raw in 10..125 -> raw
                            else -> null
                        }
                    }
                    .orEmpty()
                values.maxOrNull()
            } catch (e: Exception) {
                null
            }
        }
    }
}
