package com.remodroid.shared

import com.remodroid.shared.rtc.ConnectionQuality
import com.remodroid.shared.rtc.StatEntry
import com.remodroid.shared.rtc.StatsParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class StatsParserTest {
    private fun report(bytes: Long, relay: Boolean) = listOf(
        StatEntry("T1", "transport", mapOf("selectedCandidatePairId" to "CP1")),
        StatEntry("CP1", "candidate-pair", mapOf("localCandidateId" to "L1", "remoteCandidateId" to "R1", "currentRoundTripTime" to 0.12)),
        StatEntry("L1", "local-candidate", mapOf("candidateType" to if (relay) "relay" else "srflx")),
        StatEntry("R1", "remote-candidate", mapOf("candidateType" to "host")),
        StatEntry(
            "IN1", "inbound-rtp",
            mapOf("kind" to "video", "bytesReceived" to bytes, "packetsLost" to 2L, "packetsReceived" to 98L,
                "framesPerSecond" to 29.0, "frameWidth" to 720L, "frameHeight" to 1600L),
        ),
    )

    @Test
    fun `parses rtt, relay, loss, resolution and bitrate`() {
        val parser = StatsParser()
        val first = parser.parse(report(0, relay = true), nowMs = 1000, sending = false)
        assertEquals(120.0, first.roundTripMs!!, 0.001)
        assertEquals(true, first.relayed)
        assertEquals(2.0, first.packetLossPercent!!, 0.001)
        assertEquals(720, first.frameWidth)
        assertNull(first.bitrateKbps)

        val second = parser.parse(report(250_000, relay = false), nowMs = 3000, sending = false)
        assertEquals(1000.0, second.bitrateKbps!!, 0.001) // 2,000,000 bits over 2 s
        assertEquals(false, second.relayed)
        assertEquals(ConnectionQuality.GOOD, ConnectionQuality.from(second))
    }

    @Test
    fun `quality degrades with latency and loss`() {
        val s = StatsParser().parse(report(0, false), 0, false)
        assertEquals(ConnectionQuality.FAIR, ConnectionQuality.from(s.copy(roundTripMs = 300.0)))
        assertEquals(ConnectionQuality.POOR, ConnectionQuality.from(s.copy(packetLossPercent = 10.0)))
        assertNull(ConnectionQuality.from(null))
    }
}
