# Muse v2.1.0

Una versión grande del sistema de canales. Se implementa el acceso a cinco terminales WeChat / QQ / Feishu / Telegram / DingTalk, la información del canal y los asistentes se conectan sin problemas; la experiencia de entrega del producto se actualiza,Agent Se han solucionado problemas clave con los enlaces de herramientas. Se recomienda a todos los usuarios actualizar.

## Lo más destacado

- **Acceso a cinco canales terminales**: WeChat,QQ, Feishu,Telegram, DingTalk completa el acoplamiento. La configuración del canal se reconstruye de acuerdo con el modelo exclusivo de cada plataforma (campos exclusivos, máquina de estado de inicio de sesión del código de escaneo de WeChat, prueba de conexión, tutorial de configuración);QQ Utilice la recepción de conexión larga de WebSocket y la respuesta pasiva, Feishu utiliza un canal de eventos de conexión larga.
- **Los canales son conversaciones.**: Cada conversación de canal tiene un contexto de múltiples rondas independiente, que admite la compresión automática y el reinicio manual de resúmenes continuos; Los textos, imágenes y mensajes de voz recientes se archivan de forma unificada y se pueden revisar en cualquier momento.
- **flujo de noticias de los medios**: Las imágenes de WeChat se descargan, descifran, comprimen y almacenan automáticamente en la base de datos; el servidor reconoce la voz y la convierte en texto. Responder admite la visión nativa del modelo y el puente visual se degrada a descripción cuando es necesario.
- **sistema de entrega de productos**: Los códigos/páginas web/documentos generados por el asistente se actualizan a tarjetas grandes de ancho completo (se muestran hasta tres en el mensaje; haga clic para expandir todo); Se agrega gestión unificada en el nuevo centro de productos.PDF Admite vista previa en la aplicación con documentos.
- **Agent Correcciones de enlaces de herramientas**: Se solucionó un problema por el cual las llamadas a herramientas en algunos modelos fallaban silenciosamente debido a los campos del directorio de capacidades predeterminados.——Las definiciones de herramientas ya no se descartan por error y las llamadas a herramientas son estables y están disponibles.
- **mercado de complementos**: La puerta de enlace de la herramienta, la raíz de confianza y los enlaces de verificación de firma son perfectos, lo que hace que la instalación e invocación de herramientas del mercado sea más segura.

## Experiencia y pulido.

- Configure la búsqueda para que se actualice a una tarjeta flotante: alinee la interacción del panel de comandos de la página de inicio, conserve la lista original antes de escribir y la búsqueda ya no interrumpirá la navegación.
- El nombre de la plataforma del canal está completamente localizado; Se pulen los detalles de la configuración, el libro mundial y otras páginas, y se completan las traducciones a varios idiomas.
- Reparar "Programar tareas" → El cuadro de diálogo "Ver todo el historial" falla debido al desplazamiento anidado y se corrige el comportamiento de desplazamiento de la lista de tareas programadas.
- CI Optimización del enlace de lanzamiento: el margen de tiempo de espera de la tarea de compilación aumenta y el proceso de lanzamiento es más estable.
