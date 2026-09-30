package com.dupati.scrollsense

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent

/** Being an active device admin forces Settings > Security > Device Admin apps >
 *  Deactivate before this app can be uninstalled — a real deterrent against a casual
 *  attempt to remove the app to bypass Parental Lock, without needing any special
 *  device-owner provisioning. */
class ParentalLockAdminReceiver : DeviceAdminReceiver() {

    override fun onDisableRequested(context: Context, intent: Intent): CharSequence {
        return context.getString(R.string.device_admin_disable_warning)
    }
}
