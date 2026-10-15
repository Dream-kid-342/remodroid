package com.remodroid.android

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.remodroid.android.ui.HomeActions
import com.remodroid.android.ui.HomeScreen
import com.remodroid.android.ui.IncomingRequestDialog
import com.remodroid.android.ui.RemoDroidTheme
import com.remodroid.shared.models.PermissionLevel
import com.remodroid.shared.session.ControllerUiState
import com.remodroid.shared.session.HostUiState
import com.remodroid.shared.session.IncomingRequest
import com.remodroid.shared.session.SessionState
import com.remodroid.shared.signaling.ConnectionStatus
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HomeScreenTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun showsCredentialsAndStatusWhenSharing() {
        compose.setContent {
            RemoDroidTheme {
                HomeScreen(
                    host = HostUiState(
                        state = SessionState.AVAILABLE,
                        sharingEnabled = true,
                        signaling = ConnectionStatus.CONNECTED,
                        deviceId = "123456789",
                        password = "abcd2345",
                        passwordExpiresAt = System.currentTimeMillis() + 600_000,
                    ),
                    controller = ControllerUiState(),
                    remoteControlEnabled = false,
                    actions = HomeActions(),
                )
            }
        }
        compose.onNodeWithText("123 456 789").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("abcd2345").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Temporary").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Ready for incoming connections").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Off · sessions are view-only until you enable it").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun connectPassesEnteredValuesAndShowsErrors() {
        var submitted: Triple<String, String, PermissionLevel>? = null
        compose.setContent {
            RemoDroidTheme {
                HomeScreen(
                    host = HostUiState(),
                    controller = ControllerUiState(state = SessionState.FAILED, message = "The partner ID or password is incorrect."),
                    remoteControlEnabled = false,
                    actions = HomeActions(onConnect = { id, pw, level -> submitted = Triple(id, pw, level) }),
                )
            }
        }
        compose.onNodeWithTag("partner_id").performScrollTo().performTextInput("123456789")
        compose.onNodeWithTag("partner_password").performScrollTo().performTextInput("abcd2345")
        compose.onNodeWithTag("connect_button").performScrollTo().performClick()
        assertEquals(Triple("123456789", "abcd2345", PermissionLevel.GUIDED_SUPPORT), submitted)
        compose.onNodeWithText("The partner ID or password is incorrect.").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun pendingRequestOffersCancel() {
        var cancelled = false
        compose.setContent {
            RemoDroidTheme {
                HomeScreen(
                    host = HostUiState(),
                    controller = ControllerUiState(state = SessionState.WAITING_FOR_APPROVAL),
                    remoteControlEnabled = false,
                    actions = HomeActions(onCancelConnect = { cancelled = true }),
                )
            }
        }
        compose.onNodeWithText("Waiting for the partner to approve…").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("cancel_connect").performScrollTo().performClick()
        assertEquals(true, cancelled)
    }

    @Test
    fun incomingRequestApproveAndReject() {
        var approved: PermissionLevel? = null
        var rejected = false
        compose.setContent {
            RemoDroidTheme {
                IncomingRequestDialog(
                    request = IncomingRequest("s".repeat(32), "Phone B", PermissionLevel.EXTENDED_SUPPORT, System.currentTimeMillis() + 60_000),
                    remoteControlEnabled = true,
                    onApprove = { approved = it },
                    onReject = { rejected = true },
                )
            }
        }
        compose.onNodeWithText("Phone B wants to see your screen.").assertIsDisplayed()
        compose.onNodeWithTag("approve").performClick()
        assertEquals(PermissionLevel.EXTENDED_SUPPORT, approved)
        compose.onNodeWithTag("reject").performClick()
        assertEquals(true, rejected)
    }
}
