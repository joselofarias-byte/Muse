Muse v2.5.9 Versión de parche.

## Correcciones importantes

- **La copia de seguridad y la recuperación son más completas**: Se corrigió la omisión de la carpeta de conocimiento en la recuperación de la copia de seguridad de NDJSON y la reversión del punto de recuperación, lo que reduce la pérdida de datos de conocimiento después de la recuperación.
- **Las conversaciones remotas son más seguras**:A2A La solicitud remota se cambia a una ruta de diálogo de texto aislada y la sesión de primer plano ya no se cambia, se modifica el cuadro de entrada o se llama a la herramienta del dispositivo local; La cancelación de la tarea solo afecta su propia solicitud.
- **Los protocolos de tareas son más confiables**: Corregir consulta de tarea A2A/Cancele el análisis de la identificación de la tarea y limite la escala de tareas, sesiones y solicitudes para evitar que solicitudes anormales contaminen el estado de la tarea.
- **Tasker Desactivado de forma predeterminada**: Las transmisiones automatizadas externas no responden de forma predeterminada; Se agregan interruptores explícitos a la página de configuración y se conservan las protecciones de longitud, frecuencia y aislamiento de herramientas de alto riesgo.

## Mejoras en la experiencia

- Las operaciones de acceso directo a mensajes admiten la configuración según las preferencias, y las operaciones de eliminación se mueven al área de operaciones de acceso directo y se conserva la confirmación secundaria.
- Cuando hay una imagen de fondo, el texto conserva el color de fondo de las burbujas y mejora el contraste del texto principal, mejorando la visibilidad de lectura.
- Complete los recursos en chino, inglés, español, japonés, coreano, portugués brasileño y ruso para la copia de configuración recién agregada en esta ronda.

---

# Muse v2.5.9 (English)

Muse v2.5.9 is a patch release.

## Important Fixes

- **More complete backup recovery**: fixes knowledge folders being omitted from NDJSON backup restore and recovery-point rollback, reducing missing knowledge data after recovery.
- **Safer remote conversations**: A2A requests now use an isolated text-only path and no longer switch the foreground session, mutate the input box, or invoke local device tools; cancellation affects only the caller's own request.
- **More reliable task protocol**: fixes A2A task lookup/cancellation id handling and bounds task, session, and request growth so malformed requests cannot pollute task state.
- **Tasker opt-in by default**: external automation broadcasts are ignored until enabled in Settings; prompt length, frequency, and high-risk tool isolation protections remain in place.

## Experience Improvements

- Message quick actions can be configured; delete moved into the quick-action row while retaining confirmation.
- Long messages keep a readable bubble background over image backgrounds, with improved text contrast.
- Added Chinese, English, Spanish, Japanese, Korean, Brazilian Portuguese, and Russian resources for the new settings.
