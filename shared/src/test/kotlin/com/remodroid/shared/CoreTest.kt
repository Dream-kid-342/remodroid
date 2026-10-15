package com.remodroid.shared

import com.remodroid.shared.control.ControlCodec
import com.remodroid.shared.control.CoordinateMapper
import com.remodroid.shared.control.GlobalAction
import com.remodroid.shared.control.GlobalActionCommand
import com.remodroid.shared.control.HostState
import com.remodroid.shared.control.NormalizedPoint
import com.remodroid.shared.control.ScreenInfo
import com.remodroid.shared.control.Swipe
import com.remodroid.shared.control.Tap
import com.remodroid.shared.control.TextInput
import com.remodroid.shared.models.Capability
import com.remodroid.shared.models.HostPlatform
import com.remodroid.shared.models.PermissionLevel
import com.remodroid.shared.models.PermissionPolicy
import com.remodroid.shared.protocol.ConnectionRequestReceived
import com.remodroid.shared.protocol.RefreshPassword
import com.remodroid.shared.protocol.RegisterHost
import com.remodroid.shared.protocol.RequestConnection
import com.remodroid.shared.protocol.ServerError
import com.remodroid.shared.protocol.SessionApproved
import com.remodroid.shared.protocol.SignalingCodec
import com.remodroid.shared.session.Role
import com.remodroid.shared.session.SessionState
import com.remodroid.shared.session.SessionStateMachine
import com.remodroid.shared.validation.Validation
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SignalingCodecTest {
    @Test
    fun `encodes messages with version and type and omits nulls`() {
        val obj = Json.parseToJsonElement(SignalingCodec.encode(RegisterHost(null))).jsonObject
        assertEquals(setOf("v", "type"), obj.keys)
        assertEquals("1", obj["v"]!!.jsonPrimitive.content)
        assertEquals("register_host", obj["type"]!!.jsonPrimitive.content)

        val obj2 = Json.parseToJsonElement(SignalingCodec.encode(RefreshPassword)).jsonObject
        assertEquals("refresh_password", obj2["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun `request_connection uses the server's field names and permission values`() {
        val text = SignalingCodec.encode(RequestConnection("r1", "123456789", "abcd2345", PermissionLevel.GUIDED_SUPPORT, "Phone B"))
        val obj = Json.parseToJsonElement(text).jsonObject
        assertEquals(setOf("v", "type", "requestId", "targetId", "password", "permission", "controllerName"), obj.keys)
        assertEquals("guided_support", obj["permission"]!!.jsonPrimitive.content)
    }

    @Test
    fun `does not leak credentials through toString`() {
        val s = RequestConnection("r1", "123456789", "abcd2345", PermissionLevel.VIEW_ONLY).toString()
        assertFalse("abcd2345" in s)
        assertFalse("123456789" in s)
    }

    @Test
    fun `decodes server messages and ignores unknown ones`() {
        val m = SignalingCodec.decode(
            """{"v":1,"type":"connection_request_received","sessionId":"s","controllerName":null,"requestedPermission":"extended_support","expiresAt":5,"future":1}""",
        )
        assertIs<ConnectionRequestReceived>(m)
        assertEquals(PermissionLevel.EXTENDED_SUPPORT, m.requestedPermission)

        val approved = SignalingCodec.decode(
            """{"v":1,"type":"session_approved","sessionId":"s","role":"host","permission":"view_only","requestedPermission":"view_only","peerName":null,"iceServers":[{"urls":["stun:x:1"]}],"expiresAt":1}""",
        )
        assertIs<SessionApproved>(approved)
        assertNull(approved.resumeToken)

        assertIs<ServerError>(SignalingCodec.decode("""{"v":1,"type":"error","code":"rate_limited","message":"m","retryAfterMs":5}"""))
        assertNull(SignalingCodec.decode("""{"v":1,"type":"brand_new"}"""))
        assertNull(SignalingCodec.decode("""{"v":2,"type":"heartbeat_ack","serverTime":1}"""))
        assertNull(SignalingCodec.decode("not json"))
    }
}

class StateMachineTest {
    @Test
    fun `controller happy path`() {
        val m = SessionStateMachine(Role.CONTROLLER)
        for (s in listOf(
            SessionState.REQUESTING_CONNECTION, SessionState.WAITING_FOR_APPROVAL, SessionState.NEGOTIATING,
            SessionState.CONNECTING_MEDIA, SessionState.CONNECTED_VIEW_ONLY, SessionState.CONNECTED_CONTROL_ENABLED,
            SessionState.RECONNECTING, SessionState.CONNECTED_CONTROL_ENABLED, SessionState.DISCONNECTING,
            SessionState.DISCONNECTED,
        )) assertTrue(m.transition(s), "-> $s")
    }

    @Test
    fun `refuses to claim connected before media negotiation`() {
        val m = SessionStateMachine(Role.CONTROLLER)
        m.transition(SessionState.REQUESTING_CONNECTION)
        assertFalse(m.transition(SessionState.CONNECTED_VIEW_ONLY))
        m.transition(SessionState.WAITING_FOR_APPROVAL)
        assertFalse(m.transition(SessionState.CONNECTED_CONTROL_ENABLED))
        assertEquals(SessionState.WAITING_FOR_APPROVAL, m.current)
    }

    @Test
    fun `prevents a second request while one is active`() {
        val m = SessionStateMachine(Role.CONTROLLER)
        m.transition(SessionState.REQUESTING_CONNECTION)
        m.transition(SessionState.WAITING_FOR_APPROVAL)
        assertFalse(m.transition(SessionState.REQUESTING_CONNECTION))
    }

    @Test
    fun `host returns to available after a session`() {
        val m = SessionStateMachine(Role.HOST)
        for (s in listOf(
            SessionState.AVAILABLE, SessionState.INCOMING_REQUEST, SessionState.NEGOTIATING,
            SessionState.CONNECTING_MEDIA, SessionState.CONNECTED_VIEW_ONLY, SessionState.DISCONNECTING,
            SessionState.AVAILABLE,
        )) assertTrue(m.transition(s), "-> $s")
        assertFalse(m.transition(SessionState.CONNECTED_VIEW_ONLY))
    }

    @Test
    fun `failure records the reason`() {
        val m = SessionStateMachine(Role.CONTROLLER)
        m.transition(SessionState.REQUESTING_CONNECTION)
        m.transition(SessionState.FAILED, "nope")
        assertEquals("nope", m.lastReason)
        assertTrue(m.transition(SessionState.REQUESTING_CONNECTION))
    }
}

class PermissionPolicyTest {
    private val androidWithAccessibility = setOf(Capability.POINTER, Capability.NAVIGATION, Capability.TEXT_INPUT, Capability.SYSTEM_PANELS)

    @Test
    fun `presets are cumulative`() {
        val levels = PermissionLevel.entries.map(PermissionPolicy::capabilitiesFor)
        levels.zipWithNext().forEach { (a, b) -> assertTrue(b.containsAll(a)) }
        assertEquals(setOf(Capability.VIEW_SCREEN), PermissionPolicy.capabilitiesFor(PermissionLevel.VIEW_ONLY))
    }

    @Test
    fun `effective capabilities are limited by device support and pause`() {
        assertEquals(
            setOf(Capability.VIEW_SCREEN, Capability.POINTER),
            PermissionPolicy.effective(PermissionLevel.GUIDED_SUPPORT, androidWithAccessibility, paused = false),
        )
        // Accessibility service disabled: maximum access still means view only.
        assertEquals(setOf(Capability.VIEW_SCREEN), PermissionPolicy.effective(PermissionLevel.MAXIMUM_SUPPORTED, emptySet(), false))
        assertEquals(emptySet(), PermissionPolicy.effective(PermissionLevel.MAXIMUM_SUPPORTED, androidWithAccessibility, paused = true))
    }

    @Test
    fun `granting is capped at the requested level`() {
        assertEquals(PermissionLevel.GUIDED_SUPPORT, PermissionLevel.MAXIMUM_SUPPORTED.atMost(PermissionLevel.GUIDED_SUPPORT))
        assertEquals(PermissionLevel.VIEW_ONLY, PermissionLevel.VIEW_ONLY.atMost(PermissionLevel.EXTENDED_SUPPORT))
    }
}

class ControlProtocolTest {
    @Test
    fun `round trips commands and host state`() {
        val tap = Tap(1, NormalizedPoint(0.5f, 0.25f))
        assertEquals(tap, ControlCodec.decode(ControlCodec.encode(tap)))
        val state = HostState(
            platform = HostPlatform.ANDROID,
            permission = PermissionLevel.GUIDED_SUPPORT,
            capabilities = setOf(Capability.VIEW_SCREEN, Capability.POINTER),
            paused = false,
            screen = ScreenInfo(720, 1600, 0),
        )
        assertEquals(state, ControlCodec.decode(ControlCodec.encode(state)))
        assertTrue(ControlCodec.encode(tap).contains("\"t\":\"tap\""))
    }

    @Test
    fun `rejects malformed commands`() {
        assertFalse(ControlCodec.isWellFormed(Tap(1, NormalizedPoint(1.5f, 0f))))
        assertFalse(ControlCodec.isWellFormed(Tap(1, NormalizedPoint(Float.NaN, 0f))))
        assertFalse(ControlCodec.isWellFormed(Swipe(1, listOf(NormalizedPoint(0f, 0f)), 100)))
        assertFalse(ControlCodec.isWellFormed(Swipe(1, List(2) { NormalizedPoint(0f, 0f) }, 60_000)))
        assertFalse(ControlCodec.isWellFormed(TextInput(1, "x".repeat(5000))))
        assertTrue(ControlCodec.isWellFormed(Swipe(1, listOf(NormalizedPoint(0f, 0f), NormalizedPoint(1f, 1f)), 300)))
        assertNull(ControlCodec.decode("""{"t":"format_disk"}"""))
    }

    @Test
    fun `required capabilities match the permission model`() {
        assertEquals(Capability.POINTER, Tap(1, NormalizedPoint(0f, 0f)).requiredCapability)
        assertEquals(Capability.NAVIGATION, GlobalActionCommand(1, GlobalAction.BACK).requiredCapability)
        assertEquals(Capability.SYSTEM_PANELS, GlobalActionCommand(1, GlobalAction.NOTIFICATIONS).requiredCapability)
        assertEquals(Capability.TEXT_INPUT, TextInput(1, "hi").requiredCapability)
    }
}

class CoordinateMapperTest {
    @Test
    fun `letterboxed portrait video in a landscape view`() {
        val rect = CoordinateMapper.fitRect(720, 1600, 1920f, 1080f)!!
        assertEquals(1080f, rect.height)
        assertEquals(486f, rect.width)
        assertEquals((1920f - 486f) / 2f, rect.left)
        assertNull(CoordinateMapper.viewToNormalized(10f, 10f, rect)) // in the black bar
        val center = CoordinateMapper.viewToNormalized(960f, 540f, rect)!!
        assertEquals(0.5f, center.x, 0.001f)
        assertEquals(0.5f, center.y, 0.001f)
    }

    @Test
    fun `maps normalized points to host pixels with clamping`() {
        assertEquals(0 to 0, CoordinateMapper.normalizedToDisplay(NormalizedPoint(0f, 0f), 1080, 2400))
        assertEquals(1079 to 2399, CoordinateMapper.normalizedToDisplay(NormalizedPoint(1f, 1f), 1080, 2400))
        assertEquals(540 to 1200, CoordinateMapper.normalizedToDisplay(NormalizedPoint(0.5f, 0.5f), 1081, 2401))
    }

    @Test
    fun `capture size keeps aspect ratio and even dimensions`() {
        assertEquals(576 to 1280, CoordinateMapper.captureSize(1080, 2400, 1280))
        assertEquals(1280 to 720, CoordinateMapper.captureSize(1920, 1080, 1280))
        assertEquals(800 to 480, CoordinateMapper.captureSize(800, 480, 1280))
        val (w, h) = CoordinateMapper.captureSize(1081, 2341, 1280)
        assertEquals(0, w % 2)
        assertEquals(0, h % 2)
    }
}

class ValidationTest {
    @Test
    fun `accepts formatted IDs and normalises passwords`() {
        assertTrue(Validation.isValidId("123 456 789"))
        assertTrue(Validation.isValidId("123-456-789"))
        assertFalse(Validation.isValidId("012345678"))
        assertFalse(Validation.isValidId("12345678"))
        assertFalse(Validation.isValidId("12345678a"))
        assertEquals("123 456 789", Validation.formatId("123456789"))
        assertTrue(Validation.isValidPassword("ABCD 2345"))
        assertFalse(Validation.isValidPassword("abcd0123")) // 0 and 1 are not in the alphabet
    }

    @Test
    fun `reports each form problem`() {
        assertEquals(
            listOf(Validation.Problem.IdEmpty, Validation.Problem.PasswordEmpty),
            Validation.validateConnectForm("", " "),
        )
        assertEquals(emptyList(), Validation.validateConnectForm("123456789", "abcd2345"))
    }

    @Test
    fun `sanitises display names`() {
        assertEquals("Phone", Validation.sanitizeDisplayName("Pho‮ne\u0000"))
        assertNull(Validation.sanitizeDisplayName("   "))
        assertNotNull(Validation.sanitizeDisplayName("x".repeat(100))?.also { assertEquals(64, it.length) })
    }
}
