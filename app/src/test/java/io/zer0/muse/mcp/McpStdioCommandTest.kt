package io.zer0.muse.mcp

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * P1-A: stdio Prueba de lógica pura del analizador de comandos.
 *
 * Cobertura: participio (comillas)/ node, npm, npx Mapeo de tiempo de ejecución incorporado/comandos ilegales y rechazo de rutas fuera de límites.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class McpStdioCommandTest {

    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Before
    fun setUp() {
        // Robolectric No preestablezca NativeLibraryDir (el sistema proporciona la máquina real), agregue una ruta estable para la afirmación de mapeo de ruta.
        context.applicationInfo.nativeLibraryDir = "/tmp/muse-libs"
    }

    @Test
    fun tokenizeHandlesQuotesAndSpaces() {
        assertEquals(
            listOf("npx", "-y", "some package", "arg"),
            McpStdioCommand.tokenize("npx -y \"some package\" arg"),
        )
        assertEquals(emptyList<String>(), McpStdioCommand.tokenize("   "))
    }

    @Test
    fun resolveNpxMapsToBuiltInRuntime() {
        val r = McpStdioCommand.resolve("npx -y @modelcontextprotocol/server-filesystem /sdcard", context)
        assertTrue("debería ser Ok: $r", r is McpStdioCommand.Result.Ok)
        val argv = (r as McpStdioCommand.Result.Ok).argv
        assertTrue("argv[0] Debería estar incorporado node: ${argv[0]}", argv[0].endsWith("libmuse_node.so"))
        assertTrue("argv[1] debería ser npx-cli.js: ${argv[1]}", argv[1].endsWith("npx-cli.js"))
        assertEquals(
            listOf("-y", "@modelcontextprotocol/server-filesystem", "/sdcard"),
            argv.drop(2),
        )
    }

    @Test
    fun resolveNpmMapsToCli() {
        val r = McpStdioCommand.resolve("npm install foo", context)
        assertTrue(r is McpStdioCommand.Result.Ok)
        val argv = (r as McpStdioCommand.Result.Ok).argv
        assertTrue("argv[0] Debería estar incorporado node: ${argv[0]}", argv[0].endsWith("libmuse_node.so"))
        assertTrue("argv[1] debería ser npm-cli.js: ${argv[1]}", argv[1].endsWith("npm-cli.js"))
    }

    @Test
    fun resolveNodeKeepsScriptArg() {
        val r = McpStdioCommand.resolve("node /tmp/x.js --flag", context)
        assertTrue(r is McpStdioCommand.Result.Ok)
        val argv = (r as McpStdioCommand.Result.Ok).argv
        assertEquals(3, argv.size)
        assertEquals("/tmp/x.js", argv[1])
        assertEquals("--flag", argv[2])
    }

    @Test
    fun rejectUnsupportedCommand() {
        val r = McpStdioCommand.resolve("rm -rf /", context)
        assertTrue("debe ser rechazado: $r", r is McpStdioCommand.Result.Error)
    }

    @Test
    fun rejectEmptyCommand() {
        val r = McpStdioCommand.resolve("   ", context)
        assertTrue(r is McpStdioCommand.Result.Error)
    }

    @Test
    fun rejectAbsolutePathOutsideRuntime() {
        val r = McpStdioCommand.resolve("/system/bin/sh -c echo", context)
        assertTrue("Las rutas del sistema deben rechazarse: $r", r is McpStdioCommand.Result.Error)
    }
}
