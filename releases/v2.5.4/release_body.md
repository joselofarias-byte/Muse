# Muse v2.5.4

Esta actualización cambia Liquid Glass de una solución de autopintura a un motor de renderizado dedicado y, simultáneamente, actualiza la cadena de herramientas Compose.

## Vidrio líquido (reelaborado)

- **Cambiar a un motor dedicado**: Utilice un fondo kyant para el glaseado (desenfoque + aumento de saturación + refracción y dispersión + borde resaltado + proyección) y utilice RuntimeShader para vaso de agua. La apariencia se actualiza desde un "bloque gris translúcido" hasta vidrio real.
- **Encuadre de fondo global**:Vidrio del fondo de la página (imagen/Degradado) Vista en vivo, que revela un fondo dinámico a medida que se desplaza, en lugar de una pintura plana estática.
- **Color de contenido adaptable**: El título, el icono y el texto de la isla se convierten automáticamente a colores claros sobre fondos oscuros, y se utilizan colores oscuros sobre fondos claros para resolver el problema del texto poco claro causado por el vidrio anterior que es demasiado transparente.
- **Bajar de versión inferior**:Android 13 Lo siguiente vuelve automáticamente al vidrio aproximado con gradiente + bordes resaltados, sin afectar la usabilidad.
- **Las burbujas de mensajes todavía usan vidrio liviano**: Docenas de burbujas en una pantalla están realmente borrosas sin moverse, lo que garantiza un desplazamiento fluido.

## Actualización de la cadena de herramientas

- Compose 1.7 → 1.10.4,compileSdk 35 → 36.
- Marco de prueba actualizado para adaptarse al nuevo SDK.

## Reparación

- Se corrigió un bloqueo causado por el bucle de dependencia entre la capa de vidrio y la representación de la capa de contenido al ingresar a la página de conversación (que se muestra como salir inmediatamente después de abrir la sesión sin dejar un registro de bloqueo).
- Se solucionó el problema por el cual el cristal de la página de detalles del chat grupal no tomaba el fondo de esta página (alineado con el comportamiento del chat único).

## Descripción

- El vidrio líquido está desactivado de forma predeterminada y se puede encontrar en Configuración → Apariencia → "Liquid Glass" se abre y ajusta la marcha y la intensidad.
- Este paquete solo se construye localmente y se utiliza para la verificación de la apariencia y el funcionamiento de la máquina real. No se ha publicado en GitHub Release.
