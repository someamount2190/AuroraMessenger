package com.aura.call

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

/**
 * Floating call window OVER OTHER APPS (the Messenger / Viber bubble). Shown while a
 * call is live and Aurora is in the background; it renders the remote camera for a video
 * call (or a labelled pill for a voice call), is draggable, and returns to the full call
 * screen on tap. Requires the "display over other apps" permission.
 *
 * The host process is kept alive by the foreground WakeService, so this only needs to
 * own the WindowManager view. The window carries FLAG_SECURE so the floating video is
 * still excluded from screenshots/recording, matching the in-app call screen.
 */
@Singleton
class CallOverlayManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val callManager: CallManager
) {
    private val wm = context.getSystemService(WindowManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val density = context.resources.displayMetrics.density

    private var root: View? = null
    private var renderer: SurfaceViewRenderer? = null
    private var boundTrack: VideoTrack? = null
    private var scope: CoroutineScope? = null

    private fun dp(v: Int) = (v * density).toInt()

    /** Whether we're allowed to draw over other apps. */
    fun canShow(): Boolean = Settings.canDrawOverlays(context)

    /** Show the floating window (no-op if already shown or permission missing). */
    fun show() = main.post {
        if (root != null || !canShow()) return@post
        val info = callManager.call.value
        if (info.state == CallManager.CallState.IDLE || info.state == CallManager.CallState.ENDED) return@post

        val view = buildView(info.isVideo, info.peerName)
        val params = WindowManager.LayoutParams(
            if (info.isVideo) dp(120) else WindowManager.LayoutParams.WRAP_CONTENT,
            if (info.isVideo) dp(168) else WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_SECURE,   // keep the floating video off screenshots
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(16); y = dp(80)
        }
        attachDrag(view, params)
        runCatching { wm?.addView(view, params) }
        root = view

        val s = CoroutineScope(Dispatchers.Main + SupervisorJob())
        scope = s
        // Bind the remote video as it appears / changes.
        s.launch { callManager.remoteVideo.collect { bindTrack(it) } }
        // Tear down if the call ends while we're floating.
        s.launch {
            callManager.call.collect {
                if (it.state == CallManager.CallState.ENDED || it.state == CallManager.CallState.IDLE) hide()
            }
        }
    }

    /** Remove the floating window and release its renderer. */
    fun hide() = main.post {
        scope?.let { it.coroutineContext[Job]?.cancel() }; scope = null
        // removeSink BEFORE release so no frame lands on a freed renderer (libjingle abort).
        boundTrack?.let { t -> renderer?.let { r -> t.removeSink(r) } }
        boundTrack = null
        renderer?.release(); renderer = null
        root?.let { runCatching { wm?.removeView(it) } }; root = null
    }

    private fun bindTrack(track: VideoTrack?) {
        val r = renderer ?: return
        if (track === boundTrack) return
        boundTrack?.removeSink(r)
        track?.addSink(r)
        boundTrack = track
    }

    private fun buildView(video: Boolean, peerName: String?): View {
        val frame = FrameLayout(context).apply {
            background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(Color.parseColor("#0B1222"))
            }
            clipToOutline = true
        }
        if (video) {
            val r = SurfaceViewRenderer(context).apply {
                setZOrderMediaOverlay(true)
                init(callManager.eglBase.eglBaseContext, null)
                setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
                setEnableHardwareScaler(true)
            }
            renderer = r
            frame.addView(r, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            // attach the current track immediately if present
            bindTrack(callManager.remoteVideo.value)
        } else {
            val label = TextView(context).apply {
                text = (peerName ?: "Call") + "\nOn call · tap to return"
                setTextColor(Color.WHITE)
                textSize = 12f
                setPadding(dp(14), dp(12), dp(14), dp(12))
            }
            frame.addView(label)
        }
        return frame
    }

    /** Drag to move; a tap (negligible movement) returns to the call screen. */
    private fun attachDrag(view: View, params: WindowManager.LayoutParams) {
        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0; var moved = false
        view.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY; startX = params.x; startY = params.y; moved = false; true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (e.rawX - downX).toInt(); val dy = (e.rawY - downY).toInt()
                    if (abs(dx) > dp(6) || abs(dy) > dp(6)) moved = true
                    params.x = startX + dx; params.y = startY + dy
                    runCatching { wm?.updateViewLayout(view, params) }
                    true
                }
                MotionEvent.ACTION_UP -> { if (!moved) returnToCall(); true }
                else -> false
            }
        }
    }

    private fun returnToCall() {
        callManager.expand()
        val intent = Intent(context, com.aura.MainActivity::class.java).apply {
            action = com.aura.notify.Notifier.ACTION_OPEN_CALL
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        runCatching { context.startActivity(intent) }
        hide()
    }
}
