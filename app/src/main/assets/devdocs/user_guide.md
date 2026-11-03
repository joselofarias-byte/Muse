<!-- devdoc: Documentación de desarrollo interno.,No se muestra a los usuarios,LLM Consulta a través de Knowledge_search -->
# Muse Guía del usuario

Cuando el usuario pregunta"como usar""como usar""Instrucciones""Documentación de ayuda""Guía para principiantes""Cómo configurar""Introducción a la función""Tutorial""¿Cuál es esta función?""como operar"Consulte este documento al hacerlo. Utilice un lenguaje sencillo al responder,Evite la jerga técnica,Da ejemplos cuando sea necesario. Todas las entradas de funciones se basan en código real.,Si no lo encuentras, no lo inventes.

---

## 1.Muse que es

Muse Es una aplicación de asistente de IA para Android.,El posicionamiento central es"Cliente LLM complementario + plataforma de agente".

La mayor diferencia con la versión web ordinaria del chat AI:

- Tiene memoria.Muse Capacidad para refinar y compilar en resúmenes los hechos mencionados en las conversaciones.,Inyectado automáticamente durante la siguiente conversación,entonces"recuerda"Algo que dijiste antes.
- Puede actuar. No solo charlar,Puede leer y escribir archivos en el entorno limitado de la aplicación, buscar en Internet, configurar despertadores, enviar mensajes de texto, consultar calendarios, abrir aplicaciones, leer el portapapeles, etc.
- Puede tener múltiples personalidades. Puedes crear múltiples asistentes.,Cada uno tiene palabras, modelos, herramientas y recuerdos independientes del sistema.
- Es privado. Todos los datos se almacenan localmente en su teléfono,No subir a servidores de terceros(Excepto llamar a su propio proveedor de modelo configurado).
- Puede venir a ti. Después de configurar el mensaje activo,El asistente le enviará mensajes regulares como un amigo de WeChat.

---

## 2. Comience a usar

### Abierto por primera vez

Muse primer comienzo(Y cuando no hay un proveedor de modelos local)La página de inicio se mostrará en pantalla completa.,Dos pasos en total:

1. Introducción a la función paso uno:Desliza hacia la izquierda o hacia la derecha para ver 5 páginas de tarjetas de funciones(Chat, Asistente, Privacidad, Búsqueda en Internet, Herramientas),hay en el fondo"Siguiente paso"y"saltar".
2. Paso 2: Título personalizado:Complete los dos cuadros de entrada
   - Nombre del asistente(¿Cómo quieres llamar a tu asistente?,por ejemplo"Xiao Miao""JARVIS")
   - Nombre de usuario(¿Cómo quieres que te llame tu asistente?,por ejemplo"Xiaoming""jefe")
   - Estos dos títulos se escribirán en el retrato del usuario.,Afecta cómo se llama la IA y se dirige a ti en las conversaciones. Puede omitirlos dejándolos en blanco.
   - Dos botones en la parte inferior:"empezar"(Ir directamente a la interfaz principal)o"Agregar proveedor de modelo"(Ir a configuración API).

### Agregar proveedor de modelo

Página de guía"Agregar proveedor de modelo",O ve a Configuración más tarde → Modelos y Servicios → proveedor,OK:

- Agregar manualmente:Complete la clave API,Base URL, identificación del modelo.
- agregar desde preestablecido:Elija proveedores comunes(OpenAI / Anthropic / Google / DeepSeek espera).
- Escanear código para importar:Escanee el código QR para importar rápidamente la configuración del proveedor.
- Importar configuración de cliente de terceros:Admite la importación de exportaciones de CherryStudio y Chatbox JSON(Formato de rastreo automático,Sólo importar proveedores y modelos.,No importar el historial de conversaciones).

Después de emparejar al menos un proveedor,Regrese a la interfaz principal para iniciar la conversación.

---

## 3. Habla con el asistente

### Operaciones básicas

- enviar mensaje:Escriba en el cuadro de entrada inferior,Haga clic en el botón Enviar para enviar.
- Deja de responder:Mientras el asistente responde,El botón de enviar se convertirá en un botón de parada.,Haga clic para interrumpir.
- Colapso de mensaje largo:Las respuestas que sean demasiado largas se colapsarán automáticamente y mostrarán una máscara de degradado.,Haga clic para ampliar el texto completo.
- El contenido del mensaje admite representación Markdown, resaltado de código, fórmulas matemáticas y tablas.
- Rich media en las respuestas del asistente:SVG/HTML/Los bloques de código de gráficos se convierten en contenido visual(Los gráficos admiten gráficos de barras, gráficos de líneas y gráficos circulares.).
- tarjeta de producto:Las respuestas incluyen código, documentación,JSON Esperando contenido reutilizable,Se generarán tarjetas de productos en las que se puede hacer clic.,Conveniente para ver individualmente o copiar.

