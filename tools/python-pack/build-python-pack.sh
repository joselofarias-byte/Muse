#!/usr/bin/env bash
# =============================================================================
# Muse Python Runtime Pack Script de compilación (ejecutado en entorno WSL/Linux)
# -----------------------------------------------------------------------------
# Función: descargue la versión oficial de python.org **Android embeddable package**, salida después del procesamiento:
#
#   app/src/main/jniLibs/<abi>/libpython3.14.so   ← Intérprete (parte ejecutable, enviado con APK)
#   app/src/main/jniLibs/<abi>/libpython3.so
#   app/src/main/jniLibs/<abi>/libcrypto_python.so
#   app/src/main/jniLibs/<abi>/libssl_python.so
#   app/src/main/jniLibs/<abi>/libsqlite3_python.so
#   app/src/main/jniLibs/<abi>/_*.cpython-314-<abi>-*.so  ← lib-dynload C Extensión (requiere dlopen)
#   app/src/main/assets/muse-python-assets.zip     ← stdlib Datos puros de Python (desempaquetados en filesDir)
#
# Uso:
#   bash tools/python-pack/build-python-pack.sh both      # arm64 + x86_64(predeterminado)
#   bash tools/python-pack/build-python-pack.sh arm64
#
# dependencia del medio ambiente (WSL Ubuntu Ejemplo):
#   sudo apt-get install -y curl ca-certificates
#
# ## División de distribución: por qué las extensiones C van a jniLibs en lugar de assets
#
# Android 10+(targetSdk 29+) de W^X La restricción impide que una aplicación se ejecute desde un directorio grabable; mientras que la extensión C
# Necesita ser abierto (asignado a la memoria ejecutable), la fuente más confiable es el APK extraído durante la instalación.**solo lectura**
# nativeLibraryDir(jniLibs Directorio canónico, todas las aplicaciones se abren desde aquí).
#
# Otra razón clave:**aislamiento arquitectónico**. La extensión .so depende de ABI (aarch64 / x86_64 uno cada uno),
# Y stdlib es Python puro (común en todas las arquitecturas). Expandir jniLibs/<abi>/ Naturalmente separados por ABI;
# Si se coloca un paquete de activos compartidos, las dos ABI se sobrescribirán debido al mismo nombre y a una arquitectura diferente.
#
# Medición real:AGP convertirá .so con prefijo no lib (_ssl.cpython-314-....so) empaquetados juntos
# APK de lib/<abi>/, extráigalo a NativeLibraryDir durante la instalación (consulte .gitignore del mismo origen
# jniLibs acuerdo).
#
# ## Por qué no es necesario cambiar el nombre de patchelf (diferencia clave con respecto al tiempo de ejecución de Node)
#
# python.org paquete oficial**Ya**Se distinguen las bibliotecas con el mismo nombre que Node runtime:
#   libssl.so / libcrypto.so / libsqlite3.so El SONAME ya está *_python.so,
#   Y las 67 extensiones lib-dynload DT_NEEDED apuntan a *_python Variantes.
# Entonces con Nodo libssl.so / libcrypto.so / libsqlite3.so Archivo con el mismo nombre**Sin conflicto**,
# No es necesario realizar un procesamiento secundario patchelf (evitando la introducción de la trampa de tiras de Node).
#
# ## Proceso de actualización (sincronizado elemento por elemento al cambiar las versiones de Python)
#   1. Modificar a continuación PY_VER
#   2. sincronizar app/src/main/java/io/zer0/muse/runtime/PythonRuntime.kt de
#      PYTHON_VERSION / PYTHON_DATA_VERSION
#   3. app/src/main/jni/muse_python.c Resolución del nombre de la biblioteca (si cambia el número de versión principal)
#   4. Después de la reconstrucción, ejecute PythonRuntimeSelfCheck(connected prueba) verificación
#
# ⚠️ Alineación de página:Android 15+ Requiere alineación de página de 16 KB (0x4000). El paquete oficial está satisfecho,
#    Este script se verificará; si no está calificado, fallará directamente.
# =============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJ_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
WORK="${MUSE_PY_WORK:-/tmp/muse-python-pack}"

# ─── versión bloqueada ───────────────────────────────────────────────────────────────
PY_VER="3.14.8"
PY_SERIES="3.14"                 # para el nombre del directorio libpython3.14.so/python3.14

# python.org Android embeddable package Denominación:python-<ver>-<arch>-linux-android.tar.gz
declare -A ARCH_MAP=(
  [arm64]="aarch64"
  [x86_64]="x86_64"
)
# jniLibs nombre del directorio
declare -A ABI_DIR=(
  [arm64]="arm64-v8a"
  [x86_64]="x86_64"
)

