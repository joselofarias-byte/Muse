package io.zer0.muse.ui.markdown

import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import org.json.JSONObject

/**
 * v1.0.92: Acción de devolución de tarjeta — Tarjeta de contenido enriquecido(HTML/SVG)El contrato mínimo con el anfitrión.
 * v2.6.7: Complemente la identidad de la tarjeta y el estado estructurado y solucione la "desconexión de circuito cerrado de la tarjeta".
 *
 * ## ¿Por qué deberíamos cambiar esta vez?
 *
 * El puente originalmente sólo tenía `send(text)`: La tarjeta primero debe comprimir la operación del usuario en un lenguaje natural antes de poder devolver la conversación.
 * Las cosas que no se pueden presionar (arrastrar valores intermedios, elementos seleccionados, resultados de cálculo) se pierden y el anfitrión no lo sabe.
 * ¿De qué tarjeta proviene este mensaje y a qué interacción corresponde? —— Se expresa como "la tarjeta termina en la tarjeta y la conversación termina en la conversación".
 *
 * Contrato actual:
 *  - [Send.cardId]:Tarjeta de identidad. Inyectado por el anfitrión (usando la identificación del mensaje, naturalmente vinculado a la ubicación de la conversación),
 *    Se aprobó el guión de la tarjeta `window.muse.cardId` Lea directamente, ya no es necesario modelar una sola vez `c_xxx`
 *    Codificado en HTML (esa ruta se interrumpirá tan pronto como se cambie el cardId).
 *  - [Send.data]: El estado estructurado devuelto por la tarjeta (JSON texto). El anfitrión lo trata como invisible.
 *    `<card_state>` La cola está adjunta al mensaje del usuario y el modelo puede leerla, pero la burbuja no se muestra.
 *  - [Send.text]: Un resumen de una frase para usuarios y modelos. Puede ser muy burdo, la precisión viene dada por [Send.data] oso.
 *
 * ruta de activación:
 *  - [Send]:  Llamada de guión en tarjeta window.muse.send(...)(después [MuseCardBridge] Retorno del puente);
 *  - [Save]:  El usuario hace clic en el encabezado de la tarjeta."guardar"botón(Guardado como un artefacto por la persona que llama).
 */
sealed interface CardAction {
    /**
     * El script de la tarjeta solicita el envío de un mensaje de usuario.
     *
     * @param text Una oración para leer (la burbuja muestra esta oración, que también es el texto visto por el modelo)
     * @param cardId tarjeta de identidad;null Indica que el host no está inyectado (tarjeta antigua/Escenario sin chat)
     * @param data estado estructurado (JSON texto);null Indica que la tarjeta no tiene estatus adicional
     */
    data class Send(
        val text: String,
        val cardId: String? = null,
        val data: String? = null,
    ) : CardAction

    /** El usuario solicitó que la tarjeta se guardara como un artefacto. */
    data class Save(val language: String, val content: String) : CardAction

    /**
     * v2.6.13: El usuario solicitó agregar la tarjeta a favoritos.
     *
     * Las tarjetas no son mensajes y los favoritos sólo reconocen la tabla de mensajes; entonces la forma en que se implementa esta acción es "en una sesión de recolección dedicada
     * "Deje un mensaje que pueda recopilarse", el texto es el código fuente de la tarjeta (encerrado por una valla, haga clic en la página de recopilación para ver el código fuente).
     *
     * @param language idioma de la tarjeta (html/svg/chart/mermaid), determina la marca de valla de la entrada de colección
     * @param content Código fuente de la tarjeta
     * @param title Título del elemento de la colección (obtenga la etiqueta de idioma en el menú)
     */
    data class Favorite(
        val language: String,
        val content: String,
        val title: String,
        /** Identidad de la tarjeta (identificación del mensaje en el escenario del chat);null Depende de la persona que llama utilizar la identificación del mensaje para averiguarlo. */
        val cardId: String? = null,
    ) : CardAction
}

