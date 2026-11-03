# Enlace 7: Memoria y Base de Conocimientos (RAG) — Línea base de deduplicación

## Confirmado para ser reparado (no hay más informes)
- Memoria:FactDb Eliminación atómica (facts+FTS+links Misma transacción); guardar automáticamente las huellas digitales (sin escritura en caso de falla, ventana consistente con MAX_MESSAGE_CHARS, clave idempotente que incluye asistente/alcance/espacio, volver a ejecutar en caso de falla);FACTS Conciliación incremental y limpieza de mesas vacías;scoped La compilación de memoria no escribe archivos raíz compartidos.
- Puerta de recuperación de memoria:MemoryTicker forceCompileNow/daily pipeline/writeDailyState Fallo cerrado antes de escribir.
- RAG Seis rondas de reparaciones: aislamiento de documentos internos cerrado por error; la falla del índice de transmisión limpia el fragmento/FTS/HNSW y borra el estado del índice del documento; el primer lote de fallas de incrustación/dimensión conserva el índice anterior;contentHash Escritura completa de entradas de importación (cálculo incremental de transmisión); las palabras clave se agotan en fragmentos FTS BM25 + tabla faltante de recuperación automática + alcance de alcanceDocIds no está ampliado; inyección de presupuesto=Fragmento real de 200 caracteres.
- devdocs:BuiltInKnowledgeDocSeeder Unificar semilla + asegurar índice de vector incremental indexado (is_internal, degradación silenciosa);search_conversation SAFE Herramientas (sessionId desde el contexto del host falló cerrado); conversación de seguimiento de sesiónRecallEnabled(messages_fts/LIKE, el texto original se inyecta dentro del presupuesto).
- HNSW:embedding Retorno de cambio de dimensiones y huellas dactilares; Los protectores de apertura importados están unificados (a603c4f7).
- resumen diario/La programación de momentos y el mapeo de roles de memoria han regresado y se han completado.

## Se sabe que no está resuelto (se pueden agregar nuevas pruebas citando el número)
- La página principal de la base de conocimientos no coincide con el diámetro de importación del F-31 y no se ha realizado la integración en la importación en streaming (se sabe).
- DocumentParser Grandes limitaciones de protección de documentos estructurados (conocidas).
- P1 #20-31 Residuos medios relacionados con RAG (MMR Brecha sólo en parte del camino HNSW,metadataFilter El retroceso de la ruta del filtro se ha solucionado parcialmente——Verificar situación actual).
- MemoryDb No existe una interfaz de recuperación en la aplicación para archivos de versión alta (conocido).

## La dirección de exploración clave de este enlace.
1. **ensureIndexed Nuevos riesgos de la indexación incremental**: Inicia la simultaneidad del índice en segundo plano y la importación de usuarios al mismo tiempo;embedding La configuración cambió a mitad de camino (modelo/Manejo de vectores antiguos de documentos is_internal cuando se cambia de dimensión); si la degradación silenciosa puede engañar a los usuarios haciéndoles pensar que los devdocs se pueden buscar semánticamente (descubrimiento).
2. Rastreo de sesión + conversación_búsqueda + búsqueda_conocimiento Superposición presupuestaria de tres entradas de búsqueda: control de cantidad total de tres inyecciones en una ronda de generación.
3. FactDb v14→v15 migración; constelación de memoria/Compatibilidad de entidades v15 para canalización de reflexión.
4. RAG Semántica fragmentada: chino/Calidad de fragmentación del texto sin espacios en blanco (manejo de fragmentación de CJK);FTS tokenizer La situación actual de la segmentación de palabras chinas (BM25 Historia de la sucursal china"fracaso silencioso"——Verificar el estado actual, calcular la cuenta antigua como nueva evidencia si ha caducado, marcarla si ha sido revisada).
5. Uso y limpieza del disco de índice vectorial: eliminación de documentos/Reciclaje de archivos HNSW posteriores al repositorio; Diseño de almacenamiento HNSW para múltiples repositorios.
6. knowledge_search include_internal Lado expuesto: qué asistentes/Los canales pueden ver documentos internos (consistencia de configuración).
