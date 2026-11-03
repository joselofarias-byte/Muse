package io.zer0.muse.mcp

import android.content.Context
import io.zer0.muse.runtime.MuseRuntime
import java.io.File

/**
 * P1-A: stdio MCP server Analizador de comandos.
 *
 * Coloque la línea de comando configurada por el usuario (como `npx -y @modelcontextprotocol/server-filesystem /sdcard`)
 * Convierta a un argv directamente ejecutable:
 *  - `node` / `npm` / `npx` → Tiempo de ejecución integrado de la aplicación ([MuseRuntime])
 *  - Ruta absoluta (solo directorios privados de aplicaciones:nativeLibraryDir / directorio de ejecución)→ Transmisión transparente tal como está.
 *  - Todos los demás comandos serán rechazados (solo los programas basados en nodos alojados en el tiempo de ejecución integrado pueden ejecutarse en el entorno de pruebas)
 */
object McpStdioCommand {

    sealed class Result {
        /** se puede pasar directamente al argv de ProcessBuilder (argv[0] es un camino absoluto). */
        data class Ok(val argv: List<String>) : Result()

        /** Error de análisis (el comando está vacío/no es compatible/ruta fuera de límites). */
        data class Error(val message: String) : Result()
    }

    /** La segmentación de palabras en espacios en blanco admite comillas dobles (conservando los espacios dentro de las comillas). */
    fun tokenize(commandLine: String): List<String> {
        val tokens = mutableListOf<String>()
        val sb = StringBuilder()
        var inQuote = false
        for (ch in commandLine.trim()) {
            when {
                ch == '"' -> inQuote = !inQuote
                ch.isWhitespace() && !inQuote -> {
                    if (sb.isNotEmpty()) {
                        tokens.add(sb.toString())
                        sb.clear()
                    }
                }
                else -> sb.append(ch)
            }
        }
        if (sb.isNotEmpty()) tokens.add(sb.toString())
        return tokens
    }

    /** Analizar la línea de comando → ejecutable argv. */
    fun resolve(commandLine: String, context: Context): Result {
        val tokens = tokenize(commandLine)
        if (tokens.isEmpty()) return Result.Error("el comando está vacío")
        val head = tokens[0]
        val rest = tokens.drop(1)
        val node = MuseRuntime.nodeBinary(context).absolutePath
        return when {
            head == "node" -> Result.Ok(listOf(node) + rest)
            head == "npm" -> Result.Ok(
                listOf(node, MuseRuntime.npmCli(context).absolutePath) + rest,
            )
            head == "npx" -> Result.Ok(
                listOf(node, File(MuseRuntime.npmDir(context), "bin/npx-cli.js").absolutePath) + rest,
            )
            head.startsWith("/") -> {
                // Ruta absoluta: solo se permiten programas en el directorio privado de la aplicación (para evitar la ejecución no autorizada de sistemas arbitrarios)/archivos de usuario)
                val f = File(head)
                val allowed = runCatching {
                    f.canonicalPath.startsWith(MuseRuntime.nativeLibDir(context).canonicalPath) ||
                        f.canonicalPath.startsWith(MuseRuntime.runtimeDir(context).canonicalPath)
                }.getOrDefault(false)
                if (allowed && f.isFile) {
                    Result.Ok(tokens)
                } else {
                    Result.Error("Permitir solo rutas de programas dentro del tiempo de ejecución de la aplicación")
                }
            }
            else -> Result.Error(
                "Comando no compatible '$head'(Actualmente admite node / npm / npx o una ruta absoluta dentro del tiempo de ejecución de la aplicación)",
            )
        }
    }
}
