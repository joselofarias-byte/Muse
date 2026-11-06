#!/usr/bin/env python3
"""F-05: generar release/manifest.json(Publicar una sola fuente).

principio de fuente única: nombre de la versión/número de versión/DB versión/APK Suma de comprobación/Cotización de notas de la versión/Procedencia del código fuente/tiempo de generación
Todo producido aquí manifest, Guión de lanzamiento posterior/Actualizaciones en la aplicación/La verificación manual se basa en ello.,
Ponle fin a"El número de versión está disperso en muchos lugares y todos lo leen al publicarlo."resultando en inconsistencia.

Uso(CI release job Se llama después de la versión de compilación de APK):
  python3 ci/script/generate_release_manifest.py \
    --version-name 1.0.76 --version-code 175 \
    --apk app/build/outputs/apk/release/app-arm64-v8a-release.apk \
    --apk app/build/outputs/apk/release/app-universal-release.apk \
    --db-file app/src/main/java/io/zer0/muse/data/session/MuseDb.kt \
    --release-notes releases/v1.0.76/release_body.md \
    --out release/manifest.json

idempotente: La generación repetida de la misma versión sobrescribe directamente, Cuando el contenido es consistente, los bytes permanecen sin cambios.
"""

import argparse
import hashlib
import json
import re
import os
import sys
import tempfile
from datetime import datetime, timezone
from pathlib import Path


def sha256_of(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def db_version_of(db_file: Path) -> int:
    text = db_file.read_text(encoding="utf-8")
    m = re.search(r"version\s*=\s*(\d+)", text)
    if not m:
        raise SystemExit(f"FATAL: No se puede acceder desde {db_file} analizar DB version")
    return int(m.group(1))


def atomic_write_text(path: Path, payload: str) -> None:
    """Write a release index beside the target, then replace it atomically."""
    path.parent.mkdir(parents=True, exist_ok=True)
    descriptor, temp_name = tempfile.mkstemp(prefix=f".{path.name}.", suffix=".tmp", dir=path.parent)
    try:
        with os.fdopen(descriptor, "w", encoding="utf-8", newline="\n") as output:
            output.write(payload)
            output.flush()
            os.fsync(output.fileno())
        os.replace(temp_name, path)
    except BaseException:
        try:
            os.unlink(temp_name)
        except FileNotFoundError:
            pass
        raise


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--version-name", required=True, help="Como por ejemplo 1.0.76(tag ir v)")
    parser.add_argument("--version-code", required=True, type=int)
    parser.add_argument("--apk", required=True, action="append", type=Path)
    parser.add_argument("--db-file", required=True, type=Path)
    parser.add_argument("--release-notes", required=True, help="release_body.md ruta relativa")
    parser.add_argument(
        "--commit-sha",
        required=True,
        help="Compilación de código fuente SHA, generalmente pasado a GitHub Actions GITHUB_SHA",
    )
    parser.add_argument(
        "--source-ref",
        required=True,
        help="Ref/etiqueta de origen de compilación, generalmente pasada a GitHub Actions GITHUB_REF_NAME",
    )
    parser.add_argument("--out", default="release/manifest.json", type=Path)
    args = parser.parse_args()
    commit_sha = args.commit_sha.strip()
    source_ref = args.source_ref.strip()
    if not re.fullmatch(r"[0-9a-fA-F]{7,64}", commit_sha):
        print("FATAL: --commit-sha Debe tener entre 7 y 64 dígitos hexadecimales. commit SHA")
        return 1
    if not source_ref:
        print("FATAL: --source-ref no puede estar vacío")
        return 1

    missing_apks = [apk for apk in args.apk if not apk.is_file()]
    if missing_apks:
        print(f"FATAL: APK no existe {missing_apks}")
        return 1
    if not args.db_file.is_file():
        print(f"FATAL: DB El archivo no existe {args.db_file}")
        return 1
    release_notes_path = Path(args.release_notes)
    if not release_notes_path.is_file():
        print(f"FATAL: Las notas de la versión no existen {args.release_notes}")
        return 1
    if ".." in release_notes_path.parts:
        print("FATAL: --release-notes No puede contener el recorrido del directorio")
        return 1
    release_notes_ref = release_notes_path.name if release_notes_path.is_absolute() else release_notes_path.as_posix()

    apk_artifacts = [
        {
            # Solo se publica el nombre del activo y la ruta absoluta local del corredor de CI no se escribe en el manifiesto público.
            "name": apk.name,
            "sizeBytes": apk.stat().st_size,
            "sha256": sha256_of(apk),
        }
        for apk in args.apk
    ]
    primary_apk = args.apk[0]
    manifest = {
        "versionName": args.version_name,
        "versionCode": args.version_code,
        "dbVersion": db_version_of(args.db_file),
        # Mantenga los campos antiguos del primer APK y use nombres de activos para evitar exponer las rutas locales del corredor de CI; consulte apkArtifacts para ver el conjunto completo.
        "apkFile": primary_apk.name,
        "apkSizeBytes": primary_apk.stat().st_size,
        "apkSha256": sha256_of(primary_apk),
        "apkArtifacts": apk_artifacts,
        "releaseNotesRef": release_notes_ref,
        "sourceCommit": commit_sha.lower(),
        "sourceRef": source_ref,
        "generatedAt": datetime.now(timezone.utc).isoformat(timespec="seconds"),
    }

    args.out.parent.mkdir(parents=True, exist_ok=True)
    payload = json.dumps(manifest, ensure_ascii=False, indent=2) + "\n"
    # El juicio idempotente ignora generateAt: cuando la misma versión se genera repetidamente, los campos restantes deben ser consistentes byte por byte.,
    # Sólo se consideran las actualizaciones de marcas de tiempo."Sin cambios sustanciales",Evite diferencias sin sentido generadas por reposiciones de CI.
    stable = json.dumps({k: v for k, v in manifest.items() if k != "generatedAt"}, ensure_ascii=False, sort_keys=True)
    if args.out.exists():
        existing = json.loads(args.out.read_text(encoding="utf-8"))
        existing_stable = json.dumps(
            {k: v for k, v in existing.items() if k != "generatedAt"},
            ensure_ascii=False, sort_keys=True,
        )
        if existing_stable == stable:
            print(f"manifest sin cambios(idempotente): {args.out}")
            return 0
    atomic_write_text(args.out, payload)
    # Vuelva a leer inmediatamente después del reemplazo para evitar informar erróneamente que las excepciones de escritura fueron exitosas.
    json.loads(args.out.read_text(encoding="utf-8"))
    print(f"manifest Generado: {args.out}")
    print(json.dumps(manifest, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    sys.exit(main())
