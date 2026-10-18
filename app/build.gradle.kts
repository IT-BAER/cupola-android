import com.android.build.api.artifact.SingleArtifact
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.baer.cupola"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.baer.cupola"
        minSdk = 33
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":feature:settings"))
    implementation(project(":core:render"))
    implementation(project(":core:orbit"))

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
}

// The app ships without INTERNET or any runtime permission (design decisions 12 and 35).
// Only library-internal signature permissions of this app may appear in the merged manifest.
abstract class CheckManifestPermissions : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val mergedManifest: RegularFileProperty

    @get:Input
    abstract val allowed: SetProperty<String>

    @get:OutputFile
    abstract val report: RegularFileProperty

    @TaskAction
    fun verify() {
        val androidNs = "http://schemas.android.com/apk/res/android"
        val document = DocumentBuilderFactory.newInstance()
            .apply { isNamespaceAware = true }
            .newDocumentBuilder()
            .parse(mergedManifest.get().asFile)
        val declared = listOf("uses-permission", "uses-permission-sdk-23").flatMap { tag ->
            val nodes = document.getElementsByTagName(tag)
            (0 until nodes.length).map { (nodes.item(it) as Element).getAttributeNS(androidNs, "name") }
        }.toSortedSet()
        report.get().asFile.writeText(declared.joinToString("\n", postfix = "\n"))
        val forbidden = declared - allowed.get()
        if (forbidden.isNotEmpty()) {
            throw GradleException("Merged manifest declares forbidden permissions: ${forbidden.joinToString()}")
        }
    }
}

androidComponents {
    onVariants { variant ->
        val variantName = variant.name.replaceFirstChar { it.uppercase() }
        val guard = tasks.register<CheckManifestPermissions>("check${variantName}ManifestPermissions") {
            mergedManifest.set(variant.artifacts.get(SingleArtifact.MERGED_MANIFEST))
            allowed.set(variant.applicationId.map { setOf("$it.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION") })
            report.set(layout.buildDirectory.file("reports/manifest-permissions/${variant.name}.txt"))
        }
        tasks.named { it == "assemble$variantName" || it == "check" }.configureEach { dependsOn(guard) }
    }
}
