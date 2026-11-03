# Enlace 2: Seguridad y Permisos — Línea base de deduplicación

## Solución confirmada/Decisión de diseño (no más presentación)
- webhook Cerrado por defecto; Falta el token de verificación de Feishu: falla cerrada;QQ Falta la firma y no se cierra; El webhook de cifrado Feishu indica claramente que no es compatible (no es una vulnerabilidad, sino una limitación conocida).
- TRUSTED modo diez ui_* La herramienta requiere aprobación; los volcados sin barreras filtran el texto del nodo de contraseña; las copias de seguridad cifradas en la nube no degradan silenciosamente el texto sin formato (las copias de seguridad de texto sin formato más antiguas se pueden importar si se conoce la compatibilidad).
- OAuth Cada registro de proceso está desensibilizado (solo se registran el estado HTTP y los campos de error desensibilizados).
- La verdadera fuente de riesgo en las herramientas de canal/lista blanca cerrada por error (P1 #17 ha sido revisado).
- Se lanzó la zona de pruebas del complemento (fetch/XHR/WebSocket La conexión de red directa, el permiso completo de las acciones del puente y el formato de verificación del nombre de la capacidad) son decisiones del producto v2.5.8.——Dejar ir en sí mismo no es un descubrimiento; la navegación por la página sigue prohibida,file:// Las lecturas de content:// todavía están aisladas. dejar ir**En implementación**ultra vires/Simplemente evítelo.
- manifest Una lista blanca explícita de campos de firma (los campos nuevos no participarán en las firmas de forma predeterminada) es una decisión de producto; pero la lista blanca**darse cuenta**Si se puede manipular, los resultados de la verificación de firma se calculan y descubren.
- La exportación de diagnóstico no registra el cuerpo del mensaje ni la clave API,sessionId Desensibilización al hachís——Está bien verificar si la implementación cumple con los estándares, pero los registros faltantes no cuentan.
- STRICT La penetración del modo ALWAYS_ALLOW es una compensación de diseño declarada (política de usuario explícita > modo sesión).
- Token de transferencia de carpeta de pantalla virtual,render_data rechazo de grandes datos/truncar,Root Lista de paquetes/Truncamiento de detalles de falla, truncamiento de cadena de herramientas de proveedor desconocido 30——Todos han sido reparados.

## La dirección de exploración clave de este enlace.
1. **v2.5.8 Nueva superficie de ataque**:A2A HTTP punto final (/.well-known/agent-card.json,/a2a JSON-RPC)——Certificación/¿Autenticación?SendMessage ¿Se pueden desencadenar llamadas de modelo pagas (abuso de costos)?GetTask/CancelTask ¿Puedo sobrepasar mi autoridad y leer las tareas de otras personas? Puertos con modo host (servidor web integrado)/Relación vinculante.
2. Tasker Entrada de retransmisiones: ¿Cualquier aplicación puede enviar retransmisiones?extra prompt longitud/¿Verificación de contenido? ¿Se puede utilizar para inyectar palabras de aviso para exceder la autoridad (induciendo al Agente a filtrar datos en notificaciones de resultados de tareas)?
3. Plug-in Hook onPromptFinalize: ¿Qué hay en el objeto de evento? ¿Pueden los complementos maliciosos utilizar esto para robar información del usuario?/Memoria saliente (se ha liberado la red de complementos)——hook si convertirse"Canal de filtración legal")? Después de que se publiquen todas las acciones de puente, el complemento puede acceder a qué acciones de puente (leer el portapapeles/Notificación/¿Archivo? ).
4. Después de que la verificación de la firma del mercado de complementos esté incluida en la lista blanca: ¿se ha introducido la lógica de compatibilidad de los paquetes existentes?"Se inyectan nuevos campos y se confía en ellos."de la boca.
5. MCP stdio server configurar (node/npm/npx restricción) todavía se mantiene;mcp_mgmt_configure ¿Se puede abusar indirectamente de él mediante bucles de herramientas?
6. Verifique el contenido del paquete de exportación de diagnóstico: ¿Qué archivos están realmente empaquetados? Confirmar sin texto/llave;sessionId Si el hachís está salado.
7. update Enlace:UpdateNotifier/latest.json Analice la tolerancia a fallas para el último.json malicioso (el sitio web oficial ha sido manipulado).
