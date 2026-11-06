
## v2.5.8 - Acceso a protocolo externo y capacidades de resolución de problemas

Esta versión complementa las capacidades externas en tres aspectos: acceso al protocolo de red, enlace automatizado y puntos de extensión de complementos.,y poner"Los problemas se pueden localizar"Realizar funciones del producto.

- **A2A 1.0 Acuerdo**:Punto final de descubrimiento de tarjeta de agente agregado(`/.well-known/agent-card.json`)con punto final JSON-RPC(`/a2a`),apoyo SendMessage / GetTask / CancelTask.A2A Llame y reutilice el mismo enlace generado para chatear en dispositivos reales(Análisis de modelos/Herramientas/Aprobación/memoria),No existe un segundo conjunto de lógica generativa.
- **Tasker vinculación**:Se agregó una nueva entrada de transmisión. `io.zer0.muse.action.TASKER_TRIGGER`(extra `prompt`),Una conversación puede ser iniciada por plataformas de automatización como Tasker/MacroDroid,Los resultados se registran en la base de datos y se envían notificaciones. Ejecución de una sola ronda en segundo plano.,Las herramientas no están autorizadas.
- **Sistema de gancho enchufable**:Los complementos se pueden declarar en el manifiesto. `hooks`,Y exportarlo en main.js `onPromptFinalize(event)` Vuelva a escribir el historial antes de que finalice el mensaje. Conservar el historial original cuando el fracaso o la devolución sea ilegal.(fail-open),No bloquea la generación.
- **Lanzamiento de la zona de pruebas del complemento**:dejar ir fetch / XHR / WebSocket acceso a la red,La acción del puente está completamente liberada.,Sólo se verifica el formato del nombre de la capacidad. Mantenga dos tipos de resultados finales:La navegación de la página todavía está deshabilitada,file:// Las lecturas de content:// todavía están aisladas.
- **Exportación de registros de diagnóstico(Solución de problemas)**:Se agregó un registrador de seguimiento de enlaces de nueva generación.,Registre el resultado/consumo de tiempo/retraso del primer token/estadísticas de personajes/clasificación de finalización de flujo/razón_final/marca de activación de compresión de cada ronda;ajustes → Acerca de →La "exportación de información de diagnóstico" se puede empaquetar y compartir. linea roja:No registre el cuerpo del mensaje y API key,sessionId Desensibilización al hachís.
- **Tiempo de espera del subagente**:Tiempo de espera predeterminado 120s → 240s,Nuevo `timeout_ms` parámetros(30s~900s);Error de tiempo de espera con información de progreso(Rondas ejecutadas, número de llamadas a herramientas y si hay un resumen parcial),Facilita la continuación en lugar de empezar de nuevo.
- **firma compatible**:manifest Los campos que participan en la firma se cambian a una lista blanca explícita.,Los campos nuevos no participarán en la firma de forma predeterminada,Los paquetes de acciones no se ven afectados.

## v2.5.7 - Contexto y estabilidad generativa

- El umbral de compresión converge a una única fuente verdadera,Eliminar la bifurcación del comportamiento causada por múltiples decisiones separadas.
- system prompt aumentar la puerta total,Las sesiones largas ya no desplazan los presupuestos debido a palabras infladas
- Limitación de incorporación de memoria relacionada,Reduzca la sobrecarga innecesaria de sesiones largas
- Se encontraron múltiples daños en la codificación de anotaciones y limpieza de anotaciones caducadas durante la revisión del enlace completo

## v2.5.6 - versión de mantenimiento

- Mantenimiento de estabilidad y cierre de problemas existentes.(Consulte las notas de la versión correspondientes para obtener más detalles.)

## v1.0.89 - Ejecución de herramientas y revisiones de seguridad.

- Las herramientas refuerzan la seguridad de los enlaces:La aprobación utiliza tarjetas de aprobación y enrutamiento unificado,tiempo de espera/rechazar/Cierre del estado de interrupción
- La determinación del resultado de la herramienta se unifica en un determinante compartido(tarjeta de tarea/subagente/Misma implementación de tareas programadas.)
- Unifique las herramientas del navegador en una instancia compartida global,Eliminar la bifurcación de estado entre el nivel de sesión y el singleton global
- Herramienta de solo lectura en escala de grises paralela habilitada;subagente/La herramienta de tareas programadas filtra por lista blanca de riesgos
- Tiempo de espera difícil de la herramienta de bloqueo(Grupo de subprocesos dedicado + interrupciones),Los tiempos de espera externos ya no existen solo de nombre
- chat grupal/Momentos/Avisos visibles y cierres de estado para fallas de respaldo y otras rutas

