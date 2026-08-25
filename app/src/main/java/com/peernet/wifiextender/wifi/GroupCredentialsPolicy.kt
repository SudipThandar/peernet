package com.peernet.wifiextender.wifi

import java.security.MessageDigest

/**
 * The single source of truth for the Wi-Fi Direct group's name and passphrase.
 *
 * These rules used to exist as string literals in **two** places -
 * `HostRuntime` built `"DIRECT-PeerNet-$shortId"` / `"pn-$hostId"`, and
 * `ClientViewModel` rebuilt the same two strings to auto-join. Nothing tied them
 * together, so editing either one would have silently broken auto-join with no
 * failing test and no visible symptom beyond "it asks for the password again".
 * They live here now so host and client cannot drift apart.
 *
 * ## Why the default passphrase is derived rather than random
 *
 * A client cannot ask for a passphrase before it has joined the network - the
 * banner server and NSD are both *inside* the group. So the only passphrase a
 * client can present without the user typing anything is one it can compute
 * itself, from the host id it already learned during discovery.
 *
 * The trade-off is explicit: the default group is joinable by anyone nearby who
 * knows this scheme. That is not a change in exposure - the previous `"pn-$hostId"`
 * default had exactly the same property - and the tunnel itself is still
 * authenticated by certificate fingerprint over TLS 1.3, so joining the group
 * does not grant access to anyone's traffic. A host who wants the group itself
 * to be private sets a custom passphrase, which by definition cannot be derived
 * and therefore has to be typed once on the client.
 */
object GroupCredentialsPolicy {

    /** WPA2 passphrase bounds. Shorter is rejected by the framework outright. */
    const val MIN_LENGTH = 8
    const val MAX_LENGTH = 63

    /** Long enough to not be guessable by hand, short enough to type. */
    const val DERIVED_LENGTH = 10

    /**
     * Deliberately excludes `0/o/O`, `1/l/I` and `i`.
     *
     * This string is read off one phone's screen and typed into another's Wi-Fi
     * prompt, so a character the user cannot distinguish is a failed connection
     * that looks like a bug in the app.
     */
    private const val ALPHABET = "23456789abcdefghjkmnpqrstuvwxyz"

    /** Wi-Fi Direct requires the `DIRECT-` prefix; the framework rejects anything else. */
    fun networkName(hostId: String): String = "DIRECT-PeerNet-${hostId.takeLast(4)}"

    /**
     * The stable default passphrase for [hostId].
     *
     * Deterministic, so a client that discovered the host id can join without the
     * user typing anything, and unchanging across launches, so a client that
     * joined once is never asked again.
     */
    fun derivePassphrase(hostId: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("peernet-group-v1:$hostId".toByteArray(Charsets.UTF_8))
        val out = StringBuilder(DERIVED_LENGTH)
        for (i in 0 until DERIVED_LENGTH) {
            out.append(ALPHABET[(digest[i].toInt() and 0xFF) % ALPHABET.length])
        }
        return out.toString()
    }

    fun isValid(passphrase: String): Boolean = rejection(passphrase) == null

    /**
     * Why [passphrase] cannot be used, in words a user can act on, or null when
     * it is acceptable.
     *
     * The framework's own failure for a bad passphrase is an opaque integer
     * delivered asynchronously, long after the screen has moved on, so the
     * passphrase is checked before it is ever handed over.
     */
    fun rejection(passphrase: String): String? = when {
        passphrase.length < MIN_LENGTH ->
            "Password must be at least $MIN_LENGTH characters (Wi-Fi requirement)."
        passphrase.length > MAX_LENGTH ->
            "Password must be at most $MAX_LENGTH characters."
        passphrase.any { it.code < 0x20 || it.code > 0x7E } ->
            "Password can only use ordinary keyboard characters."
        else -> null
    }
}
