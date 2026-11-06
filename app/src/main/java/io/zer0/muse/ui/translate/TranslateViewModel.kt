package io.zer0.muse.ui.translate

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.zer0.ai.ChatService
import io.zer0.ai.core.ChatCompletion
import io.zer0.ai.core.ChatStreamEvent
import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.ProviderError
import io.zer0.ai.core.ProviderException
import io.zer0.ai.core.UIMessage
import io.zer0.ai.core.providerError
import io.zer0.common.Logger
import io.zer0.muse.R
import io.zer0.muse.ui.speech.TtsManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * v1.97 gap8: Página de traducción independiente ViewModel.
 *
 * Reutilizar [ChatService] capacidades universales de finalización de texto,No depende de sesiones/Persistencia de mensajes.
 * estrategias de traducción y [io.zer0.muse.ui.ChatViewModel.translateMessage] consistente:
 *  1. Prioridad [ChatService.completeText](Desechable,rapido)
 *  2. Si el proveedor no implementa completeText o se produce un error,Rebajar [ChatService.streamChat](transmisión,Actualizar traducciones en tiempo real)
 *
 * v1.0.30 gap4.3 ~ gap4.9 conjunto de mejoras:
 *  - gap4.3 Favoritos:Se pueden agregar estrellas a elementos históricos.,DAO proporcionar observeFavorites/setFavorite
 *  - gap4.4 Traducción por lotes:Traduce varios fragmentos de texto con una llamada de LLM(JSON retorno de matriz)
 *  - gap4.5 Estilo personalizado:SharedPreferences Estilo de usuario persistente,Mostrar junto al estilo predeterminado
 *  - gap4.6 Glosario:[GlossaryStore] Mantener el texto original.→mapeo de traducción,añadido al traducir prompt
 *  - gap4.8 Almacenamiento en caché sin conexión:LruCache(50) caché de traducciones recientes,Los hits regresan directamente para evitar llamadas de LLM
 */
