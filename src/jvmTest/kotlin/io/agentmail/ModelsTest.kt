package io.agentmail

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Проверяет провайдер-зависимые правила полноты и миграции конфигурации. */
class ModelsTest {
    @Test
    fun `ollama does not require llm api key`() {
        val settings = validSettings()
        val secrets = Secrets(mailPassword = "mail", llmApiKey = "", telegramBotToken = "telegram")

        assertTrue(settings.validationErrors(secrets).isEmpty())
        assertTrue(secrets.isCompleteFor(settings))
    }

    @Test
    fun `ollama requires selected model`() {
        val settings = validSettings().copy(ollamaModel = "")
        val secrets = Secrets(mailPassword = "mail", llmApiKey = "", telegramBotToken = "telegram")

        assertTrue(settings.validationErrors(secrets).contains("Выберите установленную модель Ollama"))
    }

    @Test
    fun `custom provider requires https model and api key`() {
        val settings = validSettings().copy(
            llmProvider = LlmProviderType.CUSTOM,
            customBaseUrl = "http://llm.company.test",
            customModel = "",
        )
        val secrets = Secrets(mailPassword = "mail", llmApiKey = "", telegramBotToken = "telegram")

        val errors = settings.validationErrors(secrets)

        assertTrue(errors.any { it.contains("HTTPS") })
        assertTrue(errors.contains("Укажите ID корпоративной модели"))
        assertTrue(errors.contains("Сохраните API key корпоративной модели"))
    }

    @Test
    fun `legacy qwen provider migrates to ollama`() {
        assertEquals(LlmProviderType.OLLAMA, storedLlmProvider("QWEN"))
        assertEquals(LlmProviderType.CUSTOM, storedLlmProvider("CUSTOM"))
    }

    @Test
    fun `ollama model must belong to current local catalog`() {
        val settings = validSettings()

        assertTrue(settings.hasAvailableOllamaModel(listOf("qwen3:8b")))
        assertTrue(!settings.hasAvailableOllamaModel(listOf("another-model")))
    }

    @Test
    fun `configured GitLab requires trusted https url and token`() {
        val settings = validSettings().copy(gitLabBaseUrl = "http://gitlab.company.test/path")
        val secrets = Secrets(mailPassword = "mail", llmApiKey = "", telegramBotToken = "telegram")

        val errors = settings.validationErrors(secrets)

        assertTrue(errors.any { it.contains("GitLab Base URL") })
        assertTrue(errors.contains("Сохраните GitLab access token"))
        assertTrue(!secrets.isCompleteFor(settings))
    }

    @Test
    fun `blank GitLab url keeps automation optional`() {
        val settings = validSettings()
        val secrets = Secrets(mailPassword = "mail", llmApiKey = "", telegramBotToken = "telegram")

        assertTrue(settings.validationErrors(secrets).isEmpty())
        assertTrue(secrets.isCompleteFor(settings))
    }

    @Test
    fun `normalizes equivalent GitLab origins`() {
        assertEquals(
            "https://gitlab.company.test",
            validSettings().copy(gitLabBaseUrl = " HTTPS://GITLAB.company.test:443/ ").normalized().gitLabBaseUrl,
        )
        assertEquals("https://[::1]", "https://[::1]".canonicalGitLabOrigin())
        assertNull("https://gitlab.company.test:0".canonicalGitLabOrigin())
        assertNull("https://gitlab.company.test:99999".canonicalGitLabOrigin())
        assertNull("https://gitlab.company.test:".canonicalGitLabOrigin())
    }

    private fun validSettings() = AppSettings(
        email = "user@company.test",
        imapUsername = "user@company.test",
        imapHost = "imap.company.test",
        tag = "@user",
        ollamaModel = "qwen3:8b",
        telegramChatId = "42",
    )
}
