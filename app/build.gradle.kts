plugins {
    id("com.android.application")
}

android {
    namespace = "com.openaria.openaria_echo_mobile"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.openaria.openaria_echo_mobile"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        encoding = "UTF-8"
    }
}

tasks.withType<org.gradle.api.tasks.compile.JavaCompile>().configureEach {
    options.compilerArgs.add("-Xlint:deprecation")
}
