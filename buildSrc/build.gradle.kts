plugins {
    `kotlin-dsl`
}

repositories {
    gradlePluginPortal()
}

dependencies {
    implementation("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.20")
    implementation("org.jetbrains.kotlin:kotlin-allopen:2.4.20")
    implementation("org.jlleitschuh.gradle:ktlint-gradle:14.2.0")
    implementation("org.springframework.boot:spring-boot-gradle-plugin:4.1.1")
    implementation("io.spring.gradle:dependency-management-plugin:1.1.7")
    implementation("io.github.ben-manes.versions:io.github.ben-manes.versions.gradle.plugin:0.64.0")

    constraints {
        implementation("org.apache.httpcomponents.client5:httpclient5:5.6.4")
        implementation("org.apache.httpcomponents.core5:httpcore5:5.4.4")
        implementation("org.apache.httpcomponents.core5:httpcore5-h2:5.4.4")
        implementation("org.apache.commons:commons-lang3:3.21.0")
    }
}
