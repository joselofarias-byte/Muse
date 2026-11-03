# Muse v2.2.0

La actualización integral más grande desde 2.1: el sistema de conferencias de chat grupal ha tomado forma, las capacidades de las herramientas se han reducido y acelerado por completo, el asistente ha aprendido a administrar su propia memoria y complementos, y el primer inicio y la primera pantalla se han rediseñado por completo. Se recomienda a todos los usuarios actualizar.

## Lo más destacado

- **Sistema de conferencias de chat grupal**: Tarjeta exclusiva para resumen de discusión (copiar/compartir/guardar como documento compartido, la siguiente discusión se inyectará automáticamente con el contexto de todos los miembros); entrega exclusiva de papeletas de votación; miembros observadores (esperan y observan sin rotación); visualización de la cola de voz y la barra del director (pausar/continuar/omitir el resto); plegado de rondas; plantillas rápidas (revisión de planes/debate positivo y negativo/lluvia de ideas/revisión de proyectos); Las interrupciones ya no se perderán durante la rotación.
- **Adelgazamiento de herramientas y recuperación bajo demanda**: De forma predeterminada, solo se envían al modelo las herramientas de uso común, con un contexto más limpio y una respuesta más rápida; el modelo puede usar find_tools para buscar por palabra clave y cargar capacidades de cola larga, y las herramientas de impacto seguirán estando disponibles dentro de la sesión.
- **Capacidad de autogestión del asistente.**: Puede escribir/borrar directamente la memoria a largo plazo ("recuerda……""eliminar ese"), también puede desinstalar, habilitar/deshabilitar complementos.
- **Primer arranque y rehacer la primera pantalla**: Guía de ocho pasos (Bienvenida/Idioma/Comprensión/Memoria/Acceso/Modelo/Apariencia/Salida), carrusel de bienvenida en varios idiomas; Un logotipo grande y saludos en la primera pantalla despliegan la animación de entrada.
- **sistema de iconos v1**:173 Todos los íconos autodibujados reemplazan completamente las referencias de íconos antiguos, gráficos exclusivos como centro de memoria/proveedor/enrutamiento de tareas/agente.
- **Web servidor HTTPS**: El servicio web integrado admite el certificado HTTPS autofirmado, lo que hace que el acceso remoto sea más seguro (Netty motor).
- **RAG índice de transmisión**: El límite de tamaño de archivo se relaja para la importación de la base de conocimientos, se ingiere la transmisión por ventana deslizante y los archivos de gran tamaño ya no causan fallas de memoria.
- **Modelos y enrutamiento**: El enrutamiento de tres velocidades del modelo auxiliar está completamente conectado; el catálogo de modelos completa la verdadera producción máxima de 49 modelos;Gemini Generación de imágenes nativas/acceso remoto al directorio de modelos.

## Experiencia y pulido.

- **charlar**: Transmisión"Aparece todo el párrafo"y siga las reparaciones de desplazamiento; eliminación de referencias al deslizar el dedo hacia la izquierda (las referencias se unifican mediante el menú de pulsación larga); Reparaciones largas de truncamiento de contenido (mejora del presupuesto de rueda de continuación/rueda de herramientas).
- **memoria**: Actualización atómica del extremo de extracción; prevención de fugas de muestras (las muestras copiadas ya no se incluirán en la biblioteca); Cierre de inyección y prevención de fallos.
- **ajustes**: Dos rondas de rectificación total——Se restaura la estructura, se unifica la denominación y se eliminan las duplicaciones de la página; el final de la página de chat se divide, la página del modelo auxiliar se rehace y se organizan la página de importación y la página de informe de fallos.
- **animación**: Además, los efectos de interacción del menú/barra de acción rápida/MuseChip se rehacen y los tokens de efectos de movimiento se unifican (respetando la configuración de animación de apagado del sistema).
- **Visión**: Calibración biplano en modo oscuro; Tokenización del ritmo del diseño del contenido largo.
- **Reparación**: Bloqueo del tamaño de una tarjeta Emoji; servicio de generación Defensa Koin; bloqueo de desplazamiento anidado del cuadro de diálogo de tareas programadas; bloqueo del clic en la línea de tiempo del flujo de memoria; base de conocimientos importación de archivos grandes OOM.
