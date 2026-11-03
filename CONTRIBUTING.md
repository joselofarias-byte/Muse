# Muse Guía de contribución

## ¡Bienvenidos aportes!

¡Gracias por tu interés en Muse! Ya sea para informar errores, enviar sugerencias de funciones, mejorar la documentación o enviar código, lo agradecemos.

## código de conducta

Este proyecto adopta el Código de conducta del contrato de colaborador. Por favor mantenga una atmósfera de comunicación respetuosa e inclusiva.

## Como contribuir

### informe Bug
1. Buscar problemas para ver si ya existe el mismo informe
2. De lo contrario, cree un nuevo problema y adjunte:
   - Modelo de dispositivo y versión de Android.
   - Pasos de reproducción (comportamiento deseado versus comportamiento real)
   - Registrar captura de pantalla o salida de adb logcat

### Enviar una sugerencia de función
1. Busque primero en Discusiones para asegurarse de que nadie lo haya mencionado.
2. Describa claramente los escenarios de uso y los resultados esperados en el problema.

### Enviar Pull Request

1. Fork Este repositorio y crea tu sucursal.
2. Siga el estilo de codificación existente (Kotlin estilo oficial)
3. Incluya pruebas unitarias para nuevas funciones.
4. asegurar `./ci/run_ci_checks.ps1 -Lane static` por (detekt,ktlint,lint y control de acceso a cobertura)
5. asegurar `./ci/run_ci_checks.ps1 -Lane unit` Todo pasó
6. Actualizar la documentación relacionada (README.md / release_notes.md Documentos explícitos como el directorio raíz)
7. Enviar PR a la sucursal principal

### entorno de desarrollo local
- Android Studio Ladybug (2024.2+) o superior
- JDK 21
- Android SDK 35
- Gradle 9.4.1 (wrapper Ya incluido)

### prueba
- CI Reglas de scripting e ingeniería:`./ci/run_ci_checks.ps1 -Lane ci-scripts` con `./ci/run_ci_checks.ps1 -Lane lanes`
- Control estático:`./ci/run_ci_checks.ps1 -Lane static`
- Pruebas unitarias del módulo:`./ci/run_ci_checks.ps1 -Lane unit`
- Construya el APK de depuración:`./ci/run_ci_checks.ps1 -Lane debug`
- Build Release APK: es necesario proporcionar la firma de lanzamiento y los parámetros de versión explícitamente, consulte `README.md` Proceso de etiqueta con CI

> **Errores de ejecutar compilaciones en paralelo en el mismo espacio de trabajo**:IDE Construir, ejecutar pruebas desde la línea de comandos o varias personas/Cuando se verifican varios agentes en paralelo, se borrarán las compilaciones iniciadas más tarde. `app/build/test-results/testDebugUnitTest/`, expresado como `java.nio.file.NoSuchFileException: …/binary/in-progress-results-*.bin`, y el directorio solo tiene el XML antiguo de la última vez. Este tipo de fracaso no es ni un aprobado ni un fracaso——La prueba no se ha realizado en absoluto. No lo trate como un error de prueba y cambie el código.
>
> Método de determinación: verifique si la marca de tiempo del resultado XML pertenece a esta ejecución y verifique el número de clases; usar `--tests "<paquete>.*"` Cuando se ejecuta el filtrado, el directorio de resultados debe contener un conjunto completo de clases y el directorio borrado no puede proporcionar un conjunto completo de resultados. Solo asegúrese de que no haya otras compilaciones ejecutándose y reejecutándose.

