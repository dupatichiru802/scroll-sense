package com.dupati.scrollsense

import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.firestore.FirebaseFirestore

/** Handles Google Sign-In and syncing the app's settings and usage history to
 *  Firestore, keyed by the signed-in user's UID. Settings use "latest wins" (the
 *  newer set of settings simply overwrites the old); usage history is additive and
 *  always merged, so signing in on a second device never loses time already tracked
 *  on either one. Signing in on a fresh install or a different device with the same
 *  Google account pulls down whatever was last synced instead of starting blank;
 *  signing in for the very first time anywhere uploads the current device's data as
 *  the new baseline. */
class SyncManager(private val context: Context) {

    private val auth = FirebaseAuth.getInstance()
    private val firestore = FirebaseFirestore.getInstance()

    private val googleSignInClient: GoogleSignInClient by lazy {
        val options = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestIdToken(context.getString(R.string.default_web_client_id))
            .requestEmail()
            .build()
        GoogleSignIn.getClient(context, options)
    }

    val isSignedIn: Boolean get() = auth.currentUser != null
    val currentUserEmail: String? get() = auth.currentUser?.email

    fun signInIntent(): Intent = googleSignInClient.signInIntent

    /** Call from onActivityResult with the intent the sign-in flow returned.
     *  [onResult] reports whether sign-in succeeded, and if so, whether this device's
     *  settings were replaced by a previously-synced set ([pulled] = true) or this
     *  device became the new baseline ([pulled] = false, nothing existed yet). */
    fun handleSignInResult(data: Intent?, onResult: (success: Boolean, pulled: Boolean) -> Unit) {
        try {
            val account = GoogleSignIn.getSignedInAccountFromIntent(data).getResult(ApiException::class.java)
            val credential = GoogleAuthProvider.getCredential(account.idToken, null)
            auth.signInWithCredential(credential)
                .addOnSuccessListener {
                    userDoc().get()
                        .addOnSuccessListener { snapshot ->
                            TimerPrefs.setLastSyncMillis(context, System.currentTimeMillis())
                            historyDoc().get()
                                .addOnSuccessListener { historySnapshot ->
                                    if (historySnapshot.exists()) {
                                        applyRemoteHistory(historySnapshot.data.orEmpty())
                                    } else {
                                        pushHistory { }
                                    }
                                }
                            if (snapshot.exists()) {
                                applyRemoteSettings(snapshot.data.orEmpty())
                                onResult(true, true)
                            } else {
                                pushSettings { }
                                onResult(true, false)
                            }
                        }
                        .addOnFailureListener { onResult(true, false) }
                }
                .addOnFailureListener { onResult(false, false) }
        } catch (e: ApiException) {
            onResult(false, false)
        }
    }

    fun signOut(onDone: () -> Unit) {
        auth.signOut()
        googleSignInClient.signOut().addOnCompleteListener { onDone() }
    }

    private fun userDoc() =
        firestore.collection("users").document(auth.currentUser!!.uid)
            .collection("sync").document("settings")

    private fun historyDoc() =
        firestore.collection("users").document(auth.currentUser!!.uid)
            .collection("sync").document("history")

    /** Uploads the current local settings, overwriting whatever was previously synced. */
    fun pushSettings(onDone: (Boolean) -> Unit) {
        if (!isSignedIn) {
            onDone(false)
            return
        }
        userDoc().set(collectLocalSettings())
            .addOnSuccessListener {
                TimerPrefs.setLastSyncMillis(context, System.currentTimeMillis())
                onDone(true)
            }
            .addOnFailureListener { onDone(false) }
    }

    /** Downloads and applies the last-synced settings, overwriting local ones. */
    fun pullSettings(onDone: (Boolean) -> Unit) {
        if (!isSignedIn) {
            onDone(false)
            return
        }
        userDoc().get()
            .addOnSuccessListener { snapshot ->
                if (snapshot.exists()) {
                    applyRemoteSettings(snapshot.data.orEmpty())
                    TimerPrefs.setLastSyncMillis(context, System.currentTimeMillis())
                    onDone(true)
                } else {
                    onDone(false)
                }
            }
            .addOnFailureListener { onDone(false) }
    }

    /** Uploads local usage history, merging into whatever was previously synced
     *  (never overwrites remote history with a smaller local total). */
    fun pushHistory(onDone: (Boolean) -> Unit) {
        if (!isSignedIn) {
            onDone(false)
            return
        }
        historyDoc().get()
            .addOnSuccessListener { snapshot ->
                if (snapshot.exists()) {
                    applyRemoteHistory(snapshot.data.orEmpty())
                }
                historyDoc().set(collectLocalHistory())
                    .addOnSuccessListener { onDone(true) }
                    .addOnFailureListener { onDone(false) }
            }
            .addOnFailureListener { onDone(false) }
    }

    /** Downloads remote usage history and merges it into local (additive, never
     *  discards local sessions or totals). */
    fun pullHistory(onDone: (Boolean) -> Unit) {
        if (!isSignedIn) {
            onDone(false)
            return
        }
        historyDoc().get()
            .addOnSuccessListener { snapshot ->
                if (snapshot.exists()) {
                    applyRemoteHistory(snapshot.data.orEmpty())
                    onDone(true)
                } else {
                    onDone(false)
                }
            }
            .addOnFailureListener { onDone(false) }
    }

