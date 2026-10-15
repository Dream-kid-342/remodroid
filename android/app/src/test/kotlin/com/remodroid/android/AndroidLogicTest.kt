package com.remodroid.android

import android.content.Intent
import com.remodroid.android.data.AppSettings
import com.remodroid.android.host.ApprovalCoordinator
import com.remodroid.android.input.AccessibilityStatus
import com.remodroid.shared.models.PermissionLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ApprovalCoordinatorTest {
    private val calls = mutableListOf<String>()
    private val coordinator = ApprovalCoordinator(
        host = object : ApprovalCoordinator.HostActions {
            override fun approve(level: PermissionLevel) { calls += "approve:${level.name}" }
            override fun reject() { calls += "reject" }
        },
        storeConsent = { calls += "store" },
        startService = { calls += "service" },
        toast = { calls += "toast" },
    )

    @Test
    fun `cancelled MediaProjection consent rejects the request`() {
        coordinator.beginApproval(PermissionLevel.GUIDED_SUPPORT) { calls += "launch" }
        coordinator.onConsentResult(granted = false, data = null)
        assertEquals(listOf("launch", "reject", "toast"), calls)
        assertNull(coordinator.pendingLevel)
    }

    @Test
    fun `granted consent starts the foreground service before approving`() {
        coordinator.beginApproval(PermissionLevel.VIEW_ONLY) { calls += "launch" }
        coordinator.onConsentResult(granted = true, data = Intent())
        assertEquals(listOf("launch", "store", "service", "approve:VIEW_ONLY"), calls)
    }

    @Test
    fun `a stray consent result without a pending approval is ignored`() {
        coordinator.onConsentResult(granted = true, data = Intent())
        assertTrue(calls.isEmpty())
    }
}

class AccessibilityStatusTest {
    private val full = "com.remodroid.android/com.remodroid.android.input.RemoteControlAccessibilityService"
    private val short = "com.remodroid.android/.input.RemoteControlAccessibilityService"

    @Test
    fun `detects the service in the enabled list`() {
        assertTrue(AccessibilityStatus.isServiceListed("com.other/.Svc:$full", full, short))
        assertTrue(AccessibilityStatus.isServiceListed(short, full, short))
    }

    @Test
    fun `reports disabled when absent or empty`() {
        assertFalse(AccessibilityStatus.isServiceListed(null, full, short))
        assertFalse(AccessibilityStatus.isServiceListed("", full, short))
        assertFalse(AccessibilityStatus.isServiceListed("com.other/.Svc", full, short))
    }
}

class SettingsValidationTest {
    @Test
    fun `release builds require wss`() {
        assertNull(AppSettings.validateUrl("wss://signal.example.com/ws", allowCleartext = false))
        assertNotNull(AppSettings.validateUrl("ws://10.0.2.2:8080/ws", allowCleartext = false))
        assertNull(AppSettings.validateUrl("ws://10.0.2.2:8080/ws", allowCleartext = true))
        assertNotNull(AppSettings.validateUrl("https://example.com", allowCleartext = true))
    }
}
