// P3-3: IAccessibilityProvider — UI Interfaz AIDL automatizada.
//
// Servidor: io.zer0.muse.accessibility.MuseAccessibilityService (Se ejecuta en el proceso del servicio de accesibilidad.)
// cliente: io.zer0.muse.tools.system.AccessibilityClient (Ejecutar en el proceso principal,Vincular a través de bindService)
//
// principios de diseño:
//  - Todos los métodos regresan sincrónicamente(Ninguno oneway),Asegúrese de que la persona que llama obtenga un resultado confirmado
//  - String Convención de valor de retorno: Devuelve JSON/texto legible en caso de éxito; Retorno del fracaso "[error] ..." forma
//  - Los parámetros de coordenadas son todas coordenadas absolutas de píxeles de la pantalla.
//  - nodeId ID de ruta devuelta para getUiHierarchy/findFocusedNodeId(Como por ejemplo "0.1.2")
package io.zer0.muse.accessibility;

interface IAccessibilityProvider {

    // Obtener el nivel de UI de la ventana actualmente activa(formato de texto compacto,Un nodo por línea).
    // Contiene cada nodo: path / class / text / content-desc / bounds / clickable / focusable.
    String getUiHierarchy();

    // Realice un gesto de toque en las coordenadas especificadas.
    boolean performClick(int x, int y);

    // Realizar un gesto de pulsación larga en las coordenadas especificadas(Mantenga presionado 500ms).
    boolean performLongPress(int x, int y);

    // Realizar acciones de accesibilidad global(Como por ejemplo GLOBAL_ACTION_BACK / HOME / RECENTS / NOTIFICATIONS).
    boolean performGlobalAction(int actionId);

    // Realizar un gesto de deslizar,de (startX,startY) llegar (endX,endY),duration milisegundos.
    boolean performSwipe(int startX, int startY, int endX, int endY, long duration);

    // Devuelve el ID de ruta del nodo de enfoque de entrada actual.(Para uso de setTextOnNode);Devuelve una cadena vacía si no hay foco.
    String findFocusedNodeId();

    // Establecer texto en el nodo especificado(Pase ACTION_SET_TEXT).
    // nodeId La ruta devuelta por getUiHierarchy/findFocusedNodeId.
    boolean setTextOnNode(String nodeId, String text);

    // Tome una captura de pantalla y guárdela en la ruta especificada(necesidad API 34+,Vuelve la versión inferior false).
    // format: "PNG" o "JPEG".
    boolean takeScreenshot(String path, String format);

    // Si los servicios de accesibilidad están habilitados y conectados(El servidor tiene onServiceConnected y regresa true).
    boolean isAccessibilityServiceEnabled();

    // Obtener el nombre del componente de la actividad actual en primer plano(Como por ejemplo "com.android.settings/.Settings");Desconocido devuelve una cadena vacía.
    String getCurrentActivityName();
}
