package io.zer0.muse.ui.common.media

import android.content.Context
import android.webkit.WebView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner

/**
 * WebView consciente del ciclo de vida.
 *
 * v1.88 Correcciones de limitaciones conocidas: darse cuenta [LifecycleEventObserver] interfaz,Observa al anfitrión [LifecycleOwner] de
 * ON_PAUSE / ON_RESUME / ON_DESTROY evento,Llamar automáticamente a WebView [onPause] / [onResume] /
 * [pauseTimers] / [resumeTimers] / [destroy],Resuelva el temporizador JS cuando la actividad está en segundo plano/La animación continúa
 * Problemas que provocan consumo de energía durante el funcionamiento.
 *
 * descripción del comportamiento:
 *  - ON_PAUSE → [onPause] + [pauseTimers]:Pausar todos los temporizadores y animaciones JS en WebView;
 *  - ON_RESUME → [onResume] + [resumeTimers]:Reanudar la ejecución;
 *  - ON_DESTROY → [destroy] + Darse de baja observador:Destruye WebView y desvincula el ciclo de vida.,Evite fugas.
 *
 * Uso:
 *  - Recomendado:Aprobado en Redactar [LifecycleAwareWebViewContainer] uso,Registro automático/Cerrar sesión;
 *  - manuales:Pase [LifecycleAwareWebViewFactory.create] Crear instancia,se registrará automáticamente en el entrante LifecycleOwner;
 *  - Al salir del grupo(Composable dispose Pero el anfitrión no se destruye.)Necesita ser llamado manualmente [destroy] + removeObserver.
 *
 * @param context Android contexto
 */
class LifecycleAwareWebView(
    context: Context,
) : WebView(context), LifecycleEventObserver {

    /**
     * Devolución de llamada de evento del ciclo de vida del host.
     *
     * Lanza LifecycleOwner ON_PAUSE / ON_RESUME / ON_DESTROY se llama cuando,
     * Reenviar al método de ciclo de vida correspondiente de WebView.
     *
     * @param source evento desencadenante LifecycleOwner
     * @param event tipos de eventos del ciclo de vida
     */
    override fun onStateChanged(source: LifecycleOwner, event: Lifecycle.Event) {
        when (event) {
            // Pausar WebView y todos los temporizadores JS cuando esté en segundo plano,Evite el consumo de energía innecesario
            Lifecycle.Event.ON_PAUSE -> {
                onPause()
                pauseTimers()
            }
            // Restaurar al volver al primer plano
            Lifecycle.Event.ON_RESUME -> {
                onResume()
                resumeTimers()
            }
            // Limpiar recursos y desvincular observadores cuando se destruya el host
            Lifecycle.Event.ON_DESTROY -> {
                destroy()
                source.lifecycle.removeObserver(this)
            }
            else -> Unit
        }
    }
}

/**
 * [LifecycleAwareWebView] Objeto de fábrica.
 *
 * Proporcionar una entrada de creación unificada,Encapsulación"Crear instancia + registrarse para LifecycleOwner"Dos pasos,
 * Evite que la persona que llama pierda la lógica de registro.
 */
object LifecycleAwareWebViewFactory {

    /**
     * crear [LifecycleAwareWebView] y unirse a [lifecycleOwner].
     *
     * Después de la creación, WebView escuchará automáticamente [lifecycleOwner] de ON_PAUSE / ON_RESUME / ON_DESTROY evento,
     * Pausar el temporizador JS mientras está en segundo plano/animación,Reanudar mientras está en primer plano.
     *
     * La persona que llama aún necesita llamar al Composable cuando sale de la composición.(Escenario de destrucción sin anfitrión)manuales destroy + removeObserver,
     * o usar directamente [LifecycleAwareWebViewContainer] Gestionado automáticamente por el contenedor.
     *
     * @param context Android contexto
     * @param lifecycleOwner Propietario del ciclo de vida del host(Generalmente Activity / Fragment)
     * @return Registrado para observación del ciclo de vida. [LifecycleAwareWebView] Ejemplo
     */
    fun create(context: Context, lifecycleOwner: LifecycleOwner): LifecycleAwareWebView {
        return LifecycleAwareWebView(context).also { webView ->
            lifecycleOwner.lifecycle.addObserver(webView)
        }
    }
}
