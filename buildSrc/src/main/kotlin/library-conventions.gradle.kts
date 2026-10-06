import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

plugins {
    id("kotlin-conventions")
    `maven-publish`
}

private val fintLabsRepo = uri("https://repo.fintlabs.no/releases")

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
        apiVersion.set(KotlinVersion.KOTLIN_2_2)
        languageVersion.set(KotlinVersion.KOTLIN_2_2)
        freeCompilerArgs.add("-Xjdk-release=21")
    }
}

java {
    withSourcesJar()
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(21)
}

publishing {
    repositories {
        maven {
            url = fintLabsRepo
            credentials {
                username = System.getenv("REPOSILITE_USERNAME")
                password = System.getenv("REPOSILITE_PASSWORD")
            }
            authentication {
                create<BasicAuthentication>("basic")
            }
        }
    }
    publications {
        create<MavenPublication>("maven") {
            artifactId = "fint-communication-${project.name}"
            from(components["java"])
        }
    }
}
