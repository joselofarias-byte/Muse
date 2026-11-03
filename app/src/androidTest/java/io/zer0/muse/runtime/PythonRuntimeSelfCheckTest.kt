package io.zer0.muse.runtime

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.zer0.muse.tools.PythonScriptTool
import io.zer0.muse.tools.script.PythonSkillEngine
import io.zer0.muse.tools.script.SkillEngineResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * v2.7 Tiempo de ejecución extendido (Python) Autoprueba: verificación de enlace completo en tiempo de ejecución de Python incorporado (simulador/máquina real conectada).
 *
 * Enlace de cobertura:
 *  t1 stub binario existe (nativeLibraryDir colocación)
 *  t2 exec éxito (W^X Omitir verificación)+ python -V
 *  t3 stdlib Descomprimir (assets)
 *  t4 Evaluación básica (print / expresión)
 *  t5 Carga de la extensión C de la biblioteca estándar (zlib / hashlib / sqlite3 / ssl)
 *  t6 Lectura y escritura de archivos
 *  t7 Red + TLS (urllib + _ssl)
 *  t8 PythonSkillEngine De un extremo a otro (protocolo de resultados + registros)
 *  t9 execute_python_script capa de herramienta (JSON estructura de retorno)
 *  t10 Propagación de errores (excepción de script → ok=false)
 *
 * Ejecutar:
 *  ./gradlew :app:connectedDebugAndroidTest \
 *    -Pandroid.testInstrumentationRunnerArguments.class=io.zer0.muse.runtime.PythonRuntimeSelfCheckTest
 */
@RunWith(AndroidJUnit4::class)
class PythonRuntimeSelfCheckTest {

    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun t1_pythonStubExists() {
        val bin = PythonRuntime.pythonBinary(ctx)
        assertTrue("python stub debería existir en nativeLibraryDir: ${bin.absolutePath}", bin.isFile)
        assertTrue("stub tamaño anormal: ${bin.length()}", bin.length() > 1_000L)
    }

    @Test
    fun t2_pythonVersion() {
        runBlocking {
            val r = PythonRuntime.execPython(ctx, listOf("-V"), timeoutMs = 30_000)
            assertEquals("stderr=${r.stderr} timedOut=${r.timedOut}", 0, r.exitCode)
            assertTrue("Excepción de salida: ${r.stdout}${r.stderr}", (r.stdout + r.stderr).contains("3.14"))
        }
    }

    @Test
    fun t3_stdlibExtract() {
        runBlocking {
            val res = PythonRuntime.ensureData(ctx)
            assertTrue("La descompresión falló: ${res.exceptionOrNull()?.message}", res.isSuccess)
            assertTrue("os.py debería existir", java.io.File(PythonRuntime.stdlibDir(ctx), "os.py").isFile)
        }
    }

    @Test
    fun t4_basicEval() {
        runBlocking {
            PythonRuntime.ensureData(ctx).getOrThrow()
            val r = PythonRuntime.execPython(
                ctx,
                listOf("-c", "print(40 + 2)"),
                timeoutMs = 30_000,
            )
            assertEquals("stderr=${r.stderr}", 0, r.exitCode)
            assertTrue("Excepción de salida: ${r.stdout}", r.stdout.trim() == "42")
        }
    }

    @Test
    fun t5_cExtensions() {
        runBlocking {
            PythonRuntime.ensureData(ctx).getOrThrow()
            val code = """
                import zlib, hashlib, sqlite3, ssl, json, re
                h = hashlib.sha256(b'x').hexdigest()
                z = zlib.compress(b'muse')
                con = sqlite3.connect(':memory:')
                con.execute('create table t(a)')
                con.execute('insert into t values (1)')
                n = con.execute('select count(*) from t').fetchone()[0]
                print('ext-ok', len(h), len(z), n, ssl.OPENSSL_VERSION.split()[1])
            """.trimIndent()
            val r = PythonRuntime.execPython(ctx, listOf("-c", code), timeoutMs = 60_000)
            assertEquals("stderr=${r.stderr}", 0, r.exitCode)
            assertTrue("Excepción de salida: ${r.stdout}", r.stdout.contains("ext-ok"))
        }
    }

    @Test
    fun t6_fileIo() {
        runBlocking {
            PythonRuntime.ensureData(ctx).getOrThrow()
            val target = java.io.File(PythonRuntime.tmpDir(ctx), "py-io-test.txt")
            val code = """
                p = r'${target.absolutePath}'
                open(p, 'w').write('muse-python')
                print(open(p).read())
            """.trimIndent()
            val r = PythonRuntime.execPython(ctx, listOf("-c", code), timeoutMs = 30_000)
            assertEquals("stderr=${r.stderr}", 0, r.exitCode)
            assertTrue("Excepción de salida: ${r.stdout}", r.stdout.contains("muse-python"))
        }
    }

    @Test
    fun t7_networkTls() {
        runBlocking {
            PythonRuntime.ensureData(ctx).getOrThrow()
            val code = """
                import urllib.request, ssl
                try:
                    with urllib.request.urlopen('https://www.python.org/', timeout=30) as resp:
                        print('net-ok', resp.status)
                except Exception as e:
                    print('net-fail', type(e).__name__, e)
            """.trimIndent()
            val r = PythonRuntime.execPython(ctx, listOf("-c", code), timeoutMs = 90_000)
            assertEquals("stderr=${r.stderr}", 0, r.exitCode)
            assertTrue("TLS Fallo de red: ${r.stdout}", r.stdout.contains("net-ok"))
        }
    }

