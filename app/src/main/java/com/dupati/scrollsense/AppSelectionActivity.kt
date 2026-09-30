package com.dupati.scrollsense

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.Filter
import android.widget.ImageView
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

private data class AppEntry(
    val packageName: String,
    val label: String,
    val icon: Drawable
)

/** One row in the list: either a section header or an actual app. Each header has a
 *  stable [sectionKey] so its expanded/collapsed state survives a search filter or an
 *  adapter rebuild. */
private sealed class ListRow {
    data class Header(val title: String, val sectionKey: String, val collapsed: Boolean) : ListRow()
    data class App(val entry: AppEntry) : ListRow()
}

/** Scanning every launchable app's label and icon is the slow part of this screen
 *  (icon decoding especially). Caching it for the process lifetime means only the
 *  first open of this screen per app session pays that cost — later opens are instant. */
private object AppListCache {
    @Volatile var apps: List<AppEntry>? = null
}

class AppSelectionActivity : AppCompatActivity() {

    private lateinit var watched: MutableSet<String>
    private lateinit var listView: ListView
    private lateinit var loadingIndicator: ProgressBar
    private lateinit var searchInput: EditText
    private lateinit var billingManager: BillingManager

    // Which sections ("watched" / "all") are collapsed. Both start expanded; not
    // persisted, resets each time this screen opens.
    private val collapsedSections = mutableSetOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        theme.applyStyle(bubbleColorThemeOverlayRes(TimerPrefs.getBubbleColor(this)), true)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_app_selection)

        watched = TimerPrefs.getWatchedPackages(this).toMutableSet()

        // Auto mode uses this list to decide when to auto-show/hide the bubble, so its
        // header talks about that; Always mode's bubble is already always on screen, so
        // the same list here is purely for picking which apps get a daily limit.
        findViewById<TextView>(R.id.appSelectionTitle).setText(
            if (TimerPrefs.getOverlayMode(this) == TimerPrefs.MODE_ALWAYS) {
                R.string.choose_apps_title_always
            } else {
                R.string.choose_apps_title
            }
        )

        listView = findViewById(R.id.appListView)
        loadingIndicator = findViewById(R.id.appListLoading)
        searchInput = findViewById(R.id.appSearchInput)
        billingManager = BillingManager(this) {}
        billingManager.startConnection()

        val cached = AppListCache.apps
        if (cached != null) {
            showApps(cached)
        } else {
            loadingIndicator.visibility = View.VISIBLE
            listView.visibility = View.GONE
            Thread {
                val apps = loadLaunchableApps()
                AppListCache.apps = apps
                runOnUiThread { showApps(apps) }
            }.start()
        }
    }

    private fun showApps(apps: List<AppEntry>) {
        loadingIndicator.visibility = View.GONE
        listView.visibility = View.VISIBLE

        val adapter = AppAdapter(apps)
        listView.adapter = adapter

        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                adapter.filter.filter(s)
            }
        })
    }

    override fun onDestroy() {
        super.onDestroy()
        billingManager.endConnection()
    }

    private fun showWatchedAppLimitUpsell() {
        AlertDialog.Builder(this)
            .setTitle(R.string.pro_upgrade_title)
            .setMessage(getString(R.string.pro_required_watched_apps, TimerPrefs.FREE_WATCHED_APP_LIMIT))
            .setPositiveButton(R.string.pro_dialog_upgrade_button) { _, _ ->
                billingManager.launchPurchaseFlow(this) {
                    Toast.makeText(this, R.string.pro_store_not_ready, Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(R.string.pro_dialog_not_now_button, null)
            .show()
    }

    private fun showLimitDialog(entry: AppEntry, onSaved: () -> Unit) {
        val padding = (20 * resources.displayMetrics.density).toInt()
        val currentMinutes = TimerPrefs.getAppLimitMinutes(this, entry.packageName)
        val input = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            hint = getString(R.string.app_limit_hint)
            if (currentMinutes > 0) setText(currentMinutes.toString())
        }
        val container = android.widget.FrameLayout(this).apply {
            setPadding(padding, padding / 4, padding, 0)
            addView(input)
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.app_limit_dialog_title)
            .setMessage(getString(R.string.app_limit_dialog_message, entry.label))
            .setView(container)
            .setPositiveButton(R.string.app_limit_save_button) { _, _ ->
                val minutes = input.text.toString().trim().toIntOrNull()?.coerceAtLeast(0) ?: 0
                TimerPrefs.setAppLimitMinutes(this, entry.packageName, minutes)
                onSaved()
            }
            .setNegativeButton(R.string.app_limit_remove_button) { _, _ ->
                TimerPrefs.setAppLimitMinutes(this, entry.packageName, 0)
                onSaved()
            }
            .setNeutralButton(R.string.cancel_button, null)
            .show()
    }

    private fun loadLaunchableApps(): List<AppEntry> {
        val pm = packageManager
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolveInfos = pm.queryIntentActivities(launcherIntent, 0)

        return resolveInfos
            .asSequence()
            .map { it.activityInfo.applicationInfo }
            .distinctBy { it.packageName }
            .filter { it.packageName != packageName }
            .map { appInfo: ApplicationInfo ->
                AppEntry(
                    packageName = appInfo.packageName,
                    label = pm.getApplicationLabel(appInfo).toString(),
                    icon = pm.getApplicationIcon(appInfo)
                )
            }
            .sortedBy { it.label.lowercase() }
            .toList()
    }

    /** Groups apps into "Currently watching" then "All apps" sections, each with its
     *  own collapsible header, so already-selected apps are easy to find at a glance
     *  and the full app list can be tucked away once you've found what you need. */
    private fun buildRows(apps: List<AppEntry>): List<ListRow> {
        val (watchedApps, otherApps) = apps.partition { watched.contains(it.packageName) }
        val rows = mutableListOf<ListRow>()
        if (watchedApps.isNotEmpty()) {
            val collapsed = "watched" in collapsedSections
            rows.add(ListRow.Header(getString(R.string.watched_apps_header), "watched", collapsed))
            if (!collapsed) watchedApps.forEach { rows.add(ListRow.App(it)) }
        }
        if (otherApps.isNotEmpty()) {
            val collapsed = "all" in collapsedSections
            rows.add(ListRow.Header(getString(R.string.all_apps_header), "all", collapsed))
            if (!collapsed) otherApps.forEach { rows.add(ListRow.App(it)) }
        }
        return rows
    }

    private inner class AppAdapter(private val allApps: List<AppEntry>) :
        ArrayAdapter<ListRow>(this@AppSelectionActivity, 0, buildRows(allApps).toMutableList()) {

        // Whichever app list is currently on screen (all apps, or a search-filtered
        // subset) - needed to rebuild rows correctly when a header is toggled.
        private var visibleApps: List<AppEntry> = allApps

        private fun rebuildRows() {
            clear()
            addAll(buildRows(visibleApps))
        }

        override fun getViewTypeCount(): Int = 2
        override fun getItemViewType(position: Int): Int = when (getItem(position)) {
            is ListRow.Header -> 0
            else -> 1
        }

        override fun areAllItemsEnabled(): Boolean = false
        override fun isEnabled(position: Int): Boolean = getItem(position) is ListRow.App

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            return when (val row = getItem(position)!!) {
                is ListRow.Header -> {
                    val view = convertView
                        ?: LayoutInflater.from(context).inflate(R.layout.item_app_header, parent, false)
                    view.findViewById<TextView>(R.id.appHeaderText).text = row.title
                    view.findViewById<ImageView>(R.id.appHeaderChevron).rotation =
                        if (row.collapsed) 0f else 180f
                    view.setOnClickListener {
                        if (row.collapsed) collapsedSections.remove(row.sectionKey) else collapsedSections.add(row.sectionKey)
                        rebuildRows()
                    }
                    view
                }
                is ListRow.App -> {
                    val view = convertView
                        ?: LayoutInflater.from(context).inflate(R.layout.item_app, parent, false)
                    val entry = row.entry

                    view.findViewById<ImageView>(R.id.appIcon).setImageDrawable(entry.icon)
                    view.findViewById<TextView>(R.id.appName).text = entry.label

                    val limitText = view.findViewById<TextView>(R.id.appLimitText)
                    fun refreshLimitText() {
                        if (watched.contains(entry.packageName)) {
                            val minutes = TimerPrefs.getAppLimitMinutes(this@AppSelectionActivity, entry.packageName)
                            limitText.text = if (minutes > 0) {
                                getString(R.string.app_limit_set_format, minutes)
                            } else {
                                getString(R.string.app_limit_none)
                            }
                            limitText.visibility = View.VISIBLE
                        } else {
                            limitText.visibility = View.GONE
                        }
                    }
                    refreshLimitText()
                    limitText.setOnClickListener { showLimitDialog(entry, ::refreshLimitText) }

                    val toggle = view.findViewById<Switch>(R.id.appToggle)
                    toggle.setOnCheckedChangeListener(null)
                    toggle.isChecked = watched.contains(entry.packageName)

                    lateinit var toggleListener: CompoundButton.OnCheckedChangeListener
                    toggleListener = CompoundButton.OnCheckedChangeListener { _, isChecked ->
                        val overLimit = isChecked &&
                            !TimerPrefs.isProUnlocked(this@AppSelectionActivity) &&
                            watched.size >= TimerPrefs.FREE_WATCHED_APP_LIMIT
                        if (overLimit) {
                            toggle.setOnCheckedChangeListener(null)
                            toggle.isChecked = false
                            toggle.setOnCheckedChangeListener(toggleListener)
                            showWatchedAppLimitUpsell()
                        } else {
                            if (isChecked) watched.add(entry.packageName) else watched.remove(entry.packageName)
                            TimerPrefs.setWatchedPackages(this@AppSelectionActivity, watched)
                        }
                        refreshLimitText()
                    }
                    toggle.setOnCheckedChangeListener(toggleListener)

                    view
                }
            }
        }

        override fun getFilter(): Filter = object : Filter() {
            override fun performFiltering(constraint: CharSequence?): FilterResults {
                val query = constraint?.toString()?.trim()?.lowercase().orEmpty()
                val matches = if (query.isEmpty()) {
                    allApps
                } else {
                    allApps.filter { it.label.lowercase().contains(query) }
                }
                return FilterResults().apply {
                    values = matches
                    count = matches.size
                }
            }

            @Suppress("UNCHECKED_CAST")
            override fun publishResults(constraint: CharSequence?, results: FilterResults) {
                visibleApps = results.values as List<AppEntry>
                rebuildRows()
            }
        }
    }
}
