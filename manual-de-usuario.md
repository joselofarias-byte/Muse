# Muse Manual de usuario

> **Versión aplicable**:v2.3.0(Organizado de acuerdo con la interfaz real de esta versión; si hay alguna discrepancia en la interfaz, prevalecerá la versión integrada en la aplicación)
>
> Este manual explica claramente cada función de Muse: dónde está la entrada, cómo operarla, qué efectos tendrá y dónde están los posibles errores. Usuario por primera vez, lea el Capítulo 1,2 Puedes comenzar a ejecutar con solo un capítulo; los capítulos restantes se pueden consultar bajo demanda, y cada capítulo es un capítulo independiente, por lo que no es necesario leerlo de principio a fin.
>
> También existe una versión simplificada de la guía gráfica en la aplicación:**ajustes → Tutoría**. Si no puede encontrar algo, busque directamente en la parte superior de la página de configuración.

## Directorio

**primera parte · empezar**
- [1. Instalación y apertura por primera vez.](#1-Instalación y apertura por primera vez.)
- [2. Navegación de la interfaz y gestos.](#2-Navegación de la interfaz y gestos.)
- [3. Modelos, proveedores y configuraciones de construcción](#3-Proveedores de modelos y configuraciones de compilación)

**segunda parte · charla diaria**
- [4. Envío de mensajes y operaciones de mensajes.](#4-Envío de mensajes y operaciones de mensajes.)
- [5. Gestión de sesiones](#5-Gestión de sesiones)
- [6. Voz y multimedia](#6-Voz y multimedia)
- [7. Redes y navegación](#7-Redes y navegación)

**tercera parte · memoria y conocimiento**
- [8. sistema de memoria](#8-sistema de memoria)
- [9. base de conocimientos](#9-base de conocimientos)

**parte 4 · Herramientas y automatización**
- [10. Herramientas y permisos](#10-Herramientas y permisos)
- [11. Operaciones a nivel del sistema:UI Automatización y terminales](#11-Operaciones a nivel del sistemaui-Automatización y terminales)

**parte 5 · Asistente y Ecología**
- [12. Asistente: utilizado por varias "personas" juntas](#12-El asistente puede ser utilizado por varias personas.)
- [13. Chat grupal y colaboración entre múltiples agentes](#13-Chat grupal y más-agent-colaboración)
- [14. Habilidades, complementos y MCP](#14-Complemento de habilidades y-mcp)
- [15. Canales de mensajería: ponga IA en su IM](#15-canal de noticias-ai-en tu-im)

**sexta parte · vida y datos**
- [16. La mensajería activa y su vida.](#16-La mensajería activa y su vida.)
- [17. Apariencia y personalización](#17-Apariencia y personalización)
- [18. Datos, respaldo y seguridad](#18-Copia de seguridad y seguridad de datos.)
- [19. Versión web y más entradas](#19-Versión web y más entradas)

**Otros**
- [Preguntas frecuentes](#Preguntas frecuentes)
- [Apéndice A: Copia de seguridad y formato de archivos de datos](#Apéndice-aFormatos de archivos de datos y copias de seguridad)
- [Apéndice B: Glosario](#Apéndice-bGlosario)

---

## primera parte · empezar

### 1. Instalación y apertura por primera vez.

#### 1.1 Descargar e instalar

- Requisitos del sistema:**Android 8.0 y arriba**.
- Canal de descarga:GitHub Releases página o sitio web oficial [museai.ltd](https://museai.ltd).
- Elija uno de los tres paquetes de instalación:

| Paquete de instalación | Adecuado para quien |
| --- | --- |
| `arm64-v8a` | La mayoría de los teléfonos celulares de los últimos años, la primera opción. |
| `armeabi-v7a` | teléfonos celulares más antiguos |
| `universal` | No consigo la versión universal a tiempo, es la más grande |

Si se le solicita "Fuente desconocida" durante la instalación, simplemente permítalo. Este es el recordatorio normal de Android para instalaciones que no sean de tiendas de aplicaciones. La nueva versión se puede sobrescribir e instalar directamente y los datos no se perderán.

#### 1.2 Abierto por primera vez: siga las instrucciones

Cuando lo abras por primera vez, pasarás por seis pantallas:

1. **pagina de bienvenida**: Varias tarjetas de función, haga clic en "Configuración de inicio" después de leer.
2. **Idioma y apariencia**: elige el idioma de la interfaz (español, inglés, portugués, japonés, coreano o ruso) y un tema. Puedes cambiarlos después.
3. **Conózcanse primero**: Complete dos títulos, "Cómo debería llamarte" y "Dale un nombre". Se utilizarán en el diálogo y también se pueden dejar en blanco.
4. **déjame recordarte**: Interruptor del sistema de memoria. Después de encenderlo, la memoria creciente se organizará automáticamente una vez finalizada la conversación. Ésta es la mayor diferencia entre Muse y las herramientas de chat comunes. Se recomienda mantenerlo encendido.
5. **Conéctate al cerebro**: Seleccione un proveedor de servicios modelo, pegue la clave API y haga clic en "Probar conexión". Después del éxito, la lista de modelos se extraerá automáticamente y se compararán las capacidades.
6. **Elija un modelo de caballo de batalla**: Busque y seleccione un modelo como predeterminado, luego haga clic en "Charlemos".

Puedes omitir cualquier paso y agregarlo más tarde:**ajustes → Modelos y capacidades → proveedor**.

#### 1.3 Conéctelo con un cerebro: proveedor de servicios modelo

Muse No vende créditos modelo por sí solo, conecta varios proveedores de servicios modelo (OpenAI,Anthropic,Google,DeepSeek, Tongyi Qianwen, Zhipu, Doubao, etc. más de 40 ajustes preestablecidos).

- **API Key que es**: Credenciales para llamar al modelo. Vaya al sitio web oficial del proveedor de servicios para registrarse y vaya a "API Genere una cadena de caracteres en la página "Clave" y péguela en Muse. La mayoría de los proveedores de servicios le darán una cierta cantidad de crédito gratuito después del registro.
- **¿Qué sucede si la conexión es exitosa?**:Muse Automáticamente extraerá la lista de modelos disponibles de este proveedor de servicios y marcará las capacidades de cada modelo (ver imágenes/inferencia/llamadas de herramientas) y luego cambiará a voluntad en la conversación.
- **Proveedor de servicios personalizados**:cualquiera "OpenAI Las interfaces compatibles (incluidas estaciones de transferencia, Ollama local y servicios de construcción propia) se pueden agregar manualmente: complete el nombre, la dirección de la interfaz,API Key Eso es todo.

Consulte el Capítulo 3 para una gestión de proveedores más completa (multiclave, cuota, consulta de saldo, modelo de dibujo, etc.).

#### 1.4 primera frase

Regrese a la página de inicio, escriba cualquier idea en el cuadro de entrada en la parte superior o haga clic en una conversación para enviar un mensaje. Puedes empezar así:

- "Hola, preséntate"
- "Recuerda: me gusta beber americano helado, pero no me gusta el cilantro" (a partir de ahora siempre lo recordaremos, ver Capítulo 8)
- "Ayúdame a comprobar el tiempo en Beijing mañana" (Habilidad de la herramienta, consulte el Capítulo 10)

---

### 2. Navegación de la interfaz y gestos.

Muse La interfaz tiene sólo tres etapas principales:**Página de inicio (lista de conversaciones), página de chat, página de configuración**. Este capítulo los analiza uno por uno, además de una hoja de trucos sobre gestos.

#### 2.1 Página de inicio (lista de conversaciones)

- **Cuadro de entrada superior**: Ingrese directamente cualquier idea, haga clic en "Nuevo y Enviar", se creará una nueva conversación y enviará esta oración como primer mensaje. Esta es la forma más sencilla de iniciar una conversación.
- **Lista de conversación**: Organizados por grupos, de arriba a abajo están "Anclados/Recientes/Carpetas/Datos". Hay filtros en la parte superior: Todo/Pin/Archivo.
- **tira de revisión**: Mostrará "La última vez que chateé con XX"……"", recordándote el tema de la última vez.
- **Socios horizontalmente**: Una fila de avatares de asistente, haz clic en con quien quieras chatear; También puedes agregar nuevos socios desde aquí.
- **bloque de atajos**: Base de conocimientos, tareas programadas, grabación rápida, traducción rápida y otras entradas.
- **Organizar conversaciones**: Mantenga presionada cualquier conversación para abrir el menú (pegarse al principio/archivar/cambiar nombre/mover a carpeta/eliminar); el área superior se puede arrastrar y ordenar; Las carpetas admiten la creación, el cambio de nombre y la eliminación (cuando se elimina una carpeta, las conversaciones que contiene se moverán a "Desagrupadas" y no se eliminarán).
- **Archivo**: Las conversaciones que no desees eliminar o ver se incluirán en el filtro "Archivar" después de archivarse. Puede cancelar el archivo en cualquier momento.
- Ocasionalmente habrá una barra de aviso de "nueva versión disponible" en la parte superior de la página de inicio. Haga clic en él para ver el contenido actualizado.

#### 2.2 pagina de chat

Dividido en tres partes de arriba a abajo:

- **barra superior**:
  - Izquierda: botón Atrás.
  - Medio: título de la sesión actual.**Haga clic**Aparece la lista de sesiones,**Pulsación larga**Aparece una lista de asistentes (cambie una "persona" con quien chatear).
  - Lado derecho: entrada del modelo actual y menú de tres puntos (seleccionar proveedores, comprimir contexto y otras operaciones están aquí).
- **área de mensajes**: Tu conversación con la IA. Varias tarjetas en mensajes (Mood, tarjetas de tareas, tarjetas de productos, etc.) Consulte el Capítulo 4.
- **Campo de entrada**: Signo más a la izquierda (panel de herramientas), cuadro de entrada en el medio, micrófono y botón de enviar a la derecha.

#### 2.3 Mapa de la página de configuración

La página de configuración está dividida en siete grupos. Hay un cuadro de búsqueda en la parte superior. Si no puede encontrarlo, puede buscar directamente (admite iniciales chinas y pinyin). Contenidos de cada grupo:

| grupo | entrada interior |
| --- | --- |
| Chatear y mostrar | Chat (detalles de visualización y comportamiento), apariencia, transmisión de voz (TTS) |
| mi asistente | asistente,Agent(Equipo de múltiples agentes), recursos del asistente (favoritos/libro mundial/mensajes rápidos/habilidades, etc.) |
| Modelos y capacidades | Proveedores, modelos auxiliares, búsqueda en red, reconocimiento de voz. |
| Memoria y mensajería activa | Mensajería activa, centro de memoria, recuperación de base de conocimientos. |
| Herramientas y conexiones | Centro de conexión (canal de mensajes/MCP/complemento/permisos de herramientas), escucha de notificaciones, tareas programadas,UI Automatización, asistente de configuración de permisos, grabación rápida, teléfono celular pequeño, traducción |
| Datos y privacidad | Gestión de datos, copia de seguridad, importación de datos, espacio de trabajo, chat de archivo,PII Protección de privacidad, uso compartido, proxy, registro de auditoría |
| Acerca de y ayuda | Tutoriales, Acerca de, Buscar actualizaciones, Registros de depuración, Experimental, Estadísticas |

La parte superior de la página de configuración es su tarjeta de cuenta (avatar y apodo, puramente local, sin registro).

#### 2.4 Hoja de referencia de gestos

Muchas operaciones quedan ocultas en la pulsación larga. Vale la pena guardar esta tabla:

| gesto | ubicación | Efecto |
| --- | --- | --- |
| Haga clic | Título de la página de chat | Abra la lista de sesiones y cambie de sesión. |
| Pulsación larga | Título de la página de chat | Abra la lista de asistentes y cambie de asistente |
| Pulsación larga | cualquier mensaje | Menú de operación emergente (copiar/Cotización/bifurcación/eliminar, etc). |
| Pulsación larga | Cuadro de entrada | Menú emergente relacionado con la entrada (incluido el modo de entrada de pantalla completa) |
| Pulsación larga | botón más | Expandir/contraer barra de herramientas de acceso directo |
| Pulsación larga | micrófono | Grabación; suelte para identificar, deslice hacia arriba para cancelar |
| Pulsación larga | entrada de sesión | Fijar/Archivar/Cambiar nombre/Mover/Eliminar |
| Pulsación larga | Artículos favoritos | Establecer etiquetas de grupo |
| Teclas de volumen | Área de mensajes de chat | Desplazarse hacia arriba y hacia abajo (desactivado de forma predeterminada, disponible en Configuración) → Abrir en el chat) |

> Consejo: el botón de edición de pantalla completa está oculto de forma predeterminada y se puede encontrar en Configuración → charlar → Actívelo en un comportamiento interactivo y escribir textos largos será mucho más cómodo.

---

### 3. Modelos, proveedores y configuraciones de construcción

#### 3.1 Cambiar modelo

- En el lado derecho de la barra superior de la página de chat está la entrada del modelo, haz clic para abrirla y cambiar; la lista de modelos estará marcada con capacidades (visuales/razonamiento/llamada de herramientas), seleccione según sea necesario.
- No importa si cambias de modelo en medio de la misma conversación. La memoria y el contexto no tienen nada que ver con el modelo y no producirá "amnesia".
- Cada asistente también puede vincularse a su propio modelo predeterminado (consulte el Capítulo 12), y el cambio manual dentro de una sesión solo tiene efecto para la sesión actual.

#### 3.2 pensamiento profundo

Es adecuado para problemas como matemáticas, lógica, codificación y análisis de textos largos que requieren "pensar un paso más".

- **cambiar**: Botón de pensamiento profundo en el campo de entrada, haga clic en el interruptor.
- **nivel**: Mantenga presionado el botón para recorrer los niveles. El presupuesto de pensamiento correspondiente a cada marcha es aproximadamente:

| nivel | Piense en el presupuesto (token) |
| --- | --- |
| bajo | aprox. 1,000 |
| en | aprox. 2,000 |
| alto | aprox. 8,000 |
| extremadamente alto | aprox. 16,000(Solo compatible con algunos modelos) |

- También hay dos niveles: "automático" y "apagado": el servidor lo determina automático y "apagado" significa que no hay ningún razonamiento. Algunos modelos no admiten inferencia y no funcionarán si están habilitados.
- Si desea que active el pensamiento profundo de forma predeterminada:**ajustes → charlar → Avanzado → Pensamiento profundo por defecto**.

#### 3.3 Modelo auxiliar (enrutamiento de tareas)

Muse Hay muchas tareas pequeñas que hacer en segundo plano: crear títulos, comprimir diálogos, refinar recuerdos y reconocer imágenes.……Estos no tienen por qué ser los modelos más caros.

**ajustes → Modelo auxiliar**Aquí puedes:

- Especificar modelos para los tres niveles de tareas:
  - Gadgets: generar títulos, clasificación ligera y otras tareas sencillas;
  - Grandes herramientas: compresión, recuperación de memoria, desmantelamiento de tareas y otros escenarios que requieren comprensión;
  - Visual: Descripción del contenido de la imagen.
- Vincule modelos por tipo de tarea: diálogo/razonamiento/programación/creación/análisis y active el "modelo de cambio automático" para que diferentes actividades caigan automáticamente en el modelo más apropiado.
- Haga un clic en "Restablecer todos los enlaces" para volver a los valores predeterminados.

> Se recomienda configurar al menos un modelo pequeño y económico para el archivo "gadget" para guardar mucho dinero a largo plazo.

#### 3.4 El “carácter” de la respuesta: aleatoriedad, estilo, tono

**ajustes → charlar → generar estilo**:

- **respuesta aleatoriedad**:0 Parcialmente segura, la respuesta es estable y predecible;2 Más creativo y divergente en respuestas.
- **estilo de tono**: Conciso/Equilibrado/Detallado, controle el nivel de detalle de su respuesta.
- **tono**: Neutral/amistoso/formal/humorístico.

#### 3.5 ¿Qué más hay en la página del proveedor?

**ajustes → Modelos y capacidades → proveedor**Es una consola con funciones comunes:

- Pruebe las conexiones y "verifique toda" la disponibilidad de todos los proveedores con un solo clic;
- Capacidad para extraer la lista de modelos ascendentes, agregar modelos manualmente y editar modelos (ventana visual/razonamiento/herramientas/contexto);
- **Múltiples grupos de claves**: El mismo proveedor puede guardar varias claves API y utilizarlas en rotación;
- Protección de límite de velocidad: número máximo de solicitudes por minuto (RPM), el número máximo de concurrencias;
- Consulta de saldo (el punto final del saldo debe configurarse en el proveedor);
- Google Vertex AI Opciones de nivel empresarial como cuenta de servicio, encabezado/cuerpo de solicitud personalizado, etc.;
- **Aquí también se configuran los modelos generados a partir de imágenes y vídeos.**(Sección de generación de imágenes/generación de videos en la página).

---

## segunda parte · charla diaria

### 4. Envío de mensajes y operaciones de mensajes.

#### 4.1 Método de envío

- **escribiendo**: Ingrese en el cuadro de entrada y haga clic en Enviar. Disponible en Configuración → Habilite "Ingresar clave para enviar" en el chat (Ingresar clave para avanzar línea cuando esté desactivado).
- **Voz**: Mantenga presionado el micrófono para hablar, suéltelo para cambiar automáticamente a texto (consulte el Capítulo 6 para obtener más detalles).
- **Imágenes/Vídeos/Documentos**: haga clic en el signo más para abrir el panel de herramientas, donde puede tomar fotografías, seleccionar imágenes del álbum (varias imágenes a la vez) y enviar videos (50MB dentro) y enviar documentos.
- **Artículo largo**: Al pegar una sección grande de texto,Muse Le preguntará "Si se adjunta como archivo" para evitar que el cuadro de entrada se explote; También puede mantener presionado el cuadro de entrada para seleccionar el modo de entrada de pantalla completa y escribir lentamente.
- **También hay**: Referencia a un documento de base de conocimientos (@ Pídale que siga cierta información), solicite plantillas de palabras, habilidades, modo de dibujo (ingrese una descripción para generar directamente una imagen), delegue a un asistente, reinicie el contexto (borre la conversación actual y comience desde el principio).

#### 4.2 Tres tipos de botones en la generación de respuestas

AI Mientras respondes, si tienes nuevas ideas:

- **detenerse**: Interrumpe la respuesta actual.
- **Únete al equipo**: coloque el nuevo mensaje en la cola para enviarlo, sin interrumpir su trabajo actual, y espere a que termine antes de enviarlo.
- **interponer**: Interrumpa directamente la generación actual y envíe su nuevo mensaje inmediatamente.

Si la última conversación se interrumpió a la mitad (por ejemplo, cambiando al fondo), cuando regrese a la sesión, aparecerá una barra que le indicará "Las últimas N llamadas a herramientas no se completaron". Puede elegir "Reanudar ejecución" para continuar ejecutándose o "Descartar" para borrarlo.

#### 4.3 comando de barra diagonal

Escriba en el cuadro de entrada `/`, aparecerá una lista de comandos:

| comando | función |
| --- | --- |
| `/new` | Nueva sesión |
| `/compact` | Comprimir el historial de la sesión actual |
| `/reset` | restablecer contexto |
| `/pin` | Anclar/desanclar |
| `/archive` | Archivar la sesión actual |

#### 4.4 Pulsación larga sobre el mensaje: Menú de operación

Mantenga presionado cualquier mensaje para que aparezca el menú de operación. El contenido del menú variará ligeramente según el tipo de mensaje:

- **Todos**: Copiar, citar, anotar, delegar al asistente, bifurcar conversación, seleccionar (ingresar selección múltiple), información del mensaje (modelo/consumo de tiempo/uso de token), respuesta de expresión (me gusta, amor, risa, sorpresa, tristeza, enojo), colección.
- **AI noticias**: También leer/detener lectura, eliminar.
- **tu propio mensaje**: También edite, traduzca, comparta, reenvíe a la conversación, elimine.

Al eliminar, puede elegir "Eliminar sólo este mensaje" o "Eliminar este mensaje y todos los mensajes posteriores".

#### 4.5 Reconstrucción y cambio de versión.

- Si no está satisfecho con la respuesta de la IA, haga clic en "Regenerar" debajo del mensaje y responderá nuevamente con otra palabra.
- Cada regeneración generará una nueva versión y la versión anterior no se perderá. El selector de versiones junto al mensaje le permite desplazarse por varias versiones de izquierda a derecha y volver a la versión anterior en cualquier momento.

#### 4.6 Ramificación: las conversaciones pueden ramificarse

- **conversación bifurcada**: Copia una nueva línea de diálogo a partir de un mensaje determinado, dejando intacto el diálogo original. Adecuado para la exploración de "¿Qué pasaría si hiciera la pregunta de manera diferente?"
- Cuando edita un mensaje que ha enviado y lo reenvía, crecerá una nueva rama desde esa ubicación y se conservarán las preguntas y respuestas anteriores.

#### 4.7 Modo de selección múltiple

Seleccione "Seleccionar" en el menú de pulsación larga para ingresar al modo de selección múltiple: aparece una casilla de verificación en el lado izquierdo del mensaje y aparece una barra de operaciones en la parte superior, que admite seleccionar todo, copiar por lotes, eliminar por lotes y exportar por lotes. Haga clic en "Salir" o haga clic en un espacio en blanco para regresar.

#### 4.8 Varios "fragmentos" en el mensaje.

No te confundas cuando veas estas tarjetas por primera vez, todos son idiomas normales de Muse:

- **Mood bloquear**:AI Actividades internas antes de responder (Vibe / Sparks / Reflections / Will Cuatro capas), plegadas de forma predeterminada, puedes ver lo que piensa cuando haces clic en él. Se puede encontrar en Configuración → charlar → La visualización de mensajes controla si se muestra o no.
- **proceso de pensamiento**: La pista de pensamiento del modelo de inferencia también está colapsada de forma predeterminada.
- **bloque de reflexión**:AI Autoevaluación de su respuesta (exactitud/integridad/tono).
- **tarjeta de tarea**:AI Un registro del proceso de llamada a una herramienta, enumerando cómo se llamó, cuáles fueron los parámetros, cuál fue el resultado, si fue exitoso o falló; varias llamadas se resumirán en un "Registro de llamadas de herramientas", que se puede ampliar y ver una por una.
- **tarjeta de producto**: Se generará una tarjeta para el código, los documentos y otros contenidos que valga la pena sacar por separado. Haga clic para ver y copiar en pantalla completa; todos los productos se archivan en el "espacio de trabajo" → Centro de productos".
- **Tarjeta de cotización**: Cuando el mensaje cita un contenido (el texto original que citó o la fuente de la base de conocimientos que encontró), aparecerá en la parte superior de la burbuja.
- **burbuja de traducción**: Hay un bloque plegable "Ver texto original" debajo de la traducción para comparar.

#### 4.9 Colección

Cualquier mensaje se puede guardar y luego ver y buscar en favoritos. Mantenga presionado un elemento en favoritos para configurar etiquetas de grupo para una fácil categorización.

---

### 5. Gestión de sesiones

#### 5.1 Nuevo y cambiar

- Puedes crear una nueva conversación ingresando directamente tus pensamientos en el cuadro de entrada en la parte superior de la página de inicio; También puedes hacer clic en "Nueva tarea" para crear una.
- Cambiar de conversación: haga clic en la lista en la página de inicio o haga clic en el título de la página de chat para que aparezca la lista y cambiar rápidamente.
- Cada conversación es un mundo independiente: en una conversación hablamos de gatos, en otra hablamos de trabajo, sin interferir entre nosotros.

#### 5.2 Organizar: pin, carpeta, archivo

- Mantenga presionado el menú emergente de conversación: Anclar/Archivar/Cambiar nombre/Mover a carpeta/Eliminar.
- Las conversaciones fijadas se fijan en la parte superior de la lista y se pueden arrastrar y ordenar.
- Las carpetas se utilizan para categorizar conversaciones por tema (trabajo, estudio, vida) y respaldar la creación, el cambio de nombre y la eliminación.
- Si no deseas eliminar o leer las conversaciones, utiliza "Archivar" y colócalas en el filtro "Archivo" para recuperarlas en cualquier momento.

#### 5.3 Buscar

- Búsqueda de la página de inicio: búsqueda de texto completo entre sesiones, búsqueda de palabras clave para localizar el mensaje específico.
- Escriba en el cuadro de entrada `/` También puedes abrir directamente el panel de comandos y buscar conversaciones, mensajes y comandos.
- La página de búsqueda completa admite más filtros y también puede filtrar por colecciones y etiquetas.

#### 5.4 Largas conversaciones y gestión del contexto.

La conversación se hizo cada vez más larga.Muse Existen algunas herramientas para garantizar que "lo recuerde sin recordarlo demasiado":

- **Compresión**:`/compact` comando o seleccione "Comprimir contexto" en el menú de tres puntos en la barra superior para comprimir conversaciones históricas en resúmenes. Al comprimir manualmente, puede establecer la cantidad de elementos retenidos e instrucciones adicionales.
- **restricciones de contexto del piso**(ajustes → charlar → Gestión de contexto): utilizando "Su mensaje" como piso, conserve las conversaciones completas de los N pisos más recientes para evitar cortarlas a la mitad.
- **Límite de ronda de herramientas**(ajustes → Chat): el número máximo de rondas de herramientas a las que la IA puede llamar en una sola ronda de conversación. El valor predeterminado es "ilimitado", las tareas largas no se interrumpirán a la mitad; también puede limitarse a 100 / 50 / 25 Ahorra consumo.
- **Modo de rendimiento**: Solo se procesarán los últimos 50 mensajes para sesiones ultralargas. Desliza hacia arriba para cargar más mensajes, lo cual es más sencillo para teléfonos más antiguos.
- **Límite de caché de sesión**: el número de copias de la sesión retenidas en segundo plano. Cuanto mayor sea el valor, más rápido será el cambio y mayor será el uso de memoria.

Cuando el contexto se comprime o trunca,Muse Se mantendrá un extracto histórico al frente y las cosas discutidas no desaparecerán de la nada.

#### 5.5 Eliminar

Aparecerá un cuadro de confirmación antes de eliminar la sesión y la operación es irreversible. Un hábito seguro es: en caso de duda, utilice "Archivar", y cuando esté seguro de que no lo necesita, elimínelo; utilice copias de seguridad para cosas importantes (consulte el Capítulo 18).

### 6. Voz y multimedia

#### 6.1 Entrada de voz

- Mantenga presionado el micrófono en el lado derecho del campo de entrada, hable por teléfono y suéltelo para convertirlo automáticamente en texto; deslice hacia arriba para cancelar mientras graba; y los resultados del reconocimiento se pueden editar antes de enviarlos.
- Dos modos:
  - **motor del sistema**: Llame al servicio de voz integrado del teléfono sin configuración, pero es posible que algunos sistemas domésticos no lo admitan y aparecerá un cuadro de diálogo del sistema cada vez que se reconozca.
  - **motor de nube**: en **ajustes → Modelos y capacidades → reconocimiento de voz** Seleccione DashScope, Paso,Whisper,Realtime Espere el servicio de reconocimiento de transmisión y complete la clave API correspondiente para obtener la experiencia de "hablar y transcribir" en la aplicación.
- Algunos motores admiten reconocimiento multilingüe y puntuación automática.

#### 6.2 Lectura en voz alta y clonación de voz.

- Respuesta de IA con pulsación larga → Leer/dejar de leer.
- **ajustes → Chatear y mostrar → Transmisión de voz**: Cambio, velocidad de conversación, tono, idioma.
- Además de la voz del sistema, el motor de voz también se puede conectar a sonidos en la nube:MiniMax,ElevenLabs,Fish Audio,DashScope, paso, etc., habrá elementos de ajuste más detallados (como emoción, estabilidad) después de conectarse.
- **Clonación de voz**: Hay una entrada "Clonar voz" en la página de transmisión de voz. Después de la configuración, puede utilizar timbres personalizados para la lectura.

#### 6.3 Mirar imágenes: comprensión de imágenes y ayudas visuales.

- **Publicar fotos directamente**: para modelos que admiten visión (GPT-4o,Gemini, Tongyi Qianwen VL, etc.) para enviar imágenes, puede leer imágenes, leer capturas de pantalla y analizar tablas; un mensaje puede contener varias imágenes.
- **ayuda visual**: Si el modelo actual no admite la visualización de imágenes,Muse Llamará automáticamente al modelo visual que especifique, convertirá la imagen en una descripción estructurada y luego se la entregará al modelo actual para que pueda "ver indirectamente" la imagen. Entrada:**ajustes → Modelos y capacidades → Modelo auxiliar → ayuda visual**; Puede especificar un modelo visual y utilizar la "prueba de sonda" incorporada para verificar que el modelo realmente funciona.
- **Escaneos e imágenes PDF**: OCR sin conexión incorporado, capaz de extraer texto en chino e inglés.

#### 6.4 Genera imágenes y vídeos.

- **modo de dibujo**: Abra el "Modo de dibujo" en el panel más e ingrese directamente la descripción de la imagen para generar la imagen; También puedes colgar imágenes de referencia y ajustar la calidad y el estilo.
- El modelo utilizado para generar está en **ajustes → Modelos y capacidades → proveedor** Configure en la partición "Generación de imágenes/Generación de videos".
- Generación de video: simplemente diga "Generar un video corto de xx" al proveedor de servicios admitido (duración de 5/10 segundos, dependiendo de las capacidades del proveedor de servicios).

#### 6.5 biblioteca de emoticonos

- Ubicación:**ajustes → charlar → biblioteca de emoticonos**.
- Importar: seleccione un archivo zip,Muse Se ordenará automáticamente según la estructura de carpetas.
- Conmutador y frecuencia: Puedes habilitarlo/deshabilitarlo en su conjunto, y ajustar la frecuencia de envío a "ocasional/normal/frecuente".
- Efecto: al chatear, el asistente naturalmente sacará un emoticón cuando el ambiente sea adecuado, en lugar de forzarlo.

---

### 7. Redes y navegación

#### 7.1 búsqueda en internet

- Dos interruptores: el valor predeterminado global está activado **ajustes → Modelos y capacidades → búsqueda en internet**;Para habilitarlo temporalmente, puede hacer clic directamente en el botón del globo en la barra de entrada.
- Prioridad de búsqueda en modo automático:
  1. Búsqueda nativa de modelos (se da preferencia a los modelos con capacidades de búsqueda integradas, que se han conectado a Gemini Grounding y OpenAI Responses, etc.);
  2. La API de búsqueda que configuró;
  3. Solución de problemas HTTP gratuita (Bing / Baidu).
- Servicios de búsqueda accesibles:Tavily,Brave,Serper,Perplexity,Exa, Bocha, etc.; Después de configurar la clave, el modo automático volverá a aparecer en secuencia y filtrará automáticamente los resultados de baja calidad.
- La estrategia de búsqueda es ajustable: el número máximo de búsquedas en una sola ronda, el número máximo de resultados obtenidos cada vez y el cambio automático al motor de respaldo después de una falla.
- Hay "Búsqueda de prueba" y "Diagnóstico de búsqueda más reciente" en la página. Si no puede encontrar algo, puede ir allí para comprobarlo primero.
- También puedes decirle directamente: "Ayúdame a buscar xxx".

#### 7.2 Míralo en línea: navegador integrado

- AI Al navegar por Internet aparecerá una pequeña cápsula en la parte superior de la página de chat, mostrando el título de la página web que se está visitando.
- Haga clic en la cápsula: una imagen en tiempo real a pantalla completa, que se puede ver al pasar páginas, hacer clic o completar formularios.
- Operaciones de página en pantalla completa: regresar (doblar la pantalla, continúa funcionando en segundo plano), actualizar, cerrar (finalizar completamente esta navegación, la cápsula desaparece).
- Cada sesión tiene una página de navegador independiente y no interfiere entre sí.

---

## tercera parte · memoria y conocimiento

### 8. sistema de memoria

Aquí es donde Muse se diferencia de otras herramientas de chat:**realmente te recuerda**. Dices algo casualmente y se retoma semanas después.

#### 8.1 ¿Cómo crecen los recuerdos?

La información de la conversación pasará por un proceso de fondo:

```
dialogo → extracción de hechos → resumen de desplazamiento → compilar agregación → comprensión profunda
```

- Lo que usted dijo será resumido en "hechos" y marcado con importancia;
- Las conversaciones largas se comprimirán en "resúmenes desplazables" y no se descartarán por ser demasiado largas;
- El trasfondo "compila" hechos y resúmenes en cognición de nivel superior, que se inyecta según su relevancia en la siguiente conversación;
- Se protegerá la información clave (personas importantes, preferencias, hábitos, asuntos médicos y financieros); Los asuntos triviales cotidianos se olvidarán lentamente como las personas.

> **Consejos importantes**: Los arreglos se están realizando lentamente en segundo plano y es posible que lo que acabo de decir aún no se haya almacenado. Lo que realmente importa, simplemente dile "Recuerda:xxx”, se solucionará inmediatamente sin pasar por el proceso en segundo plano.

#### 8.2 panel de memoria

Entrada:**ajustes → centro de memoria**. Aquí puedes:

- Explore y busque todos los recuerdos jerárquicamente por "actual/corto plazo/largo plazo/metahecho"; filtrar por tiempo, importancia y espacio de memoria;
- Además de la lista, hay vistas de línea de tiempo y "Constelación de memoria"; la línea de tiempo se puede filtrar por "Todos/Hechos/Resumen/Hitos";
- Editar o eliminar cualquier elemento; agregar manualmente nuevos recuerdos; ajustar la importancia (normal/importante/crítico "nunca olvidar");
- Se puede rastrear cada memoria hasta su origen, tiempo de almacenamiento y número de visitas;
- Cada memoria tiene un historial de modificaciones y puedes "volver a esta versión".

Los parámetros detallados de la memoria también se encuentran en la configuración de la memoria: interruptor maestro, presupuesto de memoria, coeficiente de atenuación (cuanto mayor, más rápido se olvida), índice de olvido, bonificación de acierto, etc. También puede restaurar el valor predeterminado con un solo clic.

**espacio de memoria**: Separar recuerdos para diferentes propósitos (como trabajo, vida, dedicados a un determinado asistente) para que no se crucen.

#### 8.3 Memoria fija (decir "recordar")

- Si el diálogo dice "Recuerda: el cumpleaños de mi madre es el 3 de mayo", lo recordará inmediatamente usando un método de memoria fija y lo traerá consigo en cada diálogo.
- Las memorias fijas también se pueden gestionar manualmente: Configuración → centro de memoria.

#### 8.4 Biblioteca de experiencias

- Ubicación:**ajustes → centro de memoria → Biblioteca de experiencias**.
- Precipite "Este tipo de problema se abordará en el futuro" en elementos (título, texto, clasificación, etiquetas), y el asistente se referirá a él cuando encuentre tareas similares.
- Adecuado para escenarios en los que es necesario corregirlo repetidamente; se puede activar y desactivar en la configuración (activarlo requerirá un poco de contexto).

#### 8.5 Lo que se recuerda y lo que no se recuerda

- La memoria sólo existe en tu teléfono celular y no se carga en ningún servidor;
- Cambiar de modelo o de proveedor no afecta la memoria;
- La memoria de datos del asistente y las conversaciones recientes están aisladas por el asistente de forma predeterminada; el "asistente predeterminado" aún puede usar el grupo de memoria global.
- De forma predeterminada, otros asistentes recién creados no leerán retratos globales de usuarios, recuerdos fijados ni experiencias compartidas; puede activar explícitamente "Usar memoria global" en la configuración del asistente. Al actualizar, los asistentes existentes que no sean los predeterminados también se cambiarán al aislamiento predeterminado, pero sus memorias de hechos existentes no se eliminarán.

---

### 9. base de conocimientos

Lánzale tu propia información (documentos, manuales, notas, contratos). Cuando haga preguntas, buscará la información antes de responder y marcará un párrafo citado.

#### 9.1 Importar documentos

- Entrada:**ajustes → Búsqueda de base de conocimientos**.
- Texto de soporte,PDF,Word,PPT,Excel,ePub y otros formatos comunes; Los documentos escaneados y los archivos PDF basados ​​en imágenes se someten a OCR sin conexión.
- Al importar, puede elegir a qué base de conocimientos ingresar (la base de conocimientos predeterminada o la suya propia).
- Base de conocimientos Haga clic en "Base de conocimientos" → carpeta → Explorar documentos; importar ZIP,7Z O RAR creará una nueva base de conocimientos basada en el nombre del paquete comprimido y conservará la estructura del directorio dentro del paquete. Los documentos se pueden mover a carpetas virtuales o importar directamente a la carpeta abierta actualmente; La reorganización del catálogo no moverá el texto original ni lo volverá a indexar.
- La importación pasará por dos pasos: fragmentación automática → Generar índice vectorial. El progreso mostrará "N bloques indexados".
- Cuando un solo archivo es demasiado grande, se le preguntará: divídalo en partes más pequeñas y cárguelas en lotes, o cárguelas en lotes. **ajustes → Búsqueda de base de conocimientos → analizador de documentos** Cambie al analizador de la nube (también es compatible con MinerU, bueno para archivos PDF académicos, fórmulas y tablas).
- **Reindexar**: Si se han modificado los parámetros de bloqueo o se ha modificado el texto del documento, es necesario reconstruir el índice para buscar contenido nuevo. La reconstrucción eliminará el índice anterior y lo regenerará, lo que lleva mucho tiempo. Se recomienda funcionar mientras la alimentación está enchufada.

#### 9.2 Encuéntrelo con precisión: fragmentación y recuperación

- **Parámetros de fragmentación**(ajustes → Búsqueda en la base de conocimientos): tamaño de fragmento, superposición de fragmentos,Markdown Percepción, fragmentación por fichas (una mezcla de chino e inglés es más precisa).
- **Búsqueda híbrida**:Palabra clave(BM25) y recuperación bidireccional de similitud de vectores,RRF Fusion, los pesos vectoriales son ajustables.
- Más avanzadas son la diversidad y el reordenamiento de MMR (Rerank), recuperación del presupuesto del token y otros modificadores, se recomienda el valor predeterminado.
- Los fragmentos encontrados tendrán una puntuación de relevancia; el umbral de relevancia se puede ajustar para evitar la intrusión de contenido incierto.

#### 9.3 usarlo en conversaciones

- **Búsqueda automática**: Verifique automáticamente la base de conocimientos cuando se le hagan preguntas relevantes y la respuesta tendrá una tarjeta de referencia ("Nombre del documento"· fragmento #N · puntuación).
- **@ Especificar documento**: Seleccione "Base de conocimientos de citas" en el panel más y especifique solo ciertos artículos para las respuestas.
- La inyección automática se puede desactivar en la configuración, pero `knowledge_search` La herramienta siempre está disponible y seguirá siendo consultada cuando sea necesario.

---

## parte 4 · Herramientas y automatización

### 10. Herramientas y permisos

#### 10.1 cual es la herramienta

AI No sólo puedes hablar, sino que también puedes hacerlo: comprobar el tiempo, enviar mensajes de texto, configurar despertadores, buscar en la web, leer y escribir archivos, generar imágenes y operar pantallas de teléfonos celulares.……Todo esto se hace mediante "herramientas". Incorporado en la versión actual **141 herramientas**, cubriendo aproximadamente:

- **vida**: Clima, hora, calculadora, calendario, despertador, cronómetro, recordatorio de programación, mapa, posicionamiento, teléfono, mensaje de texto, correo electrónico, libreta de direcciones;
- **Equipo**:Batería, red, almacenamiento,CPU, sensor, brillo, volumen, linterna, vibración, aplicación de primer plano,Wi-Fi / Estado de Bluetooth;
- **Procesamiento de contenido**: Portapapeles, traducción, código QR, generación de contraseña,Base64 / Herramientas Hash/JSON/URL;
- **Documentos e información**: Lectura y escritura de archivos del espacio de trabajo, resolución de enlaces,PDF Análisis, biblioteca de recursos, registro rápido, tareas pendientes;
- **generación de medios**: Imágenes, vídeos, lecturas de voz, emoticonos;
- **Redes**: Búsqueda, rastreo web, automatización del navegador (pasar página, clics, completar formularios, capturas de pantalla);
- **memoria**: Reparar memoria, guardar y eliminar memoria, buscar memoria y acumular experiencia;
- **Automatización**: Tareas programadas, notificaciones, comandos del sistema, terminal,UI Automatización (pantalla operativa);
- **ecología**: Canal de mensajes, conector, mercado de complementos,MCP Gerencia, subagente.

No es necesario recordar ningún nombre al usarlo: simplemente use el lenguaje natural para ordenarlo y seleccionará las herramientas, desmantelará los pasos y verá los resultados por sí solo. El proceso se puede ver en el diálogo (tarjeta de tareas, ver 4.8).

#### 10.2 Estratificación de permisos: primer vistazo al "estado listo"

Existe una categoría de herramientas que requieren autorización adicional del sistema (operar pantallas, ejecutar comandos, etc.). Cuando no hay autorización,Muse Se marcará como "no listo"; fallará inmediatamente cuando se le llame y le indicará dónde ir para abrir los permisos. No seguirá dando vueltas en círculos ni fallará silenciosamente. Para conocer el método de activación, consulte el asistente de configuración de permisos en el Capítulo 11.

#### 10.3 Aprobación: Si hay alguna influencia, consultar con antelación

- **Tres modos generales**(ajustes → charlar → aprobación de herramienta):
  - Delegación completa de autoridad: todas las herramientas se llaman directamente sin preguntar (adecuado para un conjunto de herramientas confiables);
  - Aprobación a pedido (recomendado): haga pequeñas cosas seguras automáticamente y solicite primero acciones de alto riesgo;
  - Modo estricto: excepto las consultas de solo lectura, todas las llamadas a herramientas requieren su confirmación.
- **estrategia de herramienta única**(ajustes → Aprobación de herramientas; también disponible en el Centro de Conexión → Permisos de herramientas (Introducir): establezca "Permitir/Preguntar/Denegar" por separado para cada herramienta. La página se muestra por nivel de riesgo y se cuenta el número de cada estrategia.
- La ventana emergente de aprobación tiene una cuenta regresiva y será rechazada automáticamente si caduca (seguridad).
- Las acciones de alto riesgo, como enviar mensajes de texto, marcar y eliminar archivos, se deben solicitar primero en "Aprobación a pedido".

#### 10.4 Trabajar de forma mesurada

- Para guardar contexto,Muse No todas las herramientas se incluirán en el modelo: las herramientas de uso común están disponibles directamente y las habilidades impopulares se "encontrarán" primero cuando sea necesario (find_tools) y llame nuevamente.
- Cuando la misma herramienta falla repetidamente, o no hay progreso muchas veces consecutivas, se detendrá automáticamente y explicará los motivos, y no continuará actualizando la pantalla.
- Las herramientas de comando del sistema están sujetas a una lista blanca (consulte el Capítulo 11); Los permisos de extensiones externas, como los complementos, se administran de forma independiente (consulte el Capítulo 14).

---

### 11. Operaciones a nivel del sistema:UI Automatización y terminales

Para permitir que Muse "lo haga por usted", primero debe abrir los permisos del sistema. Este capítulo habla sobre cómo abrir permisos y qué se puede hacer después de abrirlos.

#### 11.1 Asistente de configuración de permisos

Entrada:**ajustes → Asistente de configuración de permisos**. Cuatro canales, de bajo a alto:

| canal | Método de activación | ¿Qué se puede hacer? |
| --- | --- | --- |
| Servicios de accesibilidad | Activar un interruptor en la configuración del sistema | Lea el contenido de la pantalla, simule clics y diapositivas,UI Es suficiente para la operación. |
| Shizuku | Instale Shizuku y autorícelo (recomendado) | Comandos a nivel de sistema, capturas de pantalla y operaciones más silenciosas |
| Root | El dispositivo está rooteado y se puede solicitar con un clic en el asistente. | Control total del sistema con acceso a otros datos de la aplicación. |
| Termux(extensión opcional) | Instalar Termux + configuración de una línea | Entorno Linux completo (apt / pip / gcc etc) |

- El "Nivel de permiso actual" se mostrará en la parte superior del asistente y cada elemento tiene botones de activación y detección de estado;
- Termux Se requiere configuración única: ejecutar en Termux

  ```
  echo 'allow-external-apps = true' >> ~/.termux/termux.properties
  ```

  Y reinicie Termux; el asistente detectará automáticamente si la configuración ha tenido efecto.

#### 11.2 UI Automatización

Entrada:**ajustes → UI Automatización**. Hay tres capas de tarjetas de habilidad en la página (Accesibilidad / Shell (Shizuku)/ Root), muestran sus respectivos estados y el botón "Ir a Abrir";Root Las solicitudes de autorización se pueden iniciar directamente dentro de la página.

hay en el fondo**"Prueba de lectura de pantalla"**: Al hacer clic en él, se leerá la pantalla y se mostrará la aplicación actual, la cantidad de controles, la resolución y la fuente de datos, que se utiliza para verificar que los permisos realmente se hayan aprobado.

Una vez activado, se puede utilizar para realizar tareas entre aplicaciones, como "abrir una aplicación, buscar un interruptor en la configuración y encenderla". El efecto específico depende del modelo y del canal de permisos.

#### 11.3 terminales

Entrada:**ajustes → terminales**. Una página de línea de comando real: ingrese comandos, borre la pantalla, reinicie la sesión, interrumpa (Ctrl-C).

Hay tres entornos que puede ejecutar:

- **El sandbox que viene con la aplicación. shell**: Aislado en el espacio propio de la aplicación;
- **Canal de comando del dispositivo**(Se requiere Shizuku/Root): se puede ejecutar `dumpsys`,`settings`,`am`,`pm`,`input`,`screencap` Espere los comandos del sistema, pero sujeto a restricciones de la lista blanca——Se rechazarán las canalizaciones, redirecciones y concatenación de comandos;
- **Termux**: Después de acceder, es un entorno Linux completo.

Puedes escribir comandos tú mismo aquí,AI También lo uso cuando trabajo.

#### 11.4 espacio de trabajo

Entrada:**ajustes → espacio de trabajo**. El "espacio de trabajo" es el archivador de AI: lee, escribe, mueve y elimina archivos en este directorio controlado y no tocará otros lugares de su teléfono. Puede ver y administrar estos archivos aquí.

**Montaje de múltiples directorios**: Además del espacio de trabajo predeterminado, también puede montar otros directorios en el teléfono (álbumes de fotos, descargas, una determinada carpeta de proyecto), y cada punto de montaje tiene su propio nombre. Al montarlo, se puede configurar en**solo lectura**,AI Puede leerlo y copiarlo, pero no puede cambiarlo ni eliminarlo. En la sesión, también puede limitar qué puntos de montaje solo se pueden operar para evitar que vayan a lugares irrelevantes.

**frontera**: No importa cuántos directorios estén montados, la lectura y escritura se limitan al rango de montaje y las rutas fuera de límites se rechazarán directamente.

#### 11.5 Automatización aún mayor

- **automatización del navegador**:AI Puede abrir páginas web, hacer clic, completar formularios, desplazarse y extraer contenido usted mismo (la "Transmisión en vivo de la cápsula" en el Capítulo 7 es para verlo funcionar);
- **agente visual (ui_agent)**:Captura de pantalla → Juicio del modelo visual → Haga clic / Ingrese → Luego, tome capturas de pantalla para comprobar y completar tareas complejas de la interfaz paso a paso.

---

### 12. Asistente: utilizado por varias "personas" juntas

#### 12.1 que es asistente

Cada asistente es una "persona" completa e independiente: su propio nombre, avatar, configuración de roles, modelo, herramientas, memoria, base de conocimientos y habilidades. Hay uno para escribir, programar, traducir y chatear sin interferir entre sí. La aplicación viene con un "asistente predeterminado" por defecto; De forma predeterminada, un nuevo asistente solo puede leer su propia memoria de hechos y conversaciones recientes.

#### 12.2 Crear, cambiar, delegar

- **Nuevo**: Configuración → mi asistente → asistente → Crea un nuevo asistente.
- **cambiar**: en la página de chat, mantenga presionado el título para seleccionar una persona; o toque el ícono en la sección "Socios horizontales" en la página de inicio para comenzar a chatear directamente.
- **delegar**: Dijo en el diálogo "@"Un asistente me ayudará a manejar esto", el asistente actual le asignará el trabajo TA——El subasistente completa la tarea de forma independiente utilizando su propia personalidad y habilidades, y el proceso se puede ver en la tarjeta de tareas. Cada asistente tiene su propia memoria y no habrá confusión.
- **Mensajes privados entre asistentes.**: Configuración → Agent → Agent Los mensajes privados le permiten ver los mensajes intercambiados entre asistentes.

#### 12.3 Detalles del asistente: cinco subpáginas

Haga clic en un asistente y habrá cinco subpáginas de arriba a abajo:

- **Conceptos básicos**: nombre, avatar (emoji / Imagen/letra inicial), modelo exclusivo o valor predeterminado global, parámetros de muestreo (temperatura,Top P, Token máximo, nivel de razonamiento, número de mensajes contextuales, salida de transmisión), introducción de una frase, nombre del asistente que se muestra en el chat, si se permite unirse al chat grupal.
- **palabra rápida**:System Prompt(configuración de roles y reglas), plantillas de mensajes, mensaje predeterminado JSON.
- **Expandir**:Mensaje rápido,Lorebook,Prompt inyección, habilidades,MCP, herramientas, base de conocimientos——Una vez comprobado quedará vinculado, y el asistente tendrá prioridad para utilizar estos recursos a partir de ahora.
- **memoria**: Cambio de memoria, si se utiliza adicionalmente el retrato de usuario global/Memoria de pines/Comparta experiencias, referencias de conversaciones recientes, recordatorios de tiempo y agregue recuerdos manualmente. Desactivar la memoria global no desactiva la memoria de hechos del asistente ni las conversaciones recientes.
- **Avanzado**: encabezados de solicitud personalizados/cuerpos de solicitud, etiquetas y etiquetas de capacidad (para coincidencia de enrutamiento automático), y**Reglas de reemplazo regulares**(Reemplace automáticamente los nombres de los personajes, filtre el texto, etc.; también se puede configurar para que solo afecte la visualización y no cambie el texto original enviado al modelo).

#### 12.4 Cómo escribir "persona" como

El tutorial oficial divide la configuración del asistente en tres niveles, de adentro hacia afuera:

1. **Capa de personaje**: quien es——Nombre, identidad, antecedentes, antecedentes del personaje;
2. **capa de relación**: ¿Qué tiene que ver contigo?——Amiga, maestra, ama de casa, compañera, como quieras llamarte;
3. **capa de estilo**: ¿Cómo habla?——Conciso o detallado, animado o tranquilo.

Puede escribir "palabras rápidas" en los tres niveles. → "Configuración de carácter" y luego haga coincidir la configuración global de "Tono/Estilo de tono" (consulte la Sección 3.4). Hay instrucciones más detalladas en la aplicación: Configuración → Tutorial de uso.

#### 12.5 Compartir e importar

- **Exportar**: Hay "Compartir tarjeta de personaje" en la parte inferior de los detalles del asistente.——Las tarjetas de personajes PNG se pueden exportar (SillyTavern formato),JSON(SillyTavern V2), o comparte tarjetas de personajes de Muse directamente con amigos.
- **importar**: La página de lista de asistentes admite la importación de tarjetas de personajes, incluido el formato SillyTavern (PNG / JSON universales). Puedes usar las cartas de personajes de otras personas tan pronto como las obtengas.

---

### 13. Chat grupal y colaboración entre múltiples agentes

#### 13.1 Chat grupal: permita que los asistentes tengan una reunión

Involucre a varios asistentes en la misma conversación y discútala como personas reales.

- **crear**: Lista de chat grupal de la página de inicio → Crea un nuevo chat grupal; complete el nombre, seleccione miembros (al menos uno) y, opcionalmente, asocie un "equipo".
- **cuatro modos de discusión**:
  - Habla por turnos: envías un mensaje y cada IA ​​habla durante un turno;
  - Discusión libre:AI Continúa automáticamente la conversación entre ellos y se detiene cuando se alcanza el límite superior o nadie responde la llamada;
  - Modo debate: cadena fija "proponiendo un plan" → pregunta → “Mejora”, cada persona debe responder a la anterior;
  - Modo anfitrión: Designar a un miembro para que sea el anfitrión, responsable de unirse al programa y promocionar el evento (volviendo a la rotación cuando no se selecciona ningún anfitrión).
- **Control de conferencias**: La barra de conferencias puede pausar, continuar y omitir los discursos restantes; También puede configurar la duración del discurso (corto/estándar/exhaustivo).
- **tarjeta de resumen de discusión**: Organice la discusión en un resumen con un clic, que se puede copiar, compartir, guardar como un documento compartido o "continuar la implementación".
- **votar**: Que los asistentes voten, cada uno vote a favor/en contra según su personalidad y dé sus razones.
- **plantilla rápida**: Revisión del plan, debate de pros y contras, lluvia de ideas, revisión del proyecto, inicio con un clic (los parámetros de discusión correspondientes se preestablecerán automáticamente).
- **observador**: Puede configurar miembros que solo miren y no participen y se turnen para hablar.
- **Contexto del chat grupal**: En la reunión, puedes ver el "documento compartido del grupo" (visible para todos los miembros) y el "contexto exclusivo" de cada IA.
- **@menciones y susurros**: @ los miembros se nombran en el cuadro de entrada; también puedes susurrarle a un miembro individualmente; Los mensajes admiten respuestas a citas. Los chats grupales se pueden buscar, fijar y editar.
- Cada asistente mantiene su propia memoria en el chat grupal, por lo que no habrá confusiones.

#### 13.2 Colaboración entre múltiples agentes (equipo)

- Entrada:**ajustes → Agent → Colaboración entre múltiples agentes**.
- Cree un "equipo": asígnele un nombre, describa los objetivos, seleccione los miembros (plantilla incorporada de "equipo de redacción": borrador → primer borrador → polaco).
- Flujo de trabajo configurable: defina el orden de ejecución, las dependencias y las estrategias resumidas de los miembros.
- Después de encenderlo, el asistente principal puede delegar tareas a los miembros del equipo y todo el proceso de colaboración se puede ver en la tarjeta de tareas.

#### 13.3 Agent mensaje privado

Los asistentes pueden enviarse mensajes privados entre sí (como comunicaciones durante el proceso de delegación). en **ajustes → Agent → Agent mensaje privado** Consulte la correspondencia aquí y apoye la selección de todos los leídos.

---

### 14. Habilidades, complementos y MCP

#### 14.1 Habilidades (Skill)

Las habilidades son "herramientas distribuibles" que añaden nuevas capacidades al asistente: generación de imágenes, traducción, códigos QR, operaciones de memoria, tareas pendientes, tarjetas, notificaciones, delegación de subtareas, etc.

- Entrada:**ajustes → mi asistente → Recursos asistentes → Habilidades**, o se puede acceder directamente desde el panel más en el campo de entrada del chat.
- Cada habilidad se puede habilitar/deshabilitar individualmente; el formato de importación es `.skill.json`.
- Algunas habilidades admiten parámetros de entrada, que le permitirán agregar contenido cuando se utilicen.
- Al importar, se realizará una revisión de seguridad (para evitar la rápida inyección de palabras y el camuflaje de identidad del sistema), y aquellos que no cumplan serán rechazados.

#### 14.2 Complementos (extensiones externas)

- Entrada:**ajustes → Gestión de complementos**(También disponible en el Centro de conexión → Complemento (entrar); la página está dividida en tres niveles: "Instalado/Mercado de complementos/Gestión de confianza".
- **importar**:`.muse-plugin` O paquete de complemento ZIP; antes de la instalación, se mostrarán la versión, el autor, las capacidades de declaración, las herramientas proporcionadas y las huellas digitales del código, y luego se instalarán después de la confirmación.
- **Firma y confianza**: El complemento tiene verificación de firma.——Hay varios estados: "Verificado y confiable por esta máquina", "La firma es válida pero el emisor no es confiable" y "Sin firmar"; unsigned sólo se puede guardar como borrador deshabilitado y no se ejecutará; Los emisores desconocidos requieren que usted confíe manualmente después de verificar la huella digital de la clave pública.
- **Deje que AI escriba complementos**: Puede dejar que el asistente redacte un complemento en el sitio; genera un borrador sin firmar. Después de revisar la lista de herramientas y el código en la administración de complementos, haga clic en "Firmar y activar" y entrará en vigor solo después de que esté firmado por la clave del autor local.
- **mercado de complementos**: Directorio oficial incorporado, que se puede explorar e instalar con un solo clic (administración unificada en la pestaña "Instalado"); la dirección del directorio y la raíz de confianza se pueden personalizar durante la prueba.
- El código del complemento se ejecuta en un entorno limitado de JS y las capacidades y riesgos se indican claramente.

#### 14.3 MCP(Model Context Protocol)

- Entrada:**ajustes → centro de conexion → MCP**.
- Agregar servidor MCP externo (SSE / StreamableHTTP transmisión; transmisiónAndroid El stdio local no es compatible).
- Token de portador de soporte, encabezado de solicitud personalizado,OAuth Autorización y revocación; reconexión automática cuando se desconecta.
- Tras acceder, las herramientas proporcionadas por el servidor quedarán registradas automáticamente (con el nombre `mcp_` prefijo), también puede explorar los recursos y mensajes expuestos por el servidor.
- MCP Herramientas aisladas por asistente: sólo si **Detalles del asistente → Expandir → MCP servidor** Sólo los servidores vinculados explícitamente al servidor estarán expuestos al asistente.
- Determinación de riesgos: incluyendo delete / send / execute Las herramientas que esperan palabras de operación deben aprobarse cada vez;read / list / get Las clases se ejecutan directamente; el resto se basa en su política de aprobación.

#### 14.4 conector(OAuth autorización)

Se utiliza para acceder a servicios de terceros que requieren autorización de inicio de sesión; Las credenciales se cifran y almacenan localmente. Si no puede encontrar la entrada, busque "Conector" directamente en la parte superior de la página de configuración.

---

### 15. Canales de mensajería: ponga IA en su IM

#### 15.1 que puede hacer

Después de la configuración,Muse Puede enviar mensajes a su software de chat; También puedes recibir mensajes de estas plataformas y que el asistente responda automáticamente.——**Los canales son conversaciones.**, cada diálogo tiene una memoria independiente.

#### 15.2 Plataformas compatibles

| plataforma | Método de acceso |
| --- | --- |
| WeChat | Escanee el código QR para vincularse (acceda a la cuenta personal a través del protocolo iLink); una cuenta de WeChat solo puede vincularse a un canal |
| QQ | Robot de plataforma abierta (App ID / App Secret); el objetivo puede ser un chat grupal o un chat individual |
| Feishu | Aplicación de creación propia (App ID / App Secret), apoya a China Feishu/en el extranjero Lark |
| Telegram | Obtenga Bot Token de @BotFather; complete chat_id como destino de envío (se puede dejar en blanco y se enviará automáticamente de regreso a la fuente) |
| DingTalk | Robots empresariales (Client ID / AppSecret / Robot Code) |

#### 15.3 Acceso y configuración

- Entrada:**ajustes → Herramientas y conexiones → centro de conexion → canal de mensajes**. La página viene con un "¿Cómo conectarse?" tutorial, que proporciona orientación paso a paso para cada plataforma y está equipado con un punto de verificación de "Conexión de prueba".
- Las credenciales solo se almacenan cifradas localmente y no se cargarán.
- **respuesta automática**: Después de abrir, al recibir un mensaje de este canal, el asistente se ejecutará y enviará automáticamente (la cuota del modelo se consume en cada ronda); puedes elegir qué asistente responderá (heredando la configuración, el modelo y los parámetros).
- **"¿Qué cuenta es la tuya?"**: Especifique su propia cuenta como destino de envío de este canal.

#### 15.4 Diálogo del canal

- Cada contacto/grupo creará automáticamente una "conversación de canal" con contexto independiente y resumen de desplazamiento.
- Puede expandir para ver el registro del mensaje o "reiniciar contexto" para borrar el historial y comenzar de nuevo.
- Hay "Recibidos recientemente" en la página del canal de mensajes, que muestra los mensajes recién recibidos.

> Instrucciones especiales para WeChat: los recordatorios activos se basan en la "ventana de respuesta después del último mensaje recibido". Si el recordatorio no se entrega, puede activar la ventana enviando primero un mensaje de WeChat a este canal.

---

### 16. La mensajería activa y su vida.

#### 16.1 mensajes no solicitados

ajustes → Mensajes proactivos (o desde Configuración → Agent entrar).

- **interruptor + intervalo**: Desde 15 minutos hasta días, puedes configurarlo libremente; También hay un "desplazamiento aleatorio" para permitir que el tiempo de envío se desvíe de forma natural (0 durante un intervalo fijo).
- **Nivel de iniciativa**Frecuencia: Pocas (aproximadamente una vez cada medio día, máximo 1 por día)/ Estándar (aproximadamente una vez cada 4 horas, hasta 3 mensajes por día)/ Muchos (aproximadamente una vez cada 2 horas, hasta 8 mensajes por día)/ Personalizar.
- **No molestar periodo**: No se enviará durante este periodo para no molestaros por la noche.
- Los detalles incluyen: período de tiempo permitido, límite diario (1–10 tiras o infinito), probabilidad de envío (0–100%), contenido activo (resumen diario/diario nocturno/actualizaciones de momentos) y “probar mensajes activos” con un solo clic.
- Decidirá si venir y cuándo en función del calor de la conversación, el estado de ánimo y su actividad.

#### 16.2 Resumen diario, acción nocturna y círculo de amigos.

en **ajustes → Agent** Aquí:

- **Empuje de resumen diario**: Se generarán resúmenes de conversaciones periódicamente varias veces al día. Aquí puede seleccionar el período de tiempo para enviar el resumen a la notificación (temprano en la mañana/mañana/mediodía/noche pueden ser selecciones múltiples).
- **Actuar de forma autónoma a altas horas de la noche**: Por la noche, organiza su memoria y escribe un diario sin molestarte.
- **Número diario de publicaciones en Momentos**: Publica varias actualizaciones todos los días y se puede desactivar.

#### 16.3 Momentos

- Muse Publicarás actualizaciones (imágenes o texto sin formato) con tus asistentes y podrás darles me gusta, comentarlas y guardarlas.
- La página "Noticias" le recordará quién le dio me gusta y comentó sobre usted; cada uno tiene su propia página de inicio.
- En la esquina superior derecha, puede publicar sus propias actualizaciones (presione brevemente las imágenes y el texto/presione prolongadamente el texto sin formato) o "generar una actualización inmediatamente"; las actualizaciones también admiten búsqueda y "ver sólo favoritos".

#### 16.4 pequeño teléfono celular

- Ubicación:**ajustes → pequeño teléfono celular**(Marcado en la aplicación como "Versión anterior de la función experimental"). Después de abrir, aparecerá una entrada en la esquina superior derecha de la página de inicio.
- Un mini escritorio inmersivo: círculo de amigos,AI Álbum de fotos, notas, clima, diario modelo.
- Las imágenes del álbum se pueden ver en pantalla completa, guardar en el álbum del sistema u ocultar.

#### 16.5 Hitos y estadísticas

- **hito**: Se grabarán momentos memorables (primera conversación, número de días que pasamos juntos, número de mensajes, etc.) y podrás filtrarlos y revisarlos por "Milestone" en la línea de tiempo del centro de memoria.
- **Estadísticas**: Configuración → Estadística. Admite rango de tiempo (hoy/esta semana/este mes/todos), número total de conversaciones, número de mensajes, número de días activos consecutivos, día más activo, tendencia semanal, mapa de actividad y proporción de uso del modelo. También hay estadísticas de memoria separadas (distribución de importancia, sesiones más activas, tendencias de los últimos 30 días) en el Centro de memoria.

---

### 17. Apariencia y personalización

#### 17.1 Tema

Entrada:**ajustes → Apariencia**.

- **12 conjunto de temas**: Papel cálido, flor de cerezo, océano, primavera, otoño, blanco y negro, tinta, papel japonés, tinte índigo, rima púrpura anochecer, oro ámbar, rosa anochecer. Cada juego viene con dos versiones, clara y oscura.
- **modo tema**: Siga el sistema/color claro/color oscuro; puede elegir un conjunto separado de colores oscuros o puede "seguir el tema de colores brillantes".
- **Conmutación programada**:Establecer "hora de despertar (color de luz)"/ "Hora de dormir (oscuro)", cambia automáticamente cuando llega la hora.
- **Selección de color dinámica**: Siga el fondo de pantalla del sistema para elegir colores (Android 12+).
- **tema de alto contraste**: Mejora el contraste entre primer plano y fondo, lo cual es conveniente para usuarios con baja visión.

#### 17.2 Fuentes y burbujas

- **tamaño del texto**: Pequeño / Mediano / Grande / Extra Grande (0.85x / 1x / 1.15x / 1.3x), con vista previa en tiempo real.
- **mensaje esquinas redondeadas**: Cuadrado / Esquinas redondeadas / Esquinas redondeadas grandes / Cápsula.
- **piel de burbuja**: Además de la máscara predeterminada, también se admiten máscaras proporcionadas por complementos.
- Todos los íconos de aplicaciones son conjuntos de íconos autodibujados (íconos de musa del proyecto de código abierto, 176 en total).

#### 17.3 Fondo de chat

ajustes → charlar → Apariencia: personalice la imagen de fondo del chat/Agente/chat grupal; También puedes usar un degradado de dos colores cuando no hay una imagen de fondo.

#### 17.4 Retrato de usuario

Ubicación:**ajustes → tarjeta de cuenta superior → Retrato de usuario**.

Puede completar: su título, autoidentificación del asistente, perfil personal, edad, ciudad, ocupación, campo profesional, formación académica, habilidades y experiencia, pasatiempos y pasatiempos, estilo de comunicación, preferencia de longitud de respuesta, tono preferido, idioma de respuesta preferido, límites y tabúes, zona horaria.……Todo opcional.

Esta información ingresa a las indicaciones del sistema de la IA, lo que le permite comprenderlo realmente. Entre ellos, el "perfil personal" tiene la máxima prioridad.

---

### 18. Datos, respaldo y seguridad

#### 18.1 ¿Dónde están los datos?

Los diálogos, la memoria, la base de conocimientos y la configuración del asistente se almacenan localmente en el teléfono. No es necesario registrarse ni iniciar sesión; la función de red está desactivada de forma predeterminada; los registros de fallos solo se guardan localmente.

#### 18.2 Copia de seguridad y recuperación

Entrada:**ajustes → Datos y respaldo**.

- **copia de seguridad local**: Exportación con un solo clic de todos los datos (sesiones, mensajes, recuerdos, etc.) como JSON estándar (las bases de datos grandes se pueden exportar en fragmentos); establezca la contraseña de respaldo y luego cifre (AES-256). La recuperación de importación sobrescribirá los datos actuales, pero primero se generará una copia protectora y se revertirá automáticamente si falla.
- **Copia de seguridad en la nube**(ajustes → Datos y respaldo → Copia de seguridad en la nube): admite almacenamiento compatible con WebDAV / S3 (Nut Cloud,Nextcloud etc.); puede configurar el intervalo de copia de seguridad automática (1–30 días); la lista de copias de seguridad remotas puede restaurar cualquier versión y eliminar copias de seguridad antiguas.
- **Registros de respaldo automáticos**: Configuración → Datos y respaldo → Registros de respaldo recientes; haga clic en cualquiera para restaurar (avisar antes de restaurar, reversión automática si falla).

#### 18.3 Pasar de otras aplicaciones

Entrada:**ajustes → Importación de datos**.

- apoyo **RikkaHub / Kelivo** Backup ZIP: Importar configuración de proveedores, roles de asistente, registros de conversaciones; se fusionan automáticamente, los duplicados no se sobrescribirán.
- También apoya **ChatGPT Datos exportados**, y **CherryStudio / Chatbox** JSON exportado (configuración de proveedor y modelo).
- 「Muse El portal "Native Backup" puede importar sus archivos de copia de seguridad exportados previamente (se sobrescribirán los datos locales actuales).
- Se recomienda hacer una copia de seguridad de los datos actuales antes de importar.

#### 18.4 Gestión de datos

Entrada:**ajustes → Gestión de datos**. Ver el uso (número de conversaciones / número de mensajes / número de recuerdos / base de datos y tamaño de caché) y tres operaciones: borrar todas las conversaciones, borrar el caché y restablecer la memoria (lo que significa que no lo reconoce nuevamente). Cada operación tiene una segunda confirmación.

#### 18.5 Compartir plantilla

ajustes → Datos y privacidad → Compartir: controle si se incluye la marca de tiempo, el nombre del modelo,Token Números, bloqueos de humor, contenido de razonamiento; formato opcional Markdown / texto sin formato / HTML.

#### 18.6 Privacidad y seguridad

- **PII Protección de la privacidad**(Habilitado de forma predeterminada): bloquea automáticamente información confidencial como número de teléfono celular, dirección de correo electrónico, tarjeta de identificación, etc. antes de enviarla. Todo lo que ve el proveedor de servicios es el marcador de posición y se restaurará al responder.
- **Confirmación de enlace**: Haga clic en el enlace del mensaje y primero aparecerá un mensaje de confirmación (puede elegir que no se le recuerde nuevamente en esta conversación).
- **agente**: Admite proxy de red personalizado.
- **Registro de auditoría**: Seguimientos de operaciones clave (API Llamada/Acciones del usuario/Autenticación/Sistema/Ejecución del agente).
- **protección contra accidentes**: Los registros de fallos solo se guardan localmente; en los raros casos en los que no se puede iniciar, el inicio en modo seguro está disponible y no se perderán datos.

#### 18.7 Experimental

ajustes → Experimental: cambios para funciones no estabilizadas (recuperación de archivos adjuntos de sesión, salida MOOD forzada, modo de depuración, autorreflexión, compresión de memoria larga, paralelismo de herramientas de solo lectura, etc.), pruebe cosas nuevas a pedido.

---

### 19. Versión web y más entradas

#### 19.1 Versión web (Web servidor)

Ubicación:**ajustes → Datos y respaldo** En la pagina "Web Bloque "Servidor".

- Después de habilitarlo, el teléfono celular se convierte en un pequeño servidor y los dispositivos con el mismo Wi-Fi pueden acceder a él mediante un navegador; el puerto se puede personalizar (1024–65535).
- El acceso LAN está cerrado de forma predeterminada y debe abrirse manualmente; solo se permite el acceso local de forma predeterminada.
- Seguridad: se requiere PIN de 6 dígitos para la primera visita (generado automáticamente, copiable y regenerable); y contraseña de inicio de sesión (8 Poco aleatorio); Se puede habilitar HTTPS (certificado autofirmado, la primera vez que visite el navegador le indicará que no es de confianza).

#### 19.2 Widgets y atajos de escritorio

- **widget**: Hay dos tipos: "Nueva conversación/conversación reciente" y "Última respuesta de IA", que se pueden agregar al escritorio para acceder con un solo clic.
- **Mantenga presionado el ícono de la aplicación**: Nueva conversación/Traducción/Entrada de voz/Configuración/Taquigrafía.
- **atajos de teclado**(Dispositivos con teclado):Ctrl+K buscar,Ctrl+N nueva conversación,Ctrl+, Abrir configuración,Ctrl+Shift+C Copiar respuesta,Enter enviar,Shift+Enter avance de línea,Esc Cerca.

#### 19.3 registro rápido

- Ubicación:**ajustes → registro rápido**(O mantenga presionado el ícono de la aplicación para seleccionar "Taquigrafía").
- Recuerda el título, texto,`#etiqueta`; Admite clasificación de carpetas, fijación, búsqueda y configuración de recordatorios; Los archivos adjuntos se pueden agregar, cifrar y exportar a Markdown/JSON.
- Puede "enviar a una nueva conversación" y dejar que AI se encargue de esta idea por usted.
- Hay una papelera de reciclaje: los registros eliminados se pueden recuperar o eliminar permanentemente.
- Admite la captura rápida de ventanas flotantes (habilitada en la configuración de grabación rápida).AI También puede ayudarle a registrarlo (el mismo conjunto de registros que aquí).

#### 19.4 escucha de notificaciones

- Ubicación:**ajustes → escucha de notificaciones**. Después de la autorización,AI Puede leer notificaciones de teléfonos celulares (como entregas urgentes y mensajes de otras personas) y responder llamadas o recordatorios en consecuencia.
- Puede elegir qué fuentes de texto de notificación pueden ingresar al contexto de IA; Los mensajes admiten filtrado (todos/no leídos/barra de notificaciones únicamente), exportación y limpieza.
- Compromiso de privacidad: el contenido de las notificaciones solo se almacena localmente (hasta 200 elementos, se descartarán los más antiguos); se filtrarán las notificaciones de la interfaz del sistema y del propio Muse; Los registros se pueden borrar o revocar la autorización en cualquier momento.

#### 19.5 Página de traducción independiente

- Ubicación:**ajustes → Traducción**. Seleccione el idioma de destino, ingrese o pegue el texto para traducir y los resultados se pueden copiar con un solo clic.
- La entrada de traducción para un solo mensaje se encuentra en el menú de pulsación larga del mensaje (ver 4.4).

---

## Preguntas frecuentes

**Q:¿Cuesta dinero?**
A:Muse Ontology es gratuito y de código abierto, sin compras ni suscripciones dentro de la aplicación; las únicas cosas que cuestan dinero son las API de los proveedores de servicios de IA (facturación de pago por uso, la mayoría de los proveedores de servicios le darán créditos gratis cuando se registre). Si desea guardar dinero, puede configurar un modelo económico para el dispositivo en el "modelo auxiliar" (ver 3.3).

**Q: ¿Dónde se almacenan los registros y recuerdos del chat? ¿Es seguro?**
A: Todo localmente en su teléfono celular, sin registro, sin inicio de sesión, sin carga. Se recomienda ejecutar una copia de seguridad para mantener los datos en sus propias manos (consulte el Capítulo 18).

**Q: Después de cambiar de modelo, ¿todavía me recuerda?**
A: Recordar. La memoria se almacena localmente en Muse y no tiene nada que ver con el modelo; Puedes cambiar de proveedor o modelo en cualquier momento sin perder la memoria.

**Q:¿Por qué a veces no respondes durante mucho tiempo?**
A: Razones comunes: pensamiento profundo, herramientas de llamada o el proveedor de servicios ascendente está abarrotado. Verifique si hay una tarjeta de tarea o un estado de "pensamiento" en la conversación. Si es así, significa que estás trabajando. Si espera demasiado, puede detenerse y reenviar.

**Q: ¿Cómo hacer que recuerde cosas importantes?**
A: Simplemente diga "Recuerde:XXX”. Las memorias fijas están disponibles inmediatamente sin esperar a que se ordenen en segundo plano (ver 8.3).

**Q: ¿Qué debo hacer si lo recuerdo mal?**
A: Abrir configuración → En el centro de memoria, busque el elemento que desea cambiar o eliminar; Puedes ver el origen y el historial de modificaciones de cada memoria.

**Q: ¿La herramienta no responde y solicita permiso?**
A:Ir a Configuración → El asistente de configuración de permisos abre el canal correspondiente; si no está autorizada, la herramienta le indicará claramente lo que falta y no fallará silenciosamente (consulte el Capítulo 11).

**Q:¿Cómo actualizar?**
A: "Configuración" en la aplicación → Buscar actualizaciones". Cuando se encuentre una nueva versión, será dirigida a la página de descarga del sitio web oficial (museai.ltd/download) para obtener el nuevo paquete; simplemente sobrescriba e instale sin perder datos.

**Q: ¿Cómo mover un teléfono celular nuevo?**
A: haga una copia de seguridad del teléfono antiguo (archivo local o copia de seguridad en la nube) y restáurela en el teléfono nuevo (consulte 18.2).

**Q: ¿Quieres que me moleste menos o que venga más a mí?**
A: Configuración → Mensajes activos: ajuste el intervalo y el nivel de iniciativa, o establezca un período de no molestar (ver 16.1).

**Q: ¿Cómo guardar tráfico y energía?**
A: Búsqueda de red y cambio de pensamiento en profundidad bajo demanda; La generación de imágenes/vídeo y la reindexación de la base de conocimientos consumen la mayor cantidad de recursos, por lo que se recomienda hacerlo mientras se carga.

---

## Apéndice A: Copia de seguridad y formato de archivos de datos

- **exportación local**: JSON estándar; cuando la cantidad de datos es grande, se exporta en NDJSON fragmentado (la primera línea son metadatos, seguida de los registros uno por uno). Después de configurar la contraseña de respaldo, el archivo exportado se cifrará y se utilizará la misma contraseña para descifrarlo cuando se importe.
- **Paquete de respaldo automático**:`muse_backup_Marca de tiempo.zip`, que contiene instantáneas de la base de datos local (conversaciones, mensajes, recuerdos, hechos, etc.); se reemplaza toda la base de datos y el índice de búsqueda se reconstruye durante la recuperación, y se genera automáticamente una copia protegida antes de la recuperación y se revierte automáticamente si falla.
- **tarjeta de personaje**: El asistente se puede exportar a tarjetas de personajes PNG/JSON compatibles con SillyTavern (ver 12.5).
- **paquete de habilidades**:`.skill.json`(ver 14.1);**Paquete de complemento**:`.muse-plugin` o ZIP (ver 14.2).

## Apéndice B: Glosario

| palabra | significado |
| --- | --- |
| API Key | El bono de llamada del proveedor de servicios modelo, equivalente a un billete |
| proveedor / Provider | Fabricante o servicio de transferencia que proporciona el modelo. |
| Token | La unidad de medida utilizada por el modelo para procesar texto es más detallada que "palabra". |
| ventana contextual | La cantidad máxima de texto que el modelo puede "ver" al mismo tiempo |
| Pensamiento/razonamiento profundo | El proceso de redacción antes de responder es más lento cuanto más profundo es y más preciso es para preguntas complejas. |
| base de conocimientos / RAG | Los datos importados y las capacidades de búsqueda hacen que las respuestas estén bien documentadas |
| Skill | Extensiones de herramientas distribuibles |
| complemento | Paquetes de extensión de terceros, se ejecutan en un entorno limitado y requieren firma/confianza |
| MCP | Protocolos estándar para acceder a herramientas externas y fuentes de datos |
| Shizuku / Root / Termux | Tres esquemas de permisos a nivel de sistema (consulte el Capítulo 11) |
| MOOD | AI Bloque interno mostrado antes de la respuesta |
| canal | Acceso a plataformas de mensajería externas como WeChat/QQ/Feishu |
| mensajes no solicitados | AI Toma la iniciativa de hablar contigo según el ritmo. |

---

*Este manual se actualiza continuamente con versiones. Si encuentra alguna discrepancia, puede plantear un problema en GitHub o informarnos en el grupo de comunicación.*
