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
    mainClass.set("com.baer.cupola.tools.geoindex.MainKt")
}

// Inputs default to <root>/local/sources; override with -PgeoSources=<dir> (a git worktree has no local/).
val geoSources = providers.gradleProperty("geoSources").map { file(it) }
    .orElse(rootProject.layout.projectDirectory.dir("local/sources").asFile)

tasks.named<JavaExec>("run") {
    val sources = geoSources.get()
    args(
        File(sources, "geonames/cities15000.zip").path,
        File(sources, "naturalearth/ne_10m_geography_marine_polys.zip").path,
        project(":core:orbit").layout.projectDirectory.dir("src/main/resources/geo").asFile.path,
    )
}

tasks.test {
    systemProperty("geoindex.sources", geoSources.get().path)
}

dependencies {
    implementation(project(":core:orbit"))
    testImplementation(libs.junit)
}
