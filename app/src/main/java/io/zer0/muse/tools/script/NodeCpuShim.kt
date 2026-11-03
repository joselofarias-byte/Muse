package io.zer0.muse.tools.script

import io.zer0.muse.runtime.MuseRuntime

/**
 * Script de divulgación de información de CPU de nodo incorporado.
 *
 * algunos equipos/libuv no puede leer la lista de CPU en el sandbox:`os.cpus()` Devuelve una matriz vacía,`os.availableParallelism()`
 * Devuelve 0, dependiendo del número de núcleos del grupo de subprocesos./El paquete de paralelismo npm se degradará silenciosamente a un solo núcleo o se calculará directamente como 0, lo que provocará un bloqueo.
 *
 * Los datos finales provienen de [MuseRuntime] Variables de entorno inyectadas ([MuseRuntime.ENV_CPU_COUNT]), solo tiene efecto cuando no se puede obtener la fuente nativa:
 *  - `os.cpus()` Sintetizar entradas de igual longitud al devolver una matriz vacía (`model`/`speed`/`times` Lo suficientemente completo para que las partes confiantes lo lean);
 *  - `os.availableParallelism()` Devuelve 0 o devuelve el número de núcleos sintéticos en caso de error.
 *
 * Cuando la función original regrese normalmente, se mantendrá tal como está sin cambiar el valor real. El script está incluido en una función autoejecutable y no está incluido en el alcance del script.
 * Pierde cualquier variable y no cambia el valor de retorno del contenedor.
 */
internal object NodeCpuShim {

    /** nombre de la variable de entorno (con [MuseRuntime.ENV_CPU_COUNT] Misma fuente, aquí solo se utilizan referencias constantes). */
    val ENV_KEY: String = MuseRuntime.ENV_CPU_COUNT

    /** Devuelve código que se puede colocar directamente al principio del script (función pura, sin efectos secundarios). */
    fun script(): String = """
        ;(function () {
          var os = require('node:os');
          var envCount = parseInt(process.env.$ENV_KEY, 10);
          var nativeCpus = os.cpus;
          var nativeParallelism = os.availableParallelism;
          function readNativeCpus() {
            try {
              var list = nativeCpus ? nativeCpus.call(os) : null;
              return Array.isArray(list) ? list : [];
            } catch (e) {
              return [];
            }
          }
          if (envCount > 0 && readNativeCpus().length === 0) {
            os.cpus = function () {
              var list = [];
              for (var i = 0; i < envCount; i++) {
                list.push({
                  model: 'Muse virtual CPU',
                  speed: 0,
                  times: { user: 0, nice: 0, sys: 0, idle: 0, irq: 0 }
                });
              }
              return list;
            };
          }
          var nativeValue = 0;
          try { nativeValue = nativeParallelism ? nativeParallelism.call(os) : 0; } catch (e) { nativeValue = 0; }
          if (!(nativeValue > 0)) {
            var detected = readNativeCpus().length;
            var effective = detected > 0 ? detected : (envCount > 0 ? envCount : 1);
            os.availableParallelism = function () { return effective; };
          }
        })();
    """.trimIndent()

    /** Arma el código secreto [body] Antes (se ejecuta la primera frase del script, antes de cualquier código de usuario). */
    fun wrap(body: String): String = script() + "\n" + body
}
