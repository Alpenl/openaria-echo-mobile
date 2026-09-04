import java.io.FileInputStream
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

dependencies {
    implementation(libs.androidx.core)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.mockwebserver)
    testImplementation(libs.org.json)

    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.espresso.core)
}

val keystoreProperties = Properties()
val keystorePropertiesFile = rootProject.file("key.properties")
if (keystorePropertiesFile.exists()) {
    keystoreProperties.load(FileInputStream(keystorePropertiesFile))
}

val releaseSigningKeys = listOf("storeFile", "storePassword", "keyAlias", "keyPassword")
val releaseSigningConfigured = releaseSigningKeys
    .any { key -> !keystoreProperties.getProperty(key).isNullOrBlank() }
val releaseSigningReady = releaseSigningKeys
    .all { key -> !keystoreProperties.getProperty(key).isNullOrBlank() }

check(!releaseSigningConfigured || releaseSigningReady) {
    "Android release signing is partially configured. Set storeFile, storePassword, keyAlias, and keyPassword in key.properties, or remove them all for an unsigned release build."
}

android {
    namespace = "com.openaria.openaria_echo_mobile"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.openaria.openaria_echo_mobile"
        minSdk = 26
        targetSdk = 36
        versionCode = 13
        versionName = "0.1.10"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        encoding = "UTF-8"
    }

    buildFeatures {
        compose = true
    }

    bundle {
        language {
            enableSplit = false
        }
    }

    signingConfigs {
        if (releaseSigningReady) {
            create("release") {
                storeFile = file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            if (releaseSigningReady) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
}

tasks.withType<org.gradle.api.tasks.compile.JavaCompile>().configureEach {
    options.compilerArgs.add("-Xlint:deprecation")
}

// Safe-swap remains readable at the frozen Device API boundary, but it is not
// a current Echo product capability. Keep its positive wire/projection tests
// in an explicitly named, manual-only compatibility task instead of letting
// them become a release gate by being picked up by the default debug unit-test
// task. CI and release workflows deliberately do not invoke this task.
val frozenCompatibilityTestPatterns = listOf(
    "*CaptureProjectionTest*safe*swap*",
    "*DeviceApiValidatorsTest*safe*swap*",
    "*DeviceHttpClientTest*safe*swap*",
)

val frozenCompatibilityTest = tasks.register<org.gradle.api.tasks.testing.Test>("testFrozenCompatibility") {
    group = "verification"
    description = "Manual-only frozen safe-swap parser/projection compatibility checks; not release acceptance."
    filter {
        frozenCompatibilityTestPatterns.forEach(::includeTestsMatching)
    }
}

tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
    if (name == "testDebugUnitTest") {
        inputs.property(
            "includeFrozenCompatibility",
            project.findProperty("includeFrozenCompatibility")?.toString() == "true",
        )
        if (project.findProperty("includeFrozenCompatibility")?.toString() != "true") {
            filter {
                frozenCompatibilityTestPatterns.forEach(::excludeTestsMatching)
            }
        }
    }
}

// Android Gradle Plugin registers the variant task during configuration. Bind
// the independent compatibility runner only after all projects have finished
// evaluating, before Gradle decides whether the runner has test sources.
gradle.projectsEvaluated {
    val debugUnitTest = tasks.findByName("testDebugUnitTest")
        as? org.gradle.api.tasks.testing.Test
        ?: error("Android debug unit-test task was not registered")
    val compatibilityTask = frozenCompatibilityTest.get()
    compatibilityTask.dependsOn("compileDebugUnitTestKotlin", "compileDebugUnitTestJavaWithJavac")
    compatibilityTask.testClassesDirs = debugUnitTest.testClassesDirs
    compatibilityTask.classpath = debugUnitTest.classpath
}

tasks.register("verifyUnitTestSources") {
    group = "verification"
    description = "Fails when the Android unit test task would be NO-SOURCE."

    doLast {
        val testSources = fileTree("src/test") {
            include("**/*Test.kt", "**/*Test.java")
        }.files
        check(testSources.isNotEmpty()) {
            "No Android unit tests found under app/src/test; CI must not pass with testDebugUnitTest NO-SOURCE."
        }
    }
}

tasks.register("verifyReleaseSafety") {
    group = "verification"
    description = "Checks that release builds do not keep legacy fake-device safety hazards."

    doLast {
        val manifest = file("src/main/AndroidManifest.xml").readText()
        check(!manifest.contains("usesCleartextTraffic=\"true\"")) {
            "Do not enable app-wide cleartext traffic. Route local HTTP through EndpointPolicy-backed clients."
        }
        check(manifest.contains("android:networkSecurityConfig=\"@xml/network_security_config\"")) {
            "Release builds must keep the network security config attached."
        }

        val networkSecurityConfig = file("src/main/res/xml/network_security_config.xml").readText()
        check(networkSecurityConfig.contains("EndpointPolicy")) {
            "Network security config must document that local cleartext is guarded by EndpointPolicy."
        }

        val productionSources = fileTree("src/main") {
            include("**/*.kt", "**/*.java")
        }.files
        val sourceText = productionSources
            .filter { it.name != "AppUpdateManager.java" }
            .joinToString("\n") { it.readText() }
        val forbidden = listOf(
            "recording = !recording",
            "10.42.0.1:8080 · API v4 · pkg",
            "connection refused",
            "\"Mount\"",
            "\"ready\"",
        )
        val hits = forbidden.filter { sourceText.contains(it) }
        check(hits.isEmpty()) {
            "Legacy fake-device UI tokens remain in production sources: ${hits.joinToString()}"
        }

        val allowedNetworkEntrypoints = setOf(
            "DeviceHttpClient.kt",
            "DeviceProbeClient.kt",
            "AppUpdateManager.java",
        )
        val unexpectedNetworkEntrypoints = productionSources
            .filter { it.readText().contains("openConnection(") }
            .filter { it.name !in allowedNetworkEntrypoints }
            .map { it.relativeTo(projectDir).path }
        check(unexpectedNetworkEntrypoints.isEmpty()) {
            "Unexpected production network entry points bypass EndpointPolicy review: " +
                unexpectedNetworkEntrypoints.joinToString()
        }

        val updateSource = file("src/main/java/com/openaria/openaria_echo_mobile/AppUpdateManager.java").readText()
        check(updateSource.contains("Update manifest URL must be HTTPS."))
        check(updateSource.contains("android.apk.url must be an HTTPS URL"))
    }
}

tasks.named("check").configure {
    dependsOn("verifyUnitTestSources", "verifyReleaseSafety")
}
