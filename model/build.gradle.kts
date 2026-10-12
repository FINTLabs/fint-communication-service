plugins {
    id("library-conventions")
}

dependencies {
    compileOnly("com.fasterxml.jackson.core:jackson-annotations:2.22")

    testImplementation(platform("org.junit:junit-bom:6.1.3"))
    testImplementation(kotlin("test"))

    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