## v1.0.88 - Multilenguaje y diseño perfecto.

- Recursos completos de traducción en inglés/multilingüe y redacción de textos publicitarios para candidatos
- Títulos de widgets y otras nuevas sincronizaciones de redacción en varios idiomas

## v1.0.87 - El modo de host y el diseño son estables

- Servidor web integrado(modo anfitrión)Capacidad en línea,Acceso API LAN
- Estabilidad del diseño y cierre de verificación de lanzamiento.

## v1.0.86 - Estabilidad de generación y memoria.

- Correcciones de estabilidad del sistema de memoria y enlace de generación(Ubicación de transmisión/biblioteca sucursal/Sección de compilación)

## v1.0.85 - Correcciones integrales para la estabilidad y las capacidades.

- Correcciones integrales para la estabilidad y las capacidades.(Ver CHANGELOG lote correspondiente)

## v1.0.84 - Resumen diario, exposición de herramientas y estabilidad de la interfaz de usuario

- resumen diario/Alineación de políticas de exposición de herramientas y correcciones de estabilidad de la interfaz de usuario

## v1.0.83 - Correcciones de estabilidad

- Se solucionó el problema de que las respuestas de transmisión se sobrescribieran con instantáneas antiguas del árbol de sesiones y cambiaran al fondo./Responder al problema que desaparece después de regresar a la recepción.
- Se solucionó el problema de la proyección vacía cuando la parte del mensaje estructurado carece de texto en el cuerpo.
- Se corrigió el fallo del contexto sin actividad al compartir registros de depuración, registros de fallos, modo seguro y registros de auditoría.
- Se corrige la barra de navegación de tres teclas que bloquea la operación final del panel inferior y se unifica la barra de entrada del Chat y el panel inferior. Insets
- Se agregó un nuevo interruptor de retorno predictivo, que está habilitado de forma predeterminada. El retorno normal se ejecutará inmediatamente después de ser deshabilitado.
- Úselo en su lugar cuando la transmisión esté apagada `completeText()`y limitar los reintentos compatibles con think/tool_choice a uno
- Gran colección de herramientas para filtrar por intención del usuario, corregir tarjetas de error duplicadas y tormentas de solicitudes de mensajería proactiva en segundo plano.
- Completa Qwen3.5,DeepSeek OCR,GLM4.6 Reconocimiento de capacidades del modelo y retención de resultados de solo razonamiento de modelos visuales.


## v1.0.68 - Correcciones de estabilidad + herramientas/Recuperación de enlaces de conversación

**Conversaciones y respuestas**
- Se solucionó el problema de que el modelo solo genera la primera palabra y el proceso de pensamiento no se actualiza.
- Se solucionó el problema por el cual el historial de múltiples rondas de conversaciones se comprimía en la ronda más reciente y los mensajes de la segunda ronda se aparecían por error.“Reintentar mensaje del asistente”pregunta
- Se solucionó el problema de no ingresar a una nueva ronda después de enviar una respuesta de cotización.
- Se solucionó el problema de que la respuesta a la cita no incluía el contenido citado. La cotización original ahora se enviará junto con el mensaje. AI
- Se solucionó el problema por el cual la tarea anti-vibración reescribía el borrador anterior después del envío y el texto enviado ya no se restauraba después de salir y volver a ingresar.
- Se solucionó el problema por el cual el conmutador de rama 1/N desaparecía después de la regeneración y el asistente podía continuar cambiando después de restaurar la versión.
- Mantenga una rama de respuesta independiente para cada pregunta y cambie entre grupos de preguntas sin interferir entre sí.

**Herramientas y chat grupal**
- Restaure el registro completo del conjunto de herramientas y ya no habrá solo unas pocas herramientas, como búsqueda en línea, navegador, dibujo, tareas programadas, etc.
- Solucionar el problema de que las herramientas del navegador no están disponibles
- Optimice el problema de la respuesta lenta del asistente de chat grupal e inicialice correctamente todos los registros de la herramienta al inicio

