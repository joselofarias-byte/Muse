#!/usr/bin/env python3
"""F-02: Migración de la base de datos esperada/verificación dura real.

accidente historico: apareció"El enlace de migración está roto pero el control de acceso está todo verde"——Hay una brecha en la cadena migratoria(No hay migración inicial para una determinada versión.)y CI
Sin verificación,La ruta de actualización falla en máquinas reales. Este guión pone"Integridad de la cadena migratoria"Conviértase en CI con acceso restringido.

Verificación cuádruple:
  A. Registro estático consistente: addMigrations Cada MIGRATION_x_y referenciada debe tener una declaración correspondiente;
     Migración estática declarada(from < Versión actual)Todos deben estar registrados en addMigrations.
  B. cadena completa: addMigrations Versión de salida de(from)La colección debe cubrir 1..N-1(N=actual version),
     Todo a es mayor que y no mayor que desde N——Cualquier versión fallará si el enlace se rompe.
  C. Pruebas explícitas de migración en vivo: migrate75To76 / migrate76To77 La migración funcional debe ser
     MuseDbMigrationTest Referencia explícita(La migración estática se anula mediante la enumeración de reflexión de ManualChain).
  D. Existen pruebas de regresión críticas: MuseDbManualChainMigrationTest debe existir y aún contener
     declaredFields Enumeración de reflexión(Evite que la eliminación accidental de esta prueba provoque que la migración estática pierda cobertura de ejecución).

Autoprueba(Se debe crear un script de verificación del escenario de migración para eliminar FAIL):
  python3 ci/script/check_migration_coverage.py --mutate-delete 89,90
  python3 ci/script/check_migration_coverage.py --mutate-delete 90,91
"""

import argparse
import re
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
DB_PATH = REPO_ROOT / "app/src/main/java/io/zer0/muse/data/session/MuseDb.kt"
TEST_MIGRATION = REPO_ROOT / "app/src/test/java/io/zer0/muse/data/session/MuseDbMigrationTest.kt"
TEST_MANUAL_CHAIN = REPO_ROOT / "app/src/test/java/io/zer0/muse/data/session/MuseDbManualChainMigrationTest.kt"
FACT_DB_PATH = REPO_ROOT / "memory/src/main/java/io/zer0/memory/fact/FactDb.kt"
MEMORY_DB_PATH = REPO_ROOT / "memory/src/main/java/io/zer0/memory/summary/MemoryDb.kt"
MEMORY_DB_BUILDER_PATH = REPO_ROOT / "memory/src/main/java/io/zer0/memory/MemoryModule.kt"
MEMORY_DB_TEST = REPO_ROOT / "memory/src/test/java/io/zer0/memory/summary/MemoryDbMigrationTest.kt"

STATIC_DECL = re.compile(r"val\s+(MIGRATION_(\d+)_(\d+))\s*=\s*object\s*:\s*Migration")
DYNAMIC_DECL = re.compile(r"fun\s+(migrate(\d+)To(\d+))\b")
ADD_MIG_TOKEN = re.compile(r"\b(?:MIGRATION_(\d+)_(\d+)|migrate(\d+)To(\d+))\b")
TEST_REF = re.compile(r"MuseDb\.(?:MIGRATION_(\d+)_(\d+)|migrate(\d+)To(\d+))")
VERSION_RE = re.compile(r"version\s*=\s*(\d+)")
# `version = SOME_CONST` forma(Como el de FactDb FACT_DB_VERSION)con su constante declaración
CONST_VERSION_RE = re.compile(r"version\s*=\s*([A-Za-z_][A-Za-z0-9_]*)")


def resolve_current_version(db_text: str) -> int | None:
    """Analizar la base de datos actual version,Admite métodos de escritura tanto literal como constante.

    - literal:`version = 105`
    - constante:  `version = FACT_DB_VERSION` → trazar hacia atrás `const val FACT_DB_VERSION = 14`

    Las primeras coincidencias solo son literales,FactDb Se informará falsamente después de usar constantes en su lugar."No se puede analizar la corriente version"(Problema de calibre,rotura sin cadena).
    """
    literal = VERSION_RE.search(db_text)
    if literal:
        return int(literal.group(1))
    ref = CONST_VERSION_RE.search(db_text)
    if not ref:
        return None
    decl = re.search(
        rf"const\s+val\s+{re.escape(ref.group(1))}\s*=\s*(\d+)",
        db_text,
    )
    return int(decl.group(1)) if decl else None


def name_to_pair(name: str) -> tuple[int, int] | None:
    """'MIGRATION_68_74' -> (68, 74); 'migrate75To76' -> (75, 76)."""
    m = re.fullmatch(r"(?:MIGRATION_)?(\d+)_(\d+)", name)
    if m:
        return int(m.group(1)), int(m.group(2))
    m = re.fullmatch(r"migrate(\d+)To(\d+)", name)
    if m:
        return int(m.group(1)), int(m.group(2))
    return None


