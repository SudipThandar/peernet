package com.peernet.wifiextender

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Gates the two power rules this project is not allowed to break, by reading the
 * manifest and sources as text.
 *
 * These are standing design decisions, not preferences:
 *
 * 1. **No `PowerManager` wake lock, ever.** Holding the CPU awake to keep a
 *    tunnel alive is the wrong fix - it converts a networking bug into a battery
 *    complaint and a one-star review. What actually keeps the link serviceable is
 *    `WifiManager.WifiLock` in `WIFI_MODE_FULL_HIGH_PERF` (see `WifiLockPolicy`),
 *    which needs no permission, plus a user-granted Doze exemption.
 * 2. **Never keep the screen awake.** Same reasoning, more visible to the user.
 *
 * Both are the kind of rule that gets quietly violated months later by someone
 * debugging a screen-off report at 2am and reaching for the obvious tool. A
 * comment cannot stop that; a red build can.
 *
 * `PowerManager` itself is still legitimate: `DozeExemptionPolicy` reads
 * `isIgnoringBatteryOptimizations`, which requires no permission and acquires
 * nothing. So these tests target the *acquisition* APIs, not the class.
 *
 * Implemented as text assertions because this project has no Robolectric, so no
 * test can inspect a merged manifest or a real `PowerManager`.
 */
class PowerPolicyManifestTest {

    private fun moduleDir(): File {
        // Gradle runs unit tests with the module directory as the working
        // directory, but walk up as a fallback so the test cannot silently pass
        // by failing to find the file.
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            if (File(dir, "src/main/AndroidManifest.xml").isFile) return dir
            dir = dir.parentFile
        }
        throw AssertionError("could not locate the app module from ${File(".").absolutePath}")
    }

    private fun manifestText(): String =
        File(moduleDir(), "src/main/AndroidManifest.xml").readText()

    private fun mainSources(): List<File> =
        File(moduleDir(), "src/main/java")
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .toList()

    @Test
    fun `manifest does not declare WAKE_LOCK`() {
        val manifest = manifestText()
        // Guard the guard: if the read produced nothing useful, fail loudly
        // rather than pass vacuously.
        assertTrue(
            "manifest looks empty - the test is not reading what it thinks it is",
            manifest.contains("android.permission.INTERNET")
        )
        assertFalse(
            "WAKE_LOCK must not be declared: PowerManager wake locks are ruled " +
                "out by design, and an unused permission is also a Play Store " +
                "review question with no good answer",
            manifest.contains("android.permission.WAKE_LOCK")
        )
    }

    @Test
    fun `manifest declares no unused device admin feature`() {
        // Declared once, never used, and it makes the app look like it wants
        // device-administrator powers it has no code for.
        assertFalse(
            "android.software.device_admin is unused and must stay out of the manifest",
            manifestText().contains("android.software.device_admin")
        )
    }

    @Test
    fun `no source acquires a PowerManager wake lock`() {
        val offenders = mainSources().filter { file ->
            val text = file.readText()
            text.contains("newWakeLock") ||
                text.contains("PARTIAL_WAKE_LOCK") ||
                text.contains("SCREEN_BRIGHT_WAKE_LOCK") ||
                text.contains("FULL_WAKE_LOCK") ||
                text.contains("PowerManager.WakeLock")
        }
        assertTrue(
            "wake lock acquisition is forbidden; found in: " +
                offenders.joinToString { it.name },
            offenders.isEmpty()
        )
    }

    @Test
    fun `no source keeps the screen awake`() {
        val offenders = mainSources().filter { file ->
            val text = file.readText()
            text.contains("FLAG_KEEP_SCREEN_ON") || text.contains("keepScreenOn")
        }
        assertTrue(
            "keeping the screen awake is forbidden; found in: " +
                offenders.joinToString { it.name },
            offenders.isEmpty()
        )
    }

    @Test
    fun `the wifi lock the tunnel actually relies on is still present`() {
        // The counterpart to the bans above: if the WifiLock ever disappears,
        // these tests would otherwise keep passing while the tunnel lost its
        // only legitimate protection.
        val holders = mainSources().filter { it.readText().contains("createWifiLock") }
        assertTrue(
            "expected WifiManager.createWifiLock in the host and client paths, found " +
                holders.size,
            holders.size >= 2
        )
    }
}
