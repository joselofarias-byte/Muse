package io.zer0.muse.web

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

/**
 * Host Mode WebSocket wire protocol.
 *
 * The browser is a presentation client. It sends intent commands and receives
 * snapshots/events produced by the Android runtime; it never supplies a
 * Provider key or a second copy of the chat business logic.
 */
const val CURRENT_PROTOCOL_VERSION: Int = 1

@Serializable
data class HostCommand(
    val protocolVersion: Int = CURRENT_PROTOCOL_VERSION,
    val type: String,
    val requestId: String? = null,
    val sessionId: String? = null,
    val text: String? = null,
    val title: String? = null,
    val archived: Boolean? = null,
    val messageId: String? = null,
    val generationId: String? = null,
    /**
     * Cursor de paginación.
     *
     * Ambos calibres comparten el mismo campo.:`state.sync` Pasar el número de secuencia del evento(numeros),`memory.list` con
     * `knowledge.documents` Sube el último elemento de la página anterior. id(cuerda). Para evitar que los dos calibres interactúen entre sí
     * Nombre del campo de multitud,Recibir por mensaje de texto,Reconoce números y cadenas.,Las ramas que requieren valores numéricos las transfiere usted mismo.
     */
    @Serializable(with = HostCursorValueSerializer::class)
    val cursor: String? = null,
    val sinceEventSeq: Long? = null,
    /** F-38: history.page Parámetros de paginación(offset=¿Cuántos elementos recientes omitir?,limit=Número de artículos por página). */
    val offset: Int? = null,
    /** Número de artículos por página:`history.page` con `memory.list` / `memory.search` compartido. */
    val limit: Int? = null,
    /** F-38: history.search palabras clave;`memory.search` También se utiliza para transportar cadenas de consulta. */
    val query: String? = null,
    /** message.delete Una lista de ID de mensajes de destino (se pueden eliminar varios mensajes a la vez). */
    val messageIds: List<String> = emptyList(),
    /** message.favorite estado objetivo (true = colección,false = Cancelar favoritos). */
    val favored: Boolean? = null,
    /**
     * chat.send El objetivo de referencia a llevar.
     *
     * con `message.quote` Relación: este campo se "envía con referencia" y el Anfitrión adjuntará la relación de referencia al nuevo mensaje;
     * `message.quote` Significa "lea primero el texto citado" y el contenido de la respuesta no se perderá. Ambos pueden existir al mismo tiempo,
     * Si elige traer este campo al enviar en la página web, no hay necesidad de un viaje de ida y vuelta adicional.
     */
    val quotedMessageId: String? = null,
    /** F-37: tool.approval.resolve answered Cuando los parámetros de la herramienta completados por el usuario anulan el mapeo(clave → valor). */
    val argOverrides: Map<String, String> = emptyMap(),
    /** settings.update clave de establecimiento de objetivos,Debe ingresar a la lista blanca de servidores. */
    val key: String? = null,
    /**
     * identificación del asistente.
     *
     * Tres usos:`session.new` Especificar en qué asistente se cuelga la nueva sesión(De lo contrario, utilice el asistente predeterminado en la configuración.);
     * `assistants.detail` Especificar el asistente para leer(Requerido);`memory.list` / `memory.search` Especificar
     * alcance de la memoria(No transmitido = El grupo de memoria principal del asistente principal.).
     */
    val assistantId: String? = null,
    /** settings.update valor objetivo,Analizar por tipo declarado en la lista blanca(Booleano solo acepta "true"/"false"). */
    val value: String? = null,
    /** `knowledge.documents` base de conocimientos objetivo id(Requerido). */
    val kbId: String? = null,
)

/**
 * poner `cursor` dos calibres de(Número de secuencia numérica/cadena id)en el mismo campo.
 *
 * Sólo se puede resolver con una cuerda.:`state.sync` Lo que se envió fue un número.,`memory.list` / `knowledge.documents` Lo que se envía es el texto de identificación.,
 * Si se declara como un número, no podrá aceptar documentos que no sean números, como los documentos de la base de conocimientos. id,Si se declara como una cadena, los números no se pueden leer. Ambos se aceptan al leer aquí.,
 * Unificar en cadena al escribir.
 */
