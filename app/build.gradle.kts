plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "br.com.pingreal"
    compileSdk = 35

    // Mantém Java e Kotlin no mesmo alvo de JVM para evitar falha no Gradle.
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    defaultConfig {
        applicationId = "br.com.pingreal"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }
}

dependencies {
    // FileProvider, usado para compartilhar a imagem do relatório.
    implementation("androidx.core:core:1.13.1")
}
