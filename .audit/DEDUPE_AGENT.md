# Enlace 6:Agent Control de dispositivos/Automatización/Tiempo de ejecución — Línea base de deduplicación

## Solución confirmada/Decisión de diseño (no más presentación)
- Degradación jerárquica de AutomationManager de canal de tres capas;execTiered Es el único punto de entrada para los comandos del dispositivo (Shizuku Priorizar raíz);Shizuku Se ha solucionado la trampa de raíz después de un fallo en tiempo de ejecución.
- DeviceCapabilityProfile Fuente única de verdad + archivo de inyección DeviceCapabilityHook+procedimientos (priority 80); no se inyectan subagentes; no se lanzan excepciones cuando la detección falla y se degrada.
- Plano de control de aplicaciones AppControlCore: lista blanca de nombres de paquetes para evitar la inyección, prohibición de la aplicación del sistema de borrar datos/Desinstalación (se permite la detención forzada), se prohíbe la destrucción de Muse y la coincidencia de nombres de cuatro niveles; no se requiere autorización en la capa de aplicación.
- Pantalla virtual: autorreparación después del reinicio del servicio (compatible con fallas en el tamaño de grabación de la pantalla y reconstrucción de una nueva ID);virtual_close Éxito idempotente para la exhibición desaparecida; hasta 3 pantallas simultáneas; lectura de pantalla semántica uiautomator dump --display-id no volverá a la pantalla principal si falla.
- virtual_screen_input / virtual_tap_text:DeviceCommandPolicy Verificación de estructura, hasta 5 búsquedas de desplazamiento, verificación posterior al clic, falla al configurar efectos secundarios desconocidos para bloquear la reproducción y la lectura de pantalla no es persistente.
- Capturas de pantalla accesibles:Android 14+ takeScreenshot Acceda a AccessibilityExecutor.captura de pantalla;13 y por debajo del calibre degradado.
- screen_tap_text resultado de la herramienta estructurada (attempts/matched/verified/matchedLabel);tap_text/tap_id coincidencia semántica+Calificación+coordenadas+La repetición está prohibida si se desconoce el resultado.
- automation_workflow:node_script pasos (HIGH Aprobación de riesgo), resultado Punto de interrupción del cifrado del almacén de claves, mismo anexo run_id, bloqueo de efectos secundarios desconocidos.
- Tiempo de ejecución de nodo incorporado:jniLibs distribución + ejecución nativaLibraryDir (alrededor W^X);RUNTIME_DATA_VERSION Controlar la descompresión intensa;exec espaciador LD_PRELOAD;npm assets descomprimir;strip romperá el diseño del segmento patchelf (keepDebugSymbols enumerados).
- MCP stdio:el comando solo permite node/npm/npx con rutas absolutas dentro del tiempo de ejecución.
- platform_selfcheck Autoprueba de nivel de canal (HEAD Última confirmación, ESTÁNDAR seguro de solo lectura).

## Conocido sin resolver/Límite (se pueden citar nuevas pruebas)
- Cuando no hay Shizuku ni Root, la API del sistema exclusivo Shell/Root no se puede ajustar (límite de capacidad, este es el diseño).
- auto La estrategia es"Si se le da prioridad al nombre del paquete de destino, la pantalla virtual dedicada se colocará en la recepción si falla."Estrategia determinista simple para , no por tipo de tarea/Segmentación de privacidad (límites de productos conocidos).
- workflow Las llamadas sucesivas al modelo son indirectas y no admiten ramificaciones condicionales arbitrarias en un único script (diseño conocido).
- Node El script es el UID de la aplicación ejecutor totalmente capaz (HIGH Protección de aprobación de riesgos, por diseño); no sujeto a DeviceCommandPolicy (por diseño, anulación de acción privada).
- Falta la aceptación real de la matriz de la máquina (conocida).

## La dirección de exploración clave de este enlace.
1. **A2A/Tasker La intersección de nuevos portales y herramientas de equipamiento.**:A2A ¿La convocatoria también pasa por la aprobación de la herramienta? Control remoto del dispositivo de disparo (tap/¿Está completo el enlace de aprobación para borrar datos?——¿Cómo manejar las tarjetas de aprobación cuando no hay nadie frente a la pantalla de forma remota?
2. automation_workflow node_script Validación de entrada de paso: longitud del contenido del script/Límites de recursos/Se acabó el tiempo; si la acumulación de resultados intermedios para pasos de nodos consecutivos es ilimitada.
3. virtual display lease Gestión: Agente concurrente + herramienta manual virtual_screen + escenarios concurrentes activados por A2A,display contar/filtración.
4. MuseRuntime Inyección de variables de entorno (HOME/TMPDIR/PATH/LD_LIBRARY_PATH/LD_PRELOAD/MUSE_*) Condición de carrera durante la ejecución simultánea de múltiples procesos/fuga; fuganpm Desempaquete la máquina de estados después de la interrupción.
5. DeviceCapabilityProfile Puntualidad de la detección: después de que el usuario revoca el permiso durante la operación (como desactivar la accesibilidad), si el modelo aún actúa de acuerdo con el archivo antiguo dentro de la ventana de retraso de actualización del archivo.
6. exec Junta/patchelf Integridad del producto: md5 posterior a la compilación verifica si existen barreras de seguridad de CI o solo acuerdos verbales.
