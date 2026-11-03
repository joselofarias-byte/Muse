# Muse v2.6.5

> Solución completa para informes de auditoría en profundidad de terceros (27 18 de los artículos se pueden implementar)+ Tres problemas de chat reportados por los usuarios.

---

## Agradecimientos

Una gran cantidad de correcciones en esta versión provienen de amigos del grupo QQ. **Atri アトリ** Informe de auditoría de terceros proporcionado.

Ese informe no era general."si bug", pero proporciona la ubicación del archivo, el número de línea, el análisis de la causa raíz, la ruta de recurrencia y el método de verificación uno por uno.——
27 En esta edición se implementan 18 de los artículos, incluidas varias cuestiones que son difíciles de comprobar por uno mismo:

- búsqueda mixta"Concurrencia de rutinas"De hecho, se ejecuta secuencialmente.
- La superposición de bloques nunca tuvo efecto porque el orden se escribió en orden inverso.
- reindexPlan Lea toda la biblioteca incrustada en la memoria para calcular un valor booleano
- Si no se reconstruye el índice, se borrará silenciosamente el índice de todo el documento.

Sin este informe, estos problemas probablemente habrían estado acechando aguas arriba durante mucho tiempo. Gracias.

---

## chino

### Corrección de comentarios de los usuarios

#### El chat eliminado volvió por sí solo y comenzó a generarse automáticamente.

Se deja un punto de control (ancla de recuperación) cuando se interrumpe una compilación. Al eliminar un mensaje, se borrará con precisión mediante identificación, pero la identificación generada previamente cuando se interrumpe la transmisión puede ser diferente de la ubicación real.**No está bien**, así queda.

La próxima vez que ingrese a la sesión, la lógica de recuperación reconstruye los puntos de control restantes en un nuevo mensaje y los clasifica por la marca de tiempo de los puntos de control, de modo que llegue al final de la lista.——parece"Eliminado y reaparecido".

Ahora, al eliminar un mensaje, los puntos de control restantes de la sesión se limpiarán en un tiempo adicional. Conservar mensajes anteriores a los mensajes eliminados.

#### Las respuestas que se hayan generado desaparecerán si vuelves a ingresar.

El mensaje que ingresa a la biblioteca tiene la semántica REEMPLAZAR. Si hay una respuesta completa y luego vuelve**El contenido está vacío.**Si la escritura (restablecimiento del estado de transmisión, transmisión de la ruta de recuperación está vacía, falta el campo de reconstrucción de la rueda de herramientas), la respuesta completa se vaciará.

Ahora se agrega protección de regresión de contenido: cuando el contenido anterior no está vacío y el nuevo valor está vacío, el contenido anterior se conserva.

#### Navegación con pulsación larga en la barra lateral"ido"

**La funcionalidad siempre está ahí, pero es invisible.**

10-09 cambios para reparar"Control deslizante visual flotando en sesiones largas", elimina toda la pista y cámbiala a una zona activa invisible con una pulsación larga a la derecha + deslizando hacia arriba y hacia abajo. La razón es cierta:LazyColumn La altura total no está expuesta y sólo se puede extrapolar en base a la altura promedio. Al encontrar imágenes/Los elementos largos, como los bloques de código, son extremadamente propensos a errores y el control deslizante saltará aleatoriamente.

Pero ser completamente invisible no es nada. Ahora agrega uno**Aparece solo al deslizar**La línea de aviso:

- Generalmente completamente oculto
- Pulsación larga en el borde derecho → Vibración de luz + aparición gradual (120ms)
- Manténgase visible mientras desliza el dedo, mientras aparece la vista previa del mensaje
- Déjalo ir y desvanecete (220ms)

No se realiza ningún mapeo de posición, por lo que no volverá a ocurrir una flotación aleatoria.

Al mismo tiempo, cambie el umbral de visualización de **25 los mensajes cayeron a 5**.

---

### Reparación de informe de auditoría

