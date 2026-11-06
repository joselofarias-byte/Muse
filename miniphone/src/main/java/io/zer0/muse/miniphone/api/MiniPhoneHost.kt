package io.zer0.muse.miniphone.api

import io.zer0.muse.miniphone.data.db.MpActorType
import kotlinx.coroutines.flow.Flow

/**
 * Cómo aparece el personaje en un pequeño teléfono celular.
 *
 * Con solo los cuatro campos requeridos para la interfaz;Los datos completos del personaje permanecen en el lado anfitrión.,Este módulo no se sostiene.
 */
data class MpCharacter(
    val id: String,
    val name: String,
    val avatarUri: String? = null,
    val tagline: String? = null,
)

/**
 * La información del usuario local en el pequeño teléfono celular.
 *
 * Permitir valores predeterminados excepto apodos(Sin conjunto de avatar/firma/La interfaz se cuida sola al empapelar).
 */
data class MpProfile(
    val nickname: String,
    val avatarUri: String? = null,
    val signature: String? = null,
    val wallpaperUri: String? = null,
    val coverImageUri: String? = null,
)

/**
 * Qué está haciendo el personaje.
 *
 * El host solo entrega esta enumeración al ejecutar la herramienta.:El nombre de la herramienta, los parámetros y los valores de retorno no salen del host.,
 * El módulo muestra una frase de copia antropomórfica en consecuencia.,No sé ni me importa lo que sucede detrás de escena.
 */
enum class MpActivityKind {
    /** buscando algo afuera(Buscar y explorar información). */
    SEARCHING,

    /** Recordar cosas relacionadas con los usuarios. */
    REMEMBERING,

    /** Consultando horarios y recordatorios. */
    CALENDAR,

    /** Mirando el clima. */
    WEATHER,

    /** Revisando notas. */
    NOTES,

    /** El resto es indescriptible. */
    OTHER,
}

/** Eventos enviados a módulos durante un ciclo de construcción. */
sealed interface MpReplyEvent {
    /** El personaje está haciendo algo en este momento.;La interfaz muestra la redacción antropomórfica correspondiente.,Se retraerá automáticamente después de que el texto toque el suelo. */
    data class Activity(val kind: MpActivityKind) : MpReplyEvent

    /** El texto final pronunciado por el personaje. */
    data class Text(val content: String) : MpReplyEvent

    /**
     * Un mensaje de voz enviado por el personaje.
     *
     * @param uri Referencia del archivo de voz en el lado del host(archivo de audio local),La interfaz se reproduce en consecuencia.
     * @param durationMs duración(milisegundos);0 Indica que el host no puede detectar,La interfaz se muestra como desconocida.
     */
    data class Voice(val uri: String, val durationMs: Long) : MpReplyEvent

    /** No hubo resultado en esta ronda. */
    data object Failed : MpReplyEvent
}

/** Una ronda de palabras que ya se han dicho en la conversación.;[sender] Solo distingue quien lo dice,El contenido es el texto tal como se colocó en la biblioteca. */
data class MpChatTurn(
    val sender: MpActorType,
    val content: String,
)

/**
 * La única entrada y salida entre el pequeño teléfono celular y el anfitrión.
 *
 * Este módulo no depende de la aplicación host.,Utilice siempre esta interfaz cuando necesite roles, capacidades de generación y capacidades de voz.,Implementado e inyectado por el host.
 *
 * Acuerdo:Los métodos que fallan no generan excepciones a este módulo. Error al generar el método de clase [MpReplyEvent.Failed] Termina normalmente después,
 * Otros métodos fallan y regresan. `null`,La persona que llama puede simplemente procesarlo como "Sin resultado".
 */
interface MiniPhoneHost {
    /** Lista de caracteres en la libreta de direcciones. Cuando no hay datos en el lado del host, se devuelve una lista vacía en lugar de fallar. */
    fun observeCharacters(): Flow<List<MpCharacter>>

