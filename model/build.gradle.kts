plugins {
    id("library-conventions")
}

dependencies {
    compileOnly(libs.jackson2.annotations)

    testImplementation(platform(libs.junit.bom))
    testImplementation(kotlin("test"))

    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
