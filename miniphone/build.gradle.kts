plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.ktlint)
}

android {
    namespace = "io.zer0.muse.miniphone"
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
        // Las pruebas de migración requieren crear una versión antigua de la biblioteca basada en el esquema exportado.:Trate el directorio de esquemas como un activo de prueba,
        // Lea directamente 1.json en la prueba para crear una tabla,No copie la declaración de creación de la tabla en el código
        getByName("test").assets.srcDir("$projectDir/schemas")
    }

    testOptions {
        unitTests {
            // Robolectric Requiere recursos fusionados para obtener Application Context(Room Para banco de memoria)
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
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

// La base de datos de este módulo es independiente del host. MuseDb,Los números de versión comienzan desde 1,Exporte el esquema como base para migraciones posteriores.
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    // AI Tipos públicos definidos por mensajes y herramientas.:La recuperación del personaje depende de la capacidad de generación inyectada por el anfitrión.,Pero la forma de la sesión está definida por este módulo.
    implementation(project(":ai"))

    // fachada de troncos(Logger):La generación programada en segundo plano debe poder decir "por qué no se envió" desde logcat
    implementation(project(":common"))

    // Compose(Shells y páginas de escritorio)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.runtime)
    implementation(libs.androidx.compose.ui)
    // Diseño/Canvas/Lazy La lista está toda ahí. foundation;material3 Simplemente trátelo como una dependencia de implementación.,No pasó al tiempo de compilación
    implementation("androidx.compose.foundation:foundation")
    implementation(libs.androidx.compose.material3)

    // ViewModel(El único poseedor del estado caparazón.)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    // Room
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // corrutina(Flow / suspend interfaz)
    implementation(libs.kotlinx.coroutines.android)

    // Koin
    implementation(platform(libs.koin.bom))
    implementation(libs.koin.android)
    implementation(libs.koin.androidx.compose)

    // Tecla de retorno del sistema(Salir de la página de chat)
    implementation(libs.androidx.activity.compose)

    // avatar/Cargando imagen:La versión es consistente con el host.(coil = 2.7.0),No citar nuevas versiones.
    implementation(libs.coil.compose)

    // Genera dinámicas regularmente en segundo plano.:WorkManager La versión es consistente con el host.(toml de work = 2.9.0),
    // No citar nuevas versiones. —— Host: la aplicación ya está ejecutando el mismo tiempo de ejecución,Las dos versiones deben tener la misma fuente.
    implementation(libs.androidx.work.runtime.ktx)

    // Configuraciones dentro del módulo(Enviar automáticamente al interruptor de Momentos y la última hora generada):No agregue campos a mp_profile,Tampoco se mueve Room schema
    implementation(libs.androidx.datastore.preferences)

    // prueba
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core.ktx)
    testImplementation(libs.kotlinx.coroutines.test)
    // Compose UI prueba(Robolectric locales JVM):tab Verifica el cambio, el orden de la lista y los estados vacíos.
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation("androidx.compose.ui:ui-test-junit4")
    testImplementation("androidx.compose.ui:ui-test-manifest")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