/**
 * v1.0.92: Tarjetas JS Puente — Inyectar como objeto global [BRIDGE_NAME],Se aprobó el guión de la tarjeta
 * `window.muse.send(text)`(Empaquetado de script de arranque,ver [CARD_BRIDGE_BOOTSTRAP_JS])Acción de devolución.
 *
 * límite de seguridad:
 *  - exponer solo [send] / [getData] dos metodos,No permita que los scripts lean ningún dato del host;
 *    Las tarjetas solo se pueden obtener si el anfitrión las inyecta activamente. [cardId];
 *  - La devolución de llamada vuelve a la ejecución del hilo principal;Texto vacío descartado;
 *  - El puente solo se inyecta en tarjetas donde la persona que llama proporciona una devolución de llamada(Escenario de mensaje de chat),escenas restantes(Ver como artefacto)
 *    Mantenga una representación pura sin puentes ni secuencias de comandos.
 */
internal class MuseCardBridge(
    private val onAction: (CardAction) -> Unit,
    /** v2.0: Lectura de datos de tarjeta(cardId → JSON texto);null Indica que el escenario no proporciona datos. */
    private val readCardData: ((String) -> String?)? = null,
    /** v2.6.7: tarjeta de identidad(inyección de huésped);null Indica que no hay inyección,El ID de tarjeta del lado de la tarjeta es nulo. */
    private val cardId: String? = null,
) {

    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * v2.6.7: Devolución de tarjeta.
     *
     * Compatible con dos formas de llamada:
     *  - `send("una palabra")`                → Texto sin formato (no es necesario modificar las tarjetas antiguas)
     *  - `send("una palabra", {json-ready})`   → Viene con estado estructurado.
     */
    @JavascriptInterface
    fun send(text: String) {
        val trimmed = text.trim().take(MAX_ACTION_TEXT_LENGTH)
        if (trimmed.isEmpty()) return
        mainHandler.post { onAction(CardAction.Send(trimmed, cardId, null)) }
    }

    /**
     * v2.6.7: Devolución de datos con estado.data Es texto JSON y el host lo transmite de forma transparente al modelo tal como está.
     *
     * Abra un método separado en lugar de sobrecargarlo [send], porque la sobrecarga del método de @JavascriptInterface está en
     * WebView El orden de envío bajo el puente no está garantizado y las tarjetas más antiguas dependen de firmas de un solo parámetro.
     */
    @JavascriptInterface
    fun sendWithData(text: String, data: String?) {
        val trimmed = text.trim().take(MAX_ACTION_TEXT_LENGTH)
        val payload = data?.trim()?.take(MAX_ACTION_DATA_LENGTH)?.takeIf { it.isNotEmpty() }
        if (trimmed.isEmpty() && payload == null) return
        // Si hay un estado puro y no hay resumen, deje una cadena vacía y ViewModel completará la copia localizada.
        mainHandler.post { onAction(CardAction.Send(trimmed, cardId, payload)) }
    }

    /**
     * El script de la tarjeta lee los datos vinculantes mediante cardId (JSON texto); ningún dato devuelve nulo.
     *
     * esto es**Sincronizar instantánea**: la escritura ocurre en el otro lado (herramienta/host), si la tarjeta solo se lee una vez durante el montaje,
     * Será invisible después de actualizaciones posteriores. Por lo tanto, el lado de la tarjeta debe coincidir. `window.muse.data.onChange(cb)` Usar.
     */
    @JavascriptInterface
    fun getData(cardId: String): String? {
        val id = cardId.trim()
        if (id.isEmpty()) return null
        return readCardData?.invoke(id)
    }

    /**
     * Cambios en los datos de suscripción de la tarjeta.
     *
     * ¿Por qué encuestar en lugar de presionar?JS Los puentes sólo se pueden introducir mediante tarjetas (`@JavascriptInterface`),
     * El host no puede llamar activamente a funciones en la tarjeta. querer“empujar”Sólo puedo confiar en WebView.evaluateJavascript para ajustar
     * función global en el lado de la tarjeta, y esa ruta está sobrecargada en la tarjeta/Poco confiable después de la reconstrucción.
     * Seleccione el sondeo aquí: comience solo cuando la tarjeta declare una suscripción,_interval Predeterminado 800 ms, solo compara referencias de cadenas,
     * Los gastos generales pueden ignorarse; cuando se descarga la tarjeta, desaparece junto con la página, sin perder el cronómetro.
     *
     * @return Devuelve verdadero si el inicio es exitoso (los datos son proporcionados por `window.muse.data.onChange` extracción de encuestas)
     */
    @JavascriptInterface
    fun subscribeData(): Boolean = readCardData != null

    /**
     * v2.6.7: negociación de competencias —— La tarjeta primero pregunta al host qué admite y luego decide qué controles mostrar.
     *
     * negociación de competencias —— La tarjeta primero pregunta al host qué admite y luego decide qué controles mostrar.
     *
     * Sin un puente negociado, la tarjeta sólo puede escribir controles a ciegas. Después de escribir, se descubre que el puente no está disponible y falla silenciosamente. ——
     * Lo que el usuario ve es "no hay respuesta después de hacer clic". Con la negociación, las cosas que no son compatibles se pueden ocultar o degradar por adelantado.
     *
     * Devuelve texto sin formato en lugar de un objeto JSON, porque @JavascriptInterface solo puede devolver String;
     * El script de arranque lo envolverá como un objeto y lo expondrá a la tarjeta.
     */
    @JavascriptInterface
    fun capabilities(): String {
        val canEmit = onAction != null
        val canRead = readCardData != null || cardId != null
        return """{"emit":"${if (canEmit) "available" else "requires_host"}",""" +
            """"state":"${if (canEmit) "available" else "requires_host"}",""" +
            """"data":"${if (canRead) "available" else "requires_host"}",""" +
            """"height":"available","cardId":${if (cardId == null) "null" else "\"$cardId\""}}"""
    }

    /** v2.6.7: La tarjeta lee su propia identidad (equivalente a la fuente del lado del puente de window.muse.cardId). */
    @JavascriptInterface
    fun getCardId(): String? = cardId

    /**
     * v2.6.7: La tarjeta informa su propia altura de contenido (CSS px).
     *
     * Se corrigió "La tarjeta grande está apretada": resulta que la altura del desplazamiento solo se mide una vez en onPageFinished.
     * Representación asincrónica en la tarjeta (gráficos, imágenes, plegado y expansión) o la altura cambia después de que el usuario arrastra el panel.
     * El anfitrión todavía usa el antiguo recorte de altura y el contenido es invisible.
     * Impulsado por ResizeObserver en la página, aumenta cada vez/Si se acorta, informará al anfitrión nuevamente.
     */
    @JavascriptInterface
    fun reportHeight(px: Int) {
        if (px <= 0) return
        mainHandler.post { onHeightChanged?.invoke(px) }
    }

    /** v2.6.7: Devolución de llamada de altura (no @JavascriptInterface, solo para uso interno). */
    internal var onHeightChanged: ((Int) -> Unit)? = null

    companion object {
        /** addJavascriptInterface El nombre del objeto global que se utilizará. */
        const val BRIDGE_NAME = "MuseCardBridge"

        /** El límite superior de devolución de texto único.(personaje),Evite que el abuso de secuencias de comandos inyecte mensajes muy largos. */
        private const val MAX_ACTION_TEXT_LENGTH = 4000

        /** El límite superior del estado de devolución única(personaje). Los estados son datos estructurados y se les permite ser más largos que los resúmenes, pero aún tienen un límite. */
        private const val MAX_ACTION_DATA_LENGTH = 16_000

        /**
         * Texto de marcador de posición cuando la tarjeta solo devuelve el estado y no ofrece un resumen.
         *
         * Aquí se deja intencionalmente una cadena vacía: el puente es un entorno no componible y no se puede obtener stringResource;
         * La copia real visible para el usuario se completa con recursos en el lado ChatViewModel para evitar la codificación en chino.
         */
        const val EMPTY_SUMMARY = ""
    }
}