internal object HostCursorValueSerializer : KSerializer<String?> {
    private val delegate: KSerializer<String?> = String.serializer().nullable

    override val descriptor: SerialDescriptor = delegate.descriptor

    override fun serialize(encoder: Encoder, value: String?): Unit = delegate.serialize(encoder, value)

    override fun deserialize(decoder: Decoder): String? {
        val jsonDecoder = decoder as? JsonDecoder ?: return delegate.deserialize(decoder)
        return when (val element = jsonDecoder.decodeJsonElement()) {
            is JsonNull -> null
            is JsonPrimitive -> element.content
            else -> null
        }
    }
}

@Serializable
data class HostEvent(
    val protocolVersion: Int = CURRENT_PROTOCOL_VERSION,
    val type: String,
    val requestId: String? = null,
    val connectionId: String? = null,
    val eventSeq: Long = 0L,
    val cursor: Long = 0L,
    val sessionId: String? = null,
    val turnId: String? = null,
    val generationId: String? = null,
    val messageId: String? = null,
    val role: String? = null,
    val title: String? = null,
    val content: String? = null,
    val reasoning: String? = null,
    val delta: String? = null,
    val error: String? = null,
    val isStreaming: Boolean? = null,
    val sessions: List<HostSession> = emptyList(),
    val messages: List<HostMessage> = emptyList(),
    val pendingApprovals: List<HostApproval> = emptyList(),
    val capabilityFlags: List<String> = emptyList(),
    /** message.delete Recibo: la cantidad de elementos realmente eliminados. */
    val deletedCount: Int? = null,
    /** message.delete Recibo de respuesta: Porque no existe/ID que se omiten porque no pertenecen a la sesión actual. */
    val skippedMessageIds: List<String> = emptyList(),
    /** settings.snapshot carga. */
    val settings: HostSettingsSnapshot? = null,
    /** assistants.list carga. */
    val assistants: List<HostAssistant> = emptyList(),
    /** providers.list carga. */
    val providers: List<HostProvider> = emptyList(),
    /** providers.list La identificación del modelo seleccionado actualmente. */
    val selectedModelId: String? = null,
    /** sessions.detail cargar;Si la sesión no existe, estará vacía con error. */
    val sessionDetail: HostSessionDetail? = null,
    /** assistants.detail cargar;Si el asistente no existe estará vacío con un error. */
    val assistantDetail: HostAssistantDetail? = null,
    /** memory.list cargar(El texto ha sido truncado y recortado a la página.). */
    val memory: List<HostMemoryEntry> = emptyList(),
    /** memory.search cargar(entrada + fragmento de golpe). */
    val memoryHits: List<HostMemoryHit> = emptyList(),
    /** memory.list ¿Hay una página siguiente después de esa? */
    val hasMore: Boolean? = null,
    /** knowledge.list cargar:Base de conocimientos de un vistazo. */
    val knowledgeBases: List<HostKnowledgeBase> = emptyList(),
    /** knowledge.documents El ID de la base de conocimientos de destino que se repetirá. */
    val kbId: String? = null,
    /** knowledge.documents cargar:Una página de documentos bajo una determinada base de conocimientos. */
    val documents: List<HostKnowledgeDocument> = emptyList(),
    /** tasks.list cargar:Lista de tareas programadas. */
    val tasks: List<HostScheduledTask> = emptyList(),
    /** system.status cargar:Instantáneas de estado de solo lectura de aplicaciones y dispositivos. */
    val systemStatus: HostSystemStatus? = null,
)

