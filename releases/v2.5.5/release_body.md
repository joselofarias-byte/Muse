# Muse v2.5.5

Esta ronda se centra en reparar problemas relacionados con el vidrio y los sub-asistentes, y en unificar la forma del riel superior.

## vidrio liquido

- **Arreglar "Aún funciona después de cerrar"**: Anteriormente, el interruptor del vidrio solo juzgaba la intensidad, no el modo, lo que provocaba que el vidrio aún permaneciera en el estado "apagado" siempre que la intensidad no fuera 0. El modo ahora está "apagado", lo que significa que está completamente cerrado.
- **Reparar la falta de respuesta de la página de configuración**: El interruptor de modo (apagado/agua/escarchado) y el control deslizante de intensidad no tenían efecto antes, pero ahora están vinculados.
- **Reparación de vidrio laminado elástico**: Se han reparado la máscara negra y el agujero cuadrado transparente del menú Más; Se ha agregado la textura de vidrio del menú Plus.

## Barra superior y campo de entrada

- **La barra superior se unifica en una gran isla.**: La barra superior del chat individual ha cambiado a la misma forma que el chat grupal, con una isla grande que contiene la tecla de retorno/título/más tecla.
- **Tres modos de cristal con la misma forma.**: La forma de la isla grande en la barra superior ya no cambia con el interruptor, solo el material cambia con el modo (originalmente era una nueva forma solo cuando se encendía el vidrio).
- **Cambiar el botón de enviar a avión de papel**: El chat único y el chat grupal están unificados.

## Reparación

- **Las tarjetas de planes ya no rotan permanentemente**: El plan anterior era promover la actualización activa del modelo de dependencia. Cuando el ciclo de la herramienta terminaba de manera anormal, el usuario detenía o reingresaba a la sesión, nadie la terminaba y la tarjeta siempre se detenía a la mitad. Ahora las tres rutas colocarán los pasos inacabados en el estado final "Cancelado".

## sub-asistente

- **La interfaz converge en un solo lugar.**: Elimina la pequeña ventana flotante en el lado derecho y la barra de delegación en la parte superior. La información del subasistente se unifica en una tarjeta de tareas expandible dentro del flujo de conversación. Puede comprobar el progreso y cancelar la tarea.

## Descripción

- La versión 110 de la base de datos, igual que la v2.5.4, se puede actualizar directamente sin perder datos.
- Este paquete solo se crea localmente para pruebas en máquinas reales y no se ha publicado en GitHub Release.
