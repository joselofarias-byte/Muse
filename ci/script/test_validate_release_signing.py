import base64
import contextlib
import io
import os
from pathlib import Path
import shutil
import stat
import subprocess
import sys
import tempfile
import unittest
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parent))
import validate_release_signing as signing


class SigningTest(unittest.TestCase):
    def test_normalization_is_unchanged(self):
        self.assertEqual(signing.normalize_text('\ufeff  a\\b \r\n', 'test'), 'a\\b')
        self.assertEqual(signing.decode_keystore('\ufeff YW\r\nJj '), b'abc')

    def test_missing_secret_fails_closed_without_writing(self):
        with tempfile.TemporaryDirectory() as directory:
            store = Path(directory) / 'store'
            properties = Path(directory) / 'properties'
            with mock.patch.dict(os.environ, {'KEYSTORE_PASSWORD': 'do-not-print'}, clear=True), \
                    mock.patch.object(sys, 'argv', ['signing', '--keystore-out', str(store),
                                                   '--properties-out', str(properties)]), \
                    contextlib.redirect_stderr(io.StringIO()) as errors:
                self.assertEqual(signing.main(), 1)
            self.assertIn('missing GitHub Actions secrets', errors.getvalue())
            self.assertNotIn('do-not-print', errors.getvalue())
            self.assertFalse(store.exists())
            self.assertFalse(properties.exists())

    def test_empty_normalized_secret_fails(self):
        values = dict.fromkeys(signing.SECRET_NAMES, 'present')
        values['KEY_PASSWORD'] = '\ufeff \r\n'
        with mock.patch.dict(os.environ, values, clear=True):
            with self.assertRaisesRegex(ValueError, 'KEY_PASSWORD is empty'):
                signing.require_secrets()

    def test_sensitive_files_private_before_first_write_and_after_replace(self):
        original_fdopen = os.fdopen
        modes = []

        def check_mode(fd, *args, **kwargs):
            modes.append(stat.S_IMODE(os.fstat(fd).st_mode))
            return original_fdopen(fd, *args, **kwargs)

        with tempfile.TemporaryDirectory() as directory:
            store = Path(directory) / 'store'
            properties = Path(directory) / 'properties'
            properties.write_text('old')
            properties.chmod(0o644)
            previous_umask = os.umask(0)
            try:
                with mock.patch.object(signing.os, 'fdopen', side_effect=check_mode):
                    signing.write_private(store, b'private key fixture')
                    signing.write_properties(properties, store, {
                        'store_password': 'store', 'alias': 'alias', 'key_password': 'key',
                    })
            finally:
                os.umask(previous_umask)
            self.assertEqual(modes, [0o600, 0o600])
            self.assertEqual(stat.S_IMODE(store.stat().st_mode), 0o600)
            self.assertEqual(stat.S_IMODE(properties.stat().st_mode), 0o600)
            self.assertEqual(sorted(p.name for p in Path(directory).iterdir()), ['properties', 'store'])

    def test_properties_escaping(self):
        self.assertEqual(signing.escape_property(' \\x\n\r\t\f=:#!é😀'),
                         r'\ \\x\n\r\t\f\=\:\#\!\u00e9\ud83d\ude00')

    @unittest.skipUnless(shutil.which('java'), 'JDK required')
    def test_properties_roundtrip_using_java_inputstream(self):
        with tempfile.TemporaryDirectory() as directory:
            directory = Path(directory)
            properties = directory / 'signing.properties'
            store = directory / 'é\\store.jks'
            values = {
                'store_password': ' \\secret\n\r\t\f=:#!é😀 ',
                'alias': 'música=:#\\alias',
                'key_password': '密碼\\u0041',
            }
            signing.write_properties(properties, store, values)
            source = directory / 'ReadProperties.java'
            source.write_text('''
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
class ReadProperties {
    public static void main(String[] args) throws Exception {
        Properties p = new Properties();
        try (InputStream in = new FileInputStream(args[0])) { p.load(in); }
        for (String key : new String[]{"storeFile", "storePassword", "keyAlias", "keyPassword"}) {
            System.out.println(Base64.getEncoder().encodeToString(
                p.getProperty(key).getBytes(StandardCharsets.UTF_8)));
        }
    }
}
''', encoding='utf-8')
            result = subprocess.run(['java', str(source), str(properties)],
                                    capture_output=True, text=True, check=True)
            self.assertEqual([base64.b64decode(line).decode('utf-8')
                              for line in result.stdout.splitlines()],
                             [str(store.resolve()), values['store_password'],
                              values['alias'], values['key_password']])

    def test_keytool_diagnostics_are_never_exposed(self):
        result = subprocess.CompletedProcess([], 1, 'secret-alias', 'secret-password')
        with mock.patch.object(signing, 'keytool_path', return_value='keytool'), \
                mock.patch.object(signing.subprocess, 'run', return_value=result):
            with self.assertRaisesRegex(RuntimeError, '^keystore preflight validation failed$'):
                signing.run_keytool([], {}, 'validation')


