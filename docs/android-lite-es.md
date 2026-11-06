# Muse Android Lite (edición española)

## Selección de la compilación

Lite es una opción explícita de Gradle: `-PmuseLite=true`, o la variable de
entorno equivalente `ORG_GRADLE_PROJECT_museLite=true`. Si no se especifica,
`museLite` es `false` y se conserva el comportamiento de la edición completa.
La propiedad acepta los valores `true` y `false`.

La opción genera `BuildConfig.EMBEDDED_RUNTIMES_ENABLED=false`. Excluye los
archivos `muse-runtime-assets.zip` y `muse-python-assets.zip`, así como las
bibliotecas descargadas en `app/src/main/jniLibs`. No excluye las bibliotecas
aportadas por dependencias. CMake sigue compilando `pty` y `muse_exec`, pero no
el ejecutable auxiliar de Python. Por tanto, Lite todavía necesita el SDK,
NDK y CMake configurados por el proyecto. CI instala JDK 21 explícitamente;
no necesita el resolver Foojay para descargar otro JDK.

## Motivo y alcance

Los paquetes opcionales de Node.js/npm y Python requieren una preparación
externa de binarios y datos. Lite permite compilar sin esa preparación,
sin eliminar las funciones centrales ni sustituir los intérpretes por un
mecanismo de ejecución menos protegido. La opción por sí sola no traduce
recursos: utiliza la localización española y la alternativa inglesa presentes
en esta rama.

## Funciones conservadas según el código fuente

- Chat de IA y conexiones con proveedores: no usan los intérpretes integrados
  para sus solicitudes habituales.
- Conversaciones, historial y ajustes: conservan sus implementaciones y datos.
- Herramientas JavaScript y complementos que utilizan `WebViewSkillEngine`.
- Infraestructura de terminal PTY, accesibilidad y pantalla virtual.
- Transporte MCP remoto que no necesita procesos locales Node/npm.
- Configuración de Android 16 (SDK objetivo 36) y compatibilidad ARM64.

La inicialización de `MuseApp` consulta `MuseRuntime.isReady` para anunciar
capacidades; no necesita descomprimir Node ni Python para arrancar.

## Funciones no disponibles

- Ejecución con Node.js integrado y sus módulos npm/npx.
- Servidores MCP stdio que dependen de esos ejecutables integrados.
- Scripts Python y complementos que utilizan `PythonSkillEngine`.

Las herramientas y opciones asociadas no se ocultan de la interfaz. Las
entradas de extracción y ejecución de los intérpretes devuelven un error
explícito en español que indica que la función requiere la edición completa.
Los diagnósticos pueden mostrar los intérpretes como no disponibles. MCP stdio
mantiene su manejo existente de errores de inicio; no se añade un intérprete
alternativo. Los archivos residuales de una edición completa no hacen que las
comprobaciones de disponibilidad de Lite devuelvan `true`.

## Seguridad y límites de verificación

No se cambian permisos, firma, controles del puente JavaScript, aislamiento de
archivos de WebView, validación de rutas ZIP ni construcción del entorno de los
subprocesos. Tampoco se deshabilita Android Lint. La variante Lite conserva el
identificador de aplicación: no es una instalación paralela de la edición
completa y las reglas normales de firma y actualización siguen aplicándose.

`LiteRuntimeTest` se ejecuta únicamente cuando los intérpretes están
deshabilitados; en la edición completa se omite mediante una suposición de
JUnit. Comprueba archivos residuales, inicialización y ejecución bloqueadas.
Las pruebas existentes del entorno y del contador de CPU siguen siendo
aplicables a ambas modalidades porque no inician los intérpretes.

Una compilación y sus pruebas no demuestran instalación ni funcionamiento en
un dispositivo. Antes de distribuir un APK, deben comprobarse el artefacto
real, su firma, arquitectura y recursos españoles. La prueba funcional debe
incluir arranque, configuración de un proveedor, envío de un mensaje,
reapertura del historial y los errores esperados de Node/Python. Se necesitan
credenciales válidas del proveedor para verificar respuestas reales de IA.

## Integración selectiva de upstream

La rama `integration/muse-lite-upstream` se basa en la revisión Lite `a5032873`
y adapta las correcciones seleccionadas, sin fusionar upstream completo. El
inventario y los límites de la revisión están en
[`upstream-integration-audit.md`](upstream-integration-audit.md).

- FTS5 consulta `PRAGMA compile_options`; si no hay resultados o falla la
  consulta, conserva el sondeo temporal anterior y su limpieza.
- Se reducen las cachés de imágenes y se liberan bajo presión de memoria,
  sin reciclar bitmaps que la interfaz pueda seguir utilizando.
- Se incorporan correcciones del vidrio oscuro, diagnósticos auxiliares,
  cierre del servicio sin Koin y selección segura de destinos proactivos.
- El reenvío entre canales queda limitado a destinatarios vinculados, con
  cuotas y protección de ecos, conservando la aprobación de envíos externos.
- La garantía proactiva es configurable y no elude silencio, cuota,
  actividad ni intentos recientes. Las conversaciones normales requieren
  habilitación explícita cuando las sesiones de agente están desactivadas.
- El actualizador solo consulta `joselofarias-byte/Muse`: valida procedencia,
  etiqueta estable, nombre y ABI del activo. Un digest presente debe ser
  SHA-256 válido; esto no equivale a verificar los bytes descargados. Android
  sigue comprobando la firma al instalar.

Lite genera únicamente el split `arm64-v8a`, sin APK universal. CI publica
`muse-lite-arm64` con un APK, `SHA256SUMS`, tamaño, badging y metadatos de
procedencia. El workflow de integración reproduce los commits ya revisados
sobre una base fijada y exige igualdad de árboles; no resuelve conflictos
ciegamente ni sobrescribe ramas remotas. El PR debe permanecer en borrador,
sin auto-merge, hasta completar CI y la revisión funcional Android.
