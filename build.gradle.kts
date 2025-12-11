// Ensure minimum required Gradle version
val minGradleVersion = "8.14"
if (gradle.gradleVersion < minGradleVersion) {
    throw GradleException("Gradle $minGradleVersion or higher is required")
}

buildscript {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
    dependencies {
        classpath("com.android.tools.build:gradle:8.9.1")
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.0.0")
        classpath("com.google.protobuf:protobuf-gradle-plugin:0.9.4")
    }
}

plugins {
    id("org.jetbrains.kotlin.android") version "2.0.0" apply false
    id("com.google.protobuf") version "0.9.4" apply false
    id("com.google.devtools.ksp") version "2.0.0-1.0.21" apply false
}

allprojects {
    repositories {
        google()
        mavenCentral()
    }
}

tasks.register("clean", Delete::class) {
    delete(rootProject.layout.buildDirectory)
}

// Lint configuration commented out temporarily
// tasks.withType<LintTask> {
//     // Configure lint settings
//     checkAllWarnings = true
//     warningsAsErrors = false
//     disable.addAll(listOf(
//         "ObsoleteSdkInt",
//         "GradleDependency",
//         "OldTargetApi"
//     ))
// }