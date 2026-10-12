plugins {
    id("kotlin-conventions")
    id("org.springframework.boot")
    kotlin("plugin.spring")
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

dependencies {
    implementation(platform(libs.spring.boot.dependencies))
    implementation(platform(libs.jackson3.bom))
    constraints {
        implementation(libs.tomcat.embed.core)
        implementation(libs.tomcat.embed.el)
        implementation(libs.tomcat.embed.websocket)
    }

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
    implementation(libs.kotlin.logging)

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
