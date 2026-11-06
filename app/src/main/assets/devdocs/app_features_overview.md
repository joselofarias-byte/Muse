<!-- devdoc: Documentación de desarrollo interno.,No se muestra a los usuarios,LLM Consulta a través de Knowledge_search -->
# Muse App Resumen de funciones

> Palabras clave desencadenantes: Cuando el usuario pregunta"¿Qué puedes hacer?""Muse que es""¿Qué funciones tiene esta aplicación?""como usar""que funciones""Introducción a la función""como usar"tiempo,Consulte este documento primero y responda con sinceridad.
> Este documento es un documento de desarrollo interno consultado por LLM a través de la herramienta Knowledge_Search.,El contenido está basado en el código real del proyecto muse.,No mostrar el texto original a los usuarios.
> "como hacer las cosas"manual de funcionamiento(Límites de capacidad/procedimientos estándar/rutas de tareas/líneas rojas del habla)Consulte asistente_capability_playbook.md.

## 1.Muse que es

Muse Es una aplicación de asistente de IA para Android.,El posicionamiento central es"Cliente LLM complementario + plataforma de agente". Combina chat, gestión de la personalidad con múltiples asistentes, memoria a largo plazo, recuperación de la base de conocimientos,Skill Extensiones, tareas programadas, colaboración en chat grupal y otras capacidades están integradas en una sola aplicación,Todos los datos se almacenan localmente(Room base de datos + DataStore).

Principales diferencias con el cliente de chat LLM normal:
- Múltiples asistentes:Cada asistente tiene sistema independiente de configuración de aviso, modelo, herramienta y memoria.
- memoria a largo plazo:Fact Refinado + compilado para inyección de fragmentos de rebajas system prompt
- base de conocimientos:Los usuarios pueden importar documentos.,LLM Búsqueda de texto completo a través de Knowledge_search
- Skill sistema:8 habilidad incorporada + autoexpansión install_skill
- Agent colaboración:delegate_agent Herramientas para que los asistentes deleguen tareas a otros asistentes,El chat grupal permite que varios asistentes discutan

## 2. Módulos funcionales principales

### 1. charlar (ChatScreen)
Interfaz interactiva principal,Salida en streaming de respuestas de LLM. entrada: Lista de conversaciones de la página de inicio → Ingrese a la sesión.

- Markdown renderizado: resaltado de código(CodeHighlighter),LaTeX fórmula(FormulaView), tabla, bloque de referencia, lista
- Colapso de mensaje largo: Colapsar automáticamente cuando se excede el umbral,Haga clic para ampliar
- Operación de burbuja de mensajes: Haga clic/Mantenga presionado para que aparezca el menú de operación.,incluir: Copiar / Reenviar(regenerar) / Eliminar / Editar / Cotizar(quote) / confiar(delegate_agent) / Traducir / leer en voz alta(TTS) / Colección/Tenedor(Copiar el historial de este mensaje a una nueva conversación) / Compartir(Exportar a Markdown y ejecutar el sistema. share sheet)
- Cotización Responder: Los mensajes de usuario pueden traer `> ` bloque de cotización,El contenido citado se muestra en la parte superior de la burbuja.
- Cambio de modelo temporal: La cápsula del modelo de campo de entrada puede cambiar temporalmente el modelo de sesión actual
- pensar en etiquetas: Admite expansión y plegado de etiquetas MOOD/thinking

### 2. Agent / asistente (AssistantScreen + AssistantDetailPages)
Gestión de la personalidad con múltiples asistentes. Cada asistente es un individuo independiente.,Hay avisos del sistema, modelos, interruptores de herramientas y configuraciones de memoria independientes. entrada: ajustes → Asistente/Configuración → Agent.

Los detalles del asistente se dividen en 5 subpáginas.:
- Basic(Conceptos básicos): Avatar, nombre, selección de modelo, exportación de tarjeta de asistente
- Prompt(palabra rápida): systemPrompt,messageTemplate,presetMessagesJson
- Extensions(Expandir): Lorebook configuración de la cosmovisión,PromptInjection Inyección rápida de palabras,QuickMessage mensaje rápido,Skill cambiar
- Memory(memoria): memoryEnabled,useGlobalMemory,enableRecentChatsReference,enableTimeReminder
- Advanced(Avanzado): Parámetros del modelo(temperature / reasoningLevel), encabezado de solicitud

### 3. memoria (MemoryScreen + MemorySettingsPage)
Sistema de memoria a largo plazo. entrada: ajustes → asistente → Subpágina de memoria/configuración → memoria.