### pensamiento profundo

En el menú + en el lado izquierdo del campo de entrada, hay"pensamiento profundo"cambiar. Después de encenderlo, la conversación utilizará un razonamiento de alta intensidad.(8000 tokens Piensa en el presupuesto),Adecuado para problemas complejos. Nota:Este cambio solo tiene efecto para la conversación actual.,No persistente,Restaure la configuración predeterminada del Asistente después de cambiar de sesión o reiniciar la aplicación.

### búsqueda en internet

También hay un menú con el signo + en el campo de entrada."búsqueda en internet"cambiar. Después de activarlo, cada vez que haga una pregunta, los resultados de la red se buscarán primero como contexto. Usado por defecto Bing(atrapar cn.bing.com,No es necesario API Key),También apoya SearXNG(Autohospedado)y Tavily(necesidad Key),en la configuración → Modelos y Servicios → Web Búsqueda o configuración → charlar → Cambio de motor de búsqueda predeterminado.

### Mensaje de menú de pulsación larga

Pulsación larga(o haga clic)Cualquier mensaje hará aparecer el menú de operación.:

- Copiar:Copie el texto completo del mensaje al portapapeles.
- regenerar:Pídele al asistente que responda esta pregunta nuevamente.(Para mensajes del asistente).
- Eliminar:borrar este mensaje(Los contenidos publicados por error se pueden retirar así.,Aparecerá un cuadro de confirmación).
- Editar:Edite el contenido del mensaje del usuario y reenvíelo.
- Cotización:Responder con una cita de este mensaje(Aparecerá un bloque de cotización en la parte superior del mensaje.).
- Traducción:Traducir el mensaje al idioma de destino(punto"Traducción"Luego aparecerá un submenú de idioma para seleccionar el idioma de destino.).
- leer en voz alta:Lea este mensaje en voz alta con voz TTS(Esta opción solo está disponible para los mensajes del Asistente).
- Colección:Añade este mensaje a tus favoritos.
- Compartir:Comparta el contenido del mensaje con otras aplicaciones.

El último mensaje del asistente también mostrará un acceso directo adicional"Copiar"y"regenerar"botón,Se puede operar sin ingresar al menú.

---

## 4. Asistente de dirección

### Múltiples asistentes

Muse Admite la creación de múltiples asistentes,Cada uno es un individuo independiente.,Tiene su propio nombre, avatar, palabras de aviso del sistema, modelos, herramientas, recuerdos e historial de conversaciones. Viene con uno por defecto"Asistente predeterminado".

entrada:ajustes → asistente. Aquí puede crear, cambiar y eliminar asistentes.

### Detalles del asistente 5 subpáginas

Ir a configuración → asistente → Elige un asistente,Verás 5 subpáginas:

1. Conceptos básicos(Basic):nombre, clasificación,emoji Avatar o avatar de imagen, imagen de fondo de chat y transparencia.
2. palabra rápida(Prompt):Palabra de aviso del sistema(Configuración de personajes), plantilla de mensaje(apoyo {{variable}} marcador de posición), mensaje predeterminado(Contexto fijo inyectado antes de iniciar un chat.).
3. Expandir(Extensions):herramientas locales habilitadas,MCP servidor,Skill,Lorebook, mensaje rápido,Prompt Encabezados de solicitud personalizados e inyección/Cuerpo de solicitud.
4. memoria(Memory):Ya sea para inyectar memoria a largo plazo, si se usa memoria global, si se inyectan resúmenes de sesiones recientes y si se habilitan recordatorios de tiempo.
5. Avanzado(Advanced):identificación del modelo, temperatura,top-p, token máximo, número de mensajes de contexto, nivel de inferencia y si se debe transmitir la salida.

Cada asistente se configura de forma independiente,No se afecten unos a otros. Después de cambiar de asistente,Lo que ves son las conversaciones y configuraciones del asistente correspondiente.

---

## 5. Sistema de memoria

Muse la memoria tiene varias capas:

### memoria a largo plazo

