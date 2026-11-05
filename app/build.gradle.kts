import org.gradle.api.tasks.testing.Test
import java.io.FileInputStream
import java.util.Properties

val keystorePropertiesFile = rootProject.file("keystore.properties")

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.ktlint)
    // Phase 2.1: Kover — Instrumentar el código de bytes de este módulo.,Datos mencionados en el informe de agregación raíz
    alias(libs.plugins.kover)
}

// Proyecto de mejora del rendimiento (2026-11-01): activa los informes de estabilidad y las métricas para el compilador Compose.
// Escriba únicamente informes de texto en compilación/ sin agregar paquetes ni cambiar el comportamiento del tiempo de ejecución; la función es separar "a quién no se puede omitir,
// ¿Qué clase se considera inestable?" pasa de la inferencia verbal a una lista verificable (app/build/compose_reports/).
composeCompiler {
    reportsDestination = layout.buildDirectory.dir("compose_reports")
    metricsDestination = layout.buildDirectory.dir("compose_metrics")
}

// Lite omite Node/npm y Python integrados; no cambia permisos ni puentes JavaScript.
val museLite = providers.gradleProperty("museLite").map(String::toBooleanStrict).getOrElse(false)

val museWebAssetsDir = layout.buildDirectory.dir("generated/museWebAssets").get().asFile
// Utilice Sincronizar en lugar de Copiar:web El resultado de cada compilación al final es un nuevo nombre de archivo nombrado según el hash del contenido.
// Copy Solo agregue pero no elimine, el hash antiguo permanecerá en el directorio de destino y se enviará junto con el APK (2026-11-01 Acumulado 57 pruebas reales,
// Y index.html solo hace referencia a 2 de ellos).Sync Los archivos que ya no existen en el directorio de origen en el directorio de destino se eliminarán después de copiarlos.
// Haga que el directorio generado sea estrictamente coherente con dist; el directorio de destino solo lo escribe esta tarea y el alcance de la eliminación es controlable.
val syncMuseWebAssets =
    tasks.register<Sync>("syncMuseWebAssets") {
        val webDist = rootProject.file("web/apps/client/dist")
        from(webDist)
        into(File(museWebAssetsDir, "muse-web"))
        onlyIf { webDist.isDirectory }
    }

// v2.2.1: Activos empaquetados de APK de proveedor independiente accesibles — Obtenga el producto exclusivo de cada variante
// (debug aplicación principal ↔ debug Provider;release aplicación principal ↔ release Provider,Mismo puñado keystore).
// El permiso de nivel de firma A11Y_BRIDGE requiere que la aplicación principal y el proveedor tengan la misma firma,Por lo tanto, la versión no permite revertir la variante de depuración.
abstract class SyncA11yProviderApkTask : DefaultTask() {
    @get:InputFile
    abstract val sourceApk: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun sync() {
        val targetDir = outputDir.get().asFile.resolve("a11y").apply { mkdirs() }
        sourceApk.get().asFile.copyTo(targetDir.resolve("accessibility-provider.apk"), overwrite = true)
    }
}

androidComponents {
    onVariants { variant ->
        val isRelease = variant.buildType == "release"
        val providerApk =
            rootProject.file(
                if (isRelease) {
                    "accessibility-provider/build/outputs/apk/release/accessibility-provider-release.apk"
                } else {
                    "accessibility-provider/build/outputs/apk/debug/accessibility-provider-debug.apk"
                },
            )
        val syncTask =
            tasks.register<SyncA11yProviderApkTask>(
                "syncA11yProviderApk" + variant.name.replaceFirstChar { it.uppercase() },
            ) {
                dependsOn(
                    if (isRelease) {
                        ":accessibility-provider:assembleRelease"
                    } else {
                        ":accessibility-provider:assembleDebug"
                    },
                )
                doFirst {
                    check(providerApk.isFile) {
                        "no encontrado Provider APK:${providerApk.path}" +
                            "(release Se requiere Keystore.properties para generar primero el archivo firmado Provider)"
                    }
                }
                sourceApk.set(providerApk)
            }
        variant.sources.assets?.addGeneratedSourceDirectory(syncTask, SyncA11yProviderApkTask::outputDir)
    }
}

