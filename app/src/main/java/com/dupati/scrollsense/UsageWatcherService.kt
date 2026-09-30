package com.dupati.scrollsense

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat

/**
 * Polls UsageStatsManager to detect the current foreground app. This replaces an
 * AccessibilityService-based approach: Google Play policy prohibits using the
 * Accessibility API for anything other than genuine accessibility purposes, and
 * foreground-app detection for a screen-time timer doesn't qualify. UsageStatsManager +
 * the "Usage access" special permission is the approach Play-published screen-time apps
 * use instead.
 *
 * In AUTO mode this service owns OverlayService's lifecycle: it shows the bubble when a
 * watched app comes to the foreground and hides it (after HIDE_DELAY_MILLIS) once the
 * user leaves every watched app. In ALWAYS mode the bubble is already on screen the whole
 * time (started directly by MainActivity), so this service instead just keeps it informed
 * of which watched app, if any, is foreground - needed so per-app daily limits can be
 * attributed and enforced even when the bubble isn't auto-hiding. See
 * checkForegroundAppAlwaysMode.
 */
class UsageWatcherService : Service() {

    private val handler = Handler(Looper.getMainLooper())

    // Only used in AUTO mode, where it's always safe to hide the bubble once the user
    // has left every watched app.
    private val hideRunnable = Runnable {
        val intent = Intent(this, OverlayService::class.java).apply {
            action = OverlayService.ACTION_STOP
        }
        startService(intent)
    }

    // True from the moment we notice the user has left every watched app until
    // hideRunnable actually fires. Without this, checkForegroundApp() would keep
    // cancelling and re-arming hideRunnable on every single poll for as long as the
    // user stays away (it runs every poll, not just once on the transition) - and since
    // POLL_INTERVAL_MILLIS is shorter than HIDE_DELAY_MILLIS, the timer would never
    // survive long enough to fire at all until some scheduling jitter happened to leave
    // a large enough gap between two polls. That's what made the bubble linger for many
    // seconds (observed ~10s+) instead of hiding after the intended 500ms.
    private var hidePending = false

    private val pollRunnable = object : Runnable {
        override fun run() {
            checkForegroundApp()
            handler.postDelayed(this, POLL_INTERVAL_MILLIS)
        }
    }

    // Last watched package the overlay was attributed to, so we can detect switching
    // directly between two different watched apps (e.g. Instagram straight to YouTube,
    // or moving focus between two watched apps open side by side in split-screen).
    private var lastWatchedPackage: String? = null

    // A switch to a *different* watched app is debounced: split-screen focus changes
    // (dragging the divider, the window-transition animation, a stray touch) can make
    // Android briefly report the other pane as foreground before settling back. Without
    // this, every blip would reset the session timer. A switch only commits once the
    // new app holds focus for SWITCH_DEBOUNCE_MILLIS.
    private var pendingSwitchPackage: String? = null

    private val switchRunnable = Runnable {
        val pkg = pendingSwitchPackage ?: return@Runnable
        pendingSwitchPackage = null
        startService(Intent(this, OverlayService::class.java).apply {
            putExtra(OverlayService.EXTRA_TRACKED_PACKAGE, pkg)
        })
        lastWatchedPackage = pkg
    }

    // Always mode's equivalent of switchRunnable: tells the already-running OverlayService
    // which watched app (if any) is now foreground, without ever starting/stopping it.
    // pendingSwitchPackage doubles as the debounce target here too - null is a valid
    // target, meaning "no watched app is foreground right now".
    private val alwaysModeSwitchRunnable = Runnable {
        if (pendingSwitchPackage == lastWatchedPackage) return@Runnable
        val pkg = pendingSwitchPackage
        lastWatchedPackage = pkg
        val intent = Intent(this, OverlayService::class.java)
        if (pkg != null) {
            intent.putExtra(OverlayService.EXTRA_TRACKED_PACKAGE, pkg)
        } else {
            intent.action = OverlayService.ACTION_CLEAR_TRACKED_PACKAGE
        }
        startService(intent)
    }

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        startForeground(NOTIFICATION_ID, buildNotification())
        handler.post(pollRunnable)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        handler.removeCallbacksAndMessages(null)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun checkForegroundApp() {
        val packageName = currentForegroundPackage() ?: return
        val watched = TimerPrefs.getWatchedPackages(this)
        val shouldShow = packageName in watched

        if (TimerPrefs.getOverlayMode(this) == TimerPrefs.MODE_ALWAYS) {
            checkForegroundAppAlwaysMode(packageName, shouldShow)
            return
        }

        if (shouldShow) {
            hidePending = false
            handler.removeCallbacks(hideRunnable)
            if (!OverlayService.isRunning) {
                handler.removeCallbacks(switchRunnable)
                pendingSwitchPackage = null
                val intent = Intent(this, OverlayService::class.java).apply {
                    putExtra(OverlayService.EXTRA_TRACKED_PACKAGE, packageName)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(intent)
                } else {
                    startService(intent)
                }
                lastWatchedPackage = packageName
            } else if (packageName == lastWatchedPackage) {
                // Settled back on the app we're already tracking — drop any pending switch.
                handler.removeCallbacks(switchRunnable)
                pendingSwitchPackage = null
            } else if (packageName != pendingSwitchPackage) {
                // A different watched app than the one we're tracking. Don't switch yet —
                // wait to see if this holds (see pendingSwitchPackage doc above).
                pendingSwitchPackage = packageName
                handler.removeCallbacks(switchRunnable)
                handler.postDelayed(switchRunnable, SWITCH_DEBOUNCE_MILLIS)
            }
        } else if (OverlayService.isBlockActive) {
            // The limit-block screen's own goHome() call is what just made the
            // foreground app change here — not anything the user did — so don't act on
            // it: no hiding the bubble, no stopping the service. The block screen stays
            // up (covering the home screen, PIN entry, everything) until the user
            // dismisses it themselves via its "Go to Home Screen" button, which clears
            // the tracked package directly. Reacting to this foreground change the same
            // way as a normal "left the watched app" event would tear the block down
            // out from under the user before they ever got to read it.
            hidePending = false
            handler.removeCallbacks(switchRunnable)
            pendingSwitchPackage = null
            handler.removeCallbacks(hideRunnable)
        } else if (OverlayService.isRunning && !hidePending) {
            hidePending = true
            handler.removeCallbacks(switchRunnable)
            pendingSwitchPackage = null
            handler.removeCallbacks(hideRunnable)
            handler.postDelayed(hideRunnable, TimerPrefs.HIDE_DELAY_MILLIS)
        }
    }

