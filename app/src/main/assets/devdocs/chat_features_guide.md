<!-- devdoc: Documentación de desarrollo interno.,No se muestra a los usuarios,LLM Consulta a través de Knowledge_search -->
# Guía completa de funciones de chat

> escena desencadenante: Usuario preguntó"Cómo operar en el chat""¿Qué puede hacer el mensaje?""como regenerarse""como cotizar""como bifurcar""¿Qué es el pensamiento profundo?""Cómo habilitar la búsqueda en Internet"tiempo,Consulte este documento y responda con sinceridad.
> Este documento está basado en el código fuente. ChatScreen.kt / ChatViewModel.kt / MessageBubble.kt / InputBar.kt / ChatStreamCoordinator.kt verdadera realización.

## 1. Descripción general

El chat es la principal interfaz interactiva de Muse:Salida de streaming Respuesta LLM,Admite entrada multimodal, como texto enriquecido de Markdown, operaciones a nivel de mensaje, cambio de modelo, pensamiento en profundidad, búsqueda en Internet, imágenes de voz, etc. Todos los mensajes se almacenan en una base de datos local.,Admite múltiples rondas de diálogo y bifurcación.(branch), interrumpir la recuperación.

## 2. Entrada

- Lista de conversaciones de la página de inicio → Haz clic en la conversación para entrar al chat
- Se puede crear una lista de conversaciones./fijar hacia arriba/Archivo/Sesión de búsqueda
- Barra superior de la página de chat: Título de la sesión + Alternar asistente + Más menú(Buscar/Borrar/Exportar, etc.)

## 3. Operación de burbuja de mensajes(Haga clic/Menú emergente de pulsación larga)

por mensaje(Usuario/AI)Todos admiten las siguientes operaciones.:

| Operación | Descripción |
|---|---|
| Copiar | Copia el texto completo del mensaje.(Contiene texto original de Markdown.) |
| Reenviar/regenerar | Regenerar mensajes de IA(preservar el contexto);Reenviar mensajes de usuario |
| Eliminar | Eliminar este mensaje(También limpie los índices FTS y genere puntos de control.,Reiniciar no revive) |
| Editar | Editar el contenido del mensaje del usuario y reenviarlo;AI El mensaje se puede editar como nuevo contexto. |
| Cotización | Responder citando este mensaje(Mostrar bloque de cotización en la parte superior de la burbuja) |
| confiar | delegate_agent:Delegar el procesamiento a otros asistentes |
| Traducción | Llame al traductor para traducir el mensaje. |
| leer en voz alta | TTS leer en voz alta(sistema/motor de nube) |
| Colección | Añadir a favoritos(Disponible en todas las sesiones) |
| bifurcación | Copiar el historial de este mensaje a una nueva conversación(rama) |
| Compartir | Exporte a Markdown y vaya al panel para compartir del sistema. |

## 4. Procesamiento de mensajes largos

- Colapsar automáticamente mensajes de IA súper largos,Haga clic"Expandir"Ver texto completo
- Representación en tiempo real durante la salida de streaming Markdown(resaltado de código/fórmula/mesa)
- Desplazarse automáticamente hasta el final entre mensajes;Pausa el seguimiento cuando el usuario desliza hacia arriba para ver el historial

## 5. Función de campo de entrada

### 5.1 Entrada de texto
- Múltiples líneas de entrada,Entra para enviar(Configurable)
- **Detección de pegado**: El contenido pegado reconoce automáticamente los sufijos comunes y los elimina;Convertir archivos que superen los 5000 caracteres
- Introducir borrador: Guarda automáticamente cada sesión,Volver a la recuperación