    private fun collectLocalSettings(): Map<String, Any?> {
        val watched = TimerPrefs.getWatchedPackages(context)
        val selectedId = TimerPrefs.getSelectedMessageId(context)
        val customIds = TimerPrefs.getCustomMessageIds(context)
        val customTexts = customIds.map { TimerPrefs.getCustomMessageText(context, it) }
        val selectedCustomIndex = customIds.indexOf(selectedId).let { if (it < 0) -1 else it }

        return mapOf(
            "watchedPackages" to watched.toList(),
            "reminderIntervalMillis" to TimerPrefs.getReminderIntervalMillis(context),
            "glowIntervalMillis" to TimerPrefs.getGlowIntervalMillis(context),
            "newsEnabled" to TimerPrefs.isNewsEnabled(context),
            "newsTopics" to TimerPrefs.getNewsTopics(context).toList(),
            "watchedStocks" to TimerPrefs.getWatchedStocks(context),
            "dimDelayMillis" to TimerPrefs.getDimDelayMillis(context),
            "messageDisplayMillis" to TimerPrefs.getMessageDisplayMillis(context),
            "reminderDisplayMode" to TimerPrefs.getReminderDisplayMode(context),
            "overlayMode" to TimerPrefs.getOverlayMode(context),
            "bubbleColor" to TimerPrefs.getBubbleColor(context),
            "mascot" to TimerPrefs.getMascot(context),
            "selectedPresetMessageId" to (if (selectedId.startsWith("system_")) selectedId else null),
            "customMessageTexts" to customTexts,
            "selectedCustomMessageIndex" to selectedCustomIndex,
            "appLimitMinutes" to watched.associateWith { TimerPrefs.getAppLimitMinutes(context, it) }
                .filterValues { it > 0 }
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun applyRemoteSettings(data: Map<String, Any?>) {
        (data["watchedPackages"] as? List<String>)?.let {
            TimerPrefs.setWatchedPackages(context, it.toSet())
        }
        (data["reminderIntervalMillis"] as? Long)?.let { TimerPrefs.setReminderIntervalMillis(context, it) }
        (data["glowIntervalMillis"] as? Long)?.let { TimerPrefs.setGlowIntervalMillis(context, it) }
        (data["newsEnabled"] as? Boolean)?.let { TimerPrefs.setNewsEnabled(context, it) }
        (data["newsTopics"] as? List<String>)?.let { TimerPrefs.setNewsTopics(context, it.toSet()) }
        (data["watchedStocks"] as? List<String>)?.let { TimerPrefs.setWatchedStocks(context, it) }
        (data["dimDelayMillis"] as? Long)?.let { TimerPrefs.setDimDelayMillis(context, it) }
        (data["messageDisplayMillis"] as? Long)?.let { TimerPrefs.setMessageDisplayMillis(context, it) }
        (data["reminderDisplayMode"] as? String)?.let { TimerPrefs.setReminderDisplayMode(context, it) }
        (data["overlayMode"] as? String)?.let { TimerPrefs.setOverlayMode(context, it) }
        (data["bubbleColor"] as? String)?.let { TimerPrefs.setBubbleColor(context, it) }
        (data["mascot"] as? String)?.let { TimerPrefs.setMascot(context, it) }

        // Custom message ids are device-generated timestamps, not meaningful to sync
        // directly, so existing ones are cleared and rebuilt from the synced text.
        TimerPrefs.getCustomMessageIds(context).toList().forEach { TimerPrefs.deleteCustomMessage(context, it) }
        val customTexts = (data["customMessageTexts"] as? List<String>).orEmpty()
        val newIds = customTexts.mapNotNull { text ->
            TimerPrefs.addCustomMessage(context)?.also { id -> TimerPrefs.setCustomMessageText(context, id, text) }
        }

        val selectedCustomIndex = (data["selectedCustomMessageIndex"] as? Long)?.toInt() ?: -1
        val selectedPresetId = data["selectedPresetMessageId"] as? String
        when {
            selectedCustomIndex in newIds.indices -> TimerPrefs.setSelectedMessageId(context, newIds[selectedCustomIndex])
            selectedPresetId != null -> TimerPrefs.setSelectedMessageId(context, selectedPresetId)
        }

        val limits = data["appLimitMinutes"] as? Map<String, Any?> ?: emptyMap()
        limits.forEach { (pkg, minutes) ->
            val value = (minutes as? Long)?.toInt() ?: return@forEach
            TimerPrefs.setAppLimitMinutes(context, pkg, value)
        }
    }

    private fun collectLocalHistory(): Map<String, Any?> {
        return mapOf(
            "dailyTotals" to TimerPrefs.getAllDailyTotals(context),
            "perAppDailyTotals" to TimerPrefs.getAllPerAppDailyTotals(context),
            "rawSessions" to TimerPrefs.getRawSessionsList(context)
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun applyRemoteHistory(data: Map<String, Any?>) {
        val dailyTotals = (data["dailyTotals"] as? Map<String, Any?>)
            .orEmpty()
            .mapValues { (_, value) -> (value as? Long) ?: 0L }
        val perAppDailyTotals = (data["perAppDailyTotals"] as? Map<String, Any?>)
            .orEmpty()
            .mapValues { (_, value) -> (value as? Long) ?: 0L }
        val rawSessions = data["rawSessions"] as? String ?: ""
        TimerPrefs.mergeHistory(context, dailyTotals, perAppDailyTotals, rawSessions)
    }
}
