package com.remodroid.android.host

import android.content.Intent
import com.remodroid.shared.models.PermissionLevel

/**
 * Sequences host approval: in-app consent -> Android screen-capture consent -> foreground service
 * -> approve on the server. Declining or cancelling the system dialog rejects the request, so a
 * partner is never told "approved" without a working capture permission.
 */
class ApprovalCoordinator(
    private val host: HostActions,
    private val storeConsent: (Intent) -> Unit,
    private val startService: () -> Unit,
    private val toast: (String) -> Unit,
) {
    interface HostActions {
        fun approve(level: PermissionLevel)
        fun reject()
    }

    var pendingLevel: PermissionLevel? = null
        private set

    fun beginApproval(level: PermissionLevel, launchSystemConsent: () -> Unit) {
        pendingLevel = level
        launchSystemConsent()
    }

    fun onConsentResult(granted: Boolean, data: Intent?) {
        val level = pendingLevel ?: return
        pendingLevel = null
        if (!granted || data == null) {
            host.reject()
            toast("Screen capture permission was not granted, so the request was declined.")
            return
        }
        storeConsent(data)
        startService()
        host.approve(level)
    }
}
