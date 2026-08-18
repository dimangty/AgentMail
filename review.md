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

# GitLab issue labels screen

## Goal

Add a button to the existing left rail that opens a screen for applying labels to an existing GitLab issue by URL.

## Requirements

- Keep the current Compose Desktop visual language and dark theme.
- Add lightweight local navigation; do not add a navigation dependency for two screens.
- Keep the current settings screen available from the left rail.
- Replace the free-form task description with a single-line GitLab issue URL field.
- Add a multi-select label picker inspired by the supplied references:
  - compact outlined input;
  - selected labels shown in the control;
  - typing filters the dropdown;
  - dropdown rows show a colored square and label name;
  - available labels: `env:dev`, `env:prod`, `env:sbox`, `env:uat`.
- Preserve selected labels while filtering and allow clicking a selected label again to remove it.
- Add a primary action button that applies the selected labels to the issue through the GitLab API.
- Use only the saved GitLab Base URL and access token from the OS keyring.
- Reject issue URLs whose origin differs from the saved trusted GitLab Base URL before making any request.
- Accept canonical GitLab issue URLs shaped like `https://gitlab.example/group/project/-/issues/123`.
- Add selected labels through GitLab's `add_labels` API without removing existing issue labels.
- Disable duplicate submissions while a request is running and show progress, success, and safe error feedback.
- Keep the issue URL and selected labels local; clear neither after a failed request.
- If GitLab is not configured, explain that Base URL and access token must be saved in Settings.
- Avoid changes to email mention matching, saved settings, and GitLab automation labels.
- Add focused unit tests for label filtering/selection, trusted issue URL parsing, request method/path/body/token handling, and rejection of untrusted URLs without a request.

## Acceptance checks

- The new rail button clearly opens the task screen and shows a selected state.
- A settings rail button returns to the existing screen without resetting its draft.
- The URL field accepts an existing GitLab issue link.
- Label filtering is case-insensitive and preserves catalog order.
- Selecting and deselecting labels works while the dropdown remains usable.
- Empty-result feedback is visible.
- The action is enabled only for a nonblank URL, at least one selected label, saved GitLab configuration, and no request in progress.
- Clicking the action sends one PUT request with `add_labels` and never sends the token to an untrusted origin.
- Existing labels are preserved because `remove_labels` is not sent.
- `./gradlew jvmTest` and `./gradlew compileKotlinJvm` pass.