/**
 * v1.0.92: script de arranque — Inyectado junto con la tarjeta HTML.(Empalme después de la limpieza,No afectado por las reglas de limpieza.),
 * Proporcionado para scripts de tarjetas. `window.muse` Fachada.
 *
 * v2.6.7 Nuevo:
 *   window.muse.cardId                       // Identidad de tarjeta de solo lectura (host inyectado con ID de mensaje)
 *   window.muse.send("Resumen", { ...state })    // Con devolución de datos de estado estructurado
 *
 * Uso en la tarjeta:
 *   if (!window.muse) return;                 // Escena sin puente (renderizado de solo lectura)
 *   window.muse.send("Configurar el sueño en 6 horas", { hours: 6 });
 */
internal const val CARD_BRIDGE_BOOTSTRAP_JS: String = """
(function(){
  if (window.muse && window.muse.send) return;
  window.muse = window.muse || {};
  window.muse.available = true;
  var __museLastTap = 0;
  document.addEventListener('click', function(){ __museLastTap = Date.now(); }, true);
  document.addEventListener('touchend', function(){ __museLastTap = Date.now(); }, true);
  // v2.6.7: Tarjeta de identidad. Inyección de host, solo lectura; nulo si no hay puente o no hay inyección.
  try { window.muse.cardId = MuseCardBridge.getCardId(); } catch (e) { window.muse.cardId = null; }
  window.muse.send = function(text, state){
    // guardia de seguridad:Solo permitido en el clic más reciente del usuario/Devolución de datos dentro de una ventana corta después del toque,
    // Evite que los scripts de tarjetas inserten mensajes silenciosamente durante la carga.
    if (Date.now() - __museLastTap > 1500) return;
    var body = String(text == null ? '' : text);
    if (state === undefined || state === null) {
      try { MuseCardBridge.send(body); } catch (e) { /* bridge send failures are non-fatal */ }
      return;
    }
    var payload;
    try { payload = typeof state === 'string' ? state : JSON.stringify(state); }
    catch (e) { payload = null; }
    try { MuseCardBridge.sendWithData(body, payload); } catch (e) { /* non-fatal */ }
  };
  // v2.0: Enlace de datos de tarjeta — Leer datos JSON vinculados al host por cardId
  // Si no se pasan parámetros, la tarjeta se leerá de forma predeterminada.
  window.muse.getData = function(cardId){
    try {
      var id = (cardId == null || cardId === '') ? window.muse.cardId : String(cardId);
      if (!id) return null;
      return MuseCardBridge.getData(id);
    } catch (e) { return null; }
  };
  // fácil de leer —— Analiza en un objeto; devuelve nulo si falla el análisis.
  window.muse.getJSON = function(cardId){
    try {
      var raw = window.muse.getData(cardId);
      return raw ? JSON.parse(raw) : null;
    } catch (e) { return null; }
  };
  // Suscríbete a los cambios de datos.
  //
  // El host no puede llamar activamente a la tarjeta, por lo que aquí se utiliza el sondeo: solo se inicia cuando se llama y solo compara cadenas.
  // No haga nada si el contenido no ha cambiado. La devolución de llamada recibida es**objeto analizado**(Igual que getJSON),
  // Cuando falla el análisis, se devuelve la cadena original.
  //
  // Uso:
  //   window.muse.data.onChange(function (data) { /* pantalla pesada */ });
  window.muse.data = window.muse.data || {};
  window.muse.data.onChange = function(cb){
    if (typeof cb !== 'function') return function(){};
    var last = null;
    var timer = setInterval(function(){
      var raw;
      try { raw = window.muse.getData(); } catch (e) { return; }
      if (raw === last) return;
      last = raw;
      var value = null;
      try { value = raw ? JSON.parse(raw) : null; } catch (e) { value = raw; }
      try { cb(value); } catch (e) { }
    }, 800);
    return function(){ clearInterval(timer); };
  };
  // v2.6.7: negociación de competencias —— La tarjeta primero pregunta al host qué admite y luego decide qué controles mostrar.
  // Uso:
  //   var cap = window.muse.capabilities();
  //   if (cap.emit === 'available') { Representar un control sometible }
  // Los objetos devueltos nunca generan excepciones; todos están marcados como require_host cuando el puente no está disponible.
  window.muse.capabilities = function(){
    try {
      var raw = MuseCardBridge.capabilities();
      return raw ? JSON.parse(raw) : { emit: 'requires_host', state: 'requires_host', data: 'requires_host', height: 'requires_host', cardId: null };
    } catch (e) {
      return { emit: 'requires_host', state: 'requires_host', data: 'requires_host', height: 'requires_host', cardId: null };
    }
  };
  // v2.6.7: Altamente adaptativo —— El contenido se vuelve más alto para indicarle al anfitrión que evite que las tarjetas grandes queden exprimidas por la altura anterior.
  // Dos restricciones evitan que el diseño se deteriore:
  //  1) 80ms Anti-vibración: cuando el contenido cambia intensamente, solo se informa la última vez y no se llama en todos los procesos cuadro por cuadro;
  //  2) Solo aumenta pero no disminuye: el contenido se acorta sin encogerse y evita saltos hacia arriba y hacia abajo (el panel plegable no necesita encogerse cuando se retrae).
  //     Excepción: el primer informe siempre se acepta (de lo contrario, un aumento desde 0 se considerará como“sin disminuir”).
  (function(){
    if (typeof ResizeObserver === 'undefined') return;
    var reported = 0;
    var timer = null;
    function push(){
      timer = null;
      var h = document.body ? Math.ceil(document.body.scrollHeight) : 0;
      if (h <= 0) return;
      if (reported > 0 && h <= reported) return;
      reported = h;
      try { MuseCardBridge.reportHeight(h); } catch (e) { /* non-fatal */ }
    }
    function schedule(){
      if (timer !== null) return;
      timer = setTimeout(push, 80);
    }
    var ro = new ResizeObserver(schedule);
    function start(){
      if (document.body) { ro.observe(document.body); push(); }
      else setTimeout(start, 16);
    }
    start();
    window.addEventListener('load', schedule);
    // fotos/Después de cargar recursos asincrónicos, como fuentes, la altura seguirá cambiando, por lo que deberá agregarla dos veces.
    setTimeout(schedule, 120);
    setTimeout(schedule, 480);
  })();
})();
"""

