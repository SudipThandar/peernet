package com.peernet.wifiextender

import com.peernet.wifiextender.service.ForegroundServiceType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Regression guard for the crash loop that made the client unusable: passing
 * `FOREGROUND_SERVICE_TYPE_SPECIAL_USE` on a platform that predates it makes
 * `startForeground()` throw IllegalArgumentException, and the sticky service
 * then crash-restarts forever (the tunnel's packet counters visibly reset).
 *
 * The expected value is written as a literal on purpose — comparing the
 * constant against itself would pass no matter what the code does.
 */
class ForegroundServiceTypeTest {

    private val specialUse = 1 shl 30

    @Test
    fun `no type is passed below api 34`() {
        // minSdk 26 through Android 13: manifest "specialUse" is unknown there.
        for (sdk in listOf(26, 28, 29, 30, 31, 32, 33)) {
            assertNull("API $sdk must not receive a type", ForegroundServiceType.forSdk(sdk))
        }
    }

    @Test
    fun `special use is passed from api 34`() {
        assertEquals(specialUse, ForegroundServiceType.forSdk(34))
        assertEquals(specialUse, ForegroundServiceType.forSdk(35))
        assertEquals(specialUse, ForegroundServiceType.forSdk(36))
    }
}
