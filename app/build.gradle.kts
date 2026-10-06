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
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("tools.jackson.module:jackson-module-kotlin")

    testImplementation("org.springframework.boot:spring-boot-starter-actuator-test")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation(kotlin("test"))

    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
