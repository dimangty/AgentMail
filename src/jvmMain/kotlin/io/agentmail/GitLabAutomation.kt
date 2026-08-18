package io.agentmail

import java.net.URI
import java.util.Locale

/**
 * Координаты merge request внутри GitLab-проекта.
 *
 * [projectPath] хранится в декодированном виде с `/` между группами, а
 * [mergeRequestIid] является внутренним номером merge request в этом проекте.
 */
data class GitLabMergeRequestRef(
    val projectPath: String,
    val mergeRequestIid: Int,
)

/**
 * Извлекает ссылку на завершённый merge request из уведомления GitLab.
 *
 * Парсер работает fail-closed: любое расхождение между текстом письма и ссылкой,
 * неоднозначность ссылок или неподтверждённый origin дают `null`. Это не позволяет
 * использовать пересланный или подделанный текст письма как основание для запроса
 * с GitLab-токеном.
 */
object GitLabMergeNotificationParser {
    private val mergedSubject = Regex("Merge request !(\\d+) was merged", RegexOption.IGNORE_CASE)
    private val mergeRequestPath = Regex("^/(.+)/-/merge_requests/(\\d+)/?$")

    /**
     * Возвращает единственную доверенную ссылку на слитый merge request либо `null`.
     *
     * Фраза о слиянии должна целиком составлять тему или первую непустую строку тела,
     * а IID в ней должен совпасть с IID в ссылке. Подходящая ссылка обязана иметь
     * ожидаемый текст и origin, равный [gitLabBaseUrl].
     */
    fun parse(message: MailMessage, gitLabBaseUrl: String): GitLabMergeRequestRef? {
        if (gitLabBaseUrl.isBlank()) return null
        val mergeLine = mergedSubject.matchEntire(message.subject.trim())
            ?: message.body.lineSequence()
                .map(String::trim)
                .firstOrNull(String::isNotBlank)
                ?.let(mergedSubject::matchEntire)
        val mailIid = mergeLine
            ?.groupValues?.get(1)?.toIntOrNull() ?: return null
        val base = runCatching { URI(gitLabBaseUrl) }.getOrNull() ?: return null
        val matches = message.links.mapNotNull { link ->
            if (link.text.trim().trimEnd('.').trim().lowercase(Locale.ROOT) != "view it on gitlab") return@mapNotNull null
            parseTrustedLink(link.href, base, mailIid)
        }.distinct()
        return matches.singleOrNull()
    }

    private fun parseTrustedLink(href: String, base: URI, mailIid: Int): GitLabMergeRequestRef? {
        val link = runCatching { URI(href) }.getOrNull() ?: return null
        // User info, query и fragment не участвуют в координатах MR и только расширяют
        // поверхность для неоднозначной интерпретации URL или утечки данных.
        if (!link.isAbsolute || link.userInfo != null || link.query != null || link.fragment != null) return null
        if (!sameOrigin(base, link)) return null
        val rawPath = link.rawPath ?: return null
        // Проверяем сырой путь до URI-декодирования: закодированные `/` и `\` способны
        // изменить границы сегментов после того, как origin уже признан доверенным.
        if (Regex("%(?:2f|5c)", RegexOption.IGNORE_CASE).containsMatchIn(rawPath)) return null
        val match = mergeRequestPath.matchEntire(link.path) ?: return null
        val projectPath = match.groupValues[1]
        val iid = match.groupValues[2].toIntOrNull() ?: return null
        val segments = projectPath.split('/')
        if (iid != mailIid || segments.any { it.isBlank() || it == "." || it == ".." }) return null
        return GitLabMergeRequestRef(projectPath, iid)
    }

}

/**
 * Координаты issue или work item внутри GitLab-проекта.
 *
 * Оба вида страниц обслуживаются одним API issues, поэтому наружу возвращается
 * единая ссылка с project path и положительным IID задачи.
 */
data class GitLabIssueRef(
    val projectPath: String,
    val issueIid: Int,
)

