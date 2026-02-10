import com.android.build.gradle.BaseExtension
import com.lagradost.cloudstream3.gradle.CloudstreamExtension
import org.gradle.kotlin.dsl.register
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile
import java.util.zip.ZipFile
import groovy.json.JsonSlurper
import groovy.json.JsonOutput

buildscript {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }

    dependencies {
        classpath("com.android.tools.build:gradle:8.13.2")
        classpath("com.github.recloudstream:gradle:cce1b8d84d")
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.3.0")
    }
}

allprojects {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}

fun Project.cloudstream(configuration: CloudstreamExtension.() -> Unit) = extensions.getByName<CloudstreamExtension>("cloudstream").configuration()

fun Project.android(configuration: BaseExtension.() -> Unit) = extensions.getByName<BaseExtension>("android").configuration()

subprojects {
    apply(plugin = "com.android.library")
    apply(plugin = "kotlin-android")
    apply(plugin = "com.lagradost.cloudstream3.gradle")

    cloudstream {
        setRepo(System.getenv("GITHUB_REPOSITORY") ?: "https://github.com/phisher98/cloudstream-extensions-phisher")
        authors = listOf("Phisher98")
    }

    android {
        namespace = "com.phisher98"

        defaultConfig {
            minSdk = 21
            compileSdkVersion(35)
            targetSdk = 35

        }

        compileOptions {
            sourceCompatibility = JavaVersion.VERSION_1_8
            targetCompatibility = JavaVersion.VERSION_1_8
        }


        tasks.withType<KotlinJvmCompile> {
            compilerOptions {
                jvmTarget.set(JvmTarget.JVM_1_8)
                freeCompilerArgs.addAll(
                    "-Xno-call-assertions",
                    "-Xno-param-assertions",
                    "-Xno-receiver-assertions"
                )
            }
        }

        lintOptions {
            isAbortOnError = false
        }
    }

    dependencies {
        val implementation by configurations
        val cloudstream by configurations
        cloudstream("com.lagradost:cloudstream3:pre-release")

        // Other dependencies
        implementation(kotlin("stdlib"))
        implementation("com.github.Blatzar:NiceHttp:0.4.13")
        implementation("org.jsoup:jsoup:1.21.2")
        implementation("androidx.annotation:annotation:1.9.1")
        implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.20.1")
        implementation("com.fasterxml.jackson.core:jackson-databind:2.20.1")
        implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
        implementation("org.mozilla:rhino:1.8.0")
        implementation("me.xdrop:fuzzywuzzy:1.4.0")
        implementation("com.google.code.gson:gson:2.13.2")
        implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
        implementation("com.github.vidstige:jadb:v1.2.1")
        implementation("org.bouncycastle:bcpkix-jdk15on:1.70")
    }
}

tasks.register<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}



tasks.register("buildAndCollect") {
    group = "build"
    description = "Builds all plugins, copies .cs3 files, and generates metadata JSONs"

    dependsOn(subprojects.map { ":${it.name}:make" })

    doLast {
        val buildsDir = File(rootDir, "Builds")
        if (!buildsDir.exists()) {
            buildsDir.mkdirs()
        } else {
            buildsDir.listFiles()?.forEach { it.delete() }
        }

        val plugins = mutableListOf<Map<String, Any>>()
        val repoUrlBase = "https://raw.githubusercontent.com/angahjee1994/testrepo/Builds"

        subprojects.forEach { project ->
            val buildDir = project.layout.buildDirectory.get().asFile
            if (buildDir.exists()) {
                buildDir.walk().filter { it.extension == "cs3" }.forEach { file ->
                    val targetFile = File(buildsDir, file.name)
                    file.copyTo(targetFile, overwrite = true)
                    println("Copied ${file.name} to ${targetFile.absolutePath}")

                    try {
                        ZipFile(targetFile).use { zip ->
                            val entry = zip.getEntry("manifest.json")
                            if (entry != null) {
                                val inputStream = zip.getInputStream(entry)
                                val jsonContent = inputStream.reader().readText()
                                val json = JsonSlurper().parseText(jsonContent) as MutableMap<String, Any>

                                // Add URL and fileSize to the json
                                json["url"] = "$repoUrlBase/${file.name}"
                                json["fileSize"] = targetFile.length()



                                plugins.add(json)
                            }
                        }
                    } catch (e: Exception) {
                        println("Failed to extract metadata from ${file.name}: ${e.message}")
                    }
                }
            }
        }

        // Save plugins.json
        val pluginsFile = File(buildsDir, "plugins.json")
        pluginsFile.writeText(JsonOutput.prettyPrint(JsonOutput.toJson(plugins)))
        println("Generated plugins.json")

        // Save repo.json
        val repoJson = mapOf(
            "name" to "botol",
            "iconUrl" to "https://raw.githubusercontent.com/angahjee1994/testrepo/refs/heads/main/icon.png",
            "description" to "Repository",
            "manifestVersion" to 1,
            "pluginLists" to listOf("https://raw.githubusercontent.com/angahjee1994/testrepo/refs/heads/Builds/plugins.json")
        )
        val repoFile = File(buildsDir, "repo.json")
        repoFile.writeText(JsonOutput.prettyPrint(JsonOutput.toJson(repoJson)))
        println("Generated repo.json")
    }
}
