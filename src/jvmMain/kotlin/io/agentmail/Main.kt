package io.agentmail

import androidx.compose.ui.unit.dp
import androidx.compose.runtime.remember
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState

fun main() = application {
    val controller = remember {
        val store = SettingsStore()
        val mailClient = ImapMailClient()
        val summarizer = KoogSummarizer()
        val telegram = TelegramClient()
        val history = DeliveryHistoryStore()
        val monitoring = MonitoringService(store, mailClient, summarizer, telegram, history)
        AppController(store, mailClient, summarizer, telegram, monitoring)
    }

    Window(
        onCloseRequest = {
            controller.close()
            exitApplication()
        },
        title = "AgentMail",
        state = rememberWindowState(width = 1280.dp, height = 860.dp),
    ) {
        AgentMailApp(controller)
    }
}
