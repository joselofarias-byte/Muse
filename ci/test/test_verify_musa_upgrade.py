"""Upgrade checks use mocked Android tools, never keys or credentials."""

import subprocess
import sys
from pathlib import Path
from unittest.mock import patch

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "script"))
import verify_musa_upgrade as upgrade

CERT = "ab" * 32


def badging(code=400, package="io.zer0.muse", extra=""):
    return (f"package: name='{package}' versionCode='{code}' versionName='1.0'\n"
            "application-label:'Musa'\n"
            "native-code: 'arm64-v8a'\n" + extra)


def signature(cert=CERT, dn="CN=Musa", extra=""):
    return ("Verifies\nNumber of signers: 1\n"
            f"Signer #1 certificate DN: {dn}\n"
            f"Signer #1 certificate SHA-256 digest: {cert}\n" + extra)


@pytest.fixture
def apks(tmp_path):
    previous = tmp_path / "previous.apk"
    candidate = tmp_path / "candidate.apk"
    previous.touch()
    candidate.touch()
    return candidate, previous


def verify(apks, previous=None, candidate=None, old_sign=None, new_sign=None):
    outputs = [previous if previous is not None else badging(),
               old_sign if old_sign is not None else signature(),
               candidate if candidate is not None else badging(401),
               new_sign if new_sign is not None else signature()]
    with patch.object(upgrade.subprocess, "run", side_effect=[
        subprocess.CompletedProcess([], 0, out, "") for out in outputs
    ]) as run:
        result = upgrade.verify_upgrade(*apks, "/sdk/aapt", "/sdk/apksigner")
    assert run.call_args_list[0].args[0] == ["/sdk/aapt", "dump", "badging", str(apks[1])]
    assert run.call_args_list[1].args[0] == [
        "/sdk/apksigner", "verify", "--verbose", "--print-certs", str(apks[1])]
    return result


def test_upgrade_passes(apks):
    old, new = verify(apks, new_sign=signature(CERT.upper()))
    assert (old.version_code, new.version_code, new.package) == (400, 401, "io.zer0.muse")


@pytest.mark.parametrize("changes", [
    {"new_sign": signature("cd" * 32)},
    {"candidate": badging(401, "other.package")},
    {"candidate": badging(400)},
    {"candidate": badging(399)},
    {"previous": badging(extra="application-debuggable\n")},
    {"candidate": badging(401, extra="application-debuggable\n")},
    {"old_sign": signature(dn="C=US, O=Android, CN=Android Debug")},
    {"new_sign": signature(dn="CN=ANDROID DEBUG, O=Android")},
    {"previous": ""},
    {"candidate": "package: name='io.zer0.muse' versionCode='invalid'\n"},
    {"candidate": badging(401) + badging(401)},
    {"candidate": badging(401).replace("versionCode='401'", "versionCode='401' versionCode='402'")},
    {"candidate": badging(401).replace("arm64-v8a", "x86_64")},
    {"candidate": badging(401).replace("'arm64-v8a'", "'arm64-v8a' 'armeabi-v7a'")},
    {"new_sign": ""},
    {"new_sign": signature("bad")},
    {"new_sign": signature().replace("Signer #1", "Signer #2")},
    {"new_sign": signature().replace("Number of signers: 1", "Number of signers: 2")},
    {"new_sign": signature(extra=f"Signer #2 certificate SHA-256 digest: {CERT}\n")},
    {"new_sign": signature(extra="Signer #2 certificate SHA-256 digest: invalid\n")},
    {"new_sign": signature(extra="Signer #2 certificate DN: CN=Musa\n")},
    {"new_sign": signature().replace("Signer #1 certificate DN: CN=Musa\n", "")},
])
def test_rejects_unsafe_or_unparseable_upgrade(apks, changes):
    with pytest.raises(ValueError):
        verify(apks, **changes)


def test_external_tool_failure_is_closed(apks):
    with patch.object(upgrade.subprocess, "run", return_value=subprocess.CompletedProcess([], 1, "", "private output")):
        with pytest.raises(ValueError, match="Falló aapt") as error:
            upgrade.verify_upgrade(*apks, "aapt", "apksigner")
    assert "private output" not in str(error.value)


def test_missing_apk_is_rejected(tmp_path):
    with pytest.raises(ValueError, match="No existe"):
        upgrade.verify_upgrade(tmp_path / "missing.apk", tmp_path / "previous.apk", "aapt", "apksigner")


def test_cli_uses_environment(apks, monkeypatch):
    for key, value in zip(("APK", "PREVIOUS_APK", "AAPT", "APKSIGNER"), (*apks, "/sdk/aapt", "/sdk/apksigner")):
        monkeypatch.setenv(key, str(value))
    info = upgrade.ApkInfo("io.zer0.muse", 400, CERT)
    with patch.object(upgrade, "verify_upgrade", return_value=(info, info)) as verify_mock:
        assert upgrade.main([]) == 0
    verify_mock.assert_called_once_with(*apks, "/sdk/aapt", "/sdk/apksigner")


def test_cli_failure(apks):
    with patch.object(upgrade, "verify_upgrade", side_effect=ValueError("rechazado")):
        assert upgrade.main(["--apk", str(apks[0]), "--previous-apk", str(apks[1])]) == 1
