#!/usr/bin/env bash
# =============================================================================
# Muse Runtime Pack Script de compilación (ejecutado en entorno WSL/Linux)
# -----------------------------------------------------------------------------
# Función: descargue el tiempo de ejecución de Node.js desde el repositorio oficial de Termux (node Programa principal + bibliotecas compartidas dependientes + npm),
#       Normalizado por patchelf (renombrado / SONAME / DT_NEEDED / RPATH=$ORIGIN) y salida:
#
#   app/src/main/jniLibs/<abi>/          ← Parte ejecutable (node + 9 bibliotecas dependientes)
#   app/src/main/assets/muse-runtime-assets.zip ← parte de datos (npm, JS puro)
#
# Uso:
#   bash tools/runtime-pack/build-runtime-pack.sh both     # arm64 + x86_64(predeterminado)
#   bash tools/runtime-pack/build-runtime-pack.sh arm64
#
# dependencia del medio ambiente (WSL Ubuntu Ejemplo):
#   sudo apt-get install -y curl ca-certificates patchelf
#
# Proceso de actualización (sincronice elemento por elemento al cambiar las versiones de nodo/dependencia):
#   1. Modifique la constante "bloqueo de versión" a continuación
#   2. Actualice RUNTIME_DATA_VERSION en app/.../runtime/MuseRuntime.kt (npm (cuando cambia la versión)
#   3. Si agrega una nueva biblioteca dependiente, agregue la lista jniLibs.keepDebugSymbols en app/build.gradle.kts
#   4. Después de la reconstrucción, ejecute RuntimeSelfCheckTest(connected prueba) verificación
#
# ⚠️ Trampas importantes (lecciones aprendidas con sangre y lágrimas, no las cambies):
#   AGP La tarea stripDebugDebugSymbols (llvm-strip) para ELF procesado por patchelf
#   Reorganiza el diseño de la sección, corrompe el encabezado del programa y la asignación de contenido y provoca SIGSEGV en tiempo de ejecución. Por lo tanto, todas las bibliotecas en tiempo de ejecución
#   Debe incluirse en keepDebugSymbols de build.gradle.kts para omitir tiras.
#   Método de verificación: comparación posterior a la construcción app/build/intermediates/stripped_native_libs/.../lib*.so
#   con app/src/main/jniLibs/.../lib*.so md5, debe ser consistente.
# =============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJ_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
WORK="${MUSE_RUNTIME_WORK:-/tmp/muse-runtime-pack}"

TERMUX_BASE="https://packages.termux.dev/apt/termux-main/pool/main"

# ─── versión bloqueada ───────────────────────────────────────────────────────────────
NODE_VER="26.12.1"
LIBCXX_VER="30"
OPENSSL_VER="1%3A3.6.5"   # URL en ':' Necesito escapar como %3A
CARES_VER="1.34.9"
ICU_VER="78.3"
SQLITE_VER="3.54.1"
ZLIB_VER="1.3.2"
NPM_VER="11.21.1"
LIBFFI_VER="3.8.0"

# ─── Mapeo de archivos: nombre del archivo de la entidad de origen -> nombre de salida (jniLibs solo aceptar lib*.so nombrar) ────────
declare -A FILEMAP=(
  ["libc++_shared.so"]="libc++_shared.so"
  ["libssl.so.3"]="libssl.so"
  ["libcrypto.so.3"]="libcrypto.so"
  ["libcares.so"]="libcares.so"
  ["libicui18n.so.78.3"]="libicui18n.so"
  ["libicuuc.so.78.3"]="libicuuc.so"
  ["libicudata.so.78.3"]="libicudata.so"
  ["libffi.so"]="libffi.so"
  ["libsqlite3.54.1.so"]="libsqlite3.so"
  ["libz.so.1.3.2"]="libz.so"
)

