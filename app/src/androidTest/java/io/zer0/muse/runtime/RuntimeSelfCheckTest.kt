package io.zer0.muse.runtime

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Forma del número de versión:vX.Y.Z o X.Y.Z. Solo se verifica la versión que se puede generar y no se vincula ninguna versión específica. */
private val NODE_VERSION_SHAPE = Regex("""^v?\d+\.\d+\.\d+""")

/**
 * P0 Autoprueba de revisión: verificación de enlace completo en tiempo de ejecución de nodo incorporada (prueba conectada a un simulador/máquina real).
 *
 * Enlace de cobertura:
 *  t1 binario existe (nativeLibraryDir colocación)
 *  t2 exec éxito (W^X Omitir verificación) + node -v
 *  t3 JS evaluar
 *  t4 Carga dinámica de biblioteca (zlib / crypto / ICU)
 *  t5 Red + TLS (libssl / libcrypto / CA)
 *  t6 assets Descompresión de datos (npm)
 *  t7 npm ejecutar
 *
 * Ejecutar:
 *  ./gradlew :app:connectedDebugAndroidTest \
 *    -Pandroid.testInstrumentationRunnerArguments.class=io.zer0.muse.runtime.RuntimeSelfCheckTest
 */
@RunWith(AndroidJUnit4::class)
class RuntimeSelfCheckTest {

    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun t1_nodeBinaryExists() {
        val node = MuseRuntime.nodeBinary(ctx)
        assertTrue("node debería existir en nativeLibraryDir: ${node.absolutePath}", node.isFile)
        assertTrue("node tamaño anormal: ${node.length()}", node.length() > 10_000_000L)
    }

    @Test
    fun t2_nodeVersion() {
        runBlocking {
            val r = MuseRuntime.execNode(ctx, listOf("-v"), timeoutMs = 20_000)
            assertEquals("stderr=${r.stderr} timedOut=${r.timedOut}", 0, r.exitCode)
            assertTrue("Excepción de salida: ${r.stdout}", NODE_VERSION_SHAPE.containsMatchIn(r.stdout.trim()))
        }
    }

    @Test
    fun t3_nodeEval() {
        runBlocking {
            val r = MuseRuntime.execNode(ctx, listOf("-e", "console.log(40+2)"), timeoutMs = 20_000)
            assertEquals("stderr=${r.stderr}", 0, r.exitCode)
            assertEquals("42", r.stdout.trim())
        }
    }

    @Test
    fun t4_nativeDeps() {
        runBlocking {
            val js = """
                const z = require('zlib');
                const c = require('crypto');
                const g = z.gzipSync(Buffer.from('muse'));
                const h = c.createHash('sha256').update('x').digest('hex');
                const n = new Intl.NumberFormat('en-US').format(1234567.89);
                console.log('deps-ok', g.length, h.length, n);
            """.trimIndent()
            val r = MuseRuntime.execNode(ctx, listOf("-e", js), timeoutMs = 30_000)
            assertEquals("stderr=${r.stderr}", 0, r.exitCode)
            assertTrue("Excepción de salida: ${r.stdout}", r.stdout.contains("deps-ok"))
        }
    }

    @Test
    fun t5_networkTls() {
        runBlocking {
            val js = "fetch('https://registry.npmjs.org/-/ping')" +
                ".then(r=>{console.log('net-ok',r.status);process.exit(0)})" +
                ".catch(e=>{console.error('net-fail:'+e.message);process.exit(2)})"
            val r = MuseRuntime.execNode(ctx, listOf("-e", js), timeoutMs = 90_000)
            assertEquals("stdout=${r.stdout} stderr=${r.stderr}", 0, r.exitCode)
            assertTrue(r.stdout.contains("net-ok"))
        }
    }

    @Test
    fun t6_npmDataExtract() {
        runBlocking {
            val res = MuseRuntime.ensureData(ctx)
            assertTrue("La descompresión falló: ${res.exceptionOrNull()?.message}", res.isSuccess)
            assertTrue("npm-cli.js debería existir", MuseRuntime.npmCli(ctx).isFile)
        }
    }

    @Test
    fun t7_npmVersion() {
        runBlocking {
            MuseRuntime.ensureData(ctx).getOrThrow()
            val r = MuseRuntime.execNode(
                ctx,
                listOf(MuseRuntime.npmCli(ctx).absolutePath, "-v"),
                timeoutMs = 120_000,
            )
            assertEquals("stderr=${r.stderr}", 0, r.exitCode)
            assertTrue("npm Excepción de salida de versión: ${r.stdout}", NODE_VERSION_SHAPE.containsMatchIn(r.stdout.trim()))
        }
    }

    @Test
    fun t8_symlinkExec() {
        runBlocking {
            // P1 Verificación previa:runtime/bin/node Los enlaces simbólicos pueden ser ejecutivos (npm/npx Conceptos básicos de resolución de nombres)
            val bin = MuseRuntime.binDir(ctx).apply { mkdirs() }
            val link = java.io.File(bin, "node")
            link.delete()
            android.system.Os.symlink(MuseRuntime.nodeBinary(ctx).absolutePath, link.absolutePath)
            val r = MuseRuntime.exec(
                ctx = ctx,
                cmd = listOf(link.absolutePath, "-v"),
                timeoutMs = 20_000,
            )
            assertEquals("stderr=${r.stderr}", 0, r.exitCode)
            assertTrue("Excepción de salida: ${r.stdout}", NODE_VERSION_SHAPE.containsMatchIn(r.stdout.trim()))
        }
    }

    @Test
    fun t10_shimNameResolutionAndShebang() {
        runBlocking {
            // P1-B Humo:sh Llamar a nodo/npm por nombre (mapeo de nombres)+ shebang El script se ejecuta directamente (npx .bin escena)
            MuseRuntime.ensureData(ctx).getOrThrow()

            val r1 = MuseRuntime.exec(ctx, listOf("/system/bin/sh", "-c", "node -v"), timeoutMs = 20_000)
            assertEquals("stderr=${r1.stderr}", 0, r1.exitCode)
            assertTrue("salida: ${r1.stdout}", NODE_VERSION_SHAPE.containsMatchIn(r1.stdout.trim()))

            val r2 = MuseRuntime.exec(ctx, listOf("/system/bin/sh", "-c", "npm -v"), timeoutMs = 60_000)
            assertEquals("stderr=${r2.stderr}", 0, r2.exitCode)
            assertTrue("salida: ${r2.stdout}", NODE_VERSION_SHAPE.containsMatchIn(r2.stdout.trim()))

            val script = java.io.File(MuseRuntime.binDir(ctx), "hello-tool")
            script.writeText("#!/usr/bin/env node\nconsole.log('shebang-ok', 6*7);\n")
            script.setExecutable(true, false)
            val r3 = MuseRuntime.exec(ctx, listOf("/system/bin/sh", "-c", script.absolutePath), timeoutMs = 20_000)
            assertEquals("stderr=${r3.stderr}", 0, r3.exitCode)
            assertTrue("salida: ${r3.stdout}", r3.stdout.contains("shebang-ok 42"))
        }
    }
}
