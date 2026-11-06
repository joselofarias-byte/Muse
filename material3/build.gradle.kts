// v1.97 gap7: :material3 módulo — Material Color Utilities capa puente.
//
// Responsabilidades:
//  - Monte directamente el código fuente de Kotlin ascendente de material-color-utilities a través de kotlin.srcDir
//    (Google material-foundation/material-color-utilities,Apache 2.0)
//  - proporcionar DynamicScheme.toColorScheme() Expandir,Esquema para generar espacio de color HCT.
//    Convertir para componer material3 ColorScheme,Para uso de CustomTheme.generateColorScheme
//
// decisiones de diseño:
//  - No introducido a través de dependencias de Maven,porque com.google.android.material:material-color-utilities
//    no publicado en Maven Central(2026-07 verificar);Usando el método de enlace directo del código fuente
//  - El código fuente ascendente se guarda en el subdirectorio material-color-utilities/kotlin/,Conveniente para el reemplazo general durante la actualización de la versión
//  - El módulo en sí es Android Library(Requiere componer esquema de color/tipo de color),no puro JVM

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ktlint)
    // Phase 2.1: Kover — Instrumentar el código de bytes de este módulo.,Datos mencionados en el informe de agregación raíz
    alias(libs.plugins.kover)
}

android {
    namespace = "io.zer0.material3"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    sourceSets {
        named("main") {
            // Monte el código fuente de Kotlin ascendente material-color-utilities en la ruta de compilación
            kotlin.srcDir("material-color-utilities/kotlin")
        }
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    // Mismo calibre que la aplicación:material3 Las versiones están bloqueadas uniformemente por la resoluciónStrategy.force del root.
    implementation(libs.androidx.compose.material3)
    // P4-5: suplemento — material3 Cero pruebas antes,La puerta de cobertura no puede caer en minBound.
    testImplementation(libs.junit)
}

// P4-5: Finalización del control de acceso a la cobertura — material3 Anteriormente no había pruebas ni reglas de verificación.
// Medición real después de agregar ColorSchemeBridgeTest LINE 39.2%,Presione 30 para configurar(Cualquier cosa menos que eso significa fracaso.).
kover {
    reports {
        verify {
            rule {
                minBound(95)
            }
        }
    }
}
