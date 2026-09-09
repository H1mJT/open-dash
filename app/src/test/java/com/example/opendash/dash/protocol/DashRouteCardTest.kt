package com.example.opendash.dash.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Test

class DashRouteCardTest {
    @Test
    fun routeCard_replacesBothTurnGlyphsAndPrimaryDistance() {
        val packet = DashCommands.routeCard(
            title = "Destination",
            maneuver = 0x06,
            secondaryManeuver = 0x02,
            primaryDist = 345,
            primaryUnit = DashCommands.NAV_UNIT_METERS,
        )

        assertArrayEquals(byteArrayOf(0x06), value(packet, 0x05, 0x02))
        assertArrayEquals(byteArrayOf(0x02), value(packet, 0x05, 0x03))
        assertArrayEquals(byteArrayOf(0x01, 0x59), value(packet, 0x05, 0x05))
    }

    private fun value(packet: ByteArray, type: Int, sub: Int): ByteArray {
        var offset = 17 // outgoing K1G fixed header plus sequence byte
        while (offset + 4 <= packet.size) {
            val currentType = packet[offset].toInt() and 0xFF
            val currentSub = packet[offset + 1].toInt() and 0xFF
            val length = ((packet[offset + 2].toInt() and 0xFF) shl 8) or
                (packet[offset + 3].toInt() and 0xFF)
            val valueStart = offset + 4
            if (currentType == type && currentSub == sub) {
                return packet.copyOfRange(valueStart, valueStart + length)
            }
            offset = valueStart + length
        }
        error("TLV %02X/%02X not found".format(type, sub))
    }
}
