# Muse UI Especificaciones de mantenimiento de la biblioteca de componentes

Esta especificación se utiliza para unificar páginas de redacción, ventanas emergentes, formularios y comentarios de estado. Las páginas nuevas se reutilizan primero `app/src/main/java/io/zer0/muse/ui/common`, no vuelva a implementar contenedores similares en páginas comerciales.

## selección interactiva

| escena | componentes |
| --- | --- |
| Eliminar, borrar, confirmar simple | `MuseDialog` |
| 1-4 formulario corto con campos | `MuseFormDialog` |
| Formularios largos, listas de selección, parámetros de herramientas. | `MuseBottomSheet` |
| Selección basada en navegación de proveedores, modelos, idiomas y más | `MuseSelectionSheet` |
| Edición de varios pasos, vista previa compleja, navegador/medios de comunicación | Página independiente o pantalla completa dedicada `Dialog` |
| Volver al inicio de la página con título | `MuseTopBar` |
| Configurar agrupaciones y cambiar filas | `SettingsGroup`,`SettingsItemRow`,`SettingsSwitchRow` |
| Entrada de texto | `MuseTextField` |
| éxito de corta duración/Comentarios de falla | `MuseToast` o `MuseSnackbar` |
| Vacío, error, cargando | `MuseEmptyState`,`MuseErrorStateBox`,`MuseLoadingState` |
| Operación de icono | Material/Tabler icono + `IconButton`, debe haber `contentDescription` |

## restricción

- No hay nuevo Material3 nativo `AlertDialog`, confirmación y forma breve están unificadas. `MuseDialog` / `MuseFormDialog`.
- No se utiliza directamente en páginas comerciales ordinarias. `ModalBottomSheet`;panel inferior ir `MuseBottomSheet`. Su shell de diálogo controlado mide uniformemente los recuadros y dibuja máscaras por separado para evitar la barra de navegación. La página comercial no necesita volver a calcular la posición de la ventana.
- `DropdownMenu` Solo para un número reducido y compacto de opciones ancladas junto a los controles de activación; proveedor cruzado/modelo/Se debe utilizar una lista de navegación de idiomas. `MuseSelectionSheet`.
- Los formularios de campos múltiples no deben utilizar una serie de ventanas emergentes independientes; en su lugar, es preferible cambiarlo a un panel de formulario con título, descripción, agrupación de campos y operaciones inferiores fijas.
- El botón Guardar debe tener un estado claro deshabilitado, estado en progreso y retroalimentación de falla; no lo cierres directamente después de hacer clic en él y luego escribe en silencio.
- Si necesita enfocar automáticamente el cuadro de entrada después de abrir la página, use `MuseTextField(focusRequester = ...)`, no pongas `FocusRequester` Cuelga sólo el diseño exterior.
- Para el estado del nivel de página `rememberSaveable`, se utiliza un valor de entrada temporal `remember`;El estado dentro del elemento de la lista debe depender de la clave estable.
- Uso del bloque de páginas `MuseIsland`,`MuseSurface` o `SettingsGroup` Primero, evite crear esquinas redondeadas, sombras y espacios en las páginas de su empresa.
- Los nuevos colores, esquinas redondeadas, espacios y tamaños táctiles dan prioridad a los tokens temáticos; el objetivo de interacción no debe ser más pequeño que el token de objetivo táctil del proyecto.
- Todas las ventanas emergentes, paneles y superposiciones de pantalla completa deben cerrarse con la tecla Atrás, un clic externo o un botón de cerrar explícito sin dejar una máscara invisible.
- Confirme que el ancho máximo de la ventana emergente sea de 320 dp y que los lados de la pantalla estrecha sean de al menos 24 dp; el botón es visualmente compacto pero el área de clic es de al menos 48 dp. La cancelación de la operación corta está a la izquierda, la confirmación está a la derecha y las fuentes grandes vuelven al diseño vertical.
- Las máscaras modales deben excluirse primero `navigationBars` Redibujar; El diálogo independiente prohíbe la superposición secundaria de la plataforma y el color protector de la barra de navegación, y el contraste del icono de la barra del sistema sigue al host.
- `CardGroup` Los títulos de grupo están alineados a 16 dp dentro de la tarjeta sin asumir la sangría de 56 dp del ancho del icono;`SectionLabel` con `CardGroup` El título conserva el espacio de 20 dp después del módulo anterior.
- Se puede hacer clic en toda la fila de administración para ingresar los detalles. Cuando hay "Más" a la derecha, las flechas hacia la derecha ya no se colocan una al lado de la otra; Estado/Los resultados de la prueba se colocan debajo del título y el área auxiliar de ajuste de línea no puede comprimir el título.
- La cuadrícula de múltiples imágenes utiliza miniaturas cuadradas unificadas con un espaciado fijo de 6 dp. Haga clic para obtener una vista previa y ver la imagen original completa; Las imágenes individuales continúan mostrándose en proporciones completas.

