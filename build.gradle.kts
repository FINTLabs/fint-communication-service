buildscript {
    repositories {
        gradlePluginPortal()
    }
    dependencies {
        classpath(platform("com.fasterxml.jackson:jackson-bom:2.22.3"))
        constraints {
            classpath("org.apache.httpcomponents.client5:httpclient5:5.6.4")
            classpath("org.apache.httpcomponents.core5:httpcore5:5.4.4")
            classpath("org.apache.httpcomponents.core5:httpcore5-h2:5.4.4")
            classpath("org.apache.commons:commons-lang3:3.20.0")
        }
    }
}

plugins {
    id("org.springframework.boot") version "3.5.16"
    id("io.spring.dependency-management") version "1.1.7"
    id("io.github.ben-manes.versions") version "0.64.0"
    id("org.jlleitschuh.gradle.ktlint") version "14.2.0"
    kotlin("jvm") version "2.4.10"
    kotlin("plugin.spring") version "2.4.10"
}

group = "no.novari"

kotlin {
    jvmToolchain(25)
}

repositories {
    mavenCentral()
}

tasks.jar {
    isEnabled = false
}

springBoot {
    mainClass.set("no.novari.communication.ApplicationKt")
}

sourceSets {
    named("main") {
        java.setSrcDirs(emptyList<String>())
    }
    named("test") {
        java.setSrcDirs(emptyList<String>())
    }
}

extra["commons-lang3.version"] = "3.20.0"
extra["jackson-bom.version"] = "2.22.3"
extra["log4j2.version"] = "2.26.1"
extra["tomcat.version"] = "10.1.59"

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation(kotlin("test"))

    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}

ktlint {
    version.set("1.8.0")
}

tasks.named("check") {
    dependsOn("ktlintCheck")
}
