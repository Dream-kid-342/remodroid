package com.remodroid.shared.rtc

/** Minimal, library-neutral form of a W3C RTCStats entry. */
data class StatEntry(val id: String, val type: String, val members: Map<String, Any?>)

/**
 * Turns a WebRTC stats report into [RtcStats]. Both org.webrtc and webrtc-java expose the standard
 * W3C stats dictionaries, so one parser serves both. Bitrate needs the previous sample.
 */
class StatsParser {
    private var lastBytes: Long? = null
    private var lastTimestampMs: Long? = null

    fun parse(entries: List<StatEntry>, nowMs: Long, sending: Boolean): RtcStats {
        val byId = entries.associateBy { it.id }
        val transport = entries.firstOrNull { it.type == "transport" }
        val pairId = transport?.members?.get("selectedCandidatePairId") as? String
        val pair = pairId?.let(byId::get)
            ?: entries.firstOrNull { it.type == "candidate-pair" && it.members["nominated"] == true && it.members["state"] == "succeeded" }

        val rttSeconds = (pair?.members?.get("currentRoundTripTime") as? Number)?.toDouble()
        val relayed = pair?.let { p ->
            val local = (p.members["localCandidateId"] as? String)?.let(byId::get)
            val remote = (p.members["remoteCandidateId"] as? String)?.let(byId::get)
            if (local == null && remote == null) null
            else local?.members?.get("candidateType") == "relay" || remote?.members?.get("candidateType") == "relay"
        }

        val rtpType = if (sending) "outbound-rtp" else "inbound-rtp"
        val rtp = entries.firstOrNull { it.type == rtpType && (it.members["kind"] ?: it.members["mediaType"]) == "video" }
        val bytes = (rtp?.members?.get(if (sending) "bytesSent" else "bytesReceived") as? Number)?.toLong()
        val fps = (rtp?.members?.get("framesPerSecond") as? Number)?.toDouble()
        val width = (rtp?.members?.get("frameWidth") as? Number)?.toInt()
        val height = (rtp?.members?.get("frameHeight") as? Number)?.toInt()

        val lossPercent = if (sending) {
            val remoteInbound = entries.firstOrNull { it.type == "remote-inbound-rtp" && (it.members["kind"] ?: "video") == "video" }
            (remoteInbound?.members?.get("fractionLost") as? Number)?.toDouble()?.times(100)
        } else {
            val lost = (rtp?.members?.get("packetsLost") as? Number)?.toDouble()
            val received = (rtp?.members?.get("packetsReceived") as? Number)?.toDouble()
            if (lost != null && received != null && lost + received > 0) lost / (lost + received) * 100 else null
        }

        var bitrate: Double? = null
        val prevBytes = lastBytes
        val prevTs = lastTimestampMs
        if (bytes != null && prevBytes != null && prevTs != null && nowMs > prevTs && bytes >= prevBytes) {
            bitrate = (bytes - prevBytes) * 8.0 / (nowMs - prevTs) // bits per ms == kbit/s
        }
        if (bytes != null) {
            lastBytes = bytes
            lastTimestampMs = nowMs
        }

        return RtcStats(
            roundTripMs = rttSeconds?.times(1000),
            packetLossPercent = lossPercent,
            bitrateKbps = bitrate,
            framesPerSecond = fps,
            frameWidth = width,
            frameHeight = height,
            relayed = relayed,
        )
    }
}
