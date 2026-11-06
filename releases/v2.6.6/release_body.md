# Muse v2.6.6

> Se han solucionado varios aspectos de la interacción con tarjetas y los agentes ya no están bloqueados. Además, algunas funciones solían tener solo entradas y estaban vacías al hacer clic.

---

## chino

### tarjeta

Al hacer clic en el botón de la tarjeta se responde. En el pasado, varios caminos no estaban conectados en absoluto, los controles estaban dibujados y no pasaba nada cuando hacía clic en ellos.

Hay dos cosas más: ya no aparece inexplicablemente una barra de desplazamiento en la tarjeta; la tarjeta aparecerá primero durante el proceso de generación, sin esperar a que se escriba todo el párrafo.

La tarjeta ahora sabe quién es, puede volver a leer los datos vinculados y puede saber de qué tarjeta proviene al transmitir.

**Un cambio que puedes notar**: "Salida de tarjeta de preferencia" en la configuración está desactivada de forma predeterminada. Cuando está desactivado, el modelo no convertirá activamente tablas, comparaciones y cuadros de opciones en tarjetas, y simplemente escribirá normalmente. Si quieres que se use como tarjeta, ve a Configuración → Charla abierta.

La tarjeta en sí todavía se está modificando y esta versión no es su aspecto final.

### agente

En el pasado, cuando se configuraba un proxy, algunas solicitudes se procesaban y otras se omitían; Una vez realizados los cambios, fue necesario reiniciarlos para que surtieran efecto.

Ahora todas las solicitudes salientes siguen al proxy. Si cambia la conexión, la siguiente conexión será nueva y no será necesario reiniciar. Puede especificar qué direcciones no utilizan proxies. La "Conexión de prueba" en la página del proveedor puede indicarle directamente si el agente se puede conectar.

### Algunos lugares en los que se hizo clic anteriormente estaban vacíos.

- **biblioteca de experiencias,AI Tarjetas de comprensión, memoria de chat grupal.** Las tres pestañas solían tener solo implementación y sin entrada, pero ahora se puede ingresar a ellas.
- **Archivos adjuntos para notas rápidas**: La luz indicadora nunca se enciende porque las dos acciones de agregar y eliminar no se han conectado antes.
- **widget de escritorio**: No importa qué asistente elijas, siempre se utilizará el predeterminado; ahora se usará el que elijas
- **Widget "Entrada rápida de voz"**: Al hacer clic antes solo se crearía una nueva sesión y no se abriría el micrófono.
- **relación de rol**: Al realizar una copia de seguridad, solo se guarda pero no se restaura. Ahora puede ir y venir.
- **Condiciones para tareas programadas.**: Las condiciones están establecidas pero no son realmente efectivas.

### llamada de herramienta

Cuando el modelo encontró un problema con la herramienta, solo pude ver un error incomprensible en el pasado.

- Antes de ejecutar la herramienta, primero comprobará si el canal está abierto. Si no, te dirá directamente cómo abrirlo.
- Cuando falla, indicará si es un problema de red o de parámetros, y si se puede volver a intentar.
- También traerá "cuántas veces, cuántas veces quedan y si se debe continuar" para evitar que se estanque o se dé por vencido prematuramente.
- La cantidad de herramientas escritas en la palabra clave es consistente con la cantidad real. En el pasado escribía un número incorrecto.
- Puede consultar el mapa de herramientas en la interfaz para saber cuáles están disponibles actualmente.

### memoria

Ahora es posible buscar texto original histórico entre sesiones, separados por asistente.

Las nuevas sesiones solían recibir un mensaje vacío que decía "los cuatro párrafos no están disponibles", pero ahora ya no está bloqueado.

Lo que le digas, "No vuelvas a hacer eso", será recordado como una regla estricta, no solo por un turno.

### Otros

- La colección admite selección múltiple en lotes
- En el pasado, es posible que no haya enviado ningún mensaje proactivo.
- La copia de seguridad omitió un tipo de datos; la relación de rol fue restablecida
- Saldo insuficiente (402) se informará correctamente como un problema de saldo y ya no se tratará como un error de red ni se reintentará repetidamente.
- La traducción por lotes cumplirá con el glosario y el estilo personalizado, consistente con el calibre de una sola traducción.
- Se han unificado los mensajes de error de voz. Hasta ahora existían cuatro variantes de una función.
- En el chat grupal, los parámetros de la herramienta se unieron repetidamente, lo que provocó que el personaje permaneciera en silencio.
- Cuando no se configura ningún canal, ya no se generará un registro cada 5 segundos.
- Las tareas pendientes de la sesión se guardarán y la sesión no se perderá al cambiar.
- El modelo ahora puede leer archivos de su elección.
- Se pueden pegar secciones grandes en archivos; Los álbumes de fotos admiten la selección por lotes.

