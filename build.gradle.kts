// nivel superior build.gradle.kts
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.ktlint) apply false
    alias(libs.plugins.detekt)
    // Phase 2.1: Kover aplicación de alto nivel,Resumen de la cobertura de 6 módulos como módulo de fusión
    // Cada submódulo todavía necesita el suyo alias(libs.plugins.kover) Sólo entonces se podrá insertar el código de bytes de este módulo.
    alias(libs.plugins.kover)
}

// Phase 2.1: Agregue los datos de cobertura de 6 módulos en root,Ejecute :koverXmlReport / :koverHtmlReport para generar un informe completo
dependencies {
    kover(project(":app"))
    kover(project(":ai"))
    kover(project(":memory"))
    kover(project(":common"))
    kover(project(":material3"))
    kover(project(":accessibility"))
}

// Phase 2.1: Configurar el formato de informe agregado — HTML + XML(Usar la ruta de salida predeterminada)

// R-CI-02: detekt Acceso,Uso unificado de la configuración del directorio raíz del almacén;Análisis de submódulo proyecto por proyecto.
detekt {
    config.setFrom(rootProject.files("detekt.yml"))
    buildUponDefaultConfig = true
}

subprojects {
    apply(plugin = "io.gitlab.arturbosch.detekt")
    extensions.configure<io.gitlab.arturbosch.detekt.extensions.DetektExtension> {
        config.setFrom(rootProject.files("detekt.yml"))
        buildUponDefaultConfig = true
        val baselineFile = project.file("detekt-baseline.xml")
        if (baselineFile.exists()) {
            baseline = baselineFile
        }
    }

    // Compose Mejora(2026.02.01 / Compose 1.10)adaptarse:
    // material3 Es una línea de versión independiente.,No con Compose BOM.BOM 2026.02.01 La restricción de la plataforma pondrá
    // material3 Y su material3-android top 1.4.0;La versión oficial de 1.4.0 tiene
    // MaterialExpressiveTheme/MotionScheme mudarse a 1.5.0-alpha,El tema del proyecto se basa en una API expresiva.
    // Por lo tanto, todo el módulo se ve obligado a bloquear material3 nuevamente en 1.4.0-alpha04.
    // El uso de fuerza en lugar de estrictamente: estrictamente provocará un conflicto grave con las restricciones de la lista de materiales, lo que provocará que el análisis informe directamente un error.
    // Nota:subprojects No se puede obtener la extensión del directorio de la versión libs dentro del alcance,Número de versión y libs.versions.toml
    // material3 ser consistente(Al actualizar esta versión, debe sincronizar aquí).
    val material3Version = "1.4.0-alpha04"
    configurations.configureEach {
        resolutionStrategy.force(
            "androidx.compose.material3:material3:$material3Version",
            "androidx.compose.material3:material3-android:$material3Version",
        )
    }

    // P4-1: ktlint Arreglo verde falso.
    // Este repositorio utiliza soporte Kotlin integrado para AGP 9(El módulo ya no se aplica org.jetbrains.kotlin.android),
    // ktlint-gradle 12.1.1 Maldita sea withId("org.jetbrains.kotlin.android") Conjunto de fuentes de Android de gancho,
    // El anzuelo nunca dispara → ktlintCheck Sólo comprobar .kts,Acción cero en .kt(No hay control de acceso).
    // Aquí está el ktlintKotlinSourceCheck de creación propia: utilice la CLI oficial de ktlint para escanear el código fuente de Kotlin de este módulo.,
    // Generado automáticamente para la primera ejecución ktlint-baseline.xml(Ya en stock),Agregar nuevas preguntas después no funcionará.;
    // También afirme que el archivo fuente no está vacío,Evite que el control de acceso degenere en una ejecución vacía nuevamente.
    pluginManager.withPlugin("org.jlleitschuh.gradle.ktlint") {
        val ktlintConfiguration =
            configurations.findByName("ktlint")
                ?: throw GradleException("ktlint configuration missing in project '$name' (ktlint plugin applied?)")
        // ktlint La configuración en sí no tiene atributos de consumo.,KMP Las dependencias se resolverán en la variante -sources(Falta KLogger, etc. durante el tiempo de ejecución).
        // Configuración analizable RUNTIME+LIBRARY autoconstruida,extendsFrom ktlint,Obtenga la variante binaria correcta.
        val ktlintCliClasspath =
            configurations.create("ktlintCliClasspath_${this.name}") {
                isCanBeConsumed = false
                isCanBeResolved = true
                extendsFrom(ktlintConfiguration)
                attributes {
                    attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage::class.java, Usage.JAVA_RUNTIME))
                    attribute(
                        Category.CATEGORY_ATTRIBUTE,
                        objects.named(Category::class.java, Category.LIBRARY),
                    )
                }
            }
        val ktlintKotlinSourceCheckTask = tasks.register<org.gradle.api.tasks.JavaExec>("ktlintKotlinSourceCheck") {
            group = "verification"
            description = "P4-1: Corre real contra las fuentes de Kotlin ktlint CLI(Reemplace la verificación fallida de ktlintCheck .kt en AGP9)"
            classpath = ktlintCliClasspath
            mainClass.set("com.pinterest.ktlint.Main")

            val srcRoot = layout.projectDirectory.dir("src").asFile.invariantSeparatorsPath
            val reportFile = layout.buildDirectory.file("reports/ktlint/ktlintKotlinSourceCheck.txt")
            val checkstyleFile = layout.buildDirectory.file("reports/ktlint/ktlintKotlinSourceCheck.xml")
            val baselineFile =
                layout.projectDirectory.file("ktlint-baseline.xml").asFile.invariantSeparatorsPath
            inputs.files(layout.projectDirectory.dir("src").asFileTree.matching { include("**/*.kt") })
            outputs.files(reportFile, checkstyleFile)

            doFirst {
                check(!inputs.files.isEmpty) {
                    "ktlintKotlinSourceCheck would lint zero .kt files under $srcRoot — refusing to run an empty gate"
                }
                args(
                    "--relative",
                    "--baseline=$baselineFile",
                    "--reporter=plain,output=${reportFile.get().asFile.invariantSeparatorsPath}",
                    "--reporter=checkstyle,output=${checkstyleFile.get().asFile.invariantSeparatorsPath}",
                    "src",
                )
            }
            doLast {
                // Informar sobre la base de una afirmación no nula: Agregue la cantidad real de archivos lint .kt(del conjunto de fuentes de entrada de tareas,Sin marcador de posición).
                val linted = inputs.files.files.size
                reportFile.get().asFile.appendText("\n@linted-file-count=$linted\n")
            }
        }
        // P4-1b: Dar formato a las tareas reflejadas con klintKotlinSourceCheck —— misma copia CLI/classpath,Realmente ejecute ktlint -F.
        // por qué es necesario: check cinturón de trabajo --baseline,El problema de inventario afectado por la línea base no se solucionará bajo -F.,
        // Por lo tanto, debe haber una copia de la deuda formateada para limpiar el stock."sin baseline"entrada;Anteriormente sólo se podía montar a mano. java -cp,No reproducible.
        // Uso:
        //   ./gradlew :app:ktlintKotlinSourceFormat                      # Módulo completo
        //   ./gradlew :app:ktlintKotlinSourceFormat "-PktlintPaths=src/main/java/io/zer0/muse/MainActivity.kt,src/test"  # Definir ruta
        // Nota: El módulo debe reconstruirse después de formatear ktlint-baseline.xml(ver PROJECT_GUIDE §6).
        // El valor debe obtenerse durante el periodo de configuración. —— Este almacén está abierto. configuration cache,Si el proyecto se toca durante la ejecución, la construcción fallará directamente.
        val formatPaths =
            (project.findProperty("ktlintPaths") as? String)
                ?.split(',')
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                ?.takeIf { it.isNotEmpty() }
                ?: listOf("src")
        val missingFormatPaths = formatPaths.filter { !project.file(it).exists() }
        // Durante el período de configuración, las referencias relacionadas con el proyecto se convierten en cadenas puras. —— Periodo de ejecución(lambdas)Mantener Project explotará el caché de configuración.
        val formatProjectDir = layout.projectDirectory.asFile.invariantSeparatorsPath
        tasks.register<org.gradle.api.tasks.JavaExec>("ktlintKotlinSourceFormat") {
            group = "verification"
            description = "P4-1b: Formateo local del código fuente Kotlin de este módulo utilizando la CLI de ktlint(sin baseline,Deuda existente que se puede liquidar)"
            classpath = ktlintCliClasspath
            mainClass.set("com.pinterest.ktlint.Main")
            // El formateo cambiará el archivo fuente en su lugar.,No participa en el juicio incremental.(Realmente se ejecuta cada vez que lo ejecuto explícitamente.).
            outputs.upToDateWhen { false }
            // ktlint -F si"no puedo cambiar"violación de(fila extra larga/Ubicación de la anotación/Caso de nombre de archivo)Regresar 1 —— Esa es la lista de cosas por hacer,
            // No es un fracaso de la misión. El control de acceso es propiedad de ktlintKotlinSourceCheck: se necesita baseline,Las preguntas nuevas siempre son rojas.
            isIgnoreExitValue = true

            doFirst {
                check(missingFormatPaths.isEmpty()) {
                    "ktlintKotlinSourceFormat: path(s) not found under $formatProjectDir: $missingFormatPaths"
                }
                args("--relative", "-F", *formatPaths.toTypedArray())
            }
        }
        if (tasks.findByName("check") != null) {
            tasks.named("check") { dependsOn(ktlintKotlinSourceCheckTask) }
        }
    }
}