/**
 * Instantáneas del estado de la aplicación y del dispositivo(Fuente de datos de solo lectura para páginas de datos y privacidad/páginas específicas de plataforma).
 *
 * Cada grupo es el resultado de "la siguiente medición",Sin almacenamiento en caché de solicitudes cruzadas:El volumen y la potencia cambian inherentemente con el tiempo.,
 * El almacenamiento en caché solo hará que la página web muestre un número que no está claro en qué momento.
 */
@Serializable
data class HostSystemStatus(
    /** nombre de la versión(Como por ejemplo `2.6.18`). */
    val appVersion: String = "",
    /** número de versión(incremento entero,Los canales de publicación se utilizan para determinar quién actualiza). */
    val appVersionCode: Long = 0,
    /** El tiempo que este proceso ha estado ejecutándose.(milisegundos). */
    val uptimeMs: Long = 0,
    val storage: HostStorageBytes = HostStorageBytes(),
    val backup: HostBackupStatus = HostBackupStatus(),
    val capabilities: HostDeviceCapabilities = HostDeviceCapabilities(),
    /** poder;Cuando no se puede obtener, se deshace todo el bloque. null(No `level=0`). */
    val battery: HostBatteryStatus? = null,
)

/**
 * El número de bytes ocupados por cada directorio.
 *
 * Calibre:Los cinco directorios se suman de forma recursiva.,[totalAppBytes] es la suma de estos cinco elementos —— No es la capacidad de todo el sandbox.,
 * Subdirectorios no listados(Como registros, volcados de memoria)No contado,ver [HostStorageUsage] descripción.
 */
@Serializable
data class HostStorageBytes(
    /** Directorio de base de datos(`databases/`,Contiene `-wal` / `-shm`). */
    val databaseBytes: Long = 0,
    /** Directorio de imágenes de mensajes(`files/muse_images`). */
    val mediaBytes: Long = 0,
    /** Directorio de archivos de voz(`files/voice_messages`). */
    val voiceBytes: Long = 0,
    /** directorio del espacio de trabajo(`files/workspace`). */
    val workspaceBytes: Long = 0,
    /** directorio de caché(`cache/`). */
    val cacheBytes: Long = 0,
    /** La suma de los cinco elementos anteriores. */
    val totalAppBytes: Long = 0,
)

/**
 * Estado de la copia de seguridad.
 *
 * Ambos "si encender" toman el calibre del interruptor propio de la aplicación.:[autoBackupEnabled] Es el control maestro de programación en segundo plano.
 * (La copia de seguridad diaria local cuelga debajo),[cloudBackupEnabled] Requiere configurar la copia de seguridad en la nube y activar la sincronización automática ——
 * La copia de seguridad en la nube no se ejecutará por sí sola cuando solo se configure sin sincronización automática.,Mostrar "habilitado" es engañoso.
 */
@Serializable
data class HostBackupStatus(
    /** La marca de tiempo de la copia de seguridad exitosa más reciente;Nunca respaldado es nulo. */
    val lastBackupAt: Long? = null,
    /** El tamaño del paquete de la última copia de seguridad exitosa.;Nunca respaldado es nulo. */
    val lastBackupBytes: Long? = null,
    val autoBackupEnabled: Boolean = false,
    val cloudBackupEnabled: Boolean = false,
)

/**
 * Seis capacidades de plataforma.
 *
 * Todo desde el mismo estado de permiso automatizado dentro de la aplicación.(ver [io.zer0.muse.automation.core.AutomationManager]),
 * No realizar detección adicional en la puerta de enlace;Siempre que no se pueda obtener el estatus false,Por tanto, los seis campos son siempre booleanos y no nulos.
 */
@Serializable
data class HostDeviceCapabilities(
    /** Si los servicios de accesibilidad están conectados. */
    val accessibility: Boolean = false,
    /** Shell canal(La implementación actual está sujeta a la licencia Shizuku.). */
    val shell: Boolean = false,
    /** Root canal. */
    val root: Boolean = false,
    /** Shizuku ¿Está LISTO? */
    val shizuku: Boolean = false,
    /** ¿Está disponible la pantalla virtual? —— La puerta de permiso tiene el mismo origen que Shell/Root,Cualquiera de los dos está disponible para crear una pantalla. */
    val virtualDisplay: Boolean = false,
    /** Notificar si se han otorgado derechos de uso(O el servicio de escucha está conectado.). */
    val notificationListener: Boolean = false,
)

