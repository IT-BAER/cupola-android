package com.baer.cupola.tools.tle

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.nio.file.Files
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CliTest {
    private val now = Instant.parse("2026-10-18T12:00:00Z")
    private val errBuf = ByteArrayOutputStream()
    private val out = PrintStream(ByteArrayOutputStream(), true)
    private val err = PrintStream(errBuf, true)
    private val dir = Files.createTempDirectory("tle-cli").toFile()
    private val noNetwork = TleSource { error("no network") }

    private fun run(source: TleSource, vararg args: String) = Cli.run(args.toList(), source, now, out, err)
    private fun stderr() = errBuf.toString()

    @Test fun fetchWritesTleAndJson() {
        val code = run({ TleSource.Response(200, TleFixtures.lines()) }, "fetch", "--out", dir.path)
        assertEquals(stderr(), 0, code)
        assertEquals(TleFixtures.lines(), File(dir, "iss.tle").readText())
        assertEquals(
            "{\"epoch\": \"2026-10-17T12:00:00Z\", \"source\": \"${Cli.ISS_URL}\", \"fetched\": \"2026-10-18T12:00:00Z\", \"catalog\": 25544}\n",
            File(dir, "iss.json").readText(),
        )
    }

    @Test fun fetchRequestsTheIssUrl() {
        var requested = ""
        run({ requested = it; TleSource.Response(200, TleFixtures.lines()) }, "fetch", "--out", dir.path)
        assertEquals(Cli.ISS_URL, requested)
    }

    @Test fun noGpDataExitsTwoWithMissingMessage() {
        val code = run({ TleSource.Response(200, "No GP data found") }, "fetch", "--out", dir.path)
        assertEquals(2, code)
        assertTrue(stderr(), stderr().contains("ISS catalogue entry 25544 missing or decayed: No GP data found"))
        assertFalse(File(dir, "iss.tle").exists())
    }

    @Test fun emptyBodyAndHttp404ExitTwo() {
        assertEquals(2, run({ TleSource.Response(200, "") }, "fetch", "--out", dir.path))
        assertTrue(stderr(), stderr().contains("missing or decayed: HTTP 200"))
        assertEquals(2, run({ TleSource.Response(404, "Not Found") }, "fetch", "--out", dir.path))
    }

    @Test fun otherHttpErrorExitsOne() {
        assertEquals(1, run({ TleSource.Response(503, "busy") }, "fetch", "--out", dir.path))
    }

    @Test fun invalidFetchedTleExitsOneAndWritesNothing() {
        val code = run({ TleSource.Response(200, TleFixtures.lines(meanMotion = 15.9)) }, "fetch", "--out", dir.path)
        assertEquals(1, code)
        assertTrue(stderr(), stderr().startsWith("mean-motion:"))
        assertFalse(File(dir, "iss.tle").exists())
    }

    @Test fun validateReportsFailureAndExitsOne() {
        val file = File(dir, "bad.tle").apply { writeText(TleFixtures.lines(inclination = 52.0)) }
        assertEquals(1, run(noNetwork, "validate", "--tle", file.path, "--now", "2026-10-18T12:00:00Z"))
        assertTrue(stderr(), stderr().startsWith("inclination:"))
    }

    @Test fun validateHonoursNow() {
        val file = File(dir, "ok.tle").apply { writeText(TleFixtures.lines()) }
        assertEquals(0, run(noNetwork, "validate", "--tle", file.path, "--now", "2026-10-18T12:00:00Z"))
        assertEquals(1, run(noNetwork, "validate", "--tle", file.path, "--now", "2026-11-18T12:00:00Z"))
    }

    @Test fun guardThirtyDaysFailsTwoDaysPasses() {
        val json = File(dir, "iss.json").apply { writeText("{\"epoch\": \"2026-10-17T12:00:00Z\", \"catalog\": 25544}") }
        assertEquals(1, run(noNetwork, "guard", "--json", json.path, "--now", "2026-11-16T12:00:00Z"))
        assertTrue(stderr(), stderr().contains("30.0 days old"))
        assertEquals(0, run(noNetwork, "guard", "--json", json.path, "--now", "2026-10-19T12:00:00Z"))
    }

    @Test fun missingFlagExitsOne() {
        assertEquals(1, run(noNetwork, "guard", "--json", "x"))
        assertTrue(stderr(), stderr().contains("missing --now"))
    }
}