// v2.2.1: Entrada jar del servidor de pantalla virtual assets(Escrito por identidad de shell en el primer uso /data/local/tmp).
// Nota:server Nunca instale ni participe en la verificación de firma,debug/release La aplicación principal puede compartir el producto variante de depuración.
val vdServerAssetsDir = layout.buildDirectory.dir("generated/vdServer").get().asFile
val syncVdServerJar =
    tasks.register<Copy>("syncVdServerJar") {
        dependsOn(":virtual-display-server:assembleDebug")
        val serverApk =
            rootProject.file("virtual-display-server/build/outputs/apk/debug/virtual-display-server-debug.apk")
        from(serverApk)
        into(File(vdServerAssetsDir, "vd"))
        rename { "vd-server.jar" }
        onlyIf { serverApk.isFile }
    }

tasks.named("preBuild") {
    dependsOn(syncMuseWebAssets)
    dependsOn(syncVdServerJar)
}

android {

    sourceSets.getByName("main").assets.srcDir(museWebAssetsDir)
    sourceSets.getByName("main").assets.srcDir(vdServerAssetsDir)

    if (museLite) {
        androidResources.ignoreAssetsPatterns += listOf("muse-runtime-assets.zip", "muse-python-assets.zip")
        // jniLibs contiene los runtimes descargados; las bibliotecas CMake y de dependencias se conservan.
        sourceSets.getByName("main").jniLibs.setSrcDirs(emptyList<String>())
    }

    namespace = "io.zer0.muse"
    compileSdk = 36

    // v2.2.x: Biblioteca nativa de Terminal PTY(autoescrito forkpty,Cero código de terceros;código fuente app/src/main/jni/)
    // ⚠️ Después de acceder, todas las compilaciones requieren NDK 27.0.12077973 + CMake 3.22.1(Ver flujo de trabajo de CI)
    ndkVersion = "27.0.12077973"

    externalNativeBuild {
        cmake {
            path = file("src/main/jni/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    defaultConfig {
        applicationId = "io.zer0.muse"
        buildConfigField("boolean", "EMBEDDED_RUNTIMES_ENABLED", (!museLite).toString())
        if (museLite) {
            externalNativeBuild.cmake.targets += listOf("pty", "muse_exec")
        }
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        minSdk = 26
        targetSdk = 36
        // v1.0.27 P0-1.1: El número de versión admite la inyección desde la propiedad Gradle,CI Extracción automática de la etiqueta git
        // prioridad: -PversionCode/-PversionName > variables de entorno > Valor predeterminado
        // Construir localmente con valores predeterminados,CI Pase ./gradlew assembleRelease -PversionName=1.0.87 inyectar
        // Una cadena vacía se considera no inyectada.(workflow_dispatch Volver al valor predeterminado cuando no hay etiqueta)
        // v1.0.90: Rectificación y finalización completas.(mercado de complementos/UIy accesibilidad/Seguridad y confiabilidad/CI barandilla)(CI todavía inyecta explícitamente compilaciones oficiales)
        // v1.0.91: versión de parche —— Se corrigió el bloqueo del área superior de la página de memoria, se corrigió la pestaña de la página del proveedor personalizada para llenar toda la pantalla
        // v2.0.0: puente del canal(Feishu/QQ/webhook recibir)/tarjetas interactivas/operaciones de pagina web/complementoUIpanel/
        //         OAuth conector/paquete de habilidades/Anotación de mensaje/Notificar autorización de puente/Ventana flotante de subagente.
        // v2.1.0: sistema de canales(WeChat/QQ/Feishu/Telegram/DingTalk cinco terminales, conversacionalización de canales, noticias de medios.)/
        //         sistema de entrega de productos/Correcciones de capacidad de la herramienta de catálogo de modelos/Establecer superposición de búsqueda/UI Pulido con localización.
        // v2.2.0: Sistema de conferencias de chat grupal/Herramientas para bajar de peso(estrechamiento en capas·Recorte de descripción·find_tools Búsqueda bajo demanda)/
        //         El asistente se cuida solo(Escritura y eliminación de memoria, inicio y detención de desinstalación de complementos)/Primer arranque y rehacer la primera pantalla/Sistema de iconos v1.
        // v2.2.1: terminales(Motor dual PTY autoescrito·xterm página terminal·Termux canal)/Accesible e independiente Provider APK
        //         (Actualizaciones principales de la aplicación sin interrupción.·Encuadernación bidireccional)/pantalla virtual v1(shell Servidor independiente·Captura de pantalla de un solo cuadro·input -d)/
        //         Mejoras en la automatización(GUI Agent anillo·Comentarios de falla de la herramienta·estrategia de mando)/Enlace de mensaje activo y reparación de escaneo de código WeChat.
        // v2.3.0: Las rondas de herramientas son ilimitadas por defecto/continuidad contextual(Resumen de la historia fuera de la ventana.·Instrucciones para el asistente de retención de rondas de herramientas)/
        //         Curar defectos de archivo de memoria(guardia de versión·Fidelidad del archivo·Recuperación única)/Jerarquía de permisos de herramientas(marca·verificación previa)/
        //         Parámetros de habilidades de palabras rápidas·MOOD salida de depuración·Diagnóstico de búsqueda de conocimiento.·Rediseño de la tarjeta de canal.
        // v2.4.0: Flujo de trabajo del agente celular/Pantalla virtual y control de aplicaciones./Node npm caja de arena/MCP stdio/
        //         Aislamiento de memoria y confiabilidad de RAG/Ciclo de vida de la voz/La conversación restaura la coherencia.
        // v2.6.0: Codex OAuth, mejoras en la experiencia del proveedor, acciones de acceso directo configurables y correcciones de límites de seguridad de A2A/Tasker.
        // v2.6.2: base de conocimientos/sesión/Habilidades/Conjunto de herramientas de gestión auxiliar,Codex OAuth Correcciones, inyección de encabezado de solicitud de retransmisión y correcciones de enrutamiento proactivo de mensajes.
        // v2.6.3: Modificar configuración→El chat colapsará (IllegalFormatConversionException),WebServer No se puede iniciar, la llamada a la herramienta falla silenciosamente, etc. issue #3~#7.
        // v2.6.5: Informe de auditoría completamente reparado + comentarios de los usuarios (resurrección después de eliminar el mensaje)/La respuesta se sobrescribe con nulo./Visibilidad de la barra de navegación)+ per-KB Configuración + reordenamiento en la nube.
        // v2.6.6: Pequeña transformación de teléfonos celulares (chat grupal/álbum de fotos/Rango visible de círculo de amigos./tabla de relaciones)+ Enlace completo del agente + ensamblaje de herramientas de tareas programadas.
        // v2.6.7: La página de configuración se ha reorganizado en 11 grupos según "Viene con qué preguntas", y las opciones fuera de lugar se han devuelto a sus posiciones originales;
        //          La ruta para reparar el backend permanente y otras páginas no está registrada; el servicio de reparación y mantenimiento carece de un canal de notificación.
        //          startForeground Chocar; Desactivar las optimizaciones de R8 para evitar VerifyError en métodos de Compose muy grandes.
        // v2.6.11: Fusión de selección múltiple de memoria (vista previa editable y deshacer)+ insignia de estado de la memoria;
        //          Sobrecarga de representación del desplazamiento del chat (en cachés de composición, marcos de imágenes y reorganización de la página de chat grupal por marco).
        // v2.6.12: Todo el pequeño teléfono celular se reescribe como un módulo independiente (arte propio, círculo de amigos, mensajes de voz, mensajes privados activos, entrada de grabación);
        //          Implementación y eliminación de tablas de datos de teléfonos celulares pequeños y antiguos (DB 121→122, omita sesiones telefónicas pequeñas y antiguas al restaurar copias de seguridad antiguas);
        //          La página de configuración se reorganiza en 9 grupos; el diálogo de voz se cambia al punto final manual;TTS/ASR Puede seguir a los proveedores;
        //          RAG La página de configuración muestra el backend real actual; Se corrigieron entradas huérfanas de memoria, puntos de aterrizaje de mensajes activos, fallas en el registro de transmisiones y ruido de registro.
        // v2.6.13: La página de configuración está subdividida en 14 grupos (cada uno ≤5 artículos, el índice de búsqueda se ha ampliado a 78 artículos); los teléfonos celulares pequeños están abiertos al público;
        //          Usabilidad para teléfonos celulares pequeños (contactos para iniciar el chat, presionar prolongadamente el menú, volver a intentarlo si falla el envío, conversación superior y clara);
        //          Chat grupal (crear un grupo, responder con hasta dos personas, verse en contexto); alinear los círculos de amigos con WeChat (deslizar hacia la izquierda y hacia la derecha en varias imágenes, eliminar comentarios,
        //          Tiempo relativo, como lista, cubierta de tarjeta de identificación); Los momentos y mensajes privados utilizan en su lugar el propio modelo del personaje y conversaciones recientes.
        // v2.6.14: Versión web: el teléfono celular abre el acceso LAN, otros dispositivos escanean el código para ingresar a PWA (cambiar/dirección/PIN/código QR);
        //          Shell de chat del host celular primero (área segura y manejo del teclado, cajón, cursor de transmisión, parada de generación, tarjeta de aprobación, desconexión y reconexión);
        //          Web Conjunto compartido de tokens de diseño con Android (con pruebas de detección de deriva)+ primitivos de componentes;
        //          Se ha agregado un nuevo comando de configuración/asistentes/proveedores/sesiones al plano de datos;
        //          Configure la página de inicio en la página web (14 Los artículos del grupo 49, redacción publicitaria e iconos son emitidos por el anfitrión); Optimización de la sobrecarga del hilo principal del enlace de chat.
        // v2.6.15: Solucione el problema de que no se puede iniciar la versión web del paquete de lanzamiento (R8 Se eliminó la construcción sin argumentos utilizada por la reflexión de Netty para crear el Canal.
        //          periódico Class ... does not have a public non-arg constructor);
        //          Se solucionó un problema por el cual JsSandbox no se inicializaba cuando se iniciaba el complemento, lo que provocaba un error de activación.
        // v2.6.16: Los cinco niveles de mensajería activa se pueden cambiar según la configuración del usuario: Silencioso 3 / Restringido 8 / Estándar 15 / Activo 30 / Adherencia ilimitada;
        //          Abolir la antigua fórmula de "Nivel piso + intimidad" para calcular la cuota (que resulta en sólo 5 publicaciones por día para personas pegajosas seleccionadas);
        //          Elimine los enlaces de cuota de reabastecimiento maxDailyMessages y GrantCredit que en realidad no son efectivos.
        // v2.6.17: Web El motor de servicio se cambió de Netty a CIO (Netty R8 eliminó dos reflexiones, lo que provocó que el servidor del paquete de versión no se iniciara);
        //          La cuota del archivo fijo en el libro mayor ya no muestra 2147483647, sino que se cambia a "Hoy ilimitado".
        // v2.6.18: La configuración se cambia a dos niveles: página de inicio 12 categorías (48 Entrada), haga clic en la categoría para ver las entradas y luego ingrese a la subpágina; buscar con migas de pan;
        //          Digitalización de listas de inscritos, página de inicio/Página de categoría/Buscar copia compartida;
        //          Web El final está alineado geométricamente con los componentes de la aplicación y se incluye en la validación (40 tokens de geometría + escaneo de valor desnudo CSS);
        //          fuente web/La combinación de colores se cambia para que sea coherente con la aplicación;WebServer Reinicie solo cuando cambie el campo de enlace (cambiar previamente el PIN también reiniciará e interrumpirá la conexión).
        versionCode = (project.findProperty("versionCode") as? String)
            ?.takeIf { it.isNotBlank() }
            ?.toIntOrNull()
            ?: System.getenv("VERSION_CODE")?.takeIf { it.isNotBlank() }?.toIntOrNull()
            // El valor inferior se suprime deliberadamente como centinela: cuando se olvida traer -PversionCode, el número de producto se puede ver de un vistazo como un paquete temporal.
            // Ya no se enviará como paquete oficial con el último número de versión real. Tome 999999 en lugar de 0/1,
            // Esto es para permitir que el paquete de depuración anule cualquier versión oficial instalada y no informe INSTALL_FAILED_VERSION_DOWNGRADE.
            ?: 999999
        versionName = (project.findProperty("versionName") as? String)
            ?.takeIf { it.isNotBlank() }
            ?: System.getenv("VERSION_NAME")?.takeIf { it.isNotBlank() }
            // Igual que arriba, el nombre también es centinela para evitar“El número avanza un espacio y el nombre permanece sin cambios.”La combinación vuelve a aparecer.
            ?: "0.0.0-dev"
    }

    signingConfigs {
        create("release") {
            // v1.89: Especifique una firma de versión independiente a través de keystore.properties(Mejoras de seguridad H-1).
            // Cree el archivo keystore.properties en el directorio raíz del proyecto cuando se lance oficialmente,contenido:
            //   storeFile=camino
            //   storePassword=Contraseña
            //   keyAlias=Alias
            //   keyPassword=Contraseña
            // Cuando falta, ya no recurrirá al almacén de claves de depuración; al ejecutar realmente la tarea de lanzamiento, recurrirá a lo siguiente taskGraph
            // guard Da un error explícito. De esta manera, aún se pueden ejecutar comprobaciones estáticas/de depuración normales, pero no se generarán APK firmados de depuración disfrazados de paquetes oficiales.
            if (keystorePropertiesFile.exists()) {
                val props = Properties()
                props.load(FileInputStream(keystorePropertiesFile))
                storeFile = file(props["storeFile"] as String)
                storePassword = props["storePassword"] as String
                keyAlias = props["keyAlias"] as String
                keyPassword = props["keyPassword"] as String
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("release")
            // Utilice la versión optimizada de las reglas predeterminadas (AGP 9 La versión sin optimización ya no es compatible).
            //
            // MessageBubble es una sola fila de más de 2000,70+ Componible de parámetros, uso de registro local.
            // Más de 200 plazas.R8 La optimización a nivel de registro en este volumen producirá dex con tipos inconsistentes.
            // Verificador de Android 14+: VerifyError.
            // Por lo tanto, utilice -dontoptimize a continuación para desactivar la optimización (conservando la ofuscación y la compresión).
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    testOptions {
        unitTests {
            // No burlado android.* El método devuelve el valor predeterminado,Evite que las llamadas de Logger/Log fallen en las pruebas unitarias de JVM
            isReturnDefaultValues = true
            // Habilite las pruebas de Robolectric para leer recursos de Android fusionados,evitar Resources$NotFoundException
            isIncludeAndroidResources = true
            // Robolectric 4.16+ En Java 21, es necesario abrir los módulos internos del JDK,
            // La característica FileDescriptor / AndroidInterceptors es la IllegalAccessException.
            // Lista tomada del funcionario de Robolectric Getting Started(Java 17+ requerido).
            all {
                // El SQLite nativo de Robolectric en esta máquina se activará en NativeOpen
                // EXCEPTION_ACCESS_VIOLATION(JVM Sólo cuelga y mira hs_err_pid*.log).
                // Cambie a la implementación pura de Java LEGACY para omitir la biblioteca nativa; También puede realizar la creación de tablas./Migrar/FTS declaración.
                it.systemProperty("robolectric.sqliteMode", "LEGACY")
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

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
        // P3-3: habilitar AIDL — IShellService(Shizuku UserService interfaz)Necesidad de generar Stub/Proxy
        aidl = true
    }

    // v1.89: packaging Configuración — Excluir archivos META-INF duplicados,Evite conflictos de construcción
    // Phase 6 6A: APK Optimización del volumen — Subcontrato por ABI(arm64-v8a / armeabi-v7a),Reducir el tamaño de un solo APK
    splits {
        abi {
            isEnable = true
            reset()
            // P0 Revisión: agregue x86_64 (el simulador local verifica el tiempo de ejecución integrado); evaluar si se debe retener antes de liberarlo
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            // Para la verificación diaria, use -PnoUniversal para omitir el paquete universal de 169 MB y usar solo el subpaquete ABI.
            isUniversalApk = providers.gradleProperty("noUniversal").isPresent.not()
        }
    }

    lint {
        abortOnError = true
        baseline = file("lint-baseline.xml")
        // Suppress NewApi warning for displayCutoutMode (minSdk 26, feature is API 27+)
        // but the theme is only applied on API 27+ devices via values-v27
        warning.add("NewApi")
        // Suppress MissingPermission for BluetoothAdapter.disable (runtime check in place)
        warning.add("MissingPermission")
        // Suppress unused resource warnings (many resources from auto-generated code)
        warning.add("UnusedResources")
        // i18n: Las cadenas no traducidas se tratan como error,intercepción de giro perdido
        error.add("MissingTranslation")
    }

    packaging {
        // P0 Tiempo de ejecución incorporado:native La biblioteca permanece descomprimida y colocada en el disco (extractNativeLibs=true),
        // Permite que el sandbox ejecute el tiempo de ejecución de Node integrado directamente desde NativeLibraryDir (libmuse_node.so)
        jniLibs {
            useLegacyPackaging = true
            // El binario de tiempo de ejecución incorporado se reescribió con patchelf para segmentar el diseño y se eliminó en el lado de Termux;
            // Se debe omitir el llvm-strip de AGP: eliminar el producto patchelf destruirá el segmento/encabezado del programa
            // La relación correspondiente conduce a SIGSEGV durante la ejecución (reproducida por medición real).
            keepDebugSymbols += listOf(
                "**/libmuse_node.so",
                "**/libc++_shared.so",
                "**/libssl.so",
                "**/libcrypto.so",
                "**/libcares.so",
                "**/libicui18n.so",
                "**/libicuuc.so",
                "**/libicudata.so",
                "**/libsqlite3.so",
                "**/libz.so",
                "**/libffi.so",
            )
        }
        resources {
            excludes +=
                listOf(
                    "META-INF/AL2.0",
                    "META-INF/LGPL2.1",
                    "META-INF/DEPENDENCIES",
                    "META-INF/LICENSE*",
                    "META-INF/NOTICE*",
                    "META-INF/*.kotlin_module",
                    // ktor-server-netty Introducir múltiples módulos Netty, Los siguientes archivos están duplicados en cada frasco.
                    "META-INF/INDEX.LIST",
                    "META-INF/io.netty.versions.properties",
                )
        }
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

// Lanzar pruebas completas que incluyen una gran cantidad de casos de uso de Robolectric/Compose;Reinicie las pruebas periódicamente worker
// Evite OOM en casos finales después de que una única JVM acumule recursos nativos/de composición durante mucho tiempo.
tasks.withType<Test>().configureEach {
    maxHeapSize = "2g"
    forkEvery = 200
}

dependencies {
    // Módulos en el proyecto
    implementation(project(":ai"))
    implementation(project(":memory"))
    implementation(project(":common"))
    // v1.97 gap7: :material3 módulo — DynamicScheme.toColorScheme() Expandir,
    // Para que CustomTheme genere completo según el color de la semilla ColorScheme
    implementation(project(":material3"))
    // P3-3: Módulo de Servicio de Accesibilidad — MuseAccessibilityService + IAccessibilityProvider AIDL
    implementation(project(":accessibility"))
    // v2.2.1: Módulo de contrato de pantalla virtual — IVirtualDisplayService AIDL + mano a mano constante
    implementation(project(":virtual-display-protocol"))
    // P0: pequeño módulo de teléfono celular — Viene con la biblioteca de la habitación.(MiniPhoneDb)Interfaz con el host MiniPhoneHost.
    // Dirección de dependencia unidireccional. app → miniphone,:miniphone No hay dependencia inversa de :app.
    implementation(project(":miniphone"))

    // P3-3: Shizuku SDK — Ejecutar comandos con privilegios de shell(Uno de los enrutadores de tres canales.,No es necesario root)
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)

    // AndroidX
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.documentfile)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.activity.compose)
    // v1.7: sistema SplashScreen API(androidx.core:core-splashscreen)
    implementation(libs.androidx.core.splashscreen)
    // v1.60-C: AppCompat(per-app cambio de idioma,Sistemas de soporte por debajo de Android 13)
    implementation(libs.androidx.appcompat)
    // Función1: Desbloqueo biométrico
    implementation(libs.androidx.biometric)
    // v1.104 P3: WorkManager — ScheduledTaskRunner Detras de escena,App Incluso si te matan, el sistema puede detenerte.
    implementation(libs.androidx.work.runtime.ktx)

    // Compose
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    // Material3 — Línea de versión independiente,No con Compose BOM.
    // 1.4.0 La versión oficial mueve MaterialExpressionTheme/MotionScheme a 1.5.0-alpha(comunicado oficial),
    // Y el tema depende de expressive API,Así que continúe en 1.4.0-alpha04.
    // El bloqueo de versiones está unificado en root build.gradle.kts y se maneja con resoluciónStrategy.force
    // (strictly Tendrá un duro conflicto con la restricción de plataforma del BOM,Utilice la fuerza en su lugar para ser más indulgente).
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    // Tabler Icons Compose(Biblioteca de iconos de línea,suplemento Material Icons)
    implementation(libs.composeIcons.tablerIcons)
    // v2.0.1: Haze — Compose backdrop blur(El fondo de la cápsula de la barra de búsqueda en la parte superior de la página de configuración está borroso;API 31+ tan borroso,Bajar de versión inferior)
    implementation(libs.haze)
    // v2.6: Motor dedicado de vidrio líquido(Compose 1.10+).
    implementation(libs.backdrop)
    implementation(libs.liquid)
    implementation(libs.androidx.navigation.compose)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // Glance Compose widgets de escritorio(Phase 12)
    implementation(libs.androidx.glance.appwidget)
    implementation(libs.androidx.glance.material3)

    // Koin
    implementation(platform(libs.koin.bom))
    implementation(libs.koin.android)
    implementation(libs.koin.androidx.compose)

    // Room
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    // v1.0.72: test Clase @Database para el conjunto de código fuente(Como GroupChatMemoryRepositoryTest's TestMuseDb)
    // Requiere generación KSP _Impl,De lo contrario, los informes de prueba de la unidad Robolectric ClassNotFoundException
    kspTest(libs.androidx.room.compiler)

    // DataStore
    implementation(libs.androidx.datastore.preferences)

    // Coil
    implementation(libs.coil.compose)
    implementation(libs.coil.svg)
    implementation(libs.coil.gif) // Phase 11.1.6: GIF Decodificación de imágenes en movimiento
    // v1.0.72: CameraX — Menú más"Vista previa de la foto"(Telegram estilo de vista en vivo)
    implementation(libs.camera.camera2)
    implementation(libs.camera.lifecycle)
    implementation(libs.camera.view)

    // OkHttp
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.okhttp.sse)
    // DNS/SSRF Prueba especial: verifique la dirección del socket realmente establecida por MockWebServer.
    testImplementation("com.squareup.okhttp3:mockwebserver:5.3.2")
    // v1.94: Jsoup — HTML analizar(Resultados de búsqueda + extracción de texto web_fetch,sustituto regex + Html.fromHtml)
    implementation(libs.jsoup)

    // Serialization & Coroutines
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    // Phase 8.6: PDF Extracción de texto(pdfbox-android,Apache 2.0)
    implementation(libs.tom.roush.pdfbox.android)
    // Phase 8.6: ML Kit reconocimiento de texto(Chino e inglés sin conexión OCR)
    implementation(libs.google.mlkit.text.recognition.chinese)
    implementation(libs.commons.compress)
    implementation(libs.junrar)
    implementation(libs.xz)

    // v1.134 P0-1: ONNX Runtime — Inferencia de reordenamiento de codificación cruzada/incrustación local.
    // El usuario ha confirmado la introducción.(onnxruntime-android 1.23.0,APK mayor volumen ~40MB).
    // Utilizado con OnnxEmbeddingProvider/OnnxRerankProvider,
    // El archivo del modelo no está integrado. APK(Evite la expansión del volumen),Importado por el usuario desde la página de configuración a filesDir/muse_onnx/.
    // Bajar automáticamente a LocalKeywordEmbeddingProvider/LocalRerankProvider cuando no esté disponible.
    implementation(libs.onnxruntime.android)

    // v1.49: Eliminar el reconocimiento de voz sin conexión de Vosk(com.alphacephei:vosk-android:0.3.47)
    // Razón:vosk-android native lib Por ABI aprox. 8-9MB,4 ITB total 34MB,El tamaño del APK es demasiado grande.
    // Cambiar a:Predeterminado a la nube ASR(DashScope/Step),Recurrir al sistema cuando no hay una clave API Intent(SpeechInput).

    // Phase 8.11: Ktor Servidor web integrado(CIO motor + JWT + ContentNegotiation + CORS)
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.cio)
    // v2.6.17: HTTPS Proporcionado por el sslConnector del motor CIO.Netty Motor retirado:
    // Se basa en dos reflexiones (ReflectiveChannelFactory construir canal,ResourceLeakDetector La inicialización estática busca métodos por nombre),
    // R8 Cuando el paquete de lanzamiento se confunde, se cortarán uno por uno, lo que se manifiesta como un NoClassDefFoundError directo cuando se inicia el servidor.
    implementation(libs.ktor.server.auth)
    // Sin hacer referencia a ktor-server-auth-jwt: sus credenciales/Referencia de ruta fallida com.auth0.jwk.JwkException,
    // Y se debe excluir jwks-rsa (tirar guayaba, entrar en conflicto con la capacidad futura de escucha de AndroidX).
    // El resultado de la clase faltante no es 401 sino NoClassDefFoundError, que StatusPages cambia a 500.
    // /ws El apretón de manos también falló por esta razón. Solo utilizamos firmas simétricas HMAC-SHA256, no se requiere rotación JWKS/RSA,
    // La autenticación es verificada por el proveedor portador de ktor-server-auth + el propio java-jwt (consulte HostAuth.kt).
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.ktor.server.cors)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.ktor.server.websockets)
    implementation(libs.auth0.java.jwt)

    // prueba
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    // v1.89: Mejoras en la infraestructura de prueba — Para pruebas posteriores de simulación/flujo y pruebas instrumentadas
    testImplementation(libs.mockk)
    testImplementation(libs.turbine)
    testImplementation(libs.androidx.room.testing)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core.ktx)
    // Compose UI prueba (Robolectric JVM local)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation("androidx.compose.ui:ui-test-junit4")
    testImplementation("androidx.compose.ui:ui-test-manifest")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.core.ktx)

    // LeakCanary Detección de pérdida de memoria(solo debug)
    debugImplementation(libs.leakcanary.android)
    // v1.55: cierto tokenizer(BPE codificación,cl100k_base — GPT-4/3.5 Universales,Otros modelos aproximados)
    implementation(libs.jtokkit)

    // v1.97: Generación y escaneo de códigos QR.(zxing Generar + imagen escaneada del código de barras del kit ML)
    implementation(libs.zxing.core)
    implementation(libs.mlkit.barcode.scanning)
}

