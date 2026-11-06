package io.zer0.muse.vdserver;

import android.os.Looper;
import android.util.Log;

/**
 * v2.2.1 pantalla virtual:Entrada del servidor.
 *
 * Modo de operación(shell uid):
 * <pre>
 * CLASSPATH=/data/local/tmp/muse-vd-server.jar app_process / io.zer0.muse.vdserver.Main &lt;hostPkg&gt; &lt;handoffToken&gt;
 * </pre>
 *
 * secuencia de inicio:
 *  1. prepareMainLooper(reflexión,app_process sin valor predeterminado main looper)
 *  2. Workarounds.apply(ActivityThread parche,Pase el nombre del paquete del marco./uid verificar)
 *  3. estructura DisplayManager(FakeContext) + ServerService
 *  4. La emisora ​​envía la carpeta al paquete host.
 *  5. El hilo principal Looper.loop se cuelga;El observador inactivo en segundo plano 15 saldrá sin llamar
 */
public final class Main {

    private static final String TAG = "MuseVdServer";

    private Main() {
    }

    public static void main(String[] args) {
        String hostPackage = (args != null && args.length > 0) ? args[0] : null;
        String handoffToken = (args != null && args.length > 1) ? args[1] : null;
        log("main start, hostPackage=" + hostPackage + ", tokenPresent=" + (handoffToken != null));

        try {
            prepareMainLooper();
        } catch (Throwable t) {
            log("prepareMainLooper failed: " + t);
        }

        Workarounds.apply();

        ServerService service;
        try {
            service = new ServerService(new FakeContext());
        } catch (Throwable t) {
            log("ServerService creation failed: " + t);
            System.exit(1);
            return;
        }

        try {
            service.startPublishLoop(hostPackage, handoffToken);
        } catch (Throwable t) {
            log("binder handoff failed: " + t);
        }

        service.startIdleWatcher();
        Looper.loop();
    }

    /** app_process El medio ambiente no está preparado main looper,preparación de reflejos(Los nombres de los métodos son diferentes en diferentes versiones.). */
    private static void prepareMainLooper() throws Exception {
        Class<?> looper = Class.forName("android.os.Looper");
        try {
            looper.getDeclaredMethod("prepareMainLooper").invoke(null);
        } catch (NoSuchMethodException e) {
            looper.getDeclaredMethod("prepare").invoke(null);
        }
    }

    static void log(String message) {
        try {
            Log.i(TAG, message);
        } catch (Throwable ignored) {
            // logcat Permanecer en silencio cuando no esté disponible
        }
    }
}