durante la conversación,El sistema lo extraerá de los mensajes históricos. Fact(hechos),Guardar en base de datos,Luego compílelo en un resumen de rebajas. la próxima vez que hablemos,Este resumen se inyectará en la palabra de aviso del sistema.,Entonces asistente"recuerda"Algo que dijiste antes.

Puntos clave(Debe ser honesto con los usuarios.):

- memoria a largo plazo"Resumen compilado"inyección de forma,No hecho por hecho. Lo que ve el Asistente es el texto resumido.
- La refinación ocurre en puntos de tiempo diarios del oleoducto(Normalmente tarda unas horas),No en tiempo real. Es posible que aún no se recuerden los hechos que acaba de exponer.
- Detalles del cambio de memoria en el Asistente → subpágina de memoria(Habilitado por defecto).
- Longitud del resumen(token presupuesto)Disponible en Configuración → Ajuste de memoria.

### Pinned Memories(memoria fija)

poner"debe recordar"La información clave es fija.,no depende de daily pipeline,Inyectado en cada conversación.

- Dilo directamente en la conversación."recuerda:no tomo cafe",El asistente lo fijará usando la herramienta pin_memory.
- También puedes configurarlo en Configuración → asistente → Las páginas de memoria se agregan manualmente.
- La memoria fija existe en archivos locales.,Protegido por tamaño máximo y número de entradas.

entrada:ajustes → memoria,o configuración → asistente → Subpágina de memoria.

---

## 6. Base de conocimientos

Los usuarios pueden importar documentos a la base de conocimientos.,El asistente busca texto completo por título y contenido a través de la herramienta Knowledge_Search.

- Formatos soportados:txt / md / csv / json.
- entrada:ajustes → base de conocimientos.
- Después de importar,El asistente recupera automáticamente los documentos relevantes como referencia al responder.
- Nota:base de conocimientos fileType="devdoc" Las entradas son documentos de desarrollo interno.(Este documento es),Solo para consultas de LLM,No se muestra en la lista de UI de la base de conocimientos.

---

## 7. Tareas programadas

Deje que el asistente ejecute tareas regularmente según expresiones cron,Incluso si no abres la aplicación.

- entrada:ajustes → Tareas programadas.
- Utilice expresiones cron estándar(minutos horas días meses semanas)Configurar el tiempo de activación.
- Después de llegar al punto, el asistente generará un mensaje y mostrará una notificación.
- se puede habilitar/Desactivar/Eliminar tareas existentes.

---

## 8. Noticias proactivas

El asistente será como un amigo de WeChat.,Enviarle mensajes activamente y notificaciones emergentes a intervalos establecidos.

- entrada:ajustes → charlar → Mensajería proactiva.
- Configuración:interruptor + intervalo de envío(1 / 2 / 4 / 8 / 12 / 24 horas).
- Después de llegar al punto, use el asistente predeterminado + el contexto de la primera sesión para generar un mensaje,Insertar conversación y mostrar notificación emergente de alta prioridad(Título"X Vienen noticias").
- estilo:Envía WeChat como una persona real,texto plano corto,Sin prefijo.

Razones comunes para no recibir mensajes no solicitados:El interruptor no está activado/el intervalo no ha expirado/la sesión o el asistente predeterminado está vacío/no se concede el permiso de notificación.

---

## 9. Chat grupal(Colaboración entre múltiples agentes)

Deje que varios asistentes colaboren y discutan en un grupo.

- entrada:ajustes → Agent.
- Crear chat grupal,Seleccionar asistentes participantes.
- Cada asistente en el chat grupal habla según su propia personalidad y experiencia.,Puedes leer mensajes de otros miembros.
- Si el asistente siente que el tema actual no requiere que hable,Esta ronda se puede saltar.
- Adecuado para escenas que requieren diferentes perspectivas(Por ejemplo, deje que una persona que sea buena escribiendo y un asistente que sea bueno en análisis discutan lo mismo.).

---

## 10. Página de traducción independiente

Muse Hay una página de traducción separada en pantalla completa.(iOS estilo).

- entrada:ajustes → Herramientas → AI Traducir.
- Diseño:Seleccione el idioma de destino en la parte superior,Cuadro de entrada central(con adhesivo/boton borrar),Los resultados de la traducción se muestran a continuación.(Copiar opcional),Utilice el botón flotante en la esquina inferior derecha para iniciar la traducción o cancelarla.
- Durante la traducción se muestra una barra de progreso.
- También puedes seleccionarlo en el menú de mensaje de pulsación larga."Traducción"Traducir un solo mensaje(Aparecerá el submenú de idioma).

