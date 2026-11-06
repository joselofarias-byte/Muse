# Enlace 3: Capa de datos/recuperación de copia de seguridad/migración — Línea base de deduplicación

## Confirmado para ser reparado (no hay más informes)
- Eliminar la atomicidad del hecho:facts+FTS+memory_links Misma transacción de FactDb, lápida/Derivado después de compilar la transacción proyectada.
- La memoria guarda automáticamente las huellas dactilares: falló/respuesta vacía/Si el análisis falla, no se escribirá la huella digital; la ventana de huellas digitales es consistente con MAX_MESSAGE_CHARS.
- conversation_events EventId estable idempotente (índice único); sala de verificación de recuperación por lotes IGNORAR valor de retorno, conflicto→La transacción de recuperación falla y se revierte.
- Diálogo de envío de invariantes de recuperación:upsertGenerationCheckpointAndDeleteOutbox La misma transacción de Habitación; verifique el punto de control residual mediante userMessageId antes de la recuperación;appendMessage Idempotente (sin incrementos repetidos de messageCount, sin actualización de vista previa/actualizadoAt).
- shadow Conversación del serializador de eventosShadowEventSequencer(session/turn/generation serie).
- Tareas programadas: historial de ejecución/next_run_at/last_run_at/retry_count misma transacción.
- Tareas programadas/chat grupal/mensajes no solicitados/Momentos/MemoryTicker/Los puntos de escritura de ProcessWriteGate para instantáneas de respaldo automático están completamente cubiertos (AUD-20261006-* serie).
- MemoryDb Bajar de versión superior:header/version comprobar → Copia de directorio temporal → SHA-256 → quick_check → Liberación atómica.
- P1 #7(Elimine por completo tres rutas para borrar la imagen),#8(restore/rollback Explícitamente claro generación_checkpoints),#10(MemoryDb degradar archivo),#12(Migrar el control de acceso a MemoryDb) Corregido.
- Copia de seguridad v4 almacenamiento de tipo de archivo juicio de copia de seguridad vacío y semántica de compatibilidad; los puntos de recuperación se transmiten al disco.
- Índice de vector incremental asegurarIndexed(is_internal documentación,contentHash/chunkCount sentencia).
- RAG Seis rondas de reparaciones de auditoría:fail-closed Aislamiento de documentos internos, limpieza de fallas del índice de transmisión+Estado borrado,contentHash Escritura de entrada completa,FTS Palabras clavechunks Prioridad+Autocuración), inyección de presupuesto=Fragmento real.

## Se sabe que no está resuelto (se pueden agregar nuevas pruebas citando el número)
- MemoryDb No hay navegación dentro de la aplicación para archivos de versión alta/Interfaz de recuperación (conocida).
- Los diámetros de importación de la página principal de la base de conocimientos y la página del F-31 son inconsistentes y no se ha realizado la integración en la importación en tiempo real (se sabe).
- PDF/DOCX/PPTX/XLSX/EPUB/OCR Todavía sujeto a DocumentParser 1 MB/página/ZIP entry Protección, la indexación de primera ronda de documentos estructurados de gran tamaño puede estar incompleta (conocido).
- P1 #20-31 Elementos no revisados ​​relacionados con la capa de datos (#20…#19 Consulte la lista global).
- HNSW La dimensión de la persistencia/Todavía existen problemas residuales con las huellas dactilares del modelo y la durabilidad (#11 Solución parcial: archivos temporales+rename Ya se conocen las dimensiones restantes).

## La dirección de exploración clave de este enlace.
1. **MuseDb v107→v110 cadena migratoria**: Corrección de la migración de nuevas entidades, coherencia del nuevo DAO/índice y declaración de migración,check_migration_coverage cubrir;Fresh install vs Diferencias en las rutas de actualización.
2. **A2A Menú desplegable de sesión**:A2A ¿En qué conversación cae el diálogo desencadenado? Sesión con enlace principal/Aislamiento y conflicto de sesiones de canal;Tasker La sesión descartada resultante pertenece a la base de datos.
3. Si la recopilación de datos exportados de diagnóstico incluirá fragmentos de la base de datos (como parámetros SQL en los registros de fallos).
4. Persistencia del gancho del complemento (hooks Análisis de declaraciones/Almacenamiento) y migración de datos de mercado de complementos/Versión compatible.
5. Tamaño de sesión grande (árbol de sesión largo/Límite de paginación de consultas por lotes en varios archivos adjuntos)——Se han realizado muchas reparaciones en los últimos dos meses."partial-page fusionar", encontrar**Otros puntos de localización en el mismo modo.**(por ejemplo, historial de artefactos/canales/exploración de memoria) si hay límites de página sin procesar.
6. Copia de seguridad automática VACUUM INTO enlace de instantánea: tres ventanas de coherencia de base de datos, revisión de puerta de escritura para ver si todos los puntos de escritura están cubiertos.
