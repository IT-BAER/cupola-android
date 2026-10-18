package com.baer.cupola.tools.tle

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant
import kotlin.system.exitProcess

class HttpTleSource : TleSource {
    private val timeout = Duration.ofSeconds(30)
    private val client = HttpClient.newBuilder().connectTimeout(timeout).build()

    override fun get(url: String): TleSource.Response {
        val request = HttpRequest.newBuilder(URI.create(url)).timeout(timeout)
            .header("User-Agent", "cupola-release-prep").GET().build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        return TleSource.Response(response.statusCode(), response.body())
    }
}

fun main(args: Array<String>) {
    exitProcess(Cli.run(args.toList(), HttpTleSource(), Instant.now(), System.out, System.err))
}