def extract_add_migrations(text: str) -> list[tuple[int, int]]:
    """de .addMigrations( Extraiga todo dentro del intervalo que coincida con el corchete de cierre (from, to)."""
    start = text.find(".addMigrations(")
    if start < 0:
        return []
    depth = 0
    for i in range(start, len(text)):
        ch = text[i]
        if ch == "(":
            depth += 1
        elif ch == ")":
            depth -= 1
            if depth == 0:
                block = text[start : i + 1]
                break
    else:
        block = text[start:]
    return [
        (int(a), int(b))
        for a, b, c, d in ADD_MIG_TOKEN.findall(block)
        for a, b in ([(a, b)] if a else [(c, d)])
    ]


def fail(msg: str, count: list[int]) -> None:
    count[0] += 1
    print(f"  FAIL: {msg}")


def check_extra_db_static_chain(
    db_path: Path,
    db_name: str,
    min_version: int,
    failures: list[int],
    registration_path: Path | None = None,
    test_path: Path | None = None,
) -> None:
    """a otras bases de datos(Como FactDb,MemoryDb)hacer verificación A+B(Registro estático consistente + cadena completa).

    Verifique la integridad de la cadena a partir de min_version:biblioteca parcial(Como por ejemplo FactDb v1/v2)es una version historica,
    Respaldado por la capa empresarial(archiveLegacyOrCorruptDatabase)Proceso,No en la cadena migratoria,Por lo tanto, no se considera una cadena rota.
    """
    print(f"\n===== {db_name} Verificación de la cadena de migración =====")
    initial_failure_count = failures[0]
    if not db_path.is_file():
        fail(f"{db_name}: no encontrado {db_path}", failures)
        return
    db_text = db_path.read_text(encoding="utf-8")
    current_version = resolve_current_version(db_text)
    if current_version is None:
        fail(f"{db_name}: No se puede analizar la corriente version", failures)
        return
    declared_static = {
        (int(a), int(b)): name for name, a, b in STATIC_DECL.findall(db_text)
    }
    registration_file = registration_path or db_path
    if not registration_file.is_file():
        fail(f"{db_name}: no encontrado migration builder {registration_file}", failures)
        return
    registration_text = registration_file.read_text(encoding="utf-8")
    registered = extract_add_migrations(registration_text)
    registered_set = set(registered)
    reachable_static = {(f, t) for (f, t) in declared_static if f < current_version}
    print(f"{db_name} version = {current_version}, Número de migraciones registradas = {len(registered)} (min versión {min_version})")

    missing_decl = sorted(registered_set - set(declared_static))
    for pair in missing_decl:
        fail(f"{db_name} addMigrations Migración no declarada referenciada {pair}", failures)
    unregistered = sorted(reachable_static - registered_set)
    for pair in unregistered:
        fail(f"{db_name} migración estática {pair} Declarado pero no registrado addMigrations", failures)

    froms = {f for f, t in registered}
    missing_from = [v for v in range(min_version, current_version) if v not in froms]
    for v in missing_from:
        fail(f"{db_name} versión {v} Sin migración de salida(cadena rota)", failures)
    for f, t in sorted(registered):
        if not (f < t <= current_version):
            fail(f"{db_name} Migrar {f}->{t} ilegal: necesito estar satisfecho from < to <= {current_version}", failures)

    if test_path is not None:
        if not test_path.is_file():
            fail(f"{db_name}: Prueba de migración no encontrada {test_path}", failures)
        else:
            test_text = test_path.read_text(encoding="utf-8")
            tested = {
                (int(from_version), int(to_version))
                for from_version, to_version in re.findall(
                    rf"\b{re.escape(db_name)}\.MIGRATION_(\d+)_(\d+)\b",
                    test_text,
                )
            }
            for pair in sorted(registered_set & set(declared_static)):
                if pair not in tested:
                    fail(f"{db_name} Migrar {pair} sido {test_path.name} Referencia explícita", failures)

    if failures[0] == initial_failure_count:
        print(f"  PASS: {db_name} La cadena de migración estática está completa.(Sin cadena rota)")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--db-path", type=Path, default=DB_PATH, help="Anular la ruta MuseDb.kt(Para autocomprobación)")
    parser.add_argument("--test-path", type=Path, default=TEST_MIGRATION, help="Cubrir la ruta de prueba de migración(Para autocomprobación)")
    parser.add_argument(
        "--mutate-delete",
        metavar="FROM,TO",
        default=None,
        help="Autoprueba: Simula la eliminación de migraciones específicas de addMigrations(separados por comas from,to),El guión debe FAIL",
    )
    args = parser.parse_args()

    db_path = args.db_path
    test_path = args.test_path
    failures: list[int] = [0]

    if not db_path.is_file():
        print(f"FATAL: no encontrado {db_path}")
        return 1
    db_text = db_path.read_text(encoding="utf-8")

    current_version = resolve_current_version(db_text)
    if current_version is None:
        print("FATAL: No se puede analizar la corriente de MuseDb.kt version")
        return 1

    declared_static = {
        (int(a), int(b)): name for name, a, b in STATIC_DECL.findall(db_text)
    }
    declared_dynamic = {
        (int(a), int(b)): name for name, a, b in DYNAMIC_DECL.findall(db_text)
    }
    registered = extract_add_migrations(db_text)

    # Modo de autoprueba: Simular la eliminación de la migración especificada.
    if args.mutate_delete:
        f, t = (int(x) for x in args.mutate_delete.split(","))
        registered = [p for p in registered if p != (f, t)]
        print(f"[Autoprueba] Eliminación simulada de migraciones de addMigrations {f}->{t}")

    print(f"MuseDb version = {current_version}")
    print(f"addMigrations Número de migraciones registradas = {len(registered)}")
    print(f"Número de migración de declaración estática = {len(declared_static)} (Contiene from >= {current_version} declaración inalcanzable)")
    print(f"Número de declaraciones de migración dinámica = {len(declared_dynamic)}")

    registered_set = set(registered)
    reachable_static = {(f, t) for (f, t) in declared_static if f < current_version}

    print("\n[verificar A] Registro estático consistente")
    for pair in sorted(registered_set & set(declared_dynamic)):
        pass  # Ver verificación para migración dinámica C
    missing_decl = sorted(registered_set - set(declared_static) - set(declared_dynamic))
    for pair in missing_decl:
        fail(f"addMigrations Referenciado a no declarado/migración indefinida {pair}", failures)
    unregistered = sorted(reachable_static - registered_set)
    for pair in unregistered:
        fail(f"migración estática {pair} Declarado pero no registrado addMigrations", failures)
    if not missing_decl and not unregistered:
        print("  PASS: addMigrations Todas las citas tienen una declaración., La migración estática accesible está toda registrada")

    print("\n[verificar B] cadena completa(from Portada 1..N-1)")
    froms = {f for f, t in registered}
    missing_from = [v for v in range(1, current_version) if v not in froms]
    for v in missing_from:
        fail(f"versión {v} Sin migración de salida(cadena rota), La ruta de actualización comienza desde {v} Inalcanzable", failures)
    for f, t in sorted(registered):
        if not (f < t <= current_version):
            fail(f"Migrar {f}->{t} ilegal: necesito estar satisfecho from < to <= {current_version}", failures)
    if not missing_from:
        print(f"  PASS: from Portada 1..{current_version - 1} Toda la cadena, Versión sin enlace roto")

    print("\n[verificar C] Pruebas explícitas de migración en vivo")
    test_text = test_path.read_text(encoding="utf-8") if test_path.is_file() else ""
    tested = {
        (int(a), int(b)) if a else (int(c), int(d))
        for a, b, c, d in TEST_REF.findall(test_text)
    }
    dynamic_registered = sorted(set(registered) & set(declared_dynamic))
    for pair in dynamic_registered:
        if pair not in tested:
            fail(f"Migración en vivo {pair} Registrado pero no referenciado explícitamente por MuseDbMigrationTest", failures)
    if not dynamic_registered:
        print("  PASS: Sin registro de migración en vivo(cheque nulo)")
    elif all(p in tested for p in dynamic_registered):
        print(f"  PASS: {len(dynamic_registered)} Cada migración dinámica tiene una referencia de prueba explícita.")

    print("\n[verificar D] Existe una prueba de regresión en cadena manual")
    if TEST_MANUAL_CHAIN.is_file():
        chain_text = TEST_MANUAL_CHAIN.read_text(encoding="utf-8")
        if "declaredFields" in chain_text and "MIGRATION_" in chain_text:
            print("  PASS: MuseDbManualChainMigrationTest Existe y contiene enumeración reflexiva.")
        else:
            fail("MuseDbManualChainMigrationTest Falta la enumeración de reflexión de campos declarados", failures)
    else:
        fail("MuseDbManualChainMigrationTest no existe, La migración estática pierde cobertura de ejecución", failures)

    check_extra_db_static_chain(FACT_DB_PATH, "FactDb", 3, failures)
    check_extra_db_static_chain(
        MEMORY_DB_PATH,
        "MemoryDb",
        1,
        failures,
        registration_path=MEMORY_DB_BUILDER_PATH,
        test_path=MEMORY_DB_TEST,
    )

    print()
    if failures[0] == 0:
        print(f"PASS: Se aprobó la verificación estricta de la cadena de migración(version {current_version}, {len(registered)} Migrar)")
        return 0
    print(f"FAIL: {failures[0]} Error en la verificación de la cadena de migración del collar")
    return 1


if __name__ == "__main__":
    sys.exit(main())
