import com.github.benmanes.gradle.versions.updates.DependencyUpdatesTask

plugins {
    base
    id("io.github.ben-manes.versions")
    id("org.jlleitschuh.gradle.ktlint")
}

repositories {
    mavenCentral()
}

dependencyLocking {
    lockAllConfigurations()
}

ktlint {
    version.set(libs.versions.ktlint.cli)
}

configurations.matching { it.name.startsWith("ktlint") }.configureEach {
    resolutionStrategy.eachDependency {
        if (requested.group == "ch.qos.logback") {
            useVersion(libs.versions.logback.get())
        }
    }
}

fun isNonStable(version: String): Boolean {
    val stableKeyword = listOf("RELEASE", "FINAL", "GA").any { version.uppercase().contains(it) }
    val regex = "^[0-9,.v-]+(-r)?$".toRegex()
    val isStable = stableKeyword || regex.matches(version)
    return !isStable
}

tasks.named<DependencyUpdatesTask>("dependencyUpdates") {
    rejectVersionIf {
        isNonStable(candidate.version)
    }
}
