plugins {
    id("library-conventions")
}

val springBoot3Version = "3.5.16"
val springBoot4Version = "4.1.1"

val testSpring7 =
    sourceSets.create("testSpring7") {
        compileClasspath += sourceSets.main.get().output
        runtimeClasspath += sourceSets.main.get().output
    }

kotlin.sourceSets.named("testSpring7") {
    kotlin.srcDir("src/test/kotlin")
}

dependencies {
    api(project(":model"))

    compileOnly(platform("org.springframework.boot:spring-boot-dependencies:$springBoot3Version"))
    compileOnly("org.springframework:spring-web")
    compileOnly("org.springframework:spring-webflux")
    compileOnly("io.projectreactor:reactor-core")

    testImplementation(platform("org.springframework.boot:spring-boot-dependencies:$springBoot3Version"))
    testImplementation(platform("com.fasterxml.jackson:jackson-bom:2.21.7"))
    testImplementation("org.springframework:spring-context")
    testImplementation("org.springframework:spring-web")
    testImplementation("org.springframework:spring-webflux")
    testImplementation("io.projectreactor:reactor-core")
    testImplementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    testImplementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310")
    testImplementation("org.skyscreamer:jsonassert")
    testImplementation(kotlin("test-junit5"))
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    "testSpring7Implementation"(project(":model"))
    "testSpring7Implementation"(platform("org.springframework.boot:spring-boot-dependencies:$springBoot4Version"))
    "testSpring7Implementation"(platform("tools.jackson:jackson-bom:3.1.7"))
    "testSpring7Implementation"("org.springframework:spring-context")
    "testSpring7Implementation"("org.springframework:spring-web")
    "testSpring7Implementation"("org.springframework:spring-webflux")
    "testSpring7Implementation"("io.projectreactor:reactor-core")
    "testSpring7Implementation"("tools.jackson.module:jackson-module-kotlin")
    "testSpring7Implementation"("org.skyscreamer:jsonassert")
    "testSpring7Implementation"(kotlin("test-junit5"))
    "testSpring7RuntimeOnly"("org.junit.platform:junit-platform-launcher")
}

val testSpring7Task =
    tasks.register<Test>("testSpring7") {
        description = "Runs the client tests against Spring Framework 7 and Jackson 3."
        group = LifecycleBasePlugin.VERIFICATION_GROUP
        testClassesDirs = testSpring7.output.classesDirs
        classpath = testSpring7.runtimeClasspath
        shouldRunAfter(tasks.test)
    }

tasks.check {
    dependsOn(testSpring7Task)
}
