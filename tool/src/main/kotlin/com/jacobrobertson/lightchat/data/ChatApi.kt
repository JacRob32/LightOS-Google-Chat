package com.jacobrobertson.lightchat.data

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.parameters
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Instant

@Serializable
data class GoogleProfile(val sub: String, val email: String)

@Serializable
data class RemoteSpace(
    val name: String,
    val displayName: String? = null,
    val spaceType: String = "SPACE",
) {
    fun entity() = SpaceEntity(
        name = name,
        title = displayName?.takeIf { it.isNotBlank() }
            ?: if (spaceType == "DIRECT_MESSAGE") "Direct message (${name.substringAfterLast('/')})"
            else "Conversation (${name.substringAfterLast('/')})",
        type = spaceType,
    )
}

@Serializable
data class RemoteUser(val name: String = "", val displayName: String? = null)

@Serializable
data class RemoteThread(val name: String)

@Serializable
data class RemoteMessage(
    val name: String,
    val text: String = "",
    val formattedText: String = "",
    val createTime: String,
    val sender: RemoteUser = RemoteUser(),
    val thread: RemoteThread? = null,
) {
    fun entity(space: String) = MessageEntity(
        name = name,
        space = space,
        thread = thread?.name ?: name,
        sender = sender.displayName?.takeIf { it.isNotBlank() }
            ?: sender.name.takeIf { it.isNotBlank() } ?: "Unknown sender",
        text = text.ifBlank { formattedText.ifBlank { "[Non-text message - open Google Chat to view]" } },
        createdAt = Instant.parse(createTime).toEpochMilli(),
    )
}

@Serializable
data class SpacesPage(val spaces: List<RemoteSpace> = emptyList(), val nextPageToken: String = "")

@Serializable
data class MessagesPage(val messages: List<RemoteMessage> = emptyList(), val nextPageToken: String = "")

@Serializable
private data class CreateMessage(val text: String, val thread: RemoteThread? = null)

@Serializable
private data class TokenResponse(@SerialName("access_token") val accessToken: String = "")

class ChatApiException(val status: Int) : Exception("Google request failed (HTTP $status).")

interface ChatService {
    suspend fun refreshAccessToken(clientId: String, clientSecret: String, refreshToken: String): String
    suspend fun profile(token: String): GoogleProfile
    suspend fun spaces(token: String, pageToken: String): SpacesPage
    suspend fun messages(token: String, space: String, pageToken: String): MessagesPage
    suspend fun send(token: String, message: OutboxEntity): RemoteMessage
}

class KtorChatService(
    private val client: HttpClient = HttpClient(OkHttp) {
        followRedirects = false
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        install(HttpTimeout) {
            requestTimeoutMillis = 30_000
            connectTimeoutMillis = 15_000
            socketTimeoutMillis = 30_000
        }
    },
) : ChatService, AutoCloseable {
    override suspend fun refreshAccessToken(
        clientId: String,
        clientSecret: String,
        refreshToken: String,
    ): String {
        val response = client.submitForm(
            "https://oauth2.googleapis.com/token",
            parameters {
                append("grant_type", "refresh_token")
                append("client_id", clientId)
                if (clientSecret.isNotEmpty()) append("client_secret", clientSecret)
                append("refresh_token", refreshToken)
            },
        )
        if (response.status.value !in 200..299) throw ChatApiException(response.status.value)
        return response.body<TokenResponse>().accessToken.also {
            check(it.isNotBlank()) { "Google returned no access token." }
        }
    }

    override suspend fun profile(token: String): GoogleProfile =
        client.get("https://openidconnect.googleapis.com/v1/userinfo") {
            bearerAuth(token)
        }.let { response ->
            if (response.status.value !in 200..299) throw ChatApiException(response.status.value)
            response.body()
        }

    override suspend fun spaces(token: String, pageToken: String): SpacesPage =
        client.get("$BASE/spaces") {
            bearerAuth(token)
            parameter("pageSize", 100)
            if (pageToken.isNotEmpty()) parameter("pageToken", pageToken)
        }.let { response ->
            if (response.status.value !in 200..299) throw ChatApiException(response.status.value)
            response.body()
        }

    override suspend fun messages(token: String, space: String, pageToken: String): MessagesPage {
        requireSpace(space)
        return client.get("$BASE/$space/messages") {
            bearerAuth(token)
            parameter("pageSize", 1000)
            parameter("orderBy", "createTime DESC")
            if (pageToken.isNotEmpty()) parameter("pageToken", pageToken)
        }.let { response ->
            if (response.status.value !in 200..299) throw ChatApiException(response.status.value)
            response.body()
        }
    }

    override suspend fun send(token: String, message: OutboxEntity): RemoteMessage {
        requireSpace(message.space)
        require(message.id.matches(Regex("[a-z0-9-]{1,56}"))) { "Invalid client message ID." }
        message.thread?.let { requireThread(message.space, it) }
        val response = client.post("$BASE/${message.space}/messages") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            parameter("messageId", "client-${message.id}")
            parameter("requestId", message.id)
            if (message.thread != null) {
                parameter("messageReplyOption", "REPLY_MESSAGE_OR_FAIL")
            }
            setBody(CreateMessage(message.text, message.thread?.let(::RemoteThread)))
        }
        // A previous attempt may have succeeded before its response reached this device.
        if (response.status.value == 409) {
            return client.get("$BASE/${message.space}/messages/client-${message.id}") {
                bearerAuth(token)
            }.let { existing ->
                if (existing.status.value !in 200..299) throw ChatApiException(existing.status.value)
                existing.body()
            }
        }
        if (response.status.value !in 200..299) throw ChatApiException(response.status.value)
        return response.body()
    }

    override fun close() = client.close()

    companion object {
        private const val BASE = "https://chat.googleapis.com/v1"
        private val SPACE = Regex("spaces/[A-Za-z0-9_-]+")
        private val THREAD = Regex("spaces/[A-Za-z0-9_-]+/threads/[A-Za-z0-9_-]+")
        fun requireSpace(name: String) {
            require(SPACE.matches(name)) { "Invalid space name." }
        }

        fun requireThread(space: String, thread: String) {
            require(thread.startsWith("$space/threads/") && THREAD.matches(thread)) {
                "Reply is unavailable because this message has no valid thread."
            }
        }
    }
}
