package io.zer0.muse.data.plugin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pruebas de estrategia de versión: degradación, linaje del editor, cobertura sin firmar,minAppVersion. */
class PluginVersionPolicyTest {

    private val appVersion = "1.0.89"

    private fun candidate(
        version: String = "1.1.0",
        publisherId: String = "museai",
        fingerprint: String = "a".repeat(64),
        signed: Boolean = true,
        minAppVersion: String = "1.0.0",
    ) = PluginInstallCandidate(
        id = "math-toolkit",
        version = version,
        publisherId = publisherId,
        publisherKeyFingerprint = fingerprint,
        signed = signed,
        minAppVersion = minAppVersion,
    )

    private fun installed(
        version: String = "1.0.0",
        publisherId: String = "museai",
        fingerprint: String = "a".repeat(64),
        signed: Boolean = true,
    ) = InstalledPluginIdentity(
        version = version,
        publisherId = publisherId,
        publisherKeyFingerprint = fingerprint,
        signed = signed,
    )

    private fun rejected(decision: PluginVersionDecision): String {
        assertTrue("Espere rechazo, en realidad libere", decision is PluginVersionDecision.Reject)
        return (decision as PluginVersionDecision.Reject).reason
    }

    @Test
    fun freshInstallAndSameVersionReinstallAreAllowed() {
        assertEquals(PluginVersionDecision.Allow, PluginVersionPolicy.evaluate(candidate(), null, appVersion))
        assertEquals(
            PluginVersionDecision.Allow,
            PluginVersionPolicy.evaluate(candidate(version = "1.0.0"), installed(), appVersion),
        )
    }

    @Test
    fun upgradeWithSamePublisherIsAllowed() {
        assertEquals(
            PluginVersionDecision.Allow,
            PluginVersionPolicy.evaluate(candidate(version = "1.1.0"), installed("1.0.0"), appVersion),
        )
    }

    @Test
    fun downgradeIsRejectedUnlessExplicitlyAllowed() {
        val decision = PluginVersionPolicy.evaluate(candidate(version = "0.9.0"), installed("1.0.0"), appVersion)
        assertTrue(rejected(decision).contains("Rebajar"))

        // La entrada de reversión se libera explícitamente.
        assertEquals(
            PluginVersionDecision.Allow,
            PluginVersionPolicy.evaluate(
                candidate(version = "0.9.0"),
                installed("1.0.0"),
                appVersion,
                allowDowngrade = true,
            ),
        )
    }

    @Test
    fun publisherChangeIsRejected() {
        val otherKey = PluginVersionPolicy.evaluate(
            candidate(fingerprint = "b".repeat(64)),
            installed(),
            appVersion,
        )
        assertTrue(rejected(otherKey).contains("Emisor"))

        val otherId = PluginVersionPolicy.evaluate(
            candidate(publisherId = "someone-else"),
            installed(),
            appVersion,
        )
        assertTrue(rejected(otherId).contains("Emisor"))
    }

    @Test
    fun unsignedPackageCannotOverwriteSignedInstall() {
        val decision = PluginVersionPolicy.evaluate(
            candidate(version = "2.0.0", signed = false),
            installed(),
            appVersion,
        )
        assertTrue(rejected(decision).contains("sin firmar"))
    }

    @Test
    fun signedPackageMayReplaceUnsignedDraft() {
        assertEquals(
            PluginVersionDecision.Allow,
            PluginVersionPolicy.evaluate(
                candidate(version = "1.0.0"),
                installed(publisherId = "", fingerprint = "", signed = false),
                appVersion,
            ),
        )
    }

    @Test
    fun localAuthorInstallMayBeReplacedByNewDraftForIteration() {
        // El asistente itera sobre complementos escritos por él mismo: las instalaciones firmadas por el autor nativo pueden ser sobrescritas por nuevos borradores (el usuario aún debe volver a firmar los borradores).
        assertEquals(
            PluginVersionDecision.Allow,
            PluginVersionPolicy.evaluate(
                candidate(version = "1.1.0", publisherId = "", fingerprint = "", signed = false),
                installed(version = "1.0.0", publisherId = "local-author", signed = true),
                appVersion,
                localAuthorPublisherId = "local-author",
            ),
        )
        // Sin embargo, se sigue rechazando cuando no se aprueba la autoría local (cerrado por defecto para evitar relajaciones accidentales).
        assertTrue(
            rejected(
                PluginVersionPolicy.evaluate(
                    candidate(version = "1.1.0", signed = false),
                    installed(version = "1.0.0", publisherId = "local-author", signed = true),
                    appVersion,
                ),
            ).contains("sin firmar"),
        )
        // Las instalaciones firmadas por terceros aún no se pueden sobrescribir con borradores.
        assertTrue(
            rejected(
                PluginVersionPolicy.evaluate(
                    candidate(version = "1.1.0", signed = false),
                    installed(version = "1.0.0", publisherId = "museai", signed = true),
                    appVersion,
                    localAuthorPublisherId = "local-author",
                ),
            ).contains("sin firmar"),
        )
    }

    @Test
    fun minAppVersionAboveCurrentAppIsRejected() {
        val decision = PluginVersionPolicy.evaluate(
            candidate(minAppVersion = "1.2.0"),
            null,
            appVersion,
        )
        assertTrue(rejected(decision).contains("App versión"))
    }

    @Test
    fun malformedVersionsAreRejectedInsteadOfGuessed() {
        assertTrue(
            rejected(PluginVersionPolicy.evaluate(candidate(version = "1.0"), null, appVersion))
                .contains("versión semántica"),
        )
        assertTrue(
            rejected(PluginVersionPolicy.evaluate(candidate(minAppVersion = "latest"), null, appVersion))
                .contains("minAppVersion"),
        )
    }

    @Test
    fun prereleaseUpgradeOverStableIsRejectedAsDowngrade() {
        // 1.0.0-rc.1 < 1.0.0: Una versión preliminar que cubra la versión oficial también es una degradación.
        val decision = PluginVersionPolicy.evaluate(
            candidate(version = "1.0.0-rc.1"),
            installed("1.0.0"),
            appVersion,
        )
        assertTrue(rejected(decision).contains("Rebajar"))
    }

    @Test
    fun devBuildSkipsMinAppVersionButKeepsOtherChecks() {
        // El número de versión del marcador de posición creado localmente es menor que cualquier requisito de complemento, y quedarse atascado en el umbral solo bloqueará la depuración;
        // Sin embargo, verificaciones como la degradación y la firma siguen siendo efectivas.
        val allowed = PluginVersionPolicy.evaluate(
            candidate(minAppVersion = "9.9.9"),
            null,
            PluginVersionPolicy.DEV_SENTINEL_VERSION,
        )
        assertEquals(PluginVersionDecision.Allow, allowed)

        val downgrade = PluginVersionPolicy.evaluate(
            candidate(version = "0.9.0", minAppVersion = "9.9.9"),
            installed("1.0.0"),
            PluginVersionPolicy.DEV_SENTINEL_VERSION,
        )
        assertTrue(rejected(downgrade).contains("Rebajar"))
    }
}
