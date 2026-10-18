plugins {
    alias(libs.plugins.android.library)
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
    implementation(project(":core:orbit"))
}
