package com.jacobrobertson.lightchat.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/** Credentials persisted (encrypted) so the user stays signed in after the app closes. */
@Serializable
data class StoredCredentials(
    val subject: String,
    val accessToken: String? = null,
    val clientId: String? = null,
    val clientSecret: String? = null,
    val refreshToken: String? = null,
) {
    val refreshable: Boolean
        get() = !clientId.isNullOrEmpty() && !refreshToken.isNullOrEmpty()

    override fun toString() = "StoredCredentials(redacted)"
}

enum class SignIn { NONE, ACCESS_TOKEN, REFRESH_TOKEN }

interface CredentialStore {
    suspend fun load(): StoredCredentials?
    suspend fun save(credentials: StoredCredentials)
    suspend fun clear()
}

object NoCredentialStore : CredentialStore {
    override suspend fun load(): StoredCredentials? = null
    override suspend fun save(credentials: StoredCredentials) = Unit
    override suspend fun clear() = Unit
}

sealed interface CredentialInput {
    class AccessToken(val token: String) : CredentialInput {
        override fun toString() = "AccessToken(redacted)"
    }

    class Refresh(val clientId: String, val clientSecret: String, val refreshToken: String) : CredentialInput {
        override fun toString() = "Refresh(redacted)"
    }
}

@Serializable
private data class AuthorizedUser(
    @SerialName("client_id") val clientId: String = "",
    @SerialName("client_secret") val clientSecret: String = "",
    @SerialName("refresh_token") val refreshToken: String = "",
)

private const val INPUT_HELP =
    "Enter an access token, or 'client_id client_secret refresh_token' separated by spaces, " +
        "or authorized-user JSON."

/** Error messages never echo the input, because it is a credential. */
fun parseCredentialInput(raw: String): CredentialInput {
    val text = raw.trim()
    require(text.isNotEmpty()) { INPUT_HELP }
    if (text.startsWith("{")) {
        val user = try {
            Json { ignoreUnknownKeys = true }.decodeFromString<AuthorizedUser>(text)
        } catch (_: SerializationException) {
            throw IllegalArgumentException("The credential JSON is not valid.")
        }
        require(user.clientId.isNotBlank() && user.refreshToken.isNotBlank()) {
            "The credential JSON needs client_id and refresh_token."
        }
        return CredentialInput.Refresh(user.clientId, user.clientSecret, user.refreshToken)
    }
    val parts = text.split(Regex("\\s+"))
    return when (parts.size) {
        1 -> CredentialInput.AccessToken(parts[0])
        3 -> CredentialInput.Refresh(parts[0], parts[1], parts[2])
        else -> throw IllegalArgumentException(INPUT_HELP)
    }
}
