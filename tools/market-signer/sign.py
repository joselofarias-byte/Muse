#!/usr/bin/env python3
"""Muse Herramienta de firma fuera de línea del mercado de complementos.

Alineado textualmente con el algoritmo del lado de la aplicación (app/src/main/java/io/zer0/muse/data/plugin/...):

  * estandarizar JSON  = AppJson(Compacto, sin espacios, campos en orden de declaración, incluidos los valores predeterminados, UTF-8 original chino;
    El campo opcional contribuye/uiPanel/toolCards no participa en la serialización cuando se usa el valor predeterminado, lo cual es consistente con el lado de la aplicación palabra por palabra)
  * manifestSha256 = PluginSecurityGate.contentSha256
      digest = SHA-256, para cada parte por turno:
          path.utf8 | 0x00 | len(bytes) decimales ASCII | 0x00 | bytes | 0x00
      Orden:("manifest.json", Especificación del manifiesto firmado JSON) → (entry, Código fuente de entrada) → Los archivos restantes están ordenados en orden ascendente por ruta.
  * Entrada de firma del emisor = PluginSecurityGate.signaturePayload
      La misma codificación de límites, pero cada parte primero obtiene su resumen SHA-256 y luego concatena los resúmenes de 32 bytes.
      manifest Utilice la especificación con el valor de la firma vacío JSON
  * Entrada de firma de directorio = PluginCatalogVerifier.canonicalPayload(PluginCatalog JSON canónico)
  * Algoritmo SHA256conECDSA(P-256), clave pública X.509 SubjectPublicKeyInfo,
    Las claves públicas y las firmas son todas líneas individuales estándar Base64 (App Forzará una comparación normalizada)

La clave privada nunca sale de la máquina local: el script solo lee el archivo de clave privada local y genera la información del producto y de la clave pública.

Uso:
  python sign.py keygen   --key FILE
  python sign.py pack     --src DIR --key FILE --publisher-id ID --out-dir DIR [--base-url URL]
  python sign.py catalog  --packages-dir DIR --key FILE --key-id ID --base-url URL
                          --catalog-id ID --out FILE [--sequence N] [--days 30]
"""

import argparse
import base64
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import time
import zipfile
from pathlib import Path

SIGNATURE_ALGORITHM = "SHA256withECDSA"
CURVE = "prime256v1"  # P-256 / secp256r1
SCHEMA_VERSION = 1

MANIFEST_ORDER = [
    "id", "name", "version", "description", "author", "minAppVersion",
    "entry", "kind", "trust", "hidden", "capabilities", "permissions",
    "activationEvents", "hooks", "enabled", "tools", "signature",
    # ── Campos opcionales: el valor predeterminado no participa en la serialización (alineado textualmente con el PluginManifest del lado de la aplicación)──
    #  · contributes / uiPanel:App lado explicitNulls=false, no hay salida cuando el valor es nulo;
    #  · hooks:App lado @EncodeDefault(NEVER), la lista vacía no se genera;
    #  · toolCards:App lado @EncodeDefault(NEVER), los objetos vacíos no se generan.
    # Extraño a cualquiera,App La verificación de firma fallará debido a diferentes secuencias de bytes (v2.0.0 Causas de accidentes de mercado).
    # hooks La ubicación de almacenamiento está determinada por la línea anterior (debe estar habilitada antes) y no aparecerá repetidamente aquí.
    "contributes", "uiPanel", "toolCards",
]

# Campos opcionales que se pueden omitir si faltan (no es necesario que aparezcan en el manifiesto ni se completen con valores predeterminados).
OPTIONAL_MANIFEST_FIELDS = ("contributes", "uiPanel", "hooks", "toolCards")

# El autor puede escribir en el manifiesto, pero no en los campos de la carga firmada.
# App lado PluginManifest.runtime con @EncodeDefault(NEVER) y no aquí
# SIGNED_TOP_LEVEL_FIELDS Aquí, ningún valor aparecerá en el JSON firmado;
# Se identifica aquí solo para verificar la extensión de entrada y los archivos en el paquete de recopilación, y se descarta durante la serialización.
UNSIGNED_MANIFEST_FIELDS = ("runtime",)
RUNTIME_JS = "js"
RUNTIME_PYTHON = "python"
KNOWN_RUNTIMES = (RUNTIME_JS, RUNTIME_PYTHON)
MANIFEST_DEFAULTS = {
    "version": "0.1.0",
    "description": "",
    "author": "",
    "minAppVersion": "1.0.0",
    "entry": "main.js",
    "kind": "tool",
    "trust": "sandboxed",
    "hidden": False,
    "capabilities": [],
    "permissions": [],
    "activationEvents": ["onStartup"],
    "hooks": [],
    "enabled": True,
    "tools": [],
    "signature": None,
}
TOOL_ORDER = ["name", "description", "parametersJson", "requiredJson", "functionName"]
TOOL_DEFAULTS = {"parametersJson": "{}", "requiredJson": "[]"}
SIGNATURE_ORDER = ["publisherId", "publicKey", "signature", "algorithm"]
ENTRY_ORDER = [
    "id", "version", "name", "description", "publisherId", "publisherKeyFingerprint",
    "artifactUrl", "artifactBytes", "artifactSha256", "manifestSha256",
    "capabilities", "permissions",
]
CATALOG_ORDER = [
    "catalogId", "schemaVersion", "sequence", "generatedAtEpochMs", "expiresAtEpochMs", "entries",
]