- Fact refinar: daily pipeline Extraer hechos de conversaciones
- compilar inyección: Fact Compilar en resumen de rebajas,Inyección a través de MemoryInjectionTransformer system prompt
- Pinned Memories: Usuario/LLM memoria fija,Inyectar cada vez,No se descompone con el tiempo
- recordatorio de tiempo: enableTimeReminder Después de abrir,Inyección de memoria dependiente del tiempo bajo demanda
- situación general/memoria independiente: useGlobalMemory Alternar uso compartido global o asistente independiente

### 4. base de conocimientos (KnowledgeScreen)
Los usuarios importan documentos para que LLM los recupere. entrada: ajustes → base de conocimientos.

- Formatos soportados: txt / md / csv / json
- Método de búsqueda: LLM Búsqueda de texto completo por título + contenido a través de la herramienta Knowledge_search
- RAG búsqueda vectorial: Admite la recuperación semántica después de configurar EmbeddingProvider(OpenAI / ONNX Rebaja de categoría local/palabra clave)
- devdoc entrada: fileType="devdoc" Las entradas son documentos de desarrollo interno.,Solo para consultas de LLM,No aparece en la interfaz de usuario(Este documento entra en esta categoría.)

### 5. Skill sistema (SkillScreen)
8 Habilidades incorporadas + autoexpansión. entrada: ajustes → Skill.

- Incorporado skill: web_search / web_fetch / knowledge_search / install_skill / delegate_agent / list_stickers / send_sticker espera
- install_skill: LLM Genere definiciones de habilidades usted mismo y guárdelas en la biblioteca(autoexpansión)
- Habilidad definida por el usuario: importar .skill.json,Pero la implementaciónKotlin debe ser una implementación en la lista blanca.(Evite que LLM sobrescriba las herramientas integradas)
- gestión: install / uninstall / enable / disable

### 6. tareas programadas (ScheduledTasksScreen)
cron La expresión hace que el asistente genere mensajes y notifique periódicamente. entrada: ajustes → Tareas programadas.

- Expresión cron estándar de 5 campos(minutos horas días meses semanas)
- ScheduledTaskRunner llama a ChatGenerationService cuando se activa para permitir que el asistente genere mensajes
- El historial de ejecución se registra en ScheduledTaskExecutionEntity
- Intervalo mínimo 10 minutos

### 7. mensajes no solicitados (ChatSettingsPage)
El asistente envía mensajes de forma proactiva a los usuarios a intervalos regulares y muestra notificaciones emergentes.(Mensaje como WeChat). entrada: ajustes → charlar → Mensajería proactiva.

- Opciones de intervalo: 1 / 2 / 4 / 8 / 12 / 24 horas
- intervalo real = intervalo base ± desplazamiento aleatorio(Mínimo 1 hora,evitar con demasiada frecuencia)
- Enviado por ProactiveMessageRunner,Notificaciones emergentes a través de MuseNotificationManager

### 8. pensamiento profundo
El menú + en el campo de entrada del chat se abre temporalmente. entrada: pagina de chat → Campo de entrada + signo → Piensa profundamente.

- habilitar ReasoningLevel.HIGH(8000 tokens presupuesto de inferencia)
- Sólo tiene efecto cuando la sesión actual se está ejecutando,No persistió en la configuración auxiliar
- Anular el valor predeterminado del asistente reasoningLevel

### 9. búsqueda en internet
Campo de entrada + interruptor de menú de signo. entrada: pagina de chat → Campo de entrada + signo → Busque en línea.

- Captura de Bing predeterminada cn.bing.com
- apoyo SearXNG / Tavily(En la configuración → configuración de búsqueda en Internet)
- Una vez activado, LLM decide de forma independiente cuándo llamar web_search / web_fetch

### 10. chat grupal (GroupChatScreen)
Discusión colaborativa entre múltiples agentes. entrada: Pestaña/Configuración de chat grupal en el hogar → Agent.

- Asistentes que eligen participar al crear un chat grupal
- Los usuarios pueden unirse al canal para participar en la conversación.
- Tipo: DM(Dos personas charlando en privado.) / Group(Grupo multijugador)
- Turnándose para hablar con el asistente de programación GroupChatScheduler

### 11. Página de traducción independiente (TranslateScreen)
Traducción multilingüe. entrada: ajustes → Herramientas → AI Traducir.

- Admite pegar texto fuente y copiar traducción
- Idioma de origen/destino opcional
- Revisar la traducción del modelo LLM actualmente configurado

### 12. Tema personalizado
Apariencia personalizada. entrada: ajustes → Apariencia.

- Material You Selección de color dinámica(dynamicColor): Sigue el fondo de pantalla del sistema para elegir colores.(Android 12+)
- 3 Reversión de capa: dynamicColor > customTheme > presetTheme
- Tema personalizado: Generar según el color de la semilla. ColorScheme,Personalizable primary / secondary / tertiary Color
- Tema predeterminado: Múltiples conjuntos de colores incorporados
- fuente,Markdown El espacio en blanco se puede ajustar.

