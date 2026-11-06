#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
TEST_DIR=$(mktemp -d)
trap 'rm -rf "$TEST_DIR"' EXIT

# Load the download functions without running either pack build.
source <(sed '/^# ─── Proceso principal/,$d' "$ROOT/tools/runtime-pack/build-runtime-pack.sh")
source <(sed '/^# ─── Proceso principal/,$d' "$ROOT/tools/python-pack/build-python-pack.sh")
SCRIPT_DIR="$TEST_DIR"
printf 'fixture\n' > "$TEST_DIR/data.tar.xz"
ar rc "$TEST_DIR/fixture.deb" "$TEST_DIR/data.tar.xz"
mkdir "$TEST_DIR/prefix"
printf 'fixture\n' > "$TEST_DIR/prefix/VERSION"
tar czf "$TEST_DIR/fixture.tar.gz" -C "$TEST_DIR" prefix
{
  printf '%s  zlib_%s_aarch64.deb\n' "$(sha256sum "$TEST_DIR/fixture.deb" | cut -d ' ' -f1)" "$ZLIB_VER"
  printf '%s  python-%s-aarch64-linux-android.tar.gz\n' "$(sha256sum "$TEST_DIR/fixture.tar.gz" | cut -d ' ' -f1)" "$PY_VER"
} > "$TEST_DIR/SHA256SUMS"

curl() {
  local out="" fail_flag=0
  while [ "$#" -gt 0 ]; do
    case "$1" in
      --fail) fail_flag=1 ;;
      -o) out="$2"; shift ;;
    esac
    shift
  done
  [ "$fail_flag" = 1 ] || { echo 'curl must reject HTTP failures' >&2; return 1; }
  echo call >> "$TEST_DIR/calls"
  case "$MODE" in
    http-error) printf '<html>404</html>' > "$out"; return 22 ;;
    invalid) printf '<html>not a package</html>' > "$out" ;;
    valid) cp "$FIXTURE" "$out" ;;
    *) echo 'Valid cache unexpectedly downloaded' >&2; return 1 ;;
  esac
}

for kind in deb python; do
  if [ "$kind" = deb ]; then
    FIXTURE="$TEST_DIR/fixture.deb"
    fetch() { fetch_deb zlib arm64 "$TEST_DIR/download"; }
  else
    FIXTURE="$TEST_DIR/fixture.tar.gz"
    fetch() { fetch_pkg aarch64 "$TEST_DIR/download"; }
  fi
  for MODE in http-error invalid; do
    rm -f "$TEST_DIR/download" "$TEST_DIR/download.part"
    if fetch; then echo "FAIL: $kind accepted $MODE" >&2; exit 1; fi
    [ ! -e "$TEST_DIR/download" ] && [ ! -e "$TEST_DIR/download.part" ]
  done
  printf 'corrupt cache' > "$TEST_DIR/download"
  MODE=valid
  fetch
  cmp "$FIXTURE" "$TEST_DIR/download"
  MODE=cache-only
  before=$(wc -l < "$TEST_DIR/calls")
  fetch
  [ "$(wc -l < "$TEST_DIR/calls")" = "$before" ]
  echo "PASS: $kind rejects HTTP/HTML failures, cleans partial files, repairs corrupt cache, reuses verified cache"
done
