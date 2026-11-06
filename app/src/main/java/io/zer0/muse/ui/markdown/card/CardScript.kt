package io.zer0.muse.ui.markdown.card

/**
 * El único guión en la tarjeta.
 *
 * ## ¿Por qué debe haber una sola copia?
 *
 * La implementación anterior inyecta dos secciones al mismo tiempo, cada una con su propia `ResizeObserver` Guión:
 *  - El IIFE al final del script de arranque del puente (reportado a `MuseCardBridge`)
 *  - Guión de observación de altitud independiente (reportado a `MuseHeightSink`)
 * Los dos observan el mismo cuerpo, realizan antivibraciones de forma independiente, deciden si informarlo por separado y luego reescriben juntos el estado de altitud del anfitrión.
 * Quien corra último gana y su tiempo es impredecible. —— Ésta es la raíz de "la altura de la tarjeta fluctúa y ocasionalmente se corta".
 *
 * Ahora: Fachada y Observación tienen responsabilidades separadas, pero**solo este**se inyecta,`CardRuntime` Es el único punto de reunión.
 *
 * ## Compensaciones de observación a gran altitud
 *
 * cantidad `body` El cuadro fronterizo, no medido. `documentElement.scrollHeight`
 * (Cuando el contenido es más corto que la ventana gráfica, esta última es igual a la altura de la ventana gráfica, lo que elevará la tarjeta a la pantalla completa).
 *
 * Si crece, se informará inmediatamente (el contenido no se cortará si crece); si se acorta, se creerá sólo después de que dos mediciones consecutivas sean consistentes.
 * (Evitar el estado intermedio de la animación de plegado). Umbral de 8 píxeles, filtra la fluctuación de subpíxeles.
 *
 * **Nunca escribas al cuerpo height / overflow** —— Una vez escrita, la medida se convierte en "uno mismo se afecta a sí mismo",
 * Altura de configuración del anfitrión → Cambios en la ventana gráfica → cambio de medida → Si el bucle de retroalimentación se establece en un nivel más alto, se establecerá el bucle de retroalimentación. Ésta es una restricción difícil.
 */
internal object CardScript {

    /** Nombre global de la interfaz altamente reportada (registrada por CardRuntime como addJavascriptInterface). */
    const val HEIGHT_SINK_NAME: String = "MuseHeightSink"

    /**
     * La frase que notifica a la tarjeta que "los datos vinculantes han cambiado". El host lo ejecuta usando evaluaJavascript.
     *
     * Solo se envían señales, no se envían datos: después de recibir la tarjeta, se irá sola `window.muse.getData()` valor,
     * La misma ruta se lee como sondeo, por lo que el comportamiento de los dos modos es exactamente el mismo.
     */
    const val DATA_EVENT_JS: String = "window.__museDataEvent&&window.__museDataEvent()"

    /** El nombre global del puente de interacción. */
    const val BRIDGE_NAME: String = "MuseCardBridge"

    /**
     * Guión de página completa.
     *
     * La fachada y la observación son dos scripts independientes: cuando no hay puente, la fachada marcará sus capacidades como require_host.
     * Las observaciones se inyectan constantemente (la escena sin puentes también requiere un alto grado de seguimiento).
     *
     * El resultado se almacena en caché una vez por proceso: el contenido del script no tiene nada que ver con la tarjeta, el tema o los datos, y cada tarjeta lo almacenará en caché.
     * Cada llamada de ensamblaje HTML.
     */
    fun build(): String = BUILD

