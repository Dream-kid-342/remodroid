package com.remodroid.shared.control

import com.remodroid.shared.models.Capability
import com.remodroid.shared.models.HostPlatform
import com.remodroid.shared.models.PermissionLevel
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * Messages sent peer-to-peer over the WebRTC data channel labelled [CONTROL_CHANNEL_LABEL].
 * They never pass through the signaling server.
 *
 * Coordinates are normalised to the transmitted video frame: (0,0) is the top-left corner,
 * (1,1) the bottom-right. The host maps them onto its current display, so scaling, letterboxing
 * on the controller and host rotation do not need to be known by the controller.
 */
const val CONTROL_CHANNEL_LABEL = "remodroid-control"
const val CONTROL_PROTOCOL_VERSION = 1

@Serializable
data class NormalizedPoint(val x: Float, val y: Float) {
    fun isValid(): Boolean = x.isFinite() && y.isFinite() && x in 0f..1f && y in 0f..1f
}

@Serializable
sealed interface ControlMessage

// ---------------------------------------------------------------- controller -> host

@Serializable
sealed interface InputCommand : ControlMessage {
    val seq: Long

    /** The capability the host requires before executing this command. */
    val requiredCapability: Capability
}

@Serializable @SerialName("tap")
data class Tap(override val seq: Long, val point: NormalizedPoint) : InputCommand {
    override val requiredCapability get() = Capability.POINTER
}

@Serializable @SerialName("long_press")
data class LongPress(override val seq: Long, val point: NormalizedPoint, val durationMs: Long = 600) : InputCommand {
    override val requiredCapability get() = Capability.POINTER
}

/** A drag along [path] (at least two points) over [durationMs]. */
@Serializable @SerialName("swipe")
data class Swipe(override val seq: Long, val path: List<NormalizedPoint>, val durationMs: Long) : InputCommand {
    override val requiredCapability get() = Capability.POINTER
}

@Serializable
enum class MouseButton {
    @SerialName("left") LEFT,
    @SerialName("right") RIGHT,
    @SerialName("middle") MIDDLE,
}

/** Desktop-style pointer events. Android hosts reject these; controllers send gestures instead. */
@Serializable @SerialName("mouse_move")
data class MouseMove(override val seq: Long, val point: NormalizedPoint) : InputCommand {
    override val requiredCapability get() = Capability.POINTER
}

@Serializable @SerialName("mouse_button")
data class MouseButtonEvent(
    override val seq: Long,
    val point: NormalizedPoint,
    val button: MouseButton,
    val down: Boolean,
) : InputCommand {
    override val requiredCapability get() = Capability.POINTER
}

/** Wheel deltas in "notches"; positive dy scrolls content down. */
@Serializable @SerialName("wheel")
data class Wheel(override val seq: Long, val point: NormalizedPoint, val dx: Float, val dy: Float) : InputCommand {
    override val requiredCapability get() = Capability.POINTER
}

@Serializable
enum class GlobalAction {
    @SerialName("back") BACK,
    @SerialName("home") HOME,
    @SerialName("recents") RECENTS,
    @SerialName("notifications") NOTIFICATIONS,
    @SerialName("quick_settings") QUICK_SETTINGS,
}

@Serializable @SerialName("global_action")
data class GlobalActionCommand(override val seq: Long, val action: GlobalAction) : InputCommand {
    override val requiredCapability
        get() = when (action) {
            GlobalAction.BACK, GlobalAction.HOME, GlobalAction.RECENTS -> Capability.NAVIGATION
            GlobalAction.NOTIFICATIONS, GlobalAction.QUICK_SETTINGS -> Capability.SYSTEM_PANELS
        }
}

/** Inserts text at the host's focused input field. Limited in length per message. */
@Serializable @SerialName("text")
data class TextInput(override val seq: Long, val text: String) : InputCommand {
    override val requiredCapability get() = Capability.TEXT_INPUT
    override fun toString() = "TextInput(seq=$seq, length=${text.length})"
}

@Serializable
enum class Key {
    @SerialName("enter") ENTER,
    @SerialName("backspace") BACKSPACE,
    @SerialName("delete") DELETE,
    @SerialName("tab") TAB,
    @SerialName("escape") ESCAPE,
    @SerialName("arrow_left") ARROW_LEFT,
    @SerialName("arrow_right") ARROW_RIGHT,
    @SerialName("arrow_up") ARROW_UP,
    @SerialName("arrow_down") ARROW_DOWN,
    @SerialName("home") HOME,
    @SerialName("end") END,
    @SerialName("page_up") PAGE_UP,
    @SerialName("page_down") PAGE_DOWN,
    @SerialName("shift") SHIFT,
    @SerialName("control") CONTROL,
    @SerialName("alt") ALT,
}

@Serializable @SerialName("key")
data class KeyCommand(override val seq: Long, val key: Key, val down: Boolean) : InputCommand {
    override val requiredCapability get() = Capability.TEXT_INPUT
}

// ---------------------------------------------------------------- host -> controller

@Serializable
data class ScreenInfo(val width: Int, val height: Int, val rotationDegrees: Int)

/** Authoritative description of what the host currently allows. Sent on open and on every change. */
@Serializable @SerialName("host_state")
data class HostState(
    val protocol: Int = CONTROL_PROTOCOL_VERSION,
    val platform: HostPlatform,
    val permission: PermissionLevel,
    val capabilities: Set<Capability>,
    val paused: Boolean,
    val screen: ScreenInfo?,
    /** Human-readable explanation when control is unavailable (e.g. accessibility service disabled). */
    val controlNote: String? = null,
) : ControlMessage

@Serializable @SerialName("input_rejected")
data class InputRejected(val seq: Long, val reason: RejectReason, val detail: String? = null) : ControlMessage

@Serializable
enum class RejectReason {
    @SerialName("not_permitted") NOT_PERMITTED,
    @SerialName("unsupported") UNSUPPORTED,
    @SerialName("paused") PAUSED,
    @SerialName("invalid") INVALID,
    @SerialName("failed") FAILED,
}

object ControlCodec {
    const val MAX_TEXT_LENGTH = 1024
    const val MAX_SWIPE_POINTS = 64
    const val MAX_GESTURE_MS = 10_000L

    val json = Json {
        classDiscriminator = "t"
        encodeDefaults = true
        explicitNulls = false
        ignoreUnknownKeys = true
    }

    fun encode(message: ControlMessage): String = json.encodeToString(ControlMessage.serializer(), message)

    fun decode(text: String): ControlMessage? = try {
        json.decodeFromString(ControlMessage.serializer(), text)
    } catch (_: SerializationException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

    /** Structural validation the host applies before considering permissions. */
    fun isWellFormed(command: InputCommand): Boolean = when (command) {
        is Tap -> command.point.isValid()
        is LongPress -> command.point.isValid() && command.durationMs in 1..MAX_GESTURE_MS
        is Swipe -> command.path.size in 2..MAX_SWIPE_POINTS &&
            command.path.all { it.isValid() } &&
            command.durationMs in 1..MAX_GESTURE_MS
        is MouseMove -> command.point.isValid()
        is MouseButtonEvent -> command.point.isValid()
        is Wheel -> command.point.isValid() && command.dx.isFinite() && command.dy.isFinite() &&
            kotlin.math.abs(command.dx) <= 50 && kotlin.math.abs(command.dy) <= 50
        is TextInput -> command.text.length in 1..MAX_TEXT_LENGTH
        is GlobalActionCommand, is KeyCommand -> true
    }
}
