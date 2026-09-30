package com.dupati.scrollsense

import android.content.Context
import android.content.pm.PackageManager
import android.view.View
import android.widget.ImageView
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Locale

/** Renders a duration as e.g. "1h 25 mins" (or just "25 mins" under an hour), for
 *  human-facing totals — as opposed to the compact HH:MM:SS clock style used for
 *  live-ticking counters. */
fun formatFriendlyDuration(millis: Long): String {
    val totalMinutes = millis / 60_000
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    val minutesText = if (minutes == 1L) "1 min" else "$minutes mins"
    return if (hours > 0) "${hours}h $minutesText" else minutesText
}

/** One watched app's (or manual "Always"-mode time's) share of today's total, for
 *  chart/legend display - lighter-weight than the daily report's own entry type
 *  since it doesn't need an icon or a package name to drill into. */
data class AppTimeShare(val label: String, val millis: Long)

/** Today's time broken down by watched app, largest first, with any manual
 *  (Always-mode) time grouped under a single "Manual" entry. */
fun todayAppBreakdown(context: Context): List<AppTimeShare> {
    val pm = context.packageManager
    val perApp = TimerPrefs.getTodayPerAppMillis(context).mapNotNull { (packageName, millis) ->
        try {
            val label = pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
            AppTimeShare(label, millis)
        } catch (e: PackageManager.NameNotFoundException) {
            null
        }
    }
    val manualMillis = TimerPrefs.getTodaySessions(context)
        .filter { it.packageName == null }
        .sumOf { it.durationMillis }
    val withManual = if (manualMillis > 0) {
        perApp + AppTimeShare(context.getString(R.string.session_log_manual_label), manualMillis)
    } else {
        perApp
    }
    return withManual.sortedByDescending { it.millis }
}

/** Wires up a chevron-headed collapsible card section: tapping [header] toggles
 *  [content]'s visibility and spins [chevron] 0<->180 degrees, persisting the new
 *  state through [getExpanded]/[setExpanded]. Shared across activities that use this
 *  same pattern (MainActivity's cards, NewsActivity's topics/headlines cards, etc). */
fun setupCollapsibleSection(
    header: View,
    content: View,
    chevron: ImageView,
    getExpanded: () -> Boolean,
    setExpanded: (Boolean) -> Unit
) {
    fun apply(expanded: Boolean, animate: Boolean) {
        content.visibility = if (expanded) View.VISIBLE else View.GONE
        val targetRotation = if (expanded) 180f else 0f
        if (animate) {
            chevron.animate().rotation(targetRotation).setDuration(150).start()
        } else {
            chevron.rotation = targetRotation
        }
    }

    apply(getExpanded(), animate = false)
    header.setOnClickListener {
        val expanded = !getExpanded()
        setExpanded(expanded)
        apply(expanded, animate = true)
    }
}

/** Resolves the current theme's colorAccent - i.e. whichever bubble colour is
 *  selected - for text drawn in code rather than via a `?attr/colorAccent` XML
 *  reference (legend rows built at runtime, for instance). */
fun Context.themeAccentColor(): Int {
    val typedValue = android.util.TypedValue()
    theme.resolveAttribute(android.R.attr.colorAccent, typedValue, true)
    return typedValue.data
}

/** Palette cycled through (in ranking order, largest first) for per-app time charts. */
val CHART_COLORS = intArrayOf(
    0xFF0288D1.toInt(), // blue
    0xFFF57C00.toInt(), // orange
    0xFF2E7D32.toInt(), // green
    0xFF8E24AA.toInt(), // purple
    0xFFD81B60.toInt(), // pink
    0xFFC62828.toInt(), // red
    0xFF00897B.toInt(), // teal
    0xFF3949AB.toInt()  // indigo
)

/** Every selectable reminder-popup mascot, in display order. */
val ALL_MASCOTS = listOf(
    TimerPrefs.MASCOT_RABBIT,
    TimerPrefs.MASCOT_HAMSTER,
    TimerPrefs.MASCOT_DOG,
    TimerPrefs.MASCOT_CAT
)

fun mascotDrawableRes(mascot: String): Int = when (mascot) {
    TimerPrefs.MASCOT_HAMSTER -> R.drawable.ic_hamster_face
    TimerPrefs.MASCOT_DOG -> R.drawable.ic_dog_face
    TimerPrefs.MASCOT_CAT -> R.drawable.ic_cat_face
    else -> R.drawable.ic_bunny_face
}

