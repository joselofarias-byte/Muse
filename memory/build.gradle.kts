plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.ktlint)
    // Phase 2.1: Kover — Instrumentar el código de bytes de este módulo.,Datos mencionados en el informe de agregación raíz
    alias(libs.plugins.kover)
}

android {
    namespace = "io.zer0.memory"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // Phase 2.2: Robolectric necesitar merged android resources(leer AndroidManifest + assets)
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
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

    // Phase 2.2 (Reparación): Deje que MigrationTestHelper lea el esquema JSON de los activos en la prueba unitaria.
    // La ubicación del esquema predeterminada solo se exporta a $projectDir/schemas,Pero MigrationTestHelper se carga desde activos.
    // Registre el directorio de esquemas para test assets,Robolectric Se fusionarán en activos fusionados para realizar pruebas de lectura.
    sourceSets {
        getByName("test").assets.srcDirs("$projectDir/schemas")
    }
}

// v1.78 (H4): Exportar Room schema JSON,Proporcionar una base para futuros escritos sobre migración.
// Reemplazo de fallbackToDestructiveMigration"Los datos se perderán durante la actualización de la versión."comportamiento
// Phase 2.2: MigrationTestHelper también lee la ruta para su verificación. v3→v8 Migración en varias etapas.
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(project(":common"))
    implementation(project(":ai"))

    // Room + FTS4
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // Serialization & Coroutines
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    // DataStore(Se utiliza para estados diarios y otros estados de continuación de puntos de interrupción.)
    implementation(libs.androidx.datastore.preferences)

    // Koin DSL(memory el módulo requiere module/single)
    implementation(platform(libs.koin.bom))
    implementation(libs.koin.android)

    // v1.55: cierto tokenizer(BPE codificación,LlmBudget Para corte suave)
    implementation(libs.jtokkit)

    // prueba
    testImplementation(libs.junit)
    // Phase 2.2: Room Migration Infraestructura de prueba — Alinear con la versión del módulo de la aplicación(room=2.8.4)
    testImplementation(libs.androidx.room.testing)
    // Robolectric: JVM correr hasta Android Context(SQLite/Cursor),No se requiere una máquina real
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core.ktx)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
kover {
    reports {
        verify {
            rule {
                minBound(45)
            }
        }
    }
}