### 13. WebServer (WebServerSection)
Servidor Ktor integrado. entrada: ajustes → Web servidor.

- Capacidad de llamar a la aplicación de forma remota a través de HTTP + JWT dentro de LAN
- mDNS descubrimiento de servicios(MdnsService),Los dispositivos LAN se pueden descubrir automáticamente
- Para control remoto en PC u otros dispositivos Muse

### 14. copia de seguridad (BackupSection)
Exportación, importación y copia de seguridad de datos en la nube. entrada: ajustes → Respaldo.

- Exportación/importación local: Exportar datos completos a un archivo,Recuperable
- Copia de seguridad en la nube: S3 / WebDAV
- Consulta de saldo: Consultar saldo de almacenamiento en la nube

### 15. biblioteca de emoticonos
v1.95 función. entrada: ajustes → charlar → Biblioteca de emoticonos.

- LLM Los emoticones disponibles se pueden enumerar a través de la herramienta list_stickers
- LLM Transitable send_sticker(id=...) La herramienta envía emoticonos a los usuarios
- Los emoticones se importan como zip.,Almacenamiento independiente de Room
- probabilidad de envío: 0-100 Ajustable(Predeterminado 30),El modelo tiene esta probabilidad de llamar cada vez que responde. send_sticker

### 16. TTS
Texto a voz. Entrada: ajustes → medios de comunicación → TTS.

- TTS del sistema: llame al motor TTS del sistema Android
- nube TTS: OpenAI / MiniMax / Edge(Compatible con el formato de interfaz OpenAI)
- Menú de mensajes de la página de chat"leer en voz alta"gatillo

### 17. OCR
Reconocimiento de texto de imagen. entrada: La página de chat reconoce automáticamente las imágenes al enviarlas.

- ML Kit Identificación fuera de línea
- Soporte chino + inglés
- Programación unificada por OcrManager

### 18. Retrato de usuario
Inyección de información personalizada. entrada: ajustes → Apariencia → Retrato de usuario.

- Rellenable: Título/Edad/Ciudad/MBTI/Ocupación/Interés
- inyección de contenido system prompt,Deje que LLM comprenda mejor a los usuarios

### 19. Página de inicio (OnboardingScreen)
Inicie el proceso por primera vez. entrada: Comienza la primera instalación.

- Paso 1: tres páginas de introducción a la función(HorizontalPager)+ Siguiente / Saltar
- Paso 2: configuración de saludo personalizada(Nombre del asistente + título de usuario)+ Comenzar/Agregar proveedor de modelo

### 20. MCP (Model Context Protocol)
Sistema de conectores. entrada: ajustes → Modelos y Servicios → MCP.

- Administrar múltiples McpClient
- Admite servicios MCP locales y remotos
- McpOAuthFlow Admite autorización de inicio de sesión de OAuth
- Reconexión automática después de la desconexión
- Después de acceder, LLM puede llamar a las herramientas proporcionadas por MCP como herramientas integradas.

### 21. Colaboración entre múltiples agentes
delegate_agent Herramientas. entrada: LLM Llamada autónoma.

- Deje que el asistente actual delegue la tarea a otros asistentes para que corran durante una ronda. LLM
- Solicitud para inyectar una lista de identificadores de ayuda disponibles,LLM Elija el objetivo de encomienda en consecuencia
- Una vez ejecutado el subasistente, los resultados se devuelven al asistente principal.

### 22. Importación y exportación de códigos QR
Compartir configuración del asistente. entrada: Detalles del asistente → Exportar código QR.

- La configuración del asistente puede generar un código QR para compartir
- Escanea el código QR para importar la configuración del asistente
- Implementado por QrCodeGenerator / QrCodeScanner

### 23. Prompt variable
Sistema de variables de plantilla. Utilizado en systemPrompt/messageTemplate.

- Gramática: Pebble subconjunto compatible,{{ var }} forma
- Variables de uso común: {{user_name}} / {{char}} / {{date}} Esperando usuarios/asistente/variables dependientes del tiempo
- Filtros de soporte: upper / lower / length / default / trim / join / replace / capitalize
- Soporte para bucle,if Condiciones
- por PebbleTemplateEngine(Implementación de Kotlin pura de dependencia cero)renderizado

### 24. Reglas de reemplazo regulares
Postprocesamiento de mensajes. entrada: Detalles del asistente → Extensions.

- RegexMessageTransformer / RegexTransformer
- Realice un posprocesamiento de reemplazo regular en la salida de LLM de acuerdo con las reglas
- Las reglas se almacenan mediante la configuración del asistente.

### 25. Lorebook
Inyección de configuración de visión mundial. entrada: Detalles del asistente → Extensions → Lorebook.