/**
 * Проверяет пользовательскую ссылку на issue/work item и извлекает её координаты.
 *
 * Результат выдаётся только для абсолютного URL с доверенным origin и канонической
 * структурой пути. Все неизвестные, неоднозначные и потенциально обходные формы
 * отклоняются fail-closed до того, как вызывающий код сможет отправить GitLab-токен.
 */
object GitLabIssueUrlParser {
    private val issuePath = Regex("^/(.+)/-/(?:issues|work_items)/(\\d+)/?$")

    /**
     * Возвращает координаты задачи, если [issueUrl] принадлежит настроенному
     * [gitLabBaseUrl], иначе возвращает `null` без попыток исправить входной URL.
     */
    fun parse(issueUrl: String, gitLabBaseUrl: String): GitLabIssueRef? {
        val canonicalBase = gitLabBaseUrl.canonicalGitLabOrigin() ?: return null
        val base = runCatching { URI(canonicalBase) }.getOrNull() ?: return null
        val link = runCatching { URI(issueUrl.trim()) }.getOrNull() ?: return null
        if (!link.isAbsolute || link.userInfo != null || link.query != null || link.fragment != null) return null
        if (link.rawAuthority?.endsWith(':') != false || link.port != -1 && link.port !in 1..65535) return null
        if (!sameOrigin(base, link)) return null
        val rawPath = link.rawPath ?: return null
        // rawPath сохраняет escape-последовательности и позволяет запретить скрытые
        // разделители до сопоставления уже декодированного link.path.
        if (Regex("%(?:2f|5c)", RegexOption.IGNORE_CASE).containsMatchIn(rawPath)) return null
        val match = issuePath.matchEntire(link.path) ?: return null
        val projectPath = match.groupValues[1]
        val issueIid = match.groupValues[2].toIntOrNull() ?: return null
        val segments = projectPath.split('/')
        if (
            issueIid <= 0 ||
            segments.size < 2 ||
            segments.first().equals("groups", ignoreCase = true) ||
            segments.any { it.isBlank() || it == "." || it == ".." }
        ) return null
        return GitLabIssueRef(projectPath, issueIid)
    }
}

/**
 * Сравнивает origin строго по схеме, хосту и эффективному порту.
 * Совпадения префикса хоста недостаточно: `gitlab.example.attacker.test` не должен
 * получить доверие и токен домена `gitlab.example`.
 */
private fun sameOrigin(first: URI, second: URI): Boolean =
    first.scheme.equals(second.scheme, ignoreCase = true) &&
        first.host.equals(second.host, ignoreCase = true) &&
        effectivePort(first) == effectivePort(second)

/** Нормализует неуказанный порт, чтобы явный `:443` был эквивалентен HTTPS по умолчанию. */
private fun effectivePort(uri: URI): Int = when {
    uri.port >= 0 -> uri.port
    uri.scheme.equals("https", ignoreCase = true) -> 443
    else -> 80
}

/**
 * Извлекает IID связанной задачи из принятого соглашения об имени source branch.
 *
 * Сегмент `issue-<iid>` или `issue-<iid>-<номер>` должен быть единственным
 * совпадением и находиться в конце пути ветки; неоднозначные имена дают `null`.
 */
object GitLabIssueBranchParser {
    private val issueSegment = Regex("^issue-(\\d+)(?:-\\d+)?$", RegexOption.IGNORE_CASE)

    /** Возвращает положительный IID задачи из [sourceBranch] либо `null`. */
    fun issueIid(sourceBranch: String): Int? = sourceBranch.split('/')
        .mapIndexedNotNull { index, segment ->
            issueSegment.matchEntire(segment)?.groupValues?.get(1)?.toIntOrNull()?.let { index to it }
        }
        .singleOrNull()
        ?.takeIf { (index, iid) -> index == sourceBranch.split('/').lastIndex && iid > 0 }
        ?.second
}
