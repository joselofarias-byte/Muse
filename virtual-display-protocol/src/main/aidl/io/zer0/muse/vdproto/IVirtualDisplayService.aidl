// v2.2.1 pantalla virtual:Contrato de servidor(AIDL).
//
// Servidor: io.zer0.muse.vdserver.Main Se inició el proceso shell uid app_process(independiente jar).
// cliente: io.zer0.muse.automation.vdisplay.VirtualDisplayClient(proceso de solicitud principal),
//         Después de pasar el IBinder por la transmisión, llámelo a través de esta interfaz.
//
// principios de diseño:
//  - Todos regresan sincrónicamente(Ninguno oneway),La persona que llama obtiene el resultado confirmado.
//  - La captura de pantalla devuelve bytes de imagen(Codificado en JPEG en alta resolución.,Evitar el límite de transacciones de 1 MB de Binder;
//    Retorno del fracaso null);Ancho de pantalla virtual recomendado <= 1080, altura <= 1920
//  - La inyección de entrada no utiliza esta interfaz(usar `input -d <displayId>` shell comando,Reutilizar ejecutores existentes)
package io.zer0.muse.vdproto;

interface IVirtualDisplayService {

    // Asegúrese de que existan pantallas virtuales compatibles(Reutilizar el mismo tamaño;Solo reconstruya esta pantalla compatible cuando cambie el tamaño). Regresar displayId;Devuelve -1 en caso de error.
    int ensureDisplay(int width, int height, int dpi);

    // Crea una pantalla independiente sin reutilización/Reemplace una pantalla compatible u otra pantalla independiente; la persona que llama debe destruir la pantalla al final de la tarea.
    // active display -1 se devuelve cuando el número alcanza el límite del servidor o falla la creación.
    int createDisplay(int width, int height, int dpi);

    // Inicie la aplicación en la pantalla virtual especificada(El servidor analiza la actividad del iniciador como shell y am start --display).
    // Devuelve verdadero en caso de éxito.
    boolean launchApp(String packageName, int displayId);

    // Destruye la pantalla virtual especificada(Ignorar si no existe).
    void destroyDisplay(int displayId);

    // Capturar un fotograma de la pantalla virtual especificada PNG(tiempo de espera/Retorno del fracaso null). También se utiliza para mantenerse con vida.
    byte[] requestScreenshot(int displayId);

    // Detección de supervivencia del servidor(Actualice también la marca de tiempo de keepalive).
    boolean isAlive();
}