class TranslateViewModel(
    private val chatService: ChatService,
    private val ttsManager: TtsManager,
    private val appContext: Context,
    private val translateHistoryDao: TranslateHistoryDao,
    private val glossaryStore: GlossaryStore,
) : ViewModel() {

    /** UI estado. */
    data class State(
        /** El texto original ingresado por el usuario. */
        val inputText: String = "",
        /** Traducción traducida. */
        val translatedText: String = "",
        /** Si se está traduciendo. */
        val translating: Boolean = false,
        /** idioma de origen(nombre chino),"Detección automática"La representación es juzgada por el modelo. */
        val sourceLanguage: String = SOURCE_LANGUAGES.first(),
        /** idioma de destino(nombre chino). */
        val targetLanguage: String = TARGET_LANGUAGES.first(),
        /** v1.0.30: estilo de traducción(Universales/academico/Negocios/coloquial/Polaco + Personalización del usuario). */
        val translationStyle: String = TRANSLATION_STYLES.first(),
        /** mensaje de error(null No indica ningún error). */
        val errorMessage: String? = null,
        /** v1.97: Historial de traducción(Últimos N artículos,v1.0.17 persistir en Room). */
        val history: List<TranslateHistoryItem> = emptyList(),
        /** v1.0.30 gap4.3: Historial de traducción de la colección(Actualizado automáticamente por DAO Flow). */
        val favorites: List<TranslateHistoryItem> = emptyList(),
        /** v1.0.30 gap4.5: Lista de estilos definidos por el usuario(Cargando desde preferencias compartidas). */
        val customStyles: List<CustomStyle> = emptyList(),
        /** v1.0.30 gap4.4: Resultados de traducción por lotes(Entrada correspondiente una por una). */
        val batchResults: List<BatchResult> = emptyList(),
        /** v1.0.30 gap4.4: Si la traducción por lotes está en curso. */
        val batchTranslating: Boolean = false,
        /** v1.0.30 gap4.6: Glosario(Texto original → traducción),Se completa después de una actualización activada por la interfaz de usuario. */
        val glossary: Map<String, String> = emptyMap(),
    )

    /** v1.97: Elemento del historial de traducción. */
    data class TranslateHistoryItem(
        val id: String,
        val sourceText: String,
        val translatedText: String,
        val sourceLanguage: String,
        val targetLanguage: String,
        val style: String = "Universales",
        val timestamp: Long = System.currentTimeMillis(),
        /** v1.0.30 gap4.3: Si ha sido recogido. */
        val favorite: Boolean = false,
    )

    /** v1.0.30 gap4.5: Personaliza el estilo de traducción. */
    data class CustomStyle(
        val name: String,
        /** El fragmento de comando agregado al mensaje(Como por ejemplo"Utilice la traducción al chino clásico antiguo"). */
        val prompt: String,
    )

    /** v1.0.30 gap4.4: Traducir resultados individuales en lotes. */
    data class BatchResult(
        val original: String,
        val translated: String,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    /** v1.0.30 gap4.8: LRU Almacenamiento en caché sin conexión,key = "${sourceText}_${targetLanguage}_${style}". */
    private val translateCache = android.util.LruCache<String, String>(50)

    /** v1.0.30 gap4.5: Preferencias compartidas de estilo personalizado. */
    private val stylePrefs by lazy {
        appContext.getSharedPreferences(PREFS_CUSTOM_STYLES, Context.MODE_PRIVATE)
    }

    init {
        // v1.0.17: Cargar historial de traducción desde Room,La fuente de datos se cambia a DAO Flow(La historia no se pierde después de que se mata el proceso.)
        viewModelScope.launch {
            translateHistoryDao.observeRecent(MAX_HISTORY).map { entities ->
                entities.map { it.toHistoryItem() }
            }.collect { items ->
                // v1.0.30 gap4.8: Llene el caché LRU al cargar el historial,Las traducciones repetidas posteriores afectan directamente
                items.forEach { item ->
                    val key = buildCacheKey(item.sourceText, item.targetLanguage, item.style)
                    if (translateCache.get(key) == null) {
                        translateCache.put(key, item.translatedText)
                    }
                }
                _state.update { it.copy(history = items) }
            }
        }
        // v1.0.30 gap4.3: Favoritos Flow
        viewModelScope.launch {
            translateHistoryDao.observeFavorites().map { entities ->
                entities.map { it.toHistoryItem() }
            }.collect { items ->
                _state.update { it.copy(favorites = items) }
            }
        }
        // v1.0.30 gap4.5: Cargar estilos personalizados al inicio
        loadCustomStyles()
        // v1.0.30 gap4.6: Actualiza el glosario al iniciar para state(UI Mostrar cantidad en consecuencia)
        refreshGlossary()
    }

    /** Corrutina de traducción actual,Se admite la cancelación. */
    private var translateJob: Job? = null

    /** v1.0.30 gap4.4: Corrutina de traducción por lotes,Se admite la cancelación. */
    private var batchJob: Job? = null

    /** v1.0.17: Entity → UI Mapeo del modelo de datos. */
    private fun TranslateHistoryEntity.toHistoryItem(): TranslateHistoryItem = TranslateHistoryItem(
        id = id,
        sourceText = sourceText,
        translatedText = translatedText,
        sourceLanguage = sourceLanguage,
        targetLanguage = targetLanguage,
        style = style,
        timestamp = createdAt,
        favorite = favorite,
    )

    /** Actualizar el texto de entrada. */
    fun updateInput(text: String) {
        _state.update { it.copy(inputText = text) }
    }

    /** Actualizar el idioma de destino. */
    fun updateTargetLanguage(language: String) {
        _state.update { it.copy(targetLanguage = language) }
    }

    /** Actualizar el idioma de origen. */
    fun updateSourceLanguage(language: String) {
        _state.update { it.copy(sourceLanguage = language) }
    }

    /** v1.0.30: Estilo de traducción actualizado. */
    fun updateTranslationStyle(style: String) {
        _state.update { it.copy(translationStyle = style) }
    }

    /** Intercambiar idiomas de origen y de destino,y rellene la traducción actual en el cuadro de entrada(Traducción fácil de continuar). */
    fun swapLanguages() {
        val current = _state.value
        translateJob?.cancel()
        _state.update {
            it.copy(
                sourceLanguage = current.targetLanguage,
                targetLanguage = if (current.sourceLanguage == SOURCE_AUTO) TARGET_LANGUAGES.first() else current.sourceLanguage,
                inputText = current.translatedText,
                translatedText = "",
                translating = false,
                errorMessage = null,
            )
        }
    }

    /** Entradas y traducciones claras. */
    fun clear() {
        translateJob?.cancel()
        _state.update {
            it.copy(
                inputText = "",
                translatedText = "",
                translating = false,
                errorMessage = null,
            )
        }
    }

    /** v1.97: Cargar elementos del historial en el cuadro de entrada(Retraducir o ver). */
    fun loadHistoryItem(item: TranslateHistoryItem) {
        translateJob?.cancel()
        _state.update {
            it.copy(
                inputText = item.sourceText,
                translatedText = item.translatedText,
                sourceLanguage = item.sourceLanguage,
                targetLanguage = item.targetLanguage,
                translating = false,
                errorMessage = null,
            )
        }
    }

    /** v1.97: Borrar historial de traducción. */
    fun clearHistory() {
        // v1.0.17: Cambiar a eliminación persistente,DAO Flow Actualizará automáticamente state.history a una lista vacía
        viewModelScope.launch {
            translateHistoryDao.deleteAll()
        }
    }

    /**
     * Pegue el texto del portapapeles en el cuadro de entrada.
     * @param clipText texto del portapapeles(Obtenido de ClipboardManager por capa UI)
     * @return true Indica pegado exitoso,false Indica que el portapapeles está vacío.
     */
    fun paste(clipText: String?): Boolean {
        if (clipText.isNullOrBlank()) return false
        _state.update { it.copy(inputText = clipText) }
        return true
    }

    /** Rellene la traducción en el cuadro de entrada(usado para"Traducir después de la traducción"escena). */
    fun swapResultToInput() {
        val result = _state.value.translatedText
        if (result.isBlank()) return
        translateJob?.cancel()
        _state.update {
            it.copy(
                inputText = result,
                translatedText = "",
                translating = false,
                errorMessage = null,
            )
        }
    }

    /**
     * Realizar traducción.
     *
     * Estrategia:completeText Prioridad → streamChat Llega al fondo de esto.
     * Actualizaciones en tiempo real durante la recopilación de streaming translatedText,Permita que el usuario vea el resultado palabra por palabra.
     * v1.0.30 gap4.8: Verifique el caché LRU antes de traducir,Los hits regresan directamente sin ajustar LLM.
     * v1.0.30 gap4.6: Se adjunta a la directiva de aviso cuando el glosario no está vacío.
     */
    fun translate() {
        val current = _state.value
        if (current.translating) return
        if (current.inputText.isBlank()) return

        // gap4.8: El hit de caché regresa directamente
        val cacheKey = buildCacheKey(current.inputText, current.targetLanguage, current.translationStyle)
        val cached = translateCache.get(cacheKey)
        if (cached != null) {
            _state.update {
                it.copy(
                    translating = false,
                    translatedText = cached,
                    errorMessage = null,
                )
            }
            return
        }

        translateJob?.cancel()
        _state.update {
            it.copy(
                translating = true,
                translatedText = "",
                errorMessage = null,
            )
        }

        translateJob = viewModelScope.launch {
            try {
                val prompt = buildTranslationPrompt(
                    text = current.inputText,
                    targetLanguage = current.targetLanguage,
                    sourceLanguage = current.sourceLanguage,
                    style = current.translationStyle,
                    customStyles = current.customStyles,
                    glossary = current.glossary,
                )
                val messages = listOf(UIMessage(role = MessageRole.USER, content = prompt))

                // Prioridad completeText(Devolución única,rapido)
                val translated: String = try {
                    val completion: ChatCompletion = chatService.completeText(messages = messages)
                    // v1.99: Quite las etiquetas incrustadas en el modelo de inferencia,Mantenga solo traducciones puras
                    io.zer0.muse.transformer.stripThinkTags(completion.text).trim()
                } catch (e: UnsupportedOperationException) {
                    // Provider No implementado completeText,degradar la transmisión
                    collectStream(messages)
                } catch (e: Exception) {
                    // Otros errores también degradan la transmisión(Fluctuación de la red, etc.)
                    Logger.w(TAG, "completeText failed, fallback to streamChat", e)
                    collectStream(messages)
                }

                if (translated.isEmpty()) {
                    _state.update {
                        it.copy(
                            translating = false,
                            errorMessage = appContext.getString(R.string.err_chat_translate_empty),
                        )
                    }
                } else {
                    // gap4.8: Escribir en caché LRU
                    translateCache.put(cacheKey, translated)
                    // v1.0.17: Traducido con éxito,persistir en Room(La lista del historial se actualiza automáticamente mediante DAO Flow)
                    val entity = TranslateHistoryEntity(
                        id = java.util.UUID.randomUUID().toString(),
                        sourceText = current.inputText,
                        translatedText = translated,
                        sourceLanguage = current.sourceLanguage,
                        targetLanguage = current.targetLanguage,
                        style = current.translationStyle,
                    )
                    translateHistoryDao.insert(entity)
                    _state.update {
                        it.copy(
                            translating = false,
                            translatedText = translated,
                        )
                    }
                }
            } catch (e: CancellationException) {
                // Cancelación de rutina,No actualizar el estado(cancelTranslation Procesado)
                throw e
            } catch (t: Exception) {
                Logger.e(TAG, "translate failed", t)
                _state.update {
                    it.copy(
                        translating = false,
                        errorMessage = appContext.getString(R.string.err_chat_translate_failed, t.message ?: appContext.getString(R.string.err_chat_unknown)),
                    )
                }
            }
        }
    }

    /** Cancelar la traducción actual. */
    fun cancelTranslation() {
        translateJob?.cancel()
        _state.update { it.copy(translating = false) }
    }

    /** Consumir mensajes de error(UI Se llama después de que se muestra la capa.,Borrar errorMessage). */
    fun consumeError() {
        _state.update { it.copy(errorMessage = null) }
    }

    /** Lea el texto actual en voz alta. */
    fun speakSource(): Boolean {
        val text = _state.value.inputText.trim()
        if (text.isBlank()) return false
        return ttsManager.speak(text, utteranceId = "translate_source_${System.currentTimeMillis()}")
    }

    /** Lea la traducción actual en voz alta. */
    fun speakTranslated(): Boolean {
        val text = _state.value.translatedText.trim()
        if (text.isBlank()) return false
        return ttsManager.speak(text, utteranceId = "translate_result_${System.currentTimeMillis()}")
    }

    /** Deja de leer. */
    fun stopSpeaking() {
        ttsManager.stop()
    }

    /**
     * v1.0.30 gap4.3: Cambia el estado de la colección de una determinada parte de la historia.
     */
    fun toggleFavorite(item: TranslateHistoryItem) {
        viewModelScope.launch {
            translateHistoryDao.setFavorite(item.id, !item.favorite)
            // No hay cambios en el caché(El estado de la colección no tiene nada que ver con la traducción.)
        }
    }

    /**
     * v1.0.30 gap4.4: Traducción por lotes.
     *
     * Traduce varios fragmentos de texto con una sola convocatoria de LLM,prompt Solicitar modelo para devolver el formato de matriz JSON
     * `[{"original":"...","translated":"..."}]`,Después del análisis, guárdelos en el historial uno por uno.
     *
     * @param texts Lista de textos a traducir(Cada artículo se traduce de forma independiente.)
     * @param targetLanguage Idioma de destino Nombre chino
     */
    fun translateBatch(texts: List<String>, targetLanguage: String) {
        if (_state.value.batchTranslating) return
        val valid = texts.map { it.trim() }.filter { it.isNotEmpty() }
        if (valid.isEmpty()) return

        batchJob?.cancel()
        _state.update {
            it.copy(
                batchTranslating = true,
                batchResults = emptyList(),
                errorMessage = null,
            )
        }

        batchJob = viewModelScope.launch {
            try {
                val style = _state.value.translationStyle
                val sourceLang = _state.value.sourceLanguage
                // v2.6.7: Debe pasarse explícitamente——La versión anterior solo agregaba parámetros predeterminados a buildBatchPrompt.
                // Si el punto de llamada no se cambia, significa que el glosario y el estilo personalizado aún no se transmiten (el valor predeterminado estará“Fijo”).
                val prompt = buildBatchPrompt(
                    texts = valid,
                    targetLanguage = targetLanguage,
                    sourceLanguage = sourceLang,
                    style = style,
                    customStyles = _state.value.customStyles,
                    glossary = _state.value.glossary,
                )
                val messages = listOf(UIMessage(role = MessageRole.USER, content = prompt))

                val raw: String = try {
                    val completion = chatService.completeText(messages = messages)
                    io.zer0.muse.transformer.stripThinkTags(completion.text).trim()
                } catch (e: UnsupportedOperationException) {
                    collectStream(messages)
                } catch (e: Exception) {
                    Logger.w(TAG, "batch completeText failed, fallback to streamChat", e)
                    collectStream(messages)
                }

                val results = parseBatchResponse(raw, valid)
                if (results.isEmpty()) {
                    _state.update {
                        it.copy(
                            batchTranslating = false,
                            errorMessage = appContext.getString(R.string.err_chat_translate_empty),
                        )
                    }
                    return@launch
                }

                // Escribir historial uno por uno + caché LRU
                results.forEach { r ->
                    val entity = TranslateHistoryEntity(
                        id = java.util.UUID.randomUUID().toString(),
                        sourceText = r.original,
                        translatedText = r.translated,
                        sourceLanguage = sourceLang,
                        targetLanguage = targetLanguage,
                        style = style,
                    )
                    translateHistoryDao.insert(entity)
                    val key = buildCacheKey(r.original, targetLanguage, style)
                    translateCache.put(key, r.translated)
                }
                _state.update {
                    it.copy(
                        batchTranslating = false,
                        batchResults = results,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Exception) {
                Logger.e(TAG, "batch translate failed", t)
                // v2.6.7: romper el banco——La única línea original supera los 140 caracteres (longitud máxima de línea en stock,
                // Exento por número de línea base). Recórtelo directamente sin depender de la cubierta base.
                val reason = t.message ?: appContext.getString(R.string.err_chat_unknown)
                _state.update {
                    it.copy(
                        batchTranslating = false,
                        errorMessage = appContext.getString(R.string.err_chat_translate_failed, reason),
                    )
                }
            }
        }
    }

    /** v1.0.30 gap4.4: Cancelar la traducción por lotes. */
    fun cancelBatchTranslation() {
        batchJob?.cancel()
        _state.update { it.copy(batchTranslating = false) }
    }

    /** v1.0.30 gap4.4: Borrar resultados de traducción por lotes(UI Se llama cuando el cuadro de diálogo está cerrado.). */
    fun clearBatchResults() {
        _state.update { it.copy(batchResults = emptyList()) }
    }

    // ── gap4.5 Gestión de estilos personalizados ──

    /**
     * Agregar estilo personalizado;Si existe el mismo nombre, se sobrescribirá.
     * persistir en SharedPreferences(JSON matriz:[{"name":"...","prompt":"..."}]).
     */
    fun addCustomStyle(name: String, prompt: String) {
        val trimmedName = name.trim()
        val trimmedPrompt = prompt.trim()
        if (trimmedName.isEmpty()) return
        val current = loadCustomStylesFromPrefs().toMutableList()
        current.removeAll { it.name == trimmedName }
        current.add(CustomStyle(trimmedName, trimmedPrompt))
        saveCustomStylesToPrefs(current)
        _state.update { it.copy(customStyles = current) }
    }

    /** Eliminar estilo personalizado;Devuelve si realmente se eliminó. */
    fun removeCustomStyle(name: String): Boolean {
        val current = loadCustomStylesFromPrefs().toMutableList()
        val removed = current.removeAll { it.name == name }
        if (removed) {
            saveCustomStylesToPrefs(current)
            _state.update { it.copy(customStyles = current) }
        }
        return removed
    }

    /** Cargue estilos personalizados desde SharedPreferences en el estado. */
    private fun loadCustomStyles() {
        _state.update { it.copy(customStyles = loadCustomStylesFromPrefs()) }
    }

    private fun loadCustomStylesFromPrefs(): List<CustomStyle> {
        val json = stylePrefs.getString(KEY_CUSTOM_STYLES, null) ?: return emptyList()
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).mapNotNull { i ->
                val obj = arr.optJSONObject(i) ?: return@mapNotNull null
                CustomStyle(
                    name = obj.optString("name"),
                    prompt = obj.optString("prompt"),
                )
            }.filter { it.name.isNotEmpty() }
        } catch (e: Exception) {
            Logger.w(TAG, "No se pudo analizar el estilo personalizado: ${e.message}")
            emptyList()
        }
    }

    private fun saveCustomStylesToPrefs(list: List<CustomStyle>) {
        val arr = JSONArray()
        list.forEach { s ->
            arr.put(
                JSONObject().apply {
                    put("name", s.name)
                    put("prompt", s.prompt)
                },
            )
        }
        stylePrefs.edit().putString(KEY_CUSTOM_STYLES, arr.toString()).apply()
    }

    // ── gap4.6 Glosario ──

    /** Agregar asignación de términos a [GlossaryStore]. */
    fun addGlossaryEntry(original: String, translated: String) {
        glossaryStore.add(original, translated)
        refreshGlossary()
    }

    /** Eliminar asignación de términos;Devuelve si realmente se eliminó. */
    fun removeGlossaryEntry(original: String): Boolean {
        val removed = glossaryStore.remove(original)
        if (removed) refreshGlossary()
        return removed
    }

    /** de [GlossaryStore] Actualiza el glosario para indicar. */
    fun refreshGlossary() {
        _state.update { it.copy(glossary = glossaryStore.list()) }
    }

    // ── gap4.7 OCR Traducción ──

    /**
     * v1.0.30 gap4.7: Complete el cuadro de entrada de texto original con los resultados del reconocimiento OCR.
     *
     * Llamado por la capa UI [io.zer0.muse.doc.OcrManager.recognize] Páselo después de recibir el texto reconocido.
     */
    fun applyOcrText(text: String) {
        if (text.isBlank()) return
        val current = _state.value.inputText
        val merged = if (current.isBlank()) text else "$current\n---\n$text"
        _state.update { it.copy(inputText = merged) }
    }

    /**
     * Colección en streaming de resultados de traducción,Texto traducido actualizado en tiempo real.
     */
    private suspend fun collectStream(messages: List<UIMessage>): String {
        val sb = StringBuilder()
        chatService.streamChat(messages = messages).collect { event ->
            when (event) {
                is ChatStreamEvent.ContentDelta -> {
                    sb.append(event.delta)
                    _state.update { it.copy(translatedText = sb.toString()) }
                }
                is ChatStreamEvent.Error -> {
                    throw ProviderException(
                        providerError = event.providerError ?: ProviderError.Unknown(displayMessage = event.message),
                        cause = event.throwable,
                    )
                }
                is ChatStreamEvent.Done -> {
                    // final de la corriente,Devuelve el texto completo recopilado.
                }
                else -> {
                    // ignorar ReasoningDelta / ToolCallDelta / ImageDelta
                }
            }
        }
        return io.zer0.muse.transformer.stripThinkTags(sb.toString()).trim()
    }

    /** v1.0.30 gap4.8: Construya la clave de caché LRU. */
    private fun buildCacheKey(sourceText: String, targetLanguage: String, style: String): String =
        "${sourceText.hashCode()}_${targetLanguage}_$style"

    companion object {
        private const val TAG = "TranslateVM"

        /**
         * §11-6: Lista para visualización de áreas históricas.
         *
         * La colección de traducciones solía estar completamente desconectada:Capaz de señalar estrellas, almacenarlas y avanzar. [TranslateUiState.favorites],
         * Pero la interfaz de usuario nunca se muestra favorites,Los usuarios nunca podrán ver lo que han recopilado.
         * poner aquí"¿Qué datos deben mostrarse?"extraer función pura,UI Únicamente responsable de transmitir interruptores.,Conveniente para pruebas y fijación individuales.
         */
        internal fun historyForDisplay(
            history: List<TranslateHistoryItem>,
            favorites: List<TranslateHistoryItem>,
            favoritesOnly: Boolean,
        ): List<TranslateHistoryItem> = if (favoritesOnly) favorites else history

        /** v1.104: Límite superior del historial de traducción(Previamente codificado take(20),Extráigalo como una constante para un fácil ajuste.). */
        private const val MAX_HISTORY = 50

        /** v1.0.30 gap4.5: Clave y nombre de archivo de SharedPreferences de estilo personalizado. */
        private const val PREFS_CUSTOM_STYLES = "translate_custom_styles"
        private const val KEY_CUSTOM_STYLES = "custom_styles_json"

        /** Detecta automáticamente los valores de marcador de posición del idioma de origen. */
        const val SOURCE_AUTO = "Detección automática"

        /** Lista de idiomas de origen admitidos(nombre chino,El primer elemento es la detección automática.). */
        val SOURCE_LANGUAGES: List<String> = listOf(
            SOURCE_AUTO, "chino", "English", "日本語", "한국어",
            "Français", "Deutsch", "Español", "Русский",
            "العربية", "Português",
        )

        /** Lista de idiomas de destino admitidos(nombre chino,Consistente con ChatViewModel.translateMessage). */
        val TARGET_LANGUAGES: List<String> = listOf(
            "chino", "English", "日本語", "한국어",
            "Français", "Deutsch", "Español", "Русский",
            "العربية", "Português",
        )

        /** v1.0.30: Estilos de traducción admitidos. */
        val TRANSLATION_STYLES: List<String> = listOf(
            "Universales",
            "academico",
            "Negocios",
            "coloquial",
            "polaco",
            "conciso",
        )

        /**
         * Crear mensaje de traducción.
         *
         * v1.0.30 gap4.5: Admite estilos personalizados(customStyles agregue su directiva de aviso cuando se presione el nombre).
         * v1.0.30 gap4.6: añadido cuando el glosario no está vacío"Consulte el siguiente glosario para la traducción.: A→B, C→D..."instrucciones.
         *
         * @param sourceLanguage idioma de origen,Deje que el modelo tome sus propias decisiones durante la detección automática.
         * @param style estilo de traducción,Afecta el tono y la redacción.
         * @param customStyles Lista de estilos definidos por el usuario(Agrega el nombre cuando llega prompt)
         * @param glossary Glosario(Texto original → traducción),añadido a cuando no es nulo prompt
         */
        fun buildTranslationPrompt(
            text: String,
            targetLanguage: String,
            sourceLanguage: String = SOURCE_AUTO,
            style: String = TRANSLATION_STYLES.first(),
            customStyles: List<CustomStyle> = emptyList(),
            glossary: Map<String, String> = emptyMap(),
        ): String = buildString {
            if (sourceLanguage == SOURCE_AUTO) {
                appendLine("Eres un asistente de traducción profesional. Por favor identifique automáticamente el idioma del texto a continuación,y traducirlo a$targetLanguage.")
            } else {
                appendLine("Eres un asistente de traducción profesional. Por favor cambie el texto a continuación de$sourceLanguage traducido como$targetLanguage.")
            }
            appendLine("- Solo genera la traducción,No agregue explicaciones, prefijos o comentarios")
            appendLine("- Mantener el formato del texto original.(nueva línea/lista/bloques de código, etc.)")
            appendLine("- Si el texto original ya está$targetLanguage,Salida tal cual")
            // Estilo personalizado primero:Utilice su directiva de aviso cuando acceda a un estilo personalizado
            appendStyleAndGlossary(style, customStyles, glossary)
            appendLine()
            appendLine("Texto original:")
            appendLine(text)
        }

        /**
         * v2.6.7: Directivas de estilo y glosario——Comúnmente utilizado para traducción única y traducción por lotes.
         *
         * Anteriormente, escribí una copia en cada uno de los dos lugares, lo que provocó dos problemas:
         *  1. Se repite el mismo literal de instrucción chino (hardcoded-cjk Por lo tanto, el control de acceso se informa como nuevo);
         *  2. Prioridades inconsistentes——La única línea es "Si se aplica el estilo personalizado, se sobrescribirá el estilo integrado".
         *     Lote es la superposición de "integrado + personalizado", y la misma opción de estilo tiene diferentes estilos de traducción en las dos rutas.
         * Sólo hay una acción después de recolectar un ayudante.
         */
        private fun StringBuilder.appendStyleAndGlossary(style: String, customStyles: List<CustomStyle>, glossary: Map<String, String>) {
            val custom = customStyles.firstOrNull { it.name == style }
            if (custom != null && custom.prompt.isNotEmpty()) {
                appendLine("- ${custom.prompt}")
            } else {
                when (style) {
                    "academico" -> appendLine("- Utiliza expresiones académicas, formales y rigurosas.")
                    "Negocios" -> appendLine("- Utilice expresiones comerciales, profesionales y educadas.")
                    "coloquial" -> appendLine("- Utilice expresiones naturales, coloquiales y cercanas a la conversación diaria.")
                    "polaco" -> appendLine("- Pulir la traducción manteniéndose fiel al significado original.,Hazlo más suave y hermoso.")
                    "conciso" -> appendLine("- Sea lo más conciso posible,Eliminar expresiones redundantes,Mantener la información básica")
                    else -> appendLine("- Utilice expresiones comunes, precisas y naturales.")
                }
            }
            // gap4.6: Glosario (reutilización del formato GlossaryStore.formatPromptSnippet, sin reescritura en línea)
            val snippet = GlossaryStore.formatPromptSnippet(glossary)
            if (snippet.isNotEmpty()) {
                appendLine("- Consulte el siguiente glosario para la traducción.: $snippet")
            }
        }

        /**
         * v1.0.30 gap4.4: Cree un mensaje de traducción por lotes.
         *
         * Pídale al modelo que devuelva una matriz JSON `[{"original":"Texto original1","translated":"traducción1"},...]`,
         * El orden es consistente con la entrada.,Es conveniente corresponder uno a uno después del análisis.
         */
        // v2.6.7: customStyles / glossary Agregado para llenar el vacío de "el lote no cumple con el glosario", el número de parámetros llega a 6.
        // Mantener la misma forma que un único buildTranslationPrompt es alinear el significado de los dos parámetros de ruta.
        // No hay ambigüedad entre "uno con estilo y otro sin"; incluir opciones afectará a tres personas que llaman externas
        // (Página de traducción independiente/herramienta de traducción/traducción en el chat), no vale la pena eliminar una pelusa.
        // El calibre es el mismo que el de las funciones de ensamblaje a nivel de pantalla, como MessageInputField, y está exento por punto.
        @Suppress("LongParameterList")
        fun buildBatchPrompt(
            texts: List<String>,
            targetLanguage: String,
            sourceLanguage: String,
            style: String,
            customStyles: List<CustomStyle> = emptyList(),
            glossary: Map<String, String> = emptyMap(),
        ): String = buildString {
            if (sourceLanguage == SOURCE_AUTO) {
                appendLine("Eres un asistente de traducción profesional. Por favor identifica automáticamente el idioma de cada texto,y traducirlo a$targetLanguage.")
            } else {
                appendLine("Eres un asistente de traducción profesional. Cambie cada párrafo del texto siguiente de$sourceLanguage traducido como$targetLanguage.")
            }
            appendLine("- Formato de matriz JSON de salida estricto: [{\"original\":\"Texto original\",\"translated\":\"traducción\"},...]")
            appendLine("- El orden de la matriz corresponde al orden de entrada uno a uno.")
            appendLine("- original Los campos conservan el texto original.,translated El campo es la traducción correspondiente.")
            appendLine("- No genere nada que no sea JSON(Sin bloques de código de rebajas)")
            appendLine("- Si un párrafo ya está$targetLanguage,translated Igual que el original")
            // v2.6.7: Utilice el mismo método de traducción que la traducción única. helper —— Anteriormente, los estilos y glosarios no se cargaban en lotes.
            // y incorporado/La personalización es una superposición en lugar de una superposición, y los estilos de traducción de las dos rutas son inconsistentes.
            appendStyleAndGlossary(style, customStyles, glossary)
            appendLine()
            appendLine("Texto a traducir(total ${texts.size} segmento):")
            texts.forEachIndexed { idx, t ->
                appendLine("[${idx + 1}] $t")
            }
        }

        /**
         * v1.0.30 gap4.4: Analizar respuestas de traducción por lotes.
         *
         * Los modelos pueden devolver bloques de código de rebajas envueltos en JSON,Pele primero y luego analice.
         * Devuelve una lista vacía cuando falla el análisis(La persona que llama se degrada a un mensaje de error).
         */
        fun parseBatchResponse(raw: String, originals: List<String>): List<BatchResult> {
            val cleaned = raw
                .trim()
                .removePrefix("```json")
                .removePrefix("```")
                .removeSuffix("```")
                .trim()
            if (cleaned.isEmpty() || !cleaned.startsWith("[")) return emptyList()
            return try {
                val arr = JSONArray(cleaned)
                (0 until arr.length()).mapNotNull { i ->
                    val obj = arr.optJSONObject(i) ?: return@mapNotNull null
                    val original = obj.optString("original")
                    val translated = obj.optString("translated")
                    if (translated.isEmpty()) return@mapNotNull null
                    BatchResult(original = original, translated = translated)
                }
            } catch (e: Exception) {
                Logger.w(TAG, "No se pudo analizar la respuesta de traducción por lotes: ${e.message}")
                // Ten todo en cuenta:Intente mapear fila por fila(Cuando el modelo no devuelve una matriz JSON)
                val lines = raw.split("\n").filter { it.isNotBlank() }
                if (lines.size == originals.size) {
                    lines.mapIndexedNotNull { idx, line ->
                        val translated = line.trim().removePrefix("```").trim()
                        if (translated.isEmpty() || idx >= originals.size) {
                            null
                        } else {
                            BatchResult(originals[idx], translated)
                        }
                    }
                } else {
                    emptyList()
                }
            }
        }
    }
}
