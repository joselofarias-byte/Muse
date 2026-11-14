# Manual de usuario de Muse

> **Versión de referencia**: v2.3.0. Está ordenado según la interfaz de esa versión. Si algo no coincide, manda lo que ves en la app.
>
> Aquí está cada función: dónde entra, cómo se usa, qué hace y dónde se traba. Si es la primera vez, con los capítulos 1 y 2 ya puedes usarla. El resto se consulta cuando haga falta. Cada capítulo se lee solo.
>
> Dentro de la app hay una guía más corta: **Ajustes → Tutorial**. Si no encuentras una opción, búscala en la parte de arriba de Ajustes.

## Contenido

**Primera parte. Empezar**

- [1. Instalar y abrir por primera vez](#1-instalar-y-abrir-por-primera-vez)
- [2. La interfaz y los gestos](#2-la-interfaz-y-los-gestos)
- [3. Modelos, proveedores y modelos auxiliares](#3-modelos-proveedores-y-modelos-auxiliares)

**Segunda parte. La conversación**

- [4. Enviar mensajes y actuar sobre ellos](#4-enviar-mensajes-y-actuar-sobre-ellos)
- [5. Organizar las conversaciones](#5-organizar-las-conversaciones)
- [6. Voz y multimedia](#6-voz-y-multimedia)
- [7. Búsqueda y navegador](#7-búsqueda-y-navegador)

**Tercera parte. Recuerdos y conocimientos**

- [8. Recuerdos](#8-recuerdos)
- [9. Base de conocimientos](#9-base-de-conocimientos)

**Cuarta parte. Herramientas**

- [10. Herramientas y permisos](#10-herramientas-y-permisos)
- [11. Automatización de la interfaz y terminal](#11-automatización-de-la-interfaz-y-terminal)

**Quinta parte. Asistentes**

- [12. Asistentes](#12-asistentes)
- [13. Chat grupal y equipos](#13-chat-grupal-y-equipos)
- [14. Habilidades, complementos y MCP](#14-habilidades-complementos-y-mcp)
- [15. Canales de mensajería](#15-canales-de-mensajería)

**Sexta parte. El día a día y los datos**

- [16. Mensajes proactivos](#16-mensajes-proactivos)
- [17. Apariencia](#17-apariencia)
- [18. Datos, copias y privacidad](#18-datos-copias-y-privacidad)
- [19. La web y otras entradas](#19-la-web-y-otras-entradas)

**Al final**

- [Preguntas frecuentes](#preguntas-frecuentes)
- [Apéndice A. Formatos de copia y de archivo](#apéndice-a-formatos-de-copia-y-de-archivo)
- [Apéndice B. Glosario](#apéndice-b-glosario)

---

## Primera parte. Empezar

### 1. Instalar y abrir por primera vez

#### 1.1 Descargar e instalar

- Hace falta **Android 8.0 o posterior**.
- Descarga el APK desde GitHub Releases o desde [museai.ltd](https://museai.ltd).
- Hay tres paquetes:

| Paquete | Para quién |
| --- | --- |
| `arm64-v8a` | Casi todos los teléfonos de los últimos años. Empieza por este. |
| `armeabi-v7a` | Teléfonos más viejos. |
| `universal` | Si no sabes cuál es el tuyo. Es el más pesado. |

Si Android pide permitir orígenes desconocidos, es el aviso normal cuando el APK no viene de una tienda. Puedes instalar una versión nueva encima de la anterior. Los datos se quedan.

#### 1.2 La primera vez

La primera apertura tiene seis pantallas:

1. **Bienvenida**. Unas tarjetas de lo que hace Muse. Al terminar, toca «Empezar».
2. **Idioma y apariencia**. Elige el idioma (español, inglés, portugués, japonés, coreano o ruso) y un tema. Después se puede cambiar.
3. **Cómo llamarte**. Dos campos: cómo debe llamarte y qué nombre le pones. Se usan en la conversación. Puedes dejarlos vacíos.
4. **Recuerdos**. El interruptor de la memoria. Si lo dejas activado, al cerrar una conversación Muse ordena lo que dijo. Es la diferencia más grande con un chat común. Conviene dejarlo activado.
5. **El modelo**. Elige un proveedor, pega la API Key y toca «Probar conexión». Si sale bien, Muse trae la lista de modelos y marca qué puede hacer cada uno.
6. **El modelo de todos los días**. Busca uno, déjalo como predeterminado y toca «Charlemos».

Puedes saltarte cualquier paso y completarlo después en **Ajustes → Modelos y capacidades → Proveedores**.

#### 1.3 Conectar un proveedor

Muse no vende crédito de modelos. Se conecta a proveedores (OpenAI, Anthropic, Google, DeepSeek, Tongyi Qianwen, Zhipu, Doubao y más de 40 preajustes).

- **La API Key** es la credencial para llamar al modelo. La generas en el sitio del proveedor, en la página de claves, y la pegas en Muse. Casi todos dan un crédito gratis al registrarte.
- **Si la prueba sale bien**, Muse descarga los modelos de ese proveedor y marca si ven imágenes, si razonan y si llaman herramientas. Después puedes cambiar de modelo en la conversación.
- **Un proveedor propio** sirve para cualquier interfaz compatible con OpenAI: una pasarela, Ollama en tu red o un servicio que armes tú. Completa el nombre, la dirección y la API Key.

El capítulo 3 cubre varias claves, la cuota, el saldo y los modelos de imagen.

#### 1.4 El primer mensaje

Vuelve al inicio. Escribe en el campo de arriba o abre una conversación. Por ejemplo:

- «Hola, preséntate.»
- «Recuerda: me gusta el americano helado y no me gusta el cilantro.» A partir de ahí lo tiene presente. Ver el capítulo 8.
- «Mira el tiempo de mañana en Montevideo.» Eso usa una herramienta. Ver el capítulo 10.

---

### 2. La interfaz y los gestos

Hay tres pantallas grandes: **inicio** (la lista de conversaciones), **chat** y **Ajustes**.

#### 2.1 Inicio

- **El campo de arriba**. Escribe y toca «Nuevo y enviar». Se crea una conversación y ese texto es el primer mensaje.
- **La lista**. Va por grupos: fijadas, recientes, carpetas y archivadas. Arriba hay filtros: Todo, Fijadas y Archivadas.
- **La tira de repaso**. Muestra de qué hablaron la última vez.
- **Los asistentes**. Una fila de avatares. Toca con quién quieres hablar, o agrega uno desde ahí.
- **Los accesos**. Base de conocimientos, tareas programadas, grabación rápida, traducción y otros.
- **Ordenar**. Mantén pulsada una conversación para fijarla, archivarla, cambiarle el nombre, moverla de carpeta o eliminarla. Las fijadas se reordenan arrastrando. Si borras una carpeta, sus conversaciones pasan a «Sin carpeta». No se borran.
- **Archivar**. Lo que no quieres borrar ni ver todo el tiempo queda en el filtro Archivadas. Se puede desarchivar.
- A veces aparece arriba un aviso de versión nueva. Tócalo para ver qué cambió.

#### 2.2 El chat

De arriba abajo:

- **La barra**.
  - A la izquierda, atrás.
  - En el centro, el título. **Tócalo** para ver la lista de conversaciones. **Mantén pulsado** para cambiar de asistente.
  - A la derecha, el modelo actual y el menú de tres puntos (proveedor, comprimir el contexto y otras acciones).
- **Los mensajes**. Ahí están las tarjetas de ánimo, de tareas y de artefactos. Ver el capítulo 4.
- **El campo**. A la izquierda el signo más, en el centro el texto, a la derecha el micrófono y enviar.

#### 2.3 El mapa de Ajustes

Ajustes tiene siete grupos y un buscador arriba. El buscador también acepta iniciales y pinyin.

| Grupo | Qué hay |
| --- | --- |
| Chat y pantalla | Chat, apariencia y voz (TTS) |
| Mi asistente | Asistentes, equipo de varios agentes y recursos (favoritos, libro de trasfondo, mensajes rápidos, habilidades) |
| Modelos y capacidades | Proveedores, modelos auxiliares, búsqueda web y reconocimiento de voz |
| Recuerdos y mensajes proactivos | Mensajes proactivos, centro de recuerdos y base de conocimientos |
| Herramientas y conexiones | Centro de conexiones (canales, MCP, complementos y permisos), notificaciones, tareas, automatización de la interfaz, permisos, grabación rápida, teléfono virtual y traducción |
| Datos y privacidad | Datos, copias, importación, espacio de trabajo, chats archivados, protección de datos personales, uso compartido, proxy y auditoría |
| Acerca de y ayuda | Tutorial, acerca de, buscar actualizaciones, registros, experimental y estadísticas |

Arriba está tu tarjeta: avatar y apodo. Es local. No hay cuenta.

#### 2.4 Gestos

Muchas acciones están en la pulsación larga.

| Gesto | Dónde | Qué hace |
| --- | --- | --- |
| Tocar | Título del chat | Abre la lista y cambia de conversación |
| Mantener pulsado | Título del chat | Cambia de asistente |
| Mantener pulsado | Un mensaje | Copiar, citar, ramificar, eliminar y el resto del menú |
| Mantener pulsado | El campo de texto | Acciones de entrada, incluida la pantalla completa |
| Mantener pulsado | El signo más | Muestra u oculta la barra de accesos |
| Mantener pulsado | El micrófono | Graba. Al soltar, transcribe. Desliza hacia arriba para cancelar |
| Mantener pulsado | Una conversación del inicio | Fijar, archivar, renombrar, mover o eliminar |
| Mantener pulsado | Un favorito | Pone una etiqueta de grupo |
| Teclas de volumen | Los mensajes | Suben y bajan. Viene desactivado. Se enciende en Ajustes → Chat |

> El editor a pantalla completa viene oculto. Actívalo en **Ajustes → Chat → Comportamiento**. Para un texto largo se agradece.

---

### 3. Modelos, proveedores y modelos auxiliares

#### 3.1 Cambiar de modelo

- A la derecha de la barra del chat está el modelo. Tócalo para cambiar. Cada uno muestra si ve, si razona y si usa herramientas.
- Puedes cambiar de modelo en medio de la misma conversación. Los recuerdos y el contexto no dependen del modelo. No se olvida de ti.
- Cada asistente puede tener su modelo (capítulo 12). Un cambio manual vale solo para esa conversación.

#### 3.2 Razonamiento

Sirve para matemática, lógica, código y textos largos, cuando hace falta pensar un paso más.

- **Encenderlo**. El botón de razonamiento, junto al campo.
- **El nivel**. Mantén pulsado el botón para recorrerlo. El presupuesto aproximado es:

| Nivel | Presupuesto |
| --- | --- |
| Bajo | unos 1.000 tokens |
| Medio | unos 2.000 |
| Alto | unos 8.000 |
| Muy alto | unos 16.000. Solo algunos modelos |

- También están «automático» y «apagado». Automático lo decide el servidor. Apagado no razona. Si el modelo no razona, encenderlo no hace nada.
- Para dejarlo activado de entrada: **Ajustes → Chat → Avanzado → Razonamiento por defecto**.

#### 3.3 Modelos auxiliares

Muse hace tareas chicas en segundo plano: títulos, comprimir el diálogo, ordenar recuerdos, describir una imagen. No tienen que ir al modelo más caro.

En **Ajustes → Modelos auxiliares**:

- Tres niveles:
  - Herramienta chica: títulos, clasificación y tareas simples.
  - Herramienta grande: comprimir, recuperar recuerdos y partir una tarea.
  - Visión: describir una imagen.
- También puedes atar un modelo a un tipo de tarea (diálogo, razonamiento, código, creación, análisis) y activar el cambio automático.
- «Restablecer todos los enlaces» vuelve a los valores de fábrica.

> Conviene tener un modelo chico y barato para las tareas simples. A la larga se nota en la factura.

#### 3.4 Cómo responde

En **Ajustes → Chat → Estilo**:

- **Azar**. Cerca de 0, la respuesta es estable. Cerca de 2, se abre más.
- **Extensión**. Conciso, equilibrado o detallado.
- **Tono**. Neutral, cercano, formal o con humor.

#### 3.5 La página del proveedor

**Ajustes → Modelos y capacidades → Proveedores** concentra lo habitual:

- Probar un proveedor, o probarlos todos.
- Traer la lista del proveedor, agregar un modelo a mano y editar qué ve, si razona, si usa herramientas y de qué tamaño es el contexto.
- **Varias claves** del mismo proveedor, en rotación.
- Límite de peticiones por minuto y de peticiones a la vez.
- Consulta de saldo, si el proveedor tiene ese endpoint.
- Opciones de empresa, como la cuenta de servicio de Google Vertex AI, y cabeceras o cuerpo de la petición.
- **Imagen y video** se configuran en las secciones de generación de esa misma página.

---

## Segunda parte. La conversación

### 4. Enviar mensajes y actuar sobre ellos

#### 4.1 Cómo enviar

- **Texto**. Escribe y toca Enviar. En **Ajustes → Chat** puedes hacer que Enter envíe. Si está apagado, Enter baja de línea.
- **Voz**. Mantén pulsado el micrófono, habla y suelta. El detalle está en el capítulo 6.
- **Imagen, video o documento**. El signo más abre el panel: cámara, álbum (varias imágenes), video de hasta 50 MB y documentos.
- **Un texto largo**. Si pegas mucho, Muse pregunta si lo adjuntas como archivo, para no reventar el campo. También puedes mantener pulsado el campo y pasar a pantalla completa.
- **Además**: citar un documento de la base de conocimientos con @, una plantilla, una habilidad, el modo de dibujo, delegar a otro asistente o reiniciar el contexto.

#### 4.2 Mientras responde

Si se te ocurre algo mientras responde:

- **Detener**. Corta la respuesta.
- **Encolar**. Deja el mensaje nuevo en espera y lo envía cuando termine.
- **Interrumpir**. Corta lo que está generando y manda el mensaje nuevo ya.

Si la última vuelta quedó a medias (por ejemplo, pasaste la app a segundo plano), al volver aparece una barra: «Las últimas N llamadas no terminaron». Puedes reanudar o descartar.

#### 4.3 Comandos con barra

Escribe `/` en el campo:

| Comando | Qué hace |
| --- | --- |
| `/new` | Nueva conversación |
| `/compact` | Comprime el historial |
| `/reset` | Reinicia el contexto |
| `/pin` | Fija o desfija |
| `/archive` | Archiva la conversación |

#### 4.4 El menú del mensaje

Mantén pulsado un mensaje. El menú cambia según quién lo escribió.

- **En todos**: copiar, citar, anotar, delegar, ramificar, seleccionar, ver datos (modelo, tiempo y tokens), reaccionar y guardar en favoritos.
- **En una respuesta**: también leer en voz alta, dejar de leer y eliminar.
- **En un mensaje tuyo**: también editar, traducir, compartir, reenviar y eliminar.

Al eliminar puedes borrar solo ese mensaje, o ese y todo lo que viene después.

#### 4.5 Regenerar

- Si la respuesta no te sirve, toca «Regenerar». Responde de nuevo.
- Cada vez queda una versión. Las anteriores no se pierden. El selector al lado del mensaje las recorre.

#### 4.6 Ramificar

- **Ramificar** copia una línea nueva desde un mensaje y deja la original intacta. Sirve para probar «¿y si lo pregunto de otra forma?».
- Si editas un mensaje tuyo y lo vuelves a enviar, desde ahí crece una rama. Lo anterior se conserva.

#### 4.7 Selección múltiple

En el menú largo, «Seleccionar» pone una casilla a la izquierda y una barra arriba: seleccionar todo, copiar, eliminar o exportar. Sales con «Salir» o tocando un espacio vacío.

#### 4.8 Las tarjetas de un mensaje

La primera vez parecen de más. Son parte de Muse:

- **Ánimo**. Lo que pasa antes de responder: Vibe, Sparks, Reflections y Will. Viene plegado. Se muestra o se oculta en **Ajustes → Chat → Mensajes**.
- **Razonamiento**. El borrador interno de un modelo que razona. También plegado.
- **Reflexión**. Cómo evalúa su propia respuesta: si está bien, si está completa y el tono.
- **Tarea**. El registro de una herramienta: qué llamó, con qué parámetros, qué volvió y si salió bien. Varias llamadas se juntan en un registro que se abre de a una.
- **Artefacto**. Código, un documento u otra pieza que conviene sacar de la burbuja. Se abre a pantalla completa y se copia. Todo queda en el espacio de trabajo, en el centro de artefactos.
- **Cita**. Si el mensaje cita un texto o un fragmento de la base de conocimientos, aparece arriba de la burbuja.
- **Traducción**. Debajo hay un bloque plegado, «Ver el original».

#### 4.9 Favoritos

Cualquier mensaje se puede guardar y después buscar en Favoritos. Mantén pulsado uno para ponerle una etiqueta de grupo.

---

### 5. Organizar las conversaciones

#### 5.1 Crear y cambiar

- Escribe en el campo del inicio, o toca «Nueva tarea».
- Para cambiar: toca la lista del inicio, o el título del chat.
- Cada conversación va por su lado. En una hablas de gatos y en otra de trabajo.

#### 5.2 Fijar, carpetas y archivo

- Mantén pulsada la conversación: fijar, archivar, renombrar, mover o eliminar.
- Las fijadas quedan arriba y se reordenan arrastrando.
- Las carpetas agrupan por tema. Se crean, se renombran y se borran.
- Si no quieres verla ni borrarla, archívala. Vuelve cuando quieras.

#### 5.3 Buscar

- En el inicio, la búsqueda recorre todas las conversaciones y baja hasta el mensaje.
- `/` en el campo abre el panel de comandos y también busca conversaciones y mensajes.
- La búsqueda completa filtra además por favoritos y etiquetas.

#### 5.4 Conversaciones largas

Cuando el hilo crece, Muse tiene con qué no olvidar de más ni de menos:

- **Comprimir**. `/compact`, o «Comprimir contexto» en el menú de tres puntos. El historial pasa a un resumen. Puedes decir cuántos mensajes recientes se quedan y agregar una instrucción.
- **Piso de contexto** (**Ajustes → Chat → Contexto**). Toma tus mensajes como piso y conserva completas las últimas N vueltas, para no cortar a la mitad.
- **Tope de herramientas** (**Ajustes → Chat**). Cuántas rondas de herramientas caben en un turno. Por defecto no hay tope, así una tarea larga no se corta. También puedes poner 100, 50 o 25.
- **Modo de rendimiento**. En un hilo muy largo solo se arman los últimos 50 mensajes. Al subir, carga más. En un teléfono viejo se siente.
- **Caché de conversaciones**. Cuántas copias quedan en segundo plano. Más copias, cambio más rápido y más memoria.

Cuando comprime o recorta, Muse deja un extracto adelante. Lo que ya se dijo no desaparece de golpe.

#### 5.5 Eliminar

Antes de borrar hay una confirmación, y no se deshace. Si dudas, archiva. Borra cuando estés seguro. Lo importante va en una copia (capítulo 18).

---

### 6. Voz y multimedia

#### 6.1 Dictado

- Mantén pulsado el micrófono, habla y suelta. El texto se puede corregir antes de enviar. Durante la grabación, desliza hacia arriba para cancelar.
- Dos motores:
  - **El del sistema**. No se configura. Algunos teléfonos no lo tienen, y a veces abre el diálogo del sistema.
  - **Uno en la nube**. En **Ajustes → Modelos y capacidades → Reconocimiento de voz** elige DashScope, Step, Whisper, Realtime u otro, y pega la clave. Transcribe mientras hablas, dentro de la app.
- Algunos motores reconocen varios idiomas y ponen la puntuación solos.

#### 6.2 Leer en voz alta

- En una respuesta, mantén pulsado y elige leer, o deja de leer.
- **Ajustes → Chat y pantalla → Voz**: interruptor, velocidad, tono e idioma.
- Además de la voz del sistema puedes conectar MiniMax, ElevenLabs, Fish Audio, DashScope o Step. Al conectar aparecen ajustes finos, como la emoción o la estabilidad.
- **Clonar una voz**. En la misma página, «Clonar voz». Después la lectura puede usar ese timbre.

#### 6.3 Imágenes

- **Enviar la foto**. Si el modelo ve (GPT-4o, Gemini, Tongyi Qianwen VL y otros), lee la imagen, una captura o una tabla. Un mensaje puede llevar varias.
- **Ayuda visual**. Si el modelo actual no ve, Muse llama al modelo de visión que elegiste, arma una descripción y se la pasa. Está en **Ajustes → Modelos y capacidades → Modelos auxiliares → Visión**. La prueba de sonda comprueba que ese modelo responde.
- **PDF escaneado**. El OCR sin conexión saca texto en chino y en letras latinas.

#### 6.4 Generar imagen y video

- **Dibujo**. En el panel del signo más, abre el modo de dibujo y describe la imagen. Puedes colgar una referencia y ajustar calidad y estilo.
- El modelo de generación se elige en **Ajustes → Modelos y capacidades → Proveedores**, en imagen o en video.
- Para un video, en un proveedor que lo soporte, pide un clip corto. La duración (5 o 10 segundos) depende del proveedor.

#### 6.5 Stickers

- **Ajustes → Chat → Stickers**.
- Importa un ZIP. Muse lo ordena según las carpetas.
- Puedes activar o desactivar el paquete, y la frecuencia: de vez en cuando, normal o seguido.
- En el chat, el asistente manda un sticker cuando el momento lo pide. No lo fuerza.

---

### 7. Búsqueda y navegador

#### 7.1 Buscar en la web

- El interruptor global está en **Ajustes → Modelos y capacidades → Búsqueda web**. En el chat, el globo de la barra lo enciende solo para ese momento.
- En automático, el orden es:
  1. La búsqueda nativa del modelo (Gemini Grounding, OpenAI Responses y otras que ya vienen conectadas).
  2. La API que configuraste.
  3. El rastreo gratis (Bing o Baidu).
- También entran Tavily, Brave, Serper, Perplexity, Exa y Bocha. Con la clave puesta, el modo automático prueba el siguiente si uno falla y descarta resultados flojos.
- Puedes limitar cuántas búsquedas hay en un turno, cuántos resultados trae cada una y si cambia de motor al fallar.
- En la misma página están «Probar búsqueda» y el diagnóstico de la última. Si algo no aparece, mira ahí primero.
- También puedes decirlo: «Busca xxx».

La guía para pedir cada clave está en [guia-api-de-busqueda.md](guia-api-de-busqueda.md).

#### 7.2 El navegador de la conversación

- Cuando el asistente navega, arriba del chat aparece una cápsula con el título de la página.
- Tócala para ver la página en vivo, a pantalla completa: pasar, tocar o completar un formulario.
- Atrás pliega la vista y el navegador sigue en segundo plano. Actualizar recarga. Cerrar termina esa navegación y la cápsula se va.
- Cada conversación tiene su propio navegador.

---

## Tercera parte. Recuerdos y conocimientos

### 8. Recuerdos

Acá Muse se separa de un chat común: **te recuerda**. Algo que dices de paso puede volver semanas después.

#### 8.1 Cómo crecen

```
conversación → hechos → resumen acumulado → compilación → comprensión
```

- Lo que dices se resume en hechos, con una importancia.
- Una conversación larga se comprime en un resumen. No se tira por larga.
- En segundo plano, Muse compila hechos y resúmenes en algo más general, y lo mete en la siguiente conversación si viene al caso.
- Lo importante (personas, gustos, hábitos, salud, dinero) queda protegido. Lo cotidiano se olvida, como le pasa a una persona.

> El orden del fondo es lento. Lo que acabas de decir puede no estar guardado todavía. Si importa, di «Recuerda: …». Eso se fija al momento.

#### 8.2 El centro de recuerdos

**Ajustes → Centro de recuerdos**:

- Ver y buscar por actual, corto plazo, largo plazo y metahecho. Filtra por fecha, importancia y espacio.
- Además de la lista hay una línea de tiempo y una constelación. La línea de tiempo filtra por todo, hechos, resúmenes o hitos.
- Editar, borrar o agregar. La importancia puede ser normal, importante o crítica («no olvidar»).
- Cada recuerdo muestra de dónde salió, cuándo se guardó y cuántas veces se usó.
- Hay historial de cambios, y puedes volver a una versión anterior.

Ahí mismo están el interruptor general, el presupuesto, la velocidad del olvido, el índice de olvido y el premio por uso. Se puede volver a los valores de fábrica.

**Espacios**. Separan recuerdos de trabajo, de la vida o de un asistente, para que no se mezclen.

#### 8.3 Fijar un recuerdo

- Si dices «Recuerda: el cumpleaños de mi madre es el 3 de mayo», queda fijado y entra en cada conversación.
- También se administran a mano en el centro de recuerdos.

#### 8.4 Experiencias

- **Ajustes → Centro de recuerdos → Experiencias**.
- Guardas «esta clase de problema se resuelve así»: título, texto, tipo y etiquetas. En una tarea parecida, el asistente lo consulta.
- Sirve cuando tuviste que corregir lo mismo varias veces. Se puede apagar. Encendido, gasta un poco de contexto.

#### 8.5 Qué queda y qué no

- Los recuerdos viven en el teléfono. No se suben a un servidor de Muse.
- Cambiar de modelo o de proveedor no los toca.
- Por defecto, los hechos y las conversaciones recientes de un asistente son suyos. El asistente predeterminado sí puede usar el conjunto global.
- Un asistente nuevo no lee el retrato global, los recuerdos fijados ni las experiencias compartidas, salvo que actives «Usar la memoria global». Al actualizar, los asistentes que no son el predeterminado pasan a ese aislamiento. Los hechos que ya tenían no se borran.

---

### 9. Base de conocimientos

Le das documentos, manuales, notas o contratos. Cuando preguntas, busca ahí antes de responder y marca el fragmento que citó.

#### 9.1 Importar

- **Ajustes → Base de conocimientos**.
- Entran texto, PDF, Word, PowerPoint, Excel, ePub y otros formatos habituales. Un escaneo o un PDF de imágenes pasa por el OCR sin conexión.
- Al importar eliges la base: la de siempre o una tuya.
- En la base, entra a una carpeta para ver los documentos. Un ZIP, 7Z o RAR crea una base con el nombre del archivo y conserva las carpetas de adentro. Puedes mover un documento a una carpeta virtual, o importarlo en la carpeta que tengas abierta. Reordenar el índice no mueve el archivo ni lo vuelve a indexar.
- La importación parte el texto y arma el índice. El avance dice cuántos fragmentos van.
- Si un archivo es demasiado grande, puedes partirlo o cambiar el analizador en **Ajustes → Base de conocimientos → Analizador**. El de la nube, MinerU entre ellos, se lleva mejor con un PDF académico, con fórmulas y con tablas.
- **Volver a indexar**. Si cambias el tamaño de los fragmentos o el texto del documento, hay que reconstruir el índice. Borra el anterior y tarda. Mejor con el teléfono enchufado.

#### 9.2 Fragmentos y búsqueda

- **Ajustes → Base de conocimientos**: tamaño del fragmento, cuánto se solapan, si respeta Markdown y si parte por tokens. Con chino y letras latinas mezclados, los tokens afinan más.
- **Búsqueda mixta**: palabras (BM25) y similitud de vectores, unidas con RRF. El peso del vector se ajusta.
- Más adentro están la diversidad MMR, el reorden (rerank) y el presupuesto de tokens. Los valores de fábrica alcanzan.
- Cada fragmento trae una puntuación. El umbral evita que entre algo flojo.

#### 9.3 Usarla en el chat

- **Sola**. Si la pregunta toca esos documentos, la respuesta trae una cita: nombre, fragmento y puntuación.
- **Con @**. En el panel del signo más, «Citar la base de conocimientos» limita la respuesta a unos documentos.
- Puedes apagar la inyección automática. La herramienta `knowledge_search` sigue disponible y el asistente la usa cuando hace falta.

---

## Cuarta parte. Herramientas

### 10. Herramientas y permisos

#### 10.1 Qué es una herramienta

El asistente no solo habla. Mira el tiempo, manda un mensaje, pone una alarma, busca, lee y escribe archivos, genera una imagen o toca la pantalla. Eso lo hacen las herramientas. Esta versión trae **141**. En grande:

- **El día a día**: clima, hora, calculadora, calendario, alarma, cronómetro, recordatorios, mapas, ubicación, teléfono, SMS, correo y contactos.
- **El aparato**: batería, red, almacenamiento, CPU, sensores, brillo, volumen, linterna, vibración, la app de adelante, Wi-Fi y Bluetooth.
- **Texto**: portapapeles, traducción, código QR, contraseñas, Base64, hash, JSON y URL.
- **Archivos**: el espacio de trabajo, enlaces, PDF, la biblioteca, notas rápidas y tareas.
- **Medios**: imágenes, video, lectura en voz alta y stickers.
- **La web**: búsqueda, rastreo y el navegador (pasar, tocar, formularios, capturas).
- **Recuerdos**: fijar, guardar, borrar, buscar y guardar una experiencia.
- **Automatización**: tareas programadas, notificaciones, comandos, terminal y la interfaz.
- **Alrededor**: canales, conectores, complementos, MCP y subagentes.

No hace falta saber el nombre. Lo pides en castellano. El asistente elige la herramienta, parte el trabajo y muestra el resultado en la tarjeta de tarea (4.8).

#### 10.2 Si no está lista

Algunas piden un permiso del sistema (tocar la pantalla, correr un comando). Sin ese permiso, Muse las marca como no listas. Si se las llama, fallan enseguida y dicen dónde activarlas. No dan vueltas ni fallan en silencio. El capítulo 11 explica cómo abrirlas.

#### 10.3 Aprobar antes de actuar

En **Ajustes → Chat → Aprobación de herramientas** hay tres modos:

- Confianza total. Llama todo sin preguntar. Solo si el conjunto te resulta seguro.
- Preguntar cuando haga falta. Es el recomendado. Lo inofensivo pasa solo. Lo riesgoso pregunta.
- Estricto. Salvo una lectura, todo pide confirmación.

También puedes fijar permitir, preguntar o denegar **una herramienta**. Está en Ajustes y en el centro de conexiones. La página agrupa por riesgo y cuenta cuántas hay en cada política.

La ventana de aprobación tiene una cuenta atrás. Si se vence, se rechaza. Enviar un SMS, llamar o borrar un archivo pregunta en el modo recomendado.

#### 10.4 Sin pasarse

- Para no llenar el contexto, el modelo no recibe las 141 de entrada. Las de uso común están. El resto se busca con `find_tools` y después se llama.
- Si la misma herramienta falla seguido, o no hay avance, se detiene y dice por qué.
- Los comandos del sistema pasan por una lista blanca (capítulo 11). Los complementos tienen sus propios permisos (capítulo 14).

---

### 11. Automatización de la interfaz y terminal

Para que Muse haga cosas en el teléfono, primero hay que abrir permisos.

#### 11.1 El asistente de permisos

**Ajustes → Asistente de permisos**. De menos a más:

| Canal | Cómo se abre | Qué permite |
| --- | --- | --- |
| Accesibilidad | Un interruptor del sistema | Leer la pantalla, tocar y deslizar |
| Shizuku | Instalar Shizuku y autorizar. Es el recomendado | Comandos, capturas y acciones más quietas |
| Root | Un teléfono con root. Se puede pedir desde el asistente | Control del sistema y datos de otras apps |
| Termux | Instalar Termux y una línea de configuración | Un Linux completo: apt, pip, gcc |

Arriba se ve el nivel actual. Cada canal tiene un botón para abrirlo y otro para comprobarlo.

Termux se configura una vez. En Termux:

```
echo 'allow-external-apps = true' >> ~/.termux/termux.properties
```

Reinicia Termux. El asistente detecta si quedó aplicado.

#### 11.2 Automatizar la interfaz

**Ajustes → Automatización de la interfaz**. Tres tarjetas: Accesibilidad, Shell (Shizuku) y Root. Cada una muestra el estado y «Abrir». El pedido de root se puede lanzar desde la página.

Abajo está **Probar la lectura de pantalla**. Lee lo que hay y muestra la app, cuántos controles hay, la resolución y de dónde salieron los datos. Sirve para comprobar que el permiso quedó bien.

Con eso puedes pedir cosas entre apps: abrir una, encontrar un interruptor y encenderlo. El resultado depende del modelo y del canal.

#### 11.3 La terminal

**Ajustes → Terminal**. Una consola de verdad: escribes, limpias, reinicias o cortas con Ctrl-C.

Tres entornos:

- **El de la app**. Aislado en su espacio.
- **El del dispositivo** (hace falta Shizuku o root). Ahí entran `dumpsys`, `settings`, `am`, `pm`, `input` y `screencap`, entre otros. Tuberías, redirecciones y encadenar comandos se rechazan.
- **Termux**. Un Linux completo, después de autorizarlo.

Puedes escribir tú, y el asistente también la usa cuando trabaja.

#### 11.4 El espacio de trabajo

**Ajustes → Espacio de trabajo**. Es el cajón del asistente. Lee, escribe, mueve y borra solo ahí. El resto del teléfono no lo toca.

Puedes montar otras carpetas (fotos, descargas, un proyecto). Cada punto tiene nombre. Si lo montas **solo para leer**, puede copiar y no puede cambiar ni borrar. En una conversación puedes limitar qué puntos están disponibles.

Aunque montes varias carpetas, nada sale de ese conjunto. Una ruta de afuera se rechaza.

#### 11.5 Un paso más

- **El navegador**. Abre páginas, toca, completa formularios, se desplaza y extrae texto. La cápsula del capítulo 7 es esa misma navegación, a la vista.
- **El agente de visión** (`ui_agent`). Captura, el modelo decide, toca o escribe, vuelve a capturar y sigue hasta terminar.

---

## Quinta parte. Asistentes

### 12. Asistentes

#### 12.1 Qué es

Cada asistente es una persona aparte: nombre, avatar, instrucciones, modelo, herramientas, recuerdos, base de conocimientos y habilidades. Uno escribe, otro programa, otro traduce. No se pisan. La app trae un asistente predeterminado. Uno nuevo, por defecto, solo lee sus propios hechos y sus conversaciones recientes.

#### 12.2 Crear, cambiar y delegar

- **Nuevo**. Ajustes → Mi asistente → Asistentes.
- **Cambiar**. En el chat, mantén pulsado el título. O toca un avatar en la fila del inicio.
- **Delegar**. Di «@nombre, encárgate de esto». El asistente actual le pasa el trabajo. El otro lo hace con su propio carácter, y el avance se ve en la tarjeta. Cada uno conserva sus recuerdos.
- **Mensajes entre asistentes**. Ajustes → Agente → Mensajes del agente.

#### 12.3 Las cinco páginas de un asistente

- **General**. Nombre, avatar (emoji, imagen o inicial), modelo propio o el global, temperatura, top P, tope de tokens, nivel de razonamiento, cuántos mensajes de contexto, si la salida va en streaming, una línea de presentación, el nombre que se ve en el chat y si puede entrar a un grupo.
- **Instrucciones**. Las instrucciones del sistema, plantillas de mensaje y un mensaje predeterminado en JSON.
- **Recursos**. Mensajes rápidos, libro de trasfondo, inyecciones, habilidades, MCP, herramientas y bases de conocimientos. Lo que marques queda atado a ese asistente y lo usa primero.
- **Recuerdos**. El interruptor, si además usa el retrato global, los recuerdos fijados y las experiencias compartidas, las conversaciones recientes, los avisos de tiempo y la carga manual. Apagar la memoria global no apaga los hechos propios ni las conversaciones recientes.
- **Avanzado**. Cabeceras y cuerpo de la petición, etiquetas para el enrutado, y **reemplazos**. Sirven para cambiar un nombre o filtrar un texto. Puedes hacer que el reemplazo solo se vea, sin alterar lo que se manda al modelo.

#### 12.4 Cómo escribir el personaje

De adentro hacia afuera:

1. **Quién es**. Nombre, identidad y trasfondo.
2. **Qué relación tienen**. Amigo, profesor, compañero de casa, o el nombre que quieras.
3. **Cómo habla**. Corto o largo, vivo o calmo.

Eso va en las instrucciones del sistema, y se combina con el tono global de la sección 3.4. En la app hay más detalle en **Ajustes → Tutorial**.

#### 12.5 Compartir

- **Exportar**. Al final del detalle, «Compartir tarjeta». Sale una imagen PNG o un JSON de SillyTavern (V2), o la tarjeta de Muse para pasársela a otra persona.
- **Importar**. En la lista de asistentes entran tarjetas, también las de SillyTavern en PNG o JSON.

---

### 13. Chat grupal y equipos

#### 13.1 Una reunión

Varios asistentes en la misma conversación.

- **Crear**. En la lista de grupos del inicio. Nombre, al menos un miembro y, si quieres, un equipo.
- **Cuatro modos**:
  - Por turnos. Tú escribes y cada uno habla una vez.
  - Libre. Siguen entre ellos hasta un tope, o hasta que nadie responda.
  - Debate. Una cadena fija: propuesta, pregunta, mejora. Cada uno responde al anterior.
  - Con moderador. Un miembro ordena la palabra. Si no hay moderador, vuelve al turno.
- **La barra**. Pausa, sigue o salta lo que falta. También la extensión de cada turno: corto, normal o largo.
- **El resumen**. Una tarjeta con la discusión. Se copia, se comparte, se guarda como documento o se sigue desde ahí.
- **Votar**. Cada uno vota a favor o en contra, según su personaje, y dice por qué.
- **Plantillas**. Revisión de un plan, a favor y en contra, lluvia de ideas o retrospectiva. Un toque deja los parámetros listos.
- **Observadores**. Miran y no hablan.
- **El contexto**. Un documento que ven todos, y un contexto privado de cada asistente.
- **Menciones**. @ en el campo nombra a alguien. También puedes hablarle solo a uno. Los mensajes se pueden citar. El grupo se busca, se fija y se edita.
- Cada asistente conserva sus recuerdos. No se mezclan.

#### 13.2 Un equipo

- **Ajustes → Agente → Equipos**.
- Le pones nombre, un objetivo y los miembros. Hay una plantilla de redacción: borrador, primera versión y pulido.
- El orden, las dependencias y cómo se resume se configuran.
- Encendido, el asistente principal reparte el trabajo. La tarjeta muestra el proceso.

#### 13.3 Mensajes entre ellos

Se escriben entre sí, por ejemplo mientras delegan. Se leen en **Ajustes → Agente → Mensajes del agente**. Puedes marcarlos todos como leídos.

---

### 14. Habilidades, complementos y MCP

#### 14.1 Habilidades

Una habilidad es una herramienta que se puede instalar: dibujar, traducir, un código QR, recuerdos, tareas, tarjetas, avisos o una subtarea.

- **Ajustes → Mi asistente → Recursos → Habilidades**. También salen del signo más, en el chat.
- Cada una se activa o se apaga. El archivo de importación es `.skill.json`.
- Algunas piden un dato al usarlas.
- Al importar hay una revisión. Si parece una inyección de instrucciones o se hace pasar por el sistema, se rechaza.

#### 14.2 Complementos

- **Ajustes → Complementos**, o desde el centro de conexiones. Tres pestañas: instalados, tienda y confianza.
- **Importar**. Un `.muse-plugin` o un ZIP. Antes de instalar ves la versión, el autor, lo que declara, las herramientas y la huella del código.
- **Firma**. Tres estados: verificado y de confianza en este teléfono, firma válida pero editor desconocido, o sin firma. Sin firma solo se guarda como borrador y no corre. Un editor desconocido pide que confíes en la huella de la clave pública.
- **Que lo escriba el asistente**. Puede dejar un borrador sin firmar. Lo revisas y tocas «Firmar y activar». Entra en vigor con la clave local.
- **La tienda**. El directorio oficial se puede explorar e instalar. La dirección y la raíz de confianza se pueden cambiar para probar.
- El código corre en un entorno limitado de JavaScript. Las capacidades y el riesgo se muestran.

#### 14.3 MCP

- **Ajustes → Centro de conexiones → MCP**.
- Un servidor externo por SSE o Streamable HTTP. En Android no hay stdio local.
- Acepta un token Bearer, cabeceras propias y OAuth. Si se corta, reintenta.
- Las herramientas del servidor se registran con el prefijo `mcp_`. También puedes ver los recursos y los mensajes que expone.
- Cada asistente ve solo los servidores que le marques en **Detalle → Recursos → MCP**.
- Si el nombre de la herramienta incluye borrar, enviar o ejecutar, pregunta siempre. Si es leer, listar u obtener, corre. El resto sigue tu política de aprobación.

#### 14.4 Conectores

Sirven para un servicio que pide iniciar sesión. La credencial se cifra en el teléfono. Si no ves la entrada, busca «Conector» arriba de Ajustes.

---

### 15. Canales de mensajería

#### 15.1 Para qué

Muse puede escribir en tus apps de chat, y también recibir. El asistente responde solo si lo activas. **Cada canal es una conversación**, con sus propios recuerdos.

#### 15.2 Plataformas

| Plataforma | Cómo entra |
| --- | --- |
| WeChat | Código QR, por el protocolo iLink. Una cuenta de WeChat solo se vincula a un canal |
| QQ | Bot de la plataforma abierta: App ID y App Secret. El destino puede ser un grupo o un chat |
| Feishu | Una app propia: App ID y App Secret. Feishu en China o Lark afuera |
| Telegram | El token del bot, con @BotFather. El `chat_id` de destino se puede dejar vacío: responde a quien escribió |
| DingTalk | Bot de empresa: Client ID, App Secret y Robot Code |

#### 15.3 Conectar

- **Ajustes → Herramientas y conexiones → Centro de conexiones → Canales**. Cada plataforma tiene un tutorial y un botón para probar la conexión.
- Las credenciales se cifran en el teléfono. No se suben.
- **Respuesta automática**. Al llegar un mensaje, el asistente responde. Cada vuelta gasta cuota. Eliges qué asistente responde, con su modelo y sus parámetros.
- **Cuál es tu cuenta**. Marca tu cuenta como destino de envío de ese canal.

#### 15.4 Las conversaciones del canal

- Cada contacto o grupo crea una conversación, con su contexto y su resumen.
- Puedes ver el historial o reiniciar el contexto.
- En la página del canal, «Recibidos recientemente» lista lo que acaba de llegar.

> En WeChat, un aviso proactivo necesita una ventana abierta por un mensaje reciente. Si no llega, escribe primero tú a ese canal.

---

## Sexta parte. El día a día y los datos

### 16. Mensajes proactivos

#### 16.1 Que te escriba

**Ajustes → Mensajes proactivos**. También se entra desde Agente.

- **Intervalo**. De 15 minutos a varios días. Un desplazamiento al azar evita que caiga siempre en el mismo minuto. En 0, el intervalo es fijo.
- **Cuánto insiste**. Poco (cerca de una vez cada medio día, y como máximo una al día), normal (cerca de una cada 4 horas, hasta 3), mucho (cerca de una cada 2 horas, hasta 8) o un valor tuyo.
- **Silencio**. En esas horas no escribe.
- También están la franja permitida, el tope diario (de 1 a 10, o sin tope), la probabilidad (de 0 a 100 %), el tipo de contenido (resumen del día, diario de la noche, actualizaciones) y un botón para probar un mensaje.
- Decide si escribe según lo reciente de la conversación, el ánimo y si estás activo.

#### 16.2 El resumen, la noche y las publicaciones

En **Ajustes → Agente**:

- **Resumen del día**. Varias veces al día. Eliges en qué franjas llega la notificación: madrugada, mañana, mediodía o noche.
- **De noche, solo**. Ordena recuerdos y escribe un diario sin avisarte.
- **Publicaciones del día**. Cuántas actualizaciones publica. Se puede apagar.

#### 16.3 Actualizaciones

- Muse y los asistentes publican texto o una imagen. Puedes dar me gusta, comentar y guardar.
- «Novedades» avisa quién reaccionó. Cada uno tiene su página.
- Arriba a la derecha publicas tú: un toque corto para imagen y texto, uno largo para solo texto. También puedes pedir que genere una ya. Se buscan, y hay un filtro de solo guardadas.

#### 16.4 Teléfono virtual

- **Ajustes → Teléfono virtual**. En la app figura como función experimental de una versión anterior. Al activarlo, aparece una entrada arriba a la derecha del inicio.
- Es un escritorio chico: actualizaciones, álbum, notas, clima y un diario del modelo.
- Las fotos del álbum se ven a pantalla completa, se guardan en el álbum del sistema o se ocultan.

#### 16.5 Hitos y números

- **Hitos**. La primera conversación, los días juntos, la cantidad de mensajes. Se filtran en la línea de tiempo del centro de recuerdos.
- **Estadísticas**. Ajustes → Estadísticas. Hoy, esta semana, este mes o todo. Conversaciones, mensajes, racha, el día más activo, la semana, el mapa de actividad y qué modelos usas. En el centro de recuerdos hay otras: importancia, conversaciones más activas y los últimos 30 días.

---

### 17. Apariencia

#### 17.1 Tema

**Ajustes → Apariencia**.

- **12 temas**: papel cálido, flor de cerezo, océano, primavera, otoño, blanco y negro, tinta, papel japonés, índigo, violeta de atardecer, ámbar y rosa de atardecer. Cada uno tiene claro y oscuro.
- **Modo**. Seguir al sistema, claro u oscuro. El oscuro puede ser otro juego, o seguir al claro.
- **Por horario**. Una hora de día y una de noche. Cambia solo.
- **Color del fondo**. En Android 12 o posterior, toma el color del fondo de pantalla.
- **Más contraste**. Separa mejor el texto del fondo.

#### 17.2 Letra y burbujas

- **Tamaño**. Chico, mediano, grande o muy grande (0,85×, 1×, 1,15×, 1,3×), con vista previa.
- **Esquinas**. Cuadradas, redondeadas, muy redondeadas o en cápsula.
- **Piel**. La de siempre, o una que traiga un complemento.
- Los iconos son del juego propio de Muse. El proyecto abierto se llama muse-icons y tiene 176.

#### 17.3 Fondo del chat

**Ajustes → Chat → Apariencia**. Una imagen para el chat, para el agente o para el grupo. Sin imagen, puede ser un degradado de dos colores.

#### 17.4 Tu retrato

**Ajustes → la tarjeta de arriba → Retrato**.

Puedes completar cómo te llama, cómo se llama a sí mismo, una descripción, edad, ciudad, oficio, campo, estudios, oficio y experiencia, gustos, estilo, si prefieres respuestas cortas o largas, el tono, el idioma y lo que no quieres que toque. También la zona horaria. Nada es obligatorio.

Eso entra en las instrucciones del sistema. La descripción personal pesa más que el resto.

---

### 18. Datos, copias y privacidad

#### 18.1 Dónde están

Conversaciones, recuerdos, bases de conocimientos y asistentes están en el teléfono. No hay cuenta. La red viene apagada. Un registro de fallo se queda en el aparato.

#### 18.2 Copias

**Ajustes → Datos y copias**.

- **En el teléfono**. Un toque exporta conversaciones, mensajes y recuerdos a JSON. Si la base es grande, sale en partes. Con contraseña, el archivo se cifra con AES-256. Al restaurar se pisa lo actual, pero antes se guarda una copia de protección. Si falla, vuelve atrás.
- **En la nube**. WebDAV o un almacén compatible con S3 (Nutstore, Nextcloud y otros). El intervalo automático va de 1 a 30 días. Puedes restaurar una versión de la lista o borrar las viejas.
- **El historial automático**. Ajustes → Datos y copias → Copias recientes. Toca una para restaurar. Avisa antes, y si falla vuelve atrás.

#### 18.3 Traer datos de otra app

**Ajustes → Importar datos**.

- Un ZIP de **RikkaHub** o **Kelivo**: proveedores, asistentes y conversaciones. Se fusiona. Lo repetido no se pisa.
- También el export de **ChatGPT**, y el JSON de **CherryStudio** o **Chatbox** (proveedores y modelos).
- «Copia de Muse» importa un archivo que exportaste antes. Eso sí pisa los datos locales.
- Antes de importar, haz una copia de lo que ya tienes.

#### 18.4 Cuánto ocupa

**Ajustes → Datos**. Ves conversaciones, mensajes, recuerdos y el tamaño de la base y de la caché. Tres acciones, cada una con una segunda confirmación: borrar todas las conversaciones, vaciar la caché y restablecer los recuerdos. Lo último es como si no te conociera.

#### 18.5 Qué se comparte

**Ajustes → Datos y privacidad → Compartir**. Decides si el texto lleva la hora, el modelo, los tokens, el bloque de ánimo y el razonamiento. El formato puede ser Markdown, texto plano o HTML.

#### 18.6 Privacidad

- **Datos personales**. Viene activado. Antes de enviar, tapa teléfonos, correos y documentos. El proveedor ve un marcador. En la respuesta, Muse lo restaura.
- **Enlaces**. Al tocar un enlace, pregunta. Puedes decir que en esa conversación no vuelva a preguntar.
- **Proxy**. Uno de red, si lo necesitas.
- **Auditoría**. Llamadas, acciones tuyas, autenticación, el sistema y lo que hizo el agente.
- **Si no arranca**. El registro del fallo es local. El modo seguro permite entrar, y los datos siguen ahí.

#### 18.7 Experimental

**Ajustes → Experimental**. Funciones que todavía se mueven: recuperar adjuntos, forzar el bloque de ánimo, depuración, reflexión, comprimir recuerdos largos, correr en paralelo las herramientas de solo lectura. Pruébalas si quieres.

---

### 19. La web y otras entradas

#### 19.1 El servidor web

En **Ajustes → Datos y copias**, el bloque del servidor web.

- Encendido, el teléfono atiende a otros aparatos de la misma Wi-Fi. El puerto va de 1024 a 65535.
- El acceso desde la red local viene cerrado. Por defecto solo entra este teléfono.
- La primera visita pide un PIN de 6 dígitos. Se genera solo, se puede copiar y se puede renovar. También hay una contraseña de 8 caracteres. Puedes activar HTTPS con un certificado propio. El navegador va a decir que no lo reconoce. Es esperable.

#### 19.2 Widgets y atajos

- **Widgets**. Uno de conversación nueva y recientes, y otro de la última respuesta. Se agregan al escritorio.
- **Mantener pulsado el icono**. Nueva conversación, traducción, voz, Ajustes o una nota.
- **Teclado**. Ctrl+K busca, Ctrl+N abre una conversación, Ctrl+, abre Ajustes, Ctrl+Mayús+C copia la respuesta, Enter envía, Mayús+Enter baja de línea y Esc cierra.

#### 19.3 Notas rápidas

- **Ajustes → Notas rápidas**, o el icono de la app, mantenido, en «Nota».
- Título, texto y `#etiquetas`. Carpetas, fijar, buscar y un recordatorio. Adjuntos, cifrado y exportación a Markdown o JSON.
- «Enviar a una conversación nueva» le pasa la idea al asistente.
- Hay papelera. Lo borrado se recupera o se elimina del todo.
- La captura flotante se activa en los ajustes de las notas. El asistente puede guardar en el mismo sitio.

#### 19.4 Notificaciones

- **Ajustes → Notificaciones**. Con el permiso, el asistente lee avisos del teléfono (un envío, un mensaje) y puede responder o recordarte algo.
- Eliges qué textos entran al contexto. El listado filtra por todos, no leídos o solo la barra. Se exporta y se vacía.
- El texto se queda en el teléfono, hasta 200 avisos. Los más viejos se van. No entran los del sistema ni los de Muse. Puedes vaciar la lista o quitar el permiso cuando quieras.

#### 19.5 Traducir aparte

- **Ajustes → Traducción**. Eliges el idioma, pegas el texto y copias el resultado.
- Traducir un solo mensaje está en el menú largo de ese mensaje (4.4).

---

## Preguntas frecuentes

**¿Hay que pagar?**

Muse es gratis y de código abierto. No hay compras dentro de la app. Lo que se paga es la API del proveedor, por uso. Casi todos dan crédito al registrarte. Para gastar menos, pon un modelo barato en los modelos auxiliares (3.3).

**¿Dónde están los chats y los recuerdos?**

En el teléfono. Sin cuenta y sin subida. Haz una copia para tenerlos también en otro lado (capítulo 18).

**Si cambio de modelo, ¿se olvida?**

No. Los recuerdos son de Muse, no del modelo. Cambia de proveedor cuando quieras.

**¿Por qué a veces tarda?**

Suele ser el razonamiento, una herramienta o el proveedor saturado. Si ves una tarjeta de tarea o el estado de razonamiento, está trabajando. Si ya es demasiado, detén y vuelve a enviar.

**¿Cómo hago que recuerde algo ya?**

Di «Recuerda: …». Queda fijado, sin esperar al orden del fondo (8.3).

**¿Y si lo recordó mal?**

En el centro de recuerdos, busca la entrada y cámbiala o bórrala. Cada una muestra el origen y los cambios.

**La herramienta pide un permiso.**

Abre el canal en **Ajustes → Asistente de permisos**. Si falta, el mensaje dice cuál. No falla en silencio (capítulo 11).

**¿Cómo actualizo?**

**Ajustes → Buscar actualizaciones**. Si hay una versión, te lleva a museai.ltd/download. Instala encima. Los datos se quedan.

**¿Cómo paso a un teléfono nuevo?**

Copia en el viejo, en un archivo o en la nube, y restaura en el nuevo (18.2).

**Quiero que escriba menos, o más.**

**Ajustes → Mensajes proactivos**. Cambia el intervalo, el nivel o las horas de silencio (16.1).

**¿Cómo gasto menos datos y batería?**

Enciende la búsqueda y el razonamiento solo cuando hagan falta. Generar imagen o video, y volver a indexar la base de conocimientos, es lo que más consume. Mejor con el teléfono enchufado.

---

## Apéndice A. Formatos de copia y de archivo

- **Exportación local**. JSON. Si hay muchos datos, NDJSON en partes: la primera línea son metadatos y después un registro por línea. Con contraseña, el archivo se cifra y la misma contraseña lo abre al importar.
- **Copia automática**. `muse_backup_` más la fecha, en zip. Adentro van las bases locales: conversaciones, mensajes, recuerdos y hechos. Al restaurar se reemplaza la base, se reconstruye el índice de búsqueda y, antes, se guarda una copia de protección.
- **Tarjeta de personaje**. PNG o JSON compatible con SillyTavern (12.5).
- **Habilidad**. `.skill.json` (14.1). **Complemento**. `.muse-plugin` o ZIP (14.2).

## Apéndice B. Glosario

| Palabra | Qué es |
| --- | --- |
| API Key | La credencial del proveedor. Sin ella no hay llamada al modelo |
| Proveedor | Quien sirve el modelo, o una pasarela hacia él |
| Token | La unidad con la que el modelo mide el texto. Es más fina que una palabra |
| Contexto | Cuánto texto puede tener en cuenta de una vez |
| Razonamiento | El borrador de antes de responder. Más profundo, más lento, y mejor en una pregunta difícil |
| Base de conocimientos | Documentos tuyos, partidos en fragmentos, para citar al responder |
| Habilidad | Una herramienta que se instala |
| Complemento | Un paquete de otra persona. Corre en un entorno limitado y pide firma o confianza |
| MCP | El protocolo para conectar herramientas y datos de afuera |
| Shizuku, root y Termux | Tres formas de darle permisos de sistema. Capítulo 11 |
| Ánimo | El bloque interno que se muestra antes de la respuesta |
| Canal | WeChat, QQ, Feishu, Telegram o DingTalk, como una conversación más |
| Mensaje proactivo | Muse te escribe según un ritmo, sin que lo llames |

---

*El manual se actualiza con las versiones. Si algo no coincide, abre un informe en GitHub o avisa en el grupo.*
