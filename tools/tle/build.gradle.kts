import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

application {
    mainClass.set("com.baer.cupola.tools.tle.MainKt")
}

// Relative --out paths resolve against the repository root, not tools/tle.
tasks.named<JavaExec>("run") {
    workingDir = rootProject.layout.projectDirectory.asFile
}

dependencies {
    implementation(project(":core:orbit"))
    testImplementation(libs.junit)
}
