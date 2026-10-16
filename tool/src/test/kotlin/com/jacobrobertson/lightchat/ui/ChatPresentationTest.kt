package com.jacobrobertson.lightchat.ui

import com.jacobrobertson.lightchat.data.RemoteMessage
import com.jacobrobertson.lightchat.data.RemoteThread
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ChatPresentationTest {
    @Test
    fun `thread ordering uses parsed instants and retains root plus latest reply`() {
        val root = RemoteMessage("spaces/a/messages/root", "root", createTime = "2026-10-15T12:00:00Z", thread = RemoteThread("spaces/a/threads/one"))
        val reply = root.copy(name = "spaces/a/messages/reply", text = "reply", createTime = "2026-10-15T12:00:00.500Z")
        val other = root.copy(name = "spaces/a/messages/other", createTime = "2026-10-15T12:00:00.100Z", thread = RemoteThread("spaces/a/threads/two"))
        val summaries = summarizeThreads(listOf(reply.entity("spaces/a"), other.entity("spaces/a"), root.entity("spaces/a")))
        assertEquals("spaces/a/threads/one", summaries.first().name)
        assertEquals("root", summaries.first().first.text)
        assertEquals("reply", summaries.first().latest.text)
        assertEquals(2, summaries.first().count)
    }

    @Test
    fun `non text content is explicit and missing threads do not collapse into one`() {
        val first = RemoteMessage("spaces/a/messages/one", createTime = "2026-10-15T12:00:00Z")
        val second = first.copy(name = "spaces/a/messages/two")
        assertTrue(first.entity("spaces/a").text.contains("Non-text message"))
        assertEquals(2, summarizeThreads(listOf(first.entity("spaces/a"), second.entity("spaces/a"))).size)
    }

    @Test
    fun `theme is strictly black on white including secondary text`() {
        assertEquals(androidx.compose.ui.graphics.Color.White, ChatColors.background)
        assertEquals(androidx.compose.ui.graphics.Color.Black, ChatColors.content)
        assertEquals(androidx.compose.ui.graphics.Color.Black, ChatColors.contentSecondary)
    }
}
