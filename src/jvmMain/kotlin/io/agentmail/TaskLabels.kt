package io.agentmail

/**
 * Разрешённый каталог меток окружения, доступных для ручного добавления к задаче.
 * [value] передаётся в GitLab без преобразования регистра или отображаемого имени.
 */
internal enum class TaskLabelOption(val value: String) {
    ENV_DEV("env:dev"),
    ENV_PROD("env:prod"),
    ENV_SBOX("env:sbox"),
    ENV_UAT("env:uat"),
}

/** Стабильный порядок меток для отображения и результатов фильтрации. */
internal val taskLabelCatalog = TaskLabelOption.entries.toList()

/**
 * Фильтрует каталог по регистронезависимому вхождению нормализованного [query].
 * Пустой запрос возвращает весь каталог в исходном порядке.
 */
internal fun filterTaskLabels(query: String): List<TaskLabelOption> {
    val normalizedQuery = query.trim()
    return if (normalizedQuery.isEmpty()) {
        taskLabelCatalog
    } else {
        taskLabelCatalog.filter { it.value.contains(normalizedQuery, ignoreCase = true) }
    }
}

/**
 * Возвращает новый набор, переключая только [label] и сохраняя остальные выбранные метки.
 * Исходный [selected] не изменяется.
 */
internal fun toggleTaskLabelSelection(
    selected: Set<TaskLabelOption>,
    label: TaskLabelOption,
): Set<TaskLabelOption> = if (label in selected) selected - label else selected + label