## Orden de migración

1. Reemplace el original primero `AlertDialog` y desnudo `ModalBottomSheet`.
2. Fusionar adiciones duplicadas en la misma página/Editar/Cambie el nombre de la ventana emergente.
3. Finalmente maneje los detalles visuales: jerarquía de títulos, orden de botones, evitar el teclado, animaciones y estados vacíos.

## línea de base actual

- Nativo `AlertDialog`:0 en.
- Componentes unificados de formato corto:`MuseFormDialog`.
- Panel de selección unificado:`MuseSelectionSheet`, se ha utilizado para 5 entradas incluyendo modelo de imagen, modelo de video, voz, selección desplegable, etc.
- Configuración de grabación rápida: usar `MuseBottomSheet`.
- Menú de pulsación larga: se utiliza para chat individual y chat grupal `MusePopover`, priorice el posicionamiento del punto de presión y gire automáticamente cuando no haya espacio suficiente.
- permanecer independiente `Dialog`: Borrar escenarios de ventana como panel de comando, medios de pantalla completa, visor de navegador, menú contextual del escritorio, confirmación de modificación de delegación, etc.
- Línea de base actual: Negocios `ModalBottomSheet` Llamado a las 0; negocio raw `OutlinedTextField` 0 Departamento; El cuadro de entrada unificado admite solicitudes de enfoque interno.

## Relleno horizontal (el contenido no debe superponerse)

Casi solo hay dos razones por las que el texto de la izquierda llega al borde de la pantalla: el relleno horizontal del contenedor se presiona explícitamente a 0, o falta el relleno en el elemento hermano. Ambas categorías se consideran defectos.

### reglas

| Jerarquía | margen izquierdo | método de valor |
| --- | --- | --- |
| Texto de página/tarjeta/lista item | 16dp | `MusePaddings.screen` |
| panel inferior (`MuseBottomSheet`) contenido | 16dp | Valor predeterminado, la persona que llama no debe volver a pasarlo `horizontalPadding` |
| establecer grupo (`SettingsGroup` / `SettingsItemRow`) | Incluido en el componente | No envuelva la página con otra capa horizontal. padding |
| Título/regreso de la columna superior | 16dp | por `MuseTopBar` Incorporado |
| menú flotante (`MuseFloatingActionMenu`) | panel interior 8dp | Incluido en el componente |
| Iconos y texto en línea | 10-12dp | `MusePaddings.auxGap` |

En pocas palabras: las líneas izquierdas de todo el texto en la misma pantalla deben estar alineadas con la misma línea. La única desviación permitida es "8dp adicionales dentro del panel".

### Lista de verificación

- [ ] Los títulos y subtítulos de un mismo bloque utilizan el mismo margen izquierdo. El elemento faltante más común: el título está afuera `Row` Lo tengo encendido `padding(horizontal = screen)`, y el subtítulo es su**Nodo hermano**, perdiendo sus propios márgenes.
- [ ] No subas el panel inferior `horizontalPadding = 0.dp`.
- [ ] No superpongas el contenido del panel una capa más `padding(horizontal = ...)`, de lo contrario se producirá una doble sangría (16 + 20 = 36dp).
- [ ] Para listas con "una tarjeta redondeada por línea", como el menú de pulsación larga y el menú más, se deben dejar márgenes en ambos lados de la tarjeta y no se permite que la tarjeta toque el borde de la pantalla.
- [ ] Escenarios como galerías de desplazamiento horizontal y medios de pantalla completa que realmente necesitan estar delimitados deben indicar los motivos en el código.

### Barandilla automática

`ci/script/check_horizontal_inset.py` Interceptar compensación explícita:`horizontalPadding = 0.dp`,`padding(horizontal = 0.dp)`,`PaddingValues(horizontal = 0.dp)`,`contentPadding = 0.dp`. Agregue al final de las líneas que realmente necesitan bordes `// inset-guard: allow` Y explique el motivo; de lo contrario, CI fallará directamente.