/**
 * v2.6.7: Adjunte el estado devuelto por la tarjeta al final del cuerpo del mensaje del usuario para que el modelo lo lea.
 *
 * Envolver con caracteres invisibles del área privada (U+2063 INVISIBLE SEPARATOR), la burbuja de diálogo se elimina al renderizar,
 * No afecta la lectura del usuario; lo que ve en el lado del modelo es un segmento claro de "estado de la tarjeta".
 *
 * ¿Por qué no insertar una nueva columna en MessageEntity? La tabla de mensajes ya es DB 116 y se actualiza otra versión para los metadatos de una oración.
 * La migración, los riesgos y los beneficios son desproporcionados. La cola del texto es completamente suficiente para el modelo y, naturalmente, se respalda junto con el mensaje.
 * Exporta y participa en el recorte contextual.
 */
internal fun appendCardStateToMessage(text: String, cardId: String?, data: String?): String {
    if (data.isNullOrBlank()) return text
    val id = cardId?.takeIf { it.isNotBlank() } ?: "unknown"
    // Verifique una vez para evitar insertar JSON ilegal directamente en el modelo como estado estructurado (el script de la tarjeta puede estar mal escrito).
    val normalized = runCatching { JSONObject(data) }.getOrNull()?.toString() ?: data
    return buildString {
        append(text)
        append('\n')
        append(CARD_STATE_OPEN)
        append("cardId=")
        append(id)
        append('\n')
        append(normalized)
        append('\n')
        append(CARD_STATE_CLOSE)
    }
}