PLUGIN_ID_REGEX = re.compile(r"^[a-z0-9][a-z0-9_-]*$")
PUBLISHER_ID_REGEX = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$")
TOOL_NAME_REGEX = re.compile(r"^[a-zA-Z0-9_-]{1,64}$")
FUNCTION_NAME_REGEX = re.compile(r"^[a-zA-Z_$][a-zA-Z0-9_$]{0,63}$")
VERSION_REGEX = re.compile(r"^\d+\.\d+\.\d+(?:[-+][0-9A-Za-z.-]+)?$")
SHA256_REGEX = re.compile(r"^[0-9a-f]{64}$")
# Los complementos externos permiten nombres de capacidades declaradas.App El lado de instalación se ha cambiado a declaración abierta (solo para visualización de vista previa de instalación,
# ya no es una condición de rechazo), aquí la sincronización se relaja con la colección enumerada en el documento de manifiesto de la aplicación,
# Esto evita que la herramienta de firma rechace unilateralmente a los autores cuando declaran capacidades de uso común, como la red.
ALLOWED_CAPABILITIES = {
    "resource.read", "resource.write", "network",
    "storage.read", "storage.write", "clipboard.read", "clipboard.write",
    "notify", "device.info", "ui", "ui.mood", "ui.skin",
}
LOADED_EXTENSIONS = (".js", ".py", ".json", ".md")
UI_SKIN_KIND = "ui-skin"
UI_SKIN_ENTRY = "skin.json"
MAX_SIGNATURE_BYTES = 256


def fail(message: str) -> None:
    print(f"ERROR: {message}", file=sys.stderr)
    raise SystemExit(1)


def openssl_binary() -> str:
    configured = os.environ.get("MUSE_OPENSSL", "").strip()
    if configured:
        if not os.path.isfile(configured):
            fail(f"MUSE_OPENSSL El archivo señalado no existe: {configured}")
        return configured
    found = shutil.which("openssl")
    if not found:
        fail("No se puede encontrar openssl, configure la variable de entorno MUSE_OPENSSL para que apunte al archivo ejecutable")
    return found


def openssl(args, stdin_bytes: bytes = None) -> bytes:
    result = subprocess.run(
        [openssl_binary()] + args,
        input=stdin_bytes,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
    )
    if result.returncode != 0:
        fail(f"openssl {' '.join(args)} falló: {result.stderr.decode('utf-8', 'replace').strip()}")
    return result.stdout


# ─────────────────────────── Normalización y resumen (alineado con aplicaciones) ───────────────────────────

def canonical_bytes(payload) -> bytes:
    """AppJson.encodeToString: Compacto, con valores predeterminados, salida no ASCII tal como está."""
    return json.dumps(payload, ensure_ascii=False, separators=(",", ":")).encode("utf-8")


def length_prefixed_part(path: str, data: bytes) -> bytes:
    return (
        path.encode("utf-8") + b"\x00"
        + str(len(data)).encode("ascii") + b"\x00"
        + data + b"\x00"
    )


def content_sha256(parts) -> str:
    digest = hashlib.sha256()
    for path, data in parts:
        digest.update(length_prefixed_part(path, data))
    return digest.hexdigest()


def signature_input(parts) -> bytes:
    return b"".join(hashlib.sha256(length_prefixed_part(path, data)).digest() for path, data in parts)


