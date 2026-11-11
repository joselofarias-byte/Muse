#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
TEST_DIR=$(mktemp -d)
trap 'rm -rf "$TEST_DIR"' EXIT

# Load the download functions without running either pack build.
source <(sed '/^TARGETS=/,$d' "$ROOT/tools/runtime-pack/build-runtime-pack.sh")
source <(sed '/^TARGETS=/,$d' "$ROOT/tools/python-pack/build-python-pack.sh")
SCRIPT_DIR="$TEST_DIR"
printf 'fixture\n' > "$TEST_DIR/data.tar.xz"
ar rc "$TEST_DIR/fixture.deb" "$TEST_DIR/data.tar.xz"
mkdir "$TEST_DIR/prefix"
printf 'fixture\n' > "$TEST_DIR/prefix/VERSION"
tar czf "$TEST_DIR/fixture.tar.gz" -C "$TEST_DIR" prefix
{
  printf '%s  zlib_%s_aarch64.deb\n' "$(sha256sum "$TEST_DIR/fixture.deb" | cut -d ' ' -f1)" "$ZLIB_VER"
  printf '%s  libicu_%s_aarch64.deb\n' "$(sha256sum "$TEST_DIR/fixture.deb" | cut -d ' ' -f1)" "$ICU_VER"
  for abi in aarch64 x86_64; do
    printf '%s  libicu_%s_%s.deb\n' "$(sha256sum "$TEST_DIR/fixture.deb" | cut -d ' ' -f1)" "$ICU_VER" "$abi"
    printf '%s  nodejs_%s_%s.deb\n' "$(sha256sum "$TEST_DIR/fixture.deb" | cut -d ' ' -f1)" "$NODE_VER" "$abi"
  done
  printf '%s  python-%s-aarch64-linux-android.tar.gz\n' "$(sha256sum "$TEST_DIR/fixture.tar.gz" | cut -d ' ' -f1)" "$PY_VER"
} > "$TEST_DIR/SHA256SUMS"

curl() {
  local out="" fail_flag=0 retry_all_errors=0 retries=0 url=""
  while [ "$#" -gt 0 ]; do
    case "$1" in
      --fail) fail_flag=1 ;;
      --retry-all-errors) retry_all_errors=1 ;;
      --retry) retries="$2"; shift ;;
      -o) out="$2"; shift ;;
      https://*) url="$1" ;;
    esac
    shift
  done
  [ "$fail_flag" = 1 ] || { echo 'curl must reject HTTP failures' >&2; return 1; }
  echo call >> "$TEST_DIR/calls"
  case "$MODE" in
    http-error) printf '<html>404</html>' > "$out"; return 22 ;;
    invalid) printf '<html>not a package</html>' > "$out" ;;
    valid) cp "$FIXTURE" "$out" ;;
    archive-*)
      if [[ "$url" == "$TERMUX_BASE/"* ]]; then
        printf '<html>404</html>' > "$out"
        return 22
      fi
      [ "$url" = "$EXPECTED_ARCHIVE_URL" ] || { echo "Unexpected recovery URL: $url" >&2; return 1; }
      [ "$retry_all_errors" = 1 ] && [ "$retries" = 3 ] || {
        echo 'Recovery downloads must retry transport errors, including HTTP/2 failures' >&2
        return 1
      }
      case "$MODE" in
        archive-valid) cp "$FIXTURE" "$out" ;;
        archive-invalid) printf '<html>not a package</html>' > "$out" ;;
        archive-error) printf '<html>404</html>' > "$out"; return 22 ;;
      esac
      ;;
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

FIXTURE="$TEST_DIR/fixture.deb"
for package_abi in 'libicu arm64' 'libicu x86_64' 'nodejs arm64' 'nodejs x86_64'; do
  read -r package abi <<< "$package_abi"
  arch="$abi"
  [ "$abi" != arm64 ] || arch=aarch64
  if [ "$package" = libicu ]; then
    EXPECTED_ARCHIVE_URL="https://archive.org/download/termux_pkgs_archive_libi/libicu/libicu_${ICU_VER}_${arch}.deb"
  else
    EXPECTED_ARCHIVE_URL="https://github.com/joselofarias-byte/Muse/releases/download/runtime-pack-nodejs-26.13.0/nodejs_${NODE_VER}_${arch}.deb"
  fi
  for MODE in archive-error archive-invalid; do
    rm -f "$TEST_DIR/download" "$TEST_DIR/download.part"
    if fetch_deb "$package" "$abi" "$TEST_DIR/download"; then
      echo "FAIL: $package ($abi) accepted $MODE" >&2; exit 1
    fi
    [ ! -e "$TEST_DIR/download" ] && [ ! -e "$TEST_DIR/download.part" ]
  done
  MODE=archive-valid
  before=$(wc -l < "$TEST_DIR/calls")
  fetch_deb "$package" "$abi" "$TEST_DIR/download"
  [ "$(wc -l < "$TEST_DIR/calls")" -eq "$((before + 2))" ]
  cmp "$FIXTURE" "$TEST_DIR/download"
  MODE=cache-only
  before=$(wc -l < "$TEST_DIR/calls")
  fetch_deb "$package" "$abi" "$TEST_DIR/download"
  [ "$(wc -l < "$TEST_DIR/calls")" -eq "$before" ]
  echo "PASS: retired $package ($abi) uses the exact recovery URL, rejects HTTP/hash failures and reuses verified cache"
done
