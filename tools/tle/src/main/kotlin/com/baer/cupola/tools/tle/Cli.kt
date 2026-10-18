package com.baer.cupola.tools.tle

import com.baer.cupola.orbit.tle.Tle
import java.io.File
import java.io.PrintStream
import java.time.Instant

/** One HTTPS GET; the tests substitute fixtures so they never touch the network. */
fun interface TleSource {
    fun get(url: String): Response

    data class Response(val status: Int, val body: String)
}

object Cli {
    const val ISS_URL = "https://celestrak.org/NORAD/elements/gp.php?CATNR=25544&FORMAT=TLE"
    const val EXIT_OK = 0
    const val EXIT_FAILED = 1
    const val EXIT_MISSING = 2

    fun run(args: List<String>, source: TleSource, now: Instant, out: PrintStream, err: PrintStream): Int {
        val command = args.firstOrNull()
        return try {
            val flags = parseFlags(args.drop(1))
            when (command) {
                "fetch" -> fetch(flags.required("out"), source, now, out, err)
                "validate" -> validate(flags.required("tle"), flags["now"]?.let(Instant::parse) ?: now, out, err)
                "guard" -> guard(
                    flags.required("json"), Instant.parse(flags.required("now")),
                    flags["max-age-days"]?.toLong() ?: 7L, out, err,
                )
                else -> {
                    err.println("usage: fetch --out <dir> | validate --tle <file> [--now <ISO>] | guard --json <file> --now <ISO> [--max-age-days 7]")
                    EXIT_FAILED
                }
            }
        } catch (e: Exception) {
            err.println("${command ?: "tle"} failed: ${e.message ?: e}")
            EXIT_FAILED
        }
    }

    private fun fetch(outDir: String, source: TleSource, now: Instant, out: PrintStream, err: PrintStream): Int {
        val response = source.get(ISS_URL)
        val body = response.body.trim()
        if (response.status == 404 || body.isEmpty() || body.contains("No GP data found", ignoreCase = true)) {
            err.println("ISS catalogue entry ${Validator.ISS_CATALOG} missing or decayed: ${body.ifEmpty { "HTTP ${response.status}" }}")
            return EXIT_MISSING
        }
        if (response.status != 200) {
            err.println("CelesTrak answered HTTP ${response.status}: $body")
            return EXIT_FAILED
        }
        val failures = Validator.validate(body, now)
        if (failures.isNotEmpty()) {
            failures.forEach(err::println)
            return EXIT_FAILED
        }
        val (l1, l2) = checkNotNull(Validator.elementLines(body))
        val name = body.lines().map { it.trim() }.firstOrNull { it.isNotEmpty() && !it.startsWith("1 ") && !it.startsWith("2 ") } ?: "ISS (ZARYA)"
        val epoch = Tle.parse(l1, l2).epoch
        val dir = File(outDir).apply { mkdirs() }
        File(dir, "iss.tle").writeText("$name\n$l1\n$l2\n")
        File(dir, "iss.json").writeText(
            "{\"epoch\": \"$epoch\", \"source\": \"$ISS_URL\", \"fetched\": \"$now\", \"catalog\": ${Validator.ISS_CATALOG}}\n",
        )
        out.println("Wrote $outDir/iss.tle and iss.json, epoch $epoch")
        return EXIT_OK
    }

    private fun validate(tlePath: String, now: Instant, out: PrintStream, err: PrintStream): Int {
        val failures = Validator.validate(File(tlePath).readText(), now)
        if (failures.isEmpty()) {
            out.println("TLE valid: $tlePath")
            return EXIT_OK
        }
        failures.forEach(err::println)
        return EXIT_FAILED
    }

    private fun guard(jsonPath: String, now: Instant, maxAgeDays: Long, out: PrintStream, err: PrintStream): Int {
        val message = Guard.check(Guard.epochOf(File(jsonPath).readText()), now, maxAgeDays)
        if (message == null) {
            out.println("Bundled TLE is within $maxAgeDays days")
            return EXIT_OK
        }
        err.println(message)
        return EXIT_FAILED
    }

    private fun parseFlags(args: List<String>): Map<String, String> {
        require(args.size % 2 == 0 && args.withIndex().all { (i, a) -> (i % 2 == 0) == a.startsWith("--") }) { "flags must be --name value pairs" }
        return args.chunked(2).associate { (k, v) -> k.removePrefix("--") to v }
    }

    private fun Map<String, String>.required(name: String): String = this[name] ?: throw IllegalArgumentException("missing --$name")
}
