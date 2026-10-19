plugins {
    id("library-conventions")
}

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

    compileOnly(platform(libs.spring.boot3.dependencies))
    compileOnly("org.springframework:spring-web")
    compileOnly("org.springframework:spring-webflux")
    compileOnly("io.projectreactor:reactor-core")

    testImplementation(platform(libs.spring.boot3.dependencies))
    testImplementation(platform(libs.jackson2.bom))
    testImplementation("org.springframework:spring-context")
    testImplementation("org.springframework:spring-web")
    testImplementation("org.springframework:spring-webflux")
    testImplementation("io.projectreactor:reactor-core")
    testImplementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    testImplementation("org.skyscreamer:jsonassert")
    testImplementation(kotlin("test-junit5"))
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    "testSpring7Implementation"(project(":model"))
    "testSpring7Implementation"(platform(libs.spring.boot.dependencies))
    "testSpring7Implementation"(platform(libs.jackson3.bom))
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