def sha256_hex(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def b64_standard(data: bytes) -> str:
    return base64.b64encode(data).decode("ascii")


def decode_b64_strict(text: str) -> bytes:
    return base64.b64decode(text, validate=True)


# ─────────────────────────── clave ───────────────────────────

def generate_key(key_path: Path) -> None:
    if key_path.exists():
        fail(f"La clave privada ya existe y no se sobrescribirá.: {key_path}")
    key_path.parent.mkdir(parents=True, exist_ok=True)
    key_path.write_bytes(openssl(["ecparam", "-name", CURVE, "-genkey", "-noout"]))
    try:
        os.chmod(key_path, 0o600)
    except OSError:
        pass


def spki_der(key_path: Path) -> bytes:
    return openssl(["ec", "-in", str(key_path), "-pubout", "-outform", "DER"])


def public_key_info(key_path: Path) -> dict:
    der = spki_der(key_path)
    return {"publicKey": b64_standard(der), "fingerprint": sha256_hex(der)}


def sign_bytes(key_path: Path, payload: bytes) -> bytes:
    with tempfile.TemporaryDirectory() as tmp:
        payload_file = Path(tmp) / "payload.bin"
        signature_file = Path(tmp) / "signature.der"
        payload_file.write_bytes(payload)
        openssl([
            "dgst", "-sha256", "-sign", str(key_path),
            "-out", str(signature_file), str(payload_file),
        ])
        signature = signature_file.read_bytes()
    if len(signature) > MAX_SIGNATURE_BYTES:
        fail(f"Longitud de la firma anormal({len(signature)} bytes)")
    return signature


def verify_signature(key_path: Path, payload: bytes, signature: bytes) -> None:
    with tempfile.TemporaryDirectory() as tmp:
        payload_file = Path(tmp) / "payload.bin"
        signature_file = Path(tmp) / "signature.der"
        public_file = Path(tmp) / "public.pem"
        payload_file.write_bytes(payload)
        signature_file.write_bytes(signature)
        public_file.write_bytes(openssl(["ec", "-in", str(key_path), "-pubout"]))
        result = subprocess.run(
            [openssl_binary(), "dgst", "-sha256", "-verify", str(public_file),
             "-signature", str(signature_file), str(payload_file)],
            stdout=subprocess.PIPE, stderr=subprocess.PIPE,
        )
        if result.returncode != 0:
            fail("Falló la autoverificación local: la firma no puede ser verificada por la clave pública correspondiente")


# ─────────────────────────── manifest estandarizar ───────────────────────────

def ordered(source: dict, order, defaults=None, label: str = "", optional=(), allowed_extra=()) -> dict:
    defaults = defaults or {}
    unknown = [key for key in source if key not in order and key not in allowed_extra]
    if unknown:
        fail(f"{label} Contiene campos desconocidos {unknown};Para evitar inconsistencias con la serialización de la aplicación, se debe eliminar o cambiar el nombre.")
    result = {}
    for key in order:
        if key in source:
            result[key] = source[key]
        elif key in defaults:
            result[key] = defaults[key]
    missing = [
        key for key in order
        if key not in result and key not in defaults and key not in optional
    ]
    if missing:
        fail(f"{label} Faltan campos obligatorios {missing}")
    return result


def normalize_manifest(raw: dict, with_signature: bool) -> dict:
    manifest = ordered(
        raw, MANIFEST_ORDER, MANIFEST_DEFAULTS, "manifest.json",
        optional=OPTIONAL_MANIFEST_FIELDS,
        allowed_extra=UNSIGNED_MANIFEST_FIELDS,
    )
    manifest["tools"] = [
        ordered(tool, TOOL_ORDER, TOOL_DEFAULTS, f"tools[{index}]")
        for index, tool in enumerate(manifest["tools"])
    ]
    if with_signature and isinstance(manifest["signature"], dict):
        manifest["signature"] = ordered(
            manifest["signature"], SIGNATURE_ORDER, {"algorithm": SIGNATURE_ALGORITHM}, "signature",
        )
    finalize_optional_fields(manifest)
    return manifest


# ─────────────── Campos opcionales alineados con la serialización de la aplicación. ───────────────
# App final (kotlinx.serialization,AppJson: encodeDefaults=true / explicitNulls=false):
#   · contributes / uiPanel No hay salida cuando es nulo;
#   · toolCards No hay salida cuando es un objeto vacío (@EncodeDefault(NEVER));
#   · contributes Muestra todos los campos predeterminados cuando no están vacíos (configuration / ConfigItem / SelectOption).
# La lógica de esta sección debe ser coherente palabra por palabra con el comportamiento anterior; de lo contrario, el paquete de firma no puede pasar la verificación de firma de la aplicación.

CONFIG_ITEM_ORDER = ["key", "type", "defaultVal", "description", "options"]
SELECT_OPTION_ORDER = ["value", "label"]


def normalize_select_option(source: dict, label: str) -> dict:
    if not isinstance(source, dict) or "value" not in source:
        fail(f"{label} desaparecido value")
    unknown = [key for key in source if key not in SELECT_OPTION_ORDER]
    if unknown:
        fail(f"{label} Contiene campos desconocidos {unknown};Para evitar inconsistencias con la serialización de la aplicación, se debe eliminar o cambiar el nombre.")
    value = source["value"]
    # SelectOption.label El valor predeterminado es el valor mismo.
    return {"value": value, "label": source.get("label", value)}


def normalize_config_item(source: dict, label: str) -> dict:
    if not isinstance(source, dict) or "key" not in source:
        fail(f"{label} desaparecido key")
    unknown = [key for key in source if key not in CONFIG_ITEM_ORDER]
    if unknown:
        fail(f"{label} Contiene campos desconocidos {unknown};Para evitar inconsistencias con la serialización de la aplicación, se debe eliminar o cambiar el nombre.")
    result = {"key": source["key"], "type": source.get("type", "string")}
    # defaultVal si JsonElement?:null La aplicación no genera la clave cuando
    if source.get("defaultVal") is not None:
        result["defaultVal"] = source["defaultVal"]
    result["description"] = source.get("description", "")
    options = source.get("options", [])
    result["options"] = [
        normalize_select_option(option, f"{label}.options[{index}]")
        for index, option in enumerate(options)
    ]
    return result


def normalize_contributes(source: dict, label: str) -> dict:
    if not isinstance(source, dict):
        fail(f"{label} debe ser un objeto")
    unknown = [key for key in source if key != "configuration"]
    if unknown:
        fail(f"{label} Contiene campos desconocidos {unknown};Para evitar inconsistencias con la serialización de la aplicación, se debe eliminar o cambiar el nombre.")
    configuration = source.get("configuration", [])
    return {
        "configuration": [
            normalize_config_item(item, f"{label}.configuration[{index}]")
            for index, item in enumerate(configuration)
        ],
    }


def finalize_optional_fields(manifest: dict) -> None:
    """Haga que los valores nulos para los campos opcionales sean coherentes con el comportamiento de serialización de la aplicación (los valores predeterminados no van en secuencias de bytes JSON)."""
    contributes = manifest.get("contributes")
    if contributes is None:
        manifest.pop("contributes", None)
    else:
        manifest["contributes"] = normalize_contributes(contributes, "contributes")
    if manifest.get("uiPanel") is None:
        manifest.pop("uiPanel", None)
    if manifest.get("toolCards") is None or manifest.get("toolCards") == {}:
        manifest.pop("toolCards", None)
    # hooks traer @EncodeDefault(NEVER): Una lista vacía tampoco ingresa a la secuencia de bytes.
    # Omitir este paso hará que manifest.json sea adicional "hooks":[], inconsistente con el resultado de la serialización de la aplicación,
    # Todos los paquetes firmados serán juzgados.“La firma del editor no coincide con el contenido del complemento”.
    if not manifest.get("hooks"):
        manifest.pop("hooks", None)


def declared_runtime(raw: dict) -> str:
    """Leer el tiempo de ejecución declarado por el autor; el valor predeterminado es js, el valor predeterminado en el lado de la aplicación.

    Este campo no participa en la carga útil de la firma y solo afecta la verificación de la extensión de entrada y la recopilación de archivos dentro del paquete.
    """
    value = raw.get("runtime", RUNTIME_JS)
    if value not in KNOWN_RUNTIMES:
        fail(f"No compatible runtime: {value}(Disponible:{', '.join(KNOWN_RUNTIMES)})")
    return value


def validate_manifest(manifest: dict, runtime: str = RUNTIME_JS) -> None:
    if not PLUGIN_ID_REGEX.match(manifest["id"]):
        fail(f"La identificación del complemento es ilegal: {manifest['id']}")
    if not manifest["name"]:
        fail("manifest desaparecido name")
    if manifest["trust"] != "sandboxed":
        fail("La confianza del complemento externo debe ser sandboxed")
    is_skin = manifest["kind"] == UI_SKIN_KIND
    if is_skin:
        if runtime != RUNTIME_JS:
            fail("ui-skin El complemento solo admite el tiempo de ejecución js")
        if manifest["tools"]:
            fail("ui-skin Los complementos no deben declarar herramientas")
        if "ui.skin" not in manifest["capabilities"]:
            fail("ui-skin Los complementos deben declararse en capacidades ui.skin")
    else:
        if not manifest["tools"]:
            fail("Los complementos deben declarar al menos una herramienta")
        expected_suffix = ".py" if runtime == RUNTIME_PYTHON else ".js"
        if not manifest["entry"].lower().endswith(expected_suffix):
            fail(f"runtime={runtime} La entrada debe ser {expected_suffix} Archivo: {manifest['entry']}")
    for capability in manifest["capabilities"] + manifest["permissions"]:
        if capability not in ALLOWED_CAPABILITIES:
            fail(f"La capacidad no está en la lista blanca: {capability}")
    for tool in manifest["tools"]:
        if not TOOL_NAME_REGEX.match(tool["name"]) or not FUNCTION_NAME_REGEX.match(tool["functionName"]):
            fail(f"El nombre de la herramienta o el nombre de la función es ilegal: {tool['name']}/{tool['functionName']}")


# ─────────────────────────── paquete ───────────────────────────

def collect_files(src: Path, manifest: dict) -> tuple:
    """Regresar (entry_path, entry_bytes, extras{path: bytes});Recopile únicamente extensiones admitidas por el cargador."""
    entry_name = manifest["entry"].replace("\\", "/")
    is_skin = manifest["kind"] == UI_SKIN_KIND
    entry_path = src / entry_name
    if is_skin:
        # El paquete skin no tiene entrada JS:App Al cargar zip, corrija el código de entrada en una cadena vacía.
        # Sin embargo, el nombre manifest.entry seguirá utilizándose para participar en el resumen de contenido y la firma debe ser coherente con él.
        entry_bytes = b""
    else:
        if not entry_path.is_file():
            fail(f"Archivo de entrada no encontrado: {entry_path}")
        entry_bytes = entry_path.read_bytes()

    extras = {}
    for path in sorted(src.rglob("*")):
        if not path.is_file():
            continue
        relative = path.relative_to(src).as_posix()
        if relative == "manifest.json":
            continue
        if not is_skin and relative == entry_name:
            continue
        if not relative.endswith(LOADED_EXTENSIONS):
            print(f"  Consejo: omita archivos que no estén cargados {relative}")
            continue
        if ".." in relative or relative.startswith("/"):
            fail(f"camino ilegal: {relative}")
        if not relative.isascii():
            fail(f"La ruta dentro del paquete debe ser ASCII (las reglas de clasificación deben ser coherentes con la aplicación byte a byte): {relative}")
        extras[relative] = path.read_bytes()
    if is_skin:
        if UI_SKIN_ENTRY not in extras:
            fail(f"ui-skin Falta el complemento {UI_SKIN_ENTRY}")
        for name in extras:
            if name.lower().endswith((".js", ".py")):
                fail(f"ui-skin Los complementos no deben contener scripts: {name}")
        validate_skin_json(extras[UI_SKIN_ENTRY])
    return entry_name, entry_bytes, extras


# ─────────────── Verificación del paquete de skin (mismo calibre que el host BubbleSkinValidator) ───────────────

BUBBLE_ROLES = ("USER", "ASSISTANT", "GROUP_ASSISTANT", "SYSTEM", "TOOL")
MIN_CONTRAST_RATIO = 4.5
SKIN_ID_REGEX = re.compile(r"^[a-z0-9][a-z0-9_-]{0,63}$")


def relative_luminance(argb: int) -> float:
    def linearize(channel: float) -> float:
        return channel / 12.92 if channel <= 0.03928 else ((channel + 0.055) / 1.055) ** 2.4

    red = linearize(((argb >> 16) & 0xFF) / 255.0)
    green = linearize(((argb >> 8) & 0xFF) / 255.0)
    blue = linearize((argb & 0xFF) / 255.0)
    return 0.2126 * red + 0.7152 * green + 0.0722 * blue


def contrast_ratio(foreground: int, background: int) -> float:
    first = relative_luminance(foreground)
    second = relative_luminance(background)
    lighter, darker = max(first, second), min(first, second)
    return (lighter + 0.05) / (darker + 0.05)


def validate_skin_json(raw: bytes) -> None:
    """Verifique usted mismo la máscara de acuerdo con las reglas del anfitrión antes de publicarla para evitar volver a caer silenciosamente en la burbuja incorporada después de colocarla en los estantes."""
    try:
        skin = json.loads(raw.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        fail(f"{UI_SKIN_ENTRY} No legal JSON: {error}")

    if skin.get("schemaVersion", 1) != 1:
        fail(f"{UI_SKIN_ENTRY} schemaVersion debe ser 1")
    if not SKIN_ID_REGEX.match(str(skin.get("id", ""))):
        fail(f"{UI_SKIN_ENTRY} identificación de piel ilegal: {skin.get('id')!r}")
    if not str(skin.get("name", "")).strip():
        fail(f"{UI_SKIN_ENTRY} Falta el nombre de la piel")

    for mode in ("light", "dark"):
        roles = skin.get(mode) or {}
        if not isinstance(roles, dict) or not roles:
            fail(f"{UI_SKIN_ENTRY} desaparecido {mode} combinación de colores")
        for role, style in roles.items():
            if role not in BUBBLE_ROLES:
                fail(f"{UI_SKIN_ENTRY} papel desconocido {role}(Disponible:{', '.join(BUBBLE_ROLES)})")
            for field, minimum, maximum in (
                ("radiusDp", 0.0, 40.0),
                ("paddingHorizontalDp", 0.0, 48.0),
                ("paddingVerticalDp", 0.0, 48.0),
                ("maxWidthFraction", 0.35, 1.0),
                ("fontScale", 0.75, 1.5),
                ("outlineWidthDp", 0.0, 8.0),
            ):
                if field in style and not (minimum <= float(style[field]) <= maximum):
                    fail(f"{UI_SKIN_ENTRY} {mode}/{role} de {field} más allá {minimum}~{maximum}")
            surface = int(style.get("surfaceArgb", 0))
            content = int(style.get("contentArgb", 0))
            ratio = contrast_ratio(content, surface)
            if ratio < MIN_CONTRAST_RATIO:
                fail(
                    f"{UI_SKIN_ENTRY} {mode}/{role} Contraste insuficiente entre el texto y el fondo.: "
                    f"{ratio:.2f} < {MIN_CONTRAST_RATIO}",
                )



# La aparición de estos fragmentos en la entrada generalmente significa que el complemento está intentando eludir la zona de pruebas o realizar acciones peligrosas; la propia caja de arena lo interceptará,
# Pero infórmelo antes del lanzamiento para evitar enviar código obviamente sospechoso al mercado.
SUSPICIOUS_SNIPPETS = (
    "eval(", "new Function", "XMLHttpRequest", "fetch(", "document.", "window.",
    "localStorage", "importScripts", "require(",
)


def check_entry_code(manifest: dict, entry_bytes: bytes, runtime: str = RUNTIME_JS) -> None:
    """Verifique que las funciones declaradas en el manifiesto realmente existan en el archivo de entrada y brinde advertencias sobre fragmentos sospechosos."""
    if entry_bytes.startswith(b"\xef\xbb\xbf"):
        print("  Advertencia: el archivo de entrada contiene una lista de materiales UTF-8; se recomienda eliminarlo (App se decodificará como UTF-8)")
    try:
        text = entry_bytes.decode("utf-8")
    except UnicodeDecodeError as error:
        fail(f"El expediente de entrada no es legal. UTF-8: {error}")

    declaration = r"def\s+" if runtime == RUNTIME_PYTHON else r"function\s+"
    for tool in manifest["tools"]:
        function_name = tool["functionName"]
        pattern = re.compile(r"(^|\n)[ \t]*" + declaration + re.escape(function_name) + r"\s*\(")
        if not pattern.search(text):
            fail(f"Función de utilidad indefinida de entrada {function_name}(manifest Herramientas {tool['name']})")

    # La tabla de fragmentos sospechosos se enumera según el método de escritura JS.Python No se aplica la entrada para evitar falsos positivos.
    if runtime == RUNTIME_JS:
        for snippet in SUSPICIOUS_SNIPPETS:
            if snippet in text:
                print(f"  Advertencia: la entrada contiene fragmentos sospechosos {snippet!r}, confirme que es necesario y seguro")



def build_parts(manifest: dict, entry_name: str, entry_bytes: bytes, extras: dict, blank_signature: bool) -> list:
    payload_manifest = json.loads(json.dumps(manifest))
    if blank_signature and isinstance(payload_manifest.get("signature"), dict):
        payload_manifest["signature"]["signature"] = ""
    parts = [("manifest.json", canonical_bytes(payload_manifest))]
    parts.append((entry_name, entry_bytes))
    for path in sorted(extras):
        parts.append((path, extras[path]))
    return parts


def command_pack(args) -> None:
    src = Path(args.src)
    key_path = Path(args.key)
    if not src.is_dir():
        fail(f"El directorio del código fuente del complemento no existe: {src}")
    if not key_path.is_file():
        fail(f"La clave privada no existe: {key_path}")
    if not PUBLISHER_ID_REGEX.match(args.publisher_id):
        fail(f"ID de emisor ilegal: {args.publisher_id}")

    raw = json.loads((src / "manifest.json").read_text(encoding="utf-8"))
    runtime = declared_runtime(raw)
    manifest = normalize_manifest(raw, with_signature=False)
    validate_manifest(manifest, runtime)

    publisher = public_key_info(key_path)
    manifest["signature"] = {
        "publisherId": args.publisher_id,
        "publicKey": publisher["publicKey"],
        "signature": "",
        "algorithm": SIGNATURE_ALGORITHM,
    }

    entry_name, entry_bytes, extras = collect_files(src, manifest)
    check_entry_code(manifest, entry_bytes, runtime)
    signature = sign_bytes(key_path, signature_input(build_parts(manifest, entry_name, entry_bytes, extras, True)))
    verify_signature(
        key_path,
        signature_input(build_parts(manifest, entry_name, entry_bytes, extras, True)),
        signature,
    )
    manifest["signature"]["signature"] = b64_standard(signature)
    manifest = normalize_manifest(manifest, with_signature=True)

    final_parts = build_parts(manifest, entry_name, entry_bytes, extras, blank_signature=False)
    manifest_sha256 = content_sha256(final_parts)
    manifest_json_bytes = final_parts[0][1]

    out_dir = Path(args.out_dir)
    out_dir.mkdir(parents=True, exist_ok=True)
    artifact_path = out_dir / f"{manifest['id']}-{manifest['version']}.muse-plugin"
    is_skin = manifest["kind"] == UI_SKIN_KIND
    with zipfile.ZipFile(artifact_path, "w", zipfile.ZIP_DEFLATED) as archive:
        archive.writestr("manifest.json", manifest_json_bytes)
        # Los paquetes de skins no deben contener ningún .js: la entrada solo termina con "manifest.entry + El "contenido vacío" participa en el resumen y no se escribe en el zip.
        if not is_skin:
            archive.writestr(entry_name, entry_bytes)
        for path in sorted(extras):
            archive.writestr(path, extras[path])
    archive_bytes = artifact_path.read_bytes()

    metadata = {
        "entry": {
            **{key: manifest[key] for key in ("id", "version", "name", "description")},
            "publisherId": args.publisher_id,
            "publisherKeyFingerprint": publisher["fingerprint"],
            "artifactFile": artifact_path.name,
            "artifactBytes": len(archive_bytes),
            "artifactSha256": sha256_hex(archive_bytes),
            "manifestSha256": manifest_sha256,
            "capabilities": manifest["capabilities"],
            "permissions": manifest["permissions"],
        },
        "publisherPublicKey": publisher["publicKey"],
    }
    metadata_path = out_dir / f"{manifest['id']}-{manifest['version']}.json"
    metadata_path.write_text(json.dumps(metadata, ensure_ascii=False, indent=2), encoding="utf-8")

    print(f"Paquete de complementos firmado: {artifact_path}")
    print(f"  id={manifest['id']} version={manifest['version']} Herramientas {len(manifest['tools'])} un")
    print(f"  artifactBytes={len(archive_bytes)} artifactSha256={metadata['entry']['artifactSha256']}")
    print(f"  manifestSha256={manifest_sha256}")
    print(f"  publisher={args.publisher_id} keyFingerprint={publisher['fingerprint']}")
    print(f"  Metadatos: {metadata_path}")


# ─────────────────────────── Directorio ───────────────────────────

def package_parts_from_zip(archive_path: Path) -> list:
    """Vuelva a calcular cada parte del paquete de acuerdo con las reglas de PluginPackageLoader para una verificación independiente de manifestSha256."""
    with zipfile.ZipFile(archive_path) as archive:
        names = [info.filename for info in archive.infolist() if not info.is_dir()]
        if "manifest.json" not in names:
            fail(f"{archive_path.name} desaparecido manifest.json")
        manifest_bytes = archive.read("manifest.json")
        manifest = json.loads(manifest_bytes.decode("utf-8"))
        entry_name = manifest.get("entry", "main.js")
        is_skin = manifest.get("kind") == UI_SKIN_KIND
        if entry_name in names:
            entry_bytes = archive.read(entry_name)
        elif is_skin:
            # El paquete skin no tiene una entrada JS: el host participa en el resumen con "nombre de entrada + contenido vacío".
            entry_bytes = b""
        else:
            fail(f"{archive_path.name} Falta el archivo de entrada {entry_name}")
        parts = [("manifest.json", manifest_bytes), (entry_name, entry_bytes)]
        for name in sorted(names):
            if name in ("manifest.json", entry_name):
                continue
            if not name.endswith(LOADED_EXTENSIONS):
                continue
            parts.append((name, archive.read(name)))
    return parts


def command_catalog(args) -> None:
    packages_dir = Path(args.packages_dir)
    key_path = Path(args.key)
    if not packages_dir.is_dir():
        fail(f"El directorio del paquete no existe: {packages_dir}")
    if not key_path.is_file():
        fail(f"La clave privada no existe: {key_path}")

    base_url = args.base_url.rstrip("/")
    entries = []
    # Lea solo metadatos con el mismo nombre que el paquete del complemento: ambos pueden existir en el directorio signed-catalog.json / public-keys.json
    # y otros JSON, compararlos por nombre evita tratarlos como metadatos de complementos.
    metadata_paths = []
    for artifact_path in sorted(packages_dir.glob("*.muse-plugin")):
        metadata_path = artifact_path.with_suffix(".json")
        if not metadata_path.is_file():
            fail(f"desaparecido {artifact_path.name} metadatos, vuelva a ejecutar pack")
        metadata_paths.append(metadata_path)
    if not metadata_paths:
        fail(f"{packages_dir} No hay ningún paquete de complemento firmado, ejecútelo primero pack")

    for metadata_path in metadata_paths:
        metadata = json.loads(metadata_path.read_text(encoding="utf-8"))
        source = dict(metadata["entry"])
        artifact_name = source.pop("artifactFile", None)
        if not artifact_name:
            fail(f"{metadata_path.name} desaparecido artifactFile")
        artifact_path = packages_dir / artifact_name
        if not artifact_path.is_file():
            fail(f"Falta el archivo del paquete de complementos: {artifact_path}")
        source["artifactUrl"] = base_url + "/" + artifact_path.name
        entry = ordered(source, ENTRY_ORDER, {}, f"entry({metadata_path.name})")

        # Recálculo independiente: el número de bytes, el resumen del paquete y el resumen del contenido deben ser coherentes con los valores registrados en la fase de empaquetado.
        archive_bytes = artifact_path.read_bytes()
        if len(archive_bytes) != entry["artifactBytes"]:
            fail(f"{entry['id']} El tamaño del registro no coincide con el tamaño real.")
        if sha256_hex(archive_bytes) != entry["artifactSha256"]:
            fail(f"{entry['id']} El resumen del paquete de registro no coincide con el real")
        if content_sha256(package_parts_from_zip(artifact_path)) != entry["manifestSha256"]:
            fail(f"{entry['id']} El resumen del contenido del registro no coincide con el contenido real del paquete.")
        entries.append(entry)

    if not entries:
        fail(f"{packages_dir} No hay metadatos del paquete de complementos firmados, ejecútelo primero pack")

    sequence = args.sequence if args.sequence is not None else int(time.time())
    now_ms = int(time.time() * 1000)
    payload = {
        "catalogId": args.catalog_id,
        "schemaVersion": SCHEMA_VERSION,
        "sequence": sequence,
        "generatedAtEpochMs": now_ms,
        "expiresAtEpochMs": now_ms + int(args.days * 86400 * 1000),
        "entries": entries,
    }
    payload = ordered(payload, CATALOG_ORDER, {}, "catalog")
    for entry in payload["entries"]:
        if not PLUGIN_ID_REGEX.match(entry["id"]):
            fail(f"La identificación de entrada del directorio es ilegal: {entry['id']}")
        if not VERSION_REGEX.match(entry["version"]):
            fail(f"La versión de entrada al directorio es ilegal: {entry['version']}")
        if not SHA256_REGEX.match(entry["artifactSha256"]) or not SHA256_REGEX.match(entry["manifestSha256"]):
            fail(f"El formato de resumen de entrada del directorio es ilegal: {entry['id']}")
        if not str(entry["artifactUrl"]).startswith("https://"):
            fail(f"La dirección de descarga de la entrada del directorio debe ser https: {entry['artifactUrl']}")

    payload_bytes = canonical_bytes(payload)
    signature = sign_bytes(key_path, payload_bytes)
    verify_signature(key_path, payload_bytes, signature)
    root = public_key_info(key_path)

    signed = {
        "payload": payload,
        "keyId": args.key_id,
        "signatureBase64": b64_standard(signature),
    }
    out_path = Path(args.out)
    out_path.parent.mkdir(parents=True, exist_ok=True)
    out_path.write_text(json.dumps(signed, ensure_ascii=False, indent=2), encoding="utf-8")

    publishers = {}
    for metadata_path in metadata_paths:
        metadata = json.loads(metadata_path.read_text(encoding="utf-8"))
        publishers[metadata["entry"]["publisherId"]] = metadata["publisherPublicKey"]
    keys_path = out_path.parent / "public-keys.json"
    keys_path.write_text(json.dumps({
        "catalog": {"keyId": args.key_id, "publicKey": root["publicKey"], "fingerprint": root["fingerprint"]},
        "publishers": publishers,
        "sequence": sequence,
        "expiresAtEpochMs": payload["expiresAtEpochMs"],
        "baseUrl": args.base_url.rstrip("/"),
    }, ensure_ascii=False, indent=2), encoding="utf-8")

    print(f"Directorio firmado: {out_path}")
    print(f"  catalogId={payload['catalogId']} sequence={sequence} entrada {len(entries)} un")
    print(f"  Válido hasta {time.strftime('%Y-%m-%d %H:%M:%S', time.localtime(payload['expiresAtEpochMs'] / 1000))}")
    print(f"  keyId={args.key_id}")
    print(f"  Confiar en la clave pública raíz(Base64): {root['publicKey']}")
    print(f"  raíz de la huella digital de confianza: {root['fingerprint']}")
    print(f"  Información de clave pública: {keys_path}")


def command_keygen(args) -> None:
    key_path = Path(args.key)
    generate_key(key_path)
    info = public_key_info(key_path)
    print(f"Clave privada P-256 generada: {key_path}")
    print(f"  clave pública(X.509 SPKI, Base64): {info['publicKey']}")
    print(f"  huella digital(SHA-256): {info['fingerprint']}")
    print("  Haga una copia de seguridad de la clave privada correctamente; una vez perdido, el directorio publicado y la raíz de confianza del usuario no se pueden actualizar.")


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="Muse Herramienta de firma fuera de línea del mercado de complementos")
    sub = parser.add_subparsers(dest="command", required=True)

    keygen = sub.add_parser("keygen", help="Generar clave privada P-256")
    keygen.add_argument("--key", required=True, help="Ruta de salida de clave privada")
    keygen.set_defaults(func=command_keygen)

    pack = sub.add_parser("pack", help="Hacer que el directorio del código fuente del complemento esté firmado .muse-plugin")
    pack.add_argument("--src", required=True, help="Directorio de código fuente del complemento (incluido manifest.json)")
    pack.add_argument("--key", required=True, help="Ruta de la clave privada del emisor")
    pack.add_argument("--publisher-id", required=True, help="Emisor ID")
    pack.add_argument("--out-dir", required=True, help="Directorio de salida del producto")
    pack.set_defaults(func=command_pack)

    catalog = sub.add_parser("catalog", help="Generar directorio de firmas con paquetes firmados")
    catalog.add_argument("--packages-dir", required=True, help="pack catalogo de productos")
    catalog.add_argument("--key", required=True, help="Ruta de la clave privada raíz del directorio")
    catalog.add_argument("--key-id", required=True, help="directorio raíz de confianza keyId")
    catalog.add_argument("--base-url", required=True, help="Dirección base externa del paquete de complemento (https)")
    catalog.add_argument("--catalog-id", default="muse-official", help="ID de directorio")
    catalog.add_argument("--out", required=True, help="Ruta de salida del directorio de firmas")
    catalog.add_argument("--sequence", type=int, default=None, help="Número de serie del directorio (el valor predeterminado es el segundo actual de Unix)")
    catalog.add_argument("--days", type=float, default=30, help="Días de validez")
    catalog.set_defaults(func=command_catalog)

    return parser


def main() -> None:
    args = build_parser().parse_args()
    args.func(args)


if __name__ == "__main__":
    main()
