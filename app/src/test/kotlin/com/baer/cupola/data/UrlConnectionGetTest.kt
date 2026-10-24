package com.baer.cupola.data

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UrlConnectionGetTest {
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/small") { it.reply(200, "ISS") }
        createContext("/big") { it.reply(200, "x".repeat(UrlConnectionGet.MAX_BYTES + 1)) }
        createContext("/limit") { it.reply(200, "x".repeat(UrlConnectionGet.MAX_BYTES)) }
        createContext("/gone") { it.reply(403, "Forbidden") }
        start()
    }

    private fun com.sun.net.httpserver.HttpExchange.reply(status: Int, body: String) {
        val bytes = body.toByteArray()
        sendResponseHeaders(status, bytes.size.toLong())
        responseBody.use { it.write(bytes) }
    }

    private fun url(path: String) = "http://127.0.0.1:${server.address.port}$path"

    @After fun tearDown() = server.stop(0)

    @Test
    fun returnsTheBodyOf200() {
        assertEquals(HttpGet.Response(200, "ISS"), UrlConnectionGet().get(url("/small")))
    }

    @Test
    fun capsTheBody() {
        assertEquals(HttpGet.Response(200, null), UrlConnectionGet().get(url("/big")))
        assertEquals(UrlConnectionGet.MAX_BYTES, UrlConnectionGet().get(url("/limit")).body?.length)
    }

    @Test
    fun non200HasNoBody() {
        val response = UrlConnectionGet().get(url("/gone"))
        assertEquals(403, response.status)
        assertNull(response.body)
    }
}