/**
 * v2.6.7: Retire la cola del estado de la tarjeta del cuerpo del mensaje (para fines de representación).
 *
 * v2.6.8: La implementación se ha mencionado anteriormente. [io.zer0.muse.transformer.InternalMarkupSanitizer], esta función sólo es compatible con alias.
 * No agregue lógica de eliminación en el lado de llamada —— Exportación unificada en desinfectante: para exhibición `displayContent`,
 * Para exportar `exportContent`, índice/para buscar `searchableContent`.
 */
internal fun stripCardStateFromMessage(text: String): String = io.zer0.muse.transformer.InternalMarkupSanitizer.stripCardState(text)

/**
 * Las marcas de inicio y fin del segmento de estado de la tarjeta (incluidos separadores invisibles para reducir la probabilidad de colisión con el texto normal).
 *
 * v2.6.8: Definir migración [io.zer0.muse.transformer.InternalMarkupSanitizer](autoridad de limpieza),
 * Las constantes con el mismo nombre están reservadas aquí para los llamantes existentes./Referencia de prueba. El formato de escritura permanece sin cambios y los mensajes históricos que se han incluido en la base de datos aún son legibles.
 */
internal const val CARD_STATE_OPEN: String = io.zer0.muse.transformer.InternalMarkupSanitizer.CARD_STATE_OPEN
internal const val CARD_STATE_CLOSE: String = io.zer0.muse.transformer.InternalMarkupSanitizer.CARD_STATE_CLOSE

