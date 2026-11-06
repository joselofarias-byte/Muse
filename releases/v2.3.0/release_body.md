# Muse v2.3.0

a"Larga experiencia en tareas"La versión principal reparada: las tareas de varios pasos ya no se interrumpen a mitad de camino. Después de largas conversaciones y una gran cantidad de operaciones con herramientas, el asistente aún recuerda de qué habló; y se ha solucionado un problema grave en el que se podía borrar la memoria. Se recomienda a todos los usuarios actualizar.

## Correcciones importantes

- **Los recuerdos ya no se pierden.**: Se solucionó el problema de que la memoria factual se pudiera borrar después de reiniciar; Si hay datos borrados en la copia de seguridad del dispositivo, se recuperarán automáticamente después de la actualización. La memoria ya no se perderá al iniciar en el futuro.
- **No más tareas largas"amnesia"**: Después de ejecutar una gran cantidad de pasos de herramienta seguidos, el asistente aún recuerda la conversación anterior y puede continuar con el contexto anterior.
- **A veces hay que esperar mucho para reparar**: Agregue protección de tiempo de espera para el procesamiento en segundo plano, como la compresión histórica, para eliminar algunos casos."La noticia se ha retrasado."larga espera.

## nuevas capacidades

- **Liberación de giro de herramienta**: No hay límite de rondas de forma predeterminada (también se puede configurar en → (limitado en el chat), las tareas complejas se pueden completar a la vez; La protección de bucle anormal aún está vigente.
- **Permisos de herramientas transparentes**: Las herramientas que requieren Shizuku/Root, accesibilidad o Termux mostrarán el estado listo y darán un mensaje claro de inmediato si no están autorizadas, en lugar de fallar inexplicablemente a mitad de la ejecución.
- **La habilidad de palabra rápida admite la entrada**: La habilidad de palabra rápida personalizada puede recibir texto de entrada e instrucciones sustitutas.
- **La búsqueda en la base de conocimientos es más controlable**: El umbral de resultados de búsqueda realmente entra en vigor y se proporciona información de diagnóstico sobre los errores.

## Mejoras en la experiencia

- La tarjeta del canal de mensajes se ha reorganizado: los nombres largos y los números de cuenta largos ya no están agrupados.
- Reparación de alarma/cuenta regresiva: complete la declaración de permiso del sistema, que el sistema habría interceptado antes; dar retroalimentación clara cuando falla.
- Las entradas de memoria largas admiten hacer clic para expandir y ver el texto completo.

---

# Muse v2.3.0 (English)

A reliability-focused release centered on long tasks: multi-step tasks are no longer interrupted midway, and after long conversations or heavy tool use the assistant still remembers what you talked about — plus a serious fix for memory being wiped on restart. Upgrading is strongly recommended.

## Important Fixes

- **Memory is no longer lost**: fixed an issue where fact memories could be wiped after a restart; data previously cleaned up will be automatically recovered on upgrade if it exists in your device backups. From now on, memories will no longer disappear across restarts.
- **No more "amnesia" on long tasks**: after running many consecutive tool steps, the assistant still remembers the earlier conversation and can continue from where it left off.
- **Occasional long waits fixed**: added a timeout safeguard to background processing such as history compression, eliminating rare cases of "messages taking a very long time to appear".

## New Capabilities

- **Tool rounds uncapped**: rounds are unlimited by default (you can still cap them under Settings → Chat), so complex tasks can complete in one go; loop protections remain active.
- **Transparent tool permissions**: tools requiring Shizuku / Root, Accessibility, or Termux now show their readiness status, and fail fast with a clear message when unauthorized — instead of mysteriously failing halfway.
- **Prompt skills accept input**: custom prompt skills can now receive input text and substitute it into their instructions.
- **More controllable knowledge search**: the search result threshold now truly takes effect, with diagnostics included when nothing matches.

## Experience Improvements

- **Channel cards redesigned**: long names and long account IDs no longer get squeezed into a mess.
- **Alarm / timer fixes**: added the missing system permission declaration (previously blocked by the system); failures now give clear feedback.
- **Long memory entries can be tapped to expand** and read in full.
