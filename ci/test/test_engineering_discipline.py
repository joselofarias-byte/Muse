#!/usr/bin/env python3
"""
test_engineering_discipline.py — check_engineering_discipline.py pruebas unitarias.

Pruebe la exactitud de la coincidencia regular de varias reglas de disciplinas de ingeniería.
correr: py -3 ci/test/test_engineering_discipline.py
"""

import sys
from pathlib import Path

# Agregar ci/script path
SCRIPT_DIR = Path(__file__).resolve().parents[1] / "script"
sys.path.insert(0, str(SCRIPT_DIR))

from check_engineering_discipline import RULES


def _matches(rule_name: str, code: str) -> bool:
    """Comprueba si la etiqueta dada activa la regla especificada."""
    pattern = RULES[rule_name]["pattern"]
    return bool(pattern.search(code))


def test_unsafe_cast_string():
    """as String Debe reconocerse como una conversión insegura."""
    assert _matches("unsafe_cast", "val name = data as String")
    assert _matches("unsafe_cast", "val x = obj as Int")


def test_unsafe_cast_not_triggered_on_safe_cast():
    """as? String Las transmisiones seguras no deberían activar reglas unsafe_cast."""
    # Nota: as? en seguido de ?,regular \bas\s+ Se requiere que as vaya seguido de un espacio en blanco y luego el tipo
    # as?String no hay espacio entre,no coincidirá;as? String en ? después como,no coincidirá \bas\s+
    assert not _matches("unsafe_cast", "val name = data as? String")
    assert not _matches("unsafe_cast", "val name = data as? String ?: \"\"")


def test_non_null_assertion():
    """descomentado !! debe ser identificado."""
    assert _matches("non_null_assertion", "val name = user!!.name")
    assert _matches("non_null_assertion", "val x = foo!!")


def test_non_null_assertion_with_comment_allowed():
    """anotado !! no debería desencadenar(permitido en el contrato)."""
    assert not _matches("non_null_assertion", "val name = user!!.name // Confirmado no vacío")
    assert not _matches("non_null_assertion", "val x = foo!! // by contract")


def test_empty_catch():
    """Los bloques catch vacíos deben reconocerse como errores."""
    assert _matches("empty_catch", "try { foo() } catch (e: Exception) {}")
    assert _matches("empty_catch", "catch (e: Throwable) { }")
    assert RULES["empty_catch"]["severity"] == "error"


def test_non_empty_catch_not_triggered():
    """Los bloques de captura que no estén vacíos no deben dispararse."""
    assert not _matches("empty_catch", "try { foo() } catch (e: Exception) { Logger.w(e) }")


def test_null_fallback():
    """descomentado ?: null debe ser identificado."""
    assert _matches("null_fallback", "val name = user?.name ?: null")
    assert _matches("null_fallback", "val x = getValue() ?: null")


def test_null_fallback_with_comment_allowed():
    """anotado ?: null No debería dispararse."""
    assert not _matches("null_fallback", "val name = user?.name ?: null // Puede que no se inicialice")


def test_default_fallback():
    """?: "unknown" / ?: "default" debe ser identificado."""
    assert _matches("default_fallback", 'val name = user?.name ?: "unknown"')
    assert _matches("default_fallback", 'val type = obj?.type ?: "default"')


def test_any_type_declaration():
    """val/var x: Any Explícito Se debe reconocer cualquier tipo."""
    assert _matches("any_type_declaration", "val data: Any = parseResponse()")
    assert _matches("any_type_declaration", "var cache: Any = Object()")
    assert _matches("any_type_declaration", "val payload: Any? = null")


def test_any_type_declaration_not_triggered_on_generic():
    """Map<String, Any> etc. Los parámetros genéricos no deberían activar(Ese es el uso legal.)."""
    # en regular (?!\s*[<,]) Asegúrate de no ser seguido por Cualquiera < o , coincidir cuando
    assert not _matches("any_type_declaration", "val map: Map<String, Any> = emptyMap()")
    assert not _matches("any_type_declaration", "val list: List<Any> = emptyList()")


