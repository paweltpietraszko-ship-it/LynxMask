// build.gradle.kts (app)
// Zmiany vs poprzednia wersja:
//   - Usunięto CameraX (4 zależności): camera-core, camera-camera2,
//     camera-lifecycle, camera-view — ścieżka kamery usunięta (Zadanie 4).
//     Bez tych bibliotek APK zmniejsza się o ~1.5 MB.
//   - Usunięto face-detection:16.1.7 (BUG-25) — biblioteka nigdy nie była
//     używana w kodzie (brak wywołań FaceDetection.*); zwiększała APK bez celu.
//   - Pozostawiono: ML Kit text-recognition (używany w ShareTargetActivity OCR),
//     play-services-mlkit-document-scanner (GMS Document Scanner — ścieżka share),
//     SQLCipher, coroutines. Bez zmian.
//   - Dlaczego bezpieczne: CameraX i face-detection nie są importowane w żadnym
//     pliku który pozostaje po usunięciu CameraScreen.kt i OcrAnalyzer.kt.

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.lynxmask.app"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.lynxmask.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    // Wymagane dla ./gradlew test (testy JVM bez urządzenia).
    // Bez tego android.util.Log rzuca RuntimeException("Stub!") na JVM
    // i żaden test silnika nie przejdzie nawet kompilacji.
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
    buildFeatures {
        compose = true
        buildConfig = true  // TODO-3: wymagane dla BuildConfig.DEBUG w PseudonymEngine.kt
    }
}

dependencies {
    // Compose i AndroidX — bez zmian
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation("androidx.compose.material:material-icons-extended")
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)

    // ML Kit
    implementation("com.google.mlkit:text-recognition:16.0.1")
    implementation("com.google.android.gms:play-services-mlkit-document-scanner:16.0.0-beta1")
    implementation("com.google.mlkit:face-detection:16.1.7")  // IMAGE-REDACT F0: blur twarzy

    // SQLCipher
    implementation("net.zetetic:sqlcipher-android:4.5.4")
    implementation("androidx.sqlite:sqlite-ktx:2.4.0")

    // Coroutines — potrzebne dla .await() na zadaniach ML Kit (Tasks API)
    // Używane w ShareTargetActivity przy OCR obrazów i PDF
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.7.3")

    // Biometria — odcisk palca na ekranie logowania
    implementation("androidx.biometric:biometric:1.1.0")

    // Jetpack Security Crypto — EncryptedFile dla UserDictionary (AES-256-GCM, AndroidKeyStore)
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // Morfologik — POS tagging dla polskiego (offline, Java natywna, ~5 MB)
    // Zastępuje ręczne listy końcówek i OSOBA_DENYLIST przez zapytanie do słownika morfologicznego.
    implementation("org.carrot2:morfologik-polish:2.1.9")

    // Testy — bez zmian
    testImplementation(libs.junit)
    testImplementation("org.json:json:20231013")
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
