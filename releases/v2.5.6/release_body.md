Muse v2.5.6 Versión oficial.

Esta versión incluye todos los cambios desde la versión 2.5.1: revisión del vidrio líquido, formas unificadas de la barra superior y de la barra del sistema, múltiples correcciones de interacción y estabilidad.

## vidrio liquido

- Al cambiar a un motor de renderizado dedicado (no una solución de aproximación autodibujada), la apariencia del vidrio se actualiza de un "bloque gris translúcido" a vidrio real con desenfoque, aumento de saturación, refracción y dispersión; El vaso de agua utiliza un sombreador de refracción en tiempo real.
- Vidrio del fondo de la página (imagen/Degradados) vista en vivo, que revela fondos dinámicos a medida que se desplaza.
- El color del contenido se adapta según la luz y la oscuridad del fondo: el título y el ícono de la isla cambian automáticamente a un color claro sobre un fondo oscuro y usan un color oscuro sobre un fondo claro.
- Solución "Sigue funcionando después de cerrar el cristal": anteriormente, el interruptor solo juzgaba la intensidad, no el modo.
- Se solucionó el problema de que el cambio de modo de vidrio y el control deslizante de intensidad en la página de configuración no surtían efecto.
- Se corrigió la representación anormal del vidrio elastómero (máscara negra y orificio cuadrado transparente); Se agregó textura de vidrio a los paneles inferiores, como el menú del signo más.
- El fondo de cristal de la página de detalles del chat grupal se hunde, alineándose con el comportamiento del chat individual.
- El valor predeterminado se cambia a vidrio esmerilado 68 %: los nuevos usuarios que lo instalen por primera vez y los usuarios de actualización que nunca hayan ajustado manualmente el vidrio tendrán esta configuración predeterminada; los usuarios que lo hayan configurado manualmente no se verán afectados.
- Android 13 Lo siguiente vuelve automáticamente al vidrio aproximado, sin afectar la usabilidad; Las burbujas de mensajes siguen siendo livianas y aproximadas (docenas por pantalla, sin que se produzca un desenfoque de carga pesado).

## Barra superior y campo de entrada

- La barra superior del chat individual se ha cambiado a la misma forma de "isla grande" que el chat grupal: una isla grande en la capa exterior, una cápsula de color sólido en el medio que muestra el título y un cristal en ambos extremos.
- La forma de la gran isla en el riel superior ya no cambia con el interruptor de vidrio, sino que cambia en consecuencia el material de relleno.
- Vuelve a cambiar el botón de enviar a avión de papel.

## barra del sistema

- La barra de estado y la barra de navegación cambian a transparencia real, y el contenido y el fondo de la página se extienden debajo de la barra del sistema.
- Desactive la máscara de contraste de la barra de navegación en Android 10 y versiones posteriores para evitar una barra extra oscura sobre un fondo transparente.
- El color de fondo de la ventana sigue el tema y la parte inferior de la ventana no cubre la página de pantalla completa.
- El panel inferior (más el menú, varias capas emergentes inferiores) se coloca en la parte inferior de la pantalla y el fondo de la ventana ya no está expuesto entre la pantalla y la pantalla.

## Reparación

- Se solucionó el problema de fallas al ingresar a la página de diálogo (causado por el bucle de dependencia entre la representación de la capa de vidrio y la capa de contenido, y no hay un registro de fallas anterior).
- Reparar la tarjeta del plan atascada permanentemente en "En progreso": los pasos inacabados ahora terminan en un estado final "Cancelado" cuando el bucle de la herramienta finaliza de manera anormal, el usuario se detiene o vuelve a ingresar a la sesión.
- Se solucionó el problema por el cual el título de la sesión se mostraba como un marcador de posición sin sentido (como "...“literal).
- Enlaces de documentos de conocimiento del asistente integrado, configuración de persistencia, reproducción de inferencias, configuración del modelo a nivel de sesión, estadísticas de costos y otras optimizaciones internas.

## sub-asistente

- La interfaz converge en un solo lugar: la pequeña ventana flotante en el lado derecho y la barra de delegación en la parte superior se eliminan, y la información del subasistente se unifica en una tarjeta de tareas expandible dentro del flujo de conversación, donde se puede ver el progreso y se pueden cancelar las tareas.

## Otros

- Compose Actualizado a 1.10.4,compileSdk Impulsado a 36.
- La versión 110 de la base de datos, sin cambios desde la v2.5.4, se puede actualizar directamente sin perder datos.

## Instalación

- Paquete universal:Muse_v2.5.6_universal.apk(cualquier dispositivo)
- 64 poco:Muse_v2.5.6_arm64-v8a.apk(la mayoría de los teléfonos celulares modernos)
- 32 poco:Muse_v2.5.6_armeabi-v7a.apk(Equipo más antiguo)