@unittest.skipUnless(shutil.which('keytool'), 'keytool required')
class RealKeytoolTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temporary = tempfile.TemporaryDirectory(prefix='signing-test-')
        cls.directory = Path(cls.temporary.name)
        cls.store = cls.directory / 'fixture.jks'
        cls.env = os.environ.copy()
        cls.env.update(MUSE_STORE_PASSWORD='fixture-store-password',
                       MUSE_KEY_PASSWORD='fixture-key-password')
        cls.values = {'alias': 'fixture'}
        # These disposable fixtures are never production signing identities.
        for alias, subject in [('fixture', 'CN=Signing Test'),
                               ('debug', 'CN=Android Debug,O=Android,C=US')]:
            cls.run_tool(['-genkeypair', '-storetype', 'JKS', '-keystore', str(cls.store),
                          '-storepass:env', 'MUSE_STORE_PASSWORD', '-keypass:env',
                          'MUSE_KEY_PASSWORD', '-alias', alias, '-dname', subject,
                          '-keyalg', 'RSA', '-keysize', '2048', '-validity', '1'])
        certificate = cls.directory / 'fixture.cer'
        cls.run_tool(['-exportcert', '-keystore', str(cls.store), '-storepass:env',
                      'MUSE_STORE_PASSWORD', '-alias', 'fixture', '-file', str(certificate)])
        cls.run_tool(['-importcert', '-noprompt', '-keystore', str(cls.store), '-storepass:env',
                      'MUSE_STORE_PASSWORD', '-alias', 'trusted', '-file', str(certificate)])

    @classmethod
    def tearDownClass(cls):
        cls.temporary.cleanup()

    @classmethod
    def run_tool(cls, args):
        subprocess.run([signing.keytool_path(), *args], env=cls.env,
                       capture_output=True, text=True, check=True)

    def test_real_private_key_password_validates_and_csr_is_cleaned(self):
        original = signing.run_keytool
        requests = []

        def inspect(args, env, stage):
            if '-certreq' in args:
                path = Path(args[args.index('-file') + 1])
                requests.append(path)
                self.assertEqual(stat.S_IMODE(path.parent.stat().st_mode), 0o700)
                self.assertEqual(stat.S_IMODE(path.stat().st_mode), 0o600)
            return original(args, env, stage)

        with mock.patch.object(signing, 'run_keytool', side_effect=inspect):
            result = signing.validate_key(self.store, self.values, self.env)
        self.assertIn('SHA256:', result)
        self.assertEqual(len(requests), 1)
        self.assertFalse(requests[0].parent.exists())

    def test_wrong_key_password_fails_even_when_list_succeeds(self):
        env = dict(self.env, MUSE_KEY_PASSWORD='incorrect-fixture-password')
        with self.assertRaisesRegex(RuntimeError, 'private key/key password failed'):
            signing.validate_key(self.store, self.values, env)

    def test_wrong_store_password_fails(self):
        env = dict(self.env, MUSE_STORE_PASSWORD='incorrect-store-password')
        with self.assertRaisesRegex(RuntimeError, 'store/alias failed'):
            signing.validate_key(self.store, self.values, env)

    def test_certificate_only_entry_fails(self):
        with self.assertRaisesRegex(RuntimeError, 'private key/key password failed'):
            signing.validate_key(self.store, {'alias': 'trusted'}, self.env)

    def test_android_debug_certificate_is_rejected(self):
        with self.assertRaisesRegex(ValueError, 'Android debug certificates'):
            signing.validate_key(self.store, {'alias': 'debug'}, self.env)

    def test_missing_alias_fails_without_echoing_alias(self):
        with self.assertRaisesRegex(RuntimeError, '^keystore preflight store/alias failed$'):
            signing.validate_key(self.store, {'alias': 'private-sensitive-alias'}, self.env)


if __name__ == '__main__':
    unittest.main()
