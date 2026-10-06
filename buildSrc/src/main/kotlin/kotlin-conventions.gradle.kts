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

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

tasks.named("check") {
    dependsOn("ktlintCheck")
}