/** poder;Cuando no se pueden recuperar ambos campos, todo el bloque es nulo. */
@Serializable
data class HostBatteryStatus(
    /** Porcentaje de batería restante 0–100;Si no se lee, es nulo. */
    val level: Int? = null,
    /** esta cargando;Es nulo cuando el sistema no proporciona un estado explícito. */
    val charging: Boolean? = null,
)

/**
 * Descripción general de una base de conocimientos.
 *
 * [documentCount] / [chunkCount] es el valor agregado en tiempo real,no leer `knowledge_bases.doc_count` esa columna ——
 * Esta columna solo se escribe una vez durante la migración.,importar/Eliminar la ruta no seguirá.
 */
@Serializable
data class HostKnowledgeBase(
    val id: String,
    val name: String,
    /** La cantidad de documentos visibles para los usuarios en esta biblioteca.(No incluye documentación de desarrollo interno.). */
    val documentCount: Int = 0,
    /** El número real de bloques en la biblioteca.(Tamaño del índice). */
    val chunkCount: Int = 0,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
    /** El nombre del backend incorporado actualmente efectivo;Es nulo cuando este proceso no ha analizado el backend. */
    val embeddingBackend: String? = null,
)

/**
 * Un documento en la base de conocimientos.
 *
 * El texto no se envía.,solo dar [sizeBytes]:La página de lista solo necesita "qué tan grande es, en cuántas partes está dividida y para qué paso se solicita".
 */
@Serializable
data class HostKnowledgeDocument(
    val id: String,
    val title: String,
    /** Número de fragmentos escritos en el índice.;0 Significa que aún no ha sido indexado. */
    val chunkCount: Int = 0,
    /** Número de bytes UTF-8 para el cuerpo. */
    val sizeBytes: Long = 0,
    val createdAt: Long = 0,
    /** estado del índice,ver el valor [HostDocumentStatus]. */
    val status: String = HostDocumentStatus.PENDING,
)

/** [HostKnowledgeDocument.status] valor. */
object HostDocumentStatus {
    /** indexado,Se puede recuperar y recuperar. */
    const val INDEXED = "indexed"

    /** Aún no indexado(La importación no se completa o el número de bloques es 0). */
    const val PENDING = "pending"

    /** El índice ha sido creado.,Sin embargo, la reconstrucción fracasó a mitad de camino y los viejos bloques han sido despejados.,Necesita ser reindexado. */
    const val INDEX_BROKEN = "index_broken"
}

/**
 * Una tarea programada.
 *
 * [cron] es la expresión original almacenada en la biblioteca,Sin análisis ni normalización.(interval Escriba la tarea es una cadena vacía);
 * [lastResult] Es el estado final de la ejecución más reciente.(`success` / `failed` / `skipped` espera),Nunca ejecutado es nulo.
 */
@Serializable
data class HostScheduledTask(
    val id: String,
    val name: String,
    val cron: String = "",
    val enabled: Boolean = true,
    /** Próxima hora de ejecución;Nulo si no está habilitado o no tiene próxima ejecución. */
    val nextRunAt: Long? = null,
    /** Última hora de ejecución;Nunca ejecutado es nulo. */
    val lastRunAt: Long? = null,
    val lastResult: String? = null,
)

