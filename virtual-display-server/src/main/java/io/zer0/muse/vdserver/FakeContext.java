package io.zer0.muse.vdserver;

import android.content.Context;
import android.content.ContextWrapper;

/**
 * v2.2.1 pantalla virtual:Contexto de enmascaramiento del proceso.
 *
 * El servidor se ejecuta como uid de shell.,Pero el lado del marco(DisplayManagerService / WMS)verificará
 * "El nombre del paquete de la persona que llama es coherente con el uid de llamada". Esta clase se disfraza de com.android.shell(uid 2000 de
 * paquete oficial),Deje que el DisplayManager construido mediante reflexión pase la validación.
 */
public final class FakeContext extends ContextWrapper {

    /** shell (adb) uid,Coincide con el paquete com.android.shell. */
    public static final int SHELL_UID = 2000;

    private static final String SHELL_PACKAGE = "com.android.shell";

    public FakeContext() {
        super(null);
    }

    @Override
    public String getPackageName() {
        return SHELL_PACKAGE;
    }

    @Override
    public String getOpPackageName() {
        return SHELL_PACKAGE;
    }

    @Override
    public Context getApplicationContext() {
        return this;
    }

    @Override
    public Context createDisplayContext(android.view.Display display) {
        return this;
    }

    /**
     * ContextWrapper De forma predeterminada, getSystemServiceName/getSystemService se delega a mBase,
     * Y si mBase es nulo, se generará NPE. Excepto DisplayManager,Reserva unificada para"contexto del sistema"
     * (Proporcionado por ActivityThread parcheado de Workarounds)Analizar servicios reales del sistema.
     * (Como por ejemplo UserManager —— DisplayManager Inspección interna isVisibleBackgroundUsersSupported).
     */
    @Override
    public String getSystemServiceName(Class<?> serviceClass) {
        if (serviceClass == android.hardware.display.DisplayManager.class) {
            return android.content.Context.DISPLAY_SERVICE;
        }
        android.content.Context ctx = systemContext();
        return ctx != null ? ctx.getSystemServiceName(serviceClass) : null;
    }

    @Override
    public Object getSystemService(String name) {
        android.content.Context ctx = systemContext();
        return ctx != null ? ctx.getSystemService(name) : null;
    }

    // Nota:Context.getSystemService(Class) para final(Internamente, se ajustarán las dos anulaciones de Nombre/Cadena anteriores.),
    // Por lo tanto no es necesario(Tampoco puede)Anular en esta clase.

    /** Tome prestado el ActivityThread parcheado para obtener el contexto del sistema(Sólo se utiliza para resolver objetos de servicio del sistema.). */
    private static android.content.Context systemContext() {
        try {
            Class<?> activityThreadClass = Class.forName("android.app.ActivityThread");
            java.lang.reflect.Field field = activityThreadClass.getDeclaredField("sCurrentActivityThread");
            field.setAccessible(true);
            Object activityThread = field.get(null);
            if (activityThread == null) {
                return null;
            }
            java.lang.reflect.Method method = activityThreadClass.getMethod("getSystemContext");
            return (android.content.Context) method.invoke(activityThread);
        } catch (Throwable t) {
            Main.log("systemContext unavailable: " + t);
            return null;
        }
    }

    @Override
    public android.content.AttributionSource getAttributionSource() {
        // v2.3.2: AttributionSource Es un tipo sólo disponible en API 31;La versión inferior regresa directamente null,
        // Ya no depende de"new Una clase inexistente arroja NoClassDefFoundError nuevamente catch"para controlar el proceso
        // (Comportamiento sin cambios:Resulta que <31 Lo mismo que arriba, usando catch para regresar null,Pero luego la pelusa informará NewApi).
        if (android.os.Build.VERSION.SDK_INT < 31) {
            return null;
        }
        try {
            android.content.AttributionSource.Builder builder =
                    new android.content.AttributionSource.Builder(SHELL_UID);
            builder.setPackageName(SHELL_PACKAGE);
            return builder.build();
        } catch (Throwable t) {
            return null;
        }
    }
}
