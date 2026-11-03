# Muse v2.4.6

Esta versión es una versión de corrección de errores posterior a la v2.4.5. Se centra en "las restricciones del proveedor de servicios ascendentes están localizadas" y soluciona tres tipos de problemas que harán que las solicitudes sean rechazadas por el proveedor de servicios ascendentes o dañen la experiencia interactiva.

## Reparación

### 1. La temperatura de muestreo fuera de los límites da como resultado HTTP 400 (el asistente de reemplazo no puede enviar el primer mensaje)

Algunos proveedores `temperature` adoptar**intervalo abierto**Restricciones (como las de SenseNova `[0.0, 2.0)`,2.0 en sí es ilegal), y el límite superior del control deslizante "Aleatoriedad de respuesta" en la aplicación es exactamente 2,0. La temperatura importada por la tarjeta de personaje de terceros puede tener cualquier valor. Previamente, estos valores se transmitirán al upstream tal cual, provocando `field Temperature invalid` E interrumpir esta ronda de generación.

Ahora al solicitar exportaciones, la temperatura se ajustará uniformemente según la declaración de capacidad del proveedor: Nuevo `ProviderCompat.maxTemperatureExclusive`, el límite superior de la declaración del punto final de SenseTime es 2,0;`ChatService` Fije la temperatura en un rango legal antes de construir la solicitud (en comparación con el existente `topP` Sujete la misma posición). Tirar del control deslizante a 2.0 ya no provocará un error.

### 2. Presupuesto de salida fuera de límites, lo que da como resultado HTTP 400 (max_tokens exceeds the limit)

Modelo no declarado `maxOutputTokens` Cuando , "toda la ventana de contexto restante" se consideraba anteriormente como el presupuesto de salida actual y se calculaba un valor cercano a la longitud total del contexto. `max_tokens`, estaba aguas arriba como `max_tokens exceeds the limit of 65536` Rechazar.

ahora `ModelOutputPolicy` Cuando "el modelo no declara un límite superior y la persona que llama no proporciona un presupuesto", se devuelve el valor predeterminado del proveedor y ya no se amplifica de la nada.

### 3. No sigue automáticamente el desplazamiento durante el proceso de respuesta

Anteriormente, el seguimiento automático requería "El último elemento visible resulta ser el último mensaje", pero se insertará "Pensando" al final del área de mensajes./Indicaciones de ejecución de herramientas, marcadores de posición de imágenes y videos, tarjetas de tareas de subagente y otros elementos de condición——Estos elementos quedarán invalidados tan pronto como sean juzgados, lo que provocará que la lista no siga durante todo el período de respuesta. Tienes que desplazarte manualmente hasta la parte inferior para ver el texto completo, y el estado "deslizado hacia arriba" se bloquea accidentalmente.

Ahora se cambia a "se alcanzó o pasó el final del área de mensajes", y la lista se desplazará automáticamente hacia adelante una pantalla cuando el elemento final esté en la parte inferior, pero la lista aún se puede desplazar hacia adelante. Los usuarios que deslicen activamente el dedo hacia arriba dejarán de seguirnos inmediatamente (el diseño permanece sin cambios).

## Información de construcción

- Versión:2.4.6 (246)
- Versión de la base de datos:107
- Distribución:arm64-v8a / armeabi-v7a / universal

---
# Muse v2.4.6 (English)

This is a defect-fix release following v2.4.5, focused on keeping upstream provider constraints enforced locally. It fixes three issues that caused requests to be rejected upstream or degraded the interaction experience.

## Fixes

### 1. Out-of-range sampling temperature caused HTTP 400 (failed on the first message after switching assistants)

Some providers constrain `temperature` with an open interval (for example SenseNova uses `[0.0, 2.0)`, where 2.0 itself is invalid). The in-app "response randomness" slider tops out at 2.0, and imported third-party character cards may carry arbitrary values. These values were passed through unchanged, triggering `field Temperature invalid` and aborting generation.

The temperature is now clamped at the request boundary according to the provider capability declaration: a new `ProviderCompat.maxTemperatureExclusive` field lets the SenseNova host declare an upper bound of 2.0, and `ChatService` clamps temperature into the legal range before building the request (at the same point where `topP` was already clamped). Pushing the slider to 2.0 no longer errors.

### 2. Out-of-range output budget caused HTTP 400 (max_tokens exceeds the limit)

When a model did not declare `maxOutputTokens`, the full remaining context window was used as the output budget, producing a `max_tokens` close to the entire context length and getting rejected with `max_tokens exceeds the limit of 65536`.

`ModelOutputPolicy` now falls back to the provider's own default when the model declares no limit and the caller supplies no budget, instead of inflating the value.

### 3. No auto-follow scrolling during replies

Auto-follow previously required the last visible item to be exactly the last message. However, the message area appends conditional trailing items such as "thinking/tool running" hints, image and video placeholders, and sub-agent task cards. Once any of these appeared, the condition failed, so the list stopped following for the entire reply, forcing a manual scroll to the bottom to read the full text, and the "user scrolled up" flag was wrongly locked.

The check is now "reached or passed the end of the message area", and the list actively scrolls by one viewport when a trailing item is pinned to the bottom while the list can still scroll forward. A manual scroll up still stops following immediately (unchanged by design).

## Build

- Version: 2.4.6 (246)
- Database version: 107
- Distributions: arm64-v8a / armeabi-v7a / universal