fun mascotLabelRes(mascot: String): Int = when (mascot) {
    TimerPrefs.MASCOT_HAMSTER -> R.string.mascot_hamster
    TimerPrefs.MASCOT_DOG -> R.string.mascot_dog
    TimerPrefs.MASCOT_CAT -> R.string.mascot_cat
    else -> R.string.mascot_rabbit
}

/** Every selectable bubble colour, in display order. */
val ALL_BUBBLE_COLORS = listOf(
    TimerPrefs.BUBBLE_COLOR_BLUE,
    TimerPrefs.BUBBLE_COLOR_ORANGE,
    TimerPrefs.BUBBLE_COLOR_GREEN,
    TimerPrefs.BUBBLE_COLOR_PURPLE,
    TimerPrefs.BUBBLE_COLOR_PINK,
    TimerPrefs.BUBBLE_COLOR_RED
)

/** The colour resource for this bubble colour choice (app is light-only, so this is
 *  always the deeper, light-background shade). */
fun bubbleColorRes(color: String): Int = when (color) {
    TimerPrefs.BUBBLE_COLOR_ORANGE -> R.color.bubbleColorOrangeLight
    TimerPrefs.BUBBLE_COLOR_GREEN -> R.color.bubbleColorGreenLight
    TimerPrefs.BUBBLE_COLOR_PURPLE -> R.color.bubbleColorPurpleLight
    TimerPrefs.BUBBLE_COLOR_PINK -> R.color.bubbleColorPinkLight
    TimerPrefs.BUBBLE_COLOR_RED -> R.color.bubbleColorRedLight
    else -> R.color.bubbleColorBlueLight
}

/** The swatch colour shown for this choice in the colour picker. */
fun bubbleColorSwatchRes(color: String): Int = bubbleColorRes(color)

/** The theme overlay that makes the app's own UI (buttons, headers, sliders — not just
 *  the floating bubble) match the selected bubble colour. Each activity applies this
 *  via theme.applyStyle() before setContentView(). */
fun bubbleColorThemeOverlayRes(color: String): Int = when (color) {
    TimerPrefs.BUBBLE_COLOR_ORANGE -> R.style.ThemeOverlay_OverlayTimer_Orange
    TimerPrefs.BUBBLE_COLOR_GREEN -> R.style.ThemeOverlay_OverlayTimer_Green
    TimerPrefs.BUBBLE_COLOR_PURPLE -> R.style.ThemeOverlay_OverlayTimer_Purple
    TimerPrefs.BUBBLE_COLOR_PINK -> R.style.ThemeOverlay_OverlayTimer_Pink
    TimerPrefs.BUBBLE_COLOR_RED -> R.style.ThemeOverlay_OverlayTimer_Red
    else -> R.style.ThemeOverlay_OverlayTimer_Blue
}

/** Persists a running total of watched-app foreground time that resets at midnight. */
object TimerPrefs {
    private const val PREFS_NAME = "overlay_timer_prefs"
    private const val KEY_WATCHED_PACKAGES = "watched_packages"
    private const val KEY_USAGE_DATE = "usage_date"
    private const val KEY_USAGE_MILLIS = "usage_millis"
    private const val KEY_SESSIONS_LIST = "sessions_list"
    private const val KEY_REMINDER_INTERVAL_MILLIS = "reminder_interval_millis"
    private const val KEY_DIM_DELAY_MILLIS = "dim_delay_millis"
    private const val KEY_MESSAGE_DISPLAY_MILLIS = "message_display_millis"
    private const val KEY_GLOW_INTERVAL_MILLIS = "glow_interval_millis"
    private const val KEY_NEWS_ENABLED = "news_enabled"
    private const val KEY_NEWS_TOPICS = "news_topics"
    private const val KEY_SEEN_NEWS_DATE = "seen_news_date"
    private const val KEY_SEEN_NEWS_LINKS = "seen_news_links"
    private const val KEY_REMINDER_DISPLAY_MODE = "reminder_display_mode"
    private const val KEY_OVERLAY_MODE = "overlay_mode"
    private const val KEY_SELECTED_MESSAGE_ID = "selected_message_id"
    private const val KEY_CUSTOM_MESSAGE_IDS = "custom_message_ids"
    private const val CUSTOM_TEXT_PREFIX = "custom_text_"
    private const val KEY_PARENTAL_LOCK_ENABLED = "parental_lock_enabled"
    private const val KEY_PARENTAL_PIN_HASH = "parental_pin_hash"
    private const val KEY_PRO_UNLOCKED = "pro_unlocked"
    private const val KEY_MASCOT = "reminder_mascot"
    private const val KEY_BUBBLE_COLOR = "bubble_color"
    private const val KEY_LAST_SYNC_MILLIS = "last_sync_millis"
    private const val KEY_OVERLAY_SECTION_EXPANDED = "overlay_section_expanded"
    private const val KEY_TUNING_SECTION_EXPANDED = "tuning_section_expanded"
    private const val KEY_NEWS_SECTION_EXPANDED = "news_section_expanded"
    private const val KEY_NEWS_TOPICS_SECTION_EXPANDED = "news_topics_section_expanded"
    private const val KEY_NEWS_HEADLINES_SECTION_EXPANDED = "news_headlines_section_expanded"
    private const val KEY_STOCKS_SECTION_EXPANDED = "stocks_section_expanded"
    private const val KEY_SYNC_SECTION_EXPANDED = "sync_section_expanded"
    private const val KEY_WATCHED_STOCKS = "watched_stocks"

