#!/usr/bin/env python3
"""F-06: Script de lanzamiento de versión reentrante de GitHub(Reanudación del punto de interrupción + retroceso exponencial + verificación remota).

cuestiones históricas: Liberar dependencias una sola vez ncipollo action, Nerviosidad de la red/Volver a ejecutar después del error
Reconstruir Liberar o cargar activos nuevamente, Y el estado final remoto no se puede verificar. Este script utiliza gh CLI
(CI runner Preinstalado) Implementar publicación idempotente:

  1. Reanudar desde el punto de interrupción: Release Reutilizar si ya existe(no reconstruir), Si el recurso ya existe y tiene el mismo tamaño, se omitirá.;
  2. retroceso exponencial: Si la carga falla, presione 2s/4s/8s para retroceder y vuelva a intentarlo.(máximo 3 veces);
  3. control remoto: Una vez completado el lanzamiento, use la vista de lanzamiento de gh para comparar la lista de activos locales, Lo que falta es el fracaso.

Uso(CI release job):
  python3 ci/script/publish_release.py \
    --tag v1.0.76 \
    --body-file releases/v1.0.76/release_body.md \
    --artifacts "app/build/outputs/apk/release/*.apk" \
    --artifacts release/manifest.json

depender de: gh CLI Certificado(GitHub Actions Medio GITHUB_TOKEN entra en vigor automáticamente).
"""

import argparse
import glob
import hashlib
import json
import os
import subprocess
import sys
import time
from pathlib import Path

MAX_UPLOAD_RETRIES = 3
BACKOFF_BASE_SECONDS = 2


def run(cmd: list[str], check: bool = True) -> subprocess.CompletedProcess:
    """ejecutar comando, Lanza una excepción con contexto de salida en caso de error."""
    proc = subprocess.run(cmd, capture_output=True, text=True)
    if check and proc.returncode != 0:
        raise RuntimeError(
            f"El comando falló: {' '.join(cmd)}\nstdout: {proc.stdout[-2000:]}\nstderr: {proc.stderr[-2000:]}",
        )
    return proc


def sha256_of(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def release_exists(tag: str) -> bool:
    proc = run(["gh", "release", "view", tag, "--json", "id"], check=False)
    return proc.returncode == 0


def fetch_remote_assets(tag: str) -> list[dict]:
    """Devolver activos remotos [{name, size, digest}]."""
    proc = run(["gh", "release", "view", tag, "--json", "assets"])
    assets = json.loads(proc.stdout).get("assets", [])
    return [
        {
            "name": asset["name"],
            "size": asset["size"],
            "digest": asset.get("digest") or "",
        }
        for asset in assets
    ]


def create_or_reuse_release(tag: str, body_file: Path) -> None:
    if not body_file.is_file():
        raise RuntimeError(f"Las notas de la versión no existen: {body_file}")
    if release_exists(tag):
        # Si ya existe, el texto también se sincronizará para evitar crear manualmente Lanzamientos temporales dejando notas de vencimiento.
        cmd = ["gh", "release", "edit", tag, "--title", f"Muse {tag}"]
        cmd += ["--notes-file", str(body_file)]
        run(cmd)
        print(f"[Reanudar desde el punto de interrupción] Release {tag} Ya existe, Reutilizar y sincronizar texto")
        return
    cmd = ["gh", "release", "create", tag, "--title", f"Muse {tag}"]
    cmd += ["--notes-file", str(body_file)]
    run(cmd)
    print(f"[publicar] Release {tag} Creado")


def upload_with_retry(tag: str, asset: Path, remote_assets: dict[str, dict]) -> None:
    remote = remote_assets.get(asset.name)
    if remote is not None:
        local_digest = f"sha256:{sha256_of(asset)}"
        if remote["size"] == asset.stat().st_size and remote["digest"] == local_digest:
            print(f"[Reanudar desde el punto de interrupción] El activo existe y el resumen es consistente., Saltar carga: {asset.name}")
            return
        print(f"[Corrección] Los activos remotos son inconsistentes con los activos locales, Volver a cargar: {asset.name}")
    last_err: Exception | None = None
    for attempt in range(1, MAX_UPLOAD_RETRIES + 1):
        try:
            run(["gh", "release", "upload", tag, str(asset), "--clobber"])
            print(f"[subir] {asset.name} éxito(No. {attempt} intentos)")
            return
        except RuntimeError as e:
            last_err = e
            if attempt < MAX_UPLOAD_RETRIES:
                wait = BACKOFF_BASE_SECONDS * (2 ** (attempt - 1))
                print(f"[Inténtalo de nuevo] {asset.name} falló(No. {attempt} veces), {wait}s Vuelve a intentarlo más tarde")
                time.sleep(wait)
    raise RuntimeError(f"subir {asset.name} continuo {MAX_UPLOAD_RETRIES} falló: {last_err}")


def verify_remote(tag: str, local_assets: list[Path]) -> None:
    """Verifique el extremo remoto una vez completado: El nombre, el tamaño y el resumen de GitHub deben coincidir."""
    remote = {asset["name"]: asset for asset in fetch_remote_assets(tag)}
    missing = []
    for asset in local_assets:
        remote_asset = remote.get(asset.name)
        if remote_asset is None:
            missing.append(f"{asset.name} (deleción distal)")
            continue
        expected_size = asset.stat().st_size
        expected_digest = f"sha256:{sha256_of(asset)}"
        if remote_asset["size"] != expected_size:
            missing.append(
                f"{asset.name} (El tamaño no coincide: locales {expected_size} vs extremo lejano {remote_asset['size']})",
            )
        if remote_asset["digest"] != expected_digest:
            missing.append(
                f"{asset.name} (El resumen no coincide: locales {expected_digest} vs extremo lejano {remote_asset['digest'] or 'desaparecido'})",
            )
    if missing:
        raise RuntimeError(f"La verificación remota falló:\n" + "\n".join(f"  - {item}" for item in missing))
    print(f"[comprobar] activos remotos {len(local_assets)} El nombre del artículo, el tamaño y SHA-256 coinciden")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--tag", required=True, help="Como por ejemplo v1.0.76")
    parser.add_argument("--body-file", required=True, type=Path, help="release_body.md camino")
    parser.add_argument("--artifacts", action="append", required=True, help="activos glob(Se puede utilizar varias veces)")
    args = parser.parse_args()

    local_assets: list[Path] = []
    for pattern in args.artifacts:
        local_assets.extend(Path(p) for p in sorted(glob.glob(pattern)))
    local_assets = [a for a in local_assets if a.is_file()]
    if not local_assets:
        print("FATAL: No se han encontrado coincidencias con ningún archivo de recursos.")
        return 1
    print(f"[publicar] tag={args.tag}, activos {len(local_assets)} artículo: {[a.name for a in local_assets]}")

    try:
        create_or_reuse_release(args.tag, args.body_file)
        remote_assets = {asset["name"]: asset for asset in fetch_remote_assets(args.tag)}
        for asset in local_assets:
            upload_with_retry(args.tag, asset, remote_assets)
            # Permita que las rutas duplicadas en la misma ronda de ejecución también vean el estado de los activos recién cargados.
            remote_assets.pop(asset.name, None)
        verify_remote(args.tag, local_assets)
    except RuntimeError as e:
        print(f"FAIL: {e}")
        return 1
    print(f"PASS: Release {args.tag} Lanzamiento completado y verificado")
    return 0


if __name__ == "__main__":
    sys.exit(main())
