package io.zer0.muse.vdserver;

import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import io.zer0.muse.vdproto.IVirtualDisplayService;

import java.io.ByteArrayOutputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * v2.2.1 pantalla virtual:Implementación del lado del servidor.
 *
 * El único lugar que tiene una fuerte referencia a VirtualDisplay —— Mientras este proceso sobreviva,La pantalla virtual no se reciclará.
 * Toma una captura de pantalla y listo"Conexión directa de la superficie de visualización ImageReader"(Sin codificador, sin pantalla espejo):Al crear una pantalla virtual, ponga
 * ImageReader La superficie se utiliza directamente como superficie de salida de la pantalla.,requestScreenshot Capture el último fotograma y conviértalo a JPEG.
 *
 * ciclo de vida:idle watcher 15s Salir sin llamar(El lado principal de la aplicación es responsable de reiniciar a pedido.).
 */
public final class ServerService extends IVirtualDisplayService.Stub {

    private static final long IDLE_TIMEOUT_MS = 15_000L;
    private static final long IDLE_SCAN_INTERVAL_MS = 5_000L;
    private static final long PUBLISH_INTERVAL_MS = 5_000L;
    private static final long SCREENSHOT_TIMEOUT_MS = 1_000L;
    private static final long SHELL_TIMEOUT_MS = 10_000L;
    private static final int MAX_JPEG_BYTES = 900_000;
    private static final int MAX_ACTIVE_DISPLAYS = 3;

    private final DisplayManager displayManager;
    private final Map<Integer, DisplayState> displays = new HashMap<>();
    private int compatibleDisplayId = -1;

    private volatile long lastActiveAt = System.currentTimeMillis();

    public ServerService(FakeContext context) throws Exception {
        this.displayManager = createDisplayManager(context);
    }

    // ── IVirtualDisplayService contrato ─────────────────────────────────────────

    @Override
    public synchronized int ensureDisplay(int width, int height, int dpi) {
        touch();
        int w = align16(width);
        int h = align16(height);
        DisplayState current = displays.get(compatibleDisplayId);
        if (current != null && w == current.width && h == current.height && dpi == current.dpi) {
            return current.id;
        }
        if (compatibleDisplayId >= 0) {
            destroyDisplayLocked(compatibleDisplayId);
        }
        DisplayState created = createDisplayLocked(w, h, dpi, "muse-vd-compat");
        if (created == null) return -1;
        compatibleDisplayId = created.id;
        Main.log("ensureDisplay " + w + "x" + h + " dpi=" + dpi + " -> id=" + created.id);
        return created.id;
    }

    @Override
    public synchronized int createDisplay(int width, int height, int dpi) {
        touch();
        int w = align16(width);
        int h = align16(height);
        if (displays.size() >= MAX_ACTIVE_DISPLAYS) {
            Main.log("createDisplay rejected: active display limit " + MAX_ACTIVE_DISPLAYS);
            return -1;
        }
        DisplayState created = createDisplayLocked(w, h, dpi, "muse-vd-agent");
        if (created == null) return -1;
        Main.log("createDisplay " + w + "x" + h + " dpi=" + dpi + " -> id=" + created.id);
        return created.id;
    }