@Serializable
data class HostSettingsSnapshot(
    val groups: List<HostSettingsGroup> = emptyList(),
    /** Valores actuales de interruptores comunes.,Misma clave [HostSettingToggle.key]. */
    val toggles: List<HostSettingToggle> = emptyList(),
    val display: HostSettingsDisplay = HostSettingsDisplay(),
    /** settings.update Claves de lista blanca aceptadas;Las claves que no estén en esta columna serán rechazadas. */
    val writableKeys: List<String> = emptyList(),
    /**
     * Valor estructural utilizado para formularios de subpágina.
     *
     * Cada elemento tiene su propio tipo, valor actual y candidato de enumeración (incluido el nombre para mostrar localizado), y la página web puede representarlos en consecuencia.
     * No es necesario mantener tablas de tipos ni copias en chino usted mismo. La lista blanca de escritura tiene el mismo origen que aquí: el conjunto de elementos grabables es
     * [writableKeys], elementos de solo lectura (como el nombre del timbre actual, si el modelo ha sido configurado) con writable=false.
     */
    val values: List<HostSettingValue> = emptyList(),
)

/**
 * Establece el valor actual del elemento.
 *
 * [type] tomar `boolean` / `number` / `enum` / `text`;[value] Transportado uniformemente por una cuerda: escritura booleana
 * `true`/`false`, el número se escribe como texto decimal, la enumeración se escribe como el valor en el candidato y el texto es como está. De esta manera, la página web
 * Al enviar, solo necesita devolver la misma cadena y no necesita preocuparse por varios tipos de serialización.
 */
@Serializable
data class HostSettingValue(
    val key: String,
    val type: String,
    val value: String,
    /** Candidatos de enumeración (incluidos nombres para mostrar localizados); no enumerado está vacío. */
    val options: List<HostSettingOption> = emptyList(),
    /** si se puede escribir;false El artículo es solo para visualización; se rechazará escribir en él. */
    val writable: Boolean = true,
    /** number límite inferior/límite superior/Paso; el no número es nulo. */
    val min: Double? = null,
    val max: Double? = null,
    val step: Double? = null,
)

/** Candidatos de enumeración: valor estable + nombre para mostrar traducido al idioma de la aplicación. */
@Serializable
data class HostSettingOption(
    val value: String,
    val label: String = "",
)

@Serializable
data class HostSettingsGroup(
    /** Nombre del recurso de cadena del título del grupo(identificación estable,No se muestra directamente). */
    val key: String,
    /** Título del grupo localizado por idioma de la aplicación. */
    val groupTitle: String = "",
    val entries: List<HostSettingsEntry> = emptyList(),
)

@Serializable
data class HostSettingsEntry(
    val id: String,
    /** El nombre del recurso de cadena del título.(identificación estable:Se utiliza para navegación y afirmaciones de prueba.,No se muestra directamente). */
    val titleKey: String,
    /** El nombre del recurso de cadena de la descripción.;Describe las entradas detalladas por el tiempo de ejecución.(como el estado de proxy)está vacío. */
    val descKey: String? = null,
    /** Título localizado por idioma de la aplicación. */
    val title: String = "",
    /** Instrucciones traducidas por idioma de la aplicación;Las entradas sin descripción están vacías. */
    val desc: String? = null,
    val groupKey: String,
    val iconKey: String,
    /** link / switch / action. */
    val kind: String,
    /** ¿La entrada está disponible en el lado de la aplicación?(La puerta cerrada durante la compilación es false). */
    val enabled: Boolean = true,
    /** kind=switch tecla de configuración correspondiente,Se puede utilizar directamente con settings.update. */
    val toggleKey: String? = null,
    /** kind=switch valor actual en;Las entradas que no son de conmutación están vacías. */
    val checked: Boolean? = null,
)

@Serializable
data class HostSettingToggle(
    val key: String,
    val enabled: Boolean,
)

@Serializable
data class HostSettingsDisplay(
    val language: String = "",
    val themeMode: String = "",
    val fontSizeScale: String = "",
    val themeId: String = "",
)

@Serializable
data class HostAssistant(
    val id: String,
    val name: String,
    val avatarUri: String? = null,
    val isDefault: Boolean = false,
)

