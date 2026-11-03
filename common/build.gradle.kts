plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ktlint)
    // Phase 2.1: Kover — Instrumentar el código de bytes de este módulo.,Datos mencionados en el informe de agregación raíz
    alias(libs.plugins.kover)
}

android {
    namespace = "io.zer0.common"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    // AndroidX Core
    implementation(libs.androidx.core.ktx)

    // Coroutines
    implementation(libs.kotlinx.coroutines.android)

    // Serialization (Json helper)
    implementation(libs.kotlinx.serialization.json)

    // prueba
    testImplementation(libs.junit)
}

// P4-5: Finalización del control de acceso a la cobertura — common No había ninguna regla de verificación antes(El silencio no cuenta.).
// Establecer según la LÍNEA medida actual 22,6%,La falla ocurre por debajo de este valor.
kover {
    reports {
        verify {
            rule {
                minBound(65)
            }
        }
    }
}
