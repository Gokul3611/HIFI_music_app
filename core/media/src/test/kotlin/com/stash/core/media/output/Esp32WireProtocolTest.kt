package com.stash.core.media.output

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class Esp32WireProtocolTest {
    @Test fun `control commands use the firmware wire format`() {
        assertThat(Esp32WireProtocol.modeCommand(Esp32SocketStreamer.DacMode.INTERNAL_DAC.code))
            .isEqualTo("MODE:2")
        assertThat(Esp32WireProtocol.sampleRateCommand(44_100)).isEqualTo("RATE:44100")
    }

    @Test fun `mode acknowledgement must exactly match the requested mode`() {
        assertThat(Esp32WireProtocol.isModeAcknowledgement("MODE_ACK:1", 1)).isTrue()
        assertThat(Esp32WireProtocol.isModeAcknowledgement("MODE_NACK:1", 1)).isFalse()
        assertThat(Esp32WireProtocol.isModeAcknowledgement("MODE_ACK:10", 1)).isFalse()
    }

    @Test fun `sample rate acknowledgement must exactly match the requested rate`() {
        assertThat(Esp32WireProtocol.isSampleRateAcknowledgement("RATE_ACK:44100", 44_100)).isTrue()
        assertThat(Esp32WireProtocol.isSampleRateAcknowledgement("RATE_NACK:44100", 44_100)).isFalse()
        assertThat(Esp32WireProtocol.isSampleRateAcknowledgement("RATE_ACK:48000", 44_100)).isFalse()
    }

    @Test fun `discovery accepts a valid advertised DAC mode only`() {
        assertThat(Esp32WireProtocol.parseDiscovery("HIFI_DAC_HERE MODE:2"))
            .isEqualTo(Esp32SocketStreamer.DacMode.INTERNAL_DAC)
        assertThat(Esp32WireProtocol.parseDiscovery("HIFI_DAC_HERE MODE:10")).isNull()
        assertThat(Esp32WireProtocol.parseDiscovery("OTHER MODE:1")).isNull()
    }

    @Test fun `TCP greeting accepts a valid advertised DAC mode only`() {
        assertThat(Esp32WireProtocol.parseGreeting("HIFI_CONNECTED MODE:1"))
            .isEqualTo(Esp32SocketStreamer.DacMode.PCM5102A)
        assertThat(Esp32WireProtocol.parseGreeting("HIFI_CONNECTED MODE:10")).isNull()
        assertThat(Esp32WireProtocol.parseGreeting("WELCOME MODE:1")).isNull()
    }

    @Test fun `status parses mode rate signal and ring fill`() {
        val status = Esp32WireProtocol.parseStatus(
            "HIFI_STATUS:MODE:2,RATE:44100,RSSI:-61,BUFFER:37%",
        )

        assertThat(status).isEqualTo(
            Esp32WireProtocol.DeviceStatus(
                mode = Esp32SocketStreamer.DacMode.INTERNAL_DAC,
                sampleRateHz = 44_100,
                rssiDbm = -61,
                bufferPct = "37%",
            ),
        )
    }

    @Test fun `status rejects malformed values instead of marking hardware verified`() {
        assertThat(Esp32WireProtocol.parseStatus("HIFI_STATUS:MODE:10,RATE:48000,RSSI:-50,BUFFER:0%"))
            .isNull()
        assertThat(Esp32WireProtocol.parseStatus("HIFI_STATUS:MODE:1,RATE:48000,RSSI:-50,BUFFER:101%"))
            .isNull()
        assertThat(Esp32WireProtocol.parseStatus("HIFI_STATUS:MODE:1,RATE:bad,RSSI:-50,BUFFER:0%"))
            .isNull()
        assertThat(Esp32WireProtocol.parseStatus("HIFI_STATUS:MODE:1,RATE:48000,RSSI:bad,BUFFER:0%"))
            .isNull()
        assertThat(Esp32WireProtocol.parseStatus("HIFI_DAC_HERE MODE:1")).isNull()
    }
}
