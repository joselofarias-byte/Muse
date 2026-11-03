import java.util.Properties

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ktlint)
    // Phase 2.1: Kover — Instrumentar el código de bytes de este módulo.,Datos mencionados en el informe de agregación raíz
    alias(libs.plugins.kover)
}

android {
    namespace = "io.zer0.ai"
    compileSdk = 36

    buildFeatures {
        buildConfig = true
    }

    defaultConfig {
        minSdk = 26

        // v1.0.18: Inyectar modelo libre de SiliconFlow fallback key
        // prioridad: -P > variables de entorno > local.properties > PLACEHOLDER
        val freeModelKey =
            (project.findProperty("FREE_MODEL_KEY") as String?)
                ?: System.getenv("FREE_MODEL_KEY")
                ?: run {
                    val lp = rootProject.file("local.properties")
                    if (lp.exists()) {
                        val props = Properties()
                        lp.inputStream().use { props.load(it) }
                        props.getProperty("FREE_MODEL_KEY")
                    } else {
                        null
                    }
                }
                ?: "PLACEHOLDER"
        buildConfigField("String", "FREE_MODEL_KEY", "\"$freeModelKey\"")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // v2.1.0: JVM La ruta de prueba única llegará Logger(android.util.Log) — Igual que el módulo de la aplicación
    // Permitir que las API de Android no simuladas devuelvan valores predeterminados,en lugar de tirar "not mocked" Anormal.
    testOptions {
        unitTests {
            isReturnDefaultValues = true
            // Robolectric 4.16+ En Java 21, es necesario abrir los módulos internos del JDK(Manifiesto oficial de Java 17+).
            all {
                it.jvmArgs(
                    "--add-opens=java.base/java.lang=ALL-UNNAMED",
                    "--add-opens=java.base/java.util=ALL-UNNAMED",
                    "--add-opens=java.base/java.io=ALL-UNNAMED",
                    "--add-opens=java.base/java.net=ALL-UNNAMED",
                    "--add-opens=java.base/java.security=ALL-UNNAMED",
                    "--add-opens=java.base/java.text=ALL-UNNAMED",
                    "--add-opens=java.base/jdk.internal.access=ALL-UNNAMED",
                    "--add-opens=java.desktop/java.awt.font=ALL-UNNAMED",
                    "--add-opens=jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED",
                )
            }
        }
    }
}

dependencies {
    implementation(project(":common"))

    // v1.100: Compose runtime anotación(@Immutable),Se utiliza para marcar UIMessage/ToolCallInfo como inmutable,
    // Deje que el compilador de Compose omita reorganizaciones no válidas. Administrar versiones a través de BOM,Introduzca solo dependencias de anotaciones.
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.runtime)

    // OkHttp + SSE
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.okhttp.sse)

    // Serialization & Coroutines
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    // Koin(DSL module/single/get)
    implementation(platform(libs.koin.bom))
    implementation(libs.koin.android)

    // prueba
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.kotlinx.serialization.json)
    testImplementation("com.squareup.okhttp3:mockwebserver:5.3.2")
}
kover {
    reports {
        verify {
            rule {
                minBound(55)
            }
        }
    }
}
