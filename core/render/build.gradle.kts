import java.security.MessageDigest
import java.util.zip.ZipFile

plugins {
    alias(libs.plugins.android.library)
}

val vulkanLayerZip = rootProject.layout.projectDirectory
    .file("local/sources/vulkan-validation/android-binaries-1.4.363.0.zip")
val vulkanLayerDir = rootProject.layout.projectDirectory.dir("local/vulkan-layers")
val hasVulkanLayerZip = vulkanLayerZip.asFile.exists()

android {
    namespace = "com.baer.cupola.core.render"
    compileSdk = 37
    ndkVersion = "30.0.16248370"

    defaultConfig {
        minSdk = 33
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        debug {
            ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
        }
        release {
            ndk { abiFilters += listOf("arm64-v8a") }
        }
    }

    // Debug-only validation layer (see extractVulkanValidationLayers); release never packages it.
    if (hasVulkanLayerZip || vulkanLayerDir.asFile.exists()) {
        // The generated `android` accessor types elements as the legacy source set class; go through the DSL type.
        val debugSources = (sourceSets as NamedDomainObjectContainer<*>).getByName("debug")
        (debugSources as com.android.build.api.dsl.AndroidSourceSet).jniLibs.directories += vulkanLayerDir.asFile.path
    }

    // The host-test KTX2 fixtures double as instrumented-test assets (TextureLoaderTest).
    val androidTestSources = (sourceSets as NamedDomainObjectContainer<*>).getByName("androidTest")
    (androidTestSources as com.android.build.api.dsl.AndroidSourceSet).assets.directories +=
        file("src/test/cpp/fixtures").path

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "4.1.2"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// Khronos validation layer for debug builds only; CI without the local zip builds without it.
abstract class ExtractVulkanLayers : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val zip: RegularFileProperty

    @get:Input
    abstract val sha256: Property<String>

    @get:Input
    abstract val entryPrefix: Property<String>

    @get:Input
    abstract val abis: ListProperty<String>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun extract() {
        val file = zip.get().asFile
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        if (actual != sha256.get()) throw GradleException("SHA-256 mismatch for $file: $actual")
        val out = outputDir.get().asFile
        out.deleteRecursively()
        ZipFile(file).use { archive ->
            for (abi in abis.get()) {
                val name = "${entryPrefix.get()}/$abi/libVkLayer_khronos_validation.so"
                val entry = archive.getEntry(name) ?: throw GradleException("$name missing in $file")
                val target = File(out, "$abi/libVkLayer_khronos_validation.so")
                target.parentFile.mkdirs()
                archive.getInputStream(entry).use { input -> target.outputStream().use { input.copyTo(it) } }
            }
        }
    }
}

val extractVulkanLayers = tasks.register<ExtractVulkanLayers>("extractVulkanValidationLayers") {
    zip.set(vulkanLayerZip)
    sha256.set("9a3b8e32cb3330406dd4f3651594201635baee4528da8e2a5300e4f55e1a9cc7")
    entryPrefix.set("android-binaries-1.4.363.0")
    abis.set(listOf("arm64-v8a", "x86_64"))
    outputDir.set(vulkanLayerDir)
}

if (hasVulkanLayerZip) {
    tasks.named { it == "mergeDebugJniLibFolders" }.configureEach { dependsOn(extractVulkanLayers) }
}

dependencies {
    implementation(project(":core:orbit"))

    testImplementation(libs.junit)

    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
}
