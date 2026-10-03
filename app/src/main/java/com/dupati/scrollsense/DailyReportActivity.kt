package com.dupati.scrollsense

import android.content.pm.PackageManager
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Locale

private data class ReportEntry(
    val packageName: String?,
    val label: String,
    val icon: Drawable,
    val millis: Long
)

private data class SessionDetail(val startWallClockMillis: Long, val durationMillis: Long)

class DailyReportActivity : AppCompatActivity() {

    private val timeFormat = SimpleDateFormat("h:mm a", Locale.getDefault())
    private val dateKeyFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private val displayDateFormat = SimpleDateFormat("MMM d, yyyy", Locale.getDefault())

    private lateinit var selectedDateKey: String
    private var currentTrendRange = TREND_WEEK

    override fun onCreate(savedInstanceState: Bundle?) {
        theme.applyStyle(bubbleColorThemeOverlayRes(TimerPrefs.getBubbleColor(this)), true)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_daily_report)

        selectedDateKey = dateKeyFormat.format(System.currentTimeMillis())

        findViewById<View>(R.id.datePrevArrow).setOnClickListener { shiftSelectedDate(-1) }
        findViewById<View>(R.id.dateNextArrow).setOnClickListener { shiftSelectedDate(1) }
        findViewById<View>(R.id.jumpToTodayText).setOnClickListener {
            selectDate(dateKeyFormat.format(System.currentTimeMillis()))
        }

        val weekOption = findViewById<TextView>(R.id.trendWeekOption)
        val monthOption = findViewById<TextView>(R.id.trendMonthOption)
        weekOption.setOnClickListener { selectTrendRange(TREND_WEEK) }
        monthOption.setOnClickListener { selectTrendRange(TREND_MONTH) }
        selectTrendRange(TREND_WEEK)

        setupCollapsibleSection(
            header = findViewById(R.id.reportBreakdownSectionHeader),
            content = findViewById(R.id.reportBreakdownSectionContent),
            chevron = findViewById(R.id.reportBreakdownSectionChevron),
            getExpanded = { TimerPrefs.getReportBreakdownSectionExpanded(this) },
            setExpanded = { TimerPrefs.setReportBreakdownSectionExpanded(this, it) }
        )
        setupCollapsibleSection(
            header = findViewById(R.id.reportTrendSectionHeader),
            content = findViewById(R.id.reportTrendSectionContent),
            chevron = findViewById(R.id.reportTrendSectionChevron),
            getExpanded = { TimerPrefs.getReportTrendSectionExpanded(this) },
            setExpanded = { TimerPrefs.setReportTrendSectionExpanded(this, it) }
        )
        setupCollapsibleSection(
            header = findViewById(R.id.reportListSubHeader),
            content = findViewById(R.id.reportListSubContent),
            chevron = findViewById(R.id.reportListSubChevron),
            getExpanded = { TimerPrefs.getReportListSectionExpanded(this) },
            setExpanded = { TimerPrefs.setReportListSectionExpanded(this, it) }
        )
        setupCollapsibleSection(
            header = findViewById(R.id.reportChartSubHeader),
            content = findViewById(R.id.reportChartSubContent),
            chevron = findViewById(R.id.reportChartSubChevron),
            getExpanded = { TimerPrefs.getReportChartSectionExpanded(this) },
            setExpanded = { TimerPrefs.setReportChartSectionExpanded(this, it) }
        )