---

## 11. Tema y personalización

### Tema

entrada:ajustes → Apariencia.

El sistema temático tiene tres niveles de prioridad.(de mayor a menor):

1. color dinámico(Material You):Android 12+ Sigue el fondo de pantalla del sistema para elegir colores.(Desactivado de forma predeterminada,Necesita abrir manualmente).
2. Tema personalizado:Genere una combinación de colores completa utilizando el algoritmo HCT basado en el color de la semilla(Ver abajo).
3. Tema predeterminado:6 Conjunto de combinaciones de colores personalizadas(warm_paper Papel cálido / flor de cerezo de sakura / océano océano / primavera primavera / otoño otoño / negro puro amoled).

modo tema(Seguimiento del sistema / Claro / Oscuro)y rango de tamaño de fuente(Pequeño / Mediano / Grande / Extra Grande)Ajústalo aquí también.

### Tema personalizado

en la configuración → Apariencia Se pueden crear temas personalizados.:

- elegir 1~3 color de semilla(primary Requerido,secondary / tertiary Opcional).
- El sistema deriva automáticamente la paleta de colores completa utilizando el espacio de color HCT de Material Color Utilities.(Mismo algoritmo que el color dinámico del fondo de pantalla de Android 12+).
- Sólo necesitas elegir el color de la semilla.,No es necesario comprender más de 50 campos de color.

### Retrato de usuario

entrada:ajustes → Modelos y Servicios → Retrato de usuario.

Rellenable:Nombre del asistente, título de usuario, edad, ciudad,MBTI, ocupación, intereses. Esta información se inyectará en las palabras de aviso del sistema.,Deja que tu asistente te conozca mejor y te hable usando tu título preferido.

---

## 12. Copia de seguridad e importación

### copia de seguridad

entrada:ajustes → copia de seguridad(o configuración → Datos y respaldo).

- copia de seguridad local:Exportar/importar datos de la aplicación a archivos locales.
- Copia de seguridad en la nube:Soporta S3 y WebDAV,Se puede consultar el saldo.
- El contenido de la copia de seguridad incluye conversaciones, mensajes, asistentes, configuraciones, etc.

### Importar configuración de cliente de terceros

Las exportaciones de CherryStudio y Chatbox se pueden importar en la configuración del proveedor. JSON:

- Formato de rastreo automático,No es necesario seleccionar fuentes manualmente.
- Sólo importar proveedores y modelos.(API Key,Base URL, lista de modelos).
- No importar el historial de conversaciones(Formato incompatible).
- No se importan configuraciones avanzadas como asistentes y mensajes rápidos.
- id Saltar el conflicto,No sobrescribe la configuración existente.

---

## trece,Web servidor

Muse Insertar un servidor Ktor,Admite la capacidad de llamar de forma remota a aplicaciones dentro de la red de área local.

- entrada:ajustes → Web servidor(o configuración → Datos y respaldo → Web servidor).
- Después de habilitar,Los dispositivos en la misma LAN se pueden llamar de forma remota a través de HTTP + JWT.
- Coopere con el descubrimiento de servicios mDNS,Transmitir automáticamente en LAN.
- Adecuado para escenarios en los que se invocan las capacidades del asistente celular en una computadora.

---

## 14. Habilidades prácticas

### Pídele a tu asistente que recuerde información

Dilo directamente en la conversación."recuerda:mi cumpleaños es el 3 de mayo",El asistente lo fijará usando la herramienta pin_memory.,Puedes recordarlo para la próxima conversación. no depende de daily pipeline,Con efecto inmediato.

Si solo se mencionara casualmente,Es posible que pasen varias horas hasta que la canalización diaria termine de ejecutarse antes de que se recuerde. Se recomienda indicar explícitamente la información importante."recuerda".

### Utilice el pensamiento profundo para resolver problemas complejos

Problemas encontrados que requieren razonamiento.(Matemáticas, lógica, comparación de planes.),Abra el menú + en el campo de entrada"pensamiento profundo",El asistente dedicará más tiempo a pensar en un análisis en profundidad del presupuesto. No es necesario abrirlo para una pequeña charla normal.,será más lento.

### Habilidades de traducción