// Seguridad de la versión: las compilaciones oficiales deben utilizar keystore.properties independientes y está prohibida la reversión silenciosa de las firmas de depuración.
// CI Solo depurar compilaciones+Comprobación estática(Activará algunas tareas de lanzamiento),usar -PreleaseSkipKeystoreCheck=true Saltar.
gradle.taskGraph.whenReady {
    val hasReleaseTask = allTasks.any { it.name.contains("Release") }
    val skipKeystoreCheck = project.findProperty("releaseSkipKeystoreCheck") == "true"
    if (hasReleaseTask && !skipKeystoreCheck && !keystorePropertiesFile.exists()) {
        throw GradleException("La compilación oficial carece de keystore.properties: primero configure la firma de lanzamiento y deshabilite la reversión de la firma de depuración.")
    }
    // Restricciones estrictas del número de versión: las compilaciones formales deben inyectar explícitamente nombre de versión/código de versión; la línea candidata predeterminada actual es 266/2.6.6.
    // La verificación temporal local se puede transferir -PreleaseSkipVersionCheck=true Saltar.
    val skipVersionCheck = project.findProperty("releaseSkipVersionCheck") == "true"
    val hasVersionName = project.hasProperty("versionName") || !System.getenv("VERSION_NAME").isNullOrBlank()
    val hasVersionCode = project.hasProperty("versionCode") || !System.getenv("VERSION_CODE").isNullOrBlank()
    if (hasReleaseTask && !skipVersionCheck && (!hasVersionName || !hasVersionCode)) {
        throw GradleException("El número de versión debe inyectarse en la compilación oficial: pase -PversionName/-PversionCode o configure VERSION_NAME/VERSION_CODE.")
    }
}
kover {
    reports {
        verify {
            rule {
                minBound(18)
            }
        }
    }
}
// Revisión de corrección (2.0 B-31): La regla de verificación anterior no tiene alcance variante,Todas las variaciones de koverVerify
// (Debug/Release espera)Tener un efecto. Si el futuro requiere real debug-only,Debe estar calificado por la API de variante de Kover aquí.
