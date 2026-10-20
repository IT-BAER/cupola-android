plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.baer.cupola.feature.settings"
    compileSdk = 37

    defaultConfig {
        minSdk = 33
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":core:render"))
    // Orbit types (City, Pass, GroundLabel, Tle) appear in the repository ports.
    api(project(":core:orbit"))

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.lifecycle.viewmodel)
    api(libs.coroutines.core)

    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.turbine)
}