#### La recuperación híbrida finalmente es verdaderamente paralela
La anotación dice"Concurrencia de rutinas", en realidad se ejecutan dos líneas secuencialmente (`resultOf` Sólo intenta/atrapa el envoltorio, no `async`). La tediosa búsqueda vectorial de grandes bases de datos y BM25 se superponen por completo. Usar en su lugar `coroutineScope + async`, el consumo total de tiempo cambia de "la suma de los dos" a "el mayor de los dos".

#### El tamaño del fragmento ya no es un valor establecido × 2
`plainChunkLimit = targetSize * 2` Permite insertar un solo fragmento por completo cuando el búfer está vacío. Digamos 400 y es posible que obtenga casi 800. Ahora presione estrictamente `targetSize` Rebanada.

#### La superposición de bloques finalmente entra en vigor
`markdownAware` Está habilitado de forma predeterminada, pero el orden en esta rama se invierte: primero `flush()` Limpia el buffer, luego toma la cola y toma la superposición.——Obtenga siempre una cadena vacía.**La superposición de configuraciones para todos los usuarios nunca tuvo efecto**.

#### Límite de inyección por bloque aumentado de 200 palabras 1200
Implementación antigua codificada `take(200)`, después de ajustar el tamaño del fragmento a 500, el 60 % nunca entrará en el contexto.——Gaste más dinero en integración y obtenga menos información.

#### RAG Ya no es posible recuperarlo si se queda atascado durante 5 minutos.
query embedding No hay tiempo de espera y el OkHttpClient utilizado para el chat se reutiliza (`readTimeout = 300s`). El usuario tiene que esperar 5 minutos cuando el terminal "acepta la conexión pero no responde". Ahora agregue un tiempo de espera estricto de 10 segundos y, cuando expire, se reducirá a la recuperación de palabras clave locales.