        refreshForSelectedDate()
    }

    private fun shiftSelectedDate(dayDelta: Int) {
        val cal = java.util.Calendar.getInstance()
        cal.time = dateKeyFormat.parse(selectedDateKey) ?: cal.time
        cal.add(java.util.Calendar.DAY_OF_YEAR, dayDelta)
        // Don't let "next" go past today - there's nothing recorded beyond it - and
        // don't wander outside the window sessions/per-app history is actually kept for.
        val today = java.util.Calendar.getInstance()
        if (cal.after(today)) return
        val oldest = java.util.Calendar.getInstance().apply { add(java.util.Calendar.DAY_OF_YEAR, -SESSION_RETENTION_DAYS) }
        if (cal.before(oldest)) return
        selectDate(dateKeyFormat.format(cal.time))
    }

    private fun selectDate(dateKey: String) {
        selectedDateKey = dateKey
        refreshForSelectedDate()
        // Rebuilds whichever trend view is showing so its "selected day" highlight
        // (the outlined calendar cell / bolded week bar) follows the new selection.
        selectTrendRange(currentTrendRange)
    }

    private fun refreshForSelectedDate() {
        val todayKey = dateKeyFormat.format(System.currentTimeMillis())
        val isToday = selectedDateKey == todayKey
        val isYesterday = selectedDateKey == dateKeyFormat.format(
            java.util.Calendar.getInstance().apply { add(java.util.Calendar.DAY_OF_YEAR, -1) }.time
        )
        findViewById<TextView>(R.id.selectedDateText).text = when {
            isToday -> getString(R.string.date_label_today)
            isYesterday -> getString(R.string.date_label_yesterday)
            else -> displayDateFormat.format(dateKeyFormat.parse(selectedDateKey)!!)
        }
        findViewById<View>(R.id.jumpToTodayText).visibility = if (isToday) View.GONE else View.VISIBLE

        val entries = loadReportEntries(selectedDateKey)
        val totalMillis = entries.sumOf { it.millis }
        findViewById<TextView>(R.id.reportTotalText).text =
            getString(R.string.daily_report_total_format, formatFriendlyDuration(totalMillis))

        updateReportChart(entries, totalMillis)

        val listContainer = findViewById<LinearLayout>(R.id.reportListContainer)
        val emptyText = findViewById<TextView>(R.id.reportEmptyText)
        val hintText = findViewById<TextView>(R.id.reportHintText)

        listContainer.removeAllViews()
        if (entries.isEmpty()) {
            emptyText.visibility = View.VISIBLE
            hintText.visibility = View.GONE
        } else {
            emptyText.visibility = View.GONE
            hintText.visibility = View.VISIBLE
            entries.forEach { entry -> listContainer.addView(buildReportBlock(entry)) }
        }
    }

    /** Per-app ring chart + legend for whichever day is currently selected - rebuilt
     *  from that day's [entries] each time selectDate()/shiftSelectedDate() runs, so it
     *  always reflects the day being viewed rather than always showing "today". */
    private fun updateReportChart(entries: List<ReportEntry>, totalMillis: Long) {
        val chart = findViewById<DonutChartView>(R.id.reportDonutChart)
        val legend = findViewById<LinearLayout>(R.id.reportLegendContainer)

        if (entries.isEmpty()) {
            chart.visibility = View.GONE
            legend.visibility = View.GONE
            return
        }
        chart.visibility = View.VISIBLE
        legend.visibility = View.VISIBLE

        val segments = entries.mapIndexed { index, entry ->
            val fraction = if (totalMillis > 0) entry.millis / totalMillis.toFloat() else 0f
            DonutChartView.Segment(fraction, CHART_COLORS[index % CHART_COLORS.size])
        }
        chart.setSegments(segments)

        legend.removeAllViews()
        entries.forEachIndexed { index, entry ->
            val percent = if (totalMillis > 0) entry.millis * 100f / totalMillis else 0f
            legend.addView(buildLegendRow(entry.label, CHART_COLORS[index % CHART_COLORS.size], percent))
        }
    }

    private fun buildLegendRow(label: String, color: Int, percent: Float): View {
        val row = LinearLayout(this).apply {
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, dp(4))
        }
        val dot = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(12), dp(12))
            background = ContextCompat.getDrawable(this@DailyReportActivity, R.drawable.color_swatch_fill)
            backgroundTintList = android.content.res.ColorStateList.valueOf(color)
        }
        val labelText = TextView(this).apply {
            text = label
            setTextColor(ContextCompat.getColor(context, R.color.labelText))
            textSize = 13f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(10)
            }
        }
        val percentText = TextView(this).apply {
            text = String.format(Locale.getDefault(), "%.1f%%", percent)
            setTextColor(themeAccentColor())
            textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
        }
        row.addView(dot)
        row.addView(labelText)
        row.addView(percentText)
        return row
    }

    private val weekdayLetterFormat = SimpleDateFormat("EEEEE", Locale.getDefault())

    private fun selectTrendRange(range: String) {
        currentTrendRange = range
        findViewById<TextView>(R.id.trendWeekOption).isSelected = range == TREND_WEEK
        findViewById<TextView>(R.id.trendMonthOption).isSelected = range == TREND_MONTH

        val days = if (range == TREND_WEEK) {
            TimerPrefs.getRecentDailyTotals(this, 7)
        } else {
            TimerPrefs.getMonthToDateDailyTotals(this)
        }
        findViewById<TextView>(R.id.trendTotalText).text =
            getString(R.string.trend_total_format, formatFriendlyDuration(days.sumOf { it.second }))

        val container = findViewById<LinearLayout>(R.id.trendChartContainer)
        container.removeAllViews()
        if (range == TREND_WEEK) {
            val barRow = LinearLayout(this).apply {
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.BOTTOM
            }
            val maxMillis = days.maxOf { it.second }.coerceAtLeast(1L)
            days.forEach { (dateKey, millis) ->
                val label = weekdayLetterFormat.format(dateKeyFormat.parse(dateKey)!!)
                barRow.addView(buildBar(millis, maxMillis, label, barHeightDp = 90, dateKey = dateKey))
            }
            container.addView(barRow)
        } else {
            buildMonthCalendarRows().forEach { container.addView(it) }
        }
    }

    /** A traditional month-grid calendar: one row of weekday initials, then one row
     *  per week, each day cell tinted by how much of that day's usage it represents
     *  relative to the month's busiest day so far (a GitHub-style heatmap). Days after
     *  today are left blank since there's nothing to show yet. */
    private fun buildMonthCalendarRows(): List<View> {
        val rows = mutableListOf<View>()

        val headerRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf("S", "M", "T", "W", "T", "F", "S").forEach { headerRow.addView(buildCalendarHeaderCell(it)) }
        rows.add(headerRow)

        val calendar = java.util.Calendar.getInstance()
        val lastDay = calendar.getActualMaximum(java.util.Calendar.DAY_OF_MONTH)
        val today = calendar.get(java.util.Calendar.DAY_OF_MONTH)
        val firstOfMonth = calendar.clone() as java.util.Calendar
        firstOfMonth.set(java.util.Calendar.DAY_OF_MONTH, 1)
        val leadingBlanks = firstOfMonth.get(java.util.Calendar.DAY_OF_WEEK) - 1

        val cal = calendar.clone() as java.util.Calendar
        val dayMillis = (1..lastDay).associateWith { day ->
            cal.set(java.util.Calendar.DAY_OF_MONTH, day)
            TimerPrefs.getDailyTotal(this, dateKeyFormat.format(cal.time))
        }
        val maxMillis = dayMillis.values.maxOrNull()?.coerceAtLeast(1L) ?: 1L

        val cells = mutableListOf<Int?>()
        repeat(leadingBlanks) { cells.add(null) }
        for (day in 1..lastDay) cells.add(day)
        while (cells.size % 7 != 0) cells.add(null)

        cells.chunked(7).forEach { week ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            week.forEach { day ->
                val millis = day?.let { dayMillis[it] } ?: 0L
                val isFuture = day != null && day > today
                val cellDateKey = day?.let { cal.set(java.util.Calendar.DAY_OF_MONTH, it); dateKeyFormat.format(cal.time) }
                row.addView(buildCalendarDayCell(day, millis, maxMillis, isFuture, cellDateKey))
            }
            rows.add(row)
        }
        return rows
    }

    private fun buildCalendarHeaderCell(letter: String): View {
        return TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(36), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginStart = dp(2)
                marginEnd = dp(2)
                bottomMargin = dp(4)
            }
            gravity = android.view.Gravity.CENTER
            text = letter
            textSize = 11f
            setTextColor(ContextCompat.getColor(context, R.color.sectionHeader))
        }
    }

    private fun buildCalendarDayCell(day: Int?, millis: Long, maxMillis: Long, isFuture: Boolean, cellDateKey: String?): View {
        val size = dp(36)
        return TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(size, size).apply {
                marginStart = dp(2)
                marginEnd = dp(2)
                topMargin = dp(2)
                bottomMargin = dp(2)
            }
            gravity = android.view.Gravity.CENTER
            textSize = 12f
            if (day == null) {
                text = ""
                background = null
            } else {
                text = day.toString()
                setTextColor(ContextCompat.getColor(context, R.color.labelText))
                val accent = themeAccentColor()
                val fraction = if (isFuture) 0f else millis.toFloat() / maxMillis.toFloat()
                val alpha = if (isFuture) 20 else (40 + 215 * fraction).toInt().coerceIn(40, 255)
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                    cornerRadius = dp(8).toFloat()
                    setColor(
                        android.graphics.Color.argb(
                            alpha,
                            android.graphics.Color.red(accent),
                            android.graphics.Color.green(accent),
                            android.graphics.Color.blue(accent)
                        )
                    )
                    if (cellDateKey == selectedDateKey) {
                        setStroke(dp(2), accent)
                    }
                }
                if (!isFuture && cellDateKey != null) {
                    isClickable = true
                    isFocusable = true
                    setOnClickListener { selectDate(cellDateKey) }
                }
            }
        }
    }

    /** One bar (plus optional label underneath) in a horizontal chart row, its height
     *  proportional to [millis] against [maxMillis] within [barHeightDp]'s space.
     *  Tapping it jumps the whole report to that day. */
    private fun buildBar(millis: Long, maxMillis: Long, label: String?, barHeightDp: Int, dateKey: String): View {
        val column = LinearLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(2)
                marginEnd = dp(2)
            }
            orientation = LinearLayout.VERTICAL
            gravity = android.view.Gravity.BOTTOM or android.view.Gravity.CENTER_HORIZONTAL
            isClickable = true
            isFocusable = true
            setOnClickListener { selectDate(dateKey) }
        }
        val fraction = millis.toFloat() / maxMillis.toFloat()
        val barHeightPx = maxOf(dp(4), dp((barHeightDp * fraction).toInt()))
        val bar = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, barHeightPx)
            setBackgroundColor(themeAccentColor())
            alpha = if (dateKey == selectedDateKey) 1f else 0.55f
        }
        column.addView(bar)
        if (label != null) {
            column.addView(TextView(this).apply {
                text = label
                gravity = android.view.Gravity.CENTER
                setTextColor(ContextCompat.getColor(context, R.color.labelText))
                textSize = 11f
                setTypeface(typeface, if (dateKey == selectedDateKey) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
                setPadding(0, dp(4), 0, 0)
            })
        }
        return column
    }

    private fun loadReportEntries(dateKey: String): List<ReportEntry> {
        val pm = packageManager
        val perApp = TimerPrefs.getPerAppMillisForDate(this, dateKey)
            .mapNotNull { (packageName, millis) ->
                try {
                    val appInfo = pm.getApplicationInfo(packageName, 0)
                    ReportEntry(
                        packageName = packageName,
                        label = pm.getApplicationLabel(appInfo).toString(),
                        icon = pm.getApplicationIcon(appInfo),
                        millis = millis
                    )
                } catch (e: PackageManager.NameNotFoundException) {
                    null
                }
            }

        // Always-mode time isn't attributed to a specific app; group it under a
        // synthetic "Manual" entry so those sessions are reachable too.
        val manualMillis = TimerPrefs.getSessionsForDate(this, dateKey)
            .filter { it.packageName == null }
            .sumOf { it.durationMillis }
        val withManual = if (manualMillis > 0) {
            perApp + ReportEntry(
                packageName = null,
                label = getString(R.string.session_log_manual_label),
                icon = applicationInfo.loadIcon(pm),
                millis = manualMillis
            )
        } else {
            perApp
        }

        return withManual.sortedByDescending { it.millis }
    }

    private fun sessionsFor(packageName: String?): List<SessionDetail> {
        return TimerPrefs.getSessionsForDate(this, selectedDateKey)
            .filter { it.packageName == packageName }
            .sortedByDescending { it.startWallClockMillis }
            .map { SessionDetail(it.startWallClockMillis, it.durationMillis) }
    }

    /** Builds one app's row plus its (initially collapsed) session list, which is
     *  populated the first time the row is tapped open. */
    private fun buildReportBlock(entry: ReportEntry): View {
        val wrapper = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val row = LayoutInflater.from(this).inflate(R.layout.item_report, wrapper, false)
        row.findViewById<ImageView>(R.id.reportAppIcon).setImageDrawable(entry.icon)
        row.findViewById<TextView>(R.id.reportAppName).text = entry.label
        row.findViewById<TextView>(R.id.reportAppTime).text = formatFriendlyDuration(entry.millis)
        val indicator = row.findViewById<TextView>(R.id.reportExpandIndicator)

        val sessionsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(dp(56), dp(2), dp(20), dp(10))
        }

        var expanded = false
        row.setOnClickListener {
            expanded = !expanded
            if (expanded && sessionsContainer.childCount == 0) {
                val sessions = sessionsFor(entry.packageName)
                if (sessions.isEmpty()) {
                    sessionsContainer.addView(TextView(this).apply {
                        text = getString(R.string.session_log_empty)
                        setTextColor(ContextCompat.getColor(context, R.color.labelText))
                        textSize = 13f
                        setPadding(0, dp(4), 0, dp(4))
                    })
                } else {
                    sessions.forEach { session -> sessionsContainer.addView(buildSessionSubRow(session)) }
                }
            }
            sessionsContainer.visibility = if (expanded) View.VISIBLE else View.GONE
            indicator.text = if (expanded) "⌄" else "›"
        }

        wrapper.addView(row)
        wrapper.addView(sessionsContainer)
        return wrapper
    }

    private fun buildSessionSubRow(session: SessionDetail): View {
        val row = LinearLayout(this).apply {
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(6), 0, dp(6))
        }
        val timeText = TextView(this).apply {
            text = timeFormat.format(session.startWallClockMillis)
            setTextColor(themeAccentColor())
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val durationText = TextView(this).apply {
            text = formatDuration(session.durationMillis)
            setTextColor(ContextCompat.getColor(context, R.color.labelText))
            textSize = 14f
            typeface = Typeface.MONOSPACE
        }
        row.addView(timeText)
        row.addView(durationText)
        return row
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun formatDuration(millis: Long): String {
        val totalSeconds = millis / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return String.format(Locale.getDefault(), "%02d:%02d:%02d", hours, minutes, seconds)
    }

    companion object {
        private const val TREND_WEEK = "week"
        private const val TREND_MONTH = "month"
        // Mirrors TimerPrefs.SESSION_RETENTION_DAYS - how far back day navigation
        // is allowed to go, since there's no session/per-app history before that.
        private const val SESSION_RETENTION_DAYS = 35
    }
}