/**
 * Detalles visualizables para un solo asistente.
 *
 * Trae solo lo que quieras dibujar en la página web.:Parámetros del modelo, resumen de caracteres, longitud de la palabra del mensaje y número de herramientas.
 * No se distribuirá el texto completo de la palabra clave. —— La página web solo necesita mostrar "N palabras configuradas",La palabra clave es para el asistente."código fuente",
 * Cuando se difunde en el navegador, no hay un recibo grande ni un espacio de visualización.
 */
@Serializable
data class HostAssistantDetail(
    val id: String,
    val name: String,
    val avatarUri: String? = null,
    /** Introducción de una frase(El subtítulo de la tarjeta de asistente.). */
    val description: String = "",
    val modelId: String? = null,
    val providerId: String? = null,
    val temperature: Float? = null,
    val topP: Float? = null,
    val maxTokens: Int? = null,
    /** Resumen del personaje(Truncado por límite superior);Si no hay una configuración separada del carácter, será una cadena vacía. */
    val personaSummary: String = "",
    /** [personaSummary] ¿Está truncado?,Para que la página web decida si mostrarla o no"Para ver el perfil completo por favor vaya a App". */
    val personaTruncated: Boolean = false,
    /** Recuento de palabras personalizado para palabras rápidas(Número de caracteres),El texto principal no será distribuido. */
    val systemPromptLength: Int = 0,
    /** El número de herramientas comprobadas por este asistente. */
    val toolCount: Int = 0,
    /** El límite superior del número de mensajes inyectados en el contexto. */
    val contextMessageSize: Int = 20,
    /** Piense en equipo de profundidad(nombre de enumeración,Como por ejemplo AUTO / OFF). */
    val reasoningLevel: String = "AUTO",
    val enabled: Boolean = true,
)

/**
 * Una entrada de memoria.
 *
 * [content] truncado:Dibuja solo dos o tres líneas en la página de lista,Entregar el párrafo completo agrandaría una página de 30 recibos en varios cientos de KB.
 * Úselo si necesita ver la posición del golpe. `memory.search` de [HostMemoryHit.snippet].
 */
@Serializable
data class HostMemoryEntry(
    /** entrada id(cadena decimal,con `memory.list` El cursor tiene el mismo conjunto de números.). */
    val id: String,
    val content: String,
    /** Fecha de inventario ISO 8601. */
    val createdAt: String,
    /** Categoría de fuente,ver el valor [HostMemorySourceKind]. */
    val source: String = HostMemorySourceKind.AUTO,
    /** Si el usuario lo fija en la parte superior. */
    val pinned: Boolean = false,
)

/** `memory.search` un golpe:[entry] con [memory.list] Misma estructura,[snippet] es el fragmento cerca del lugar del impacto. */
@Serializable
data class HostMemoryHit(
    val entry: HostMemoryEntry,
    val snippet: String,
)

/** [HostMemoryEntry.source] valor. */
object HostMemorySourceKind {
    /** Elementos extraídos de conversaciones.(sesión con fuente). */
    const val CONVERSATION = "conversation"

    /** Entradas agregadas manualmente o expresamente indicadas por el usuario. */
    const val MANUAL = "manual"

    /** El sistema organiza automáticamente/Entradas inferidas. */
    const val AUTO = "auto"
}

@Serializable
data class HostProvider(
    val id: String,
    val name: String,
    val enabled: Boolean,
    val models: List<HostProviderModel> = emptyList(),
)

@Serializable
data class HostProviderModel(
    val id: String,
    val name: String,
)

@Serializable
data class HostSessionDetail(
    val id: String,
    val title: String,
    val assistantId: String,
    val archived: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
    val messageCount: Int,
)

@Serializable
data class HostApproval(
    val toolCallId: String,
    val toolName: String,
    val argumentsPreview: String,
)

@Serializable
data class HostSession(
    val id: String,
    val title: String,
    val assistantId: String,
    val updatedAt: Long,
    val archived: Boolean,
    val pinned: Boolean,
)