    /** Valid bubble colour ids; keep in sync with bubbleColorRes(). */
    const val BUBBLE_COLOR_BLUE = "blue"
    const val BUBBLE_COLOR_ORANGE = "orange"
    const val BUBBLE_COLOR_GREEN = "green"
    const val BUBBLE_COLOR_PURPLE = "purple"
    const val BUBBLE_COLOR_PINK = "pink"
    const val BUBBLE_COLOR_RED = "red"
    const val DEFAULT_BUBBLE_COLOR = BUBBLE_COLOR_ORANGE

    /** Valid mascot ids for the reminder popup icon; keep in sync with the drawables. */
    const val MASCOT_RABBIT = "rabbit"
    const val MASCOT_HAMSTER = "hamster"
    const val MASCOT_DOG = "dog"
    const val MASCOT_CAT = "cat"

    /** Free tier can watch this many apps in Auto mode; Pro removes the limit. */
    const val FREE_WATCHED_APP_LIMIT = 2

    /** The id of the first preset in R.array.system_reminder_messages; the default reminder message. */
    const val DEFAULT_MESSAGE_ID = "system_0"
    const val MAX_CUSTOM_MESSAGES = 3

    /** The three overlay behaviors: no bubble, always shown, or auto-shown for chosen apps. */
    const val MODE_OFF = "OFF"
    const val MODE_ALWAYS = "ALWAYS"
    const val MODE_AUTO = "AUTO"
    private const val PACKAGE_MILLIS_PREFIX = "usage_millis_pkg_"
    private const val PACKAGE_DATE_PREFIX = "usage_date_pkg_"
    private const val PACKAGE_DAILY_PREFIX = "usage_daily_pkg_"
    private const val DAILY_TOTAL_PREFIX = "daily_total_"
    private const val PACKAGE_LIMIT_MINUTES_PREFIX = "limit_minutes_pkg_"
    private const val LIMIT_WARN_DATE_PREFIX = "limit_warn_date_pkg_"
    private const val LIMIT_OVERRIDE_DATE_PREFIX = "limit_override_date_pkg_"

    /** Fixed (not user-configurable) delay before hiding the bubble after leaving a watched app. */
    const val HIDE_DELAY_MILLIS = 500L

    const val DEFAULT_REMINDER_INTERVAL_MILLIS = 10 * 60 * 1000L
    const val MIN_REMINDER_INTERVAL_MILLIS = 1 * 60 * 1000L

    const val DEFAULT_GLOW_INTERVAL_MILLIS = 5 * 60 * 1000L
    const val MIN_GLOW_INTERVAL_MILLIS = 1 * 60 * 1000L

    const val DEFAULT_DIM_DELAY_MILLIS = 3000L
    const val MIN_DIM_DELAY_MILLIS = 1000L

    const val DEFAULT_MESSAGE_DISPLAY_MILLIS = 2000L
    const val MIN_MESSAGE_DISPLAY_MILLIS = 1000L
    const val MAX_MESSAGE_DISPLAY_MILLIS = 8000L

    /** Whether reminder/limit messages on the overlay auto-hide after the configured
     *  duration, or stay up until tapped away. */
    const val REMINDER_DISPLAY_TIMED = "timed"
    const val REMINDER_DISPLAY_PERMANENT = "permanent"
    const val DEFAULT_REMINDER_DISPLAY_MODE = REMINDER_DISPLAY_TIMED

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    private fun todayKey(): String = dateFormat.format(System.currentTimeMillis())

