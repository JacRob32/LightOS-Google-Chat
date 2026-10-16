package com.jacobrobertson.lightchat.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jacobrobertson.lightchat.data.MessageEntity
import com.jacobrobertson.lightchat.data.OutboxEntity
import com.jacobrobertson.lightchat.data.SignIn
import com.jacobrobertson.lightchat.data.SpaceEntity
import com.thelightphone.sdk.rememberKeyboardOptions
import com.thelightphone.sdk.ui.LightColors
import com.thelightphone.sdk.ui.LightTextInputEditor
import com.thelightphone.sdk.ui.LightTheme

val ChatColors = LightColors(Color.White, Color.Black, Color.Black)

@Composable
fun ChatContent(state: ChatUiState, viewModel: ChatViewModel, onBack: () -> Unit) {
    LightTheme(colors = ChatColors) {
        Column(Modifier.fillMaxSize().background(Color.White)) {
            if (state.error != null || state.notice != null || state.busy) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
                    Copy(state.error ?: if (state.busy) "Working..." else state.notice.orEmpty(), small = true)
                    if (!state.busy) Action("Dismiss", onClick = viewModel::dismissError)
                    Rule()
                }
            }
            if (state.panel == Panel.COMPOSE || state.panel == Panel.TOKEN) {
                Editor(state, viewModel, onBack, Modifier.weight(1f))
            } else {
                Column(Modifier.weight(1f).padding(horizontal = 20.dp)) {
                    Header(state, viewModel, onBack)
                    Rule()
                    when (state.panel) {
                        Panel.ACCOUNT -> Account(state, viewModel, Modifier.weight(1f))
                        Panel.FORGET -> Forget(state, viewModel, onBack, Modifier.weight(1f))
                        else -> {
                            when {
                                !state.initialized -> Box(Modifier.weight(1f)) { Copy("Opening local cache...") }
                                state.profile == null -> Welcome(viewModel, Modifier.weight(1f))
                                else -> Conversations(state, viewModel, Modifier.weight(1f))
                            }
                            Footer(state, viewModel)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Header(state: ChatUiState, viewModel: ChatViewModel, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Action("Back", enabled = !state.busy, onClick = onBack)
        Text(
            if (state.panel == Panel.ACCOUNT || state.panel == Panel.FORGET) "Account" else "Chat",
            color = Color.Black,
            fontSize = 26.sp,
            fontWeight = FontWeight.Normal,
            modifier = Modifier.padding(vertical = 10.dp),
        )
        Action("Account", enabled = !state.busy, onClick = viewModel::account)
    }
}

@Composable
private fun Welcome(viewModel: ChatViewModel, modifier: Modifier) {
    Column(modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Spacer(Modifier.height(12.dp))
        Copy("A quieter way to chat.")
        Copy("Connect your Google Workspace account to see conversations here. Opened chats stay available offline.")
        Copy("No Google Play Services. No background polling. Refresh only when you choose.", small = true)
        Action("Connect account", onClick = viewModel::tokenEntry)
    }
}

@Composable
private fun Conversations(state: ChatUiState, viewModel: ChatViewModel, modifier: Modifier) {
    val space = state.spaces.find { it.name == state.space }
    val messages = state.messages.filter { it.space == state.space }
    val queued = state.outbox.filter { it.space == state.space }
    when {
        state.space == null -> LazyColumn(modifier) {
            item {
                Copy(if (state.connected) "Conversations" else "Conversations / offline cache", small = true)
                if (state.spaces.isEmpty()) Copy("No cached conversations. Connect and refresh to get started.")
            }
            items(state.spaces, key = { it.name }) { entry ->
                ConversationRow(entry, !state.busy) { viewModel.openSpace(entry.name) }
            }
        }
        state.thread == null -> LazyColumn(modifier) {
            item {
                Copy(space?.title ?: "Conversation")
                Copy(
                    if (space?.accessible == false) "No longer available. Queued messages are kept until discarded."
                    else space?.syncedAt?.let { "Cached ${displayTime(it)}" } ?: "Not downloaded yet. Refresh to cache messages.",
                    small = true,
                )
            }
            items(summarizeThreads(messages), key = { it.name }) { thread ->
                Column(
                    Modifier.fillMaxWidth().chatClick(!state.busy) { viewModel.openThread(thread.name) }
                        .padding(vertical = 12.dp),
                ) {
                    Copy(thread.first.text, maxLines = 2)
                    Copy("${thread.latest.sender} / ${displayTime(thread.latest.createdAt)}", small = true, maxLines = 1)
                    Copy("${thread.count} ${if (thread.count == 1) "message" else "messages"}", small = true)
                }
                Rule()
            }
            if (messages.isEmpty()) item { Copy("No cached messages in this conversation.") }
            items(queued, key = { "queued-${it.id}" }) { Pending(it, state.busy, viewModel::discard) }
        }
        else -> LazyColumn(modifier) {
            item {
                Copy(space?.title ?: "Thread", small = true)
                if ("/threads/" !in state.thread) Copy("Reply unavailable: Google did not return a thread.", small = true)
            }
            items(messages.filter { it.thread == state.thread }, key = { it.name }) { Message(it) }
            items(queued.filter { it.thread == state.thread }, key = { "queued-${it.id}" }) {
                Pending(it, state.busy, viewModel::discard)
            }
        }
    }
}

@Composable
private fun ConversationRow(space: SpaceEntity, enabled: Boolean, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().chatClick(enabled, onClick).padding(vertical = 14.dp)) {
        Copy(space.title, maxLines = 2)
        Copy(
            when {
                !space.accessible -> "Unavailable / cached"
                space.type == "DIRECT_MESSAGE" -> "Direct message"
                space.type == "GROUP_CHAT" -> "Group conversation"
                else -> "Space"
            },
            small = true,
        )
    }
    Rule()
}

@Composable
private fun Message(message: MessageEntity) {
    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Copy("${message.sender} / ${displayTime(message.createdAt)}", small = true)
        Copy(message.text)
    }
    Rule()
}

@Composable
private fun Pending(message: OutboxEntity, busy: Boolean, discard: (String) -> Unit) {
    var confirm by remember(message.id) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
        Copy("Outbox / ${displayTime(message.createdAt)}", small = true)
        Copy(message.text)
        Copy(message.error ?: "Saved locally. Waiting to send.", small = true)
        if (confirm) {
            Copy("Discard this unsent message?", small = true)
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                Action("Discard", enabled = !busy, onClick = { discard(message.id) })
                Action("Keep", enabled = !busy, onClick = { confirm = false })
            }
        } else {
            Action("Discard...", enabled = !busy, onClick = { confirm = true })
        }
    }
    Rule()
}

