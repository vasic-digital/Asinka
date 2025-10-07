import com.google.protobuf.gradle.id
import java.util.Properties

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("com.google.protobuf")
    id("com.google.devtools.ksp")
    id("maven-publish")
    id("signing")
}

val envFile = rootProject.file(".env")
val envProperties = Properties()
if (envFile.exists()) {
    envFile.inputStream().use { envProperties.load(it) }
}

android {

    namespace = "digital.vasic.asinka"
    compileSdk = 35
    defaultConfig {

        minSdk = 21
        targetSdk = 35

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        release {

            isMinifyEnabled = false

            proguardFiles(

                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {

        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {

        jvmTarget = "17"

        freeCompilerArgs += listOf(

            "-opt-in=kotlin.RequiresOptIn",
            "-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi"
        )
    }

    buildFeatures {
        buildConfig = true
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:3.25.1"
    }
    plugins {
        id("grpc") {
            artifact = "io.grpc:protoc-gen-grpc-java:1.60.1"
        }
        id("grpckt") {
            artifact = "io.grpc:protoc-gen-grpc-kotlin:1.4.1:jdk8@jar"
        }
    }
    generateProtoTasks {
        all().forEach { task ->
            task.plugins {
                id("grpc")
                id("grpckt")
            }
            task.builtins {
                id("java")
                id("kotlin")
            }
        }
    }
}

dependencies {
    // Kotlin
    implementation("org.jetbrains.kotlin:kotlin-stdlib:2.1.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")

    // AndroidX
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.4")
    implementation("androidx.lifecycle:lifecycle-process:2.9.4")

    // gRPC
    implementation("io.grpc:grpc-kotlin-stub:1.4.1")
    implementation("io.grpc:grpc-okhttp:1.60.1")
    implementation("io.grpc:grpc-protobuf:1.60.1")
    implementation("io.grpc:grpc-stub:1.60.1")
    implementation("com.google.protobuf:protobuf-java:3.25.1")
    implementation("com.google.protobuf:protobuf-kotlin:3.25.1")
    implementation("javax.annotation:javax.annotation-api:1.3.2")

    // Room Database
    val roomVersion = "2.8.0"
    implementation("androidx.room:room-runtime:$roomVersion")
    implementation("androidx.room:room-ktx:$roomVersion")
    ksp("androidx.room:room-compiler:$roomVersion")

    // SQLCipher
    implementation("net.zetetic:sqlcipher-android:4.10.0@aar")

    // Network Service Discovery
    implementation("com.google.android.gms:play-services-nearby:19.3.0")

    // Security/Encryption
    implementation("androidx.security:security-crypto:1.1.0")

    // Testing
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    testImplementation("org.mockito.kotlin:mockito-kotlin:5.4.0")
    testImplementation("org.mockito:mockito-core:5.14.2")
    testImplementation("io.mockk:mockk:1.13.13")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("androidx.test:runner:1.6.2")
    testImplementation("androidx.test.ext:junit:1.2.1")
    testImplementation("org.robolectric:robolectric:4.13")

    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:rules:1.6.1")
    androidTestImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    androidTestImplementation("io.mockk:mockk-android:1.13.13")
}

val versionName = envProperties.getProperty("VERSION_NAME", "0.1.0")
val versionCode = envProperties.getProperty("VERSION_CODE", "1").toInt()

publishing {
    publications {
        create<MavenPublication>("release") {
            groupId = "digital.vasic.asinka"
            artifactId = "asinka"
            version = versionName

            afterEvaluate {
                from(components["release"])
            }

            pom {
                name.set("Asinka")
                description.set("Android IPC library based on gRPC for real-time object synchronization between applications")
                url.set("https://github.com/vasic-digital/asinka")

                licenses {
                    license {
                        name.set("Apache License, Version 2.0")
                        url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                    }
                }

                developers {
                    developer {
                        id.set("vasic-digital")
                        name.set("Vasic Digital")
                        url.set("https://vasic.digital")
                    }
                }

                scm {
                    connection.set("scm:git:git://github.com/vasic-digital/asinka.git")
                    developerConnection.set("scm:git:ssh://git@github.com/vasic-digital/asinka.git")
                    url.set("https://github.com/vasic-digital/asinka")
                }
            }
        }
    }

    repositories {
        maven {
            name = "MavenCentral"
            url = uri("https://s01.oss.sonatype.org/service/local/staging/deploy/maven2/")
            credentials {
                username = envProperties.getProperty("MAVEN_CENTRAL_USERNAME", "")
                password = envProperties.getProperty("MAVEN_CENTRAL_PASSWORD", "")
            }
        }
    }
}

signing {
    val keyId = envProperties.getProperty("SIGNING_KEY_ID", "")
    val password = envProperties.getProperty("SIGNING_PASSWORD", "")
    val secretKeyRingFile = envProperties.getProperty("SIGNING_SECRET_KEY_RING_FILE", "")

    if (keyId.isNotEmpty() && password.isNotEmpty() && secretKeyRingFile.isNotEmpty()) {
        useInMemoryPgpKeys(keyId, File(secretKeyRingFile).readText(), password)
        sign(publishing.publications["release"])
    }
}