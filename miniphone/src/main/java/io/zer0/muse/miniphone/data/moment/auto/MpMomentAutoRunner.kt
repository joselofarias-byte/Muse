package io.zer0.muse.miniphone.data.moment.auto

import io.zer0.common.Logger
import io.zer0.common.resultOf
import io.zer0.muse.miniphone.api.MiniPhoneHost
import io.zer0.muse.miniphone.api.MpCharacter
import io.zer0.muse.miniphone.data.moment.MpCharacterMaterial
import io.zer0.muse.miniphone.data.moment.MpMomentsRepository
import io.zer0.muse.miniphone.data.settings.MpMomentAutoStore
import kotlinx.coroutines.flow.first
import java.time.ZoneId
import kotlin.random.Random

/** Una ronda de resultados generados en segundo plano.:Fue exitoso, se omitió o falló. Sentencias que se pueden ver en el registro,También se puede afirmar aquí. */
sealed interface MpMomentAutoOutcome {
    /** El depósito fue exitoso. */
    data class Published(val momentId: String, val characterId: String) : MpMomentAutoOutcome

    /** Juzgado como no enviado,ver la razón [reason]. */
    data class Skipped(val reason: MpMomentSkipReason) : MpMomentAutoOutcome

    /** Decidido a ser enviado,Pero la generación o biblioteca falló.;[reason] ¿Es el valor escrito en el registro? */
    data class Failed(val reason: String) : MpMomentAutoOutcome
}

/**
 * Un proceso completo de "dejar que el personaje publique una actualización".
 *
 * secuencia con [MpMomentAutoPlan.decide] consistente:Pase cuatro pases primero y sáltese el juicio.,Una vez completado el juicio, se selecciona el personaje, se ajusta la generación del anfitrión y el anfitrión se coloca en el inventario.
 * falló**no lo vuelvas a intentar** —— WorkManager Naturalmente volverá en el próximo ciclo.,Volver a intentarlo solo convertirá el consumo de una cuota en tres en una ronda.
 *
 * La persona que llama entrega la hora, la zona horaria y la fuente aleatoria.(El valor predeterminado es el valor del sistema),Entonces este proceso se puede ejecutar completamente en la prueba.:
 * "Debe enviarse o no" se deja al juez de función pura,"¿Qué hay más en la biblioteca después de enviarlo?" Utilice Room real para afirmar.
 *
 * Material generado por [material] luchar:¿De qué has hablado recientemente, cuál fue tu última publicación, qué hora es ahora?,Ninguno de ellos se busca en esta categoría.
 *
 * @param now fuente de tiempo,Reloj del sistema predeterminado
 * @param random fuente aleatoria:Dados de una oportunidad + qué personaje elegir,La prueba se puede clavar
 * @param zone La zona horaria utilizada para determinar "qué hora es ahora"
 */
class MpMomentAutoRunner(
    private val host: MiniPhoneHost,
    private val repository: MpMomentsRepository,
    private val store: MpMomentAutoStore,
    private val material: MpCharacterMaterial,
    private val now: () -> Long = System::currentTimeMillis,
    private val random: Random = Random.Default,
    private val zone: ZoneId = ZoneId.systemDefault(),
) {

    /** Corre una ronda. Puede fallar pero**No lanzar excepción**:Todas las excepciones están normalizadas. [MpMomentAutoOutcome]. */
    suspend fun run(): MpMomentAutoOutcome {
        val at = now()
        val enabled = resultOf { store.isEnabled() }
            .onError { msg, t -> fail("settings_unreadable", t, msg) }
            .getOrNull()
            ?: return MpMomentAutoOutcome.Failed(FAIL_SETTINGS)
        val lastGeneratedAt = resultOf { store.lastGeneratedAt() }
            .onError { msg, t -> fail("settings_unreadable", t, msg) }
            .getOrNull()
        val characters = resultOf { host.observeCharacters().first() }
            .onError { msg, t -> fail("characters_unreadable", t, msg) }
            .getOrNull()
            .orEmpty()
        val decision = MpMomentAutoPlan.decide(
            nowMillis = at,
            lastGeneratedAtMillis = lastGeneratedAt,
            roll = random.nextDouble(),
            hasCharacter = characters.isNotEmpty(),
            enabled = enabled,
            zone = zone,
        )
        return when (decision) {
            is MpMomentAutoDecision.Skip -> skip(decision.reason)
            MpMomentAutoDecision.Generate -> publish(characters, at)
        }
    }

    /** Juzgado como no enviado:Deje un registro que muestre el motivo.,Entonces se acabó. */
    private fun skip(reason: MpMomentSkipReason): MpMomentAutoOutcome {
        Logger.d(TAG, "Saltar tiempo en Momentos | reason=${reason.value}")
        return MpMomentAutoOutcome.Skipped(reason)
    }

    /** Elige un papel → Reunir materiales → Deja que el anfitrión genere → Cayó en la biblioteca → Tome nota de la hora esta vez. Si algún paso falla, deténgase. */
    private suspend fun publish(characters: List<MpCharacter>, at: Long): MpMomentAutoOutcome {
        val character: MpCharacter = characters.random(random)
        val focus = MpMomentAutoPlan.hintFor(MpMomentAutoPlan.hourOf(at, zone))
        val hint = material.forMoment(character.id, focus)
        Logger.d(TAG, "Los momentos se generan regularmente. | characterId=${character.id} | focus=$focus")
        val content = resultOf { host.composeMoment(character.id, hint) }
            .onError { msg, t -> fail("compose_error", t, msg) }
            .getOrNull()
        if (content.isNullOrBlank()) {
            // El anfitrión se ha tragado el fallo como nulo.;Simplemente haga una nota más aquí y diga "Esta ronda no tiene resultados" y luego deténgase.,no lo vuelvas a intentar
            Logger.w(TAG, "Falló el tiempo de los momentos | reason=compose_null")
            return MpMomentAutoOutcome.Failed(FAIL_COMPOSE)
        }
        val moment = resultOf { repository.insertFromCharacter(character, content) }
            .onError { msg, t -> fail("insert_error", t, msg) }
            .getOrNull()
        if (moment == null) {
            Logger.w(TAG, "Falló el tiempo de los momentos | reason=empty_content")
            return MpMomentAutoOutcome.Failed(FAIL_INSERT)
        }
        // La falla en la escritura de la marca de tiempo no afectará las dinámicas que se han incluido en la biblioteca.:Como máximo, se necesitará una protección antivibración menos para la siguiente ronda.
        resultOf { store.markGenerated(at) }
            .onError { msg, t -> fail("settings_write", t, msg) }
        Logger.d(TAG, "Los momentos se registran regularmente | momentId=${moment.id} | len=${moment.content.length}")
        return MpMomentAutoOutcome.Published(momentId = moment.id, characterId = character.id)
    }

    private fun fail(reason: String, throwable: Throwable?, message: String) {
        Logger.w(TAG, "Falló el tiempo de los momentos | reason=$reason | ${throwable?.message ?: message}", throwable)
    }

    private companion object {
        const val TAG: String = "MiniPhoneMoment"

        const val FAIL_SETTINGS: String = "settings_unreadable"

        const val FAIL_COMPOSE: String = "compose_null"

        const val FAIL_INSERT: String = "insert_skipped"
    }
}
