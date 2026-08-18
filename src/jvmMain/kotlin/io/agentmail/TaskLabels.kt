package io.agentmail

internal enum class TaskLabelOption(val value: String) {
    ENV_DEV("env:dev"),
    ENV_PROD("env:prod"),
    ENV_SBOX("env:sbox"),
    ENV_UAT("env:uat"),
}

internal val taskLabelCatalog = TaskLabelOption.entries.toList()

internal fun filterTaskLabels(query: String): List<TaskLabelOption> {
    val normalizedQuery = query.trim()
    return if (normalizedQuery.isEmpty()) {
        taskLabelCatalog
    } else {
        taskLabelCatalog.filter { it.value.contains(normalizedQuery, ignoreCase = true) }
    }
}

internal fun toggleTaskLabelSelection(
    selected: Set<TaskLabelOption>,
    label: TaskLabelOption,
): Set<TaskLabelOption> = if (label in selected) selected - label else selected + label