    /** Información del usuario local. Si el lado del host no se ha configurado, se devuelven datos vacíos.(El apodo es una cadena vacía.). */
    fun observeProfile(): Flow<MpProfile>

    /**
     * deja que un personaje [history] Luego pasa a la siguiente ronda.
     *
     * flujo frío:Cada colección ejecuta una ronda completa de generación. ¿Cuáles son las variedades normales? [MpReplyEvent.Activity]
     * seguido de uno o más [MpReplyEvent.Text] / [MpReplyEvent.Voice];Cuando no hay resultado, solo hay uno. [MpReplyEvent.Failed].
     * no importa cuál sea la situación,La transmisión terminará normalmente.,No se lanza ninguna excepción.
     *
     * Se permite enviar una ronda de respuesta en varios párrafos.:El anfitrión exporta el modelo a `[Voz]……[/Voz]` Los párrafos de la marca se sintetizan en sonidos fonéticos en el orden de aparición.
     * [MpReplyEvent.Voice],Enviar los párrafos restantes [MpReplyEvent.Text];La etiqueta en sí no contiene ningún texto.,Sin párrafos chinos
     * No sintetizado, enviado como texto. La secuencia de eventos es consistente con el resultado del modelo.,Se pueden reproducir un máximo de 5 voces en una sola ronda.
     */
    fun respond(characterId: String, history: List<MpChatTurn>): Flow<MpReplyEvent>

    /** Pídele a un personaje que publique una actualización.;Devuelve nulo en caso de error. */
    suspend fun composeMoment(characterId: String, hint: String?): String?

    /**
     * Generar un mensaje privado iniciado por el personaje.;Devuelve nulo si no se puede generar.
     *
     * con [composeMoment] La diferencia radica en la semántica más que en los enlaces.:Las actualizaciones son "actualizaciones recientes enviadas a todos para que las vean".,
     * Un mensaje privado es "una frase dicha directamente al usuario".—— Este último requiere la primera persona, una o dos frases, sin narración ni explicación.
     */
    suspend fun composeProactiveMessage(characterId: String, hint: String?): String?

    /**
     * Sintetizar texto en voz.
     *
     * @return `first` para archivos de audio uri,`second` por duración(milisegundos);Devuelve nulo en caso de error.
     */
    suspend fun synthesizeVoice(text: String, emotion: String?): Pair<String, Long>?

    /**
     * Convierte un WAV grabado por el usuario en texto.
     *
     * Los formatos de grabación, codificación y carga los determina el anfitrión. —— El módulo sólo transfiere bytes.,No sé qué servicio se utiliza para la identificación.
     * No implementarlo significa no admitir la entrada de voz.:El anfitrión debe dar explícitamente la conclusión.,No deje ningún valor predeterminado.
     *
     * @return Eliminar el texto en blanco inicial y final;Se devuelve si no se reconoce, no se configura o la solicitud falla. null
     */
    suspend fun transcribeVoice(wavBytes: ByteArray): String?

    /**
     * El nombre de la versión de la aplicación host.(para exhibición,por ejemplo `2.6.12`).
     *
     * Solo se utiliza para información de versión de la página "Yo". Cadena vacía predeterminada:Host sin versión reportada(Incluye sustituto para pruebas.)No mostrar esta sección,
     * La interfaz no completará un número de versión falso.
     */
    fun appVersionName(): String = ""

    /**
     * ¿La configuración ASR actual admite la conversión de todo el segmento de audio en texto?(La entrada de grabación depende de ello).
     *
     * La conclusión debe ser consistente con [transcribeVoice] ¿Será realmente consistente?:Devuelve verdadero después de que el usuario presiona el micrófono,
     * Una vez grabado, debería ser una solicitud real.,en lugar de un llamado que está condenado al fracaso. La interfaz atenuará el micrófono en consecuencia.
     */
    suspend fun supportsVoiceTranscription(): Boolean
}
