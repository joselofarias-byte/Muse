package io.zer0.muse.vdproto;

/**
 * v2.2.1 pantalla virtual:Servidor ↔ anfitrión(aplicación principal)La transmisión mano a mano es constante.
 *
 * Servidor(shell uid)Pase IActivityManager.broadcastIntent(reflexión)cinturón de barra
 * {@link #EXTRA_BINDER} IBinder transmitir a {@code setPackage(hostPkg)},
 * aplicación principal {@code VirtualDisplayBinderReceiver}(exported=true)Complete el formulario de registro después de recibirlo.
 *
 * Todo Java y sin dependencias.:El módulo del servidor hace referencia directamente a esta clase.,dex No lleva kotlin-stdlib.
 */
public final class VdContract {

    /** transmisión mano a mano action(package Orientación,difusión no implícita). */
    public static final String ACTION_BINDER = "io.zer0.muse.vdproto.BINDER";

    /** objetivo(anfitrión)nombre del paquete extra,El servidor repite el nombre del paquete de la aplicación principal para verificar el destinatario. */
    public static final String EXTRA_HOST_PACKAGE = "host_package";

    /** IBinder extra(IVirtualDisplayService de Stub.asBinder()). */
    public static final String EXTRA_BINDER = "binder";
    public static final String EXTRA_HANDOFF_TOKEN = "handoff_token";

    /** La ruta fija del jar del servidor en /data/local/tmp(Lanzador compartido con pkill). */
    public static final String SERVER_JAR_PATH = "/data/local/tmp/muse-vd-server.jar";

    /** Clase de entrada del servidor(CLASSPATH + app_process Segundo parámetro). */
    public static final String SERVER_MAIN_CLASS = "io.zer0.muse.vdserver.Main";

    /** pkill Cadena de características(Procesar líneas de comando que contienen). */
    public static final String SERVER_KILL_PATTERN = SERVER_MAIN_CLASS;

    private VdContract() {
    }
}
