# Muse v2.6.4

> Repare la ruta crítica de RAG sin tiempo de espera (hasta 5 minutos), la superposición de bloques nunca tiene efecto, la indexación falla y los datos se pierden silenciosamente.
> Mensaje activo OOC, el modelo multimodal de estación repetidora está mal etiquetado.

---

## chino

### El envío de mensajes puede retrasarse 5 minutos (P0)

RAG La recuperación está en el camino crítico del envío de mensajes, mientras que query embedding **sin ningún tiempo de espera**:

- Lo que se reutiliza es OkHttpClient para chat (`readTimeout = 300s`)
- embedding Una vez que el punto final "acepta la conexión pero no responde", el usuario tendrá que esperar más tiempo después del envío. **5 minutos**Acabo de ver la primera palabra.

Ahora agregue la incrustación de consultas **10 segundos de tiempo de espera duro**. Los tiempos de espera y los fallos se tratan de la misma manera: se activa un disyuntor de 5 minutos.
Esta búsqueda se redujo a búsqueda de palabras clave locales y el chat continuó como de costumbre.

> Si su servicio de integración siempre ha sido normal, es posible que no haya encontrado este problema; pero si cambia a un punto final que responde lentamente, lo hará.

### La superposición fragmentada nunca funcionó (P1)

`markdownAware` Está habilitado de forma predeterminada, pero el orden se invierte en esta rama:

```kotlin
flush()                                                  // Borrar primero
val tail = current.toString().takeLast(overlap)          // Obtener la cadena vacía
if (tail.isNotBlank()) current.append(tail)              // nunca ejecutar
```

Resultado:**Todas las configuraciones de "superposición de bloques" ajustadas por el usuario son configuraciones inactivas.**——UI Ajustable, la base de datos existe, pero nunca se utiliza.
El orden se invirtió y se agregaron 4 bloqueos de prueba unitarios.

### Solo se inyectan las primeras 200 palabras en cada bloque (P1)

Codificación dura al inyectar base de conocimientos `take(200)`. Cuando ajustas el tamaño del fragmento a 500,**El último 60% nunca entrará en el contexto.**——
Gasté más dinero en incrustar, pero solo vi 200 palabras más.

Ahora que se menciona el límite superior en 1200, la verdadera adaptación se deja a la lógica del presupuesto simbólico existente.

### Si la reconstrucción del índice falla a mitad de camino, se borrará silenciosamente (P0)

Al reconstruir el índice, primero se eliminan los bloques antiguos y luego se escriben bloques nuevos. Si falla a mitad de camino:

- El índice antiguo ha sido eliminado.
- El nuevo índice está incompleto
- El documento todavía está en la lista y parece normal.
- **Pero la búsqueda nunca puede recuperarlo y no aparece ningún mensaje.**

Ahora estará marcado como "Índice perdido" y aparecerá un texto rojo debajo del documento en la lista de la base de conocimientos:
**"El índice se ha perdido (la reconstrucción falló a mitad de camino) y la búsqueda no se realizará. Mantenga presionado para volver a indexar".**

No se agregan nuevas columnas de base de datos y no hay riesgo de migración.

### Mensajería proactiva OOC (comentarios de los usuarios)

Síntomas: los mensajes no solicitados no tienen nada que ver con el contexto, los caracteres están muy distorsionados y los caracteres en inglés envían mensajes en chino.

La causa fundamental es la superposición de tres lugares:

**① Carácter truncado a 500 palabras**
```kotlin
appendLine("Tu referencia de personaje:${assistant.systemPrompt.take(500)}")
```

**② No he leído en absoluto la definición de personalidad y cadena de pensamiento.**——`personaDefinition` / `thinkingChain` Estos dos campos
No ha sido citado en absoluto en los mensajes activos.

**③ sin restricciones de idioma**——prompt Escribe todo en chino y el modelo te responderá en chino.
Los caracteres ingleses también se han convertido en "estadounidenses que hablan chino".

Ahora:
- Definición completa de personalidad + sistema completoPrompt (no más truncamiento)
- Inferir el idioma del habla basándose en el propio corpus del personaje y exigir claramente "incluso si la palabra indicada es chino, no envíe mensajes en chino".
- Conversaciones recientes de **5 Artículo → 12 Artículo**, una sola línea de **200 palabra → 400 palabra**(5 La barra no forma un contexto, por eso tiene "0 relación con el contexto")

