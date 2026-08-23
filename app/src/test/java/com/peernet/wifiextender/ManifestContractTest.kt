package com.peernet.wifiextender

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The manifest carries contracts the compiler cannot check and that fail only
 * on a real device — silently. Both of the defects that made this app deliver
 * no internet at all were of that shape, so they get a gate here.
 *
 * Notably: a VpnService without the `android.net.VpnService` intent filter is
 * accepted by the build, shows the consent dialog, and then `establish()`
 * returns null forever.
 */
class ManifestContractTest {

    private val manifest: String by lazy { readManifest() }

    @Test
    fun `vpn service is bindable by the platform`() {
        val service = serviceBlock(".service.PeerNetVpnService")

        assertTrue(
            "VpnService must declare the android.net.VpnService intent filter, " +
                "otherwise Builder.establish() returns null on every device.\n$service",
            service.contains("android.net.VpnService")
        )
        assertTrue(
            "A service with an intent filter the system must bind has to be exported " +
                "(BIND_VPN_SERVICE still restricts it to the platform).\n$service",
            service.contains("android:exported=\"true\"")
        )
        assertTrue(
            "VpnService must be guarded by BIND_VPN_SERVICE.\n$service",
            service.contains("android.permission.BIND_VPN_SERVICE")
        )
    }

    @Test
    fun `foreground services declare a type`() {
        // Android 14+ throws MissingForegroundServiceTypeException without it.
        for (name in listOf(".service.PeerNetVpnService", ".service.HostForegroundService")) {
            val service = serviceBlock(name)
            assertTrue(
                "$name must declare android:foregroundServiceType.\n$service",
                service.contains("android:foregroundServiceType")
            )
        }
    }

    @Test
    fun `permissions the runtime flow depends on are declared`() {
        for (permission in listOf(
            "android.permission.ACCESS_FINE_LOCATION",
            "android.permission.ACCESS_WIFI_STATE",
            "android.permission.CHANGE_WIFI_STATE",
            "android.permission.INTERNET",
            "android.permission.FOREGROUND_SERVICE",
            "android.permission.POST_NOTIFICATIONS"
        )) {
            assertTrue("missing <uses-permission> $permission", manifest.contains(permission))
        }
    }

    /** The `<service …>…</service>` (or self-closing) declaration for [name]. */
    private fun serviceBlock(name: String): String {
        val start = manifest.indexOf("android:name=\"$name\"")
        assertTrue("no <service> declaration for $name", start > 0)
        val open = manifest.lastIndexOf("<service", start)
        val selfClose = manifest.indexOf("/>", start)
        val blockClose = manifest.indexOf("</service>", start)
        val end = when {
            blockClose < 0 -> selfClose + 2
            selfClose in 0 until blockClose -> selfClose + 2
            else -> blockClose + "</service>".length
        }
        return manifest.substring(open, end)
    }

    /**
     * Unit tests run with the module directory as working dir, but never trust
     * that: walk up until the manifest appears, and fail loudly rather than
     * pass vacuously if it does not.
     */
    private fun readManifest(): String {
        var dir: File? = File(".").absoluteFile
        repeat(5) {
            val candidates = listOf(
                File(dir, "src/main/AndroidManifest.xml"),
                File(dir, "app/src/main/AndroidManifest.xml")
            )
            candidates.firstOrNull { it.isFile }?.let { return it.readText() }
            dir = dir?.parentFile
        }
        throw AssertionError(
            "AndroidManifest.xml not found from ${File(".").absolutePath}; " +
                "this gate must never pass without reading it"
        )
    }
}
