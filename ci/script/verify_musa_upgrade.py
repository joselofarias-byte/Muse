#!/usr/bin/env python3
"""Comprueba una APK ARM64 contra una APK de producción anterior del mismo repo.

El workflow debe descargar la APK anterior de una release de confianza. Este
script no descarga artefactos ni accede a claves o credenciales.
"""

from __future__ import annotations

import argparse
import os
import re
import subprocess
import sys
from dataclasses import dataclass
from pathlib import Path


@dataclass(frozen=True)
class ApkInfo:
    package: str
    version_code: int
    certificate: str


def run_tool(command: list[str]) -> str:
    result = subprocess.run(command, capture_output=True, text=True, check=False)
    if result.returncode != 0:
        # No volcamos salidas externas en los logs de publicación.
        raise ValueError(f"Falló {Path(command[0]).name} (código {result.returncode})")
    return result.stdout


def inspect_apk(apk: Path, aapt: str, apksigner: str, *, arm64: bool) -> ApkInfo:
    if not apk.is_file():
        raise ValueError(f"No existe la APK: {apk}")
    badging = run_tool([aapt, "dump", "badging", str(apk)])
    lines = badging.splitlines()
    packages = [line for line in lines if line.startswith("package:")]
    if len(packages) != 1:
        raise ValueError("Se esperaba exactamente una línea package de aapt")
    attributes = re.findall(r"\b([A-Za-z][A-Za-z0-9]*)='([^']*)'", packages[0])
    attrs = dict(attributes)
    if len(attrs) != len(attributes):
        raise ValueError("Atributos package duplicados")
    package = attrs.get("name", "")
    code = attrs.get("versionCode", "")
    if not re.fullmatch(r"[A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z][A-Za-z0-9_]*)+", package):
        raise ValueError("Identificador de paquete ausente o inválido")
    if not re.fullmatch(r"[0-9]+", code) or not 0 < int(code) <= 2100000000:
        raise ValueError("versionCode ausente o inválido")
    if attrs.get("versionCodeMajor", "0") != "0":
        raise ValueError("versionCodeMajor no compatible con esta verificación")
    if any(line.strip().startswith("application-debuggable") for line in lines):
        raise ValueError("No se permite una APK depurable, ni siquiera como referencia")
    if arm64:
        native = [line for line in lines if line.startswith("native-code:")]
        if len(native) != 1 or native[0].strip() != "native-code: 'arm64-v8a'":
            raise ValueError("La APK candidata debe contener únicamente ARM64 (arm64-v8a)")

    output = run_tool([apksigner, "verify", "--verbose", "--print-certs", str(apk)])
    cert_lines = [line.strip() for line in output.splitlines()
                  if re.match(r"Signer #.* certificate SHA-256 digest:", line.strip())]
    dn_lines = [line.strip() for line in output.splitlines()
                if re.match(r"Signer #.* certificate DN:", line.strip())]
    if len(cert_lines) != 1 or len(dn_lines) != 1:
        raise ValueError("Se requiere exactamente un firmante con certificado y DN")
    cert = re.fullmatch(r"Signer #1 certificate SHA-256 digest:\s*([0-9a-fA-F]{64})", cert_lines[0])
    dn = re.fullmatch(r"Signer #1 certificate DN:\s*(\S.*)", dn_lines[0])
    if not cert or not dn:
        raise ValueError("Certificado o DN de firma inválido")
    counts = re.findall(r"^Number of signers:\s*(.*)$", output, re.MULTILINE)
    if counts and counts != ["1"]:
        raise ValueError("Número de firmantes inesperado")
    if re.search(r"android\s*debug", dn.group(1), re.IGNORECASE):
        raise ValueError("No se permite un certificado Android Debug")
    return ApkInfo(package, int(code), cert.group(1).lower())


def verify_upgrade(apk: Path, previous_apk: Path, aapt: str, apksigner: str) -> tuple[ApkInfo, ApkInfo]:
    previous = inspect_apk(previous_apk, aapt, apksigner, arm64=False)
    candidate = inspect_apk(apk, aapt, apksigner, arm64=True)
    if candidate.package != previous.package:
        raise ValueError("El identificador de paquete no coincide con la release anterior")
    if candidate.certificate != previous.certificate:
        raise ValueError("El certificado SHA-256 no coincide con la release anterior")
    if candidate.version_code <= previous.version_code:
        raise ValueError("El versionCode candidato debe ser mayor que el publicado")
    return previous, candidate


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    for flag, env, default in (
        ("--apk", "APK", None),
        ("--previous-apk", "PREVIOUS_APK", None),
        ("--aapt", "AAPT", "aapt"),
        ("--apksigner", "APKSIGNER", "apksigner"),
    ):
        parser.add_argument(flag, default=os.environ.get(env) or default)
    args = parser.parse_args(argv)
    if not args.apk or not args.previous_apk:
        parser.error("Se requieren --apk/ APK y --previous-apk/ PREVIOUS_APK")
    try:
        previous, candidate = verify_upgrade(Path(args.apk), Path(args.previous_apk), args.aapt, args.apksigner)
    except (OSError, ValueError) as error:
        print(f"Verificación de actualización Musa FALLÓ: {error}", file=sys.stderr)
        return 1
    print(f"Actualización Musa verificada: {candidate.package}, "
          f"versionCode {previous.version_code} → {candidate.version_code}; "
          "ARM64 y certificado de producción sin cambios")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
