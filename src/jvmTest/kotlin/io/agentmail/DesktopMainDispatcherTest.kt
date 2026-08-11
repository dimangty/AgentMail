package io.agentmail

import java.awt.EventQueue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertTrue

/** Проверяет привязку корутинного Main dispatcher десктопного приложения к AWT/Swing EDT. */
class DesktopMainDispatcherTest {
    /**
     * Гарантирует, что переход в [Dispatchers.Main] выполняет блок на Event Dispatch Thread.
     * Это защищает UI-код от незаметной подмены Main dispatcher реализацией, не совместимой с AWT.
     */
    @Test
    fun `desktop main dispatcher runs on awt event thread`() = runBlocking {
        withTimeout(5_000) {
            withContext(Dispatchers.Main) {
                assertTrue(EventQueue.isDispatchThread())
            }
        }
    }
}