# ─── Requiere .so empaquetado (de prefijo/lib/) ─────────────────────────────────────
LIB_SO_FILES=(
  "libpython${PY_SERIES}.so"
  "libpython3.so"
  "libcrypto_python.so"
  "libssl_python.so"
  "libsqlite3_python.so"
)

# ─── stdlib Recortar: Eliminar inútil para dispositivos celulares/Subdirectorio muy grande ───────────────────────────────
# test 37MB(CPython kit de autoprueba),idlelib 2MB(IDE de escritorio),turtledemo 112KB,
# tkinter(Android Sin Tk). Estos no afectan las importaciones regulares.
PRUNE_DIRS=(
  "test"
  "idlelib"
  "turtledemo"
  "tkinter"
  "lib2to3"
)
# Corta la extensión C correspondiente en lib-dynload
PRUNE_DYNLOAD_GLOBS=(
  "_tkinter.*"
  "_test*.*"
)

fetch_pkg() { # $1=arch(aarch64|x86_64) $2=archivo objeto
  local arch="$1" out="$2"
  if [ ! -f "$out" ]; then
    local url="https://www.python.org/ftp/python/${PY_VER}/python-${PY_VER}-${arch}-linux-android.tar.gz"
    echo "  Descargar $url"
    curl -sSL --retry 3 --connect-timeout 20 -o "$out" "$url"
  fi
}

# Verifique que un ELF tenga una página de 16 KB alineada
check_align() { # $1=Archivo
  local al
  al=$(readelf -lW "$1" 2>/dev/null | awk '/LOAD/{print $NF; exit}')
  [ "$al" = "0x4000" ]
}

