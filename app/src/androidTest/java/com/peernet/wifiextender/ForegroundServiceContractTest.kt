package com.peernet.wifiextender

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.peernet.wifiextender.service.HostForegroundService
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Runs the real `startForeground` contract against the real platform.
 *
 * This exists because a purely-JVM test cannot catch it: passing
 * `FOREGROUND_SERVICE_TYPE_SPECIAL_USE` on API 29-33 throws
 * `IllegalArgumentException` ("not a subset of ... 0x0" — the value does not
 * exist before API 34), the sticky service then crash-restarts forever, and the
 * only visible symptom on the tester's phone was engine counters *resetting*.
 * Unit tests and `assembleDebug` were both green through all of it.
 */
@RunWith(AndroidJUnit4::class)
class ForegroundServiceContractTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private var scenario: ActivityScenario<MainActivity>? = null

    @After
    fun stopEverything() {
        context.stopService(Intent(context, HostForegroundService::class.java))
        scenario?.close()
    }

    @Test
    fun host_service_reaches_the_foreground_on_this_api_level() {
        // A resumed activity is required: API 31+ rejects foreground-service
        // starts from the background, which would fail the test for a reason
        // that has nothing to do with the contract under test.
        scenario = ActivityScenario.launch(MainActivity::class.java)

        ContextCompat.startForegroundService(
            context,
            Intent(context, HostForegroundService::class.java)
        )

        assertTrue(
            "HostForegroundService never reached the foreground on API " +
                "${Build.VERSION.SDK_INT}: startForeground was rejected, which on a " +
                "sticky service turns into an invisible crash loop.",
            awaitForegroundService(HostForegroundService::class.java.name)
        )
    }

    /**
     * Polls for our own service in the foreground state. `getRunningServices`
     * is limited to the caller's own services since API 26, which is exactly
     * what is needed here and requires no permission (unlike inspecting
     * notifications, which API 33+ can suppress).
     */
    private fun awaitForegroundService(className: String, timeoutMs: Long = 10_000): Boolean {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            @Suppress("DEPRECATION")
            val running = runCatching { am.getRunningServices(64) }.getOrDefault(emptyList())
            if (running.any { it.service.className == className && it.foreground }) return true
            Thread.sleep(200)
        }
        return false
    }
}
