# Enlace 4: Fiabilidad del canal y del backend — Línea base de deduplicación

## Solución confirmada/Decisión de diseño (no más presentación)
- ChannelInbox Entrega confiable:PENDING Haz el pedido primero,PROCESSING Recuperación, rechazo completo de cola,BLOCKED(CHECKPOINT_UNREADABLE) Vuelva a intentarlo sin bucle.
- Telegram La compensación sólo avanza después de todo el lote de entradas entrantes;WeClaw Cursor/context token SecureKeyStore Persistencia cifrada, la falla de escritura no avanzará;QQ Vincula el sourceEventId original.
- Puerta de recuperación del canal:ChannelInbox/ChannelConversationStore cerrado por error durante la restauración de ProcessWriteGate.webhook El retorno de excepción 503 hace que la plataforma vuelva a intentarlo.
- chat grupal/tareas programadas/mensajes no solicitados/Las compuertas de la escritura en Moments están todas cerradas (AUD-20261006-* serie).
- Cifrado del punto de control de la herramienta Channel Agent, efectos secundarios inciertos, bloqueo de repetición;at-least-once es el límite de diseño declarado.
- Medios del canal: adjunte la bandeja de entrada + mediaPath de la colección histórica antes de limpiar para proteger los archivos a los que se hace referencia.
- Descarga de CDN multimedia de WeChat+AES Descifrado, servidor de voz ASR; grupo de mensajes grupales: enrutamiento de prefijo.

## Se sabe que no está resuelto (se pueden agregar nuevas pruebas citando el número)
- P1 #13-16/#18-19(Elementos del historial de ubicación y ciclo de vida del canal)——Pero es posible que gran parte ya haya llegado a ChannelInbox,**Verificar antes de citar**; Anotación implementada"Bucle cerrado".
- El canal ON_STOP detiene el receptor, jitter de retroceso exponencial——Verifique si ha aterrizado.
- Matriz de plataforma real (QQ/Feishu/Telegram/DingTalk/WeChat iLink) Sin dispositivo/Aceptación de cuenta——Conocido, no descubierto.

## La dirección de exploración clave de este enlace.
1. **Estado actual de la gestión del ciclo de vida de los receptores de cinco canales.**: Cambiar entre delante y detrás/El receptor arranca y se detiene durante el proceso de reciclaje; si existe un límite superior y fluctuación para los reintentos de retroceso; y procesamiento de trabajos pendientes después de la desconexión y recuperación de la red.
2. ChannelInbox dispatcher: ¿Serie de un solo subproceso o concurrente? ¿Se bloqueará un canal (como la desconexión prolongada de la conexión de Feishu) y privará a otros canales?
3. A2A/Tasker ¿El canal entrante comparte ChannelConversationStore con el canal entrante? Bloqueos de escritura simultáneos para la misma sesión/Idempotente.
4. webhook Tiempo de espera HTTP del receptor/Fecha límite de cuerpo lento (he estudiado la fecha límite de cuerpo lento) body deadline——Verificar cobertura de todos los receptores de la plataforma).
5. Devolución de notificación (Tasker Notificación de resultados, notificación de respuesta del canal) canal de notificación/Permisos/No perturbar el procesamiento.
6. Ciclo de vida de los archivos multimedia: limpieza de archivos de descarga temporal, permisos de ubicación de archivos descifrados.
