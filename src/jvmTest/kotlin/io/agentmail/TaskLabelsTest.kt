package io.agentmail

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Фиксирует UI-контракт каталога меток: стабильный порядок, предсказуемый поиск
 * и переключение одного элемента без потери остальных выбранных значений.
 */
class TaskLabelsTest {
    @Test
    fun `catalog contains labels in display order`() {
        assertEquals(
            listOf("env:dev", "env:prod", "env:sbox", "env:uat"),
            taskLabelCatalog.map(TaskLabelOption::value),
        )
    }

    @Test
    fun `filter is case insensitive and preserves catalog order`() {
        assertEquals(taskLabelCatalog, filterTaskLabels("ENV:"))
        assertEquals(listOf(TaskLabelOption.ENV_PROD), filterTaskLabels("PrOd"))
    }

    @Test
    fun `blank filter returns catalog and unknown filter returns empty result`() {
        assertEquals(taskLabelCatalog, filterTaskLabels("   "))
        assertTrue(filterTaskLabels("staging").isEmpty())
    }

    @Test
    fun `toggle adds and removes a label without discarding other selections`() {
        val selected = setOf(TaskLabelOption.ENV_UAT)
        val withProd = toggleTaskLabelSelection(selected, TaskLabelOption.ENV_PROD)

        assertEquals(setOf(TaskLabelOption.ENV_UAT, TaskLabelOption.ENV_PROD), withProd)
        assertEquals(selected, toggleTaskLabelSelection(withProd, TaskLabelOption.ENV_PROD))
    }
}
