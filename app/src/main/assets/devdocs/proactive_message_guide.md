<!-- devdoc: Documentación de desarrollo interno.,No se muestra a los usuarios,LLM Consulta a través de Knowledge_search -->
# La guía completa de mensajes proactivos

> escena desencadenante: Usuario preguntó"¿Cómo consigo que la IA me encuentre de forma proactiva?""¿Qué es la mensajería proactiva?""¿Por qué la IA envió mensajes de repente?""Cómo desactivar los mensajes activos"tiempo,Consulte este documento y responda con sinceridad.
> Este documento se basa en la implementación real del código fuente ProactiveMessageRunner.kt / AppSettingsStore.kt.

## 1. Descripción general

Asistente de mensajería activa**Envíe mensajes activamente a los usuarios a intervalos fijos y notificaciones emergentes**(Mensaje como WeChat),para interacción con compañeros(Saludos/recordatorio/Compartir). Enviado por ProactiveMessageRunner.

## 2. Configurar la entrada

ajustes → charlar → mensajes no solicitados
- **interruptor principal**: encender/cerrar
- **intervalo**: 1 / 2 / 4 / 8 / 12 / 24 horas

## 3. Mecanismo de programación

- Intervalo base seleccionado por el usuario(1-24 horas)
- **intervalo real = intervalo base ± desplazamiento aleatorio**(Mínimo 1 hora,evitar con demasiada frecuencia)
- El desplazamiento aleatorio hace que la sincronización de los mensajes sea más natural,No mecanico
- evento desencadenante: app_resume(De vuelta a la recepción)¿Es oportuna la inspección oportuna?;El fondo está programado por un temporizador.
- **Restablecer entre días**: Se ha borrado el recuento diario de mensajes activos,Un nuevo día y un nuevo día

## 4. Estilo de notificación

- Vaya a MuseNotificationManager para que aparezcan notificaciones del sistema
- La notificación contiene contenido del mensaje del asistente.
- Haga clic en la notificación para ingresar a la sesión correspondiente

## 5. Contenido del mensaje activo

- Generado por el asistente en función del contexto actual.(Saludos/Situación actual/recordatorio)
- Inspección desencadenada por evento(app_resume)puede combinarse con la actividad del usuario
- El estilo del contenido es coherente con la personalidad del asistente.

## 6. Preguntas frecuentes Q&A

1. **"¿Cómo consigo que la IA tome la iniciativa de encontrarme todos los días?"**: ajustes → charlar → mensajes no solicitados → Activar + seleccionar intervalo(Como 24 horas).
2. **"Por qué la IA no se envía a tiempo"**: ①El intervalo es"aprox."No"Preciso"(Hay un desplazamiento aleatorio);②Aplicación eliminada/El límite de fondo puede perder el envío;③Calculado nuevamente después del reinicio entre días.
3. **"muy a menudo/demasiado ruidoso"**: aumentar el intervalo(8/12/24 horas)O ciérrelo directamente.
4. **"¿Puedes especificar una hora para enviarlo?"**: Los mensajes activos se basan en intervalos.,No es un sistema de punto fijo;Utilice tareas programadas para requisitos de punto fijo(cron).
5. **"La diferencia entre mensajes activos y tareas programadas"**: mensajes no solicitados=intervalo fijo+Mensajes complementarios compensados aleatoriamente;tareas programadas=cron Ejecución de tareas desencadenada con precisión.
6. **"¿El teléfono celular consumirá batería?"**: La programación se basa en eventos+Sincronización ligera,El consumo de energía es insignificante.

## 7.LLM Puntos de llamada

- Los mensajes activos son programados por el sistema.(No fue iniciado por el propio LLM.);LLM es responsable de generar contenido de mensajería de alta calidad
- Al recibir una solicitud de mensaje no solicitado,combinado con la hora actual/Estado de usuario/La personalidad genera mensajes naturales
- No digas lo mismo cada vez(Los saludos necesitan cambiar.)
- Los usuarios pueden tener una conversación normal al responder a un mensaje no solicitado
