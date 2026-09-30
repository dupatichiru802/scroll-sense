package com.dupati.scrollsense

import android.app.AppOpsManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.os.Process
import android.provider.Settings
import android.text.InputFilter
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.CompoundButton
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var statusDot: View
    private lateinit var todayTotalText: TextView
    private lateinit var headerMascotIcon: ImageView
    private lateinit var modeOffOption: TextView
    private lateinit var modeAlwaysOption: TextView
    private lateinit var modeAutoOption: TextView
    private lateinit var chooseAppsButton: Button
    private lateinit var appsToWatchSection: View
    private lateinit var overlaySectionHeader: View
    private lateinit var overlaySectionContent: View
    private lateinit var overlaySectionChevron: ImageView
    private lateinit var tuningSectionHeader: View
    private lateinit var tuningSectionContent: View
    private lateinit var tuningSectionChevron: ImageView
    private lateinit var newsSectionHeader: View
    private lateinit var newsSectionContent: View
    private lateinit var newsSectionChevron: ImageView
    private lateinit var stocksSectionHeader: View
    private lateinit var stocksSectionContent: View
    private lateinit var stocksSectionChevron: ImageView
    private lateinit var reminderIntervalLabel: TextView
    private lateinit var reminderIntervalSeekBar: SeekBar
    private lateinit var glowIntervalLabel: TextView
    private lateinit var glowIntervalSeekBar: SeekBar
    private lateinit var newsEnabledSwitch: Switch
    private lateinit var customizeMessageButton: Button
    private lateinit var dimDelayLabel: TextView
    private lateinit var dimDelaySeekBar: SeekBar
    private lateinit var messageDisplayLabel: TextView
    private lateinit var messageDisplaySeekBar: SeekBar
    private lateinit var reminderDisplayTimedOption: TextView
    private lateinit var reminderDisplayPermanentOption: TextView
    private lateinit var batteryWarningText: TextView
    private lateinit var batteryOptimizationButton: Button
    private lateinit var parentalLockSwitch: Switch
    private lateinit var parentalLockStatusText: TextView
    private lateinit var devicePolicyManager: DevicePolicyManager
    private lateinit var adminComponent: ComponentName
    private lateinit var proUpgradeCard: MaterialCardView
    private lateinit var proUpgradeButton: MaterialButton
    private lateinit var billingManager: BillingManager
    private lateinit var syncManager: SyncManager
    private lateinit var signInButton: Button
    private lateinit var syncSignedInGroup: View
    private lateinit var syncAccountText: TextView
    private lateinit var syncStatusText: TextView
    private lateinit var syncNowButton: Button
    private lateinit var signOutButton: Button
    private lateinit var syncSectionHeader: View
    private lateinit var syncSectionContent: View
    private lateinit var syncSectionChevron: ImageView

    // The mode we're trying to reach once an in-flight permission request comes back.
    private var pendingMode: String? = null

    // Once the correct PIN has been entered, further setting changes in this same visit
    // don't re-prompt — this resets whenever the activity is freshly created.
    private var settingsUnlockedThisSession = false

    private val deviceAdminLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { }

    private val overlayPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            val mode = pendingMode
            pendingMode = null
            if (hasOverlayPermission() && mode != null) {
                selectMode(mode)
            } else {
                updateStatus()
            }
        }

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val mode = pendingMode
            pendingMode = null
            // Only retry selectMode() if permission was actually granted — otherwise,
            // since Android can resolve a repeat request instantly with no dialog once
            // denied, retrying unconditionally here would call selectMode() ->
            // notificationPermissionLauncher.launch() -> this callback -> selectMode()
            // ... synchronously forever, crashing with a StackOverflowError.
            if (granted && mode != null) {
                selectMode(mode)
            } else {
                updateStatus()
            }
        }

    private val usageAccessLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            val mode = pendingMode
            pendingMode = null
            if (hasUsageAccessPermission() && mode != null) {
                selectMode(mode)
            } else {
                updateStatus()
            }
        }

    private val signInLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            syncManager.handleSignInResult(result.data) { success, pulled ->
                runOnUiThread {
                    if (!success) {
                        Toast.makeText(this, R.string.sign_in_failed, Toast.LENGTH_SHORT).show()
                        return@runOnUiThread
                    }
                    if (pulled) {
                        // Settings just changed underneath every screen on this
                        // activity, so recreate rather than trying to patch each
                        // slider/toggle/label individually.
                        Toast.makeText(this, R.string.sync_status_restored, Toast.LENGTH_LONG).show()
                        recreate()
                    } else {
                        Toast.makeText(this, R.string.sync_status_baseline, Toast.LENGTH_LONG).show()
                        updateSyncUi()
                    }
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        theme.applyStyle(bubbleColorThemeOverlayRes(TimerPrefs.getBubbleColor(this)), true)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        statusDot = findViewById(R.id.accessibilityStatusDot)
        todayTotalText = findViewById(R.id.todayTotalText)
        headerMascotIcon = findViewById(R.id.headerMascotIcon)
        headerMascotIcon.setOnClickListener { withPinGate { showMascotPickerDialog() } }
        modeOffOption = findViewById(R.id.modeOffOption)
        modeAlwaysOption = findViewById(R.id.modeAlwaysOption)
        modeAutoOption = findViewById(R.id.modeAutoOption)
        chooseAppsButton = findViewById(R.id.chooseAppsButton)
        appsToWatchSection = findViewById(R.id.appsToWatchSection)
        overlaySectionHeader = findViewById(R.id.overlaySectionHeader)
        overlaySectionContent = findViewById(R.id.overlaySectionContent)
        overlaySectionChevron = findViewById(R.id.overlaySectionChevron)
        tuningSectionHeader = findViewById(R.id.tuningSectionHeader)
        tuningSectionContent = findViewById(R.id.tuningSectionContent)
        tuningSectionChevron = findViewById(R.id.tuningSectionChevron)
        newsSectionHeader = findViewById(R.id.newsSectionHeader)
        newsSectionContent = findViewById(R.id.newsSectionContent)
        newsSectionChevron = findViewById(R.id.newsSectionChevron)
        stocksSectionHeader = findViewById(R.id.stocksSectionHeader)
        stocksSectionContent = findViewById(R.id.stocksSectionContent)
        stocksSectionChevron = findViewById(R.id.stocksSectionChevron)
        reminderIntervalLabel = findViewById(R.id.reminderIntervalLabel)
        reminderIntervalSeekBar = findViewById(R.id.reminderIntervalSeekBar)
        glowIntervalLabel = findViewById(R.id.glowIntervalLabel)
        glowIntervalSeekBar = findViewById(R.id.glowIntervalSeekBar)
        newsEnabledSwitch = findViewById(R.id.newsEnabledSwitch)
        dimDelayLabel = findViewById(R.id.dimDelayLabel)
        dimDelaySeekBar = findViewById(R.id.dimDelaySeekBar)
        messageDisplayLabel = findViewById(R.id.messageDisplayLabel)
        messageDisplaySeekBar = findViewById(R.id.messageDisplaySeekBar)
        reminderDisplayTimedOption = findViewById(R.id.reminderDisplayTimedOption)
        reminderDisplayPermanentOption = findViewById(R.id.reminderDisplayPermanentOption)
        batteryWarningText = findViewById(R.id.batteryWarningText)
        batteryOptimizationButton = findViewById(R.id.batteryOptimizationButton)
        parentalLockSwitch = findViewById(R.id.parentalLockSwitch)
        parentalLockStatusText = findViewById(R.id.parentalLockStatusText)
        proUpgradeCard = findViewById(R.id.proUpgradeCard)
        proUpgradeButton = findViewById(R.id.proUpgradeButton)
        val promoCodeLink = findViewById<TextView>(R.id.promoCodeLink)
        val viewReportButton = findViewById<Button>(R.id.viewReportButton)
        signInButton = findViewById(R.id.signInButton)
        syncSignedInGroup = findViewById(R.id.syncSignedInGroup)
        syncAccountText = findViewById(R.id.syncAccountText)
        syncStatusText = findViewById(R.id.syncStatusText)
        syncNowButton = findViewById(R.id.syncNowButton)
        signOutButton = findViewById(R.id.signOutButton)
        syncSectionHeader = findViewById(R.id.syncSectionHeader)
        syncSectionContent = findViewById(R.id.syncSectionContent)
        syncSectionChevron = findViewById(R.id.syncSectionChevron)

        devicePolicyManager = getSystemService(DEVICE_POLICY_SERVICE) as DevicePolicyManager
        adminComponent = ComponentName(this, ParentalLockAdminReceiver::class.java)

        syncManager = SyncManager(this)
        signInButton.setOnClickListener { signInLauncher.launch(syncManager.signInIntent()) }
        syncNowButton.setOnClickListener {
            syncNowButton.isEnabled = false
            syncManager.pushSettings { settingsSuccess ->
                syncManager.pushHistory { historySuccess ->
                    runOnUiThread {
                        syncNowButton.isEnabled = true
                        syncStatusText.setText(
                            if (settingsSuccess && historySuccess) R.string.sync_status_baseline else R.string.sync_status_failed
                        )
                        updateSyncUi()
                    }
                }
            }
        }
        signOutButton.setOnClickListener {
            syncManager.signOut { runOnUiThread { updateSyncUi() } }
        }
        updateSyncUi()

        billingManager = BillingManager(this) {
            runOnUiThread {
                updateProUpgradeCard()
                Toast.makeText(this, R.string.pro_thanks, Toast.LENGTH_LONG).show()
            }
        }
        billingManager.startConnection()
        proUpgradeButton.setOnClickListener {
            billingManager.launchPurchaseFlow(this) {
                Toast.makeText(this, R.string.pro_store_not_ready, Toast.LENGTH_SHORT).show()
            }
        }
        promoCodeLink.setOnClickListener { showPromoCodeDialog() }
        // Dev-only unlock: real purchases can't complete until this app has a Play
        // Console product configured, so this is the only way to test Pro-gated
        // features pre-launch. Gated by DEV_UNLOCKS_ENABLED — must be false for the
        // build uploaded to Play Store (see build.gradle.kts).
        if (BuildConfig.DEV_UNLOCKS_ENABLED) {
            proUpgradeCard.setOnLongClickListener {
                TimerPrefs.setProUnlocked(this, true)
                updateProUpgradeCard()
                Toast.makeText(this, "Pro unlocked (dev)", Toast.LENGTH_SHORT).show()
                true
            }
        }

        modeOffOption.setOnClickListener { withPinGate { selectMode(TimerPrefs.MODE_OFF) } }
        modeAlwaysOption.setOnClickListener { withPinGate { selectMode(TimerPrefs.MODE_ALWAYS) } }
        modeAutoOption.setOnClickListener { withPinGate { selectMode(TimerPrefs.MODE_AUTO) } }
        chooseAppsButton.setOnClickListener {
            withPinGate { startActivity(Intent(this, AppSelectionActivity::class.java)) }
        }
        customizeMessageButton = findViewById(R.id.customizeMessageButton)
        customizeMessageButton.setOnClickListener {
            withPinGate { startActivity(Intent(this, ReminderMessageActivity::class.java)) }
        }
        batteryOptimizationButton.setOnClickListener {
            startActivity(Intent(
                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:$packageName")
            ))
        }
        viewReportButton.setOnClickListener {
            startActivity(Intent(this, DailyReportActivity::class.java))
        }

        val viewNewsButton = findViewById<Button>(R.id.viewNewsButton)
        viewNewsButton.setOnClickListener {
            startActivity(Intent(this, NewsActivity::class.java))
        }

        val viewStocksButton = findViewById<Button>(R.id.viewStocksButton)
        viewStocksButton.setOnClickListener {
            startActivity(Intent(this, StocksActivity::class.java))
        }

        val currentInterval = TimerPrefs.getReminderIntervalMillis(this)
        reminderIntervalSeekBar.progress = intervalMillisToProgress(currentInterval)
        updateReminderIntervalLabel(currentInterval)
        reminderIntervalSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                updateReminderIntervalLabel(progressToIntervalMillis(progress))
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {
                val intervalMillis = progressToIntervalMillis(seekBar.progress)
                withPinGate(
                    action = { TimerPrefs.setReminderIntervalMillis(this@MainActivity, intervalMillis) },
                    onDenied = {
                        val reverted = TimerPrefs.getReminderIntervalMillis(this@MainActivity)
                        seekBar.progress = intervalMillisToProgress(reverted)
                        updateReminderIntervalLabel(reverted)
                    }
                )
            }
        })

        // Reuses progressToIntervalMillis/intervalMillisToProgress below: MIN_GLOW_INTERVAL_MILLIS
        // matches MIN_REMINDER_INTERVAL_MILLIS (1 min), so the same 1-60 min mapping applies.
        val currentGlowInterval = TimerPrefs.getGlowIntervalMillis(this)
        glowIntervalSeekBar.progress = intervalMillisToProgress(currentGlowInterval)
        updateGlowIntervalLabel(currentGlowInterval)
        glowIntervalSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                updateGlowIntervalLabel(progressToIntervalMillis(progress))
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {
                val intervalMillis = progressToIntervalMillis(seekBar.progress)
                withPinGate(
                    action = { TimerPrefs.setGlowIntervalMillis(this@MainActivity, intervalMillis) },
                    onDenied = {
                        val reverted = TimerPrefs.getGlowIntervalMillis(this@MainActivity)
                        seekBar.progress = intervalMillisToProgress(reverted)
                        updateGlowIntervalLabel(reverted)
                    }
                )
            }
        })

        // News now shows a headline as soon as one's available rather than on a fixed
        // interval, so this is a plain on/off switch instead of a minutes slider.
        newsEnabledSwitch.isChecked = TimerPrefs.isNewsEnabled(this)
        lateinit var newsToggleListener: CompoundButton.OnCheckedChangeListener
        newsToggleListener = CompoundButton.OnCheckedChangeListener { _, isChecked ->
            withPinGate(
                action = { TimerPrefs.setNewsEnabled(this@MainActivity, isChecked) },
                onDenied = {
                    newsEnabledSwitch.setOnCheckedChangeListener(null)
                    newsEnabledSwitch.isChecked = !isChecked
                    newsEnabledSwitch.setOnCheckedChangeListener(newsToggleListener)
                }
            )
        }
        newsEnabledSwitch.setOnCheckedChangeListener(newsToggleListener)

        val currentDimDelay = TimerPrefs.getDimDelayMillis(this)
        dimDelaySeekBar.progress = dimMillisToProgress(currentDimDelay)
        updateDimDelayLabel(currentDimDelay)
        dimDelaySeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                updateDimDelayLabel(progressToDimMillis(progress))
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {
                val delayMillis = progressToDimMillis(seekBar.progress)
                withPinGate(
                    action = { TimerPrefs.setDimDelayMillis(this@MainActivity, delayMillis) },
                    onDenied = {
                        val reverted = TimerPrefs.getDimDelayMillis(this@MainActivity)
                        seekBar.progress = dimMillisToProgress(reverted)
                        updateDimDelayLabel(reverted)
                    }
                )
            }
        })

        val currentMessageDisplay = TimerPrefs.getMessageDisplayMillis(this)
        messageDisplaySeekBar.progress = messageDisplayMillisToProgress(currentMessageDisplay)
        updateMessageDisplayLabel(currentMessageDisplay)
        messageDisplaySeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                updateMessageDisplayLabel(progressToMessageDisplayMillis(progress))
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {
                val delayMillis = progressToMessageDisplayMillis(seekBar.progress)
                withPinGate(
                    action = { TimerPrefs.setMessageDisplayMillis(this@MainActivity, delayMillis) },
                    onDenied = {
                        val reverted = TimerPrefs.getMessageDisplayMillis(this@MainActivity)
                        seekBar.progress = messageDisplayMillisToProgress(reverted)
                        updateMessageDisplayLabel(reverted)
                    }
                )
            }
        })

        updateReminderDisplayModeSelection(TimerPrefs.getReminderDisplayMode(this))
        reminderDisplayTimedOption.setOnClickListener {
            withPinGate { setReminderDisplayMode(TimerPrefs.REMINDER_DISPLAY_TIMED) }
        }
        reminderDisplayPermanentOption.setOnClickListener {
            withPinGate { setReminderDisplayMode(TimerPrefs.REMINDER_DISPLAY_PERMANENT) }
        }

        setupCollapsibleSection(
            header = overlaySectionHeader,
            content = overlaySectionContent,
            chevron = overlaySectionChevron,
            getExpanded = { TimerPrefs.getOverlaySectionExpanded(this) },
            setExpanded = { TimerPrefs.setOverlaySectionExpanded(this, it) }
        )
        setupCollapsibleSection(
            header = tuningSectionHeader,
            content = tuningSectionContent,
            chevron = tuningSectionChevron,
            getExpanded = { TimerPrefs.getTuningSectionExpanded(this) },
            setExpanded = { TimerPrefs.setTuningSectionExpanded(this, it) }
        )
        setupCollapsibleSection(
            header = newsSectionHeader,
            content = newsSectionContent,
            chevron = newsSectionChevron,
            getExpanded = { TimerPrefs.getNewsSectionExpanded(this) },
            setExpanded = { TimerPrefs.setNewsSectionExpanded(this, it) }
        )
        setupCollapsibleSection(
            header = stocksSectionHeader,
            content = stocksSectionContent,
            chevron = stocksSectionChevron,
            getExpanded = { TimerPrefs.getStocksSectionExpanded(this) },
            setExpanded = { TimerPrefs.setStocksSectionExpanded(this, it) }
        )
        setupCollapsibleSection(
            header = syncSectionHeader,
            content = syncSectionContent,
            chevron = syncSectionChevron,
            getExpanded = { TimerPrefs.getSyncSectionExpanded(this) },
            setExpanded = { TimerPrefs.setSyncSectionExpanded(this, it) }
        )

        parentalLockSwitch.isChecked = TimerPrefs.getParentalLockEnabled(this)
        updateParentalLockStatusText(parentalLockSwitch.isChecked)
        parentalLockSwitch.setOnCheckedChangeListener { _, isChecked ->
            updateParentalLockStatusText(isChecked)
            onParentalLockToggled(isChecked)
        }
        refreshFineTuningLockState()
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
        updateProUpgradeCard()
    }

    override fun onDestroy() {
        super.onDestroy()
        billingManager.endConnection()
    }

    private fun updateProUpgradeCard() {
        if (TimerPrefs.isProUnlocked(this)) {
            proUpgradeCard.visibility = View.GONE
            return
        }
        proUpgradeCard.visibility = View.VISIBLE
        val price = billingManager.formattedPrice()
        proUpgradeButton.text = if (price != null) {
            getString(R.string.pro_upgrade_button_with_price, price)
        } else {
            getString(R.string.pro_upgrade_button)
        }
    }

    /** Shows an upsell prompt for a Pro-only feature; "See Pro" jumps straight to the
     *  purchase flow rather than making the user hunt for the upgrade card. */
    private fun showProUpsell(message: String) {
        AlertDialog.Builder(this)
            .setTitle(R.string.pro_upgrade_title)
            .setMessage(message)
            .setPositiveButton(R.string.pro_dialog_upgrade_button) { _, _ ->
                billingManager.launchPurchaseFlow(this) {
                    Toast.makeText(this, R.string.pro_store_not_ready, Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(R.string.pro_dialog_not_now_button, null)
            .show()
    }

    /** Lets the user type a promo code to unlock Pro before real Play Billing is set
     *  up (or later, for gifting/marketing codes even after launch). */
    private fun showPromoCodeDialog() {
        val input = EditText(this).apply {
            hint = getString(R.string.promo_code_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
        }
        val paddingPx = (24 * resources.displayMetrics.density).toInt()
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(paddingPx, 0, paddingPx, 0)
            addView(input)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.promo_code_title)
            .setView(container)
            .setPositiveButton(R.string.redeem_button, null)
            .setNegativeButton(R.string.cancel_button, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (PromoCodes.isValid(input.text.toString())) {
                    TimerPrefs.setProUnlocked(this, true)
                    updateProUpgradeCard()
                    dialog.dismiss()
                    Toast.makeText(this, R.string.promo_code_success, Toast.LENGTH_LONG).show()
                } else {
                    input.error = getString(R.string.promo_code_invalid)
                }
            }
        }
        dialog.show()
    }

    // --- Overlay mode -----------------------------------------------------

    /**
     * Moves toward the requested mode, chaining through whichever permission requests
     * are still needed. Safe to call repeatedly (e.g. after a permission result) since
     * it just re-checks what's missing each time.
     */
    private fun selectMode(mode: String) {
        when (mode) {
            TimerPrefs.MODE_OFF -> {
                TimerPrefs.setOverlayMode(this, mode)
                stopUsageWatcher()
                stopOverlay()
                updateStatus()
            }
            TimerPrefs.MODE_ALWAYS -> {
                if (!hasOverlayPermission()) {
                    pendingMode = mode
                    overlayPermissionLauncher.launch(overlayPermissionIntent())
                    return
                }
                // Notification permission is nice-to-have (makes the "running" notice
                // visible) but not required — the foreground service works fine without
                // it. Ask once, but don't block enabling the mode on the answer: if it's
                // already permanently denied, the system won't even show a dialog again,
                // so gating on it here would leave the mode impossible to turn on.
                requestNotificationPermissionIfNeeded()
                // Always mode still needs to know which app is in the foreground so
                // per-app daily limits can be enforced — Usage access is required for
                // that just like Auto mode, even though the bubble itself stays on
                // screen the whole time regardless of the foreground app.
                if (!hasUsageAccessPermission()) {
                    pendingMode = mode
                    usageAccessLauncher.launch(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                    return
                }
                TimerPrefs.setOverlayMode(this, mode)
                startOverlay()
                startUsageWatcher()
                updateStatus()
            }
            TimerPrefs.MODE_AUTO -> {
                if (!hasOverlayPermission()) {
                    pendingMode = mode
                    overlayPermissionLauncher.launch(overlayPermissionIntent())
                    return
                }
                requestNotificationPermissionIfNeeded()
                if (!hasUsageAccessPermission()) {
                    pendingMode = mode
                    usageAccessLauncher.launch(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                    return
                }
                TimerPrefs.setOverlayMode(this, mode)
                // Switching here from Always mode leaves its bubble running (Always
                // starts OverlayService directly and nothing ever stopped it) - Auto
                // mode drives the bubble entirely through UsageWatcherService's own
                // watched-app detection below, so any leftover instance needs clearing
                // first or it would just stay on screen for every app, not only watched
                // ones.
                if (OverlayService.isRunning) stopOverlay()
                startUsageWatcher()
                // UsageWatcherService.isRunning won't flip to true until the service's
                // onCreate() runs on a later main-thread message, which hasn't happened
                // yet at this point — so assume success here rather than reporting a
                // stale "not running" status that a second tap would immediately clear.
                updateStatus(assumeAutoJustStarted = true)
            }
        }
    }

    private fun overlayPermissionIntent() =
        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))

    /** Fire-and-forget: shows the system dialog if this is the first ask, silently
     *  no-ops if already permanently denied. Never blocks the caller on the result —
     *  see the comment in selectMode(). */
    private fun requestNotificationPermissionIfNeeded() {
        if (needsNotificationPermission()) {
            notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun needsNotificationPermission(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ActivityCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED

    private fun hasOverlayPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)

    private fun hasUsageAccessPermission(): Boolean {
        val appOps = getSystemService(APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), packageName)
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), packageName)
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    private fun isIgnoringBatteryOptimizations(): Boolean {
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        return powerManager.isIgnoringBatteryOptimizations(packageName)
    }

    private fun startOverlay() {
        val intent = Intent(this, OverlayService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun stopOverlay() {
        startService(Intent(this, OverlayService::class.java).apply {
            action = OverlayService.ACTION_STOP
        })
    }

    private fun startUsageWatcher() {
        val intent = Intent(this, UsageWatcherService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun stopUsageWatcher() {
        startService(Intent(this, UsageWatcherService::class.java).apply {
            action = UsageWatcherService.ACTION_STOP
        })
    }

    /** [beforeChange] runs just before recreate() - the mascot dialog this now lives
     *  in needs to dismiss itself first, since recreate() tears down this Activity and
     *  would otherwise leave the dialog's window orphaned (a "leaked window" crash). */
    private fun buildBubbleColorRow(row: LinearLayout, beforeChange: () -> Unit = {}) {
        val selected = TimerPrefs.getBubbleColor(this)
        row.removeAllViews()
        ALL_BUBBLE_COLORS.forEach { color ->
            val swatch = LayoutInflater.from(this).inflate(R.layout.item_color_swatch, row, false)
            swatch.findViewById<View>(R.id.colorSwatchFill).backgroundTintList =
                android.content.res.ColorStateList.valueOf(ContextCompat.getColor(this, bubbleColorSwatchRes(color)))
            swatch.isSelected = color == selected
            // Deliberately not PIN-gated - it's cosmetic only, so it stays changeable
            // even while parental lock is on and the mascot picker's other controls
            // aren't.
            swatch.setOnClickListener {
                if (color != TimerPrefs.getBubbleColor(this)) {
                    beforeChange()
                    TimerPrefs.setBubbleColor(this, color)
                    refreshOverlayTheme()
                    recreate()
                }
            }
            row.addView(swatch)
        }
    }

    /** Quick mascot + bubble colour picker reached by tapping the header icon
     *  directly, independent of the "Customise Reminder Message" screen - picking
     *  either applies immediately, no separate save step. Bubble colour lives here
     *  (rather than its own card) since it's the other half of "how Scroll Buddy
     *  looks", right alongside picking which mascot it is. */
    private fun showMascotPickerDialog() {
        val density = resources.displayMetrics.density
        lateinit var dialog: AlertDialog

        val mascotRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER
            val padding = (12 * density).toInt()
            setPadding(padding, padding, padding, padding)
        }
        val selectedMascot = TimerPrefs.getMascot(this)
        ALL_MASCOTS.forEach { mascot ->
            val option = LayoutInflater.from(this).inflate(R.layout.item_mascot_option, mascotRow, false)
            option.findViewById<ImageView>(R.id.mascotIcon).setImageResource(mascotDrawableRes(mascot))
            option.contentDescription = getString(mascotLabelRes(mascot))
            option.isSelected = mascot == selectedMascot
            option.setOnClickListener {
                TimerPrefs.setMascot(this, mascot)
                headerMascotIcon.setImageResource(mascotDrawableRes(mascot))
                dialog.dismiss()
            }
            mascotRow.addView(option)
        }

        val colorLabel = TextView(this).apply {
            text = getString(R.string.bubble_colour_label)
            setTextColor(themeAccentColor())
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            textSize = 13f
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (18 * density).toInt() }
        }
        val colorRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (10 * density).toInt() }
        }
        buildBubbleColorRow(colorRow, beforeChange = { dialog.dismiss() })

        val outerContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER_HORIZONTAL
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            val sidePadding = (8 * density).toInt()
            setPadding(sidePadding, 0, sidePadding, 0)
            addView(mascotRow)
            addView(colorLabel)
            addView(colorRow)
        }

        dialog = AlertDialog.Builder(this)
            .setTitle(R.string.section_mascot)
            .setMessage(R.string.mascot_subtitle)
            .setView(outerContainer)
            .setNegativeButton(R.string.cancel_button, null)
            .create()
        dialog.show()
    }

    /** Wires a section header to show/hide its content and flip a chevron, remembering
     *  the expanded/collapsed state across app launches (not parental-lock gated — this
     *  is just a display preference, not a setting change). */
    private fun refreshOverlayTheme() {
        if (OverlayService.isRunning) {
            startService(Intent(this, OverlayService::class.java).apply {
                action = OverlayService.ACTION_REFRESH_THEME
            })
        }
    }

    private fun updateStatus(assumeAutoJustStarted: Boolean = false) {
        val mode = TimerPrefs.getOverlayMode(this)

        modeOffOption.isSelected = mode == TimerPrefs.MODE_OFF
        modeAlwaysOption.isSelected = mode == TimerPrefs.MODE_ALWAYS
        modeAutoOption.isSelected = mode == TimerPrefs.MODE_AUTO

        val (statusStringRes, statusColorRes) = when (mode) {
            TimerPrefs.MODE_ALWAYS -> R.string.overlay_status_always to R.color.statusOn
            TimerPrefs.MODE_AUTO -> if (UsageWatcherService.isRunning || assumeAutoJustStarted) {
                R.string.overlay_status_auto_on to R.color.statusOn
            } else {
                R.string.overlay_status_auto_off to R.color.statusOff
            }
            else -> R.string.overlay_status_off to R.color.statusOff
        }
        statusText.setText(statusStringRes)
        val statusColor = ContextCompat.getColor(this, statusColorRes)
        statusText.setTextColor(statusColor)
        statusDot.background.mutate().setTint(statusColor)

        // Auto mode uses this list to decide when to auto-show/hide the bubble; Always
        // mode has no use for that (the bubble is always up) but still needs it to pick
        // which apps get a daily limit, so this section — and its per-app limit dialog —
        // needs to be reachable in both modes. Lives inside Fine-tuning rather than its
        // own card since it's no longer just an Auto-mode display setting.
        appsToWatchSection.visibility =
            if (mode == TimerPrefs.MODE_AUTO || mode == TimerPrefs.MODE_ALWAYS) View.VISIBLE else View.GONE

        val needsBatteryFix = mode != TimerPrefs.MODE_OFF && !isIgnoringBatteryOptimizations()
        batteryWarningText.visibility = if (needsBatteryFix) View.VISIBLE else View.GONE
        batteryOptimizationButton.visibility = if (needsBatteryFix) View.VISIBLE else View.GONE

        val todayMillis = TimerPrefs.getTodayMillis(this)
        todayTotalText.text = formatFriendlyDuration(todayMillis)

        headerMascotIcon.setImageResource(mascotDrawableRes(TimerPrefs.getMascot(this)))
    }

    // --- Fine-tuning sliders -----------------------------------------------

    private fun progressToIntervalMillis(progress: Int): Long =
        TimerPrefs.MIN_REMINDER_INTERVAL_MILLIS + progress * 60_000L

    private fun intervalMillisToProgress(millis: Long): Int =
        ((millis - TimerPrefs.MIN_REMINDER_INTERVAL_MILLIS) / 60_000L).toInt()

    private fun updateReminderIntervalLabel(intervalMillis: Long) {
        val minutes = (intervalMillis / 60_000L).toInt()
        reminderIntervalLabel.text = getString(R.string.reminder_interval_format, minutes)
    }

    private fun updateGlowIntervalLabel(intervalMillis: Long) {
        val minutes = (intervalMillis / 60_000L).toInt()
        glowIntervalLabel.text = getString(R.string.glow_interval_format, minutes)
    }

    private fun progressToDimMillis(progress: Int): Long =
        TimerPrefs.MIN_DIM_DELAY_MILLIS + progress * 100L

    private fun dimMillisToProgress(millis: Long): Int =
        ((millis - TimerPrefs.MIN_DIM_DELAY_MILLIS) / 100L).toInt()

    private fun updateDimDelayLabel(delayMillis: Long) {
        dimDelayLabel.text = getString(R.string.dim_delay_format, delayMillis / 1000f)
    }

    private fun progressToMessageDisplayMillis(progress: Int): Long =
        TimerPrefs.MIN_MESSAGE_DISPLAY_MILLIS + progress * 500L

    private fun messageDisplayMillisToProgress(millis: Long): Int =
        ((millis - TimerPrefs.MIN_MESSAGE_DISPLAY_MILLIS) / 500L).toInt()

    private fun updateMessageDisplayLabel(delayMillis: Long) {
        messageDisplayLabel.text = getString(R.string.message_display_format, delayMillis / 1000f)
    }

    private fun setReminderDisplayMode(mode: String) {
        TimerPrefs.setReminderDisplayMode(this, mode)
        updateReminderDisplayModeSelection(mode)
        refreshFineTuningLockState()
    }

    private fun updateReminderDisplayModeSelection(mode: String) {
        val isTimed = mode == TimerPrefs.REMINDER_DISPLAY_TIMED
        reminderDisplayTimedOption.isSelected = isTimed
        reminderDisplayPermanentOption.isSelected = !isTimed
    }

    /** Whether Fine-tuning is currently locked: the parental PIN is set and hasn't
     *  been entered yet this visit. */
    private fun isFineTuningLocked(): Boolean =
        TimerPrefs.getParentalLockEnabled(this) && !settingsUnlockedThisSession

    /** Disables (and dims) every Fine-tuning control except the bubble colour picker
     *  while parental lock is active and not yet unlocked this visit - the colour
     *  picker is cosmetic only, so it's left interactive regardless. The message
     *  display slider additionally stays disabled whenever Permanent mode is chosen,
     *  independent of the lock, since it has nothing to control in that mode. */
    private fun refreshFineTuningLockState() {
        val locked = isFineTuningLocked()
        val lockAlpha = if (locked) 0.4f else 1f

        reminderIntervalLabel.isEnabled = !locked
        reminderIntervalSeekBar.isEnabled = !locked
        reminderIntervalLabel.alpha = lockAlpha
        reminderIntervalSeekBar.alpha = lockAlpha

        glowIntervalLabel.isEnabled = !locked
        glowIntervalSeekBar.isEnabled = !locked
        glowIntervalLabel.alpha = lockAlpha
        glowIntervalSeekBar.alpha = lockAlpha

        newsEnabledSwitch.isEnabled = !locked
        newsEnabledSwitch.alpha = lockAlpha

        customizeMessageButton.isEnabled = !locked
        customizeMessageButton.alpha = lockAlpha

        dimDelayLabel.isEnabled = !locked
        dimDelaySeekBar.isEnabled = !locked
        dimDelayLabel.alpha = lockAlpha
        dimDelaySeekBar.alpha = lockAlpha

        reminderDisplayTimedOption.isEnabled = !locked
        reminderDisplayPermanentOption.isEnabled = !locked
        reminderDisplayTimedOption.alpha = lockAlpha
        reminderDisplayPermanentOption.alpha = lockAlpha

        val isTimed = TimerPrefs.getReminderDisplayMode(this) == TimerPrefs.REMINDER_DISPLAY_TIMED
        val messageControlsEnabled = !locked && isTimed
        messageDisplaySeekBar.isEnabled = messageControlsEnabled
        val messageAlpha = if (locked) lockAlpha else if (isTimed) 1f else 0.4f
        messageDisplaySeekBar.alpha = messageAlpha
        messageDisplayLabel.alpha = messageAlpha

        // Bubble colour row is deliberately left alone here - it stays enabled.
    }

    // --- Parental lock -------------------------------------------------------

    /** Runs [action] immediately if the lock is off or already unlocked this visit;
     *  otherwise prompts for the PIN first, running [action] only on success. */
    private fun withPinGate(onDenied: () -> Unit = {}, action: () -> Unit) {
        if (!TimerPrefs.getParentalLockEnabled(this) || settingsUnlockedThisSession) {
            action()
            return
        }
        showPinEntryDialog(
            title = getString(R.string.enter_pin_title),
            message = getString(R.string.enter_pin_message),
            confirmText = getString(R.string.unlock_button),
            errorMessage = getString(R.string.incorrect_pin),
            onConfirm = { pin ->
                val ok = TimerPrefs.verifyParentalPin(this, pin)
                if (ok) {
                    settingsUnlockedThisSession = true
                    refreshFineTuningLockState()
                    action()
                }
                ok
            },
            onCancel = onDenied
        )
    }

    private fun updateParentalLockStatusText(isOn: Boolean) {
        parentalLockStatusText.text = getString(
            if (isOn) R.string.parental_lock_status_on else R.string.parental_lock_status_off
        )
        parentalLockStatusText.setTextColor(
            ContextCompat.getColor(this, if (isOn) R.color.statusOn else R.color.statusOff)
        )
    }

    private fun updateSyncUi() {
        val signedIn = syncManager.isSignedIn
        signInButton.visibility = if (signedIn) View.GONE else View.VISIBLE
        syncSignedInGroup.visibility = if (signedIn) View.VISIBLE else View.GONE
        if (!signedIn) return

        syncAccountText.text = syncManager.currentUserEmail.orEmpty()
        val lastSync = TimerPrefs.getLastSyncMillis(this)
        syncStatusText.text = if (lastSync > 0) {
            val formatted = java.text.SimpleDateFormat("MMM d, h:mm a", Locale.getDefault()).format(lastSync)
            getString(R.string.sync_status_synced_format, formatted)
        } else {
            getString(R.string.sync_status_never)
        }
    }

    private fun onParentalLockToggled(isChecked: Boolean) {
        val alreadyEnabled = TimerPrefs.getParentalLockEnabled(this)
        if (isChecked == alreadyEnabled) return

        if (isChecked) {
            if (!TimerPrefs.isProUnlocked(this)) {
                parentalLockSwitch.isChecked = false
                showProUpsell(getString(R.string.pro_required_parental_lock))
                return
            }
            promptSetNewPin(
                onSet = {
                    TimerPrefs.setParentalLockEnabled(this, true)
                    settingsUnlockedThisSession = false
                    refreshFineTuningLockState()
                    promptDeviceAdmin()
                },
                onCancel = { parentalLockSwitch.isChecked = false }
            )
        } else {
            showPinEntryDialog(
                title = getString(R.string.enter_pin_title),
                message = getString(R.string.enter_pin_message),
                confirmText = getString(R.string.unlock_button),
                errorMessage = getString(R.string.incorrect_pin),
                onConfirm = { pin ->
                    val ok = TimerPrefs.verifyParentalPin(this, pin)
                    if (ok) {
                        TimerPrefs.setParentalLockEnabled(this, false)
                        TimerPrefs.clearParentalPin(this)
                        settingsUnlockedThisSession = false
                        refreshFineTuningLockState()
                        removeDeviceAdminIfActive()
                    }
                    ok
                },
                onCancel = { parentalLockSwitch.isChecked = true }
            )
        }
    }

    private fun promptSetNewPin(onSet: () -> Unit, onCancel: () -> Unit) {
        showPinEntryDialog(
            title = getString(R.string.set_pin_title),
            message = getString(R.string.set_pin_message),
            confirmText = getString(R.string.set_pin_button),
            errorMessage = null,
            onConfirm = { firstPin ->
                showPinEntryDialog(
                    title = getString(R.string.confirm_pin_title),
                    message = getString(R.string.confirm_pin_message),
                    confirmText = getString(R.string.set_pin_button),
                    errorMessage = getString(R.string.pin_mismatch),
                    onConfirm = { secondPin ->
                        val matches = secondPin == firstPin
                        if (matches) {
                            TimerPrefs.setParentalPin(this, firstPin)
                            onSet()
                        }
                        matches
                    },
                    onCancel = onCancel
                )
                true
            },
            onCancel = onCancel
        )
    }

    private fun promptDeviceAdmin() {
        if (devicePolicyManager.isAdminActive(adminComponent)) return
        AlertDialog.Builder(this)
            .setTitle(R.string.device_admin_request_title)
            .setMessage(R.string.device_admin_request_message)
            .setPositiveButton(R.string.device_admin_request_button) { _, _ ->
                val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                    putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent)
                    putExtra(
                        DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                        getString(R.string.device_admin_request_message)
                    )
                }
                deviceAdminLauncher.launch(intent)
            }
            .setNegativeButton(R.string.device_admin_skip_button, null)
            .show()
    }

    private fun removeDeviceAdminIfActive() {
        if (devicePolicyManager.isAdminActive(adminComponent)) {
            devicePolicyManager.removeActiveAdmin(adminComponent)
        }
    }

    /** Shows a 4-digit PIN entry dialog. [onConfirm] returns true to accept and close the
     *  dialog, or false to show [errorMessage] and let the user try again. */
    private fun showPinEntryDialog(
        title: String,
        message: String,
        confirmText: String,
        errorMessage: String?,
        onConfirm: (String) -> Boolean,
        onCancel: () -> Unit
    ) {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            filters = arrayOf(InputFilter.LengthFilter(4))
            hint = getString(R.string.pin_hint)
        }
        val paddingPx = (24 * resources.displayMetrics.density).toInt()
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(paddingPx, 0, paddingPx, 0)
            addView(input)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setView(container)
            .setPositiveButton(confirmText, null)
            .setNegativeButton(R.string.cancel_button) { _, _ -> onCancel() }
            .setOnCancelListener { onCancel() }
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val pin = input.text.toString()
                if (pin.length == 4 && onConfirm(pin)) {
                    dialog.dismiss()
                } else {
                    input.error = errorMessage
                }
            }
        }
        dialog.show()
    }
}