    private val BUILD: String by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        buildString {
            append(FACADE_JS)
            append('\n')
            append(observerJs())
        }
    }

    /**
     * `window.muse` Fachada: La única entrada para que los guiones de cartas llamen al anfitrión.
     *
     * También es necesario definir cuando no hay puente, simplemente etiquetar la habilidad verazmente como `requires_host` —— Por lo tanto, la tarjeta pasa a ser una visualización estática.
     * En lugar de terminar de escribir el control y hacer clic en él, descubre que nadie respondió.
     */
    private const val FACADE_JS: String = """
(function(){
  window.muse = window.muse || {};
  var bridge = (typeof MuseCardBridge !== 'undefined') ? MuseCardBridge : null;
  window.muse.available = !!bridge;
  var lastTap = 0;
  document.addEventListener('click', function(){ lastTap = Date.now(); }, true);
  document.addEventListener('touchend', function(){ lastTap = Date.now(); }, true);

  // La identidad solo la genera e inyecta el host; es de sólo lectura en el lado de la tarjeta.
  window.muse.cardId = null;
  if (bridge) { try { window.muse.cardId = bridge.getCardId(); } catch (e) { } }

  function capability(){
    if (!bridge) {
      return { emit: 'requires_host', state: 'requires_host', data: 'requires_host',
               height: 'available', cardId: null };
    }
    try {
      var raw = bridge.capabilities();
      return raw ? JSON.parse(raw) : { emit: 'requires_host', state: 'requires_host',
                                      data: 'requires_host', height: 'available', cardId: null };
    } catch (e) {
      return { emit: 'requires_host', state: 'requires_host', data: 'requires_host',
               height: 'available', cardId: null };
    }
  }
  window.muse.capabilities = capability;

  // Solo permita la devolución de datos dentro de una ventana corta después del último clic del usuario para evitar la inyección silenciosa de mensajes cuando la tarjeta está cargada.
  function withinGesture(){ return Date.now() - lastTap <= 1500; }

  window.muse.send = function(text, state){
    if (!bridge || !withinGesture()) return;
    var body = String(text == null ? '' : text);
    if (state === undefined || state === null) {
      try { bridge.send(body); } catch (e) { }
      return;
    }
    var payload;
    try { payload = (typeof state === 'string') ? state : JSON.stringify(state); }
    catch (e) { payload = null; }
    try { bridge.sendWithData(body, payload); } catch (e) { }
  };

  // Devolución de datos declarativa: escribir en el elemento data-m-invoke="Si quieres enviarlo", haz clic en enviar y listo.
  // Opcional data-m-state='{"k":1}' Trae estado estructurado.
  // Vaya a la etapa de captura para que el clic vinculado a la tarjeta no pueda detenerla;preventDefault Evite saltos de página completa.
  document.addEventListener('click', function(e){
    var node = e.target;
    while (node && node !== document && !(node.getAttribute && node.getAttribute('data-m-invoke'))) {
      node = node.parentNode;
    }
    if (!node || node === document || !node.getAttribute) return;
    var text = node.getAttribute('data-m-invoke') || '';
    if (!text) return;
    e.preventDefault();
    var raw = node.getAttribute('data-m-state');
    var payload;
    if (raw) { try { payload = JSON.parse(raw); } catch (err) { payload = undefined; } }
    window.muse.send(text, payload);
  }, true);

  window.muse.getData = function(cardId){
    if (!bridge) return null;
    try {
      var id = (cardId == null || cardId === '') ? window.muse.cardId : String(cardId);
      if (!id) return null;
      return bridge.getData(id);
    } catch (e) { return null; }
  };

  window.muse.getJSON = function(cardId){
    try {
      var raw = window.muse.getData(cardId);
      return raw ? JSON.parse(raw) : null;
    } catch (e) { return null; }
  };

  // Suscripción de datos: solo se puede llamar al puente mediante la tarjeta, y la notificación inversa del host se basa en evaluarJavascript.
  // Cuando el host admite push (esta página recibe __museDataPush mediante CardRuntime), se activará el evento y se activará el cambio;
  // Volver al sondeo de 800 ms cuando no sea compatible —— Las rutas de degradación se conservan para evitar escenarios sin chat (sin CardDataStore)
  // La tarjeta pierde la capacidad de actualizarse.
  window.muse.data = window.muse.data || {};
  var __museSubs = [];
  function __museRemove(entry){
    var i = __museSubs.indexOf(entry);
    if (i >= 0) __museSubs.splice(i, 1);
  }
  function __museRead(entry){
    var raw;
    try { raw = window.muse.getData(); } catch (e) { return; }
    // Vuelva a llamar solo cuando el valor realmente cambie: el sondeo y la inserción utilizan el mismo método para evitar volver a dibujar repetidamente durante las tormentas de inserción.
    if (raw === entry.last) return;
    entry.last = raw;
    var value = null;
    try { value = raw ? JSON.parse(raw) : null; } catch (e) { value = raw; }
    try { entry.cb(value); } catch (e) { }
  }
  window.muse.data.onChange = function(cb){
    if (typeof cb !== 'function') return function(){};
    var entry = { cb: cb, last: null, timer: null };
    __museSubs.push(entry);
    if (window.__museDataPush) {
      // Lectura complementaria al suscribirse: cubre dos situaciones: "los datos ya existen antes de suscribirse" y "el empuje cae durante la carga de la página".
      setTimeout(function(){ __museRead(entry); }, 0);
      return function(){ __museRemove(entry); };
    }
    entry.timer = setInterval(function(){ __museRead(entry); }, 800);
    return function(){ clearInterval(entry.timer); __museRemove(entry); };
  };
  // Entrada push del host: solo significa "puede haber cambiado" y el valor sigue siendo un puente.
  window.__museDataEvent = function(){
    var snapshot = __museSubs.slice();
    for (var i = 0; i < snapshot.length; i++) __museRead(snapshot[i]);
  };
})();
"""

    /**
     * Altamente observado.
     *
     * No tiene nada que ver con los puentes: las tarjetas sin puentes interactivos también deben seguirse con atención, pero no se pueden devolver.
     * Esta independencia es un escollo que la antigua implementación ha pisado. —— En ese momento, el informe de altitud estaba colgado en el puente y no hubo ninguna observación en el camino libre del puente.
     */
    private fun observerJs(): String {
        return """
(function(){
  if (window.__museHeightObserver) return;
  window.__museHeightObserver = true;
  // La altura sólo va hacia la altura y se hunde: El puente sólo transmite hacia atrás, independientemente de la altura.
  var sink = (typeof MuseHeightSink !== 'undefined') ? MuseHeightSink : null;
  if (!sink) return;

  var reported = 0;
  var pending = -1;
  var pendingCount = 0;
  var timer = null;

  function measure(){
    var b = document.body;
    if (!b) return 0;
    // No mida antes de crear la ventana gráfica: un ancho cercano a 0 hará que cada palabra se ajuste de forma independiente.
    // La altura medida es un valor falso de varios miles de píxeles, y escribirlo en el host ampliará la tarjeta hasta convertirla en una pantalla en blanco.
    if (window.innerWidth < 80 || window.innerHeight < 40) return 0;
    var box = b.getBoundingClientRect().height;
    var content = b.scrollHeight;
    // Lo que sea mayor: el cuadro puede ser más corto que el contenido (el contenido se desborda) o puede ser más alto que el contenido porque el margen está colapsado.
    // Creer en sólo uno de ellos será un error —— El contenido del buzón se corta y el contenido de la carta se deja en blanco en la parte inferior.
    var h = Math.max(box, content);
    if (h <= 0) h = b.offsetHeight;
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
    if (reported === 0) { push(h); return; }
    var delta = h - reported;
    if (Math.abs(delta) < 8) return;
    if (delta > 0) { push(h); return; }
    if (pending === h) { pendingCount += 1 } else { pending = h; pendingCount = 1 }
    if (pendingCount >= 2) push(h);
  }

  function schedule(){
    if (timer !== null) return;
    timer = setTimeout(evaluate, 80);
  }

  // Prueba de recuperación en la parte inferior: sólo realice la prueba cuando "aún no se haya medido la altura".
  // Cada vez que se evalúa se requiere getBoundingClientRect + scrollHeight, lo que equivale a forzar un diseño;
  // Cuando hay muchas tarjetas y desplazamientos frecuentes hacia adentro y hacia afuera,150/400/900 Tres temporizadores incondicionales son tres diseños redundantes por instancia de tarjeta.
  // Después del primer informe exitoso, ResizeObserver seguirá siendo responsable de los cambios posteriores y no tiene ningún valor encubrirlo.
  function fallback(){
    if (reported > 0) return;
    schedule();
  }

  var ro = (typeof ResizeObserver !== 'undefined') ? new ResizeObserver(schedule) : null;
  function start(){
    if (!document.body) { setTimeout(start, 16); return; }
    if (ro) ro.observe(document.body);
    schedule();
    window.addEventListener('load', fallback);
    setTimeout(fallback, 150);
    setTimeout(fallback, 400);
    setTimeout(fallback, 900);
  }
  start();
})();
""".trimIndent()
    }
}