### 5.2 "+"menú(Funcionalidad mejorada)
| Función | Descripción |
|---|---|
| pensamiento profundo | Esta sesión está abierta. ReasoningLevel.HIGH(8000 tokens presupuesto de inferencia),No persistente |
| búsqueda en internet | LLM tiende a llamar después de habilitar web_search / web_fetch |
| Envío de fotos | Seleccionar fotos para enviar(compresión automática/OCR Reconocer texto) |
| Entrada de voz | Mantenga para hablar,ASR Convertir texto |
| mensaje rapido | Envía chips preestablecidos con un clic({{input}}/{{clipboard}}/{{date}} Variables renderizadas automáticamente) |
| cámara | tomar foto y enviar |

### 5.3 Cambio de modelo
- La cápsula del modelo de campo de entrada puede**Cambiar temporalmente el modelo de sesión actual**(La información histórica se mantiene sin cambios.,Nuevo mensaje con nuevo modelo.)
- Cuando el asistente configura un modelo dedicado,El cambio manual sobrescribirá el modelo exclusivo.(Consejos"Se han cubierto los modelos específicos de asistente.,Recuperación de sesión de cambio")

## 6. Salida en streaming y rondas múltiples

- Mostrar el estado de espera antes de que llegue el primer token
- Actualizaciones de transmisión UI(texto/pensar/Estado de llamada de herramienta)
- pensar en etiquetas(MOOD/Piensa en el contenido)Ampliable y plegable
- Las rondas de llamadas de herramientas se ejecutan automáticamente,Continuar generando resultados después del reabastecimiento,hasta la respuesta final
- Gestión automática de contextos de diálogo multiturno.;Compresión automática durante períodos de tiempo extremadamente largos.(Mantener los N elementos más recientes)

## 7. Interrupción y recuperación

- dejar de generar: Conserva parte del contenido generado,Los marcadores se pueden regenerar.
- Reinicio de la aplicación: Las interrupciones se pueden generar mediante"restaurar"continuar;Los mensajes completados no serán mal etiquetados.
- Generar punto de control: Guarda automáticamente el progreso cuando se interrumpe,Continuar ejecutándose automáticamente después de reiniciar

## 8. Preguntas frecuentes Q&A

1. **"Cómo regenerar respuestas de IA"**: Pulsación larga/Haga clic en Mensaje AI → Reenviar(regenerar).
2. **"Cómo borrar mensajes"**: Mensaje de pulsación larga → Borrar. Reiniciar después de la eliminación no lo revivirá.
3. **"Cómo citar una determinada frase en respuesta"**: Mensaje de pulsación larga → Cotización,Aparece un bloque de referencia en el campo de entrada.,Introduzca una respuesta.
4. **"que es el tenedor"**: Copie el historial anterior al mensaje actual en una nueva conversación,Continúe charlando en diferentes direcciones sin afectar la conversación original.
5. **"¿De qué sirve el pensamiento profundo?"**: Darle al modelo un mayor presupuesto de inferencia.,Adecuado para problemas complejos(código/análisis/planificación).
6. **"Cómo cambiar de modelo"**: Cambie directamente las cápsulas de modelo en el campo de entrada;o establecer→Modelo global por defecto para modelos y servicios.
7. **"Por qué la IA no busca en Internet"**: Comprobar campo de entrada+menú"búsqueda en internet"Ya sea para encender;Cuando está activada, la IA determinará de forma independiente cuándo buscar.
8. **"El orden de los mensajes está desordenado./Repetir"**: Por lo general, se debe a un etiquetado incorrecto cuando la versión anterior se interrumpe y se restaura.,Fijo;Actualice la versión y proporcione comentarios si lo encuentra.

## 9.LLM Puntos de llamada

- Respuestas en streaming renderizadas en tiempo real;Piensa en el contenido reasoning,Pon el texto content
- Ronda de llamada de herramienta: Después de llamar, espere a que se completen los resultados antes de continuar.,No inventes los resultados de tus propias herramientas
- Cuando el usuario desliza hacia arriba para ver el historial,Los mensajes nuevos no están obligados a desplazarse hasta el final(Manejado por la interfaz de usuario)
- Preservar el contenido de salida al generar interrupciones,Continuar después de la recuperación
