package io.zer0.muse.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * R-TEST-03: SecureKeyStore Compatible con lógica de transmisión transparente;Android Keystore Se requiere una máquina real para cifrar y descifrar (Robolectric Sin AndroidKeyStore).
 *
 * Phase 3 (Fiabilidad P1): anexar [SecureKeyCipher.decryptOrNull] Pruebas semánticas de fallas explícitas —
 * El error de descifrado regresa null(El motivo está documentado por la implementación.),Entrada compatible [SecureKeyStore.decrypt] Mantener la semántica de cadenas vacías.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SecureKeyStoreTest {

    @Test
    fun `plain text passes through decrypt`() = runBlocking {
        assertEquals("legacy-plain", SecureKeyStore.decrypt("legacy-plain"))
    }

    @Test
    fun `empty value is not encrypted`() = runBlocking {
        assertEquals("", SecureKeyStore.encrypt(""))
        assertEquals("", SecureKeyStore.decrypt(""))
    }

    private class FakeCipher : SecureKeyCipher {
        override suspend fun encrypt(plain: String): String = if (plain.isEmpty()) plain else "fake:${plain.reversed()}"
        override suspend fun decrypt(stored: String): String =
            if (stored.startsWith("fake:")) stored.removePrefix("fake:").reversed() else stored

        /** Phase 3 (P1): "fake:broken" Clave de suplantación no válida/Corrupción de datos → Nulo explícito. */
        override suspend fun decryptOrNull(stored: String): String? = if (stored == "fake:broken") null else decrypt(stored)
    }

    @Test
    fun `delegate can be swapped for jvm tests`() = runBlocking {
        val original = SecureKeyStore.delegate
        try {
            SecureKeyStore.delegate = FakeCipher()
            assertEquals("hello", SecureKeyStore.decrypt(SecureKeyStore.encrypt("hello")))
        } finally {
            SecureKeyStore.delegate = original
        }
    }

    @Test
    fun `decryptOrNull returns null on failure instead of empty string`() = runBlocking {
        val original = SecureKeyStore.delegate
        try {
            SecureKeyStore.delegate = FakeCipher()
            assertNull("El error de descifrado debe devolverse explícitamente null", SecureKeyStore.decryptOrNull("fake:broken"))
            // Las entradas de compatibilidad mantienen la semántica antigua(cadena vacía),Los guardias de llamadas existentes permanecen sin cambios
            assertEquals("", SecureKeyStore.decrypt("fake:broken"))
        } finally {
            SecureKeyStore.delegate = original
        }
    }

    @Test
    fun `decryptOrNull passes through empty and legacy plaintext`() = runBlocking {
        val original = SecureKeyStore.delegate
        try {
            SecureKeyStore.delegate = FakeCipher()
            assertEquals("", SecureKeyStore.decryptOrNull(""))
            assertEquals("legacy-plain", SecureKeyStore.decryptOrNull("legacy-plain"))
            assertEquals("hello", SecureKeyStore.decryptOrNull(SecureKeyStore.encrypt("hello")))
        } finally {
            SecureKeyStore.delegate = original
        }
    }
}
