plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.ktlint)
    // R-BUILD-09: Kover instrumentación,Incluir agregación de cobertura raíz
    alias(libs.plugins.kover)
}

android {
    namespace = "io.zer0.muse.accessibility"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
    }

    // P3-3: Habilitado explícitamente AIDL — MuseAccessibilityService(Servidor)con AccessibilityClient(cliente)
    // Interfaz IAccessibilityProvider compartida,Generado a través de buildFeatures.aidl Stub/Proxy
    buildFeatures {
        aidl = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":common"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

// P4-5: Finalización del control de acceso a la cobertura — accessibility No había ninguna regla de verificación antes(El silencio no cuenta.).
// Establecer según la LÍNEA medida actual 1,7%(Los servicios son en su mayoría rutas de enlace AIDL/sistema,Difícil de cubrir con una sola prueba),La falla ocurre por debajo de este valor.
kover {
    reports {
        verify {
            rule {
                minBound(2)
            }
        }
    }
}