build_abi() { # $1=arm64|x86_64
  local abi="$1"
  local arch="${ARCH_MAP[$abi]}"
  local outdir="$PROJ_ROOT/app/src/main/jniLibs/${ABI_DIR[$abi]}"
  local base="$WORK/$abi"
  local tgz="$base/python.tar.gz"
  local unpack="$base/unpacked"

  echo "== [$abi] preparar Python $PY_VER ($arch) =="
  mkdir -p "$base"
  fetch_pkg "$arch" "$tgz"

  if [ ! -d "$unpack/prefix" ]; then
    rm -rf "$unpack"; mkdir -p "$unpack"
    tar xzf "$tgz" -C "$unpack"
  fi
  local prefix="$unpack/prefix"

  echo "== [$abi] Comprobar la alineación de la página =="
  local fail=0
  for f in "$prefix/lib"/*.so "$prefix/lib/python${PY_SERIES}/lib-dynload"/*.so; do
    [ -f "$f" ] || continue
    check_align "$f" || { echo "  !! $(basename "$f") No alineado con 16 KB"; fail=1; }
  done
  [ "$fail" = 1 ] && { echo "!! [$abi] Existe un archivo alineado que no es de 16 KB, finalizado"; exit 1; }
  echo "  16KB Alineación ✓"

  echo "== [$abi] Exportar biblioteca principal -> $outdir =="
  mkdir -p "$outdir"
  for name in "${LIB_SO_FILES[@]}"; do
    if [ ! -f "$prefix/lib/$name" ]; then
      echo "  !! desaparecido $name"; exit 1
    fi
    cp "$prefix/lib/$name" "$outdir/$name"
  done

  echo "== [$abi] Salida de la extensión C lib-dynload -> $outdir =="
  local dynload="$prefix/lib/python${PY_SERIES}/lib-dynload"
  local count=0
  for f in "$dynload"/*.so; do
    [ -f "$f" ] || continue
    local bn
    bn="$(basename "$f")"
    # Recorta las extensiones inútiles
    local skip=0
    for g in "${PRUNE_DYNLOAD_GLOBS[@]}"; do
      case "$bn" in $g) skip=1 ;; esac
    done
    [ "$skip" = 1 ] && continue
    cp "$f" "$outdir/$bn"
    count=$((count + 1))
  done
  echo "  Salida $count extensiones"

  echo "== [$abi] Completo =="
  ls -1 "$outdir" | wc -l | xargs echo "  jniLibs/$abi Número de archivos:"
}

# ─── stdlib paquete (assets, uso único; Python puro es universal en todas las arquitecturas)─────────────────
#
# Nota:stdlib Hay uno en **ABI Relacionado**Archivo `_sysconfigdata__android_<abi>.py`
# (pip / sysconfig / leer al compilar la extensión). Hay uno para cada uno de los dos ABI. No se puede poner sólo una copia.
# De lo contrario en otra arquitectura `import _sysconfigdata` Será ModuleNotFoundError.
# Procesamiento: coloque los archivos de ambas ABI en activos y ejecútelos de acuerdo con la situación real mediante PythonRuntime. ABI
# Cree un enlace simbólico/copia con el nombre esperado para la arquitectura actual.
build_stdlib_assets() {
  local stage="$WORK/stdlib-stage"
  local outzip="$PROJ_ROOT/app/src/main/assets/muse-python-assets.zip"

  echo "== [stdlib] preparar staging =="
  rm -rf "$stage"; mkdir -p "$stage/lib"
  # Basado principalmente en stdlib de arm64 (el contenido .py puro es consistente entre las dos arquitecturas)
  cp -r "$WORK/arm64/unpacked/prefix/lib/python${PY_SERIES}" "$stage/lib/python${PY_SERIES}"

  echo "== [stdlib] Cultivo =="
  for d in "${PRUNE_DIRS[@]}"; do
    if [ -d "$stage/lib/python${PY_SERIES}/$d" ]; then
      rm -rf "$stage/lib/python${PY_SERIES}/$d"
      echo "  Eliminar $d"
    fi
  done

  # lib-dynload El .so depende de ABI (aarch64 / x86_64 Se ha incluido un ejemplar de cada uno. jniLibs/<abi>/
  # Distribuir.assets Es compartido por las dos ABI y no se puede ampliar (diferentes arquitecturas con el mismo nombre se sobrescribirán entre sí).
  # Aquí solo queda una nota de marcador de posición, y el tiempo de ejecución viene dado por PythonRuntime.setupDynloadLinks() usar symlink
  # Apunte lib-dynload a NativeLibraryDir.
  local dl="$stage/lib/python${PY_SERIES}/lib-dynload"
  if [ -d "$dl" ]; then
    rm -rf "$dl"
    mkdir -p "$dl"
    cat > "$dl/README.txt" <<'EOF'
lib-dynload C extensions are shipped per-ABI in jniLibs/<abi>/ and symlinked here at runtime.
See PythonRuntime.setupDynloadLinks().
EOF
  fi

  # Limpiar __pycache__ (volumen + arquitectura independiente pero inútil)
  find "$stage" -name '__pycache__' -type d -prune -exec rm -rf {} + 2>/dev/null || true

  # v2.7: Parche en _sysconfigdata para todas las ABI (ABI Relacionado, consulte los comentarios del encabezado de la función).
  # El nombre esperado por la arquitectura actual está determinado por el tiempo de ejecución por ABI, quedando ambos.
  for _abi in arm64 x86_64; do
    _arch="${ARCH_MAP[$_abi]}"
    _scd="$WORK/$_abi/unpacked/prefix/lib/python${PY_SERIES}/_sysconfigdata__android_${_arch}-linux-android.py"
    if [ -f "$_scd" ]; then
      cp "$_scd" "$stage/lib/python${PY_SERIES}/"
      echo "  rellenar _sysconfigdata: $_abi"
    else
      echo "  !! no encontrado $_abi de _sysconfigdata: $_scd"
    fi
  done

  echo "Python ${PY_VER} stdlib" > "$stage/VERSION.txt"

  echo "== [stdlib] paquete -> $outzip =="
  [ -f "$outzip" ] && rm -f "$outzip"
  python3 - "$stage" "$outzip" <<'PYEOF'
import sys, zipfile, os
root, out = sys.argv[1], sys.argv[2]
count = 0
with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED, compresslevel=6) as z:
    for dirpath, dirnames, filenames in os.walk(root):
        for fn in filenames:
            full = os.path.join(dirpath, fn)
            z.write(full, os.path.relpath(full, root))
            count += 1
print(f"  zip Completo: {count} archivos, {os.path.getsize(out)/1e6:.2f}MB")
PYEOF
}

# ─── Proceso principal ─────────────────────────────────────────────────────────────────
TARGETS="${1:-both}"
case "$TARGETS" in
  both)   TARGET_LIST=(arm64 x86_64) ;;
  arm64)  TARGET_LIST=(arm64) ;;
  x86_64) TARGET_LIST=(x86_64) ;;
  *) echo "Uso: $0 [both|arm64|x86_64]"; exit 1 ;;
esac

mkdir -p "$WORK"
for t in "${TARGET_LIST[@]}"; do build_abi "$t"; done
build_stdlib_assets

echo
echo "============================================================"
echo "La construcción está completa. Productos:"
echo "  app/src/main/jniLibs/{arm64-v8a,x86_64}/  (libpython + Dependencias + extensión lib-dynload)"
echo "  app/src/main/assets/muse-python-assets.zip (stdlib datos)"
echo "Recordatorio: ejecutar :app:connectedDebugAndroidTest -P...class=...PythonRuntimeSelfCheck Hacer verificación."