- Activar la inyección de entradas de configuración de visión mundial por palabras clave
- LorebookTransformer inyecta la configuración correspondiente cuando una palabra clave coincide en la conversación
- Lorebook independienteGestión de pantalla

### 26. PromptInjection
Rápida inyección de palabras. entrada: Detalles del asistente → Extensions → PromptInjection.

- Inyecte palabras adicionales en el system prompt
- Manejado por PromptInjectionTransformer
- Gestión independiente de PromptInjectionScreen

### 27. QuickMessage
Mensaje rápido. entrada: Detalles del asistente → Extensions → QuickMessage / Chips de acceso directo al campo de entrada.

- Frases preestablecidas chips,Enviar con un clic
- Utilice marcadores de posición de doble corchete {{input}} / {{clipboard}} / {{date}},Renderizado automáticamente por QuickMessageRepository.renderTemplate
- Gestión independiente de QuickMessageScreen

## 3. Establecer partición de elementos

página de configuración (SettingsScreen) Dividido en las siguientes particiones,de arriba a abajo:

### 1. Apariencia y personalización(Interruptor de alta frecuencia del usuario en la parte superior)
- Tema(dynamicColor / customTheme / presetTheme)
- fuente,Markdown dejar en blanco
- Retrato de usuario(Título/Edad/Ciudad/MBTI/Ocupación/Interés)
- Configuración secundaria del chat(Visualización de mensajes/Expansión predeterminada/Temperatura/Avanzado)

### 2. memoria y datos
- memoria(MemorySettingsPage)
- Copia de seguridad en la nube(S3 / WebDAV)
- Importación de datos(DataImportPage)
- base de conocimientos / RAG(RagSettingsPage)

### 3. Modelos y Servicios
- proveedor(ProviderSection): API Key / Base URL / OAuth / locales Ollama
- asistente(AssistantSection)
- Agent(MultiAgentSettingsPage)
- búsqueda en internet(WebSearchSection): Bing / SearXNG / Tavily
- MCP(McpSection)
- ASR(AsrSection)
- medios de comunicación(MediaSettingsPage): TTS / Generación de imágenes(ImageGenSection)

### 4. Herramientas(Entrada independiente a la herramienta de IA)
- AI Traducción(TranslateScreen)

### 5. Avanzado(Configuración técnica configurada al final)
- seguro(SecuritySettingsPage)
- agente(ProxySettingsPage)
- Experimental(ExperimentsSettingsPage)
- Estadísticas(StatsPage)
- Web servidor(WebServerSection)

### 6. Acerca de
- Tutoría(SettingsTutorialPage)
- Información de versión/licencia de código abierto(LicensesScreen)

## 4. Principios para responder preguntas funcionales de los usuarios.

1. Responde sinceramente: Respuesta basada en las características enumeradas en este documento.,No inventes funciones que no puedas encontrar.
2. expresión popular: Utilice un lenguaje que los usuarios comunes puedan entender.,Evite la jerga técnica
3. dar entrada: Al responder funciones, intente decirle a los usuarios dónde encontrarlas.(Establecer ruta o entrada de operación)
4. Hablando francamente de limitaciones: Si el usuario pregunta sobre una función que Muse no admite,claramente indicado,No pretendas apoyar
5. Diferenciar versiones: Algunas funciones están marcadas con números de versión.(Como el paquete de emoticonos v1.95),Es posible que las versiones anteriores no tengan

## 5. Referencia a preguntas frecuentes

- "Muse ¿Qué se puede hacer?": Consulte la Sección 2 para obtener una introducción paso a paso a los módulos funcionales principales.
- "Cómo usar la memoria": ajustes → asistente → Se abre la subpágina de memoria,o decir en una conversación"recuerda:XXX"Memoria fija
- "Cómo importar conocimiento": ajustes → base de conocimientos,importar txt/md/csv/json,LLM Se recuperará a través de Knowledge_search.
- "Cómo enviar mensajes regularmente": ajustes → tareas programadas,Configurar expresiones cron
- "¿Cómo consigo que mi asistente me encuentre de forma proactiva?": ajustes → charlar → mensajes no solicitados,Seleccione intervalo de 1/2/4/8/12/24 horas
- "Cómo cambiar el tema": ajustes → Apariencia,Elija Material You, colores dinámicos o temas personalizados
- "Cómo pensar profundamente": El campo de entrada del chat + menú numérico abre un pensamiento profundo
- "Cómo buscar en línea": Campo de entrada de chat + No. de menú para habilitar la búsqueda en Internet,o en la configuración de ajustes SearXNG/Tavily
- "Cómo colaborar con múltiples asistentes": Crear múltiples asistentes,Déjalos discutirlo en el chat grupal.,O delegar con delegado_agent
- "Cómo compartir asistente": Detalles del asistente exportar código QR,La otra parte escanea el código para importar