@Serializable
data class HostMessage(
    val id: String,
    val role: String,
    val content: String,
    val reasoning: String? = null,
    val modelId: String? = null,
    val createdAt: Long,
    /** Dirección de imagen accesible: `/api/media/<token>` ruta relativa,o originalmente `http(s)` dirección original. */
    val imageUrls: List<String> = emptyList(),
    val toolCallId: String? = null,
    val toolName: String? = null,
    val citationUrls: List<String> = emptyList(),
    /** Dirección accesible para mensajes de voz.,con [imageUrls] misma regla;Los mensajes que no son de voz están vacíos. */
    val voiceUrl: String? = null,
    /** Duración de la voz(milisegundos).null = Mensaje sin voz o de duración desconocida(Datos antiguos, error de detección). */
    val voiceDurationMs: Long? = null,
    /**
     * La llamada a la herramienta transportada por este mensaje.
     *
     * Un mensaje de ayuda puede realizar varias llamadas a la vez,entonces es una matriz;Los mensajes que no contienen llamadas a herramientas son matrices vacías.
     * [toolName] Mantener la semántica original(Valor único, solo se proporciona el nombre de la herramienta),El nuevo campo no lo reemplaza.
     */
    val toolCalls: List<HostToolCall> = emptyList(),
    /** estado del nivel de mensaje,El valor es [HostMessageStatus] Una de las cuatro constantes en . */
    val status: String = HostMessageStatus.SENT,
    /** Copia del motivo del error,solo [status] para [HostMessageStatus.FAILED] El tiempo no está vacío. */
    val errorText: String? = null,
    /**
     * Cite el texto citado en la respuesta.
     *
     * La relación de referencia tomada del propio mensaje (solo posible en los mensajes de los usuarios) es el mismo contenido que la tarjeta de referencia en la burbuja de mensajes de la aplicación;
     * Está vacío cuando no hay referencia. El texto principal también termina con `> ` La forma del prefijo permanece en [content] Li(App formato de persistencia).
     */
    val quotedContent: String? = null,
)

/**
 * Información completa para una llamada de herramienta.
 *
 * App Estos campos se utilizan para representar tarjetas plegadas en tarjetas de herramientas finales.,La página web puede mostrar las tarjetas tal como están sin tener que implementar lógica empresarial.
 * Los parámetros y resultados solo se publican para vista previa.(ver [argumentsPreview]),Los resultados completos no están incluidos en la instantánea. ——
 * El resultado de una herramienta de lectura de archivos puede ser decenas de KB,La instantánea se reenvía cada vez que recibe un incremento de transmisión.
 */
@Serializable
data class HostToolCall(
    /** Al escribir los resultados de la herramienta id(corresponder `tool_call_id`);Si no hay una identificación de proveedor, la generará el servidor. */
    val callId: String,
    val name: String,
    /** Vista previa truncada del parámetro JSON. */
    val argumentsPreview: String,
    /** Vista previa truncada de los resultados de la ejecución;null = No se ha ejecutado o no se ha publicado el resultado.(Pendiente de aprobación, lista de solicitudes solo llamadas). */
    val resultPreview: String? = null,
    /** Si la ejecución fue exitosa;null = desconocido(Llamadas que solo solicitan lista,El resultado no está en este mensaje.). */
    val success: Boolean? = null,
    /** tiempo de ejecución local(milisegundos),Mismo calibre que ToolCallInfo.durationMs de la aplicación;null = No contado. */
    val durationMs: Long? = null,
)

/** [HostMessage.status] valor. */
object HostMessageStatus {
    /** Escrito en la lista de mensajes local,Esperando que se emita la cola. */
    const val SENDING = "sending"

    /** Las respuestas del asistente se están transmitiendo. */
    const val STREAMING = "streaming"

    /** Terminado. */
    const val SENT = "sent"

    /** Esta ronda de generación termina con el fracaso.,[HostMessage.errorText] Con una razón. */
    const val FAILED = "failed"
}
