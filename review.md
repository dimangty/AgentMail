# GitLab Reviewed Automation

## Request

When a new email is a GitLab merge notification like the supplied example and contains the keyword `merged`, follow its `View it on GitLab` merge-request link and update the related GitLab issue exactly once:

- add the `Reviewed` label;
- remove the `Merge Request` label;
- retain the existing Telegram notification behavior and its exactly-once safeguards.

Example notification:

```text
Merge request !138 was merged

Branches: feature/issue-233-2 to develop

Author: Ignat Mustafaev
Assignee: Ignat Mustafaev
Reviewer: Dmitriy Bykov
-
View it on GitLab.
```

## Implementation Constraints

- Process merge notifications independently of the configured mention tag, because the example has no mention.
- Preserve the `View it on GitLab` hyperlink while parsing HTML mail.
- Never send a GitLab token to a host supplied only by email. Add a trusted GitLab Base URL setting and require the link to have the same origin.
- Store the GitLab access token in the existing OS keyring, never Preferences or logs.
- Resolve the issue from the authoritative merge request `source_branch`; support the demonstrated `issue-233-2` naming convention.
- Verify through the GitLab API that the referenced merge request is actually merged before updating the issue.
- Use the GitLab Issues API to add `Reviewed` and remove `Merge Request` idempotently.
- Persist a separate per-email GitLab-action status in SQLite. A confirmed success blocks future execution; failures may retry because the label operation is idempotent.
- Keep changes minimal and compatible with the existing Kotlin/JVM, Ktor, SQLite, Compose Desktop architecture.
- Add focused unit/HTTP contract tests and update README documentation.
- Do not modify the pre-existing untracked `src/jvmTest/kotlin/io/agentmail/DesktopMainDispatcherTest.kt`.

## Review Focus

- Accidental token disclosure or requests to an untrusted host.
- Incorrect matching of non-merged mail or unrelated links.
- Incorrect project/MR/issue parsing, especially `feature/issue-233-2`.
- Duplicate label actions after restart or polling overlap.
- Cursor behavior when GitLab calls fail.
- Regression in existing Telegram delivery and secret persistence.

## Real Notification Regression

The first live test did not move issue `#1` from `Merge Request` to `Reviewed`. The received Gmail message renders this content:

```text
Merge request !2 was merged

Branches: features/issue-1 to master
...
View it on GitLab.
```

The durable `gitlab_action_history` table remains empty, so the notification was rejected before any GitLab API reservation or request. In this real GitLab notification, the canonical merge line is the first nonblank body line; the email subject is not guaranteed to equal that line. The current parser only applies `matchEntire` to `MailMessage.subject`, which explains the miss.

The fix must:

- accept the exact canonical merge line when it is either the complete trimmed subject or the first nonblank visible body line;
- continue rejecting forwarded/replied messages where the phrase occurs later in quoted body content or inside a prefixed line;
- keep MR IID agreement with the trusted `View it on GitLab` URL;
- cover the exact live format with `subject = "Test"`, first body line `Merge request !2 was merged`, branch `features/issue-1`, and MR link ending in `/2`;
- retain the authoritative GitLab API `state == merged` check and source-branch issue resolution;
- document that an email already passed by the IMAP cursor cannot be replayed automatically, so verification after the fix requires a new merge notification unless a safe explicit replay facility is added.
