import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("multiplatform") version "2.3.21"
    kotlin("plugin.compose") version "2.3.21"
    kotlin("plugin.serialization") version "2.3.21"
    id("org.jetbrains.compose") version "1.11.1"
}

group = "io.agentmail"
version = "1.0.0"

kotlin {
    jvmToolchain(17)
    jvm()

    sourceSets {
        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.components.resources)
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.10.0")
        }
        jvmMain.dependencies {
            implementation(compose.desktop.currentOs)
            implementation("ai.koog:prompt-executor-openai-client:1.1.1")
            implementation("ai.koog:http-client-ktor:1.1.1")
            implementation("io.ktor:ktor-client-cio:3.3.3")
            implementation("io.ktor:ktor-client-content-negotiation:3.3.3")
            implementation("io.ktor:ktor-serialization-kotlinx-json:3.3.3")
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.10.2")
            implementation("jakarta.mail:jakarta.mail-api:2.1.5")
            implementation("org.eclipse.angus:angus-mail:2.0.5")
            implementation("org.jsoup:jsoup:1.23.1")
            implementation("com.github.javakeyring:java-keyring:1.0.4")
            implementation("org.xerial:sqlite-jdbc:3.50.3.0")
            implementation("org.slf4j:slf4j-simple:2.0.17")
        }
        jvmTest.dependencies {
            implementation(kotlin("test"))
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
            implementation("io.ktor:ktor-client-mock:3.3.3")
        }
    }
}

compose.desktop {
    application {
        mainClass = "io.agentmail.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "AgentMail"
            packageVersion = "1.0.0"
            description = "Corporate email mention notifier"
            vendor = "AgentMail"
            modules("java.naming", "java.sql", "java.security.sasl")
        }
    }
}
