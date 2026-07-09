import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

// Datos del negocio (nombre, teléfono del catálogo...) fuera del código y del
// repo: viven en secrets.properties (gitignored, ver secrets.properties.example).
val secrets = Properties().apply {
    val file = rootProject.file("secrets.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

fun secret(key: String, default: String): String = secrets.getProperty(key) ?: default

android {
    namespace = "com.undead85.messagebot"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.undead85.messagebot"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        buildConfigField("String", "BUSINESS_NAME", "\"${secret("BUSINESS_NAME", "el negocio")}\"")
        buildConfigField("String", "OWNER_NAME", "\"${secret("OWNER_NAME", "la dueña")}\"")
        buildConfigField("String", "OWNER_SHORT", "\"${secret("OWNER_SHORT", "la dueña")}\"")
        buildConfigField("String", "BUSINESS_LOCATION", "\"${secret("BUSINESS_LOCATION", "Chile")}\"")
        buildConfigField("String", "CATALOG_URL", "\"${secret("CATALOG_URL", "")}\"")
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.mediapipe.tasks.genai)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.kotlinx.coroutines.test)

    // ---------------------------------------------------------------
    // FUTURO — Integración del LLM local (elegir UNA de estas vías):
    //
    // 1) Google AI Edge / Gemini Nano (requiere dispositivos compatibles, API 31+):
    //    implementation("com.google.ai.edge.aicore:aicore:<version>")
    //
    // 2) MediaPipe LLM Inference (Gemma, Phi, etc. en formato .task):
    //    implementation("com.google.mediapipe:tasks-genai:<version>")
    //
    // 3) ONNX Runtime (modelos exportados a ONNX):
    //    implementation("com.microsoft.onnxruntime:onnxruntime-android:<version>")
    //
    // 4) llama.cpp: no hay artefacto oficial en Maven; se integra compilando
    //    la librería nativa con el NDK (CMake) y exponiéndola vía JNI.
    //
    // Verifica las versiones actuales en la documentación oficial antes de añadirlas.
    // ---------------------------------------------------------------
}
