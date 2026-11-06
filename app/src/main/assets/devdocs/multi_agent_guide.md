<!-- devdoc: Documentación de desarrollo interno.,No se muestra a los usuarios,LLM Consulta a través de Knowledge_search -->
# Una guía completa para la colaboración entre múltiples agentes

> escena desencadenante: Usuario preguntó"Cómo utilizar varios asistentes juntos""¿Puedo delegar tareas a otros asistentes?""¿Qué es el chat grupal?""Cómo conseguir que los asistentes hablen"tiempo,Consulte este documento y responda con sinceridad.
> Este documento está basado en el código fuente. AgentRouter.kt / AgentToolsRegistrar.kt / GroupChatScheduler.kt / GroupChatScreen.kt verdadera realización.

## 1. Descripción general

Muse Admite múltiples asistentes(Agent)colaboración:
1. **delegate_agent confiar**: El asistente actual delega la tarea a otro asistente para que corra durante una ronda. LLM,Continuar con los resultados
2. **chat grupal(GroupChat)**: Varios asistentes se turnan para hablar y discutir en el canal.,Los usuarios pueden unirse
3. **DM**: Chat privado entre dos asistentes.

## 2.delegate_agent mecanismo de delegación

### método de llamada(LLM perspectiva)
```
delegate_agent(task = "Ayúdame a calcular el presupuesto.", agent = "asistente financiero")
```
- **task**: Descripción de la tarea delegada(contexto completo)
- **agent**: asistente de gol id(Seleccionar de la lista de asistentes solicitada por el sistema)

### proceso
1. Llamada de ayuda actual delegate_agent,ayudante objetivo
2. Para asistente de destino**propio** systemPrompt/modelo/memoria/Las herramientas funcionan durante una ronda.
3. El resultado se devuelve al asistente actual.,Responder al usuario después de integrar el asistente actual

### Puntos principales
- Confiar a asistentes profesionales(como se traduce/código/redacción),Dar rienda suelta a las fortalezas de su propia personalidad.
- task Escribe el contexto claramente.,El asistente objetivo no puede ver la conversación actual
- Una vez que se devuelvan los resultados, se integrarán verazmente.,No altere las conclusiones del subasistente
- Sub-asistente subsesión de aislamientos,No inyecta el diálogo principal en la memoria a largo plazo.(evitar la recursividad)

## 3. Chat grupal(GroupChat)

### 3.1 Tipo
| Tipo | Descripción |
|---|---|
| DM | Dos asistentes charlando en privado. |
| Group | Grupo multijugador(≥3 gente) |

### 3.2 crear
1. Chat grupal en casa Tab → Nuevo
2. Asistente de suscripción(2+ un)
3. Seleccionar tipo(DM/Group)
4. Después de la creación, el robot asistente comienza a hablar automáticamente.

### 3.3 programación de discursos(GroupChatScheduler)
- Los asistentes se turnan para hablar.,El planificador determina el orden de los discursos.(rotación/respuesta)
- Los usuarios pueden unirse al canal para hablar.(@Se puede dirigir a un asistente.)
- invokeAgent / vote / summary Esperando el modo de programación

### 3.4 Conjunto de herramientas de chat grupal de tres piezas(LLM perspectiva)
| Herramientas | Propósito |
|---|---|
| channel_reply | hablar(Asistente actual de vinculación de identidad,Los parámetros incluyen contenido de voz.) |
| channel_pass | Saltate esta ronda(Cuando no tienes nada que decir) |
| channel_read_context | Leer más contexto histórico |

Nota: channel_* Herramienta solo disponible en contexto de chat grupal;No se puede llamar en la sesión principal(Prevenir la suplantación de identidad).

## Cuatro.Agent modelo exclusivo(resolveAssistantModel)

- Cada asistente puede configurar su propio modelo(modelId+providerId)
- Discurso de chat grupal/confiar/al votar,El asistente de destino utiliza su propio modelo configurado.
- Si no se configura, se utilizará el modelo predeterminado global.

## 5.@mention y orientación

- En el chat grupal @algún asistente → El asistente fue nombrado.,respuesta prioritaria
- Los usuarios pueden hablar con varios asistentes.

## 6. Preguntas frecuentes Q&A

1. **"Cómo crear múltiples asistentes"**: ajustes → asistente → Nuevo,Configurar personalidad/modelo/Lista blanca.
2. **"¿Cómo le pido al asistente que me ayude a buscar información y luego responder la pregunta?"**: Confíelo a un asistente que sepa buscar,O utilizar el asistente actual directamente web_search(No se requiere delegación).
3. **"El asistente de chat grupal no habla"**: Verifique si el asistente está habilitado y el modelo está disponible;channel_pass es normal(Salta cuando no tengas nada que decir).
4. **"¿Puede el subasistente ver mi historial de chat?"**: No puedo ver el registro completo,Sólo recibió la comisión. task;La memoria a largo plazo también está segregada.
5. **"Cómo guardar los resultados del chat grupal"**: Los mensajes de chat grupal se almacenan en la base de datos.,Se puede ver atrás;Un asistente puede resumir las conclusiones importantes.
6. **"¿Se puede delegar al asistente principal a los chats grupales?"**: no directamente;La delegación es una tarea de punto a punto con un solo asistente.

## 7.LLM Puntos de llamada

- Se utiliza cuando se requiere una división profesional del trabajo. delegate_agent(No vale la pena delegar todas las tareas,Responde preguntas sencillas tú mismo)
- Debe usarse en el chat grupal. channel_* discurso de tres piezas,No generar texto perdido
- Anota los antecedentes de la tarea encomendada./objetivo/formato esperado,Reducir ida y vuelta
- Los discursos de chat grupal son breves y con puntos de vista.,No deslices la pantalla
- Responde primero cuando el usuario @ tú
