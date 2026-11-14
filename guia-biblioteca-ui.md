# Biblioteca de interfaz de Muse

Esta guía unifica cómo se escriben las páginas, los diálogos, los formularios y los mensajes de estado. En una página nueva, reutiliza primero lo que hay en `app/src/main/java/io/zer0/muse/ui/common`. No vuelvas a armar un contenedor parecido dentro de una pantalla de producto.

## Qué componente usar

| Situación | Componente |
| --- | --- |
| Eliminar, borrar o confirmar algo simple | `MuseDialog` |
| Formulario corto, de 1 a 4 campos | `MuseFormDialog` |
| Formularios largos, listas o parámetros de una herramienta | `MuseBottomSheet` |
| Elegir proveedor, modelo, idioma u otra lista por la que se navega | `MuseSelectionSheet` |
| Varios pasos, una vista previa compleja, o el navegador y los medios | Página propia, o un `Dialog` a pantalla completa |
| Volver al inicio de una página con título | `MuseTopBar` |
| Grupos de ajustes e interruptores | `SettingsGroup`, `SettingsItemRow`, `SettingsSwitchRow` |
| Texto | `MuseTextField` |
| Un acierto o un fallo breve | `MuseToast` o `MuseSnackbar` |
| Vacío, error o carga | `MuseEmptyState`, `MuseErrorStateBox`, `MuseLoadingState` |
| Una acción con icono | Icono de Material o Tabler más `IconButton`, siempre con `contentDescription` |

## Reglas

- No agregues un `AlertDialog` nativo de Material 3. Las confirmaciones y los formularios cortos van en `MuseDialog` o `MuseFormDialog`.
- En una pantalla de producto no uses `ModalBottomSheet` directo. El panel inferior es `MuseBottomSheet`. Su marco mide los márgenes y dibuja la máscara; la pantalla no tiene que recalcular la ventana.
- `DropdownMenu` solo sirve para pocas opciones, pegadas al control que las abre. Una lista de proveedores, modelos o idiomas va en `MuseSelectionSheet`.
- Un formulario de varios campos no se parte en varios diálogos. Mejor un panel con título, descripción, campos agrupados y las acciones fijas abajo.
- El botón Guardar tiene que mostrar cuándo está desactivado, cuándo está en curso y cuándo falló. No cierres el diálogo al tocarlo y escribas en silencio.
- Si al abrir la pantalla el foco debe ir al campo, usa `MuseTextField(focusRequester = ...)`. No cuelgues el `FocusRequester` solo del contenedor de afuera.
- El estado de la página va en `rememberSaveable`. Un valor temporal de un campo va en `remember`. El estado de una fila de lista tiene que depender de una clave estable.
- Los bloques de página usan primero `MuseIsland`, `MuseSurface` o `SettingsGroup`. No inventes radios, sombras ni espacios en cada pantalla.
- Colores, radios, espacios y tamaños táctiles salen de los tokens del tema. El objetivo táctil no puede ser más chico que el token del proyecto.
- Todo diálogo, panel y capa a pantalla completa se cierra con Atrás, con un toque afuera o con un botón Cerrar. No puede quedar una máscara invisible.
- El ancho máximo de un diálogo es 320 dp, y en una pantalla angosta quedan al menos 24 dp a cada lado. El botón se ve compacto, pero el área de toque mide al menos 48 dp. En una acción corta, cancelar va a la izquierda y confirmar a la derecha. Con letra grande, los botones pasan a una columna.
- La máscara modal se dibuja por fuera de las `navigationBars`. Un diálogo propio no vuelve a pintar la barra del sistema ni su color de protección. El contraste de los iconos del sistema sigue al host.
- En `CardGroup`, el título del grupo se alinea a 16 dp dentro de la tarjeta. No des por hecho el margen de 56 dp del ancho de un icono. Entre `SectionLabel` y el título de `CardGroup` quedan 20 dp después del bloque anterior.
- Si toda la fila abre el detalle y a la derecha ya hay «Más», no pongas además una flecha. El estado y el resultado de una prueba van debajo del título. El texto auxiliar no puede achicar el título.
- Una grilla de varias imágenes usa miniaturas cuadradas con 6 dp de separación. Al tocar, se ve la imagen completa. Una sola imagen sigue mostrando su proporción.

## Orden para migrar

1. Reemplaza primero los `AlertDialog` y los `ModalBottomSheet` sueltos.
2. Junta en la misma página los diálogos repetidos de agregar, editar y cambiar el nombre.
3. Al final ajusta el detalle: jerarquía de títulos, orden de los botones, teclado, animación y estados vacíos.

## Cómo está hoy

- `AlertDialog` nativo: ninguno.
- Formulario corto unificado: `MuseFormDialog`.
- Lista de selección unificada: `MuseSelectionSheet`. Ya se usa en cinco entradas, entre ellas modelo de imagen, modelo de video, voz y menús desplegables.
- La grabación rápida usa `MuseBottomSheet`.
- El menú de pulsación larga del chat y del chat grupal usa `MusePopover`. Se ancla al punto de la pulsación y se acomoda si no hay lugar.
- Siguen como `Dialog` propio el panel de comandos, los medios a pantalla completa, el visor del navegador, el menú contextual del escritorio y la confirmación al editar una delegación.
- En el producto, `ModalBottomSheet` y `OutlinedTextField` sueltos están en cero. El campo unificado puede pedir el foco por dentro.

## Margen horizontal

El texto de la izquierda llega al borde por dos motivos: el margen horizontal del contenedor se forzó a 0, o a un hermano le falta el suyo. Las dos cosas son un defecto.

### Reglas

| Nivel | Margen izquierdo | De dónde sale |
| --- | --- | --- |
| Texto de página, tarjeta o fila | 16 dp | `MusePaddings.screen` |
| Contenido de `MuseBottomSheet` | 16 dp | Valor por defecto. Quien lo llama no vuelve a pasar `horizontalPadding` |
| `SettingsGroup` y `SettingsItemRow` | Ya está en el componente | No envuelvas la página con otro padding horizontal |
| Título y atrás de la barra superior | 16 dp | Lo trae `MuseTopBar` |
| `MuseFloatingActionMenu` | 8 dp dentro del panel | Ya está en el componente |
| Icono y texto en la misma línea | 10–12 dp | `MusePaddings.auxGap` |

En una misma pantalla, el borde izquierdo de todo el texto tiene que caer en la misma línea. La única excepción son los 8 dp de más dentro de un panel.

### Lista de control

- [ ] El título y el subtítulo del mismo bloque usan el mismo margen. El fallo más común: el título está en un `Row` con `padding(horizontal = screen)` y el subtítulo es un hermano que se quedó sin margen.
- [ ] No pases `horizontalPadding = 0.dp` al panel inferior.
- [ ] No le sumes al contenido del panel otro `padding(horizontal = ...)`. Si no, el margen se duplica (16 + 20 = 36 dp).
- [ ] En una lista de «una tarjeta redondeada por fila», como el menú de pulsación larga o el menú de más, la tarjeta deja margen a los dos lados y no toca el borde.
- [ ] Una galería horizontal o un medio a pantalla completa que de verdad tenga que ir al borde lo dice en el código, con el motivo.

### Control automático

`ci/script/check_horizontal_inset.py` rechaza un margen explícito en cero: `horizontalPadding = 0.dp`, `padding(horizontal = 0.dp)`, `PaddingValues(horizontal = 0.dp)` y `contentPadding = 0.dp`. Si una línea tiene que ir al borde, ciérrala con `// inset-guard: allow` y explica por qué. Si no, CI falla.
