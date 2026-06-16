// build.gradle.kts — LINIA APP (app/build.gradle.kts)
// Przegląd RODO: Potok RODO, 10.06.2026
//
// ZNALEZIONE PRZY PRZEGLĄDZIE RODO:
//
//   [RODO-KRYTYCZNE] play-services-mlkit-document-scanner:16.0.0-beta1
//     Status: BETA w środowisku produkcyjnym + potencjalna RODO surface.
//     Problem: GMS Document Scanner (SCANNER_MODE_FULL) uruchamia Activity
//     zarządzaną przez Google Play Services. Obrazy dokumentów przechodzą
//     przez GMS runtime podczas skanowania.
//     Google oficjalnie deklaruje przetwarzanie "on-device", ale GMS zbiera
//     telemetrię niezależnie. Obecny onboarding mówi "nie wysyła żadnych danych
//     do zewnętrznych serwerów" — twierdzenie potencjalnie nieprecyzyjne.
//
//     OPCJE (wymagają decyzji architektonicznej):
//       A) Utrzymać GmsDocumentScanner + dodać DPA z Google (jako "podmiot
//          przetwarzający") + zaktualizować tekst onboardingu ("nie przechowuje")
//       B) Zastąpić GmsDocumentScanner bezpośrednim OCR ML Kit (text-recognition
//          jest already on-device) — mniej komfortowy UX skanowania
//       C) Zaktualizować do wersji stable gdy dostępna (nie -beta1)
//     Nie zmieniono w tym potoku — decyzja architektoniczna wymagana.
//
//   [RODO-MINOR] isMinifyEnabled = false (release)
//     Brak obfuskacji kodu w produkcji → łatwiejszy reverse-engineering
//     logiki pseudonimizacji, struktury tokenów, kluczy Keystore.
//     Art. 32: "odpowiednie środki techniczne i organizacyjne".
//     NIE włączono w tym potoku: SQLCipher wymaga dedykowanych reguł ProGuard
//     (konsumers.keep net.zetetic.* i powiązane klasy JNI) — ślepe włączenie
//     zepsuje openDatabase(). Wymaga osobnego zadania z testami regresyjnymi.
//
//   [INFO] text-recognition:16.0.1 — on-device, bez wysyłania danych. OK dla RODO.
//
//   [INFO] Brak nowych zależności dla PBKDF2 (Zadanie 3):
//     SecretKeyFactory i PBEKeySpec są w standardowym Android SDK (javax.crypto).
//
//   [MINOR] play-services-mlkit-document-scanner:16.0.0-beta1 → sprawdź stable
//   [MINOR] sqlcipher-android:4.5.4 → 4.6.x dostępna
//   [MINOR] kotlinx-coroutines-play-services:1.7.3 → 1.9.x dostępna
//   [MINOR] material-icons-extended:1.7.8 → sprawdź spójność z BOM Compose
//
// Poprzednie zmiany:
//   - Usunięto CameraX (4 zależności) — ścieżka kamery usunięta.
//   - Usunięto face-detection:16.1.7 (BUG-25) — nieużywana biblioteka.

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
            // [RODO-MINOR] isMinifyEnabled = false — brak obfuskacji.
            // Włączenie wymaga ProGuard rules dla SQLCipher (JNI natives).
            // Zadanie do osobnego potoku z testami regresyjnymi.
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
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    // Compose i AndroidX
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation("androidx.compose.material:material-icons-extended:1.7.8")
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)

    // ML Kit — on-device, dane nie opuszczają urządzenia. OK dla RODO.
    // [RODO v2.7] play-services-mlkit-document-scanner usunięty — GMS Scanner
    // uruchamiał Activity Google Play Services. Obrazy idą bezpośrednio przez
    // ML Kit text-recognition (on-device). Patrz ShareTargetActivity v2.7.
    implementation("com.google.mlkit:text-recognition:16.0.1")

    // SQLCipher — szyfrowanie bazy sesji
    implementation("net.zetetic:sqlcipher-android:4.5.4")
    implementation("androidx.sqlite:sqlite-ktx:2.4.0")

    // Coroutines — .await() na zadaniach ML Kit (Tasks API) w ShareTargetActivity
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.7.3")

    // Testy
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    // androidx.test:core — wymagane dla ApplicationProvider w SessionStoreTest
    androidTestImplementation("androidx.test:core-ktx:1.5.0")
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
