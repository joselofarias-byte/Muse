# Muse Python Pack(tiempo de ejecución de Python incorporado)

Muse zona de pruebas de aplicacionesPython Herramienta de compilación Extended Runtime". Proporcionada para asistentes y complementos.**completamente integrado**de
Python 3.14 Entorno de ejecución que no depende del usuario para instalar Termux o cualquier aplicación externa.

## por qué es necesario

Tiempo de ejecución de nodo incorporado (`tools/runtime-pack`) resuelve la ecología JS;Python Ecología (procesamiento de datos,
La informática científica, la automatización de scripts y una gran cantidad de bibliotecas de terceros) requieren un conjunto equivalente de tiempos de ejecución. Las dos arquitecturas son isomórficas:
**La parte ejecutable va a jniLibs con el APK, y la parte de datos va a los activos y se descomprime cuando se usa por primera vez.**, para evitar
Android 10+ de W^X restricciones.

## Arquitectura

```
app/
├── src/main/jniLibs/<abi>/                    ← Parte ejecutable (distribuida con APK)
│   ├── libmuse_python.so                      ← Stub ejecutable PIE autoescrito (entrada)
│   ├── libpython3.14.so / libpython3.so       ← CPython intérprete
│   ├── libssl_python.so / libcrypto_python.so ← OpenSSL(tenga en cuenta el sufijo _python)
│   ├── libsqlite3_python.so                   ← SQLite
│   └── *.cpython-314-<abi>-linux-android.so   ← 58 extensiones C lib-dynload
└── src/main/assets/
    └── muse-python-assets.zip                 ← stdlib(Pitón pura)+ doble ABI sysconfigdata
```

**lado del consumidor**:`app/src/main/java/io/zer0/muse/runtime/PythonRuntime.kt`(camino /
Descomprimir / CA bundle / pip / entorno del subproceso).

## Tres diseños clave

### 1. ¿Por qué debería haber `libmuse_python.so`(talón autoescrito)

python.org de Android embeddable package **Sólo disponible `libpython3.14.so`, no
`bin/python3` archivo ejecutable**(Android No"Instalación a nivel del sistema Python"concepto, documentación oficial
Dice explícitamente que solo se puede utilizar en modo integrado).

Muse Adopte isomorfismo con el tiempo de ejecución de Node**modelo de subproceso**: Requiere una entrada ejecutable.
`app/src/main/jni/muse_python.c` Es un lanzador con decenas de líneas, usa `dlopen` Cargar
`libpython3.14.so` y llamar `Py_BytesMain`.

⚠️ **Debe construirse como ejecutable.**(`add_executable` + `PREFIX/SUFFIX` cobertura),
No se puede utilizar `add_library(SHARED)` —— La última salida `e_entry=0x0` y ninguno `PT_INTERP`,
Salte a la dirección 0 cuando SIGSEGV lo ejecute directamente (exit=139, medido).

usar `dlopen` En lugar de un enlace directo: las compilaciones normales de Gradle no requieren que se descargue el paquete Python
(El binario no está incluido en la biblioteca). Cuando no está listo para ejecutarse, solo las capacidades de Python no están disponibles, lo que no hará que todo el proyecto falle.

### 2. ¿Por qué C se extiende a jniLibs en lugar de assets

- **W^X**:Android 10+ Deshabilitar el acceso desde directorios grabables `execve`;C La extensión debe ser `dlopen`
  (asignado como memoria ejecutable), el directorio nativoLibraryDir de solo lectura es la única fuente confiable.
- **aislamiento arquitectónico**: extensión `.so` depende de ABI (aarch64 / x86_64 uno cada uno),
  Y stdlib es Python puro (común en todas las arquitecturas). Expandir `jniLibs/<abi>/` Naturalmente separados por ABI;
  Si se coloca un paquete de activos compartidos, las dos ABI se cubrirán entre sí debido al mismo nombre y a la diferente arquitectura.

Medición real:AGP Estará equivocado `lib` prefijado `.so`(`_ssl.cpython-314-....so`) empaquetados juntos
APK de `lib/<abi>/`, extráigalo a NativeLibraryDir durante la instalación.

### 3. Por qué no es necesario cambiar el nombre de patchelf (diferencia clave con el tiempo de ejecución de Node)

python.org paquete oficial**Ya**Se distinguen las bibliotecas con el mismo nombre que Node runtime:
`libssl.so` / `libcrypto.so` / `libsqlite3.so` El SONAME ya está `*_python.so`,
Y todas las extensiones lib-dynload `DT_NEEDED` todos apuntan a `*_python` Variantes.
Entonces el archivo con el mismo nombre que Node**Sin conflicto**, sin necesidad de procesamiento secundario patchelf
(También evita la trampa de tiras de Node).