/**
 * v2.6.9: Interfaz JS dedicada para informes de altura —— con puente interactivo [MuseCardBridge] Completamente desacoplado.
 *
 * El callejón sin salida de la antigua implementación: el guión de observación de altitud (ResizeObserver) sólo puede pasar `MuseCardBridge.reportHeight`
 * Devolución de datos, pero la ruta de representación sin devolución de llamada (producto de paso de tarjeta de tareas, vista previa de pantalla completa, vista de artefactos) no registra el puente en absoluto.
 * Las llamadas en el script son absorbidas silenciosamente por try/catch, por lo que la altura siempre se detiene en la primera prueba de onPageFinished.
 * Si la tarjeta crea DOM (gráficos, listas, paneles plegables) de forma asincrónica en el script, el contenido no crecerá durante la primera prueba.
 * Lo que se midió fue un valor pequeño y nadie lo corrigió después. —— Lo que ve el usuario es "la tarjeta está presionada en una caja fija".
 *
 * Ahora esta interfaz se registrará mientras exista un WebView y existirá para siempre, independientemente de "si se puede devolver".
 */
internal class CardHeightSink {
    /** Devolución de llamada del hilo principal; montado y eliminado por RichContentCard. */
    internal var onHeightChanged: ((Int) -> Unit)? = null

    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    internal fun attachTo(webView: android.webkit.WebView) {
        webView.addJavascriptInterface(this, NAME)
    }

    @android.webkit.JavascriptInterface
    fun reportHeight(px: Int) {
        if (px <= 0) return
        val callback = onHeightChanged ?: return
        mainHandler.post { callback(px) }
    }

    companion object {
        /** addJavascriptInterface El nombre del objeto global que se utilizará. */
        const val NAME = "MuseHeightSink"
    }
}