**memoria**
- Reparación de memoria, análisis y escritura de resultados de extracción de hechos, la memoria de chat diaria se puede extraer normalmente
- Solucionar el problema de la contaminación del texto de inferencia en el proceso de memoria.

**Modelos y migración**
- Se corrigió el fallo de la migración de la tabla de mensajes de chat grupal al actualizar a 1.0.62, y la actualización ya no informará “Migration didn't properly handle”
- Reescritura gratuita del modelo alternativo, modelo gratuito disponible después de eliminar la clave API
- El módulo de coordenadas visuales hanako sigue siendo compatible después de pasar a llamarse muse-box

**experiencia**
- El nombre establecido en el primer arranque ahora se muestra correctamente en el perfil
- “Acerca de”Pagina nueva“UnirseQQgrupo”, haz clic para saltar rápidamente (QQ Número de grupo 905451314)
- Se corrigió el color negro que aparece en interacciones como presionar prolongadamente y deslizar/Máscara de color, presionar y deslizar ya no mostrará bloques de color
- Se solucionó un problema sistémico por el cual parte del contenido de la interfaz de usuario desaparecía aleatoriamente después de plegarlo y desplazarse

**Ingeniería**
- Limpiar una gran cantidad de referencias externas y organizar estructuras internas.
- Complemente la migración de bases de datos, el árbol de diálogo, la memoria y otras pruebas de regresión.


## v1.0.67 - Ramas de diálogo completas + experiencia de edición mejorada

- Actualizaciones importantes desde la última versión oficial v1.0.62

**Experiencia de chat**
- Los mensajes de usuario admiten la edición emergente y ya no ocupan el campo de entrada principal; Se genera automáticamente una nueva versión de la pregunta después de editarla.
- La misma pregunta admite el cambio de versión 1/N. Cada versión guarda de forma independiente la respuesta del asistente sin interferir entre sí.
- El asistente responde al botón de cambio 1/N después de volver a intentarlo, lo que puede cambiar entre diferentes respuestas bajo la misma pregunta.
- Se solucionó el problema de que no había un segundo grupo después de editar y el botón de rama no se mostraba después de volver a intentarlo.
- La ocupación del contexto se cambia a una pequeña fila independiente debajo del botón de acceso directo, que ocupa un espacio más pequeño y el campo de entrada está más limpio.
- La entrada de selección de mensajes se ha movido al menú de pulsación larga y la línea de acceso directo es más concisa.
- Quitar“Saltar a los más antiguos no leídos”Consejos
- Los recuentos históricos de sucursales se reparan automáticamente y los índices de sucursales anormales ya no son confusos.

**Archivos y tareas**
- Restaurar la entrada del chat de archivos: puede ver conversaciones de archivos históricos, abrir conversaciones y cancelar archivos
- La tarjeta de tareas del subagente en segundo plano y la página de detalles muestran el avatar del asistente correspondiente y el ícono del robot ya no se usa de manera uniforme.
- Configuración del asistente agregada“Asistente predeterminado”, utiliza automáticamente el asistente seleccionado cada vez que inicias una nueva tarea

**Modelos y canales**
- La información sobre el contexto y la capacidad del modelo se mantiene en directorios locales, dando prioridad a la sobrescritura manual por parte de los usuarios, lo que hace que la información del modelo sea más precisa.

**Complementos y extensiones**
- El complemento se cambia a instalación externa y ya no a paquete integrado. Los usuarios pueden optar por instalarlo y activarlo ellos mismos.
- Se agregó una nueva guía de creación de complementos para admitir la creación de complementos asistida por modelos y Skill

**Estable y confiable**
- Se corrigió un error que ocurría al editar un mensaje.“El borrador ha sido restaurado.”pregunta
- Se solucionó el problema de la cadena de mensajes a otras sesiones al cambiar de sesión o de agente.
- Se solucionó el problema de que los avatares personalizados desaparecieran ocasionalmente y regresaran al color del tema.
- Reparar parámetros faltantes de la herramienta de búsqueda de Internet/Informar errores y hacer que las llamadas a herramientas sean más confiables
- Verificación del esquema de parámetros de llamada de herramienta para reducir las llamadas no válidas

