package io.zer0.muse.data.plugin

/** Información de identidad relacionada con la versión del complemento que se instalará. */
data class PluginInstallCandidate(
    val id: String,
    val version: String,
    val publisherId: String,
    val publisherKeyFingerprint: String,
    /** Si está firmado por un editor válido (falso para borradores de cuarentena sin firmar). */
    val signed: Boolean,
    val minAppVersion: String,
)

/** Identidad relativa a la versión del complemento instalado; se utiliza para comparar la descendencia y las rebajas del editor. */
data class InstalledPluginIdentity(
    val version: String,
    val publisherId: String,
    val publisherKeyFingerprint: String,
    val signed: Boolean,
)

sealed class PluginVersionDecision {
    data object Allow : PluginVersionDecision()
    data class Reject(val reason: String) : PluginVersionDecision()
}

/**
 * Estrategia de versión para la instalación del complemento (lógica pura, conveniente para pruebas individuales).
 *
 * Bloquea tres tipos de riesgos reales:
 *  1. **Instalación de degradación**: Los paquetes que son anteriores a la versión instalada (posiblemente con vulnerabilidades conocidas) se instalan de forma silenciosa. Retroceder para realizar una entrada explícita,
 *     Esa ruta la inicia el usuario y se volverá a verificar, así que use [allowDowngrade] Liberar.
 *  2. **Cambio de editor**: El ID del complemento con el mismo nombre se sobrescribe con otro paquete de clave pública, lo que equivale a asumir la identidad y las habilidades del complemento instalado.
 *  3. **Sobrescrituras sin firmar firmadas**:borrador aislado(AI Los paquetes sin firmar generados o importados localmente) nunca pueden votar a favor de los usuarios.
 *     Se ha confirmado una versión firmada del fideicomiso.
 *
 * Además, manifiesto obligatorio. `minAppVersion`: Los complementos que requieran una versión superior a la versión actual de la aplicación serán rechazados directamente.
 * En lugar de instalar un complemento destinado a fallar.
 */
object PluginVersionPolicy {

    /** El número de versión del marcador de posición cuando versionName no se inyecta en la compilación local es coherente con el valor base de build.gradle.kts. */
    const val DEV_SENTINEL_VERSION = "0.0.0-dev"

    fun evaluate(
        candidate: PluginInstallCandidate,
        installed: InstalledPluginIdentity?,
        appVersion: String,
        allowDowngrade: Boolean = false,
        /**
         * La identificación del emisor de la clave de autor nativo.
         *
         * Las instalaciones firmadas por él podrán ser**nuevo borrador sin firmar**Anulación: esta es la única ruta para que el asistente repita los complementos escritos por él mismo.
         * Y los borradores todavía están deshabilitados.+En el estado no confirmado, deberá ser firmado nuevamente por el usuario para que surta efecto, por lo que no constituye una escalada de privilegios.
         * Las instalaciones firmadas por terceros todavía se niegan a ser sobrescritas por paquetes no firmados.
         */
        localAuthorPublisherId: String? = null,
    ): PluginVersionDecision {
        val candidateVersion = PluginVersion.parse(candidate.version)
            ?: return PluginVersionDecision.Reject("El número de versión del complemento no es una versión semántica: ${candidate.version}")
        val requiredApp = PluginVersion.parse(candidate.minAppVersion)
            ?: return PluginVersionDecision.Reject("minAppVersion No es una versión semántica: ${candidate.minAppVersion}")
        val currentApp = PluginVersion.parse(appVersion)
            ?: return PluginVersionDecision.Reject("No se puede resolver la versión actual de la aplicación: $appVersion")

        if (requiredApp > currentApp) {
            // El número de versión cuando se compila localmente sin -PversionName es un valor centinela, más pequeño que la versión requerida por cualquier complemento.
            // Ese tipo de paquete no es un paquete que deba enviarse en primer lugar, y quedarse atascado en el umbral de la versión sólo bloqueará la depuración;
            // El resto de la verificación (firma, permisos,schema) se ejecutará como de costumbre.
            if (appVersion == DEV_SENTINEL_VERSION) {
                io.zer0.common.Logger.w(
                    "PluginVersionPolicy",
                    "desarrollar construir ($DEV_SENTINEL_VERSION) omitir umbral minAppVersion: requisito de complemento $requiredApp",
                )
            } else {
                return PluginVersionDecision.Reject("El complemento requiere que la versión de la aplicación sea al menos $requiredApp, actualmente $currentApp")
            }
        }
        val existing = installed ?: return PluginVersionDecision.Allow

        if (existing.signed && !candidate.signed) {
            val authoredLocally = localAuthorPublisherId != null &&
                existing.publisherId.isNotEmpty() &&
                existing.publisherId == localAuthorPublisherId
            if (!authoredLocally) {
                return PluginVersionDecision.Reject("Se instala una versión firmada y no se puede sobrescribir con un paquete sin firmar.")
            }
        }
        if (existing.signed && candidate.signed) {
            val samePublisher = existing.publisherId.isNotEmpty() &&
                existing.publisherId == candidate.publisherId &&
                existing.publisherKeyFingerprint.isNotEmpty() &&
                existing.publisherKeyFingerprint.equals(
                    candidate.publisherKeyFingerprint,
                    ignoreCase = true,
                )
            if (!samePublisher) {
                return PluginVersionDecision.Reject("El editor del complemento no coincide con la versión instalada y se rechaza la sobrescritura de la instalación.")
            }
        }

        val installedVersion = PluginVersion.parse(existing.version)
            ?: return PluginVersionDecision.Allow // Si el número de versión del registro antiguo no está estandarizado, no impedirá la actualización a la versión estandarizada.
        if (candidateVersion < installedVersion && !allowDowngrade) {
            return PluginVersionDecision.Reject(
                "nueva versión $candidateVersion inferior al instalado $installedVersion, la instalación de degradación ha sido rechazada",
            )
        }
        return PluginVersionDecision.Allow
    }
}
