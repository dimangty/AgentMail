package io.agentmail

/**
 * Поддерживаемые семейства настольных операционных систем для выбора платформенного
 * поведения приложения.
 *
 * Классификация намеренно укрупнённая: неизвестное значение `os.name` считается
 * Linux, поэтому тип не следует использовать как точный идентификатор дистрибутива.
 */
enum class OsType {
    Mac,
    Windows,
    Linux;

    companion object {
        /** Определяет семейство ОС по системному свойству `os.name` при каждом обращении. */
        val current: OsType
            get() {
                val osName = System.getProperty("os.name").lowercase()
                return when {
                    osName.startsWith("mac") -> OsType.Mac
                    osName.startsWith("win") -> OsType.Windows
                    else -> OsType.Linux
                }
            }
    }
}
