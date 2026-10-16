package com.jacobrobertson.lightchat.data

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.http.content.OutgoingContent
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ChatApiTest {
    private fun service(handler: (HttpRequestData) -> Pair<HttpStatusCode, String>): KtorChatService {
        val engine = MockEngine { request ->
            assertEquals("Bearer access-token", request.headers[HttpHeaders.Authorization])
            val (status, body) = handler(request)
            respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        return KtorChatService(HttpClient(engine) {
            followRedirects = false
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        })
    }

    @Test
    fun `list requests use Google HTTPS endpoints and pass opaque pagination tokens`() = runTest {
        service { request ->
            assertEquals("https", request.url.protocol.name)
            assertEquals("chat.googleapis.com", request.url.host)
            assertEquals("a+b/= token", request.url.parameters["pageToken"])
            if (request.url.encodedPath.endsWith("/messages")) {
                assertEquals("/v1/spaces/team/messages", request.url.encodedPath)
                assertEquals("createTime DESC", request.url.parameters["orderBy"])
                HttpStatusCode.OK to """{"messages":[],"nextPageToken":"next"}"""
            } else {
                assertEquals("/v1/spaces", request.url.encodedPath)
                HttpStatusCode.OK to """{"spaces":[{"name":"spaces/team","displayName":"Team","futureField":true}]}"""
            }
        }.use { api ->
            assertEquals("Team", api.spaces("access-token", "a+b/= token").spaces.single().displayName)
            assertEquals("next", api.messages("access-token", "spaces/team", "a+b/= token").nextPageToken)
        }
    }

    @Test
    fun `reply uses stable custom message id and never falls back to a new thread`() = runTest {
        service { request ->
            assertEquals("client-abc-123", request.url.parameters["messageId"])
            assertEquals("abc-123", request.url.parameters["requestId"])
            assertEquals("REPLY_MESSAGE_OR_FAIL", request.url.parameters["messageReplyOption"])
            val body = (request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()
            val json = Json.parseToJsonElement(body).jsonObject
            assertEquals("hello", json.getValue("text").jsonPrimitive.content)
            assertEquals("spaces/team/threads/thread", json.getValue("thread").jsonObject.getValue("name").jsonPrimitive.content)
            HttpStatusCode.NotFound to "{}"
        }.use { api ->
            val failure = assertFailsWith<ChatApiException> {
                api.send("access-token", OutboxEntity("abc-123", "spaces/team", "spaces/team/threads/thread", "hello", 0))
            }
            assertEquals(404, failure.status)
        }
    }

    @Test
    fun `duplicate create retrieves previous successful message rather than sending twice`() = runTest {
        val paths = mutableListOf<String>()
        service { request ->
            paths += request.url.encodedPath
            if (paths.size == 1) HttpStatusCode.Conflict to "{}"
            else HttpStatusCode.OK to """{"name":"spaces/team/messages/client-abc","text":"hello","createTime":"2026-10-15T12:00:00Z"}"""
        }.use { api ->
            val result = api.send("access-token", OutboxEntity("abc", "spaces/team", null, "hello", 0))
            assertEquals("spaces/team/messages/client-abc", result.name)
            assertEquals(listOf("/v1/spaces/team/messages", "/v1/spaces/team/messages/client-abc"), paths)
        }
    }

    @Test
    fun `profile uses OpenID userinfo to verify account identity`() = runTest {
        service { request ->
            assertEquals("openidconnect.googleapis.com", request.url.host)
            assertEquals("/v1/userinfo", request.url.encodedPath)
            HttpStatusCode.OK to """{"sub":"person","email":"person@example.test"}"""
        }.use { api -> assertEquals("person", api.profile("access-token").sub) }
    }

    @Test
    fun `malformed resources are rejected before any network request`() = runTest {
        service { error("Unexpected network call") }.use { api ->
            assertFailsWith<IllegalArgumentException> { api.messages("access-token", "spaces/team?alt=media", "") }
            assertFailsWith<IllegalArgumentException> {
                api.send("access-token", OutboxEntity("abc", "spaces/team", "spaces/other/threads/thread", "hello", 0))
            }
        }
    }

    @Test
    fun `error bodies containing sensitive details are not exposed in exceptions`() = runTest {
        service { HttpStatusCode.Forbidden to """{"error":"sensitive server detail"}""" }.use { api ->
            val failure = assertFailsWith<ChatApiException> { api.spaces("access-token", "") }
            assertEquals(403, failure.status)
            assertTrue("sensitive" !in failure.message.orEmpty())
        }
    }
}