    fun getTodayMillis(context: Context): Long {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val storedDate = prefs.getString(KEY_USAGE_DATE, null)
        return if (storedDate == todayKey()) prefs.getLong(KEY_USAGE_MILLIS, 0L) else 0L
    }

    fun addTodayMillis(context: Context, delta: Long) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val newTotal = getTodayMillis(context) + delta
        val dateKey = todayKey()
        prefs.edit()
            .putString(KEY_USAGE_DATE, dateKey)
            .putLong(KEY_USAGE_MILLIS, newTotal)
            // Permanent per-day record, keyed by date rather than "today" - this is
            // what survives the midnight rollover so weekly/monthly charts have
            // something to read. Always mirrors the live total for today's own key.
            .putLong(DAILY_TOTAL_PREFIX + dateKey, newTotal)
            .apply()
    }

    fun getDailyTotal(context: Context, dateKey: String): Long {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getLong(DAILY_TOTAL_PREFIX + dateKey, 0L)
    }

    /** Every stored daily total (dateKey -> millis), for exporting to sync - these are
     *  lightweight (one Long per day) so there's no need to window this like sessions. */
    fun getAllDailyTotals(context: Context): Map<String, Long> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.all
            .filterKeys { it.startsWith(DAILY_TOTAL_PREFIX) }
            .mapKeys { it.key.removePrefix(DAILY_TOTAL_PREFIX) }
            .mapValues { (_, value) -> (value as? Long) ?: 0L }
    }

    /** Every stored per-app-per-day total, keyed as "dateKey_packageName" -> millis. */
    fun getAllPerAppDailyTotals(context: Context): Map<String, Long> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.all
            .filterKeys { it.startsWith(PACKAGE_DAILY_PREFIX) }
            .mapKeys { it.key.removePrefix(PACKAGE_DAILY_PREFIX) }
            .mapValues { (_, value) -> (value as? Long) ?: 0L }
    }

    /** The raw pipe/newline-delimited session log, already pruned to the retention
     *  window - opaque to callers, just round-tripped through sync as-is. */
    fun getRawSessionsList(context: Context): String {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_SESSIONS_LIST, "") ?: ""
    }

    /** Merges synced usage history into local storage - the max of local vs. remote
     *  for any day/app that exists in both, and a de-duplicated union of session
     *  lines - rather than overwriting, so restoring history on a device that's
     *  already tracked something today doesn't erase it. */
    fun mergeHistory(
        context: Context,
        dailyTotals: Map<String, Long>,
        perAppDailyTotals: Map<String, Long>,
        rawSessions: String
    ) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val editor = prefs.edit()

        dailyTotals.forEach { (dateKey, millis) ->
            val existing = prefs.getLong(DAILY_TOTAL_PREFIX + dateKey, 0L)
            editor.putLong(DAILY_TOTAL_PREFIX + dateKey, maxOf(existing, millis))
        }
        perAppDailyTotals.forEach { (key, millis) ->
            val existing = prefs.getLong(PACKAGE_DAILY_PREFIX + key, 0L)
            editor.putLong(PACKAGE_DAILY_PREFIX + key, maxOf(existing, millis))
        }

        val localSessions = getRawSessionsList(context).split("\n").filter { it.isNotBlank() }
        val remoteSessions = rawSessions.split("\n").filter { it.isNotBlank() }
        val merged = (localSessions + remoteSessions).distinct().joinToString("\n")
        editor.putString(KEY_SESSIONS_LIST, merged)

        editor.apply()
    }

    /** Day-by-day totals for the last [days] days ending today, oldest first. */
    fun getRecentDailyTotals(context: Context, days: Int): List<Pair<String, Long>> {
        val calendar = java.util.Calendar.getInstance()
        return (days - 1 downTo 0).map { offset ->
            val cal = calendar.clone() as java.util.Calendar
            cal.add(java.util.Calendar.DAY_OF_YEAR, -offset)
            val key = dateFormat.format(cal.time)
            key to getDailyTotal(context, key)
        }
    }

    /** Day-by-day totals for the current calendar month, day 1 through today. */
    fun getMonthToDateDailyTotals(context: Context): List<Pair<String, Long>> {
        val calendar = java.util.Calendar.getInstance()
        val today = calendar.get(java.util.Calendar.DAY_OF_MONTH)
        val cal = calendar.clone() as java.util.Calendar
        return (1..today).map { day ->
            cal.set(java.util.Calendar.DAY_OF_MONTH, day)
            val key = dateFormat.format(cal.time)
            key to getDailyTotal(context, key)
        }
    }

    /** Today's accumulated foreground time for one specific watched app. Uses its own
     *  per-package date marker rather than the shared KEY_USAGE_DATE - if every
     *  package shared one marker, whichever category (aggregate, a different app's
     *  total, the sessions list) happened to persist first after midnight would
     *  silently mark the day as "already rolled over" for everyone else too, even
     *  though their own values were never actually reset. */
    fun getTodayMillisForPackage(context: Context, packageName: String): Long {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val storedDate = prefs.getString(PACKAGE_DATE_PREFIX + packageName, null)
        if (storedDate != todayKey()) return 0L
        return prefs.getLong(PACKAGE_MILLIS_PREFIX + packageName, 0L)
    }

    fun addTodayMillisForPackage(context: Context, packageName: String, delta: Long) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val newTotal = getTodayMillisForPackage(context, packageName) + delta
        val dateKey = todayKey()
        prefs.edit()
            .putString(PACKAGE_DATE_PREFIX + packageName, dateKey)
            .putLong(PACKAGE_MILLIS_PREFIX + packageName, newTotal)
            // Permanent per-day-per-app record (mirrors the aggregate's daily_total_
            // pattern), so the Daily Report can look up any past day's breakdown, not
            // just whatever's cached as "today".
            .putLong(PACKAGE_DAILY_PREFIX + dateKey + "_" + packageName, newTotal)
            .apply()
    }

    /** Per-app breakdown for a specific past (or present) day. */
    fun getPerAppMillisForDate(context: Context, dateKey: String): Map<String, Long> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val prefix = PACKAGE_DAILY_PREFIX + dateKey + "_"
        return prefs.all
            .filterKeys { it.startsWith(prefix) }
            .mapKeys { it.key.removePrefix(prefix) }
            .mapValues { (_, value) -> (value as? Long) ?: 0L }
    }

    /** A watched app's daily time limit in minutes, or 0 if it has none set. */
    fun getAppLimitMinutes(context: Context, packageName: String): Int {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(PACKAGE_LIMIT_MINUTES_PREFIX + packageName, 0)
    }

    fun setAppLimitMinutes(context: Context, packageName: String, minutes: Int) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(PACKAGE_LIMIT_MINUTES_PREFIX + packageName, minutes)
            .apply()
    }

    /** Whether the "approaching your limit" nudge has already fired today for this
     *  app - stored as the date it last fired, so it naturally resets each day
     *  without needing a separate rollover step. */
    fun hasWarnedLimitToday(context: Context, packageName: String): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(LIMIT_WARN_DATE_PREFIX + packageName, null) == todayKey()
    }

    fun markLimitWarnedToday(context: Context, packageName: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(LIMIT_WARN_DATE_PREFIX + packageName, todayKey())
            .apply()
    }


    /** Whether the user has PIN-unlocked their way past today's block screen for this
     *  app - stored as the date it was unlocked, so like the other limit flags it
     *  resets on its own at midnight without a separate rollover step. */
    fun getLimitOverrideToday(context: Context, packageName: String): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(LIMIT_OVERRIDE_DATE_PREFIX + packageName, null) == todayKey()
    }

    fun setLimitOverrideToday(context: Context, packageName: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(LIMIT_OVERRIDE_DATE_PREFIX + packageName, todayKey())
            .apply()
    }

    /** Today's per-app breakdown, keyed by package name. */
    fun getTodayPerAppMillis(context: Context): Map<String, Long> = getPerAppMillisForDate(context, todayKey())


    fun getWatchedPackages(context: Context): Set<String> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getStringSet(KEY_WATCHED_PACKAGES, emptySet()) ?: emptySet()
    }

    fun setWatchedPackages(context: Context, packages: Set<String>) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putStringSet(KEY_WATCHED_PACKAGES, packages)
            .apply()
    }

    /** Which BBC News topics feed the news pulse and the News screen. Defaults to just
     *  "Top Stories" so the feature works sensibly before anyone's touched the picker. */
    fun getNewsTopics(context: Context): Set<String> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getStringSet(KEY_NEWS_TOPICS, setOf(NewsTopics.TOP)) ?: setOf(NewsTopics.TOP)
    }

    fun setNewsTopics(context: Context, topicIds: Set<String>) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putStringSet(KEY_NEWS_TOPICS, topicIds)
            .apply()
    }

    /** Article links already shown on the bubble today, so the same headline doesn't
     *  repeat - persisted (not just kept in OverlayService's memory) since leaving and
     *  returning to a watched app recreates that service, which would otherwise forget
     *  what it had already shown and start back at the top of the (likely unchanged)
     *  feed. Date-stamped so it naturally resets each day, same pattern as the other
     *  "today" flags. */
    fun getSeenNewsLinksToday(context: Context): Set<String> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val storedDate = prefs.getString(KEY_SEEN_NEWS_DATE, null)
        if (storedDate != todayKey()) return emptySet()
        return prefs.getStringSet(KEY_SEEN_NEWS_LINKS, emptySet()) ?: emptySet()
    }

    fun markNewsLinkSeenToday(context: Context, link: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val current = getSeenNewsLinksToday(context)
        prefs.edit()
            .putString(KEY_SEEN_NEWS_DATE, todayKey())
            .putStringSet(KEY_SEEN_NEWS_LINKS, current + link)
            .apply()
    }

    fun getReminderIntervalMillis(context: Context): Long {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getLong(KEY_REMINDER_INTERVAL_MILLIS, DEFAULT_REMINDER_INTERVAL_MILLIS)
    }

    fun setReminderIntervalMillis(context: Context, intervalMillis: Long) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_REMINDER_INTERVAL_MILLIS, intervalMillis)
            .apply()
    }

    fun getGlowIntervalMillis(context: Context): Long {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getLong(KEY_GLOW_INTERVAL_MILLIS, DEFAULT_GLOW_INTERVAL_MILLIS)
    }

    fun setGlowIntervalMillis(context: Context, intervalMillis: Long) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_GLOW_INTERVAL_MILLIS, intervalMillis)
            .apply()
    }

    fun isNewsEnabled(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_NEWS_ENABLED, true)
    }

    fun setNewsEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_NEWS_ENABLED, enabled)
            .apply()
    }

    fun getDimDelayMillis(context: Context): Long {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getLong(KEY_DIM_DELAY_MILLIS, DEFAULT_DIM_DELAY_MILLIS)
    }

    fun setDimDelayMillis(context: Context, delayMillis: Long) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_DIM_DELAY_MILLIS, delayMillis)
            .apply()
    }

    fun getOverlaySectionExpanded(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_OVERLAY_SECTION_EXPANDED, true)
    }

    fun setOverlaySectionExpanded(context: Context, expanded: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_OVERLAY_SECTION_EXPANDED, expanded)
            .apply()
    }

    fun getTuningSectionExpanded(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_TUNING_SECTION_EXPANDED, true)
    }

    fun setTuningSectionExpanded(context: Context, expanded: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_TUNING_SECTION_EXPANDED, expanded)
            .apply()
    }

    fun getNewsSectionExpanded(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_NEWS_SECTION_EXPANDED, true)
    }

    fun setNewsSectionExpanded(context: Context, expanded: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_NEWS_SECTION_EXPANDED, expanded)
            .apply()
    }

    fun getNewsTopicsSectionExpanded(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_NEWS_TOPICS_SECTION_EXPANDED, true)
    }

    fun setNewsTopicsSectionExpanded(context: Context, expanded: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_NEWS_TOPICS_SECTION_EXPANDED, expanded)
            .apply()
    }

    fun getNewsHeadlinesSectionExpanded(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_NEWS_HEADLINES_SECTION_EXPANDED, true)
    }

    fun setNewsHeadlinesSectionExpanded(context: Context, expanded: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_NEWS_HEADLINES_SECTION_EXPANDED, expanded)
            .apply()
    }

    fun getStocksSectionExpanded(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_STOCKS_SECTION_EXPANDED, true)
    }

    fun setStocksSectionExpanded(context: Context, expanded: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_STOCKS_SECTION_EXPANDED, expanded)
            .apply()
    }

    /** Ticker symbols the user is tracking, in the order they were added - stored
     *  comma-joined rather than as a String set, since (unlike watched apps) display
     *  order here is meaningful and a Set wouldn't preserve it. */
    fun getWatchedStocks(context: Context): List<String> {
        val raw = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_WATCHED_STOCKS, "") ?: ""
        return raw.split(",").map { it.trim().uppercase() }.filter { it.isNotEmpty() }
    }

    fun setWatchedStocks(context: Context, symbols: List<String>) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_WATCHED_STOCKS, symbols.joinToString(","))
            .apply()
    }

    fun getSyncSectionExpanded(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_SYNC_SECTION_EXPANDED, true)
    }

    fun setSyncSectionExpanded(context: Context, expanded: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_SYNC_SECTION_EXPANDED, expanded)
            .apply()
    }

    fun getBubbleColor(context: Context): String {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_BUBBLE_COLOR, DEFAULT_BUBBLE_COLOR) ?: DEFAULT_BUBBLE_COLOR
    }

    fun setBubbleColor(context: Context, color: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_BUBBLE_COLOR, color)
            .apply()
    }

    fun getLastSyncMillis(context: Context): Long {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getLong(KEY_LAST_SYNC_MILLIS, 0L)
    }

    fun setLastSyncMillis(context: Context, millis: Long) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_LAST_SYNC_MILLIS, millis)
            .apply()
    }

    fun getMessageDisplayMillis(context: Context): Long {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getLong(KEY_MESSAGE_DISPLAY_MILLIS, DEFAULT_MESSAGE_DISPLAY_MILLIS)
    }

    fun setMessageDisplayMillis(context: Context, delayMillis: Long) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_MESSAGE_DISPLAY_MILLIS, delayMillis)
            .apply()
    }

    fun getReminderDisplayMode(context: Context): String {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_REMINDER_DISPLAY_MODE, DEFAULT_REMINDER_DISPLAY_MODE) ?: DEFAULT_REMINDER_DISPLAY_MODE
    }

    fun setReminderDisplayMode(context: Context, mode: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_REMINDER_DISPLAY_MODE, mode)
            .apply()
    }

    fun getOverlayMode(context: Context): String {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_OVERLAY_MODE, MODE_OFF) ?: MODE_OFF
    }

    fun setOverlayMode(context: Context, mode: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_OVERLAY_MODE, mode)
            .apply()
    }

    fun getSelectedMessageId(context: Context): String {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_SELECTED_MESSAGE_ID, DEFAULT_MESSAGE_ID) ?: DEFAULT_MESSAGE_ID
    }

    fun setSelectedMessageId(context: Context, id: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_SELECTED_MESSAGE_ID, id)
            .apply()
    }

    /** Ordered ids of the user's custom messages (at most MAX_CUSTOM_MESSAGES). */
    fun getCustomMessageIds(context: Context): List<String> {
        val raw = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_CUSTOM_MESSAGE_IDS, "") ?: ""
        return if (raw.isBlank()) emptyList() else raw.split(",")
    }

    private fun setCustomMessageIds(context: Context, ids: List<String>) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_CUSTOM_MESSAGE_IDS, ids.joinToString(","))
            .apply()
    }

    /** Adds a new blank custom message slot and returns its id, or null if the
     *  MAX_CUSTOM_MESSAGES limit has already been reached. */
    fun addCustomMessage(context: Context): String? {
        val ids = getCustomMessageIds(context)
        if (ids.size >= MAX_CUSTOM_MESSAGES) return null
        val newId = "c" + System.currentTimeMillis()
        setCustomMessageIds(context, ids + newId)
        setCustomMessageText(context, newId, "")
        return newId
    }

    fun deleteCustomMessage(context: Context, id: String) {
        setCustomMessageIds(context, getCustomMessageIds(context) - id)
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(CUSTOM_TEXT_PREFIX + id)
            .apply()
        if (getSelectedMessageId(context) == id) {
            setSelectedMessageId(context, DEFAULT_MESSAGE_ID)
        }
    }

    fun getCustomMessageText(context: Context, id: String): String {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(CUSTOM_TEXT_PREFIX + id, "") ?: ""
    }

    fun setCustomMessageText(context: Context, id: String, text: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(CUSTOM_TEXT_PREFIX + id, text)
            .apply()
    }

    /** Resolves the currently selected reminder message to its raw template text
     *  (containing {app}/{minutes} placeholders), falling back to the first preset
     *  if a selected custom message turns out to be empty or was deleted. */
    fun getActiveMessageTemplate(context: Context): String {
        val presets = context.resources.getStringArray(R.array.system_reminder_messages)
        val fallback = presets.firstOrNull().orEmpty()
        val id = getSelectedMessageId(context)
        return when {
            id.startsWith("system_") -> {
                val index = id.removePrefix("system_").toIntOrNull() ?: 0
                presets.getOrElse(index) { fallback }
            }
            id in getCustomMessageIds(context) -> getCustomMessageText(context, id).ifBlank { fallback }
            else -> fallback
        }
    }

    /** Below a session this short, don't bother recording it — it's noise from the
     *  overlay briefly reappearing rather than a real viewing session. */
    private const val MIN_SESSION_MILLIS = 1000L

    /** How long session history is kept around for the Daily Report's month view -
     *  long enough to browse a full calendar month back, without keeping the list
     *  growing forever. */
    private const val SESSION_RETENTION_DAYS = 35L

    /** Records one finished viewing session (null packageName means Always-mode, not
     *  attributed to a specific app), so the daily report can list individual sessions
     *  with their real start time for any recent day, not just today. Sessions older
     *  than the retention window are pruned on write rather than kept forever. */
    fun addSessionRecord(context: Context, packageName: String?, startWallClockMillis: Long, durationMillis: Long) {
        if (durationMillis < MIN_SESSION_MILLIS) return
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val cutoff = System.currentTimeMillis() - SESSION_RETENTION_DAYS * 24 * 60 * 60 * 1000L
        val existingLines = (prefs.getString(KEY_SESSIONS_LIST, "") ?: "")
            .split("\n")
            .filter { it.isNotBlank() }
            .filter { line -> (line.split("|").getOrNull(1)?.toLongOrNull() ?: 0L) >= cutoff }
        val entry = "${packageName.orEmpty()}|$startWallClockMillis|$durationMillis"
        val updated = (existingLines + entry).joinToString("\n")
        prefs.edit().putString(KEY_SESSIONS_LIST, updated).apply()
    }

    private fun parseSessionRecords(context: Context): List<SessionRecord> {
        val raw = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_SESSIONS_LIST, "") ?: ""
        if (raw.isBlank()) return emptyList()
        return raw.split("\n").mapNotNull { line ->
            val parts = line.split("|")
            if (parts.size != 3) return@mapNotNull null
            val start = parts[1].toLongOrNull() ?: return@mapNotNull null
            val duration = parts[2].toLongOrNull() ?: return@mapNotNull null
            SessionRecord(parts[0].ifBlank { null }, start, duration)
        }
    }

    /** Recorded sessions for one specific day (within the retention window), most
     *  recent first. */
    fun getSessionsForDate(context: Context, dateKey: String): List<SessionRecord> {
        return parseSessionRecords(context)
            .filter { dateFormat.format(it.startWallClockMillis) == dateKey }
            .sortedByDescending { it.startWallClockMillis }
    }

    fun getTodaySessions(context: Context): List<SessionRecord> = getSessionsForDate(context, todayKey())

    fun getMascot(context: Context): String {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_MASCOT, MASCOT_RABBIT) ?: MASCOT_RABBIT
    }

    fun setMascot(context: Context, mascot: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_MASCOT, mascot)
            .apply()
    }

    fun isProUnlocked(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_PRO_UNLOCKED, false)
    }

    fun setProUnlocked(context: Context, unlocked: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_PRO_UNLOCKED, unlocked)
            .apply()
    }

    fun getParentalLockEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_PARENTAL_LOCK_ENABLED, false)
    }

    fun setParentalLockEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_PARENTAL_LOCK_ENABLED, enabled)
            .apply()
    }

    fun hasParentalPin(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .contains(KEY_PARENTAL_PIN_HASH)
    }

    fun setParentalPin(context: Context, pin: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_PARENTAL_PIN_HASH, hashPin(pin))
            .apply()
    }

    fun verifyParentalPin(context: Context, pin: String): Boolean {
        val stored = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_PARENTAL_PIN_HASH, null) ?: return false
        return stored == hashPin(pin)
    }

    fun clearParentalPin(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_PARENTAL_PIN_HASH)
            .apply()
    }

    private fun hashPin(pin: String): String = sha256Hex(pin)
}

/** One continuous viewing session: which app (null for Always-mode, not app-specific),
 *  when it started (wall-clock time), and how long it lasted. */
data class SessionRecord(
    val packageName: String?,
    val startWallClockMillis: Long,
    val durationMillis: Long
)

private fun sha256Hex(input: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
    return digest.joinToString("") { "%02x".format(it) }
}

/** Local, offline promo-code check for unlocking Pro before real Play Billing is wired
 *  up. Not cryptographically secure (no server to back it), just enough to hand out
 *  codes without giving away the plaintext in a decompiled APK. To add a code, hash
 *  the normalized (trimmed, uppercased) string with SHA-256 and add it below. */
object PromoCodes {
    private val VALID_HASHES = setOf(
        // SCROLLSENSEVIP
        "4c021ceed96df3882fef9cc8f7c39d99ffcb76aadd001e76fa937a75af1f22ce"
    )

    fun isValid(code: String): Boolean {
        val normalized = code.trim().uppercase()
        if (normalized.isEmpty()) return false
        return sha256Hex(normalized) in VALID_HASHES
    }
}
