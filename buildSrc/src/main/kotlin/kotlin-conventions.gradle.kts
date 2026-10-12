plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jlleitschuh.gradle.ktlint")
}

private val libs = versionCatalogs.named("libs")

private fun version(alias: String) = libs.findVersion(alias).get().requiredVersion

group = "no.novari"
version = providers.gradleProperty("version").getOrElse("1.0-SNAPSHOT")

repositories {
    mavenCentral()
}

kotlin {
    jvmToolchain(25)
}

ktlint {
    version.set(version("ktlint-cli"))
}

// ktlint embeds the Kotlin 2.2 compiler and breaks when the Kotlin Gradle plugin aligns it to a newer version.
afterEvaluate {
    configurations.matching { it.name.startsWith("ktlint") }.configureEach {
        resolutionStrategy.eachDependency {
            if (requested.group == "org.jetbrains.kotlin") {
                useVersion(version("ktlint-kotlin"))
            }
            if (requested.group == "ch.qos.logback") {
                useVersion(version("logback"))
            }
        }
    }
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

tasks.named("check") {
    dependsOn("ktlintCheck")
}
