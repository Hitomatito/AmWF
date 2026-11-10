import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
}

val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties()
if (keystorePropertiesFile.isFile) {
    keystorePropertiesFile.inputStream().use { keystoreProperties.load(it) }
}

android {
    namespace = "com.hitomatito.amwf"
    compileSdk {
        version = release(37) {
            minorApiLevel = 1
        }
    }

    // Los binarios Pixie Dust son ARM-only (arm64-v8a/armeabi-v7a); no existe ABI
    // x86_64 para ChromeOS que aportar. El alcance del producto es solo teléfonos.
    lint {
        disable += "ChromeOsAbiSupport"
    }

    defaultConfig {
        applicationId = "com.hitomatito.amwf"
        minSdk = 24
        targetSdk = 37
        versionCode = 5
        versionName = "2.0.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            // Pixie Dust necesita binarios nativos ARM (wpa_supplicant, wpa_cli, pixiewps).
            // Soportamos arm64-v8a (dispositivos modernos) y armeabi-v7a (dispositivos antiguos).
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    signingConfigs {
        if (keystorePropertiesFile.isFile) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (keystorePropertiesFile.isFile) {
                signingConfig = signingConfigs.getByName("release")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    buildFeatures {
        viewBinding = true
    }

    bundle {
        // La app cambia el locale en runtime (LocaleHelper.attachBaseContext):
        // sin esto, un AAB con splits de idioma podría no incluir ES/EN y el
        // selector de idioma dejaría de funcionar.
        language {
            enableSplit = false
        }
    }

    testOptions {
        unitTests {
            // android.util.Log y demás stubs no-op en tests JVM puros
            // (devolver valores por defecto en vez de lanzar "not mocked").
            isReturnDefaultValues = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.cardview)
    implementation(libs.androidx.recyclerview)
    // Kotlin coroutines (structured concurrency para red de procesos root y streaming de salida)
    implementation(libs.kotlinx.coroutines.android)
    // ViewModel + Lifecycle (UI state, ciclo de vida, repeatOnLifecycle)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
