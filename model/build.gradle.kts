plugins {
    id("library-conventions")
}

dependencies {
    testImplementation(platform("org.junit:junit-bom:6.1.3"))
    testImplementation(kotlin("test"))

    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
