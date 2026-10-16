package com.jacobrobertson.lightchat.data

import android.app.Application
import androidx.room.Room
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, manifest = Config.NONE)
class ChatRepositoryTest {
    private lateinit var database: ChatDatabase
    private lateinit var service: FakeChatService
    private lateinit var repository: ChatRepository
    private val space = "spaces/team"

    @Before
    fun setup() {
        database = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), ChatDatabase::class.java).build()
        service = FakeChatService()
        repository = ChatRepository(database, service) { 1234L }
    }

    @After
    fun teardown() = database.close()

    private suspend fun connected() {
        repository.connect("test-token")
        repository.refreshSpaces()
    }

    @Test
    fun `cached data emits without credentials and refresh requires sign in`() = runTest {
        connected()
        repository.refreshMessages(space)
        val offline = ChatRepository(database, service)
        assertEquals(1, offline.messages.first().size)
        assertEquals("person@example.test", offline.profile.first()?.email)
        assertFalse(offline.connected.value)
        assertFailsWith<SignInRequiredException> { offline.refreshMessages(space) }
        assertEquals(1, offline.messages.first().size)
    }

    @Test
    fun `all pages are fetched and failed later page leaves previous snapshot intact`() = runTest {
        connected()
        repository.refreshMessages(space)
        service.messagePages = { page ->
            if (page.isEmpty()) MessagesPage(listOf(remoteMessage("new")), "second")
            else throw ChatApiException(503)
        }
        assertFailsWith<ChatApiException> { repository.refreshMessages(space) }
        assertEquals(listOf("$space/messages/old"), repository.messages.first().map { it.name })
        assertEquals(1234L, repository.spaces.first().single().syncedAt)
    }

    @Test
    fun `refresh reconciles edits and deletes while preserving other spaces`() = runTest {
        connected()
        repository.refreshMessages(space)
        database.chatDao().saveMessages(listOf(remoteMessage("other").entity("spaces/other")))
        service.messagePages = { MessagesPage(listOf(remoteMessage("replacement", "edited"))) }
        repository.refreshMessages(space)
        val messages = repository.messages.first()
        assertEquals(2, messages.size)
        assertEquals("edited", messages.single { it.space == space }.text)
        assertEquals(1, messages.count { it.space == "spaces/other" })
    }

    @Test
    fun `empty server snapshot removes stale cached messages`() = runTest {
        connected()
        repository.refreshMessages(space)
        service.messagePages = { MessagesPage() }
        repository.refreshMessages(space)
        assertTrue(repository.messages.first().isEmpty())
    }

    @Test
    fun `different verified account cannot reuse the previous cache or outbox`() = runTest {
        connected()
        repository.enqueue(space, null, "Do not send as a different account")
        service.identity = GoogleProfile("another-person", "another@example.test")
        assertFailsWith<IllegalArgumentException> { repository.connect("another-token") }
        assertEquals("person", repository.profile.first()?.subject)
        assertEquals(1, repository.outbox.first().size)
        assertTrue(service.sent.isEmpty())
    }

    @Test
    fun `forget account removes profile cache outbox and in memory credentials`() = runTest {
        connected()
        repository.refreshMessages(space)
        repository.enqueue(space, null, "unsent")
        repository.forgetAccount()
        assertNull(repository.profile.first())
        assertTrue(repository.spaces.first().isEmpty())
        assertTrue(repository.messages.first().isEmpty())
        assertTrue(repository.outbox.first().isEmpty())
        assertFalse(repository.connected.value)
    }

    @Test
    fun `failed send stays queued and explicit retry reuses the same client id`() = runTest {
        connected()
        repository.enqueue(space, "$space/threads/thread", "hello")
        service.sendFailure = ChatApiException(503)
        assertFailsWith<ChatApiException> { repository.flushOutbox() }
        val pending = repository.outbox.first().single()
        assertNotNull(pending.error)
        assertEquals("hello", pending.text)
        service.sendFailure = null
        repository.flushOutbox()
        assertTrue(repository.outbox.first().isEmpty())
        assertEquals(listOf(pending.id, pending.id), service.sent.map { it.id })
        assertEquals("hello", repository.messages.first().single().text)
    }

    @Test
    fun `offline enqueue is persistent and does not call the network`() = runTest {
        connected()
        val offline = ChatRepository(database, service)
        offline.enqueue(space, null, "write offline")
        assertEquals("write offline", repository.outbox.first().single().text)
        assertTrue(service.sent.isEmpty())
        assertFailsWith<SignInRequiredException> { offline.flushOutbox() }
    }

    @Test
    fun `expired token marks session disconnected without deleting cached messages`() = runTest {
        connected()
        repository.refreshMessages(space)
        service.messagePages = { throw ChatApiException(401) }
        assertFailsWith<ChatApiException> { repository.refreshMessages(space) }
        assertFalse(repository.connected.value)
        assertEquals(1, repository.messages.first().size)
    }

    @Test
    fun `revoked space loses cached content but retains unsent messages for explicit discard`() = runTest {
        connected()
        repository.refreshMessages(space)
        repository.enqueue(space, null, "queued before revocation")
        service.spacePages = { SpacesPage() }
        repository.refreshSpaces()
        assertFalse(repository.spaces.first().single().accessible)
        assertTrue(repository.messages.first().isEmpty())
        assertEquals(1, repository.outbox.first().size)
        assertFailsWith<IllegalStateException> { repository.flushOutbox() }
        assertTrue(service.sent.isEmpty())
        repository.discard(repository.outbox.first().single().id)
        assertTrue(repository.outbox.first().isEmpty())
    }

    @Test
    fun `malformed message does not replace cached snapshot`() = runTest {
        connected()
        repository.refreshMessages(space)
        service.messagePages = { MessagesPage(listOf(remoteMessage("invalid").copy(createTime = "not a timestamp"))) }
        assertFailsWith<java.time.format.DateTimeParseException> { repository.refreshMessages(space) }
        assertEquals("$space/messages/old", repository.messages.first().single().name)
    }

    @Test
    fun `message size is limited in UTF8 bytes not Kotlin characters`() = runTest {
        connected()
        repository.enqueue(space, null, "a".repeat(32_000))
        assertEquals(1, repository.outbox.first().size)
        assertFailsWith<IllegalArgumentException> { repository.enqueue(space, null, "a".repeat(32_001)) }
        assertFailsWith<IllegalArgumentException> { repository.enqueue(space, null, "\u20ac".repeat(10_667)) }
        assertFailsWith<IllegalArgumentException> { repository.enqueue(space, null, "   ") }
        assertEquals(1, repository.outbox.first().size)
    }

    @Test
    fun `invalid or cross space reply cannot be queued as a new top level message`() = runTest {
        connected()
        assertFailsWith<IllegalArgumentException> {
            repository.enqueue(space, "$space/messages/no-thread", "reply")
        }
        assertFailsWith<IllegalArgumentException> {
            repository.enqueue(space, "spaces/other/threads/thread", "reply")
        }
        assertTrue(repository.outbox.first().isEmpty())
    }

    @Test
    fun `pagination collects every page and detects repeated page tokens`() = runTest {
        val pages = mutableListOf<String>()
        val items = collectPages { page ->
            pages += page
            when (page) {
                "" -> listOf(1) to "two"
                "two" -> listOf(2) to "three"
                else -> listOf(3) to ""
            }
        }
        assertEquals(listOf(1, 2, 3), items)
        assertEquals(listOf("", "two", "three"), pages)
        assertFailsWith<IllegalStateException> { collectPages { listOf(1) to "repeated" } }
    }

    @Test
    fun `access token sign in survives app restart and works without re-entering it`() = runTest {
        val store = FakeCredentialStore()
        assertTrue(ChatRepository(database, service, store).apply { connect("saved-token") }.connected.value)
        assertEquals("saved-token", store.saved?.accessToken)

        val restarted = ChatRepository(database, service, store)
        assertFalse(restarted.connected.value)
        restarted.restore()
        assertTrue(restarted.connected.value)
        assertEquals(SignIn.ACCESS_TOKEN, restarted.signIn.value)
        restarted.refreshSpaces()
        assertEquals(listOf("saved-token"), service.tokensUsed)
    }

    @Test
    fun `refresh token sign in is renewed automatically after restart`() = runTest {
        val store = FakeCredentialStore()
        val first = ChatRepository(database, service, store)
        assertTrue(first.connect("client-id client-secret refresh-token"))
        assertEquals(SignIn.REFRESH_TOKEN, first.signIn.value)
        assertEquals("refresh-token", store.saved?.refreshToken)

        service.renewedToken = "second-access-token"
        val restarted = ChatRepository(database, service, store)
        restarted.restore()
        assertTrue(restarted.connected.value)
        restarted.refreshSpaces()
        assertEquals("second-access-token", service.tokensUsed.last())
        assertNull(store.saved?.accessToken)
        assertEquals("refresh-token", store.saved?.refreshToken)
    }

    @Test
    fun `expired access token is renewed once and the request is retried`() = runTest {
        val store = FakeCredentialStore()
        val repo = ChatRepository(database, service, store)
        repo.connect("{\"client_id\":\"id\",\"client_secret\":\"secret\",\"refresh_token\":\"refresh\",\"type\":\"authorized_user\"}")
        service.renewedToken = "fresh-token"
        // The token obtained at connect time (the initial renewal) now expires.
        service.expiredTokens += "renewed-token"
        repo.refreshSpaces()
        assertEquals(listOf("renewed-token", "fresh-token"), service.tokensUsed)
        assertTrue(repo.connected.value)
        assertEquals(2, service.refreshCalls)
    }

    @Test
    fun `revoked refresh token clears saved sign in but keeps cached chats`() = runTest {
        val store = FakeCredentialStore()
        val repo = ChatRepository(database, service, store)
        repo.connect("id secret refresh")
        repo.refreshSpaces()
        repo.refreshMessages(space)
        service.expiredTokens += "renewed-token"
        service.refreshFailure = ChatApiException(400)
        assertFailsWith<SessionExpiredException> { repo.refreshMessages(space) }
        assertFalse(repo.connected.value)
        assertNull(store.saved)
        assertEquals(1, repo.messages.first().size)
        assertTrue(userFacingError(SessionExpiredException()).contains("revoked"))
    }

    @Test
    fun `transient network failure while renewing keeps the saved sign in`() = runTest {
        val store = FakeCredentialStore()
        val repo = ChatRepository(database, service, store)
        repo.connect("id secret refresh")
        service.expiredTokens += "renewed-token"
        service.refreshFailure = java.io.IOException("offline")
        assertFailsWith<java.io.IOException> { repo.refreshSpaces() }
        assertTrue(repo.connected.value)
        assertNotNull(store.saved?.refreshToken)
    }

    @Test
    fun `expired access only token clears the saved sign in`() = runTest {
        val store = FakeCredentialStore()
        val repo = ChatRepository(database, service, store)
        repo.connect("short-lived")
        service.expiredTokens += "short-lived"
        assertFailsWith<ChatApiException> { repo.refreshSpaces() }
        assertFalse(repo.connected.value)
        assertNull(store.saved)
    }

    @Test
    fun `saved sign in for a different account is discarded on restore`() = runTest {
        val store = FakeCredentialStore()
        ChatRepository(database, service, store).connect("token-for-person")
        store.saved = store.saved?.copy(subject = "someone-else")
        val restarted = ChatRepository(database, service, store)
        restarted.restore()
        assertFalse(restarted.connected.value)
        assertNull(store.saved)
    }

    @Test
    fun `unreadable saved sign in is discarded instead of crashing`() = runTest {
        val store = FakeCredentialStore()
        ChatRepository(database, service, store).connect("token")
        store.loadFailure = java.security.GeneralSecurityException("key invalidated")
        val restarted = ChatRepository(database, service, store)
        restarted.restore()
        assertFalse(restarted.connected.value)
        assertNull(store.saved)
    }

    @Test
    fun `sign in still works for this session when saving fails`() = runTest {
        val store = FakeCredentialStore().apply { saveFailure = java.io.IOException("disk") }
        val repo = ChatRepository(database, service, store)
        assertFalse(repo.connect("token"))
        assertTrue(repo.connected.value)
        repo.refreshSpaces()
    }

    @Test
    fun `sign out removes the saved sign in but keeps chats and the outbox`() = runTest {
        val store = FakeCredentialStore()
        val repo = ChatRepository(database, service, store)
        repo.connect("token")
        repo.refreshSpaces()
        repo.refreshMessages(space)
        repo.enqueue(space, null, "unsent")
        repo.signOut()
        assertFalse(repo.connected.value)
        assertEquals(SignIn.NONE, repo.signIn.value)
        assertNull(store.saved)
        assertEquals(1, repo.messages.first().size)
        assertEquals(1, repo.outbox.first().size)
    }

    @Test
    fun `forgetting the account also removes the saved sign in`() = runTest {
        val store = FakeCredentialStore()
        val repo = ChatRepository(database, service, store)
        repo.connect("id secret refresh")
        repo.forgetAccount()
        assertNull(store.saved)
        assertEquals(SignIn.NONE, repo.signIn.value)
    }

    @Test
    fun `credential input parsing accepts supported formats and never echoes secrets`() {
        assertTrue(parseCredentialInput(" abc ") is CredentialInput.AccessToken)
        val refresh = parseCredentialInput("id secret token") as CredentialInput.Refresh
        assertEquals("token", refresh.refreshToken)
        val json = parseCredentialInput("{\"client_id\":\"i\",\"refresh_token\":\"r\"}") as CredentialInput.Refresh
        assertEquals("", json.clientSecret)
        val failure = assertFailsWith<IllegalArgumentException> { parseCredentialInput("two parts") }
        assertFalse(failure.message.orEmpty().contains("parts"))
        assertFailsWith<IllegalArgumentException> { parseCredentialInput("{not json") }
        assertFailsWith<IllegalArgumentException> { parseCredentialInput("{\"client_id\":\"i\"}") }
        assertFailsWith<IllegalArgumentException> { parseCredentialInput("   ") }
        assertFalse(StoredCredentials("s", "secret-access", "i", "c", "secret-refresh").toString().contains("secret"))
    }

    @Test
    fun `database survives closing and reopening without persisting the token`() = runTest {
        val app = RuntimeEnvironment.getApplication()
        val name = "persistence-test.db"
        app.deleteDatabase(name)
        var disk = Room.databaseBuilder(app, ChatDatabase::class.java, name).build()
        try {
            val online = ChatRepository(disk, service)
            online.connect("memory-only")
            online.refreshSpaces()
            online.refreshMessages(space)
            online.enqueue(space, null, "survives restart")
            disk.close()
            disk = Room.databaseBuilder(app, ChatDatabase::class.java, name).build()
            val restored = ChatRepository(disk, service)
            assertEquals("person", restored.profile.first()?.subject)
            assertEquals(1, restored.messages.first().size)
            assertEquals("survives restart", restored.outbox.first().single().text)
            assertFalse(restored.connected.value)
        } finally {
            disk.close()
            app.deleteDatabase(name)
        }
    }
}

