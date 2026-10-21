import com.android.build.api.artifact.SingleArtifact
import java.time.Duration
import java.time.Instant
import java.util.Locale
import java.util.Properties
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "com.baer.cupola"
    compileSdk = 37
    // Same NDK as :core:render so packaging can strip the release .so.
    ndkVersion = "30.0.16248370"

    defaultConfig {
        applicationId = "com.baer.cupola"
        minSdk = 33
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Upload key (plan P8.1): CI passes the decoded keystore by environment, a local build reads local.properties.
    // Without either the release build stays unsigned (CI build job).
    signingConfigs {
        create("release") {
            val localProperties = Properties().apply {
                val file = rootProject.file("local.properties")
                if (file.exists()) file.inputStream().use { load(it) }
            }
            val envKeystore = System.getenv("RELEASE_KEYSTORE_FILE")
            val localKeystore = localProperties.getProperty("RELEASE_STORE_FILE", "")
            if (envKeystore != null && file(envKeystore).exists()) {
                storeFile = file(envKeystore)
                storePassword = System.getenv("RELEASE_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("RELEASE_KEY_ALIAS")
                keyPassword = System.getenv("RELEASE_KEY_PASSWORD")
            } else if (localKeystore.isNotEmpty()) {
                storeFile = file(localKeystore)
                storePassword = localProperties.getProperty("RELEASE_STORE_PASSWORD", "")
                keyAlias = localProperties.getProperty("RELEASE_KEY_ALIAS", "")
                keyPassword = localProperties.getProperty("RELEASE_KEY_PASSWORD", "")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
        }
        release {
            val upload = signingConfigs.getByName("release")
            if (upload.storeFile?.exists() == true) signingConfig = upload
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    assetPacks += listOf(":earthpack")

    bundle {
        texture {
            enableSplit = true
        }
    }
}

dependencies {
    implementation(project(":feature:settings"))
    implementation(project(":core:render"))
    implementation(project(":core:orbit"))

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.hilt.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.datastore.preferences)
    implementation(libs.coroutines.core)

    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.robolectric)

    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
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

// Release builds must not ship a stale ISS TLE (design decisions 13 and 40). Same rule as :tools:tle Guard.
@UntrackedTask(because = "The verdict depends on the clock unless cupola.tleCheckTime is set")
abstract class CheckReleaseTleAge : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val issJson: RegularFileProperty

    @get:Input
    @get:Optional
    abstract val checkTime: Property<String>

    @TaskAction
    fun verify() {
        val epochText = Regex(""""epoch"\s*:\s*"([^"]+)"""").find(issJson.get().asFile.readText())?.groupValues?.get(1)
            ?: throw GradleException("No \"epoch\" field in ${issJson.get().asFile}")
        val epoch = Instant.parse(epochText)
        val now = checkTime.map { Instant.parse(it) }.getOrElse(Instant.now())
        val age = Duration.between(epoch, now)
        if (age > Duration.ofDays(7)) {
            throw GradleException("Bundled ISS TLE epoch $epoch is ${"%.1f".format(Locale.ROOT, age.toMillis() / 86_400_000.0)} days old at $now; limit is 7 days. Run the release-prep workflow.")
        }
    }
}

val checkReleaseTleAge = tasks.register<CheckReleaseTleAge>("checkReleaseTleAge") {
    issJson.set(layout.projectDirectory.file("src/main/assets/orbit/iss.json"))
    checkTime.set(providers.gradleProperty("cupola.tleCheckTime"))
}

// A normal CI build between releases always carries an older TLE, so only tagged release builds are guarded.
if (providers.gradleProperty("cupola.releaseTag").orNull == "true") {
    tasks.named { it == "bundleRelease" || it == "assembleRelease" }.configureEach { dependsOn(checkReleaseTleAge) }
}

// Every mirrored source behind a shipped texture or index (tools/textures/sources.lock) needs a credits row
// with the same licence and credit text (plan P5 acceptance); P6.9 shows credits.tsv in the app.
abstract class CheckCredits : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val sourcesLock: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val credits: RegularFileProperty

    @get:OutputFile
    abstract val report: RegularFileProperty

    @TaskAction
    fun verify() {
        // Tab-separated, '#' comments, first remaining line is the header.
        fun rows(file: File) = file.readLines(Charsets.UTF_8)
            .filter { it.isNotBlank() && !it.startsWith("#") }.drop(1).map { it.split('\t') }
        val credited = rows(credits.get().asFile).map { it[1] to it[2] }.toSet()
        val needed = rows(sourcesLock.get().asFile).map { it[4] to it[5] }.toSet()
        val missing = needed - credited
        report.get().asFile.writeText("needed=${needed.size} credited=${credited.size} missing=${missing.size}\n")
        if (missing.isNotEmpty()) {
            throw GradleException("No credits.tsv row for: " + missing.joinToString("; ") { "${it.second} (${it.first})" })
        }
    }
}

val checkCredits = tasks.register<CheckCredits>("checkCredits") {
    sourcesLock.set(rootProject.layout.projectDirectory.file("tools/textures/sources.lock"))
    credits.set(layout.projectDirectory.file("src/main/assets/credits.tsv"))
    report.set(layout.buildDirectory.file("reports/credits.txt"))
}
tasks.named { it == "check" }.configureEach { dependsOn(checkCredits) }
// A release bundle must stay inside the texture budget and credit every source.
tasks.named { it == "bundleRelease" }.configureEach { dependsOn(checkCredits, ":earthpack:checkTexturePack") }
