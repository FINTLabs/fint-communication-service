plugins {
    id("kotlin-conventions")
    id("org.springframework.boot")
    id("io.spring.dependency-management")
    kotlin("plugin.spring")
}

tasks.jar {
    isEnabled = false
}

extra["tomcat.version"] = "11.0.26"
extra["jackson-bom.version"] = "3.1.7"

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

dependencies {
    implementation(project(":model"))
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-data-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.flywaydb:flyway-database-postgresql")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("tools.jackson.module:jackson-module-kotlin")
    implementation("tools.jackson.dataformat:jackson-dataformat-yaml")
    implementation("com.samskivert:jmustache")
    implementation("io.github.oshai:kotlin-logging-jvm:8.0.4")

    testImplementation("org.springframework.boot:spring-boot-starter-actuator-test")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.springframework.boot:spring-boot-starter-data-jdbc-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testImplementation(kotlin("test"))

    runtimeOnly("org.postgresql:postgresql")

    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
