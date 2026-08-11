package io.agentmail

import java.net.URI
import java.util.Locale

data class GitLabMergeRequestRef(
    val projectPath: String,
    val mergeRequestIid: Int,
)

object GitLabMergeNotificationParser {
    private val mergedSubject = Regex("Merge request !(\\d+) was merged", RegexOption.IGNORE_CASE)
    private val mergeRequestPath = Regex("^/(.+)/-/merge_requests/(\\d+)/?$")

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
        if (!link.isAbsolute || link.userInfo != null || link.query != null || link.fragment != null) return null
        if (!sameOrigin(base, link)) return null
        val rawPath = link.rawPath ?: return null
        if (Regex("%(?:2f|5c)", RegexOption.IGNORE_CASE).containsMatchIn(rawPath)) return null
        val match = mergeRequestPath.matchEntire(link.path) ?: return null
        val projectPath = match.groupValues[1]
        val iid = match.groupValues[2].toIntOrNull() ?: return null
        val segments = projectPath.split('/')
        if (iid != mailIid || segments.any { it.isBlank() || it == "." || it == ".." }) return null
        return GitLabMergeRequestRef(projectPath, iid)
    }

    private fun sameOrigin(first: URI, second: URI): Boolean =
        first.scheme.equals(second.scheme, ignoreCase = true) &&
            first.host.equals(second.host, ignoreCase = true) &&
            effectivePort(first) == effectivePort(second)

    private fun effectivePort(uri: URI): Int = when {
        uri.port >= 0 -> uri.port
        uri.scheme.equals("https", ignoreCase = true) -> 443
        else -> 80
    }
}

object GitLabIssueBranchParser {
    private val issueSegment = Regex("^issue-(\\d+)(?:-\\d+)?$", RegexOption.IGNORE_CASE)

    fun issueIid(sourceBranch: String): Int? = sourceBranch.split('/')
        .mapIndexedNotNull { index, segment ->
            issueSegment.matchEntire(segment)?.groupValues?.get(1)?.toIntOrNull()?.let { index to it }
        }
        .singleOrNull()
        ?.takeIf { (index, iid) -> index == sourceBranch.split('/').lastIndex && iid > 0 }
        ?.second
}