internal fun remoteMessage(id: String = "old", text: String = "cached") = RemoteMessage(
    name = "spaces/team/messages/$id",
    text = text,
    createTime = "2026-10-15T12:00:00Z",
    sender = RemoteUser("users/person", "Person"),
    thread = RemoteThread("spaces/team/threads/thread"),
)

private class FakeChatService : ChatService {
    var identity = GoogleProfile("person", "person@example.test")
    var spacePages: (String) -> SpacesPage = { SpacesPage(listOf(RemoteSpace("spaces/team", "Team"))) }
    var messagePages: (String) -> MessagesPage = { MessagesPage(listOf(remoteMessage())) }
    var sendFailure: Exception? = null
    val sent = mutableListOf<OutboxEntity>()
    val expiredTokens = mutableSetOf<String>()
    val tokensUsed = mutableListOf<String>()
    var renewedToken = "renewed-token"
    var refreshFailure: Exception? = null
    var refreshCalls = 0

    private fun use(token: String) {
        tokensUsed += token
        if (token in expiredTokens) throw ChatApiException(401)
    }

    override suspend fun refreshAccessToken(clientId: String, clientSecret: String, refreshToken: String): String {
        refreshCalls++
        refreshFailure?.let { throw it }
        return renewedToken
    }

    override suspend fun profile(token: String) = identity
    override suspend fun spaces(token: String, pageToken: String): SpacesPage {
        use(token)
        return spacePages(pageToken)
    }

    override suspend fun messages(token: String, space: String, pageToken: String): MessagesPage {
        use(token)
        return messagePages(pageToken)
    }

    override suspend fun send(token: String, message: OutboxEntity): RemoteMessage {
        use(token)
        sent += message
        sendFailure?.let { throw it }
        return remoteMessage("client-${message.id}", message.text)
    }
}

private class FakeCredentialStore : CredentialStore {
    var saved: StoredCredentials? = null
    var loadFailure: Exception? = null
    var saveFailure: Exception? = null

    override suspend fun load(): StoredCredentials? {
        loadFailure?.let { throw it }
        return saved
    }

    override suspend fun save(credentials: StoredCredentials) {
        saveFailure?.let { throw it }
        saved = credentials
    }

    override suspend fun clear() {
        saved = null
    }
}
