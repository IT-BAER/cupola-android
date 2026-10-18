plugins {
    alias(libs.plugins.android.asset.pack)
}

// Install-time asset pack, no code (plan P5.4). Play serves `textures#tcf_astc/` to ASTC devices and the
// suffix-less `textures/` (ETC2) to all others; the suffix is stripped in the installed APK.
assetPack {
    packName.set("earthpack")
    dynamicDelivery {
        deliveryType.set("install-time")
    }
}

// Texture budget (plan P5) and Play's rule that the default folder must exist: a texture missing from
// `textures/` leaves non-ASTC devices without it.
abstract class CheckTexturePack : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val astcDir: DirectoryProperty

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val defaultDir: DirectoryProperty

    @get:Input
    abstract val maxAstcBytes: Property<Long>

    @get:OutputFile
    abstract val report: RegularFileProperty

    @TaskAction
    fun verify() {
        val astc = astcDir.get().asFile.listFiles().orEmpty().filter { it.isFile }.associateBy { it.name }
        val default = defaultDir.get().asFile.listFiles().orEmpty().filter { it.isFile }.associateBy { it.name }
        val astcBytes = astc.values.sumOf { it.length() }
        val defaultBytes = default.values.sumOf { it.length() }
        report.get().asFile.writeText(
            "astc files=${astc.size} bytes=$astcBytes limit=${maxAstcBytes.get()}\n" +
                "default files=${default.size} bytes=$defaultBytes\n"
        )
        if (astc.isEmpty()) throw GradleException("No textures in ${astcDir.get().asFile}")
        if (astc.keys != default.keys) {
            throw GradleException(
                "Texture folders differ: only ASTC ${(astc.keys - default.keys).sorted()}, " +
                    "only default ${(default.keys - astc.keys).sorted()}"
            )
        }
        if (astcBytes > maxAstcBytes.get()) {
            throw GradleException("ASTC textures take $astcBytes bytes; budget is ${maxAstcBytes.get()} (96 MiB)")
        }
    }
}

val checkTexturePack = tasks.register<CheckTexturePack>("checkTexturePack") {
    astcDir.set(layout.projectDirectory.dir("src/main/assets/textures#tcf_astc"))
    defaultDir.set(layout.projectDirectory.dir("src/main/assets/textures"))
    maxAstcBytes.set(96L * 1024 * 1024)
    report.set(layout.buildDirectory.file("reports/texture-pack.txt"))
}
tasks.named("check") { dependsOn(checkTexturePack) }