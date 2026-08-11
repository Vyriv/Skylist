package dev.ryan.playerlist

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

object SkylistApiClient {
    const val apiBaseUrl = PlayerListLinks.skylistApiBaseUrl

    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build()

    fun fetchJson(path: String, timeoutSeconds: Long = 8): JsonObject? {
        // This client only talks to the Skylist relay API for public mod data and
        // read-only lookups. It does not launch anything locally or access local
        // account/session files.
        val request = HttpRequest.newBuilder()
            .uri(URI.create(apiBaseUrl + path))
            .timeout(Duration.ofSeconds(timeoutSeconds))
            .header("accept", "application/json,*/*")
            .GET()
            .build()

        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        return when (response.statusCode()) {
            200 -> JsonParser.parseString(response.body()).asJsonObject
            204, 404 -> null
            else -> throw IOException("Unexpected response ${response.statusCode()} from ${request.uri()}")
        }
    }
}
