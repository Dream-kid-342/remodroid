package com.remodroid.shared.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Permission presets a controller can request and a host can grant.
 * The host is the only party that enforces them; see [PermissionPolicy].
 */
@Serializable
enum class PermissionLevel(val label: String, val description: String) {
    @SerialName("view_only")
    VIEW_ONLY("View only", "Screen viewing. No remote input."),

    @SerialName("guided_support")
    GUIDED_SUPPORT("Guided support", "Screen viewing plus taps, long presses and swipes."),

    @SerialName("extended_support")
    EXTENDED_SUPPORT("Extended support", "Guided support plus navigation buttons and text input."),

    @SerialName("maximum_supported")
    MAXIMUM_SUPPORTED(
        "Maximum supported access",
        "Every documented capability this device allows, such as opening the notification shade. " +
            "It never bypasses lock screens or protected windows.",
    );

    fun atMost(other: PermissionLevel): PermissionLevel = if (ordinal <= other.ordinal) this else other
}

/** Individual capabilities a permission level can include. */
@Serializable
enum class Capability {
    /** Receive the host's screen video. */
    @SerialName("view_screen")
    VIEW_SCREEN,

    /** Taps, long presses, swipes; mouse movement, clicks and wheel on desktop hosts. */
    @SerialName("pointer")
    POINTER,

    /** Android Back / Home / Recents. */
    @SerialName("navigation")
    NAVIGATION,

    /** Typing text and sending editing keys. */
    @SerialName("text_input")
    TEXT_INPUT,

    /** Notification shade and quick settings on Android. */
    @SerialName("system_panels")
    SYSTEM_PANELS,
}

@Serializable
enum class HostPlatform {
    @SerialName("android")
    ANDROID,

    @SerialName("windows")
    WINDOWS,
}

object PermissionPolicy {
    fun capabilitiesFor(level: PermissionLevel): Set<Capability> = when (level) {
        PermissionLevel.VIEW_ONLY -> setOf(Capability.VIEW_SCREEN)
        PermissionLevel.GUIDED_SUPPORT -> setOf(Capability.VIEW_SCREEN, Capability.POINTER)
        PermissionLevel.EXTENDED_SUPPORT -> setOf(
            Capability.VIEW_SCREEN,
            Capability.POINTER,
            Capability.NAVIGATION,
            Capability.TEXT_INPUT,
        )
        PermissionLevel.MAXIMUM_SUPPORTED -> Capability.entries.toSet()
    }

    /**
     * What the host will actually honour: the granted level, limited to what the device
     * can do right now (for example, whether the accessibility service is enabled), and
     * reduced to view-only while sharing is paused.
     */
    fun effective(granted: PermissionLevel, deviceSupported: Set<Capability>, paused: Boolean): Set<Capability> {
        if (paused) return emptySet()
        return capabilitiesFor(granted).intersect(deviceSupported + Capability.VIEW_SCREEN)
    }
}
