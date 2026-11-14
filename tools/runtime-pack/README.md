# Muse Runtime Pack(tiempo de ejecución de nodo incorporado)

Muse Una herramienta de compilación de "tiempo de ejecución extendido" para entornos limitados de aplicaciones. Proporcionado para el servidor MCP stdio, scripts de habilidades y terminales
Entorno de ejecución real de Node.js/npm, no depende de que los usuarios instalen Termux.

## Arquitectura

```
app/
├── src/main/jniLibs/<abi>/          ← Parte ejecutable (distribuida con APK, extraída a NativeLibraryDir durante la instalación)
│   ├── libmuse_node.so              ← Node 24.18.0 LTS(Termux construcción oficial,NDK r29,16KB alineación de página)
│   ├── libc++_shared.so / libssl.so / libcrypto.so / libcares.so
│   ├── libicui18n.so / libicuuc.so / libicudata.so / libsqlite3.so / libz.so
│   (Todo normalizado por patchelf: renombrado / SONAME / DT_NEEDED reescrito / RPATH=$ORIGIN, autónomo)
└── src/main/assets/
    └── muse-runtime-assets.zip      ← parte de datos (npm 11.20.0, JS puro, descomprimido en el primer uso)
```

**¿Por qué tiene capas así?**(Android W^X restricciones):
- targetSdk 29+ La aplicación no puede `exec` Archivos en su propio directorio de datos;
- pero `nativeLibraryDir`(APK El área de solo lectura extraída durante la instalación) no está restringida y se puede ejecutar/desbloquear de forma segura;
- Por lo tanto el "archivo ejecutable" debe acompañar al APK con `lib*.so` distribución nombrada (jniLibs especificación);
- "Datos puros" (npm JS) coloque los activos, descomprímalo en el directorio de datos y use (node Leer JS no requiere permiso ejecutivo).

**lado del consumidor**:`app/src/main/java/io/zer0/muse/runtime/MuseRuntime.kt`(ruta/descomprimir/entorno de subproceso).

## reconstruir

Bajo WSL/Linux:

```bash
sudo apt-get install -y curl ca-certificates patchelf   # Desechable
bash tools/runtime-pack/build-runtime-pack.sh both      # arm64 + x86_64 Reconstrucción completa
```

El producto se escribe directamente a `app/src/main/jniLibs/` con `app/src/main/assets/`(Estas dos ubicaciones han sido gitignore y los binarios no están incluidos en la biblioteca).

## verificar

```bash
# Emulador (primero es necesario iniciar AVD y dispositivos adb en línea)
./gradlew :app:connectedDebugAndroidTest -PnoUniversal \
  "-Pandroid.testInstrumentationRunnerArguments.class=io.zer0.muse.runtime.RuntimeSelfCheckTest" \
  "-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true"
```

8 Cobertura de autoprueba del artículo: el binario existe → exec → JS evaluar → Carga dinámica de biblioteca (zlib/crypto/ICU)→
TLS red → npm Descomprimir → npm ejecutar → symlink exec(P1 Conceptos básicos de resolución de nombres).

## ⚠️ Lista de trampas (no la pises)

1. **AGP strip Destruirá el producto patchelf.**:`stripDebugDebugSymbols`(llvm-strip) Correcto patchelf
   El ELF procesado reorganiza el diseño del segmento, lo que provoca confusión en el mapeo en tiempo de ejecución ySIGSEGV(Segundo accidente, sin salida, sin lápida).
   Todas las bibliotecas en tiempo de ejecución deben incluirse en `app/build.gradle.kts` de `jniLibs.keepDebugSymbols`.
   Verificación:`stripped_native_libs/.../lib*.so` con `jniLibs/.../lib*.so` md5 Debe ser consistente.
2. **`extractNativeLibs` debe ser true**(build.gradle Aprobado `useLegacyPackaging = true` configuración),
   De lo contrario, la biblioteca no se guardará y no se podrá ejecutar.
3. **Se realizarán tres cambios simultáneamente al actualizar la versión.**: Constantes de script en este directorio → `MuseRuntime.RUNTIME_DATA_VERSION`
   → `keepDebugSymbols`(Solo al agregar una nueva biblioteca).
4. **El enlace de instalación está intacto.**: Del instalador "Punching extracted elf file”Simplemente haga un agujero en la página cero para guardar espacio.
   El contenido no tiene pérdidas (probado para ser consistente con md5).

## Bloqueo de versión (2026-09)

| componentes | versión | Fuente |
|---|---|---|
| Node.js | 26.13.1 | Termux nodejs |
| npm | 12.2.2 | Termux npm (all) |
| libc++ | 30 | Termux libc++ |
| OpenSSL | 3.6.5 | Termux openssl |
| c-ares | 1.34.9 | Termux c-ares |
| ICU | 79.1 | Termux libicu |
| SQLite | 3.54.1 | Termux libsqlite |
| zlib | 1.4 | Termux zlib |

> El binario en tiempo de ejecución es parte del proyecto GPLv3 y la fuente es el paquete precompilado oficial de Termux (cada componente conserva su original).
> Licencia de código abierto), el script de compilación y el flujo de procesamiento se pueden encontrar en este directorio.
