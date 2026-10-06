plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jlleitschuh.gradle.ktlint")
}

group = "no.novari"
version = providers.gradleProperty("version").getOrElse("1.0-SNAPSHOT")

repositories {
    mavenCentral()
}

kotlin {
    jvmToolchain(25)
}

ktlint {
    version.set("1.8.0")
}

// ktlint 1.8.0 embeds the Kotlin 2.2 compiler and breaks when the Kotlin Gradle plugin aligns it to a newer version.
afterEvaluate {
    configurations.matching { it.name.startsWith("ktlint") }.configureEach {
        resolutionStrategy.eachDependency {
            if (requested.group == "org.jetbrains.kotlin") {
                useVersion("2.2.21")
            }
            if (requested.group == "ch.qos.logback") {
                useVersion("1.5.38")
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
