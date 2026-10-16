package com.jacobrobertson.lightchat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.jacobrobertson.lightchat.data.ChatDatabase
import com.jacobrobertson.lightchat.data.ChatRepository
import com.jacobrobertson.lightchat.data.KtorChatService
import com.jacobrobertson.lightchat.security.EncryptedCredentialStore
import com.jacobrobertson.lightchat.ui.ChatContent
import com.jacobrobertson.lightchat.ui.ChatViewModel
import com.thelightphone.sdk.InitialScreen
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.buildDatabase

@InitialScreen
class ChatScreen(activity: SealedLightActivity) : LightScreen<Unit, ChatViewModel>(activity) {
    override val viewModelClass = ChatViewModel::class.java

    override fun createViewModel(): ChatViewModel {
        val database = lightContext.buildDatabase(ChatDatabase::class.java, "google-chat.db")
        val service = KtorChatService()
        val store = EncryptedCredentialStore(lightContext.dataStore)
        return ChatViewModel(ChatRepository(database, service, store)) {
            service.close()
            database.close()
        }
    }

    @Composable
    override fun Content() {
        val state by viewModel.state.collectAsState()
        ChatContent(state, viewModel, onBack = { goBack() })
    }
}
