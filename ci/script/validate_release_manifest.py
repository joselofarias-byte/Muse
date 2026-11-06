#!/usr/bin/env python3
"""Validate a generated release manifest against the local release inputs.

The generator records the build provenance and APK digest; this independent
check prevents a mismatched manifest, APK, DB version, tag, or commit from
being uploaded by the release job.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import sys
from pathlib import Path

COMMIT_RE = re.compile(r"^[0-9a-fA-F]{7,64}$")


def sha256_of(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()


def db_version_of(path: Path) -> int:
    match = re.search(r"version\s*=\s*(\d+)", path.read_text(encoding="utf-8"))
    if not match:
        raise ValueError(f"No se puede acceder desde {path} analizar DB version")
    return int(match.group(1))


def validate_manifest(
    manifest_path: Path,
    apk_path: Path | list[Path],
    db_path: Path,
    tag: str,
    commit_sha: str,
) -> list[str]:
    errors: list[str] = []
    apk_paths = [apk_path] if isinstance(apk_path, Path) else apk_path
    if not manifest_path.is_file():
        return [f"manifest no existe: {manifest_path}"]
    missing_apks = [path for path in apk_paths if not path.is_file()]
    if missing_apks:
        errors.extend(f"APK no existe: {path}" for path in missing_apks)
    if not db_path.is_file():
        errors.append(f"DB El archivo fuente no existe: {db_path}")
    try:
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        return [f"manifest No se puede analizar: {error}"]
    if not isinstance(manifest, dict):
        return ["manifest El nivel superior debe ser JSON object"]

    expected_version = tag.strip()[1:] if tag.strip().lower().startswith("v") else tag.strip()
    if manifest.get("versionName") != expected_version:
        errors.append("versionName inconsistente con la etiqueta")
    if manifest.get("sourceRef") != tag.strip():
        errors.append("sourceRef inconsistente con la etiqueta")
    normalized_commit = commit_sha.strip().lower()
    if not COMMIT_RE.fullmatch(normalized_commit):
        errors.append("El formato del SHA de confirmación entrante es ilegal.")
    elif manifest.get("sourceCommit") != normalized_commit:
        errors.append("sourceCommit Inconsistente con el compromiso de compilación")

    required = ("versionCode", "dbVersion", "apkSizeBytes", "apkSha256", "releaseNotesRef")
    for key in required:
        if key not in manifest:
            errors.append(f"manifest Campo faltante: {key}")
    if not isinstance(manifest.get("versionCode"), int) or manifest.get("versionCode", 0) <= 0:
        errors.append("versionCode Debe ser un número entero positivo")
    if not isinstance(manifest.get("releaseNotesRef"), str) or not manifest.get("releaseNotesRef", "").strip():
        errors.append("releaseNotesRef no puede estar vacío")

    primary_apk = apk_paths[0] if apk_paths else None
    if primary_apk is not None and primary_apk.is_file():
        actual_size = primary_apk.stat().st_size
        actual_hash = sha256_of(primary_apk)
        if manifest.get("apkSizeBytes") != actual_size:
            errors.append("apkSizeBytes Inconsistente con el APK real")
        if manifest.get("apkSha256") != actual_hash:
            errors.append("apkSha256 Inconsistente con el APK real")

    # El nuevo manifiesto cubre todos los APK de ABI cargados por el trabajo de publicación; el manifiesto anterior sigue siendo compatible si falta este campo.
    declared_artifacts = manifest.get("apkArtifacts")
    if declared_artifacts is not None:
        if not isinstance(declared_artifacts, list) or len(declared_artifacts) != len(apk_paths):
            errors.append("apkArtifacts Inconsistente con la cantidad de APK publicados")
        else:
            declared_by_name = {
                item.get("name"): item
                for item in declared_artifacts
                if isinstance(item, dict) and isinstance(item.get("name"), str)
            }
            for apk in apk_paths:
                if not apk.is_file():
                    continue
                item = declared_by_name.get(apk.name)
                if item is None:
                    errors.append(f"manifest Falta entrada de APK: {apk.name}")
                    continue
                if item.get("sizeBytes") != apk.stat().st_size:
                    errors.append(f"apkArtifacts El tamaño no coincide con el APK real: {apk.name}")
                if item.get("sha256") != sha256_of(apk):
                    errors.append(f"apkArtifacts Resumen no coherente con el APK real: {apk.name}")

    if db_path.is_file():
        try:
            actual_db_version = db_version_of(db_path)
            if manifest.get("dbVersion") != actual_db_version:
                errors.append("dbVersion Inconsistente con el código fuente de la base de datos actual")
        except ValueError as error:
            errors.append(str(error))
    return errors


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", required=True, type=Path)
    parser.add_argument("--apk", required=True, action="append", type=Path)
    parser.add_argument("--db-file", required=True, type=Path)
    parser.add_argument("--tag", required=True)
    parser.add_argument("--commit-sha", required=True)
    args = parser.parse_args()
    errors = validate_manifest(args.manifest, args.apk, args.db_file, args.tag, args.commit_sha)
    if errors:
        print("release manifest validation FAIL:")
        for error in errors:
            print(f"  - {error}")
        return 1
    print("release manifest validation PASS: version, DB, APK digest and provenance match")
    return 0


if __name__ == "__main__":
    sys.exit(main())
