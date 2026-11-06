# Enlace 9: Rendimiento y Estabilidad — Línea base de deduplicación

## Solución confirmada/Conocido (no hay más informes)
- ANR Se ha solucionado la falsa alarma de vigilancia (AnrDetector ticket Contar + congelar sobreimpulso + controlador de referencia fuerte + tiempo real transcurrido); utilice la validación cruzada de seguimientos del sistema para una verdadera solución de problemas de ANR.
- AssistantAvatar OOM(Decodificado según el tamaño de la pantalla) Fijo; palabras de indicaciones de caracteres extra largas/Esta solución no cubre otras posibilidades de OOM, como el almacenamiento en caché de imágenes de mensajes.——Este"no cubierto"Se puede explorar pero no se considera un descubrimiento nuevo. Encontrar un nuevo camino específico se considera nueva evidencia.
- Las operaciones de CPU/disco de indexación de documentos se sacaron del hilo principal;44MB zip Importar reparación de la causa raíz del fallo (HNSW Estrangulamiento de la placa de caída).
- Guardia de vencimiento del cambio de sesión (88405e1c); Se omite la rueda de precalentamiento de compresión.
- Compose: Lista larga de recordatorios de costos de reestructuración en PROJECT_GUIDE §5;MuseDialog Se conoce la trampa de desplazamiento del área de contenido.
- SQLiteConnection.close warning(Observado ocasionalmente durante pruebas a gran escala, no se ha demostrado que esté relacionado con cambios)——Observaciones conocidas.
- DataStore/Robolectric flake:BubbleSkinPluginSourceTest AccessDeniedException(Conocido por estar curado);TransformerPipelineTest Se eliminó RobolectricTestRunner (la causa raíz del problema no está determinada y requiere observación a largo plazo).

## La dirección de exploración clave de este enlace.
1. **Ruta de inicio**:MuseApp.onCreate Estado actual (v2.5.x Nueva sembradora/ensureIndexed/A2A/diagnostica si la inicialización ralentiza el arranque en frío);Koin Tamaño del gráfico del módulo; cantidad de trabajo antes del primer fotograma.
2. **ChatViewModel Impacto en el rendimiento después de la división**:S1-S5 Si la extracción introduce copias de estado adicionales/Colección intermedia; Distribución en la ruta caliente del ciclo de la herramienta.
3. Generar rutas activas de enlaces:GenerationTrace enterrar el punto,shadow Se superpone la sobrecarga generada por eventos y consultas de compresión de marcas de agua en cada ronda.
4. Memoria de sesión larga: árbol de mensajes+tarjeta de tarea+tarjeta de producto+Almacenamiento completo de imágenes; Reciclaje de memoria fuera de los límites de carga de paginación.
5. Riesgo del hilo principal: nuevo código (A2A handler,Tasker receiver, embalaje de exportación de diagnóstico) límites de hilo——BroadcastReceiver.onReceive hilo principal,ContentResolver/Ubicación del archivo IO.
6. Estabilidad del proceso: Singleton global (AutomationManager/ToolRegistry/MuseRuntime) propagación de fallos de inicialización; el estado de cada administrador se restaura cuando se finaliza el proceso.
7. WorkManager/Doze: Tareas programadas, mensajes activos y resumen diario de comportamientos en Doze (nivel de código:setExactAndAllowWhileIdle etc. Corrección del uso de API).