# ─── DT_NEEDED Mapeo: cadena original -> Nuevo nombre (incluido el nombre del enlace y el nombre de la entidad) ───────────
declare -A NEEDMAP=(
  ["libz.so.1"]="libz.so"
  ["libz.so.1.3.2"]="libz.so"
  ["libcrypto.so.3"]="libcrypto.so"
  ["libssl.so.3"]="libssl.so"
  ["libicui18n.so.78"]="libicui18n.so"
  ["libicui18n.so.78.3"]="libicui18n.so"
  ["libicuuc.so.78"]="libicuuc.so"
  ["libicuuc.so.78.3"]="libicuuc.so"
  ["libicudata.so.78"]="libicudata.so"
  ["libicudata.so.78.3"]="libicudata.so"
  ["libsqlite3.so.3.53.4"]="libsqlite3.so"
)

# ─── Termux Análisis de URL del paquete ─────────────────────────────────────────────────────
pkg_url() { # $1=paquete key  $2=abi(arm64|x86_64|all)
  local key="$1" abi="$2"
  # Este script se llama internamente arm64.Termux El almacén se llama aarch64;x86_64 Ambas partes están de acuerdo.
  [ "$abi" = "arm64" ] && abi="aarch64"
  case "$key" in
    nodejs)    echo "$TERMUX_BASE/n/nodejs/nodejs_${NODE_VER}_${abi}.deb" ;;
    libffi)     echo "$TERMUX_BASE/libf/libffi/libffi_${LIBFFI_VER}_${abi}.deb" ;;
    libcxx)     echo "$TERMUX_BASE/libc/libc%2B%2B/libc%2B%2B_${LIBCXX_VER}_${abi}.deb" ;;
    openssl)    echo "$TERMUX_BASE/o/openssl/openssl_${OPENSSL_VER}_${abi}.deb" ;;
    cares)      echo "$TERMUX_BASE/c/c-ares/c-ares_${CARES_VER}_${abi}.deb" ;;
    libicu)     echo "$TERMUX_BASE/libi/libicu/libicu_${ICU_VER}_${abi}.deb" ;;
    libsqlite)  echo "$TERMUX_BASE/libs/libsqlite/libsqlite_${SQLITE_VER}_${abi}.deb" ;;
    zlib)       echo "$TERMUX_BASE/z/zlib/zlib_${ZLIB_VER}_${abi}.deb" ;;
    npm)        echo "$TERMUX_BASE/n/npm/npm_${NPM_VER}_all.deb" ;;
    *) echo "!! Paquete desconocido: $key" >&2; exit 1 ;;
  esac
}

fetch_deb() { # $1=paquete key $2=abi $3=archivo objeto
  if [ ! -f "$3" ]; then
    echo "  Descargar $1 ($2) ..."
    curl -sSL --retry 3 --connect-timeout 20 -o "$3" "$(pkg_url "$1" "$2")"
  fi
}

unpack_deb() { # $1=deb Archivo $2=Directorio de salida
  local deb="$1" out="$2"
  rm -rf "$out"; mkdir -p "$out"
  (cd "$out" && ar x "$deb")
  local dt
  dt=$(cd "$out" && ls data.tar.* 2>/dev/null | head -1)
  [ -n "$dt" ] || { echo "!! $deb Ninguno data.tar"; exit 1; }
  (cd "$out" && mkdir -p data && tar xf "$dt" -C data)
}