## Dos errores específicos de Android (ya solucionados en el código)

1. **CA Certificado**:Android de `/system/etc/security/cacerts` El nombre del archivo es OpenSSL
   **old hash**(MD5), mientras OpenSSL 3.x presiona **new hash**(SHA1) buscar,
   `SSL_CERT_DIR` señalarlo `loaded certs=0` → TLS Informe CERTIFICATE_VERIFY_FAILED.
   `PythonRuntime.ensureCaBundle` Consolide las CA del sistema en un único paquete PEM.
   usar `SSL_CERT_FILE` Señalalo.
2. **`_sysconfigdata` ¿Es un archivo relacionado con ABI?**:stdlib adentro
   `_sysconfigdata__android_<abi>.py` Una copia por arquitectura;stdlib Cuando se utilizan activos compartidos
   Se debe colocar una copia de cada ABI, de lo contrario la otra arquitectura `import _sysconfigdata` periódico
   ModuleNotFoundError(pip/sysconfig No disponible directamente).

## reconstruir

Bajo WSL/Linux:

```bash
bash tools/python-pack/build-python-pack.sh both     # arm64 + x86_64(predeterminado)
bash tools/python-pack/build-python-pack.sh arm64
```

El producto se escribe directamente a `app/src/main/jniLibs/` con `app/src/main/assets/`
(Estas dos ubicaciones han sido gitignore y los binarios no están incluidos en la biblioteca).

## verificar

```bash
# Emulador (primero es necesario iniciar AVD y dispositivos adb en línea)
./gradlew :app:connectedDebugAndroidTest -PnoUniversal \
  "-Pandroid.testInstrumentationRunnerArguments.class=io.zer0.muse.runtime.PythonRuntimeSelfCheckTest"
```

14 Cobertura de autoprueba del artículo:stub existir → `python -V` → stdlib Descomprimir → Evaluación básica →
C extender (ssl/sqlite3/zlib/hashlib)→ Archivo IO → TLS red →
protocolo de guión → capa de herramientas JSON → propagación de errores → Llamada a función de complemento → Error de función faltante del complemento →
pip guía → pip Instale el paquete Python puro e impórtelo.

## Bloqueo de versión (2026-10)

| componentes | versión | Fuente |
|---|---|---|
| Python | 3.14.8 | python.org Android embeddable package |
| OpenSSL | Viene con el paquete | python.org |
| SQLite | Viene con el paquete | python.org |
| pip | 26.2.1 | Viene con el paquete ensurepip `_bundled` wheel |

> Python Licenciado bajo la licencia PSF; OpenSSL incluido con el paquete tiene licencia Apache-2.0.

## ⚠️ lista de trampas

1. **stub Debe ser un archivo ejecutable**:`add_executable` + `PREFIX/SUFFIX`,
   No disponible `add_library(SHARED)`(Ver Diseño 1 arriba).
2. **Se realizarán tres cambios simultáneamente al actualizar la versión.**: este directorio `PY_VER` → `PythonRuntime.PYTHON_VERSION`
   → `PythonRuntime.PYTHON_DATA_VERSION`(Este último tiene un sufijo extra para forzar la redescompresión).
3. **`extractNativeLibs` debe ser true**(build.gradle Aprobado
   `useLegacyPackaging = true` configuración), de lo contrario la biblioteca no se guardará en el disco y no podrá ejecutarse.
4. **16KB alineación de página**:Android 15+ Requisitos; Se cumplió con el paquete oficial y se verificará el script.
   No aprobar resultará en un fracaso directo.
5. **stdlib Cultivo**: Eliminar por defecto `test`(37MB)/`idlelib`/`turtledemo`/`tkinter`,
   No afecta la importación regular; si una biblioteca los requiere, cámbielos `PRUNE_DIRS`.

## Integridad de las descargas

`SHA256SUMS` fija los hashes SHA-256 publicados en `messageSignature.messageDigest`
de los archivos `.tar.gz.sigstore` oficiales de Python 3.14.8 en
`https://www.python.org/ftp/python/3.14.8/`. Al cambiar `PY_VER`, actualice también
los hashes desde esos metadatos oficiales. El script comprueba tanto la caché como
las nuevas descargas, rechaza errores HTTP y extrae de nuevo el paquete validado
para no reutilizar directorios incompletos o de otra versión. Esta comprobación
de integridad no sustituye una verificación de la firma Sigstore.
