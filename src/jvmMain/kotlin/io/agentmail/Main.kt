package io.agentmail

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.MenuBar
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import io.agentmail.agentmail.generated.resources.Res
import io.agentmail.agentmail.generated.resources.tray_icon_mac
import io.agentmail.agentmail.generated.resources.tray_icon_win
import java.awt.Desktop
import java.awt.EventQueue
import java.awt.SystemTray
import org.jetbrains.compose.resources.painterResource
import java.awt.Dimension

private val minimumWindowSize = Dimension(1280, 860)

/**
 * Запускает desktop-приложение и связывает время жизни Compose, окна, tray и
 * контроллера. Закрытие окна скрывает или сворачивает интерфейс, тогда как полное
 * освобождение ресурсов происходит только при завершении `application`.
 */
fun main() = application {
    val traySupported = remember { SystemTray.isSupported() }
    var windowVisible by remember { mutableStateOf(true) }
    // Счётчик представляет событие «показать», поэтому повторный запрос с true всё равно активирует эффект фокуса.
    var showRequest by remember { mutableIntStateOf(0) }
    val windowState = rememberWindowState(
        position = WindowPosition.Aligned(Alignment.Center),
        width = minimumWindowSize.width.dp,
        height = minimumWindowSize.height.dp
    )
    // На macOS системная команда Quit перехватывается только при наличии tray, чтобы оставить агент работающим.
    val macQuitDesktop = remember(traySupported) {
        runCatching {
            if (traySupported && OsType.current == OsType.Mac && Desktop.isDesktopSupported()) {
                Desktop.getDesktop().takeIf { it.isSupported(Desktop.Action.APP_QUIT_HANDLER) }
            } else {
                null
            }
        }.getOrNull()
    }
    // Один граф сервисов живёт столько же, сколько корневая композиция application.
    val controller = remember {
        val store = SettingsStore()
        val mailClient = ImapMailClient()
        val summarizer = KoogSummarizer()
        val telegram = TelegramClient()
        val gitLab = GitLabClient()
        val ollamaModels = OllamaModelsClient()
        val history = DeliveryHistoryStore()
        val monitoring = MonitoringService(store, mailClient, summarizer, telegram, gitLab, history)
        AppController(store, mailClient, summarizer, telegram, gitLab, monitoring, ollamaModels)
    }

    DisposableEffect(controller) {
        // Явный выход удаляет композицию и через этот callback последовательно закрывает фоновые ресурсы.
        onDispose(controller::close)
    }

    DisposableEffect(macQuitDesktop) {
        // Нативный Quit превращается в скрытие окна; фактический выход остаётся пунктом меню tray.
        val handlerInstalled = runCatching {
            macQuitDesktop?.setQuitHandler { _, response ->
                response.cancelQuit()
                // Изменение Compose-state откладывается на AWT event queue, обслуживающую desktop UI.
                EventQueue.invokeLater {
                    windowVisible = false
                }
            }
            macQuitDesktop != null
        }.getOrDefault(false)

        onDispose {
            // Обработчик снимается до уничтожения приложения, чтобы Desktop не удерживал Compose-state.
            if (handlerInstalled) {
                runCatching { macQuitDesktop?.setQuitHandler(null) }
            }
        }
    }

    // Восстанавливает окно из обоих состояний и отдельно сигнализирует о необходимости поднять его наверх.
    val showWindow = {
        windowVisible = true
        windowState.isMinimized = false
        showRequest++
        Unit
    }

    if (traySupported) {
        Tray(
            icon = painterResource(
                when (OsType.current) {
                    OsType.Mac -> Res.drawable.tray_icon_mac
                    OsType.Windows, OsType.Linux -> Res.drawable.tray_icon_win
                }
            ),
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
            // Пока процесс доступен через tray, окно можно скрыть; без tray оно остаётся доступным в панели задач.
            if (traySupported) {
                windowVisible = false
            } else {
                windowState.isMinimized = true
            }
        },
        title = "AgentMail",
        state = windowState,
        icon = painterResource(Res.drawable.tray_icon_win),
        visible = windowVisible,
    ) {
        window.minimumSize = minimumWindowSize
        window.rootPane.putClientProperty("apple.awt.windowAppearance", "NSAppearanceNameDarkAqua")
        if (!traySupported) {
            MenuBar {
                Menu("AgentMail") {
                    Item("Выйти", onClick = ::exitApplication)
                }
            }
        }
        LaunchedEffect(showRequest) {
            // Фокус запрашивается после применения Compose-состояния видимости, когда нативное окно уже показано.
            if (windowVisible) {
                window.toFront()
                window.requestFocus()
                if (OsType.current == OsType.Mac) {
                    Desktop.getDesktop().requestForeground(true)
                }
            }
        }
        AgentMailApp(controller)
    }
}
