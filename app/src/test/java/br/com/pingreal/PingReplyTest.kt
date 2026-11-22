package br.com.pingreal

import org.junit.Assert.assertEquals
import org.junit.Test

class PingReplyTest {
    @Test fun readsTheSizeThePhonePingReports() {
        val r = PingSession.parseReply("64 bytes from 8.8.8.8: icmp_seq=3 ttl=117 time=12.4 ms")!!
        assertEquals(64, r.bytes)
        assertEquals(3, r.seq)
    }

    @Test fun looseFormatAlsoKeepsTheSize() {
        val r = PingSession.parseReply("72 bytes from 8.8.8.8: foo ttl=117 bar time=9 ms")!!
        assertEquals(72, r.bytes)
    }
}
