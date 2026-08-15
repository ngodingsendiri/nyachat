plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.compose)
  alias(libs.plugins.google.devtools.ksp)
  alias(libs.plugins.roborazzi)
  alias(libs.plugins.secrets)
  alias(libs.plugins.google.services)
  alias(libs.plugins.google.firebase.crashlytics)
}

// L11: versi & versionCode dari gradle.properties (satu sumber kebenaran).
// CI & lokal bisa override via -PappVersion=... -PappVersionCode=... tanpa edit file.
// Skema rilis: tag r* (r1.0.0, r1.0.1, ...) — lihat GitHubUpdateChecker.
// PENTING (audit root 2026-08-14): fallback di bawah HANYA untuk kasus property
// tidak tersedia (gradle.properties dihapus/rusak) dan harus SELALU sinkron dengan
// nilai aktual di gradle.properties — kalau tidak, build diam-diam memakai versi
// usang. Workflow .github/workflows/build-apk.yml membaca fallback ini via regex.
private val appVersion: String = project.findProperty("appVersion") as String? ?: "r1.5.1"
private val appVersionCode: Int = (project.findProperty("appVersionCode") as String?)?.toIntOrNull() ?: 31

android {
  namespace = "com.startupmini.nyachat"
  compileSdk { version = release(36) { minorApiLevel = 1 } }

  defaultConfig {
    applicationId = "com.startupmini.nyachat"
    minSdk = 24
    targetSdk = 36
    versionCode = appVersionCode
    versionName = appVersion

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    // CATATAN KEAMANAN: TIDAK ada API key AI yang dikompilasi ke APK (key bisa
    // diekstrak). Gemini & OpenRouter murni BYOK via Pengaturan → Kunci API.
    // Dulu ada buildConfigField OPENROUTER_API_KEY yang membakar key produksi ke
    // APK bila env CI diset — sudah dihapus (P1).
  }

  signingConfigs {
    create("release") {
      val keystorePath = System.getenv("KEYSTORE_PATH") ?: "${rootDir}/my-upload-key.jks"
      storeFile = file(keystorePath)
      storePassword = System.getenv("STORE_PASSWORD")
      keyAlias = "upload"
      keyPassword = System.getenv("KEY_PASSWORD")
    }
    // debug.keystore DI-COMMIT ke repo (PKCS12, password android, alias
    // androiddebugkey) supaya SHA-1 penandatangan DEBUG STABEL di semua build
    // (lokal maupun GitHub Actions). Sebelumnya CI membuat keystore acak baru
    // tiap build -> SHA-1 berubah-ubah -> Google Sign-In Firebase menolak app
    // dengan "gagal login". Dengan SHA-1 tetap, daftarkan sekali di Firebase
    // Console -> Pengaturan project -> Aplikasi Android -> SHA-1.
    create("debugConfig") {
      storeFile = file("${rootDir}/debug.keystore")
      storeType = "PKCS12"
      storePassword = "android"
      keyAlias = "androiddebugkey"
      keyPassword = "android"
    }
  }

  buildTypes {
    release {
      isMinifyEnabled = true
      isShrinkResources = true
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
      signingConfig = signingConfigs.getByName("release")
    }
    debug { signingConfig = signingConfigs.getByName("debugConfig") }
  }

  // Resource Firebase (default_web_client_id dkk.) dipertahankan di APK release
  // lewat tools:keep (app/src/main/res/values/keep.xml) — tanpa itu web client ID
  // hilang dari APK release -> login Google gagal dengan pesan "Google Sign-In
  // belum dikonfigurasi" walau Google sudah aktif di console.
  // (Catatan: DSL androidResources.keepSpecificResources tidak tersedia di AGP 9;
  //  PinConnectScreen membaca default_web_client_id via getIdentifier() dengan
  //  fallback null supaya app tetap kompil walau oauth_client kosong di JSON.)
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
  }
  buildFeatures {
    compose = true
    buildConfig = true
  }
  testOptions { unitTests { isIncludeAndroidResources = true } }
  // M12: migration test Room membaca skema historis dari app/schemas. Skema
  // ditambahkan ke aset debug (bukan test sourceSet) karena MigrationTestHelper
  // di Robolectric membaca lewat context instrumentation/app — aset unit test
  // SDK tidak di-merge oleh AGP. Debug assets mudah-mudahan tidak berdampak ke
  // APK release (R8/shrink menghapusnya di buildType release).
  sourceSets {
    getByName("debug").assets.srcDirs("$projectDir/schemas")
  }
}

// Configure the Secrets Gradle Plugin to use .env and .env.example files
// to match the convention used in Web projects.
secrets {
  propertiesFileName = ".env"
  defaultPropertiesFileName = ".env.example"
  // OPENROUTER_API_KEY dikelola manual via buildConfigField (env) agar tidak duplikat
  ignoreList.add("OPENROUTER_API_KEY")
}

// Room: AppDatabase memakai exportSchema = true — skema per versi ditulis ke
// app/schemas supaya sejarah migrasi bisa direview & diverifikasi.
ksp {
  arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
  implementation(platform(libs.androidx.compose.bom))
  implementation(platform(libs.firebase.bom))
  implementation(libs.androidx.activity.compose)
  implementation(libs.androidx.compose.material.icons.core)
  implementation(libs.androidx.compose.material.icons.extended)
  implementation(libs.androidx.compose.material3)
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.graphics)
  implementation(libs.androidx.compose.ui.text.google.fonts)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  implementation(libs.androidx.room.ktx)
  implementation(libs.androidx.room.runtime)
  // implementation(libs.androidx.security.crypto)  // REMOVED: migrasi ke SecureStorage (Android Keystore)
  implementation(libs.firebase.firestore)
  // Relay AI server (FASE 4): memanggil Cloud Function aiComplete — SDK ini
  // otomatis melampirkan Firebase Auth ID token (user login) ke callable.
  implementation(libs.firebase.functions)
  implementation(libs.firebase.auth)
  implementation(libs.firebase.crashlytics)
  // 3.7: notifikasi chat real-time — FCM data message ditampilkan
  // FirebaseMessagingService (versi dikelola firebase-bom).
  implementation(libs.firebase.messaging)
  implementation(libs.androidx.credentials)
  implementation(libs.googleid)
  implementation(libs.androidx.credentials.play.services.auth)
  implementation(libs.play.services.auth)
  implementation(libs.kotlinx.coroutines.play.services)
  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.kotlinx.coroutines.core)
  implementation(libs.okhttp)
  testImplementation(libs.androidx.compose.ui.test.junit4)
  testImplementation(libs.androidx.core)
  testImplementation(libs.androidx.junit)
  testImplementation(libs.junit)
  testImplementation(libs.androidx.room.testing)
  testImplementation(libs.org.json)
  testImplementation(libs.kotlinx.coroutines.test)
  testImplementation(libs.robolectric)
  testImplementation(libs.roborazzi)
  testImplementation(libs.roborazzi.compose)
  testImplementation(libs.roborazzi.junit.rule)
  androidTestImplementation(platform(libs.androidx.compose.bom))
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  androidTestImplementation(libs.androidx.espresso.core)
  androidTestImplementation(libs.androidx.junit)
  androidTestImplementation(libs.androidx.runner)
  debugImplementation(libs.androidx.compose.ui.test.manifest)
  debugImplementation(libs.androidx.compose.ui.tooling)
  "ksp"(libs.androidx.room.compiler)
}
