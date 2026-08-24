package com.peernet.wifiextender

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Guards against a defect that reached a tester's phone.
 *
 * Writing Kotlin sources with a non-UTF-8 encoding replaced every em-dash and
 * ellipsis with U+FFFD, so on-screen status strings rendered as
 * "Searching this network for a PeerNet host?" with a garbage character. The
 * code still compiles, lint stays silent, unit tests pass, and only a human
 * looking at the screen notices. This test turns that into a build failure.
 *
 * Reading with UTF-8 also catches genuinely malformed bytes, because the
 * decoder substitutes U+FFFD for every invalid sequence.
 */
class SourceEncodingTest {

    @Test
    fun sources_are_valid_utf8_without_replacement_characters() {
        val roots = listOf(
            File("src/main/java"),
            File("src/test/java"),
            File("src/androidTest/java")
        ).filter { it.isDirectory }

        assertTrue(
            "No source roots found from ${File(".").absolutePath} — this test would pass vacuously",
            roots.isNotEmpty()
        )

        val sources = roots
            .flatMap { root -> root.walkTopDown().filter { it.isFile && it.extension == "kt" } }
            .toList()

        assertTrue(
            "Only ${sources.size} Kotlin sources scanned — the walk is broken",
            sources.size > 10
        )

        val offenders = sources
            .filter { it.readText().contains('\uFFFD') }
            .map { it.path }

        assertTrue(
            "These sources contain U+FFFD, i.e. they were written with the wrong " +
                "encoding (rewrite them as UTF-8): $offenders",
            offenders.isEmpty()
        )
    }
}
