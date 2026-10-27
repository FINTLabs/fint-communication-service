plugins {
    `kotlin-dsl`
}

repositories {
    gradlePluginPortal()
}

dependencyLocking {
    lockAllConfigurations()
}

dependencies {
    implementation(libs.kotlin.gradle.plugin)
    implementation(libs.kotlin.allopen)
    implementation(libs.ktlint.gradle.plugin)
    implementation(libs.spring.boot.gradle.plugin)
    implementation(libs.ben.manes.versions.plugin)

    constraints {
        implementation(libs.httpclient5)
        implementation(libs.httpcore5)
        implementation(libs.httpcore5.h2)
        implementation(libs.commons.lang3)
        implementation(libs.jackson3.core)
        implementation(libs.jackson3.databind)
    }
}
