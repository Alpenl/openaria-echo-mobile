import java.io.FileInputStream
import java.util.Properties

plugins {
    id("com.android.application")
}

dependencies {
    implementation("androidx.core:core:1.17.0")
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
        versionCode = 4
        versionName = "0.1.1"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        encoding = "UTF-8"
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
