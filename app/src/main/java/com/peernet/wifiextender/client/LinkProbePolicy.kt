package com.peernet.wifiextender.client

import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException

/** Where the probe was when it failed. The same exception means different things. */
enum class ProbePhase { CONNECTING, READING_BANNER }

enum class ProbeFailureKind {
    /** Address reachable, nothing accepting on the port. The host is not sharing. */
    REFUSED,

    /** No route off this interface at all. This phone is on the wrong network. */
    NETWORK_UNREACHABLE,

    /** Route exists, the host address does not answer ARP. */
    HOST_UNREACHABLE,

    /** The system blocked the socket. */
    PERMISSION_DENIED,

    /** The SYN went unanswered. Never reached the host. */
    CONNECT_TIMEOUT,

    /** TCP connected; the host never wrote its banner. The responder is wedged. */
    BANNER_TIMEOUT,

    /** TCP connected, then the host hung up without answering. */
    CLOSED_EARLY,

    /** Unrecognised. The raw error is carried through rather than guessed at. */
    OTHER
}

/**
 * @param message what the tester reads on the client screen
 * @param detail  what goes in the diagnostics buffer, errno text preserved
 * @param tcpEstablished whether TCP actually connected before this failed. This
 *   is the single most valuable bit: it decides whether the next thing to look at
 *   is the network or the host's responder.
 */
data class ProbeDiagnosis(
    val kind: ProbeFailureKind,
    val message: String,
    val detail: String,
    val tcpEstablished: Boolean
)

/**
 * Classifies why a link probe to the host's :4434 responder failed.
 *
 * This exists because the previous handling collapsed unrelated failures into
 * confident, wrong sentences. Every [ConnectException] was reported as "refused
 * the connection - is SHARE on, on the host phone?", but on Android that one
 * exception type carries `ECONNREFUSED` *and* `ENETUNREACH`: a client with no
 * route to the host - which is what a process-wide socket bind to the wrong
 * network produces - was told the host had stopped sharing. `e.message`, the only
 * thing that distinguishes them, was discarded.
 *
 * A read timeout was likewise reported as "wrong network", when it is the
 * opposite: TCP connected, so the network is fine and the host's responder
 * accepted and then never wrote. Those two point at different phones.
 *
 * The errno string is always carried into the diagnostics detail, so an
 * unrecognised failure degrades to "here is exactly what the OS said" instead of
 * a plausible fiction.
 */
object LinkProbePolicy {

    fun classify(phase: ProbePhase, e: Throwable): ProbeDiagnosis {
        val raw = e.message.orEmpty()
        val connected = phase == ProbePhase.READING_BANNER
        val kind = when {
            // Errno text is authoritative and present on Android for all of these.
            raw.contains("ECONNREFUSED") -> ProbeFailureKind.REFUSED
            raw.contains("ENETUNREACH") -> ProbeFailureKind.NETWORK_UNREACHABLE
            raw.contains("EHOSTUNREACH") -> ProbeFailureKind.HOST_UNREACHABLE
            raw.contains("EACCES") -> ProbeFailureKind.PERMISSION_DENIED
            raw.contains("ECONNRESET") || raw.contains("EPIPE") -> ProbeFailureKind.CLOSED_EARLY
            e is NoRouteToHostException -> ProbeFailureKind.HOST_UNREACHABLE
            e is SocketTimeoutException ->
                if (connected) ProbeFailureKind.BANNER_TIMEOUT else ProbeFailureKind.CONNECT_TIMEOUT
            // Message wording is a fallback for devices that do not include errno.
            raw.contains("refused", ignoreCase = true) -> ProbeFailureKind.REFUSED
            raw.contains("unreachable", ignoreCase = true) -> ProbeFailureKind.NETWORK_UNREACHABLE
            raw.contains("reset", ignoreCase = true) -> ProbeFailureKind.CLOSED_EARLY
            // A bare ConnectException is deliberately NOT called a refusal. That
            // assumption is what produced the misreport this class exists to end.
            else -> ProbeFailureKind.OTHER
        }
        return ProbeDiagnosis(
            kind = kind,
            message = message(kind, e, raw),
            detail = "$kind phase=$phase ${e.javaClass.simpleName}: ${raw.take(140)}",
            tcpEstablished = connected || kind == ProbeFailureKind.CLOSED_EARLY ||
                kind == ProbeFailureKind.BANNER_TIMEOUT
        )
    }

    private fun message(kind: ProbeFailureKind, e: Throwable, raw: String): String = when (kind) {
        ProbeFailureKind.REFUSED ->
            "the host phone refused the connection - it is reachable, but not sharing. " +
                "Tap SHARE on the host."
        ProbeFailureKind.NETWORK_UNREACHABLE ->
            "no route to the host - this phone is not on the host's network, " +
                "or another VPN captured the route."
        ProbeFailureKind.HOST_UNREACHABLE ->
            "on the network, but the host address does not answer. " +
                "The host may have recreated its group."
        ProbeFailureKind.PERMISSION_DENIED ->
            "the system blocked the connection (permission denied)."
        ProbeFailureKind.CONNECT_TIMEOUT ->
            "no answer within 3s - wrong network, or the host phone is asleep."
        ProbeFailureKind.BANNER_TIMEOUT ->
            "connected to the host, but it never identified itself within 3s. " +
                "The host's link responder is stuck, not missing."
        ProbeFailureKind.CLOSED_EARLY ->
            "connected to the host, then it hung up without answering. " +
                "The host is restarting its responder."
        ProbeFailureKind.OTHER ->
            "could not reach the host (${e.javaClass.simpleName}: ${raw.take(80)})"
    }
}
