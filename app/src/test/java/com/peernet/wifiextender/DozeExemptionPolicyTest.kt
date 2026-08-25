package com.peernet.wifiextender

import com.peernet.wifiextender.power.DozeExemptionPolicy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Gates for the one-time Doze exemption prompt.
 *
 * Context: with `PowerManager` wake locks ruled out by design, a user-granted
 * battery-optimization exemption is the only remaining lever against screen-off
 * stalls. It is also the only system dialog in an app whose whole UX rule is
 * "one screen, two buttons, no settings" - so the conditions on showing it carry
 * real weight and are gated here.
 */
class DozeExemptionPolicyTest {

    @Test
    fun `prompts once when a session is running and the exemption is missing`() {
        assertTrue(
            "this is the only case that should ever show a system dialog",
            DozeExemptionPolicy.shouldPrompt(
                sessionActive = true,
                alreadyExempt = false,
                alreadyAsked = false
            )
        )
    }

    @Test
    fun `never prompts before a session is running`() {
        // A dialog on a cold start would be the first thing a new user sees, with
        // no context for why the app wants it - and there is nothing to protect
        // yet anyway.
        assertFalse(
            "the prompt must always have a running session as its context",
            DozeExemptionPolicy.shouldPrompt(
                sessionActive = false,
                alreadyExempt = false,
                alreadyAsked = false
            )
        )
    }

    @Test
    fun `never prompts when already exempt`() {
        assertFalse(
            "asking for a permission the app already holds is pure noise",
            DozeExemptionPolicy.shouldPrompt(
                sessionActive = true,
                alreadyExempt = true,
                alreadyAsked = false
            )
        )
    }

    @Test
    fun `never asks a second time`() {
        // A user who declined has answered. Re-asking every session would turn a
        // two-button app into a nag screen, and the mandate here is no settings
        // and no ceremony.
        assertFalse(
            "declining once must be respected for good",
            DozeExemptionPolicy.shouldPrompt(
                sessionActive = true,
                alreadyExempt = false,
                alreadyAsked = true
            )
        )
    }

    @Test
    fun `an exempt user who was asked before is still never prompted`() {
        assertFalse(
            DozeExemptionPolicy.shouldPrompt(
                sessionActive = true,
                alreadyExempt = true,
                alreadyAsked = true
            )
        )
    }
}
