package com.peernet.wifiextender

import com.peernet.wifiextender.client.LinkProbePolicy
import com.peernet.wifiextender.client.ProbeFailureKind
import com.peernet.wifiextender.client.ProbePhase
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The client must not name a cause it cannot distinguish.
 *
 * The probe previously used three catch blocks. Every `ConnectException` was
 * reported as "refused the connection - is SHARE on, on the host phone?", but on
 * Android that single exception type carries both `ECONNREFUSED` (host reachable,
 * not sharing) and `ENETUNREACH` (this phone has no route at all). Those are
 * different phones and different fixes, and the only thing separating them -
 * `e.message` - was thrown away.
 *
 * "TCP connection refused" is the symptom that drove the whole investigation, so
 * a classifier that guesses here corrupts every conclusion downstream.
 */
class LinkProbePolicyTest {

    /** The shape Android actually produces, errno text and all. */
    private fun androidConnectError(errno: String, desc: String) = ConnectException(
        "failed to connect to /192.168.49.1 (port 4434) from /192.168.49.37 " +
            "(port 41288) after 3000ms: isConnected failed: $errno ($desc)"
    )

    @Test
    fun `a refusal and an unreachable network are not reported the same way`() {
        val refused = LinkProbePolicy.classify(
            ProbePhase.CONNECTING,
            androidConnectError("ECONNREFUSED", "Connection refused")
        )
        val noRoute = LinkProbePolicy.classify(
            ProbePhase.CONNECTING,
            androidConnectError("ENETUNREACH", "Network is unreachable")
        )

        assertEquals(ProbeFailureKind.REFUSED, refused.kind)
        assertEquals(ProbeFailureKind.NETWORK_UNREACHABLE, noRoute.kind)
        // Both are ConnectException. Before this policy both produced this exact
        // same sentence blaming the host.
        assertNotEquals(refused.message, noRoute.message)
    }

    @Test
    fun `a refusal blames the host, an unreachable network does not`() {
        val refused = LinkProbePolicy.classify(
            ProbePhase.CONNECTING,
            androidConnectError("ECONNREFUSED", "Connection refused")
        )
        assertTrue(refused.message.contains("not sharing"))

        val noRoute = LinkProbePolicy.classify(
            ProbePhase.CONNECTING,
            androidConnectError("ENETUNREACH", "Network is unreachable")
        )
        assertFalse(noRoute.message.contains("not sharing"))
        assertTrue(noRoute.message.contains("route"))
    }

    @Test
    fun `a banner timeout means the network worked and the host responder did not`() {
        val d = LinkProbePolicy.classify(
            ProbePhase.READING_BANNER,
            SocketTimeoutException("Read timed out")
        )
        assertEquals(ProbeFailureKind.BANNER_TIMEOUT, d.kind)
        assertTrue(d.tcpEstablished)
        // The old code called this "wrong network", which is the opposite of true:
        // TCP connected, so the network is fine and the host accepted.
        assertFalse(d.message.contains("wrong network"))
        assertTrue(d.message.contains("connected"))
    }

    @Test
    fun `a connect timeout means we never reached the host`() {
        val d = LinkProbePolicy.classify(
            ProbePhase.CONNECTING,
            SocketTimeoutException("failed to connect to /192.168.49.1 (port 4434) after 3000ms")
        )
        assertEquals(ProbeFailureKind.CONNECT_TIMEOUT, d.kind)
        assertFalse(d.tcpEstablished)
    }

    @Test
    fun `the same exception type is classified by phase`() {
        val e = SocketTimeoutException("timed out")
        assertNotEquals(
            LinkProbePolicy.classify(ProbePhase.CONNECTING, e).kind,
            LinkProbePolicy.classify(ProbePhase.READING_BANNER, e).kind
        )
    }

    @Test
    fun `no route to host is separated from an unreachable network`() {
        val d = LinkProbePolicy.classify(
            ProbePhase.CONNECTING,
            NoRouteToHostException("Host unreachable")
        )
        assertEquals(ProbeFailureKind.HOST_UNREACHABLE, d.kind)
    }

    @Test
    fun `a reset means the host accepted and then hung up`() {
        val d = LinkProbePolicy.classify(
            ProbePhase.READING_BANNER,
            SocketException("Connection reset by peer: ECONNRESET (Connection reset by peer)")
        )
        assertEquals(ProbeFailureKind.CLOSED_EARLY, d.kind)
        assertTrue(d.tcpEstablished)
    }

    @Test
    fun `a blocked socket is not called a refusal`() {
        val d = LinkProbePolicy.classify(
            ProbePhase.CONNECTING,
            ConnectException("socket failed: EACCES (Permission denied)")
        )
        assertEquals(ProbeFailureKind.PERMISSION_DENIED, d.kind)
    }

    @Test
    fun `a bare ConnectException is not assumed to be a refusal`() {
        // This assumption is the defect. Without errno text there is no evidence
        // of a refusal, so it must not claim one.
        val d = LinkProbePolicy.classify(ProbePhase.CONNECTING, ConnectException(null))
        assertEquals(ProbeFailureKind.OTHER, d.kind)
        assertFalse(d.message.contains("not sharing"))
    }

    @Test
    fun `the errno text always survives into the diagnostics detail`() {
        val d = LinkProbePolicy.classify(
            ProbePhase.CONNECTING,
            androidConnectError("ENETUNREACH", "Network is unreachable")
        )
        assertTrue(d.detail.contains("ENETUNREACH"))
        assertTrue(d.detail.contains("ConnectException"))
        assertTrue(d.detail.contains("CONNECTING"))
    }

    @Test
    fun `an unknown failure reports what the OS said instead of inventing a cause`() {
        val d = LinkProbePolicy.classify(
            ProbePhase.CONNECTING,
            SocketException("ETIMEDOUT weird vendor text")
        )
        assertEquals(ProbeFailureKind.OTHER, d.kind)
        assertTrue(d.message.contains("SocketException"))
        assertTrue(d.detail.contains("ETIMEDOUT weird vendor text"))
    }

    @Test
    fun `every kind produces a non-empty distinct sentence`() {
        val samples = mapOf(
            ProbeFailureKind.REFUSED to androidConnectError("ECONNREFUSED", "Connection refused"),
            ProbeFailureKind.NETWORK_UNREACHABLE to androidConnectError("ENETUNREACH", "Network is unreachable"),
            ProbeFailureKind.HOST_UNREACHABLE to androidConnectError("EHOSTUNREACH", "No route to host"),
            ProbeFailureKind.PERMISSION_DENIED to ConnectException("socket failed: EACCES (Permission denied)"),
            ProbeFailureKind.CONNECT_TIMEOUT to SocketTimeoutException("connect timed out"),
            ProbeFailureKind.CLOSED_EARLY to SocketException("ECONNRESET (Connection reset by peer)"),
            ProbeFailureKind.OTHER to SocketException("vendor gibberish")
        )
        val messages = samples.map { (expected, e) ->
            val d = LinkProbePolicy.classify(ProbePhase.CONNECTING, e)
            assertEquals("wrong kind for ${e.message}", expected, d.kind)
            assertTrue(d.message.isNotBlank())
            d.message
        }
        assertEquals(messages.size, messages.distinct().size)
    }
}
