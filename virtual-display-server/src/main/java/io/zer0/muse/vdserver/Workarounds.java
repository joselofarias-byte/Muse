package io.zer0.muse.vdserver;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;

/**
 * v2.2.1 pantalla virtual:framework Compatible con parches(best-effort,Solo falla al iniciar sesión).
 *
 * app_process El proceso no pasa por la ruta de inicialización normal de la aplicación de zygote. Algunos componentes del marco
 * (Por ejemplo, DisplayManager se basa internamente en el estado local del subproceso de ActivityThread.)Necesidad de estandarizar
 * ActivityThread singleton"suplemento"progreso. Todos los pasos son independientes. try/catch —— Diferentes campos de ROM/versión
 * Saltar automáticamente si falta,En lugar de permitir que el servidor no se inicie.
 */
public final class Workarounds {

    private Workarounds() {
    }

    public static void apply() {
        mendActivityThread();
    }

    /**
     * v2.3.2: este método**deliberadamente**Acceso a miembros privados del marco —— De esto se trata el parche de compatibilidad(Ver comentarios de clase),
     * Y cada paso es independiente try/catch(campo/Se omite la construcción faltante,Nunca permita que el servidor no se inicie).
     * Comprobaciones de pelusa relacionadas(BlockedPrivateApi / SoonBlockedPrivateApi)En el módulo build.gradle.kts
     * Exento por módulo y exponga los motivos,En lugar de suprimirlo aquí y allá.
     */
    private static void mendActivityThread() {
        try {
            Class<?> activityThreadClass = Class.forName("android.app.ActivityThread");
            Constructor<?> constructor = activityThreadClass.getDeclaredConstructor();
            constructor.setAccessible(true);
            Object activityThread = constructor.newInstance();

            Field currentThread = activityThreadClass.getDeclaredField("sCurrentActivityThread");
            currentThread.setAccessible(true);
            currentThread.set(null, activityThread);

            try {
                Field systemThread = activityThreadClass.getDeclaredField("mSystemThread");
                systemThread.setAccessible(true);
                systemThread.setBoolean(activityThread, true);
            } catch (Throwable ignored) {
                // Ignorar si el campo no existe
            }

            if (android.os.Build.VERSION.SDK_INT >= 31) {
                try {
                    Class<?> controllerClass = Class.forName("android.app.ConfigurationController");
                    Constructor<?> controllerCtor = controllerClass.getDeclaredConstructor(activityThreadClass);
                    controllerCtor.setAccessible(true);
                    Object controller = controllerCtor.newInstance(activityThread);
                    Field controllerField = activityThreadClass.getDeclaredField("mConfigurationController");
                    controllerField.setAccessible(true);
                    controllerField.set(activityThread, controller);
                } catch (Throwable ignored) {
                    // Se ignora cuando cambia la configuración de versiones superiores de ConfigurationController.
                }
            }

            Main.log("Workarounds: ActivityThread mended");
        } catch (Throwable t) {
            Main.log("Workarounds skipped: " + t);
        }
    }
}
