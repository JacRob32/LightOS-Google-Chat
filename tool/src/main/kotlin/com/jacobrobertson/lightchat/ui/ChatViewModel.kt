package com.jacobrobertson.lightchat.ui

import android.util.Log
import androidx.lifecycle.viewModelScope
import com.jacobrobertson.lightchat.data.ChatRepository
import com.jacobrobertson.lightchat.data.MessageEntity
import com.jacobrobertson.lightchat.data.OutboxEntity
import com.jacobrobertson.lightchat.data.ProfileEntity
import com.jacobrobertson.lightchat.data.SignIn
import com.jacobrobertson.lightchat.data.SpaceEntity
import com.jacobrobertson.lightchat.data.userFacingError
import com.thelightphone.sdk.LightViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class Panel { CHATS, ACCOUNT, TOKEN, COMPOSE, FORGET }

data class ChatUiState(
    val profile: ProfileEntity? = null,
    val spaces: List<SpaceEntity> = emptyList(),
    val messages: List<MessageEntity> = emptyList(),
    val outbox: List<OutboxEntity> = emptyList(),
    val connected: Boolean = false,
    val signIn: SignIn = SignIn.NONE,
    val initialized: Boolean = false,
    val busy: Boolean = false,
    val error: String? = null,
    val notice: String? = null,
    val panel: Panel = Panel.CHATS,
    val space: String? = null,
    val thread: String? = null,
    val draft: String = "",
)

class ChatViewModel(
    private val repository: ChatRepository,
    private val closeResources: () -> Unit,
) : LightViewModel<Unit>() {
    private val _state = MutableStateFlow(ChatUiState())
    val state = _state.asStateFlow()
    private var operation: Job? = null
    private val drafts = mutableMapOf<Pair<String?, String?>, String>()

    init {
        viewModelScope.launch {
            combine(repository.profile, repository.spaces, repository.messages, repository.outbox) {
                    profile, spaces, messages, outbox ->
                ChatUiState(profile = profile, spaces = spaces, messages = messages, outbox = outbox)
            }.catch { failure ->
                report(failure)
            }.collect { cache ->
                _state.update {
                    it.copy(
                        profile = cache.profile,
                        spaces = cache.spaces,
                        messages = cache.messages,
                        outbox = cache.outbox,
                        initialized = true,
                    )
                }
            }
        }
        viewModelScope.launch {
            repository.restore()
        }
        viewModelScope.launch {
            repository.connected.collect { connected -> _state.update { it.copy(connected = connected) } }
        }
        viewModelScope.launch {
            repository.signIn.collect { signIn -> _state.update { it.copy(signIn = signIn) } }
        }
    }

    fun account() = _state.update { it.copy(panel = Panel.ACCOUNT, error = null, notice = null) }
    fun tokenEntry() = _state.update { it.copy(panel = Panel.TOKEN, error = null, notice = null) }
    fun confirmForget() = _state.update { it.copy(panel = Panel.FORGET) }
    fun dismissError() = _state.update { it.copy(error = null, notice = null) }

    fun connect(token: CharSequence) = runOperation {
        val saved = repository.connect(token.toString())
        _state.update { it.copy(panel = Panel.CHATS, notice = "Connected. Refreshing conversations...") }
        repository.refreshSpaces()
        _state.update {
            it.copy(
                notice = if (saved) "Connected. Your sign-in is saved on this phone."
                else "Connected, but this sign-in could not be saved. You will need to reconnect next time.",
            )
        }
    }

    fun signOut() = runOperation {
        repository.signOut()
        _state.update { it.copy(notice = "Signed out. Cached chats are still available.") }
    }

    fun openSpace(space: String) {
        _state.update {
            it.copy(space = space, thread = null, draft = drafts[space to null].orEmpty(), error = null, notice = null)
        }
        if (_state.value.connected && _state.value.spaces.find { it.name == space }?.accessible == true) {
            runOperation { repository.refreshMessages(space) }
        }
    }

    fun openThread(thread: String) = _state.update {
        it.copy(thread = thread, draft = drafts[it.space to thread].orEmpty(), error = null, notice = null)
    }

    fun refresh() = runOperation {
        val space = _state.value.space
        repository.refreshSpaces()
        if (space != null) repository.refreshMessages(space)
        _state.update { it.copy(notice = "Up to date.") }
    }

    fun compose() = _state.update { it.copy(panel = Panel.COMPOSE, error = null, notice = null) }
    fun updateDraft(value: String) {
        val current = _state.value
        drafts[current.space to current.thread] = value
        _state.update { it.copy(draft = value) }
    }

    fun queueMessage(text: CharSequence) = runOperation {
        val current = _state.value
        val space = checkNotNull(current.space) { "Choose a conversation first." }
        repository.enqueue(space, current.thread, text.toString())
        drafts.remove(current.space to current.thread)
        _state.update { it.copy(panel = Panel.CHATS, draft = "", notice = "Message saved to outbox.") }
        if (_state.value.connected) {
            repository.flushOutbox()
            _state.update { it.copy(notice = "Message sent.") }
        }
    }

    fun retryOutbox() = runOperation {
        repository.flushOutbox()
        _state.update { it.copy(notice = "Outbox sent.") }
    }

    fun discard(id: String) = runOperation { repository.discard(id) }

    fun forgetAccount() = runOperation {
        repository.forgetAccount()
        drafts.clear()
        _state.update { ChatUiState(initialized = true, busy = it.busy, panel = Panel.ACCOUNT) }
    }

    override fun onBackPressed(): Boolean {
        val current = _state.value
        if (current.busy) return true
        when {
            current.panel == Panel.TOKEN || current.panel == Panel.FORGET ->
                _state.update { it.copy(panel = Panel.ACCOUNT, error = null) }
            current.panel != Panel.CHATS -> _state.update { it.copy(panel = Panel.CHATS, error = null) }
            current.thread != null -> _state.update {
                it.copy(thread = null, draft = drafts[it.space to null].orEmpty(), error = null)
            }
            current.space != null -> _state.update { it.copy(space = null, draft = "", error = null) }
            else -> return false
        }
        return true
    }

    private fun runOperation(action: suspend () -> Unit) {
        if (operation?.isActive == true) return
        _state.update { it.copy(busy = true, error = null, notice = null) }
        operation = viewModelScope.launch {
            try {
                action()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                report(failure)
            } finally {
                _state.update { it.copy(busy = false) }
            }
        }
    }

    private fun report(failure: Throwable) {
        // Never log response bodies, token values, or message text.
        Log.e("LightChat", "Chat operation failed: ${failure::class.simpleName}")
        _state.update { it.copy(error = userFacingError(failure), initialized = true) }
    }

    override fun onCleared() {
        operation?.cancel()
        closeResources()
        super.onCleared()
    }
}
