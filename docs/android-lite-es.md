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
NDK y CMake configurados por el proyecto.

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