# ─── Conjunto ABI único ────────────────────────────────────────────────────────────
build_abi() { # $1=arm64|x86_64
  local abi="$1"
  local base="$WORK/$abi"
  local debs="$base/debs"
  local work="$base/pack"
  # Android El nombre del directorio jniLibs no coincide con el nombre ABI de Termux
  local outabi="$abi"
  [ "$abi" = "arm64" ] && outabi="arm64-v8a"
  local outdir="$PROJ_ROOT/app/src/main/jniLibs/$outabi"

  echo "== [$abi] Descargar =="
  mkdir -p "$debs"
  for key in nodejs libcxx openssl cares libicu libsqlite zlib libffi; do
    fetch_deb "$key" "$abi" "$debs/$key.deb"
  done

  echo "== [$abi] Desempacar =="
  for key in nodejs libcxx openssl cares libicu libsqlite zlib libffi; do
    [ -d "$base/unpacked/$key" ] || unpack_deb "$debs/$key.deb" "$base/unpacked/$key"
  done

  echo "== [$abi] Recopilar archivos fuente =="
  rm -rf "$work"; mkdir -p "$work"
  local missing=0
  for f in "${!FILEMAP[@]}"; do
    local src
    src=$(find "$base/unpacked" -name "$f" -type f | head -1 || true)
    if [ -z "$src" ]; then echo "  !! no encontrado $f"; missing=1; continue; fi
    cp "$src" "$work/$f"
  done
  [ "$missing" = 1 ] && { echo "!! [$abi] Faltan dependencias, terminadas"; exit 1; }

  local node_src
  node_src=$(find "$base/unpacked/nodejs" -type f -name node | head -1)
  cp "$node_src" "$work/libmuse_node.so"

  echo "== [$abi] patchelf Proceso =="
  (cd "$work"
    for f in "${!FILEMAP[@]}"; do
      patchelf --set-soname "${FILEMAP[$f]}" "$f"
    done
    for f in *; do
      [ -f "$f" ] || continue
      for old in "${!NEEDMAP[@]}"; do
        new="${NEEDMAP[$old]}"
        [ "$old" = "$new" ] && continue
        patchelf --replace-needed "$old" "$new" "$f" 2>/dev/null || true
      done
      patchelf --set-rpath '$ORIGIN' "$f"
    done
    for f in "${!FILEMAP[@]}"; do
      new="${FILEMAP[$f]}"
      [ "$f" != "$new" ] && mv "$f" "$new"
    done
  )

  echo "== [$abi] 16KB verificación de alineación de página =="
  local fail=0
  for f in "$work"/*; do
    local al
    al=$(readelf -lW "$f" | awk '/LOAD/{print $NF; exit}')
    [ "$al" = "0x4000" ] || { echo "  !! $(basename "$f") align=$al"; fail=1; }
  done
  [ "$fail" = 1 ] && { echo "!! [$abi] Existe un archivo alineado que no es de 16 KB"; exit 1; }
  echo "  Todos los 16 KB alineados ✓"

  echo "== [$abi] salida -> $outdir =="
  mkdir -p "$outdir"
  cp "$work"/* "$outdir/"
  ls -lh "$outdir"
}

# ─── npm paquete (assets, una sola vez) ────────────────────────────────────────────
build_npm_assets() {
  local base="$WORK/npm"
  local stage="$base/stage"
  local outzip="$PROJ_ROOT/app/src/main/assets/muse-runtime-assets.zip"

  echo "== [npm] Descargar y descomprimir =="
  mkdir -p "$base/debs"
  fetch_deb npm all "$base/debs/npm.deb"
  [ -d "$base/unpacked" ] || unpack_deb "$base/debs/npm.deb" "$base/unpacked"

  rm -rf "$stage"; mkdir -p "$stage"
  local npm_dir
  npm_dir=$(find "$base/unpacked" -type d -path '*usr/lib/node_modules/npm' | head -1)
  cp -r "$npm_dir" "$stage/npm"
  echo "npm $(grep -m1 '"version"' "$stage/npm/package.json" | sed 's/.*: *"\([^"]*\)".*/\1/')" > "$stage/VERSION.txt"

  echo "== [npm] paquete -> $outzip =="
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
  both)  TARGET_LIST=(arm64 x86_64) ;;
  arm64) TARGET_LIST=(arm64) ;;
  x86_64) TARGET_LIST=(x86_64) ;;
  *) echo "Uso: $0 [both|arm64|x86_64]"; exit 1 ;;
esac

mkdir -p "$WORK"
for t in "${TARGET_LIST[@]}"; do build_abi "$t"; done
build_npm_assets

echo
echo "============================================================"
echo "La construcción está completa. Productos:"
echo "  app/src/main/jniLibs/{arm64-v8a,x86_64}/  (parte ejecutable)"
echo "  app/src/main/assets/muse-runtime-assets.zip (npm datos)"
echo "Recordatorio: ejecutar :app:connectedDebugAndroidTest -P...class=...RuntimeSelfCheckTest Hacer verificación."