### espera animación

El indicador de espera se sustituye por un gráfico deformable de color tinta (hoja → estrella → anillo), todas las posiciones tienen el mismo aspecto.

El primer mensaje de una nueva conversación tiene una animación de envío.

### Estabilidad

- Las capturas de pantalla accesibles siempre han fallado antes, pero ahora funcionan.
- Shizuku Tomar capturas de pantalla solía colgar el servicio en segundo plano, pero ahora no es así.
- Termux El canal solía ser "inutilizable incluso si estaba autorizado", pero ahora puede devolver resultados.
- Los informes de fallos recibirán un botón y se enviarán a una dirección fija; si no se puede enviar, se enviará automáticamente por correo electrónico.
- Una vez completada la inspección estándar del código y solucionados los problemas existentes, la inspección realmente lo bloqueará.

---

# Muse v2.6.6 (English)

> Card interaction works in places it did not, the proxy no longer half-works, and some screens that used to be empty now have something behind them.

---

## Cards

Buttons inside cards respond now. Several paths were never connected, so controls were drawn but tapping them did nothing.

Two other things: cards no longer show a stray scrollbar, and a card appears while it is being generated instead of waiting for the whole block to finish.

A card can now tell which card it is, read back its bound data, and say where it came from when it sends something back.

**One change you may notice**: "prefer card output" in Settings is now off by default. While it is off, the model will not turn tables, comparisons, or option lists into cards; it writes normally. If you want cards, turn it on in Settings → Chat.

Cards are still being reworked. This is not their final form.

## Proxy

Previously, with a proxy configured, some requests used it and some slipped past; and a change only took effect after a restart.

Now every outbound request follows the proxy, a change applies to the next connection with no restart, and you can name addresses that skip it. "Test connection" on the provider page tells you whether the proxy works.

## Places that used to be empty

- **Experience library, AI understanding cards, group chat memory**: three tabs had implementations but no way in; you can reach them now
- **Quick note attachments**: the indicator never lit up because adding and removing were never connected
- **Desktop widget**: whichever assistant you picked made no difference and the default was always used; it now uses the one you picked
- **"Quick voice input" widget**: tapping it only created a session and never opened the microphone
- **Character relationships**: backups saved them but never restored them; that round trip works now
- **Scheduled task conditions**: conditions you set were not actually applied

## Tool calls

When a tool did not work, the model used to get an error it could not act on.

- It checks the channel before running a tool and says how to enable it when it is off
- Failures say whether it was the network or the arguments, and whether a retry can help
- Failures also carry "attempt N, N left, keep going or stop", so it neither hammers away nor gives up early
- The tool count in the prompt matches reality; it used to claim a number that did not exist
- A tool map is available, so it knows what exists right now

## Memory

You can search past messages across sessions, kept separate per assistant.

A new session used to receive a four-section "nothing yet" block. It no longer does.

When you tell it "don't do that again", that becomes a standing rule rather than applying once.

## Other

- Favorites support multi-select batch actions
- Proactive messages could previously send nothing at all
- Backup missed one data type; character relationships now restore
- Insufficient balance (402) is reported as a balance problem instead of being retried as a network error
- Batch translation honors the glossary and custom style, matching single translation
- Speech error messages are unified; one feature used to have four different wordings
- Group chat duplicated tool arguments, which made a character go silent
- With no channel configured, it no longer writes a log line every 5 seconds
- Session todos persist instead of disappearing when you switch sessions
- The model can read files you pick yourself
- Long pastes can become files; the gallery supports multi-select

## Waiting animation

The waiting indicator is a morphing ink shape (leaf → star → ring), the same everywhere it appears.

New sessions animate the first message.

## Stability

- Accessibility screenshots used to always fail; they work now
- Shizuku screenshots used to take the background service down; they do not anymore
- The Termux channel used to be authorized but unusable; it returns results now
- Crash reporting is a single button sending to a fixed address, falling back to email if it cannot get through
- Existing code-standard violations are cleared, so the checks actually stop new ones