def test_any_return_type():
    """fun foo(): Any Se deben reconocer las funciones que devuelven Any."""
    assert _matches("any_return_type", "fun getData(): Any { return 42 }")
    assert _matches("any_return_type", "fun parse(): Any? { return null }")


def test_any_return_type_not_triggered_on_generic():
    """Regresar Map<String, Any> etc. Los genéricos no deberían desencadenar."""
    assert not _matches("any_return_type", "fun getMap(): Map<String, Any> { return emptyMap() }")


def test_when_else_null():
    """when bloquear else -> null Debería reconocerse como un tipo sindical."""
    assert _matches("when_else_null", "else -> null")
    assert _matches("when_else_null", "    else -> null")


def test_when_else_null_with_comment_allowed():
    """anotado else -> null No debería dispararse."""
    assert not _matches("when_else_null", "else -> null // Compatible con versiones anteriores")
    assert not _matches("when_else_null", "else -> null // business fallback")


def test_when_else_other_value_not_triggered():
    """else -> Otros valores no deberían activar when_else_null."""
    assert not _matches("when_else_null", "else -> 0")
    assert not _matches("when_else_null", 'else -> ""')
    assert not _matches("when_else_null", "else -> error(\"unknown\")")


def test_nullable_unsafe_cast():
    """(value as String) Se deben reconocer las conversiones no seguras entre paréntesis."""
    assert _matches("nullable_unsafe_cast", "val processed = (value as String).trim()")
    assert _matches("nullable_unsafe_cast", "val n = (obj as Int).toLong()")


def test_nullable_unsafe_cast_not_triggered_on_safe():
    """(value as? String) Las transiciones de seguridad no deberían desencadenarse."""
    assert not _matches("nullable_unsafe_cast", "val processed = (value as? String)?.trim()")


def test_all_rules_have_required_fields():
    """Todas las reglas deben contener pattern / severity / message Tres campos."""
    for name, rule in RULES.items():
        assert "pattern" in rule, f"reglas {name} desaparecido pattern"
        assert "severity" in rule, f"reglas {name} desaparecido severity"
        assert "message" in rule, f"reglas {name} desaparecido message"
        assert rule["severity"] in ("error", "warning"), f"reglas {name} La gravedad debe ser error o warning"


def test_error_rules_block_merge():
    """error Las reglas de nivel deben impedir la fusión."""
    error_rules = [n for n, r in RULES.items() if r["severity"] == "error"]
    assert "empty_catch" in error_rules, "empty_catch Debe ser nivel de error"


def run_all_tests():
    """Ejecute todas las pruebas e informe los resultados."""
    tests = [
        test_unsafe_cast_string,
        test_unsafe_cast_not_triggered_on_safe_cast,
        test_non_null_assertion,
        test_non_null_assertion_with_comment_allowed,
        test_empty_catch,
        test_non_empty_catch_not_triggered,
        test_null_fallback,
        test_null_fallback_with_comment_allowed,
        test_default_fallback,
        test_any_type_declaration,
        test_any_type_declaration_not_triggered_on_generic,
        test_any_return_type,
        test_any_return_type_not_triggered_on_generic,
        test_when_else_null,
        test_when_else_null_with_comment_allowed,
        test_when_else_other_value_not_triggered,
        test_nullable_unsafe_cast,
        test_nullable_unsafe_cast_not_triggered_on_safe,
        test_all_rules_have_required_fields,
        test_error_rules_block_merge,
    ]

    passed = 0
    failed = 0
    for test in tests:
        try:
            test()
            print(f"  [PASS] {test.__name__}")
            passed += 1
        except AssertionError as e:
            print(f"  [FAIL] {test.__name__}: {e}")
            failed += 1
        except Exception as e:
            print(f"  [ERROR] {test.__name__}: {e}")
            failed += 1

    print(f"\n{'='*50}")
    print(f"Total: {passed + failed}, Passed: {passed}, Failed: {failed}")
    return 0 if failed == 0 else 1


if __name__ == "__main__":
    sys.exit(run_all_tests())
