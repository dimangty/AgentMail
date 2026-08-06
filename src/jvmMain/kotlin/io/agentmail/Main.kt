package io.agentmail

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.MenuBar
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import io.agentmail.agentmail.generated.resources.Res
import io.agentmail.agentmail.generated.resources.tray_icon
import java.awt.Desktop
import java.awt.EventQueue
import java.awt.SystemTray
import org.jetbrains.compose.resources.painterResource

fun main() = application {
    val traySupported = remember { SystemTray.isSupported() }
    var windowVisible by remember { mutableStateOf(true) }
    var showRequest by remember { mutableIntStateOf(0) }
    val windowState = rememberWindowState(width = 1280.dp, height = 860.dp)
    val macQuitDesktop = remember(traySupported) {
        runCatching {
            if (
                traySupported &&
                System.getProperty("os.name").startsWith("Mac", ignoreCase = true) &&
                Desktop.isDesktopSupported()
            ) {
                Desktop.getDesktop().takeIf { it.isSupported(Desktop.Action.APP_QUIT_HANDLER) }
            } else {
                null
            }
        }.getOrNull()
    }
    val controller = remember {
        val store = SettingsStore()
        val mailClient = ImapMailClient()
        val summarizer = KoogSummarizer()
        val telegram = TelegramClient()
        val ollamaModels = OllamaModelsClient()
        val history = DeliveryHistoryStore()
        val monitoring = MonitoringService(store, mailClient, summarizer, telegram, history)
        AppController(store, mailClient, summarizer, telegram, monitoring, ollamaModels)
    }

    DisposableEffect(controller) {
        onDispose(controller::close)
    }

    DisposableEffect(macQuitDesktop) {
        val handlerInstalled = runCatching {
            macQuitDesktop?.setQuitHandler { _, response ->
                response.cancelQuit()
                EventQueue.invokeLater {
                    windowVisible = false
                }
            }
            macQuitDesktop != null
        }.getOrDefault(false)

        onDispose {
            if (handlerInstalled) {
                runCatching { macQuitDesktop?.setQuitHandler(null) }
            }
        }
    }

    val showWindow = {
        windowVisible = true
        windowState.isMinimized = false
        showRequest++
        Unit
    }

    if (traySupported) {
        Tray(
            icon = painterResource(Res.drawable.tray_icon),
            tooltip = "AgentMail",
            onAction = showWindow,
            menu = {
                Item("Открыть AgentMail", onClick = showWindow)
                Separator()
                Item("Выйти", onClick = ::exitApplication)
            },
        )
    }

    Window(
        onCloseRequest = {
            if (traySupported) {
                windowVisible = false
            } else {
                windowState.isMinimized = true
            }
        },
        title = "AgentMail",
        state = windowState,
        visible = windowVisible,
    ) {
        if (!traySupported) {
            MenuBar {
                Menu("AgentMail") {
                    Item("Выйти", onClick = ::exitApplication)
                }
            }
        }
        LaunchedEffect(showRequest) {
            if (windowVisible) {
                window.toFront()
                window.requestFocus()
            }
        }
        AgentMailApp(controller)
    }
}