@Composable
private fun Footer(state: ChatUiState, viewModel: ChatViewModel) {
    Rule()
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        if (state.connected) {
            Action("Refresh", enabled = !state.busy, onClick = viewModel::refresh)
        } else {
            Action("Connect", enabled = !state.busy, onClick = viewModel::tokenEntry)
        }
        if (state.space != null && state.spaces.find { it.name == state.space }?.accessible == true) {
            Action(
                if (state.thread == null) "Write" else "Reply",
                enabled = !state.busy && (state.thread == null || "/threads/" in state.thread),
                onClick = viewModel::compose,
            )
        }
        if (state.outbox.isNotEmpty()) {
            Action("Retry outbox (${state.outbox.size})", enabled = !state.busy && state.connected, onClick = viewModel::retryOutbox)
        }
    }
}

@Composable
private fun Account(state: ChatUiState, viewModel: ChatViewModel, modifier: Modifier) {
    Column(modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Copy(state.profile?.email ?: "No account connected")
        Copy(
            when (state.signIn) {
                SignIn.REFRESH_TOKEN -> "Signed in. Saved encrypted on this phone and renewed automatically."
                SignIn.ACCESS_TOKEN -> "Signed in. Saved encrypted on this phone, but an access token expires about an hour after Google issues it."
                SignIn.NONE -> "Not signed in. Cached chats remain on this phone."
            },
            small = true,
        )
        Action(if (state.connected) "Replace sign-in" else "Sign in", enabled = !state.busy, onClick = viewModel::tokenEntry)
        Copy("Enter an access token, or for lasting sign-in enter: client_id client_secret refresh_token (separated by spaces). Authorized-user JSON also works.", small = true)
        Copy("Scopes: openid, email, chat.spaces.readonly and chat.messages. Credentials are encrypted with a key that never leaves this phone's Keystore and are sent only to Google's HTTPS endpoints.", small = true)
        Copy("Chats and unsent messages are stored in this app's private database. A refresh reconciles edited and deleted messages in the open conversation.", small = true)
        Copy("To switch accounts, forget this account first. No background notifications or automatic refresh.", small = true)
        if (state.connected) {
            Action("Sign out", enabled = !state.busy, onClick = viewModel::signOut)
        }
        if (state.profile != null) {
            Action("Forget account...", enabled = !state.busy, onClick = viewModel::confirmForget)
        }
    }
}

