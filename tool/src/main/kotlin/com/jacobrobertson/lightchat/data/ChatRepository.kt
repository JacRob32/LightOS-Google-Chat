package com.jacobrobertson.lightchat.data

import androidx.room.withTransaction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

class SignInRequiredException : Exception("Connect a Google account to refresh or send.")

class SessionExpiredException : Exception("The saved sign-in is no longer valid.")

class ChatRepository(
    private val database: ChatDatabase,
    private val service: ChatService,
    private val store: CredentialStore = NoCredentialStore,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val dao = database.chatDao()
    private val mutex = Mutex()
    private var token: String? = null
    private var credentials: StoredCredentials? = null
    private val _connected = MutableStateFlow(false)
    val connected = _connected.asStateFlow()
    private val _signIn = MutableStateFlow(SignIn.NONE)
    val signIn = _signIn.asStateFlow()
    val profile = dao.observeProfile()
    val spaces = dao.observeSpaces()
    val messages = dao.observeMessages()
    val outbox = dao.observeOutbox()

    /** Restores a saved sign-in without any network request. */
    suspend fun restore() = mutex.withLock {
        val saved = try {
            store.load()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Unreadable (e.g. the device key was invalidated): drop it, the user signs in again.
            clearSavedCredentials()
            null
        } ?: return@withLock
        val cached = dao.profile()
        if (cached == null || cached.subject != saved.subject) {
            clearSavedCredentials()
            return@withLock
        }
        credentials = saved
        // A saved refresh token is exchanged on first use; a stored access token may be stale.
        token = saved.accessToken.takeIf { !saved.refreshable }
        publishSignIn()
    }

    /** Returns whether the sign-in was saved on this phone for the next launch. */
    suspend fun connect(input: String): Boolean = mutex.withLock {
        val parsed = parseCredentialInput(input)
        val refresh = parsed as? CredentialInput.Refresh
        val candidate = when (parsed) {
            is CredentialInput.AccessToken -> parsed.token
            is CredentialInput.Refresh ->
                service.refreshAccessToken(parsed.clientId, parsed.clientSecret, parsed.refreshToken)
        }
        val identity = service.profile(candidate)
        require(identity.sub.isNotBlank() && identity.email.isNotBlank()) {
            "The token must include the openid and email scopes."
        }
        val previous = dao.profile()
        require(previous == null || previous.subject == identity.sub) {
            "This token belongs to a different account. Forget the cached account before switching."
        }
        dao.saveProfile(ProfileEntity(subject = identity.sub, email = identity.email))
        val saved = StoredCredentials(
            subject = identity.sub,
            accessToken = candidate.takeIf { refresh == null },
            clientId = refresh?.clientId,
            clientSecret = refresh?.clientSecret,
            refreshToken = refresh?.refreshToken,
        )
        credentials = saved
        token = candidate
        publishSignIn()
        persist(saved)
    }

    suspend fun refreshSpaces() = authenticated { accessToken ->
        val remote = collectPages { page -> service.spaces(accessToken, page).let { it.spaces to it.nextPageToken } }
        database.withTransaction {
            dao.markSpacesUnavailable()
            for (space in remote) {
                val cached = dao.space(space.name)
                dao.saveSpaces(listOf(space.entity().copy(syncedAt = cached?.syncedAt)))
            }
            dao.removeInaccessibleMessages()
        }
    }

    suspend fun refreshMessages(space: String) = authenticated { accessToken ->
        val remote = collectPages { page ->
            service.messages(accessToken, space, page).let { it.messages to it.nextPageToken }
        }
        val messages = remote.map { it.entity(space) }
        database.withTransaction {
            dao.deleteMessages(space)
            dao.saveMessages(messages)
            dao.setSyncedAt(space, now())
        }
    }

    suspend fun enqueue(space: String, thread: String?, text: String) = mutex.withLock {
        KtorChatService.requireSpace(space)
        thread?.let { KtorChatService.requireThread(space, it) }
        require(dao.profile() != null) { "Connect a Google account before writing a message." }
        require(dao.space(space)?.accessible == true) { "This conversation is no longer available." }
        require(text.isNotBlank()) { "A message cannot be empty." }
        require(text.toByteArray(Charsets.UTF_8).size <= 32_000) {
            "A message must be at most 32,000 UTF-8 bytes."
        }
        val message = OutboxEntity(
            id = UUID.randomUUID().toString(),
            space = space,
            thread = thread,
            text = text.trim(),
            createdAt = now(),
        )
        dao.savePending(message)
    }

    suspend fun flushOutbox() = authenticated { accessToken ->
        for (message in dao.pending()) {
            try {
                check(dao.space(message.space)?.accessible == true) {
                    "A queued conversation is no longer available. Discard its queued message."
                }
                val sent = service.send(accessToken, message).entity(message.space)
                database.withTransaction {
                    dao.saveMessages(listOf(sent))
                    dao.deletePending(message.id)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                dao.savePending(message.copy(error = userFacingError(failure)))
                throw failure
            }
        }
    }

    suspend fun discard(id: String) = mutex.withLock { dao.deletePending(id) }

    suspend fun forgetAccount() = mutex.withLock {
        database.withTransaction {
            dao.clearOutbox()
            dao.clearMessages()
            dao.clearSpaces()
            dao.clearProfile()
        }
        clearSavedCredentials()
    }

    /** Removes the saved sign-in but keeps cached chats and unsent messages. */
    suspend fun signOut() = mutex.withLock { clearSavedCredentials() }

    private suspend fun clearSavedCredentials() {
        credentials = null
        token = null
        publishSignIn()
        try {
            store.clear()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Memory is already cleared; nothing more can be done for an unwritable store.
        }
    }

    private suspend fun persist(saved: StoredCredentials): Boolean = try {
        store.save(saved)
        true
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        false
    }

    private fun publishSignIn() {
        val saved = credentials
        _signIn.value = when {
            saved == null -> SignIn.NONE
            saved.refreshable -> SignIn.REFRESH_TOKEN
            token != null -> SignIn.ACCESS_TOKEN
            else -> SignIn.NONE
        }
        _connected.value = _signIn.value != SignIn.NONE
    }

    /** Exchanges the saved refresh token for a new access token, or null when there is none. */
    private suspend fun renewAccessToken(): String? {
        val saved = credentials?.takeIf { it.refreshable } ?: return null
        val renewed = try {
            service.refreshAccessToken(saved.clientId.orEmpty(), saved.clientSecret.orEmpty(), saved.refreshToken.orEmpty())
        } catch (failure: ChatApiException) {
            if (failure.status == 400 || failure.status == 401) {
                clearSavedCredentials()
                throw SessionExpiredException()
            }
            throw failure
        }
        token = renewed
        return renewed
    }

    private suspend fun <T> authenticated(block: suspend (String) -> T): T = mutex.withLock {
        val accessToken = token ?: renewAccessToken() ?: throw SignInRequiredException()
        try {
            block(accessToken)
        } catch (failure: ChatApiException) {
            if (failure.status != 401) throw failure
            val renewed = renewAccessToken()
            if (renewed == null) {
                clearSavedCredentials()
                throw failure
            }
            try {
                block(renewed)
            } catch (again: ChatApiException) {
                if (again.status == 401) clearSavedCredentials()
                throw again
            }
        }
    }
}

internal suspend fun <T> collectPages(fetch: suspend (String) -> Pair<List<T>, String>): List<T> {
    val collected = mutableListOf<T>()
    val seen = mutableSetOf<String>()
    var page = ""
    do {
        check(seen.add(page)) { "Google returned a repeated page token. Please retry." }
        val (items, next) = fetch(page)
        collected += items
        page = next
    } while (page.isNotEmpty())
    return collected
}

fun userFacingError(failure: Throwable): String = when (failure) {
    is SignInRequiredException -> "Offline cache is available. Connect a token to refresh or send."
    is SessionExpiredException ->
        "Your saved sign-in was revoked or expired. Connect again; cached chats are still available."
    is ChatApiException -> when (failure.status) {
        401 -> "Your token expired or was revoked. Connect a fresh token; cached chats are still available."
        403 -> "Google denied access. Check Chat API enablement, OAuth scopes, and Workspace policy."
        404 -> "The conversation or reply thread is no longer available."
        429 -> "Google's rate limit was reached. Wait before retrying."
        in 500..599 -> "Google Chat is temporarily unavailable. Cached chats and queued messages are safe."
        else -> "Google rejected the request (HTTP ${failure.status})."
    }
    is java.io.IOException -> "Cannot reach Google. Read cached chats or retry when online."
    is IllegalArgumentException, is IllegalStateException -> failure.message ?: "Invalid operation."
    else -> "The operation failed. Cached chats are unchanged. Please retry."
}