    /** Always mode keeps the bubble on screen regardless of which app is foreground -
     *  OverlayService's own lifecycle is never touched here. This just keeps it informed
     *  of which watched app (if any) is currently in the foreground, so it can attribute
     *  elapsed time and enforce that app's daily limit. Switches are debounced the same
     *  way as Auto mode's switchRunnable, to avoid resetting the session on a brief
     *  split-screen focus blip. */
    private fun checkForegroundAppAlwaysMode(packageName: String, shouldShow: Boolean) {
        if (!OverlayService.isRunning) {
            lastWatchedPackage = null
            pendingSwitchPackage = null
            handler.removeCallbacks(alwaysModeSwitchRunnable)
            return
        }
        val target = if (shouldShow) packageName else null
        if (target == lastWatchedPackage) {
            pendingSwitchPackage = null
            handler.removeCallbacks(alwaysModeSwitchRunnable)
            return
        }
        if (OverlayService.isBlockActive) {
            // The limit-block screen's own goHome() call is what just made the
            // foreground app change here, not anything the user did - ignore it. The
            // block stays up until the user dismisses it themselves via its "Go to Home
            // Screen" button, which clears the tracked package directly.
            pendingSwitchPackage = null
            handler.removeCallbacks(alwaysModeSwitchRunnable)
            return
        }
        if (target != pendingSwitchPackage) {
            pendingSwitchPackage = target
            handler.removeCallbacks(alwaysModeSwitchRunnable)
            handler.postDelayed(alwaysModeSwitchRunnable, SWITCH_DEBOUNCE_MILLIS)
        }
    }

    private fun currentForegroundPackage(): String? {
        val usageStatsManager = getSystemService(USAGE_STATS_SERVICE) as UsageStatsManager
        val end = System.currentTimeMillis()
        val begin = end - LOOKBACK_WINDOW_MILLIS
        val events = usageStatsManager.queryEvents(begin, end)

        var latestPackage: String? = null
        var latestTimestamp = 0L
        val event = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND && event.timeStamp > latestTimestamp) {
                latestTimestamp = event.timeStamp
                latestPackage = event.packageName
            }
        }
        return latestPackage
    }

    private fun buildNotification(): Notification {
        val channelId = "usage_watcher_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId, "Auto-Detect", NotificationManager.IMPORTANCE_MIN
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }

        val stopIntent = Intent(this, UsageWatcherService::class.java).apply { action = ACTION_STOP }
        val stopPendingIntent = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("Scroll Sense auto-detect running")
            .setContentText("Watching for your chosen apps to open")
            .setSmallIcon(android.R.drawable.ic_menu_recent_history)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopPendingIntent)
            .build()
    }

    companion object {
        private const val NOTIFICATION_ID = 1002
        // Shorter than you'd pick for battery reasons alone, but this interval directly
        // bounds how long a limited app is visible/interactive before the block screen
        // catches it - 1000ms made that lag noticeable on tap.
        private const val POLL_INTERVAL_MILLIS = 300L
        private const val LOOKBACK_WINDOW_MILLIS = 10_000L
        private const val SWITCH_DEBOUNCE_MILLIS = 1200L
        const val ACTION_STOP = "com.dupati.scrollsense.ACTION_STOP_USAGE_WATCHER"

        @Volatile
        var isRunning: Boolean = false
            private set
    }
}