@Composable
private fun Forget(state: ChatUiState, viewModel: ChatViewModel, onBack: () -> Unit, modifier: Modifier) {
    Column(modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Copy("Forget ${state.profile?.email.orEmpty()}?")
        Copy("This deletes all cached conversations and ${state.outbox.size} unsent messages from this phone. It does not delete messages from Google.")
        Action("Delete local data", enabled = !state.busy, onClick = viewModel::forgetAccount)
        Action("Keep account", enabled = !state.busy, onClick = onBack)
    }
}

@Composable
private fun Editor(state: ChatUiState, viewModel: ChatViewModel, onBack: () -> Unit, modifier: Modifier) {
    val isToken = state.panel == Panel.TOKEN
    // Do not save OAuth credentials in Android's saved-instance-state bundle.
    val input = remember(isToken) { TextFieldState(if (isToken) "" else state.draft) }
    if (!isToken) {
        LaunchedEffect(input) {
            snapshotFlow { input.text.toString() }.collect(viewModel::updateDraft)
        }
    }
    LightTextInputEditor(
        title = if (isToken) "Sign in" else if (state.thread == null) "New message" else "Reply",
        state = input,
        keyboardOptionsFlow = rememberKeyboardOptions(),
        submitLabel = if (isToken) "CONNECT" else "SEND",
        onSubmit = { text ->
            if (!state.busy) {
                if (isToken) {
                    viewModel.connect(text.toString())
                    input.edit { replace(0, length, "") }
                } else {
                    viewModel.queueMessage(text)
                }
            }
        },
        onBack = { if (!state.busy) onBack() },
        singleLine = isToken,
        initialCaps = false,
        modifier = modifier,
    )
}

@Composable
private fun Copy(text: String, small: Boolean = false, maxLines: Int = Int.MAX_VALUE) {
    Text(
        text,
        color = Color.Black,
        fontSize = if (small) 13.sp else 18.sp,
        lineHeight = if (small) 19.sp else 26.sp,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(vertical = 4.dp),
    )
}

@Composable
private fun Action(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    Text(
        label,
        color = Color.Black,
        fontSize = 14.sp,
        modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)
            .chatClick(enabled, onClick).padding(vertical = 14.dp),
    )
}

@Composable
private fun Modifier.chatClick(enabled: Boolean, onClick: () -> Unit): Modifier =
    clickable(
        enabled = enabled,
        interactionSource = remember { MutableInteractionSource() },
        indication = null,
        role = Role.Button,
        onClick = onClick,
    )

@Composable
private fun Rule() {
    Spacer(Modifier.fillMaxWidth().height(1.dp).background(Color.Black))
}

@Preview(name = "1080 x 1240 at 3x", widthDp = 360, heightDp = 413, showBackground = true)
@Composable
private fun ConversationsPreview() {
    LightTheme(colors = ChatColors) {
        Column(Modifier.fillMaxSize().background(Color.White).padding(horizontal = 20.dp)) {
            Text("Chat", color = Color.Black, fontSize = 26.sp, modifier = Modifier.padding(vertical = 16.dp))
            Rule()
            Copy("Conversations / offline cache", small = true)
            ConversationRow(SpaceEntity("spaces/team", "Design team", "SPACE"), true) {}
            ConversationRow(SpaceEntity("spaces/plans", "Weekend plans", "GROUP_CHAT"), true) {}
            ConversationRow(SpaceEntity("spaces/alex", "Alex", "DIRECT_MESSAGE"), true) {}
        }
    }
}
