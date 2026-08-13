package io.agentmail

enum class OsType {
    Mac,
    Windows,
    Linux;

    companion object {
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