La traducción de oraciones cortas se puede seleccionar directamente desde el menú de pulsación larga del mensaje."Traducción". Para traducir el texto completo, vaya a Configuración → Herramientas → AI Traducción,Tener página de pantalla completa independiente,Admite pegar, borrar y copiar resultados.

### Emoticonos y rich media

El asistente puede generar gráficos SVG,HTML fragmentos, diagramas(columnar/Polilínea/gráfico circular). Solo pregunta cuando necesites visualizar datos."dibujar un diagrama"o"Mostrar con fotos".

### Tema personalizado

No satisfecho con el tema predeterminado,Ir a configuración → Apariencia Crear un tema personalizado,Elige un color de semilla que te guste,El sistema deriva automáticamente la paleta de colores completa.

### Pídele a tu asistente que te llame como quieras

Ir a configuración → Modelos y Servicios → Relleno de retrato de usuario"Nombre de usuario"(por ejemplo"jefe")y"Nombre del asistente"(por ejemplo"Xiao Miao"),El asistente te llamará de esta manera durante la conversación.

### División del trabajo entre múltiples asistentes.

Cree múltiples asistentes para realizar sus propias tareas.(Uno se dedica a traducir, otro se dedica a escribir código y el otro se dedica a chatear.),en la configuración → Alternar asistente. También puedes crear un chat grupal para que colaboren.

### Ahorra tráfico

No es necesario buscar en línea una pequeña charla;Sólo se necesita información en tiempo real(Noticias, clima, precios de acciones)Abrir de nuevo más tarde. Lo mismo ocurre con el pensamiento profundo.,Encienda a pedido.

---

## 15. Preguntas frecuentes

### El asistente no recuerda lo que dije antes.?

Consulta los datos del asistente → Si el interruptor de memoria de la subpágina de memoria está activado. La memoria a largo plazo es el resumen compilado.,No artículo por artículo fact,Y el funcionamiento del canal diario tarda varias horas. INFORMACIÓN IMPORTANTE CONSEJO DICE"recuerda:XXX"Corregido con pin_memory,o ir a configuración → asistente → Las páginas de memoria se agregan manualmente.

### La búsqueda en Internet no arrojó resultados?

comprobar la configuración → Modelos y Servicios → Web Configuración del motor de búsqueda en Buscar. Bing no lo requiere de forma predeterminada Key,Pero si la red está restringida, es posible que no puedas captarla. Se puede cambiar SearXNG(Autohospedado)o Tavily(necesidad Key). Confirme también el campo de entrada + menú"búsqueda en internet"El interruptor está encendido.

### La traducción falló?

La página de traducción independiente debe configurarse con un proveedor modelo antes de poder usarse. comprobar la configuración → Hay al menos un proveedor disponible en Modelos y Servicios. Mensaje de menú de pulsación larga"Traducción"La misma razón. Si el modelo en sí no es compatible,Cambie a un modelo compatible.

### El tema no aplica?

comprobar la configuración → Selección del tema de apariencia. Si el color dinámico está activado(Android 12+),Sólo los cambios en el fondo de pantalla del sistema afectarán la combinación de colores. Primero es necesario crear temas personalizados y luego seleccionarlos. modo tema(El sistema sigue/color claro/oscuro)Controla individualmente la luz y la oscuridad.

### No se recibió ningún mensaje activo?

comprobar la configuración → charlar → Mensaje activo: si el interruptor está activado, si se alcanza el intervalo y si se concede el permiso de notificación. También requiere que exista al menos una sesión y un asistente predeterminado.,De lo contrario, ningún contexto genera el mensaje.

### Cómo leer la página de la guía nuevamente?

Página de inicio solo en el primer lanzamiento(y no hay proveedor)mostrar. Si quieres volver a verlo, puedes ir a Configuración. → Acerca de → Vuelva a mostrar el arranque. Para modificar el título, vaya a Configuración → Modelos y Servicios → Retrato de usuario.

### El pensamiento profundo está activado pero no hay cambios.?

El pensamiento profundo requiere modelos actuales que respalden el razonamiento. Algunos modelos no soportan la inferencia intensiva. Además, el pensamiento profundo sólo tiene efecto en la conversación actual.,no persistirá,Restaure la configuración predeterminada del Asistente para la próxima conversación.

### Cómo importar configuraciones de otras aplicaciones?

Importe exportaciones de CherryStudio o Chatbox en la configuración del proveedor JSON,Formato de rastreo automático,Sólo se importan proveedores y modelos. El historial de conversaciones y la configuración avanzada no se importan.