**Otras mejoras**
- Elimine el ícono de candado en la esquina superior derecha de la página de diálogo de tareas
- Se siguen mejorando los detalles de accesibilidad.
- La compilación formal agrega verificación obligatoria de firma y número de versión
- Mucha organización interna: entornos, herramientas,Skill, Módulo de navegación dividido

## v1.0.18 - Se corrigieron varios fallos.,La interfaz es más limpia.

- Se solucionó el problema por el cual el botón de la herramienta fallaba al hacer clic y el modo seguro se iniciaba normalmente pero causaba un bucle sin fin.
- El botón flotante en la página de tareas se reduce.,La búsqueda admite pinyin,Eliminar mis informes inútiles

## v1.0.19 - Disponible sin iniciar sesión + optimización de pequeña experiencia

- Modelos gratuitos incorporados key,Simplemente instálalo y podrás chatear.(No es necesario registrarse)
- Reconexión de voz automática después de la desconexión de la red sin perder palabras,Esta vez se puede recordar la aprobación de la herramienta,Resultados de búsqueda con vista previa resaltada

## v1.0.20 - Colaboración en línea con múltiples asistentes

- Varios asistentes de IA pueden delegar tareas entre sí,Trabajar juntos como equipo para completar tareas.
- Puedes ver un diagrama de árbol del flujo de tareas en la conversación.,Las tareas en segundo plano tienen una lista,El campo de entrada se puede enviar activamente

## v1.0.21 - Las conversaciones fluyen más fluidamente(Versión clave)

- Se solucionaron problemas con palabras faltantes y retrasos en medio de la respuesta.,Las respuestas largas se desplazan más suavemente
- Comprimir automáticamente el contexto si es demasiado largo,La transmisión se puede reanudar incluso si la red está desconectada.
- Escriba archivos automáticamente y haga referencia a mensajes históricos cuando sean demasiado largos,No retrases la conversación

## v1.0.30 - Funcionalidad completa de chat grupal + pulida con muchos detalles interactivos

- El tema predeterminado cambia a blanco y negro durante el primer inicio y se ajusta el orden de la lista de temas.
- Las etiquetas del cuadro de entrada se mueven uniformemente encima del cuadro de entrada para evitar la oclusión
- Las burbujas de mensajes eliminan las sombras y lucen más limpias
- El botón "Volver al fondo" se cambia a un estilo pequeño, redondo y transparente, que solo se muestra cuando se desliza hacia arriba.
- La búsqueda en Internet y el pensamiento profundo se mueven al menú más y se muestra una pequeña marca de verificación cuando se activa.
- La posición del menú del signo más se mueve hacia abajo y los espacios izquierdo y derecho se dejan en blanco para reducir el tamaño.
- Mantenga presionado el menú de diálogo en la página de inicio para aumentar el espacio inferior y evitar que sea compacto
- Se solucionó el problema de restaurar accidentalmente borradores enviados después de salir y volver a ingresar a la sesión.
- La selección del modelo de la interfaz del cuadro de diálogo se cambia a un icono circular para guardar espacio en la parte superior.
- Se corrigieron problemas visuales como máscaras negras y dirección incorrecta de la cola de la burbuja al presionar prolongadamente un mensaje.
- El asistente de chat grupal admite el uso de modelos configurados individualmente
- Se agregó una entrada de selección de modelo a la página de detalles del asistente.
- Se corrigieron problemas de compatibilidad con algunos servicios de modelos de terceros.
- Se solucionaron problemas como que el estado de ánimo no se analizaba correctamente y el asistente no respondía en los chats grupales.
- Optimice la lógica de visualización de notificaciones y no le moleste cuando esté en primer plano
- Se corrigieron problemas detallados como el desbordamiento de texto en la página de tareas programadas y los íconos invisibles en la página de traducción.

## v1.0.22 - Función de chat grupal muy mejorada

- Los chats grupales finalmente pueden utilizar la orquestación del flujo de trabajo
- Cada IA ​​piensa de forma independiente y no mezcla roles.
- Ingrese @ para que aparezca automáticamente la lista de miembros
- En el chat grupal, puedes ver en tiempo real quién mira, quién responde y quién salta
- La memoria del chat grupal se almacena de forma independiente y no contamina la memoria principal del asistente.