    @Test
    fun t8_skillEngineProtocol() {
        runBlocking {
            PythonRuntime.ensureData(ctx).getOrThrow()
            val result = PythonSkillEngine(ctx).eval(
                script = "print('hello from script')\nresult = {'sum': 6 * 7}",
                timeoutMs = 30_000,
            )
            assertTrue("debería tener éxito: $result", result is SkillEngineResult.Success)
            val ok = result as SkillEngineResult.Success
            assertTrue("value debe contener sum=42: ${ok.valueJson}", ok.valueJson.contains("42"))
            assertTrue("logs debe capturar print: ${ok.consoleLogs}", ok.consoleLogs.any { it.contains("hello from script") })
        }
    }

    @Test
    fun t9_toolLayerJson() {
        runBlocking {
            PythonRuntime.ensureData(ctx).getOrThrow()
            val json = PythonScriptTool.execute(
                ctx,
                mapOf("code" to "result = [1, 2, 3]"),
            )
            assertTrue("El regreso debe ser JSON: $json", json.contains("\"result\""))
            assertTrue("Debe contener un valor de matriz: $json", json.contains("[1,2,3]"))
            assertTrue("error debería ser null: $json", json.contains("\"error\":null"))
        }
    }

    @Test
    fun t10_errorPropagation() {
        runBlocking {
            PythonRuntime.ensureData(ctx).getOrThrow()
            val result = PythonSkillEngine(ctx).eval(
                script = "raise ValueError('boom')",
                timeoutMs = 30_000,
            )
            assertTrue("debería fallar: $result", result is SkillEngineResult.Error)
            assertTrue(
                "El error debe contener ValueError: ${(result as SkillEngineResult.Error).message}",
                result.message.contains("ValueError"),
            )
        }
    }

    /** v2.7 P2: Llamada a la función Python del complemento (callPluginFunction). */
    @Test
    fun t11_pluginFunctionCall() {
        runBlocking {
            PythonRuntime.ensureData(ctx).getOrThrow()
            val entry = """
                def greet(args):
                    return {"msg": "hello " + str(args.get("name", "world"))}
            """.trimIndent()
            val result = PythonSkillEngine(ctx).callPluginFunction(
                entryCode = entry,
                functionName = "greet",
                argsJson = "[{\"name\": \"muse\"}]",
                timeoutMs = 30_000,
            )
            assertTrue("La llamada a la función del complemento debería tener éxito: $result", result is SkillEngineResult.Success)
            val ok = result as SkillEngineResult.Success
            assertTrue("El valor de retorno debe contener hello muse: ${ok.valueJson}", ok.valueJson.contains("hello muse"))
        }
    }

    /** v2.7 P2: Se producirá un error legible cuando la entrada del complemento carezca de una función. */
    @Test
    fun t12_pluginMissingFunction() {
        runBlocking {
            PythonRuntime.ensureData(ctx).getOrThrow()
            val result = PythonSkillEngine(ctx).callPluginFunction(
                entryCode = "def other(args):\n    return 1\n",
                functionName = "missing_fn",
                argsJson = "[{}]",
                timeoutMs = 30_000,
            )
            assertTrue("debería fallar: $result", result is SkillEngineResult.Error)
            assertTrue(
                "Debería solicitar una función indefinida: ${(result as SkillEngineResult.Error).message}",
                result.message.contains("missing_fn"),
            )
        }
    }

    /** v2.7 P3:pip arranque (ensurepip) con instalación pura del paquete Python. */
    @Test
    fun t13_pipBootstrap() {
        runBlocking {
            val res = PythonRuntime.ensurePip(ctx)
            assertTrue("ensurepip falló: ${res.exceptionOrNull()?.message}", res.isSuccess)
            val r = PythonRuntime.execPython(ctx, listOf("-m", "pip", "--version"), timeoutMs = 60_000)
            assertEquals("stderr=${r.stderr}", 0, r.exitCode)
            assertTrue("pip Excepción de salida de versión: ${r.stdout}", r.stdout.contains("pip"))
        }
    }

    /** v2.7 P3:pip Instale el paquete Python puro e impórtelo. */
    @Test
    fun t14_pipInstallPurePythonPackage() {
        runBlocking {
            PythonRuntime.ensurePip(ctx).getOrThrow()
            // Elija un paquete que sea pequeño, puro Python y que no tenga extensiones C
            val install = PythonRuntime.execPython(
                ctx,
                listOf("-m", "pip", "install", "--no-cache-dir", "six"),
                timeoutMs = 180_000,
            )
            assertEquals("La instalación falló: ${install.stdout}\n${install.stderr}", 0, install.exitCode)
            val use = PythonRuntime.execPython(
                ctx,
                listOf("-c", "import six; result = six.__version__; print('six-ok', result)"),
                timeoutMs = 60_000,
            )
            assertEquals("stderr=${use.stderr}", 0, use.exitCode)
            assertTrue("debería poder import six: ${use.stdout}", use.stdout.contains("six-ok"))
        }
    }
}