> **`compileDebugKotlin` falso positivo `UP-TO-DATE` el hoyo (Windows Medición real)**: Después de que un proceso externo (editor, script, otro agente) reescribe el código fuente,Gradle Es posible que el VFS del demonio no esté al tanto del cambio, por lo que `:app:compileDebugKotlin` Reportar directamente `UP-TO-DATE` y "éxito"——En este momento, "compilación aprobada" es un falso positivo y los cambios no ingresaron al compilador en absoluto. Confirmado `--stop` Reinicie la guardia,`--no-watch-fs` Ninguno de los dos se puede evadir de manera estable (ambos han vuelto a ocurrir), ni es un accidente.
>
> Método de juicio: mirar `app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/` Compruebe si la marca de tiempo de la clase correspondiente es posterior al último cambio de código fuente. Úselo cuando necesite confirmar que "realmente fue compilado" `--rerun`(Solo obliga a volver a ejecutar la tarea, las dependencias siguen siendo incrementales y el costo es pequeño); es necesario eliminar todos los efectos de caché antes de usar `--rerun-tasks`(Carrera con todo el peso, significativamente más lenta).

> **PowerShell comerá `--tests` comodín**:Escribe en pwsh `--tests "*Settings*"`, los comodines se tratarán como rutas expandidas en directorios `settings.gradle.kts`,Gradle Lo que se recibió fue un filtro de prueba que no existe. Informe `No tests found for given includes: [settings.gradle.kts]`. Este fallo no tiene nada que ver con la prueba en sí y cambiar el código es una pérdida de tiempo.
>
> Solución alternativa: utilice nombres de clase completos en su lugar (`--tests "io.zer0.muse.data.SettingsRepositoryTest"`) o ruta del paquete (`--tests "io.zer0.muse.data.*"`); suelte el comando cuando se deban usar comodines `cmd` en lugar de pwsh. De la misma manera, después de ejecutar, primero mire la cantidad de clases XML en el directorio de resultados, no solo mire `BUILD SUCCESSFUL`.

> **`kspDebugUnitTestKotlin` periódico `[MissingType]: Element '…' references a type that is not present` pozo**: La causa raíz normalmente no es el código, sino `app/build/intermediates/compile_app_classes_jar/…/bundleDebugClassesToCompileJar/classes.jar`(y homólogo `runtime_app_classes_jar/…/classes.jar`) fue escrito en una versión incompleta——Sólo R,AIDL,BuildConfig, de docenas a cientos de elementos, ninguna de las clases de Kotlin en el código fuente principal. Lo que es más problemático es que Gradle lo juzgará como `UP-TO-DATE`, tan claro `kspCaches`, Qing `generated/ksp` Todo fue en vano. En la construcción concurrente, ambos procesos se borran y reescriben al mismo tiempo. `built_in_kotlinc/…/classes` Es más fácil pisar cuando.

> Método de juicio:`bundleDebugClassesToCompileJar/classes.jar` El tamaño es sólo de aproximadamente 1 MB, el número de entradas es de varios cientos y no incluye `io/zer0/muse/` Tipo de negocio significa daño; comparar `app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes` El número de clases a continuación está completo.

> Solución: eliminar `app/build/intermediates/compile_app_classes_jar` con `app/build/intermediates/runtime_app_classes_jar` Simplemente ejecute los dos directorios nuevamente sin tocar el código fuente. Síntomas del mismo origen y lanzamiento de la fase de prueba. `NoClassDefFoundError`(La clase de código fuente principal no se puede obtener del mismo jar de tiempo de ejecución incompleto), el método de modificación es el mismo.

## Especificaciones del código

- Siguiendo el directorio raíz del repositorio AGENTS.md(Las disciplinas de ingeniería y los acuerdos de colaboración están sujetos a este documento.)
- Envíe información en chino o inglés y manténgala clara.

## Documentación

Los documentos externos se encuentran en el directorio raíz del almacén (README.md / README_EN.md / release_notes.md / manual-de-usuario.md esperar). Después de modificar el código, actualice el archivo .md correspondiente simultáneamente; Los documentos de diseño interno no se mantienen en el almacén.

## licencia

Al contribuir con código, usted acepta que su contribución tendrá la licencia GPL v3.