### El modelo multimodal de la estación de transferencia aún no puede ver la imagen (comentarios de los usuarios)

Revisado en versión anterior. `enhanceModel` La lógica de la cobertura, pero falta una cosa:

```kotlin
inputModalities = inputModalities ?: setOf("text")   // Al construir el modelo, configúrelo como texto sin formato.
```

Cuando la capacidad no se declara aguas arriba, el modelo nacerá "no admite la visión" y no importa cuán inteligente sea más adelante, no podrá guardarlo.

se ha modificado para dejarlo en blanco y se ha entregado al registro de capacidades para inferir en función del modelId (`openrouter/gpt-4o` Estos prefijos también pueden eliminarse de las coincidencias).
Cuando no se pueda hacer la inferencia, el comportamiento será el mismo que antes y no habrá lesiones accidentales.

### #8 Corrección de descripción de licencia de terceros

`NOTICE` y `assets/licenses/manifest.json` adentro,ML Kit La licencia de anteriormente estaba mal etiquetada como Apache-2.0.
La declaración POM real es **ML Kit Terms of Service**, corregido y anotado.

Muse No existen licencias adicionales ni excepciones de vinculación a la Sección 7 de la GPL per se, que también se indica en la documentación.

---

## English

### Sending a message could hang for 5 minutes

RAG retrieval runs on the send path, and query embedding had **no timeout** —
it reused the chat OkHttpClient (`readTimeout = 300s`). An endpoint that accepts
the connection but never responds left users waiting up to 5 minutes for the
first token.

Added a **10-second hard timeout**; a timeout now triggers the same 5-minute
circuit breaker as a failure and falls back to local keyword search.

### Chunk overlap never worked

`markdownAware` defaults to on, and that branch cleared the buffer *before*
reading the tail:

```kotlin
flush()                                              // clears
val tail = current.toString().takeLast(overlap)      // always empty
if (tail.isNotBlank()) current.append(tail)          // never runs
```

Every user's overlap setting was silently dead. Fixed and covered by 4 unit tests.

### Only the first 200 characters were injected

Hard-coded `take(200)` meant raising chunkSize to 500 still only surfaced the
first 40% — more embedding cost, less context. Cap raised to 1200; actual
trimming is handled by the existing token budget.

### Failed re-index silently wiped documents

Re-index deletes old chunks before writing new ones. On mid-way failure, the old
index was gone, the new one incomplete, and the document still looked fine in the
list — but could never be retrieved, with no warning.

Now marked as broken, and the document card shows:
**"Index lost (re-index failed midway), retrieval will not hit it. Long-press to re-index."**
No schema change, no migration.

### Proactive messages out of character

Three compounding causes:

1. Persona truncated to 500 chars
2. `personaDefinition` and `thinkingChain` never read
3. No language constraint — the prompt is written in Chinese, so the model answers
   in Chinese even for English-speaking characters

Now: full persona, language inferred from the character's own text, and recent
context expanded from 5 to 12 messages (200 → 400 chars each).

### Relay-hosted multimodal models still could not see images

The previous release fixed the override logic but missed the constructor default
(`inputModalities ?: setOf("text")`). Now left empty so the capability registry
can infer it, including for prefixed ids like `openrouter/gpt-4o`.

### License notice correction (#8)

ML Kit was previously labeled Apache-2.0 in `NOTICE` and the licenses manifest.
The actual POM declares **ML Kit Terms of Service**; corrected and annotated.
Muse itself carries no GPL-7 additional permissions or linking exceptions.

---

## Instalación

- Simplemente sobrescriba e instale.**No es necesario desinstalar**, retención de datos
- La versión de la base de datos aún está **111**(El esquema no ha sido cambiado esta vez),**Sin riesgo de migración**
- Firmado con 2.6.1 / 2.6.2 / 2.6.3 Lo mismo

## cosas conocidas

- #5(reasoning filtrado al texto) no se ha modificado y entra en conflicto con el principio existente de "vomitar está bien, pero vaciar no está bien", y debe decidirse por separado
- Las mejoras en el lenguaje y el tono de los mensajes proactivos dependen de que el rol en sí esté claramente escrito; Aún será más general cuando esté vacío.
