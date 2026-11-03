package io.zer0.muse.data

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.mockk.mockk
import io.zer0.muse.R
import io.zer0.muse.backup.CloudBackupConfig
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = SettingsRepositoryTestApplication::class)
class SettingsRepositoryTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun newRepository(): SettingsRepository = SettingsRepository(context, mockk(relaxed = true))

    @Test
    fun enterGuestMode_keepsOnboardingNickname() = runBlocking {
        val repo = newRepository()
        repo.saveUserProfile(UserProfile(userNickName = "Ziqi"))

        repo.enterGuestMode()

        val state = repo.accountStateFlow.first()
        assertTrue(state.isGuestMode)
        assertEquals("Ziqi", state.userName)
    }

    @Test
    fun enterGuestMode_withoutNicknameFallsBackToGuest() = runBlocking {
        val repo = newRepository()

        repo.enterGuestMode()

        val state = repo.accountStateFlow.first()
        assertTrue(state.isGuestMode)
        assertEquals(context.getString(R.string.settings_repo_guest_name), state.userName)
    }

    @Test
    fun detectTaskType_emptyTextFallsBackToChat() {
        assertEquals(SettingsRepository.TaskType.CHAT, newRepository().detectTaskType(""))
    }

    @Test
    fun detectTaskType_codePromptDoesNotBecomeCreative() {
        assertEquals(SettingsRepository.TaskType.CODE, newRepository().detectTaskType("Escribir código para implementar un algoritmo de clasificación."))
    }

    @Test
    fun detectTaskType_analysisPromptIsDetected() {
        assertEquals(SettingsRepository.TaskType.ANALYSIS, newRepository().detectTaskType("Analizar estos datos y proporcionar un informe."))
    }

    // ── P0-1: Bandera de contraseña de respaldo(backupPasswordSet)No se puede borrar mediante guardados y recálculos irrelevantes. ──

    @Test
    fun saveCloudBackupConfig_passwordBlankButFlagTrue_keepsFlag() = runBlocking {
        // Keystore Escenario de falla:La contraseña leída está vacía,Pero el usuario ha establecido una contraseña.(La bandera es true).
        // cualquier salvamento superfluo(Joo Chiat autoSync)no puede recalcular la bandera como false,De lo contrario la nube/La guardia de exportación local está deshabilitada.
        val repo = newRepository()
        repo.saveCloudBackupConfig(CloudBackupConfig(backupPassword = "", backupPasswordSet = true))

        val readBack = repo.cloudBackupConfigFlow.first()

        assertTrue("Una vez que caduque la contraseña, se deben conservar los campos irrelevantes. backupPasswordSet=true", readBack.backupPasswordSet)
    }

    @Test
    fun saveCloudBackupConfig_passwordProvided_setsFlag() = runBlocking {
        // Nueva contraseña del usuario → Ubicación de la bandera true(Sustituto de memoria cifrada,Robolectric Ninguno Android Keystore)
        val original = SecureKeyStore.delegate
        try {
            SecureKeyStore.delegate = object : SecureKeyCipher {
                override suspend fun encrypt(plain: String): String = "fake:$plain"
                override suspend fun decrypt(stored: String): String =
                    if (stored.startsWith("fake:")) stored.removePrefix("fake:") else stored
            }
            val repo = newRepository()
            repo.saveCloudBackupConfig(CloudBackupConfig(backupPassword = "secret123", backupPasswordSet = false))

            val readBack = repo.cloudBackupConfigFlow.first()

            assertTrue("Después de configurar la contraseña backupPasswordSet debe ser true", readBack.backupPasswordSet)
        } finally {
            SecureKeyStore.delegate = original
        }
    }

    @Test
    fun saveCloudBackupConfig_passwordBlankAndFlagFalse_staysFalse() = runBlocking {
        // El usuario borra explícitamente la contraseña(o nunca establecer)→ La bandera permanece false,Permitir exportaciones posteriores de texto claro
        val repo = newRepository()
        repo.saveCloudBackupConfig(CloudBackupConfig(backupPassword = "", backupPasswordSet = false))

        val readBack = repo.cloudBackupConfigFlow.first()

        assertFalse("Después de borrar explícitamente la contraseña backupPasswordSet debe ser false", readBack.backupPasswordSet)
    }

    // ── AUDIT §11.3-7: stringSet Se debe realizar una copia de seguridad de los elementos de configuración de tipo ──

    @Test
    fun stringSetFavoritesRoundTripThroughBackupSnapshot() = runBlocking {
        // Segundo caso de brecha similar: La instantánea solo atraviesa las cuatro categorías string/bool/int/long,stringSet Ninguno exportado,
        // La colección de momentos y la colección de álbumes de fotos se perderán cuando cambies de teléfono. Realiza todo el viaje de ida y vuelta aquí.:Exportar → Cambiar valor local → restaurar → Compara artículo por artículo.
        val repo = newRepository()
        repo.saveMomentFavoriteIds(setOf("moment-a", "moment-b"))
        repo.toggleMiniAlbumFavoriteImage("1024")
        repo.toggleMiniAlbumFavoriteImage("2048")

        val snapshot = repo.exportSettingsSnapshot()
        assertTrue(
            "Las colecciones en Moments deben ingresarse en instantáneas: ${snapshot.keys}",
            snapshot.containsKey("stringSet:moment_favorite_ids"),
        )
        assertTrue(
            "La colección de álbumes debe ingresar instantáneas.: ${snapshot.keys}",
            snapshot.containsKey("stringSet:mini_album_favorite_image_ids"),
        )

        // Simular el estado existente del dispositivo de destino(Nada que ver con el contenido de la copia de seguridad)
        repo.saveMomentFavoriteIds(emptySet())
        repo.toggleMiniAlbumFavoriteImage("999")

        repo.restoreSettingsSnapshot(snapshot)

        assertEquals(
            "El contenido de la colección debe ser consistente después de la restauración.",
            setOf("moment-a", "moment-b"),
            repo.momentFavoriteIdsFlow.first(),
        )
        assertEquals(
            "La recuperación de la colección de álbumes se sobrescribe en lugar de fusionarse:El 999 local original debe reemplazarse por el contenido de la instantánea.",
            setOf("1024", "2048"),
            repo.miniAlbumFavoriteImageIdsFlow.first(),
        )
    }

    @Test
    fun emptyFavoriteSetSurvivesRoundTripAsEmptySet() = runBlocking {
        // colección vacía"presencia explícita"con"no, esto key"debe poder distinguir:La primera es una colección vacía después de la recuperación.,
        // Este último debería dejar sin cambios el valor original del dispositivo de destino. Si se usa para codificar joinToString,Los dos se comprimirán en una cadena vacía.
        val repo = newRepository()
        repo.saveMomentFavoriteIds(emptySet())

        val snapshot = repo.exportSettingsSnapshot()
        assertEquals(
            "Esto también debería escribirse para el conjunto vacío. key(De lo contrario, será imposible borrar la colección al restaurar.)",
            "[]",
            snapshot["stringSet:moment_favorite_ids"],
        )
    }

    @Test
    fun oldBackupWithoutStringSetFieldsRestoresWithoutError() = runBlocking {
        // copia de seguridad antigua(Sin campo stringSet)Debe poder recuperarse normalmente.,Y no debe borrar las colecciones existentes en el dispositivo de destino.
        val repo = newRepository()
        repo.saveMomentFavoriteIds(setOf("keep-me"))

        val oldStyleSnapshot = mapOf("theme_id" to "mono")
        repo.restoreSettingsSnapshot(oldStyleSnapshot)

        assertEquals(
            "Si la clave no existe en la instantánea, no se debe tocar.(Misma semántica que las otras cuatro categorías.:Sobrescribe solo los contenidos en la instantánea. key)",
            setOf("keep-me"),
            repo.momentFavoriteIdsFlow.first(),
        )
    }

    @Test
    fun malformedStringSetValueIsSkippedInsteadOfWiped() = runBlocking {
        // Los datos incorrectos escriben una colección vacía que destruye silenciosamente la colección;El enfoque correcto es omitir esta clave.
        val repo = newRepository()
        repo.saveMomentFavoriteIds(setOf("keep-me"))

        repo.restoreSettingsSnapshot(mapOf("stringSet:moment_favorite_ids" to "not-a-json-array"))

        assertEquals(
            "Se deben omitir los errores de análisis en lugar de escribir el conjunto vacío",
            setOf("keep-me"),
            repo.momentFavoriteIdsFlow.first(),
        )
    }

    @Test
    fun nonWhitelistedStringSetIsIgnoredOnRestore() = runBlocking {
        // Es posible que los datos de recuperación hayan sido manipulados:Los StringSets que no están en la lista blanca no se deben escribir en(Mismo calibre que los cuatro tipos de escalares.).
        val repo = newRepository()
        repo.restoreSettingsSnapshot(mapOf("stringSet:daily_summary_claimed_slots" to "[\"2026-10-23#0900\"]"))

        assertFalse(
            "El estado de deduplicación programada no se debe restaurar con una copia de seguridad",
            repo.isDailySummarySlotClaimed("2026-10-23#0900"),
        )
    }
}

class SettingsRepositoryTestApplication : Application()
