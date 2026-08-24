package com.peernet.wifiextender

import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.peernet.wifiextender.core.RustCoreBridge
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.FileDescriptor

/**
 * Runs the native capture loop on a real Android runtime.
 *
 * The defect this gates: `AsyncFd::try_io` reports the closure's `EAGAIN` as
 * `Err(TryIoError)` (readiness already cleared), not `Ok(Err(WouldBlock))`.
 * Treating that as fatal killed the capture one poll after the first packet, so
 * every later DNS retry and TCP SYN sat unread in the TUN queue — on the device
 * this looked exactly like "connected, but no internet".
 *
 * A socketpair stands in for the TUN: same non-blocking fd semantics, same
 * epoll behaviour, no VPN consent needed.
 */
@RunWith(AndroidJUnit4::class)
class TunCaptureOnDeviceTest {

    /** The same wrapper the app uses; no Hilt needed, it has no dependencies. */
    private val engine = RustCoreBridge()

    private var writeEnd: FileDescriptor? = null

    @Before
    fun clearAnyPreviousCapture() {
        assertTrue(
            "native engine did not load on this device/ABI",
            engine.isAvailable
        )
        engine.stopTunCapture()
    }

    @After
    fun stopCapture() {
        engine.stopTunCapture()
        writeEnd?.let { fd -> runCatching { Os.close(fd) } }
    }

    @Test
    fun capture_keeps_reading_across_idle_gaps() {
        val ours = FileDescriptor()
        val engines = FileDescriptor()
        Os.socketpair(OsConstants.AF_UNIX, OsConstants.SOCK_SEQPACKET, 0, ours, engines)
        writeEnd = ours

        // The engine takes ownership of its end.
        val handOff = ParcelFileDescriptor.dup(engines).detachFd()
        Os.close(engines)
        assertTrue("engine refused the capture fd", engine.startTunCapture(handOff, MTU))

        // Idle gaps between packets are the whole point: each gap produces the
        // EAGAIN that used to end the loop.
        repeat(PACKETS) { i ->
            val packet = udpPacket(sourcePort = 40000 + i)
            Os.write(ours, packet, 0, packet.size)
            Thread.sleep(300)
        }

        val counted = waitForPacketCount(PACKETS.toLong())
        assertEquals(
            "capture stopped early: the loop died on an idle read instead of " +
                "waiting for the next packet",
            PACKETS.toLong(),
            counted
        )
        assertTrue(
            "capture reported itself dead: ${engine.engineStats()}",
            engine.engineStats().contains("cap=up")
        )
    }

    private fun waitForPacketCount(expected: Long, timeoutMs: Long = 5_000): Long {
        val deadline = System.currentTimeMillis() + timeoutMs
        var seen = engine.tunPacketCount()
        while (seen < expected && System.currentTimeMillis() < deadline) {
            Thread.sleep(100)
            seen = engine.tunPacketCount()
        }
        return seen
    }

    /** Well-formed IPv4/UDP query toward the virtual DNS address. */
    private fun udpPacket(sourcePort: Int): ByteArray {
        val payload = byteArrayOf(0x12, 0x34, 0x01, 0x00, 0x00, 0x01)
        val total = 20 + 8 + payload.size
        val p = ByteArray(total)
        p[0] = 0x45
        p[2] = (total shr 8).toByte()
        p[3] = (total and 0xFF).toByte()
        p[8] = 64          // TTL
        p[9] = 17          // UDP
        // 10.215.17.2 -> 10.215.17.1 (client TUN address -> virtual DNS)
        p[12] = 10; p[13] = (215).toByte(); p[14] = 17; p[15] = 2
        p[16] = 10; p[17] = (215).toByte(); p[18] = 17; p[19] = 1
        p[20] = (sourcePort shr 8).toByte()
        p[21] = (sourcePort and 0xFF).toByte()
        p[22] = 0; p[23] = 53
        val udpLen = 8 + payload.size
        p[24] = (udpLen shr 8).toByte()
        p[25] = (udpLen and 0xFF).toByte()
        payload.copyInto(p, 28)
        return p
    }

    private companion object {
        const val MTU = 1280
        const val PACKETS = 3
    }
}
