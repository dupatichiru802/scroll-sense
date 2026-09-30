package com.dupati.scrollsense

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.PixelFormat
import android.net.Uri
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.util.Locale
import kotlin.math.abs

class OverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private var bubbleView: View? = null
    private var limitBlockView: View? = null
    private lateinit var timerContainer: View
    private lateinit var dayTotalText: TextView
    private lateinit var sessionText: TextView
    private lateinit var mascotBackView: View
    private lateinit var mascotBackIcon: ImageView
    private lateinit var messageContainer: View
    private lateinit var messageText: TextView
    private lateinit var messageIcon: ImageView
    private var mascotAnimator: ObjectAnimator? = null
    private val handler = Handler(Looper.getMainLooper())

    // Today's persisted total as of when this session started, plus how long this session has run.
    private var baseMillis: Long = 0
    private var sessionStartRealtime: Long = 0
    private var sessionStartWallClock: Long = 0
    private var lastPersistRealtime: Long = 0

    // Which watched app triggered this session, if any (null for manual/"Always" starts).
    private var trackedPackageName: String? = null

    // Whether the tick loop is currently paused for a screen-off period - see
    // screenStateReceiver/pauseTicking/resumeTicking. Nobody's looking at the screen
    // while it's off, so time (and reminders/glow/news, which all ride on the same
    // tick loop) shouldn't keep accruing until it comes back on.
    private var pausedForScreenOff = false
    private var screenOffRealtime: Long = 0L

    private val screenStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> pauseTicking()
                Intent.ACTION_SCREEN_ON -> resumeTicking()
            }
        }
    }

    /** Flushes accrued time and stops the tick loop the moment the screen turns off. */
    private fun pauseTicking() {
        if (pausedForScreenOff) return
        pausedForScreenOff = true
        val now = SystemClock.elapsedRealtime()
        persistProgress(now)
        screenOffRealtime = now
        handler.removeCallbacks(tickRunnable)
    }

    /** Resumes the tick loop on screen-on, rebasing timestamps by exactly how long the
     *  screen was off so that period isn't counted as elapsed time once things start
     *  ticking again. */
    private fun resumeTicking() {
        if (!pausedForScreenOff) return
        pausedForScreenOff = false
        val now = SystemClock.elapsedRealtime()
        val offDuration = now - screenOffRealtime
        sessionStartRealtime += offDuration
        lastPersistRealtime = now
        handler.post(tickRunnable)
    }

    // How many reminder-interval thresholds have fired during the CURRENT session.
    // Reset to 0 whenever a new session starts (see onCreate/switchTrackedPackage).
    private var sessionRemindersFired: Int = 0

    // Same idea as sessionRemindersFired, but for the bubble glow pulse.
    private var sessionGlowsFired: Int = 0

    // Cached RSS headlines, refetched periodically rather than on every pulse - see
    // checkNews/maybeRefreshNews. Which of these have already been shown today is
    // tracked persistently in TimerPrefs, not here, so it survives this service being
    // torn down and recreated (see showNextHeadline).
    private var newsHeadlines: List<NewsItem> = emptyList()
    private var newsLastFetchRealtime: Long = 0L
    private var newsFetchInFlight: Boolean = false

    // Whether the bubble's message face is currently showing a news headline rather
    // than a reminder/limit message - changes what tapping it does (open the article
    // vs. open the app). Reset to false at the top of every revealMessage() call.
    private var currentMessageIsNews: Boolean = false
    private var currentNewsLink: String? = null

    private val tickRunnable = object : Runnable {
        override fun run() {
            val now = SystemClock.elapsedRealtime()
            val sessionElapsed = now - sessionStartRealtime
            // baseMillis already includes everything persisted up to lastPersistRealtime,
            // so only the not-yet-persisted delta should be added here — adding the full
            // sessionElapsed double-counts the portion already folded into baseMillis by
            // earlier persistProgress() calls, inflating the displayed total over time.
            dayTotalText.text = "T " + formatHoursMinutes(baseMillis + (now - lastPersistRealtime))
            sessionText.text = formatDuration(sessionElapsed)

            if (now - lastPersistRealtime >= 5000) {
                persistProgress(now)
            }
            checkReminder(now)
            checkAppLimit(now)
            checkGlow(now)
            checkNews(now)
            handler.postDelayed(this, 500)
        }
    }

    /** Nudges the user once they're close to (80%) a watched app's daily limit, then
     *  covers the app with a full-screen block once the limit itself is hit - re-shown
     *  every tick for as long as they stay over, including if they leave and come back
     *  later the same day, unless they've PIN-unlocked it for the rest of today (see
     *  showLimitBlock). No-ops whenever trackedPackageName is null - in Always mode
     *  that's whenever UsageWatcherService reports the foreground app isn't one being
     *  watched (see ACTION_CLEAR_TRACKED_PACKAGE), so there's no specific app to hold a
     *  limit against right now. */
    private fun checkAppLimit(nowRealtime: Long) {
        val packageName = trackedPackageName ?: return
        val limitMinutes = TimerPrefs.getAppLimitMinutes(this, packageName)
        if (limitMinutes <= 0) {
            hideLimitBlock()
            return
        }

        val persistedMillis = TimerPrefs.getTodayMillisForPackage(this, packageName)
        val liveMillis = persistedMillis + (nowRealtime - lastPersistRealtime)
        val limitMillis = limitMinutes * 60_000L

        if (liveMillis >= limitMillis) {
            if (TimerPrefs.getLimitOverrideToday(this, packageName)) {
                hideLimitBlock()
                return
            }
            val appLabel = appLabelOrNull(packageName) ?: "this app"
            val totalMinutes = roundToMinutes(liveMillis)
            showLimitBlock(
                packageName,
                getString(R.string.limit_block_message_format, appLabel, totalMinutes, limitMinutes)
            )
        } else {
            hideLimitBlock()
            if (liveMillis >= (limitMillis * 0.8).toLong()) {
                if (TimerPrefs.hasWarnedLimitToday(this, packageName)) return
                TimerPrefs.markLimitWarnedToday(this, packageName)
                vibrateReminder()
                val appLabel = appLabelOrNull(packageName) ?: "this app"
                val usedMinutes = roundToMinutes(liveMillis)
                revealMessage(getString(R.string.app_limit_warning_format, appLabel, usedMinutes, limitMinutes))
            }
        }
    }

    private fun checkReminder(nowRealtime: Long) {
        val interval = TimerPrefs.getReminderIntervalMillis(this)
        if (interval <= 0) return

        // Fires on how long the CURRENT session has run, not today's cumulative total —
        // a fresh session should always take a full interval to earn its first reminder,
        // rather than inheriting a near-miss from usage earlier today. sessionRemindersFired
        // is in-memory and reset alongside sessionStartRealtime, so it naturally restarts
        // with every new session.
        val sessionElapsed = nowRealtime - sessionStartRealtime
        val dueCount = (sessionElapsed / interval).toInt()
        if (dueCount > sessionRemindersFired) {
            sessionRemindersFired = dueCount
            vibrateReminder()
            val persistedMillis = trackedPackageName?.let { TimerPrefs.getTodayMillisForPackage(this, it) }
                ?: TimerPrefs.getTodayMillis(this)
            // Include the not-yet-persisted delta from this session so the "today" figure
            // shown in the message stays live between the 5s persistence checkpoints.
            val liveMillisToday = persistedMillis + (nowRealtime - lastPersistRealtime)
            // Round rather than truncate for display: a 45s-old session should read
            // "1 min", not "0 min".
            val displayMinutesToday = roundToMinutes(liveMillisToday)
            val displaySessionMinutes = roundToMinutes(sessionElapsed)
            showReminderMessage(displayMinutesToday, displaySessionMinutes)
        }
    }

    /** Pulses the bubble with a brief glow at the configured interval - a lighter-touch
     *  nudge than the reminder message, since it doesn't interrupt whatever's on the
     *  timer face. Same per-session, fires-once-per-threshold shape as checkReminder. */
    private fun checkGlow(nowRealtime: Long) {
        val interval = TimerPrefs.getGlowIntervalMillis(this)
        if (interval <= 0) return

        val sessionElapsed = nowRealtime - sessionStartRealtime
        val dueCount = (sessionElapsed / interval).toInt()
        if (dueCount > sessionGlowsFired) {
            sessionGlowsFired = dueCount
            glowBubble()
        }
    }

    /** Delivers a headline from the cached RSS feed as soon as a fresh, unseen one is
     *  available, rather than on a fixed interval - checks for a refresh every tick
     *  (throttled internally, see maybeRefreshNews) and reveals the next headline the
     *  moment the bubble isn't already showing some other message. Off entirely if the
     *  user has disabled news, since unlike reminders/glow this reaches a third party. */
    private fun checkNews(nowRealtime: Long) {
        if (!TimerPrefs.isNewsEnabled(this)) return
        maybeRefreshNews(nowRealtime)
        if (messageContainer.visibility == View.VISIBLE) return
        showNextHeadline()
    }

    /** Refetches the RSS feed on a background thread if the cache is empty or older
     *  than NEWS_REFRESH_INTERVAL_MILLIS - not on every single pulse, since the feed
     *  itself doesn't update that often and there's no need for a network round trip
     *  each time. A fetch already in flight is left to finish rather than doubled up.
     *  Any tracked stocks' news feeds are folded in alongside the topic feeds, so
     *  stock headlines get mixed into the same pulse rotation instead of needing a
     *  separate delivery mechanism - each one already carries its stock symbol as its
     *  topicLabel (see StockFetcher.fetchStockNews), so it reads as e.g. "AAPL: ..."
     *  the same way a topic headline reads as "US: ...". */
    private fun maybeRefreshNews(nowRealtime: Long) {
        if (newsFetchInFlight) return
        if (newsHeadlines.isNotEmpty() && nowRealtime - newsLastFetchRealtime < NEWS_REFRESH_INTERVAL_MILLIS) return

        newsFetchInFlight = true
        val topicFeeds = NewsTopics.feedsFor(this, TimerPrefs.getNewsTopics(this))
        val stockFeeds = TimerPrefs.getWatchedStocks(this).map { it to StockFetcher.stockNewsFeedUrl(it) }
        val feeds = topicFeeds + stockFeeds
        Thread {
            val items = NewsFetcher.fetchHeadlines(feeds)
            handler.post {
                newsFetchInFlight = false
                if (items.isNotEmpty()) {
                    newsHeadlines = items
                    newsLastFetchRealtime = SystemClock.elapsedRealtime()
                }
            }
        }.start()
    }

    /** Shows the first cached headline not already shown today - checked against a
     *  persisted "seen" set (see TimerPrefs.getSeenNewsLinksToday) rather than an
     *  in-memory cursor, so repeatedly leaving and returning to a watched app can't
     *  reset back to the top of the feed and repeat the same headline. A no-op if
     *  nothing's been fetched yet, or if every cached headline has already been shown
     *  today - it'll pick up again once maybeRefreshNews finds something new. */
    private fun showNextHeadline() {
        if (newsHeadlines.isEmpty()) return
        val seen = TimerPrefs.getSeenNewsLinksToday(this)
        val item = newsHeadlines.firstOrNull { it.link !in seen } ?: return
        revealMessage("${item.topicLabel}: ${item.title}", holdMillis = NEWS_MESSAGE_HOLD_MILLIS)
        currentMessageIsNews = true
        currentNewsLink = item.link
        TimerPrefs.markNewsLinkSeenToday(this, item.link)
    }

    /** Opens a news headline's article in the browser - the tap action on the message
     *  bubble when it's showing news instead of a reminder. Launched from a Service so
     *  it needs its own new task, same as openApp()/goHome(). */
    private fun openLink(url: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            // No app can handle it (no browser installed) - nothing sensible to do
            // from a background service, so just drop it.
        }
    }

    private fun roundToMinutes(millis: Long): Long = Math.round(millis / 60_000.0)

    /** Swaps the round bubble for a message-bubble reading e.g. "You've been watching
     *  Instagram for 15 min", so the reminder is unmistakable, then reverts after a
     *  few seconds. In Always mode there's no specific tracked app, so the {app}
     *  placeholder falls back to talking about the phone as a whole instead. */
    private fun showReminderMessage(minutes: Long, sessionMinutes: Long) {
        val appLabel = trackedPackageName?.let { appLabelOrNull(it) }
            ?: getString(R.string.reminder_fallback_label_always)
        val template = TimerPrefs.getActiveMessageTemplate(this)
        val text = template
            .replace("{app}", appLabel)
            .replace("{minutes}", minutes.toString())
            .replace("{session}", sessionMinutes.toString())
        revealMessage(text)
    }

    /** Shared by the interval reminder and the daily-limit nudges: swaps the round
     *  bubble for the given message text, then reverts after a few seconds.
     *  [holdMillis], when given, overrides the usual reminder-display-mode/configured
     *  duration with a fixed hold time - used for news headlines, which run much
     *  longer than a reminder ping and need more time to read, but should still
     *  eventually revert to the session clock on their own rather than getting stuck
     *  showing stale news indefinitely (which is what happens if the message is left
     *  up forever: it just dims in place instead of ever going back). */
    private fun revealMessage(text: String, holdMillis: Long? = null) {
        // Defaults to a non-news message; showNextHeadline() flips this back on right
        // after calling in here, once it's known the text being shown is a headline.
        currentMessageIsNews = false
        currentNewsLink = null

        handler.removeCallbacks(dimRunnable)
        handler.removeCallbacks(messageHideRunnable)
        bubbleView?.let {
            it.animate().cancel()
            it.alpha = 1f
        }

        // Cleanly cancel any in-progress or completed coin flip so the timer bubble
        // is the one underneath once the message clears, not a half-flipped view.
        handler.removeCallbacks(flipBackRunnable)
        dimmedToMascot = false
        mascotBackView.visibility = View.GONE
        mascotBackView.rotationY = 0f
        timerContainer.rotationY = 0f

        messageText.text = text
        messageIcon.setImageResource(mascotDrawableRes(TimerPrefs.getMascot(this)))

        timerContainer.visibility = View.GONE
        messageContainer.visibility = View.VISIBLE
        startMascotBounce()
        // In permanent mode the message just stays up until tapped away (see the click
        // listener set on messageContainer in showBubble()) instead of auto-hiding
        // after the configured duration - unless holdMillis overrides that with its
        // own fixed timer regardless of the display-mode setting.
        if (holdMillis != null) {
            handler.postDelayed(messageHideRunnable, holdMillis)
        } else if (TimerPrefs.getReminderDisplayMode(this) == TimerPrefs.REMINDER_DISPLAY_TIMED) {
            handler.postDelayed(messageHideRunnable, TimerPrefs.getMessageDisplayMillis(this))
        }
    }

    // Which package the currently-shown block screen belongs to, so the PIN-unlock
    // button knows what to grant an override for. Null whenever no block is showing.
    private var limitBlockPackageName: String? = null

    /** Full-screen, touch-blocking overlay covering the watched app once its daily
     *  limit is hit - re-created fresh each time (unlike the little message bubble)
     *  since it needs to be added/removed as a distinct top-level window that actually
     *  intercepts touches, rather than just swapping content within the bubble. Closes
     *  the app immediately (see goHome() below) but otherwise stays up - covering
     *  whatever's behind it, home screen included - until the user taps "Go to Home
     *  Screen" or enters a correct Parental Lock PIN; UsageWatcherService's own
     *  foreground-app tracking is ignored the whole time (see isBlockActive), since the
     *  only reason the foreground app changes while this is up is the goHome() call
     *  below, not anything the user did. */
    private fun showLimitBlock(packageName: String, text: String) {
        limitBlockPackageName = packageName
        isBlockActive = true

        val existing = limitBlockView
        if (existing != null) {
            existing.findViewById<TextView>(R.id.limitBlockMessage).text = text
            return
        }

        val view = LayoutInflater.from(this).inflate(R.layout.overlay_limit_block, null)
        view.findViewById<TextView>(R.id.limitBlockMessage).text = text
        view.findViewById<ImageView>(R.id.limitBlockIcon)
            .setImageResource(mascotDrawableRes(TimerPrefs.getMascot(this)))

        // The app is already closed automatically (see goHome() below) the moment this
        // screen appears - this button's job is letting the user dismiss the block
        // itself and get back to a usable home screen, rather than just re-sending an
        // already-redundant home intent.
        view.findViewById<Button>(R.id.limitBlockHomeButton).setOnClickListener {
            goHome()
            switchTrackedPackage(null)
        }

        val unlockLink = view.findViewById<TextView>(R.id.limitBlockUnlockLink)
        val pinEntry = view.findViewById<LinearLayout>(R.id.limitBlockPinEntry)
        val pinInput = view.findViewById<EditText>(R.id.limitBlockPinInput)
        val pinError = view.findViewById<TextView>(R.id.limitBlockPinError)

        if (TimerPrefs.hasParentalPin(this)) {
            unlockLink.visibility = View.VISIBLE
            unlockLink.setOnClickListener {
                unlockLink.visibility = View.GONE
                pinEntry.visibility = View.VISIBLE
            }
            view.findViewById<Button>(R.id.limitBlockPinConfirmButton).setOnClickListener {
                val pin = pinInput.text.toString()
                val pkg = limitBlockPackageName
                if (pkg != null && TimerPrefs.verifyParentalPin(this, pin)) {
                    TimerPrefs.setLimitOverrideToday(this, pkg)
                    hideLimitBlock()
                } else {
                    pinError.visibility = View.VISIBLE
                    pinInput.text.clear()
                }
            }
        }

        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayType,
            0,
            PixelFormat.TRANSLUCENT
        )

        windowManager.addView(view, params)
        limitBlockView = view
        vibrateReminder()

        // The overlay covers the screen, but the blocked app is still technically the
        // foreground activity underneath it - so its video/audio (a Reel, for example)
        // just keeps playing behind the block. Backgrounding it for real, the same way
        // the "Go to Home Screen" button does, is what actually stops that, since an
        // app losing foreground/visibility is what triggers its own pause behaviour.
        goHome()
    }

    private fun hideLimitBlock() {
        limitBlockView?.let { windowManager.removeView(it) }
        limitBlockView = null
        limitBlockPackageName = null
        isBlockActive = false
    }

    /** Sends the user to the device home screen - the "get out of the blocked app"
     *  action on the limit-block screen. Launched from a Service so it needs its own
     *  new task, same as [openApp]. */
    private fun goHome() {
        val intent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        startActivity(intent)
    }

    /** Brings the app to the foreground, launched from a Service so it needs its own
     *  new task. Used when a reminder/limit message is tapped, so there's a quick way
     *  to reach the settings that control it. */
    private fun openApp() {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        startActivity(intent)
    }

    private val flipBackRunnable = Runnable { flipToTimerSide() }

    /** Swipe-down handler for both faces of the bubble: flips whichever side is
     *  showing over to the other one, so the gesture works the same way there and
     *  back instead of only being able to reveal the mascot. */
    private fun toggleFlip() {
        if (mascotBackView.visibility == View.VISIBLE) {
            handler.removeCallbacks(flipBackRunnable)
            flipToTimerSide()
        } else {
            flipToMascotSide()
        }
    }

    /** Flips the bubble over like a coin to reveal the current mascot on the other
     *  side. Two quarter-turns (0->90 on the way out, -90->0 on the way in) with the
     *  content swapped at the midpoint, rather than one 360 spin, is what actually
     *  sells the illusion of a single object turning over instead of just spinning in
     *  place. A message-triggered flip gets the heart burst and flips back to the
     *  timer on its own after a few seconds; the idle/dim flip (see dimRunnable) gets
     *  neither - it stays on the mascot side, quietly, until the user touches the
     *  bubble again. */
    private fun flipToMascotSide(withHeartBurst: Boolean = true, autoRevertMillis: Long? = TimerPrefs.getMessageDisplayMillis(this) + 400) {
        val density = resources.displayMetrics.density
        timerContainer.cameraDistance = 8000 * density
        mascotBackView.cameraDistance = 8000 * density

        mascotBackIcon.setImageResource(mascotDrawableRes(TimerPrefs.getMascot(this)))

        ObjectAnimator.ofFloat(timerContainer, "rotationY", 0f, 90f).apply {
            duration = 200
            interpolator = AccelerateDecelerateInterpolator()
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    timerContainer.visibility = View.GONE
                    timerContainer.rotationY = 0f
                    mascotBackView.rotationY = -90f
                    mascotBackView.visibility = View.VISIBLE
                    if (withHeartBurst) playHeartBurst()
                    ObjectAnimator.ofFloat(mascotBackView, "rotationY", -90f, 0f).apply {
                        duration = 200
                        interpolator = AccelerateDecelerateInterpolator()
                        start()
                    }
                }
            })
            start()
        }

        handler.removeCallbacks(flipBackRunnable)
        if (autoRevertMillis != null) {
            handler.postDelayed(flipBackRunnable, autoRevertMillis)
        }
    }

    /** Flips back from the mascot side to the normal timer bubble. */
    private fun flipToTimerSide() {
        if (mascotBackView.visibility != View.VISIBLE) return
        ObjectAnimator.ofFloat(mascotBackView, "rotationY", 0f, 90f).apply {
            duration = 200
            interpolator = AccelerateDecelerateInterpolator()
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    mascotBackView.visibility = View.GONE
                    mascotBackView.rotationY = 0f
                    timerContainer.rotationY = -90f
                    timerContainer.visibility = View.VISIBLE
                    ObjectAnimator.ofFloat(timerContainer, "rotationY", -90f, 0f).apply {
                        duration = 200
                        interpolator = AccelerateDecelerateInterpolator()
                        start()
                    }
                }
            })
            start()
        }
    }

    /** A little burst of hearts/kisses floating up and fading out around the mascot
     *  when it's revealed, for some extra charm on the flip. */
    /** Hearts are added to the mascot's own 92dp box (not the whole bubble window),
     *  and travel only a few dp, so they read as sparkles rising off the mascot's
     *  face rather than flying out past the bubble's edge. */
    private fun playHeartBurst() {
        val root = mascotBackView as? FrameLayout ?: return
        val density = resources.displayMetrics.density
        val emojis = listOf("💕", "😘", "💖")
        emojis.forEachIndexed { index, emoji ->
            val heart = TextView(this).apply {
                text = emoji
                textSize = 14f
                alpha = 0f
            }
            val params = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
            params.gravity = Gravity.CENTER
            params.leftMargin = ((index - 1) * 16 * density).toInt()
            params.topMargin = -(6 * density).toInt()
            root.addView(heart, params)

            heart.animate()
                .translationY(-14 * density)
                .alpha(1f)
                .setStartDelay((index * 500).toLong())
                .setDuration(400)
                .withEndAction {
                    heart.animate()
                        .translationY(-26 * density)
                        .alpha(0f)
                        .setDuration(700)
                        .withEndAction { root.removeView(heart) }
                        .start()
                }
                .start()
        }
    }

    /** A little repeating hop so the mascot feels alive while the message is up. */
    private fun startMascotBounce() {
        mascotAnimator?.cancel()
        messageIcon.translationY = 0f
        val animator = ObjectAnimator.ofFloat(messageIcon, "translationY", 0f, -10f, 0f).apply {
            duration = 450
            repeatCount = ObjectAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
        }
        mascotAnimator = animator
        animator.start()
    }

    private fun stopMascotBounce() {
        mascotAnimator?.cancel()
        mascotAnimator = null
        messageIcon.translationY = 0f
    }

    private val messageHideRunnable = Runnable {
        stopMascotBounce()
        messageContainer.visibility = View.GONE
        timerContainer.visibility = View.VISIBLE
        scheduleDim()
    }

    private fun appLabelOrNull(packageName: String): String? = try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(packageName, 0)).toString()
    } catch (e: PackageManager.NameNotFoundException) {
        null
    }

    private fun vibrateReminder() {
        val pattern = longArrayOf(0, 250, 100, 250)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager = getSystemService(VibratorManager::class.java)
            vibratorManager.defaultVibrator.vibrate(
                VibrationEffect.createWaveform(pattern, -1)
            )
        } else {
            @Suppress("DEPRECATION")
            val vibrator = getSystemService(VIBRATOR_SERVICE) as Vibrator
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(pattern, -1)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        baseMillis = TimerPrefs.getTodayMillis(this)
        sessionStartRealtime = SystemClock.elapsedRealtime()
        sessionStartWallClock = System.currentTimeMillis()
        lastPersistRealtime = sessionStartRealtime
        sessionRemindersFired = 0
        sessionGlowsFired = 0
        startForeground(NOTIFICATION_ID, buildNotification())
        showBubble()
        handler.post(tickRunnable)
        registerReceiver(screenStateReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        })
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_REFRESH_THEME -> applyBubbleTheme()
            ACTION_REFRESH_NEWS -> {
                // Drop the cache so the next news pulse refetches under the newly
                // chosen topics, instead of waiting out NEWS_REFRESH_INTERVAL_MILLIS
                // or showing stale headlines from the topics that were picked before.
                newsHeadlines = emptyList()
                newsLastFetchRealtime = 0L
            }
            // Always mode's foreground detection sends this when the current app isn't
            // one being watched, so time (and any limit) stops being attributed to
            // whichever watched app was tracked before.
            ACTION_CLEAR_TRACKED_PACKAGE -> {
                if (trackedPackageName != null) switchTrackedPackage(null)
            }
            else -> {
                // Also handles switching directly between two different watched apps
                // without an intervening hide, so time keeps attributing correctly.
                intent?.getStringExtra(EXTRA_TRACKED_PACKAGE)?.let { pkg ->
                    if (pkg != trackedPackageName) switchTrackedPackage(pkg)
                }
            }
        }
        return START_STICKY
    }

    /** Flushes accrued time to whichever app was tracked before, then rebases the
     *  displayed timer onto the new app's own persisted total (or, if [newPackage] is
     *  null, back onto today's overall total) so the bubble reflects time spent in
     *  that specific app, not the combined watched-apps total. [newPackage] is null
     *  when Always mode's foreground detection (see UsageWatcherService) finds the
     *  current app isn't one being watched - there's no specific app to attribute
     *  time, or a limit, to right now. */
    private fun switchTrackedPackage(newPackage: String?) {
        val now = SystemClock.elapsedRealtime()
        persistProgress(now)
        finalizeCurrentSession(now)
        trackedPackageName = newPackage
        baseMillis = newPackage?.let { TimerPrefs.getTodayMillisForPackage(this, it) }
            ?: TimerPrefs.getTodayMillis(this)
        sessionStartRealtime = now
        sessionStartWallClock = System.currentTimeMillis()
        lastPersistRealtime = now
        sessionRemindersFired = 0
        sessionGlowsFired = 0

        // A block screen belonging to whichever app was tracked before (if any) has no
        // business surviving the switch - checkAppLimit() re-imposes it fresh below if
        // the new package is still over its own limit, but has no way to know to clear
        // a stale one for the old package if the new one is null or under its limit.
        hideLimitBlock()

        // If this app is still over its limit, re-impose the block right away rather
        // than waiting for the first tick. Leaving a blocked app (e.g. tapping "Go to
        // Home Screen") stops this whole service in Auto mode, so bouncing straight
        // back in recreates it from scratch - without this, there'd be a brief gap
        // where the app is fully usable again while UsageWatcherService rediscovers it.
        checkAppLimit(now)
    }

    /** Records the just-finished continuous viewing session (with its real start time)
     *  so the daily report can list individual sessions, not just a running total. */
    private fun finalizeCurrentSession(nowRealtime: Long) {
        val duration = nowRealtime - sessionStartRealtime
        TimerPrefs.addSessionRecord(this, trackedPackageName, sessionStartWallClock, duration)
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        unregisterReceiver(screenStateReceiver)
        val now = SystemClock.elapsedRealtime()
        persistProgress(now)
        finalizeCurrentSession(now)
        handler.removeCallbacks(tickRunnable)
        handler.removeCallbacks(dimRunnable)
        handler.removeCallbacks(messageHideRunnable)
        handler.removeCallbacks(flipBackRunnable)
        mascotAnimator?.cancel()
        bubbleView?.let { windowManager.removeView(it) }
        bubbleView = null
        hideLimitBlock()
    }

    private fun persistProgress(nowRealtime: Long) {
        val delta = nowRealtime - lastPersistRealtime
        if (delta > 0) {
            TimerPrefs.addTodayMillis(this, delta)
            trackedPackageName?.let { TimerPrefs.addTodayMillisForPackage(this, it, delta) }
            // Re-derive from the persisted total instead of just adding delta: if
            // midnight passed since the last persist, TimerPrefs.addTodayMillis()
            // above already reset the stored total for the new day, but a plain
            // "baseMillis += delta" would have kept compounding onto yesterday's
            // value forever, since the bubble can run continuously across midnight
            // (Always mode, or a session that happens to span it).
            baseMillis = trackedPackageName?.let { TimerPrefs.getTodayMillisForPackage(this, it) }
                ?: TimerPrefs.getTodayMillis(this)
            lastPersistRealtime = nowRealtime
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // Whether the bubble is currently showing the mascot face *because it dimmed*,
    // as opposed to a message flip or a manual swipe - only then does waking up flip
    // it back to the timer side; a manual flip is left exactly where the user put it.
    private var dimmedToMascot = false

    private val dimRunnable = Runnable {
        if (mascotBackView.visibility != View.VISIBLE) {
            dimmedToMascot = true
            flipToMascotSide(withHeartBurst = false, autoRevertMillis = null)
        }
        bubbleView?.animate()?.alpha(DIM_ALPHA)?.setDuration(400)?.start()
    }

    private fun scheduleDim() {
        handler.removeCallbacks(dimRunnable)
        handler.postDelayed(dimRunnable, TimerPrefs.getDimDelayMillis(this))
    }

    private fun wakeFromDim(view: View) {
        handler.removeCallbacks(dimRunnable)
        view.animate().alpha(1f).setDuration(150).start()
        if (dimmedToMascot) {
            dimmedToMascot = false
            flipToTimerSide()
        }
    }

    /** Periodic ambient nudge, lighter-touch than the reminder message: the bubble's
     *  ring and shadow briefly widen and fade back, like it's glowing, instead of
     *  swapping to a text message. Wakes it from dim first so the pulse is actually
     *  visible, then re-arms the dim timer once it's done. */
    private fun glowBubble() {
        val root = bubbleView ?: return
        wakeFromDim(root)

        val accentColor = ContextCompat.getColor(this, bubbleColorRes(TimerPrefs.getBubbleColor(this)))
        val density = resources.displayMetrics.density
        val baseStrokePx = (2 * density).toInt()
        val glowStrokePx = (7 * density).toInt()
        val baseElevation = 8 * density
        val glowElevation = 22 * density
        val drawable = timerContainer.background?.mutate() as? GradientDrawable

        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 450
            repeatCount = 1
            repeatMode = ValueAnimator.REVERSE
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { anim ->
                val fraction = anim.animatedValue as Float
                val strokePx = (baseStrokePx + fraction * (glowStrokePx - baseStrokePx)).toInt()
                drawable?.setStroke(strokePx, accentColor)
                timerContainer.elevation = baseElevation + fraction * (glowElevation - baseElevation)
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    drawable?.setStroke(baseStrokePx, accentColor)
                    timerContainer.elevation = baseElevation
                    scheduleDim()
                }
            })
            start()
        }
    }

    private fun applyBubbleTheme() {
        val accentColor = ContextCompat.getColor(this, bubbleColorRes(TimerPrefs.getBubbleColor(this)))
        timerContainer.setBackgroundResource(R.drawable.bubble_background_light)
        dayTotalText.setTextColor(accentColor)
        sessionText.setTextColor(accentColor)
        mascotBackView.setBackgroundResource(R.drawable.bubble_background_light)
        messageContainer.setBackgroundResource(R.drawable.bubble_message_background_light)
        messageText.setTextColor(accentColor)

        // The background drawables' stroke colour is just a static default in XML;
        // override it here so the selected bubble colour reaches the outline too.
        // mutate() first so this doesn't repaint every view sharing the cached drawable.
        val strokeWidthPx = (2 * resources.displayMetrics.density).toInt()
        (timerContainer.background.mutate() as? GradientDrawable)?.setStroke(strokeWidthPx, accentColor)
        (mascotBackView.background.mutate() as? GradientDrawable)?.setStroke(strokeWidthPx, accentColor)
        (messageContainer.background.mutate() as? GradientDrawable)?.setStroke(strokeWidthPx, accentColor)
    }

    private fun showBubble() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val view = LayoutInflater.from(this).inflate(R.layout.overlay_bubble, null)
        timerContainer = view.findViewById(R.id.bubbleTimerContainer)
        dayTotalText = view.findViewById(R.id.bubbleDayTotalText)
        sessionText = view.findViewById(R.id.bubbleSessionText)
        mascotBackView = view.findViewById(R.id.bubbleMascotBackView)
        mascotBackIcon = view.findViewById(R.id.bubbleMascotBackIcon)
        messageContainer = view.findViewById(R.id.bubbleMessageContainer)
        messageText = view.findViewById(R.id.bubbleMessageText)
        messageIcon = view.findViewById(R.id.bubbleMessageIcon)
        // Tapping the message opens the app instead of dismissing it, so there's a
        // quick way to reach the reminder settings right from the nudge itself.
        messageContainer.setOnClickListener {
            val link = currentNewsLink
            if (currentMessageIsNews && link != null) openLink(link) else openApp()
        }
        applyBubbleTheme()

        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.END
        params.x = 24
        params.y = 200

        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var touchDownTimeMs = 0L

        // Shared by both faces of the bubble - without this, the mascot side (shown
        // after a coin flip) would have no touch handling at all, since it's a
        // separate view from the timer side and previously only that one had a
        // listener attached, leaving the bubble undraggable/untappable while flipped.
        val bubbleTouchListener = View.OnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    touchDownTimeMs = SystemClock.uptimeMillis()
                    wakeFromDim(view)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()
                    // Gravity is TOP|END, so moving right shrinks the x offset from the right edge.
                    params.x = initialX - dx
                    params.y = initialY + dy
                    windowManager.updateViewLayout(view, params)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val dx = event.rawX - initialTouchX
                    val dy = event.rawY - initialTouchY
                    val swipeThreshold = 40 * resources.displayMetrics.density
                    val elapsedMs = SystemClock.uptimeMillis() - touchDownTimeMs
                    // A flip is a quick flick, not a drag: fast, mostly-downward, and
                    // short-lived. A slower or longer drag is someone repositioning
                    // the bubble, so it's left wherever it landed instead.
                    val isFlick = elapsedMs < 300 && dy > swipeThreshold && dy > abs(dx) * 1.5f
                    if (abs(dx) < 8 && abs(dy) < 8) {
                        v.performClick()
                    } else if (isFlick) {
                        // Snap back to where it started so the flip reads as an
                        // in-place gesture, not a side effect of having dragged it.
                        params.x = initialX
                        params.y = initialY
                        windowManager.updateViewLayout(view, params)
                        toggleFlip()
                    }
                    scheduleDim()
                    true
                }
                else -> false
            }
        }
        timerContainer.setOnTouchListener(bubbleTouchListener)
        mascotBackView.setOnTouchListener(bubbleTouchListener)

        windowManager.addView(view, params)
        bubbleView = view
        scheduleDim()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // The bubble sits in the screen-edge back-gesture zone; without this the
            // gesture nav TouchInteractionService swallows touches before they reach it.
            view.post {
                view.systemGestureExclusionRects = listOf(
                    android.graphics.Rect(0, 0, view.width, view.height)
                )
            }
        }
    }

    private fun buildNotification(): Notification {
        val channelId = "overlay_timer_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId, "Scroll Sense", NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }

        val stopIntent = Intent(this, OverlayService::class.java).apply { action = ACTION_STOP }
        val stopPendingIntent = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("Scroll Sense running")
            .setContentText("Tracking today's total time in watched apps")
            .setSmallIcon(android.R.drawable.ic_menu_recent_history)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopPendingIntent)
            .build()
    }

    private fun formatDuration(millis: Long): String {
        val totalSeconds = millis / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return String.format(Locale.getDefault(), "%02d:%02d:%02d", hours, minutes, seconds)
    }

    private fun formatHoursMinutes(millis: Long): String {
        val totalMinutes = millis / 60_000
        val hours = totalMinutes / 60
        val minutes = totalMinutes % 60
        return String.format(Locale.getDefault(), "%02d:%02d", hours, minutes)
    }

    companion object {
        private const val NOTIFICATION_ID = 1001
        const val ACTION_STOP = "com.dupati.scrollsense.ACTION_STOP"
        const val ACTION_REFRESH_THEME = "com.dupati.scrollsense.ACTION_REFRESH_THEME"
        const val ACTION_REFRESH_NEWS = "com.dupati.scrollsense.ACTION_REFRESH_NEWS"
        const val ACTION_CLEAR_TRACKED_PACKAGE = "com.dupati.scrollsense.ACTION_CLEAR_TRACKED_PACKAGE"
        const val EXTRA_TRACKED_PACKAGE = "com.dupati.scrollsense.EXTRA_TRACKED_PACKAGE"
        private const val DIM_ALPHA = 0.15f
        private const val NEWS_REFRESH_INTERVAL_MILLIS = 20 * 60 * 1000L
        // Long enough to actually read a headline, unlike the short, user-configurable
        // reminder message duration - but still finite, so the bubble always finds its
        // way back to the session clock rather than getting stuck showing old news.
        private const val NEWS_MESSAGE_HOLD_MILLIS = 12_000L

        @Volatile
        var isRunning: Boolean = false
            private set

        // True for as long as the full-screen limit-block window is up. UsageWatcherService
        // reads this to tear the block down immediately (rather than waiting on the usual
        // bubble-hide delay, then a full service stop) the moment it notices the user has
        // left the blocked app - see showLimitBlock/hideLimitBlock and
        // UsageWatcherService.checkForegroundApp.
        @Volatile
        var isBlockActive: Boolean = false
            private set
    }
}
