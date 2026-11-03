# Enlace 1:AI Provider / Transmisión/bucle de herramientas — Línea base de deduplicación

## Se ha confirmado que este enlace está reparado (no se permiten más informes)
- OpenAI stream-guard Si la reversión falla, se emite un Error en lugar de Solo hecho;FirstEventWatchdog AtomicBoolean + flujo vacío/Una condición de carrera de tiempo de espera solo activa el retroceso una vez;Done/pendingBuilder Finalizar el estado de carrera (los cambios de contenido del estado final se retrasan hasta que estén pendientes de descarga);callback generation guard(OpenAI/Anthropic/Responses);`OpenAIProviderAbortTest`(503 retry backoff Después de que haya pasado el aborto (solo se envía 1 solicitud).
- Nivel de pensamiento del bucle de herramientas: todas las rondas válidas conservan la configuración del usuario (ToolRoundPresentationPolicy); el sistema indica que el lenguaje de pensamiento sigue el lenguaje de la interfaz (thinkingInstruction); Los proveedores que no respaldan el razonamiento no falsifican contenido.
- Reanudar la carga después de la desconexión:resumeOverlapToDrop() Sólo recortar puede demostrar una reproducción completa (ChatResumeLcpTest);"La primera palabra fue tragada."No reproducido, se agregó diagnóstico de depuración, esperando registros reales——"No reapareció"No es un descubrimiento, pero se pueden encontrar nuevas rutas de código faltantes.
- Herramienta Catálogo de capacidadesUsar valor predeterminado=No declarado reservado por soporte;catalog image/reasoning Tres estados; tapa de salida del directorio indicador SOSPECHOSO.
- debugInfo en completed/failed/cancelled/context-overflow Exportación unificada.

## Conocido y no resuelto (se pueden agregar nuevas evidencias citando el número, pero no se considera un descubrimiento nuevo)
- Provider Lista de bucles abiertos:Gemini usage-only terminal,OpenAI Responses final text/retry juicio,Anthropic/Gemini stream abort listener(**Nota:Gemini/Anthropic El detector de abortos figuraba como un espacio vacío. Si encuentra que se ha implementado, márquelo."Bucle cerrado"**), la red real debe verificar la antigua carrera de reintento de EventSource.
- P1 #1/#3-6(presupuesto, medición de compresión,SSE,prompt cache, la verdadera fuente de habilidad),#20-31 Artículos relacionados en Proveedor.

## La dirección de exploración clave de este enlace.
1. 2026-10 Nuevo código después de dividir y refactorizar:ChatViewModel Coordinador/transformador extraído (S1 cuerpo del bucle de herramienta,S2 disposición de compresión,S4 aprobación de herramientas,S5 Para ser llamado por la herramienta de recuperación)——Ya sea para introducir pérdida de estado al extraer/doble gatillo/Competencia.
2. A2A entrada (web/a2a/) y entrada Tasker (intent/) Al multiplexar para generar enlaces: concurrencia, cancelación de propagación,generation guard Ya sea para sobrescribir nuevas entradas.
3. El complemento onPromptFinalize inyecta el enlace generado:fail-open Si la declaración es verdadera en todas las rutas de excepción;hook Si el incidente obtenido filtró algún contenido sensible más allá del texto del mensaje.
4. Diagnóstico del nuevo enfoque de GenerationTrace: si se debe introducir la asignación en la ruta activa del enlace de generación/cerrar con llave; bloqueartrace Si la estructura de datos crece ilimitadamente en sesiones extremadamente largas.
5. subagente timeout_ms 30s~900s Análisis de parámetros y semántica de continuación; ruta de generación de información de tiempo de espera y progreso.
