package io.zer0.muse.terminal

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Fase terminal II:autocompilado PTY(libpty.so)Verificación del dispositivo.
 *
 * Verdadero viaje de ida y vuelta:Crear una pseudo terminal → Escribir comandos a través de pty → leer eco/salida → Obtenga el código de salida.
 * Anular los límites de JNI:Crear proceso/bloquear lectura y escritura/esperar salida/tamaño de ventana/cerrar fd.
 */
@RunWith(AndroidJUnit4::class)
class PtyInstrumentedTest {

    @Test
    fun ptyEchoAndExitCodeRoundTrip() {
        assertTrue("libpty.so debería cargarse exitosamente(Pty.available),loadError=${Pty.loadError}", Pty.available)
        val cwd = InstrumentationRegistry.getInstrumentation().targetContext.filesDir.absolutePath
        val pidArray = IntArray(1)
        val fd = Pty.createSubprocess("/system/bin/sh", cwd, pidArray, "", "")
        assertTrue("forkpty debería volver válido fd,real $fd", fd >= 0)
        assertTrue("El pid del proceso hijo debe ser válido.,real ${pidArray[0]}", pidArray[0] > 0)

        val output = StringBuilder()
        val reader = Thread {
            val buffer = ByteArray(4096)
            while (true) {
                val n = Pty.read(fd, buffer, 0, buffer.size)
                if (n <= 0) break
                synchronized(output) { output.append(String(buffer, 0, n, Charsets.UTF_8)) }
            }
        }
        reader.start()

        Thread.sleep(800) // Espere a que el sh interactivo esté listo
        val command = "echo MUSE_PTY_READY\nexit 7\n"
        val bytes = command.toByteArray(Charsets.UTF_8)
        val written = Pty.write(fd, bytes, 0, bytes.size)
        if (written != bytes.size) {
            val alive = runCatching {
                android.os.Process.sendSignal(pidArray[0], 0)
                true
            }.getOrDefault(false)
            val soFar = synchronized(output) { output.toString() }
            throw AssertionError(
                "Error de escritura: written=$written errno=${Pty.errnoValue()} childAlive=$alive " +
                    "available=${Pty.available(fd)} soFar=[$soFar]",
            )
        }

        reader.join(8000)
        Pty.closeFd(fd)

        val text = synchronized(output) { output.toString() }
        assertTrue("Debe contener eco de comando: $text", text.contains("echo MUSE_PTY_READY"))
        assertTrue("debe contener la salida del comando: $text", text.contains("MUSE_PTY_READY"))

        val exitCode = Pty.waitFor(pidArray[0])
        assertEquals("El código de salida debe ser 7(Decodificado por waitpid)", 7, exitCode)
    }

    @Test
    fun windowSizeSyncDoesNotCrash() {
        if (!Pty.available) return
        val cwd = InstrumentationRegistry.getInstrumentation().targetContext.filesDir.absolutePath
        val pidArray = IntArray(1)
        val fd = Pty.createSubprocess("/system/bin/sh", cwd, pidArray, "", "")
        assertTrue(fd >= 0)
        Pty.setWindowSize(fd, 40, 120)
        Pty.setWindowSize(fd, 0, 0) // Los valores ilegales deben ignorarse en lugar de fallar
        Pty.closeFd(fd)
    }
}
