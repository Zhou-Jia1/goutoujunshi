import java.security.MessageDigest
import groovy.json.JsonOutput

plugins { alias(libs.plugins.android.library); alias(libs.plugins.kotlin.serialization) }

val repositoryRoot = rootProject.layout.projectDirectory.dir("..")
val generatedAssets = objects.directoryProperty().convention(layout.buildDirectory.dir("generated/knowledgeAssets"))
val syncKnowledge = tasks.register<Sync>("syncKnowledge") {
    description = "Bundle the canonical Skill and reference sources with integrity hashes."
    from(repositoryRoot) { include("SKILL.md", "references/**/*.md") }
    into(generatedAssets.map { it.dir("knowledge") })
    doFirst {
        require(destinationDir.canonicalFile.toPath().startsWith(layout.buildDirectory.get().asFile.canonicalFile.toPath()))
    }
    doLast {
        fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val entries = destinationDir.walkTopDown().filter { it.isFile && it.extension == "md" }
            .map { file ->
                val text = file.readText(Charsets.UTF_8).replace("\r\n", "\n").replace("\r", "\n")
                mapOf("path" to file.relativeTo(destinationDir).invariantSeparatorsPath,
                    "sha256" to hash(file.readBytes()), "characters" to text.codePointCount(0, text.length))
            }.sortedBy { it["path"].toString() }.toList()
        require(entries.any { it["path"] == "SKILL.md" })
        val fingerprint = entries.joinToString("\n") { "${it["path"]}:${it["sha256"]}" }
        destinationDir.resolve("knowledge-manifest.json").writeText(JsonOutput.prettyPrint(JsonOutput.toJson(
            mapOf("version" to 1, "knowledgeVersion" to hash(fingerprint.toByteArray(Charsets.UTF_8)), "entries" to entries)
        )), Charsets.UTF_8)
    }
}
android {
    namespace = "com.shengjidaguai.goutoujunshi.knowledge"
    compileSdk { version = release(37) { minorApiLevel = 2 } }
    buildToolsVersion = "37.0.0"
    defaultConfig { minSdk = 26 }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    testOptions { unitTests.isIncludeAndroidResources = true }
}
androidComponents.onVariants { variant ->
    variant.sources.assets?.addGeneratedSourceDirectory(syncKnowledge) { generatedAssets }
}
tasks.named("preBuild") { dependsOn(syncKnowledge) }
tasks.withType<Test>().configureEach {
    dependsOn(syncKnowledge)
    doFirst {
        // AGP assigns the generated directory through the Variant API after configuration.
        systemProperty("junshi.repositoryRoot", repositoryRoot.asFile.absolutePath)
        systemProperty("junshi.generatedKnowledge", generatedAssets.get().dir("knowledge").asFile.absolutePath)
    }
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
dependencies { api(project(":core:domain")); testImplementation(libs.junit); testImplementation(libs.robolectric) }
