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
        // Subir os dois a cada mudança: versionCode +1, versionName 1.1 → 1.2 → ...
        versionCode = 21
        versionName = "1.21"
    }

    // Chave fixa: todo APK gerado (inclusive no GitHub) sai com a mesma assinatura,
    // então dá para instalar por cima da versão anterior sem desinstalar.
    signingConfigs {
        create("pingreal") {
            storeFile = file("pingreal.keystore")
            storePassword = "pingreal"
            keyAlias = "pingreal"
            keyPassword = "pingreal"
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("pingreal")
        }
        getByName("release") {
            signingConfig = signingConfigs.getByName("pingreal")
        }
    }

    // O APK sai como "PingReal-v.<versão>.apk" (ex.: PingReal-v.1.19.apk) em vez de app-debug.apk.
    applicationVariants.all {
        val variant = this
        outputs.all {
            (this as com.android.build.gradle.internal.api.BaseVariantOutputImpl).outputFileName =
                "PingReal-v.${variant.versionName}.apk"
        }
    }
}

dependencies {
    // FileProvider, usado para compartilhar a imagem do relatório.
    implementation("androidx.core:core:1.13.1")

    testImplementation("junit:junit:4.13.2")
    // Nos testes (fora do Android) o org.json do sistema não existe; esta é a mesma biblioteca para a JVM.
    testImplementation("org.json:json:20231013")
}
