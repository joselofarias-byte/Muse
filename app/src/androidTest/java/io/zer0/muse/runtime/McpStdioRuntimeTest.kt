package io.zer0.muse.runtime

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.zer0.muse.mcp.McpClient
import io.zer0.muse.mcp.McpConnectionState
import io.zer0.muse.mcp.McpServerConfig
import io.zer0.muse.mcp.McpTransportType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.BufferedReader
import java.io.File

/**
 * P0 Acabado:MCP stdio server Validación de extremo a extremo (prueba conectada al simulador).
 *
 * Enlace:npm install @modelcontextprotocol/server-filesystem → Comience con el nodo incorporado
 * server(stdio)→ JSON-RPC initialize apretón de manos → tools/list.
 *
 * Objetivo de verificación: el servidor MCP basado en npm disponible en el mercado puede ejecutarse completamente en el entorno limitado de Muse
 * (P1 stdio Preaceptación de la implementación de la transmisión).
 */
@RunWith(AndroidJUnit4::class)
class McpStdioRuntimeTest {

    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun npmInstallAndMcpHandshake() {
        runBlocking {
            MuseRuntime.ensureData(ctx).getOrThrow()

            val work = File(MuseRuntime.runtimeDir(ctx), "mcp-e2e").apply {
                deleteRecursively()
                mkdirs()
            }
            File(work, "package.json").writeText("""{"name":"muse-mcp-e2e","private":true}""")

            // 1) npm install(Descarga en línea, espere suficiente tiempo de espera)
            val install = MuseRuntime.execNode(
                ctx = ctx,
                args = listOf(
                    MuseRuntime.npmCli(ctx).absolutePath,
                    "install",
                    "--no-audit",
                    "--no-fund",
                    "--loglevel=error",
                    "@modelcontextprotocol/server-filesystem",
                ),
                timeoutMs = 300_000,
                workdir = work,
            )
            val failMsg = "npm install falló(stderr): ${install.stderr.take(800)}"
            assertEquals(failMsg, 0, install.exitCode)

            // 2) Localizar la entrada del servidor
            val pkgDir = File(work, "node_modules/@modelcontextprotocol/server-filesystem")
            val entry = listOf("dist/index.js", "build/index.js", "index.js")
                .map { File(pkgDir, it) }
                .firstOrNull { it.isFile }
            assertTrue("server Entrada no encontrada (pkgDir=$pkgDir)", entry != null)

            // 3) Inicie el servidor y complete el protocolo de enlace MCP
            val serverErr = StringBuilder()
            val proc = ProcessBuilder(
                MuseRuntime.nodeBinary(ctx).absolutePath,
                entry!!.absolutePath,
                work.absolutePath,
            )
                .directory(work)
                .apply {
                    environment().clear()
                    environment().putAll(MuseRuntime.buildEnv(ctx))
                }
                .start()
            Thread {
                runCatching {
                    proc.errorStream.bufferedReader().useLines { lines ->
                        lines.forEach { line ->
                            synchronized(serverErr) {
                                if (serverErr.length < 8000) serverErr.appendLine(line)
                            }
                        }
                    }
                }
            }.start()

            try {
                val out = proc.outputStream
                val reader = proc.inputStream.bufferedReader()

                val initReq = """{"jsonrpc":"2.0","id":1,"method":"initialize","params":""" +
                    """{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"muse-e2e","version":"0.1"}}}"""
                out.write((initReq + "\n").toByteArray())
                out.flush()
                val initResp = readUntil(reader, "\"id\":1", 60_000)
                assertTrue("initialize Sin respuesta/tiempo de espera (stderr=${serverErr.take(400)})", initResp != null)
                assertTrue("initialize respuesta anormal: ${initResp!!.take(300)}", initResp.contains("\"result\""))

                out.write("""{"jsonrpc":"2.0","method":"notifications/initialized"}""".plus("\n").toByteArray())
                out.write("""{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}""".plus("\n").toByteArray())
                out.flush()
                val toolsResp = readUntil(reader, "\"id\":2", 30_000)
                assertTrue("tools/list Sin respuesta/tiempo de espera (stderr=${serverErr.take(400)})", toolsResp != null)
                assertTrue("tools/list respuesta anormal: ${toolsResp!!.take(300)}", toolsResp.contains("\"tools\""))
            } finally {
                proc.destroyForcibly()
            }
        }
    }

    @Test
    fun mcpClientStdioEndToEnd() {
        runBlocking {
            // P1-A De punta a punta:McpClient(STDIO) → empezar npx server → initialize → listTools
            MuseRuntime.ensureData(ctx).getOrThrow()

            val config = McpServerConfig(
                id = "e2e-stdio",
                name = "e2e-filesystem",
                transportType = McpTransportType.STDIO,
                command = "npx -y @modelcontextprotocol/server-filesystem ${MuseRuntime.runtimeDir(ctx).absolutePath}",
                autoReconnect = false,
                requestTimeoutMs = 180_000,
            )
            val client = McpClient(config, appContext = ctx)
            try {
                client.start()
                val state = withTimeout(240_000) {
                    client.state.first {
                        it == McpConnectionState.CONNECTED || it == McpConnectionState.FAILED
                    }
                }
                assertEquals("Estado de conexión anormal", McpConnectionState.CONNECTED, state)
                val tools = client.listToolsOrNull()
                assertTrue("Espera una lista de herramientas que no esté vacía,real: ${tools?.size}", tools != null && tools.isNotEmpty())
            } finally {
                client.close()
            }
        }
    }

    /** Lea stdout línea por línea hasta que aparezca algo que contenga [needle] fila; el tiempo de espera/EOF devuelve nulo. */
    private suspend fun readUntil(reader: BufferedReader, needle: String, timeoutMs: Long): String? = withContext(Dispatchers.IO) {
        try {
            withTimeout(timeoutMs) {
                async {
                    var found: String? = null
                    for (i in 0 until 50) {
                        val line = reader.readLine() ?: break
                        if (line.contains(needle)) {
                            found = line
                            break
                        }
                    }
                    found
                }.await()
            }
        } catch (e: Exception) {
            null
        }
    }
}
