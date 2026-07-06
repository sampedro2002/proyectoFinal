plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.eatfood.control.mobile"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.eatfood.control.mobile"
        minSdk = 29          // Android 10.0 (Q)
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"

        // URL del backend por defecto. 10.0.2.2 = host del PC visto desde el emulador.
        // En un teléfono real se cambia desde la pantalla de Ajustes de la app.
        buildConfigField("String", "DEFAULT_SERVER_URL", "\"http://10.0.2.2:8080\"")

        ndk {
            // Solo ARM: el SDK ZKTeco no distribuye .so para x86/x86_64.
            abiFilters += listOf("armeabi-v7a", "arm64-v8a")
        }
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
        isCoreLibraryDesugaringEnabled = true
    }
    kotlinOptions { jvmTarget = "17" }

    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources {
            // Exclusiones estándar de Compose
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            // Los JARs del SDK ZKTeco incluyen entradas META-INF que
            // colisionan con otros artefactos durante el empaquetado.
            excludes += "META-INF/DEPENDENCIES"
            excludes += "META-INF/LICENSE"
            excludes += "META-INF/LICENSE.txt"
            excludes += "META-INF/NOTICE"
            excludes += "META-INF/NOTICE.txt"
            excludes += "META-INF/*.SF"
            excludes += "META-INF/*.DSA"
            excludes += "META-INF/*.RSA"
        }
        // No comprimir los .so del SDK ZKTeco para que el enlazador JNI
        // los cargue directamente desde el APK sin descomprimir en disco.
        jniLibs.useLegacyPackaging = true
    }
}

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.2")

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.activity:activity-compose:1.9.3")

    // Jetpack Compose (BOM validado en este equipo)
    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Red: Retrofit + OkHttp + Gson
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-gson:2.11.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")

    // Persistencia segura de tokens
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // Escáner de códigos QR de Google Play Services (sin permiso de cámara ni UI propia).
    // Se usa para aprovisionar la URL del servidor desde un QR (el usuario no la teclea).
    implementation("com.google.android.gms:play-services-code-scanner:16.1.0")

    // Cola offline (Room) — reemplaza IndexedDB del frontend web
    val room_version = "2.7.0-alpha12"
    implementation("androidx.room:room-runtime:$room_version")
    implementation("androidx.room:room-ktx:$room_version")
    ksp("androidx.room:room-compiler:$room_version")

    // SDK del lector ZK9500 (ZKTeco Biometric SDK: zkandroidcore/fpreader/fingerservice).
    // Los .jar están en app/libs/ y las librerías nativas (.so) en src/main/jniLibs/.
    implementation(fileTree("libs") { include("*.jar", "*.aar") })
}