/**
 * La altura de la tarjeta sigue el contenido: la tarjeta es tan alta como el contenido, sin truncamiento ni desplazamiento dentro de la tarjeta.
 *
 * ## por qué no“Solo reporta una vez”
 *
 * lo he probado“Congelación del primer cuadro + desplazamiento en la tarjeta”. Dos problemas fatales:
 *  1. En el primer cuadro, el contenido a menudo aún no ha crecido (fuentes, imágenes, DOM creado por scripts modelo) y se establece un valor pequeño.
 *     Desde entonces, la tarjeta ha sido aplastada;
 *  2. Lo que quieren los usuarios es“Ver la tarjeta completa de un vistazo”, el desplazamiento dentro de la tarjeta va en contra de esto.
 *
 * Así que volvamos a“La altura es función del contenido.”. De lo que realmente debemos protegernos no es“Cambios de altitud”, pero**enseñar de nuevo**:
 * Altura del dispositivo host → Cambios en la ventana gráfica → cambio de medida → Vuelva a establecer la altura. La clave para cortarlo no es bloquear la altura, sino:
 *  - cantidad [body] cuadro de borde (cuando no se establece la altura, solo está determinada por el contenido y no tiene ningún efecto causal en la ventana gráfica);
 *  - **Nunca escriba altura o overflow**(Una vez escrito, se convierte en influencia propia);
 *  - Utilice un umbral de cambio más grande + ventana de estabilidad para filtrar estados intermedios y fluctuaciones de subpíxeles.
 *
 * También debería seguir si el contenido se acorta (la altura debería volver después de plegar el panel plegable), pero requiere dos mediciones consecutivas para ser consistentes;
 * Si se hace más alto, lo seguirá inmediatamente, porque hacerlo más alto no hará que se recorte el contenido.
 */
internal val HEIGHT_OBSERVER_SCRIPT: String = """
(function(){
  if (window.__museHeightObserver) return;
  window.__museHeightObserver = true;
  var sink = (typeof MuseHeightSink !== 'undefined') ? MuseHeightSink : null;
  if (!sink) return;

  var reported = 0;
  var pending = -1;
  var pendingCount = 0;
  var timer = null;

  // Mide el borde del cuerpo.body No se establece ninguna altura, por lo que está determinada únicamente por el contenido y no tiene ninguna relación causal con la ventana gráfica.
  // No utilice documentElement.scrollHeight: cuando el contenido es más corto que la ventana gráfica, es igual a la altura de la ventana gráfica y la altura se elevará a la ventana gráfica.
  function measure(){
    var b = document.body;
    if (!b) return 0;
    var h = b.getBoundingClientRect().height;
    if (h <= 0) h = b.scrollHeight;
    return Math.ceil(h);
  }

  function push(h){
    reported = h;
    pending = -1;
    pendingCount = 0;
    try { sink.reportHeight(h); } catch (e) { }
  }

  function evaluate(){
    timer = null;
    var h = measure();
    if (h <= 0) return;

    // La primera presentación surtirá efecto directamente.
    if (reported === 0) { push(h); return; }

    var delta = h - reported;
    // No se informan pequeñas diferencias: el redondeo de subpíxeles y las sugerencias de fuente pueden provocar cambios falsos de 1 a 3 píxeles.
    if (Math.abs(delta) < 8) return;

    // Vuélvete más alto: sigue de inmediato (hacerte más alto no interrumpirá el contenido y informar antes brindará una mejor experiencia).
    if (delta > 0) { push(h); return; }

    // Acortamiento: Sólo dos medidas consecutivas deben ser consistentes para creerlo, para evitar el problema causado por el estado intermedio de la animación de plegado.“Primero bajo y luego alto.”.
    if (pending === h) { pendingCount += 1 } else { pending = h; pendingCount = 1 }
    if (pendingCount >= 2) push(h);
  }

  function schedule(){
    if (timer !== null) return;
    timer = setTimeout(evaluate, 80);
  }

  var ro = (typeof ResizeObserver !== 'undefined') ? new ResizeObserver(schedule) : null;
  function start(){
    if (!document.body) { setTimeout(start, 16); return; }
    if (ro) ro.observe(document.body);
    schedule();
    window.addEventListener('load', schedule);
    // Contenido asincrónico (imagen/fuente/Guión del gráfico) La altura seguirá cambiando una vez completada; complétela varias veces.
    setTimeout(schedule, 150);
    setTimeout(schedule, 400);
    setTimeout(schedule, 900);
  }
  start();
})();
""".trimIndent()