    private DisplayState createDisplayLocked(int width, int height, int dpi, String name) {
        if (width < 320 || width > 1920 || height < 320 || height > 2560 || dpi < 120 || dpi > 640) {
            Main.log("create display rejected: invalid metrics " + width + "x" + height + " dpi=" + dpi);
            return null;
        }
        if (displays.size() >= MAX_ACTIVE_DISPLAYS) {
            Main.log("create display rejected: active display limit " + MAX_ACTIVE_DISPLAYS);
            return null;
        }
        ImageReader reader = null;
        VirtualDisplay virtualDisplay = null;
        try {
            reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3);
            virtualDisplay = displayManager.createVirtualDisplay(
                    name, width, height, dpi, reader.getSurface(), computeFlags());
            if (virtualDisplay == null || virtualDisplay.getDisplay() == null) {
                if (virtualDisplay != null) virtualDisplay.release();
                reader.close();
                return null;
            }
            int id = virtualDisplay.getDisplay().getDisplayId();
            DisplayState state = new DisplayState(id, width, height, dpi, virtualDisplay, reader);
            displays.put(id, state);
            applyImePolicy(id);
            return state;
        } catch (Throwable t) {
            Main.log("create display failed: " + t);
            try {
                if (virtualDisplay != null) virtualDisplay.release();
            } catch (Throwable ignored) {
                // Preserve the original creation failure.
            }
            try {
                if (reader != null) reader.close();
            } catch (Throwable ignored) {
                // Preserve the original creation failure.
            }
            return null;
        }
    }

    @Override
    public synchronized boolean launchApp(String packageName, int id) {
        touch();
        if (packageName == null || !packageName.matches("[a-zA-Z][a-zA-Z0-9_.]*")) {
            return false;
        }
        if (!displays.containsKey(id)) {
            throw new IllegalStateException("display not found: " + id);
        }
        try {
            String component = resolveLauncherComponent(packageName);
            if (component == null) {
                Main.log("launchApp: no launcher activity for " + packageName);
                return false;
            }
            // --activity-multiple-task(FLAG_ACTIVITY_MULTIPLE_TASK): Fuerza la apertura de una nueva tarea en la pantalla virtual de destino.
            // Solo cuando se usa --display,Las tareas existentes serán“Reutilizar”Volver a la pantalla original(task reuse),La aplicación no puede ingresar a la pantalla virtual en absoluto.
            // Nota:Este marco no tiene la opción --actividad-nueva-tarea(am Informe Unknown option).
            String command = "am start --display " + id
                    + " --activity-multiple-task -n " + component;
            String output = execShellCapture(command);
            boolean ok = !output.contains("Error:")
                    && !output.contains("Error type")
                    && !output.contains("Exception")
                    && !output.contains("Warning: Activity not started");
            Main.log("launchApp " + component + " on display " + id + " -> " + ok + " | "
                    + output.trim().replace('\n', '~'));
            return ok;
        } catch (Throwable t) {
            Main.log("launchApp failed: " + t);
            return false;
        }
    }

    @Override
    public synchronized void destroyDisplay(int id) {
        touch();
        destroyDisplayLocked(id);
    }

    private void destroyDisplayLocked(int id) {
        DisplayState state = displays.remove(id);
        if (state == null) return;
        if (compatibleDisplayId == id) compatibleDisplayId = -1;
        state.release();
    }

    @Override
    public byte[] requestScreenshot(int id) {
        touch();
        // v2.6.7 fix (AUDIT F26): No coloque esperas de bloqueo en bloqueos globales.
        //
        // La implementación anterior de todo el método está sincronizada y la implementación interna Thread.sleep(20) El sondeo tarda hasta 1 segundo;
        // La misma cerradura fue cerrada en este segundo. ensureDisplay / createDisplay / launchApp /
        // destroyDisplay Todos alineados,Agent Capturas de pantalla continuas (GUI El bucle se corta en cada paso)
        // Se muestra como“Toda la automatización atascada”. Ahora el bloqueo solo es necesario al recuperar DisplayState.
        DisplayState state;
        synchronized (this) {
            state = displays.get(id);
        }
        if (state == null) {
            // Let the client distinguish a stale display ID from a valid display
            // that simply has not produced a frame yet.
            throw new IllegalStateException("display not found: " + id);
        }
        Image image = null;
        try {
            long deadline = System.currentTimeMillis() + SCREENSHOT_TIMEOUT_MS;
            while (image == null && System.currentTimeMillis() < deadline) {
                image = state.imageReader.acquireLatestImage();
                if (image == null) {
                    Thread.sleep(20L);
                }
            }
            if (image == null) {
                return null;
            }
            byte[] jpeg = imageToJpeg(image);
            // El bloqueo se liberó, pero es posible que la pantalla se haya destruido mientras tanto: verifique una vez para evitar que los fotogramas regresen a una pantalla muerta.
            synchronized (this) {
                if (!displays.containsKey(id)) return null;
            }
            return jpeg;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Throwable t) {
            Main.log("requestScreenshot failed: " + t);
            return null;
        } finally {
            if (image != null) {
                try {
                    image.close();
                } catch (Throwable ignored) {
                    // No cerrar no afecta los fotogramas generados.
                }
            }
        }
    }

    @Override
    public boolean isAlive() {
        touch();
        return true;
    }

    // ── ciclo de vida ────────────────────────────────────────────────────────────

    public void startIdleWatcher() {
        Handler handler = new Handler(Looper.getMainLooper());
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (System.currentTimeMillis() - lastActiveAt > IDLE_TIMEOUT_MS) {
                    Main.log("idle timeout, exiting");
                    System.exit(0);
                }
                handler.postDelayed(this, IDLE_SCAN_INTERVAL_MS);
            }
        }, IDLE_SCAN_INTERVAL_MS);
    }

    /**
     * Reedición periódica binder(cada 5s). El servidor se cerrará automáticamente después de estar inactivo durante 15 segundos.,Entonces el número de reintentos está limitado.
     * propósito:El proceso del host puede comenzar más tarde que el servidor./Reiniciar(instrument/colapsar),Se perderá una sola transmisión.;
     * El reenvío permite que el registro converja en cualquier secuencia de inicio. Nota:La transmisión no actualiza lastActiveAt.
     */
    public void startPublishLoop(String hostPackage, String handoffToken) {
        Handler handler = new Handler(Looper.getMainLooper());
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                BinderHandoff.publish(ServerService.this, hostPackage, handoffToken);
                handler.postDelayed(this, PUBLISH_INTERVAL_MS);
            }
        }, 0L);
    }

    private void touch() {
        lastActiveAt = System.currentTimeMillis();
    }

    private static final class DisplayState {
        final int id;
        final int width;
        final int height;
        final int dpi;
        final VirtualDisplay virtualDisplay;
        final ImageReader imageReader;

        DisplayState(int id, int width, int height, int dpi, VirtualDisplay virtualDisplay, ImageReader imageReader) {
            this.id = id;
            this.width = width;
            this.height = height;
            this.dpi = dpi;
            this.virtualDisplay = virtualDisplay;
            this.imageReader = imageReader;
        }

        void release() {
            try {
                virtualDisplay.release();
            } catch (Throwable ignored) {
                // Resource release is best-effort; server exit remains a final fallback.
            }
            try {
                imageReader.close();
            } catch (Throwable ignored) {
                // Resource release is best-effort; server exit remains a final fallback.
            }
        }
    }

    // ── herramientas internas ────────────────────────────────────────────────────────────

    private static DisplayManager createDisplayManager(FakeContext context) throws Exception {
        Class<?> cls = Class.forName("android.hardware.display.DisplayManager");
        try {
            java.lang.reflect.Constructor<?> ctor =
                    cls.getDeclaredConstructor(android.content.Context.class);
            ctor.setAccessible(true);
            return (DisplayManager) ctor.newInstance(context);
        } catch (NoSuchMethodException notFound) {
            Class<?> globalCls = Class.forName("android.hardware.display.DisplayManagerGlobal");
            Object global = globalCls.getMethod("getInstance").invoke(null);
            java.lang.reflect.Constructor<?> ctor =
                    cls.getDeclaredConstructor(android.content.Context.class, globalCls);
            ctor.setAccessible(true);
            return (DisplayManager) ctor.newInstance(context, global);
        }
    }

    /**
     * flags Agregar por versión. El valor posicional parcial es @SystemApi(No en el frasco público del SDK,No se puede hacer referencia a él en tiempo de compilación.),
     * Aquí usamos literales de bits consistentes con el marco.;PUBLIC/PRESENTATION/OWN_CONTENT_ONLY Tome constantes públicas.
     */
    private static int computeFlags() {
        int flags = DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC
                | DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION
                | DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY
                | (1 << 6)   // SUPPORTS_TOUCH
                | (1 << 7)   // ROTATES_WITH_CONTENT
                | (1 << 8);  // DESTROY_CONTENT_ON_REMOVAL
        if (Build.VERSION.SDK_INT >= 33) {
            flags |= (1 << 10)  // TRUSTED
                    | (1 << 11) // OWN_DISPLAY_GROUP
                    | (1 << 12) // ALWAYS_UNLOCKED
                    | (1 << 13); // TOUCH_FEEDBACK_DISABLED
        }
        if (Build.VERSION.SDK_INT >= 34) {
            flags |= (1 << 14)  // OWN_FOCUS
                    | (1 << 15); // DEVICE_DISPLAY_GROUP
        }
        return flags;
    }

    /** La política IME de la pantalla virtual está configurada en LOCAL(El teclado se muestra en la pantalla virtual.);El error sólo se registra. */
    private static void applyImePolicy(int id) {
        try {
            Class<?> windowManagerGlobal = Class.forName("android.view.WindowManagerGlobal");
            Object windowManagerService = windowManagerGlobal.getMethod("getWindowManagerService").invoke(null);
            java.lang.reflect.Method target = null;
            for (java.lang.reflect.Method method : windowManagerService.getClass().getMethods()) {
                String name = method.getName();
                if (("setDisplayImePolicy".equals(name) || "setShouldShowIme".equals(name))
                        && method.getParameterTypes().length == 2) {
                    target = method;
                    if ("setDisplayImePolicy".equals(name)) {
                        break;
                    }
                }
            }
            if (target == null) {
                return;
            }
            if ("setDisplayImePolicy".equals(target.getName())) {
                // WindowManagerPolicyConstants.DISPLAY_IME_POLICY_LOCAL = 1
                target.invoke(windowManagerService, id, 1);
            } else {
                target.invoke(windowManagerService, id, true);
            }
        } catch (Throwable t) {
            Main.log("applyImePolicy skipped: " + t);
        }
    }

    private static int align16(int value) {
        return (value + 15) / 16 * 16;
    }

    /** Ejecutar un solo comando como shell,Devolver salida combinada(stdout+stderr);La excepción devuelve un texto de error. */
    private static String execShellCapture(String command) {
        try {
            Process process = Runtime.getRuntime().exec(new String[] {"sh", "-c", command});
            String out = drain(process.getInputStream());
            String err = drain(process.getErrorStream());
            if (!process.waitFor(SHELL_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                Main.log("execShellCapture timed out after " + SHELL_TIMEOUT_MS + "ms");
                return "";
            }
            return out + err;
        } catch (Throwable t) {
            Main.log("execShellCapture failed: " + t);
            return "";
        }
    }

    /** Actividad del iniciador de análisis(pkg/.Act o pkg/com.full.Act);Devuelve nulo si falla el análisis. */
    private static String resolveLauncherComponent(String packageName) {
        try {
            Process process = Runtime.getRuntime().exec(new String[] {
                    "sh", "-c", "cmd package resolve-activity --brief " + packageName,
            });
            String output = drain(process.getInputStream());
            drain(process.getErrorStream());
            if (!process.waitFor(SHELL_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                Main.log("resolveLauncherComponent timed out after " + SHELL_TIMEOUT_MS + "ms");
                return null;
            }
            String component = null;
            for (String line : output.split("\\r?\\n")) {
                String trimmed = line.trim();
                if (trimmed.contains("/")) {
                    component = trimmed;
                }
            }
            if (component == null) {
                return null;
            }
            // Antiinyección:Los nombres de los componentes solo permiten nombres de paquetes/Caracteres del nombre de la actividad
            if (!component.matches("[a-zA-Z0-9_.]+/[a-zA-Z0-9_.$]+")) {
                return null;
            }
            return component;
        } catch (Throwable t) {
            Main.log("resolveLauncherComponent failed: " + t);
            return null;
        }
    }

    private static String drain(java.io.InputStream stream) {
        try {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int read;
            while ((read = stream.read(buffer)) >= 0) {
                out.write(buffer, 0, read);
            }
            return out.toString("UTF-8");
        } catch (Throwable t) {
            return "";
        }
    }

    private static byte[] compress(Bitmap bitmap, int quality) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out);
        return out.toByteArray();
    }

    /** RGBA_8888 ImageReader marco → JPEG bytes(Incluye acolchado RowStride y barandillas volumétricas.). */
    private static byte[] imageToJpeg(Image image) {
        Image.Plane plane = image.getPlanes()[0];
        int width = image.getWidth();
        int height = image.getHeight();
        int pixelStride = plane.getPixelStride();
        int rowStride = plane.getRowStride();
        int rowPadding = rowStride - pixelStride * width;
        Bitmap padded = Bitmap.createBitmap(
                width + rowPadding / pixelStride, height, Bitmap.Config.ARGB_8888);
        padded.copyPixelsFromBuffer(plane.getBuffer());
        Bitmap cropped = Bitmap.createBitmap(padded, 0, 0, width, height);
        if (cropped != padded) {
            padded.recycle();
        }
        try {
            byte[] bytes = compress(cropped, 80);
            if (bytes.length > MAX_JPEG_BYTES) {
                bytes = compress(cropped, 60);
            }
            return bytes;
        } finally {
            cropped.recycle();
        }
    }
}
