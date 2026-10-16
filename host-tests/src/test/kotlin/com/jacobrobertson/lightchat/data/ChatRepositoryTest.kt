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

    override suspend fun profile(token: String) = identity
    override suspend fun spaces(token: String, pageToken: String) = spacePages(pageToken)
    override suspend fun messages(token: String, space: String, pageToken: String) = messagePages(pageToken)
    override suspend fun send(token: String, message: OutboxEntity): RemoteMessage {
        sent += message
        sendFailure?.let { throw it }
        return remoteMessage("client-${message.id}", message.text)
    }
}