#### La reconstrucción del índice ya no lee toda la base de datos en la memoria
Para calcular un valor booleano `getAllWithEmbedding()`, lea el fragmento completo de la biblioteca junto con incrustarlo en la memoria (1024 Dimensiones: aproximadamente 4 KB/bloque,1 10.000 yuanes (40 MB+). Utilice recuentos de SQL en su lugar.

#### La reconstrucción de índices ya no los borra silenciosamente
Cuando falló a mitad de camino, el índice anterior se eliminó y el nuevo índice quedó incompleto. Los documentos todavía estaban en la lista y parecían normales, pero nunca se pudo recuperar la búsqueda. Ahora está marcado como "Índice perdido" y se muestra un mensaje de texto rojo debajo de la tarjeta del documento.

#### El número "similitud 0,83" es falso
La búsqueda híbrida devuelve la relación de la "puntuación relativa más alta" (la más alta es uniformemente 1,0), no la similitud del coseno. El resultado ahora conserva el verdadero `rawScore`.

#### Las visitas débiles a las palabras clave ya no se incluyen en los resultados.
`threshold` Anteriormente solo funcionaba en rutas vectoriales,BM25 Los golpes participan en la fusión por muy débiles que sean. Los umbrales de las postales ahora también están normalizados por la fuerza relativa.

#### InicioDos saludos de tiempo contradictorios
El resumen escribe saludos de la hora del día en el texto. Los saludos en la página de inicio son en tiempo real. "Buenas noches" y "Buenos mediodía" aparecerán al mismo tiempo por la noche. El resumen ya no incluye información sobre el período de tiempo.

#### 30 Reconstrucción del punto del documento del artículo pero genera 46 artículos
Lo que es adicional es la documentación de desarrollo incorporada. Es necesario reconstruirlos juntos, pero no deben incluirse en los números que ven los usuarios. Ahora presione incorporado/Separación de documentos de usuario.

#### 8 Los elementos numéricos ahora se pueden ingresar manualmente
Anteriormente, solo podía arrastrar el control deslizante, que estaba limitado por el tamaño del paso: el tamaño del bloque era 100 (**380 No puedo configurarlo**), el número máximo de búsquedas es 10,Token Presupuesto limitado a 8.000. Ahora haga clic en el área de valor para ingresar y se recortará automáticamente al rango legal.

#### Se agregó el botón "Probar incrustación"
Falta de coincidencia (nombre de modelo incorrecto, saldo insuficiente,Provider No compatible) Anteriormente, solo podía averiguarlo después de reconstruir el índice y el error solo se informaba en el registro. Ahora inserte una oración con un clic `ping`, haga eco de las dimensiones y el tiempo necesario, o proporcione un motivo de error legible.

#### Reindexar falló y finalmente hablar palabras humanas
El antiguo mensaje simplemente decía "46 "Error", los errores reales (como HTTP 402 Saldo insuficiente) solo están en logcat. Ahora muestre las dos primeras razones específicas.

#### Agregar nueva nube Rerank
El informe dice que esta es la mayor brecha de capacidad en comparación con herramientas similares. Se agregó compatibilidad con OpenAI `POST {baseUrl}/rerank`,SiliconFlow / Jina / Cohere Se puede reutilizar. La reclasificación local está básicamente inactiva para los chinos (trate toda la oración china como un token) y ahora existe una solución en la nube.

#### Cubrir los parámetros de búsqueda por base de datos de puntos de conocimiento (DB 111→112)
La implementación anterior tiene una configuración RAG global y diferentes bibliotecas no pueden tener diferentes valores de fragmento/topK/umbral. Cada biblioteca ahora puede anular `chunkSize` / `chunkOverlap` / `topK` / `threshold` / `embeddingModel`, déjelo en blanco para heredar el valor global.

#### El modelo ONNX local tiene una entrada
`localModelPath` No ha habido ningún acceso a la capa de interfaz de usuario y los usuarios normales nunca podrán igualar el modelo ONNX.LOCAL El modo siempre se degrada a búsqueda de palabras clave. Ahora puede seleccionar archivos .onnx en la página de configuración y copiarlos automáticamente al directorio privado de la aplicación.

#### Gesto de retorno a pantalla completa tocado accidentalmente
0 posiciones antes de la posición completa `systemGestureExclusionRects`y el contenido está a solo 16 dp del borde de la pantalla. Al deslizar el dedo horizontalmente desde el borde, el sistema regresará primero. Se agregaron exclusiones de gestos para pasar la página horizontal en la página de inicio y deslizar el dedo hacia la izquierda para eliminar la lista de conversaciones.

#### La entrada de la página de enlace profundo no tiene efecto
`onNewIntent` no llamado `setIntent()`, dando lugar a posteriores `getIntent()` Siempre devuelve la intención anterior al inicio.

#### Otros
- El problema de enviar borradores de pensamiento en inglés al chat grupal (v2.6.4 Volver) Corregido: ya no se rescata el contenido del discurso del razonamiento
- Mensaje activo OOC: el carácter ya no se corta en 500 palabras, se agregan definiciones de personalidad y restricciones de idioma, y el contexto es de 5 elementos. → 12 Artículo
- El modelo multimodal de la estación de transferencia ya no se elimina por error en la tabla de capacidades incorporada.
- Se ha solucionado la falsa alarma del botón "Test" del proveedor (maxTokens 16 → 256)
- Los detalles del error ya no se truncan a 80 caracteres.
- Termux Suplemento guía de autorización `termux-reload-settings`
- Se agregó un interruptor de "modo de respuesta rápida" para omitir la recuperación de memoria relevante de aproximadamente 500 ms por ronda.
- 4 en `!!` convergencia,`GlobalScope` Convergencia al alcance a nivel de módulo

---

## Acknowledgements

A large part of the fixes in this release come from a third-party audit report
contributed by **Atri アトリ** from our QQ group.

The report was not a vague "something is broken" — every item came with file
locations, line numbers, root-cause analysis, reproduction steps and a way to
verify the fix. 18 of its 27 findings landed in this version, including several
that are very hard to spot on your own:

- Hybrid search's "coroutine concurrency" was in fact sequential
- Chunk overlap never worked, because the buffer was cleared before reading its tail
- `reindexPlan` loaded the entire library's embeddings into memory to compute one boolean
- A failed re-index silently wiped a document's whole index

Without this report these would likely have stayed hidden upstream for a long time.
Thank you.

---

## English

### User-reported fixes

**Deleted chats came back and auto-generated.** Interrupted generations leave a checkpoint. Deletion cleaned by exact id, but the pre-generated id from a stream interruption often doesn't match the persisted row, so checkpoints survived. On next open they were rebuilt as new messages and sorted by checkpoint timestamp — landing at the bottom of the list. Deletion now also sweeps leftover checkpoints by timestamp.

**Generated replies vanished on re-entry.** Message upserts use REPLACE semantics. A later empty-content write (stream reset, recovery path, tool-round rebuild missing a field) wiped the full reply. Added content-regression protection.

**Side navigation "disappeared".** It never did — a 10-09 change removed the visible track (the slider drifted in long sessions because LazyColumn doesn't expose total height) and kept only an invisible long-press hot zone. A hint line now fades in only while navigating, and the threshold dropped from 25 messages to 5.

### Audit report fixes

- Hybrid search is actually parallel now (`coroutineScope + async`, was sequential)
- Chunk size no longer doubles (strict `targetSize` split)
- Chunk overlap finally works (was clearing before reading the tail)
- Per-chunk injection cap 200 → 1200 characters
- RAG query embedding has a 10s timeout (was unbounded, up to 5 minutes)
- Re-index no longer loads the whole library into memory (SQL count instead)
- Failed re-index is marked broken with a visible hint instead of silently wiping
- Results carry the real `rawScore`; the "similarity" shown before was a relative ratio
- BM25 hits now honor `threshold`
- Home no longer shows two contradictory time greetings
- Re-index counts exclude built-in docs (30 docs no longer reported as 46)
- Eight numeric settings accept typed input (chunk size 380 was unreachable)
- New "Test Embedding" button with dimension and latency
- Re-index failures report actual reasons
- **New cloud rerank** (OpenAI-compatible `/rerank`; SiliconFlow / Jina / Cohere)
- **Per-knowledge-base retrieval overrides** (DB 111 → 112)
- Local ONNX model can finally be selected in settings
- System gesture exclusion for horizontal surfaces
- Deep-link `setIntent()` fix
- Group chat no longer leaks English reasoning drafts (#10 regression)
- Proactive messages no longer out of character
- Relay multimodal models no longer misjudged as text-only
- Provider test false negatives fixed
- Error detail no longer truncated
- Termux permission guidance includes `termux-reload-settings`
- New "fast reply mode" skips ~500ms relevant-memory retrieval per turn

---

## Instalación

- Simplemente sobrescriba e instale.**No es necesario desinstalar**, retención de datos
- **base de datos 111 → 112**: Se agregó una columna de cobertura de parámetros de búsqueda a nivel de base de conocimientos (todos pueden estar en blanco; dejarlo en blanco heredará el global)
- Firmado con 2.6.1 ~ 2.6.4 Lo mismo

> ⚠️ Esta versión incluye migración de base de datos. La actualización desde cualquier versión 2.6.x requerirá 5 pasos `ALTER TABLE ADD COLUMN`, todos admiten valores NULL y tienen valores predeterminados. El riesgo es bajo, pero se recomienda realizar una copia de seguridad antes de actualizar por primera vez.

## cosas conocidas

- "El borde derecho del cuadro de mensaje se desborda" no se puede reproducir ni localizar, y el usuario debe proporcionar una sesión específica/Comprobar después de configurar
- #5 reasoning Las fugas se manejan según el plan C (inténtelo de nuevo la primera vez y solo asuma toda la responsabilidad si falla nuevamente), lo cual es un compromiso con el principio existente de "vomitar está bien pero no estar vacío".
- La línea base de migración histórica no está fusionada (esto invalidará la ruta de actualización para los usuarios de versiones muy antiguas y los riesgos superarán los beneficios)
