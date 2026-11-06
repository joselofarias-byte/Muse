<!-- devdoc: Documentación de desarrollo interno.,No se muestra a los usuarios,LLM Consulta a través de Knowledge_search -->
# ToolRegistry Colección completa de herramientas integradas

> escena desencadenante: Usuario preguntó"¿A qué herramientas puedes llamar?""¿Qué funciones están disponibles?""XX Cómo utilizar la función""¿Para qué sirve esta herramienta?"tiempo,Consulte este documento y responda con sinceridad.
> Este documento está basado en el código fuente. ToolRegistry.kt(BUILT_IN_TOOL_IDS)/ cada uno Registrar / SkillExecutor.kt(BUILT_IN_SKILLS)la verdadera realización de,es el índice autorizado de las capacidades de la herramienta Muse.

## 1. Descripción general

ToolRegistry ¿Es el registro de herramientas local de Muse?(Versión simplificada del marco MCP). Las herramientas pueden ser:
- LLM Activado por llamada a función/tool_call(camino principal)
- tareas programadas,Skill,WebServer Reutilizar

Las herramientas se dividen en dos categorías.:
1. **herramientas locales(built-in)**: Registrado por cada Registrador al inicio ToolRegistry,Nombre sin prefijo
2. **Skill(Incorporado+Usuario)**: Definido por SkillExecutor,Habilitado a través de SkillIdsJson,Extensible por install_skill

También hay dos tipos de herramientas dinámicas.:
3. **MCP Herramientas**: banda de nombre `mcp_{serverId}__` prefijo,Regístrese después de conectarse al servidor MCP
4. **workspace Herramientas**: Lectura y escritura de archivos del espacio de trabajo de la aplicación

## 2. Mecanismo de lista blanca de herramientas(importante)

- Configuración del asistente `toolIdsJson`: Predeterminado `"[]"` expresar**Habilitar todo**;La especificación de una matriz habilita solo las herramientas enumeradas
- Configuración del asistente `skillIdsJson`: Todas las habilidades integradas están habilitadas de forma predeterminada;Subconjuntos especificables
- Configuración del asistente `mcpServerIdsJson`: cual unir MCP server(La conexión se realiza correctamente y el asistente principal se vincula automáticamente.)
- El sistema indica el índice de capacidad de la herramienta y tools schema **Filtro del mismo diámetro**(Por lista blanca),Lo que ve el modelo se puede ajustar

## 3. Lista completa de herramientas locales(uno por uno)

### 3.1 herramientas basicas

| Herramientas | parámetros | Descripción | Permisos |
|---|---|---|---|
| get_current_time | timezone(Opcional,IANA) | Obtener la hora actual | Ninguno |
| calculator | expression | calculadora sencilla,Suma, Resta, Multiplicación y División y Paréntesis | Ninguno |
| echo | text | entrada de eco(para probar) | Ninguno |
| json_pretty | json | JSON Embellecer y formatear | Ninguno |
| hash_text | text / algorithm | hash de texto | Ninguno |
| generate_uuid | - | generar UUID | Ninguno |
| random_number | min / max | numero aleatorio | Ninguno |
| generate_password | length(Opcional) | contraseña aleatoria | Ninguno |
| url_encode / url_decode | text | URL codificación/decodificar | Ninguno |
| base64_encode / base64_decode | text | Base64 codificación/decodificar | Ninguno |

### 3.2 Hora y horario

| Herramientas | parámetros | Descripción | Permisos |
|---|---|---|---|
| calendar_today | - | Lista principal de eventos del calendario de hoy | READ_CALENDAR |
| add_calendar_event | title/start/end espera | Agregar evento de calendario | READ_CALENDAR + escribir |
| schedule_reminder | text/time espera | Establecer recordatorio | POST_NOTIFICATIONS |
| cancel_reminder | id | Cancelar recordatorio | Igual que arriba |
| list_reminders | - | Recordatorios de lista | Ninguno |
| set_alarm | hour/min/label | Configurar la alarma del sistema(Abra la aplicación del reloj del sistema) | Ninguno |
| set_timer | seconds/label | Cuenta regresiva del diseño(Abra la aplicación del reloj del sistema) | Ninguno |

### 3.3 Herramientas del sistema celular

| Herramientas | parámetros | Descripción | Permisos |
|---|---|---|---|
| open_app | packageName | Abrir aplicación(Como por ejemplo com.tencent.mm) | Ninguno |
| open_url | url | El navegador abre el enlace | Ninguno |
| open_maps | query | Abrir búsqueda de mapa | Ninguno |
| open_system_setting | setting | Abra la página de configuración del sistema | Ninguno |
| share_text | text | Panel para compartir sistema | Ninguno |
| send_sms | phone/text | Enviar SMS | SEND_SMS |
| send_email | to/subject/body | Enviar correo electrónico(Inicie la aplicación de correo) | Ninguno |
| make_phone_call | phone | hacer una llamada | CALL_PHONE |
| add_contact | name/phone | Añadir contacto | WRITE_CONTACTS |
| get_contacts_count / get_contacts_list | - | Estadísticas de contacto/lista | READ_CONTACTS |
| get_location | - | última ubicación conocida(Latitud y longitud/Precisión) | ACCESS_COARSE_LOCATION |
| get_device_info | - | marca/modelo/Androidversión/pantalla/poder | Ninguno |
| get_battery_info | - | estado de la batería | Ninguno |
| get_network_info | - | estado de la red | ACCESS_NETWORK_STATE |
| get_wifi_info | - | WiFi información | ACCESS_FINE_LOCATION(parte) |
| get_bluetooth_devices | - | Lista de dispositivos Bluetooth | BLUETOOTH_CONNECT |
| get_cpu_info / get_memory_info / get_storage_info / get_display_info / get_sensors_list | - | Información de hardware | Ninguno |
| get_foreground_app | - | Aplicación actual en primer plano | Permisos especiales |
| get_recent_notifications | - | notificaciones recientes | NOTIFICATION_LISTENER |
| list_installed_apps | - | Lista de aplicaciones instaladas | QUERY_ALL_PACKAGES |
| get_public_ip | - | Red pública IP | red |
| screen_time | - | Tiempo de pantalla de la aplicación hoy Top10 | PACKAGE_USAGE_STATS |
| get_brightness / set_brightness | value | Lectura de brillo/escribir | WRITE_SETTINGS |
| get_volume / set_volume | type/value | lectura de volumen/escribir | WRITE_SETTINGS |
| toggle_wifi / toggle_bluetooth / toggle_flashlight | enabled | Cambiar WiFi/Bluetooth/linterna | CHANGE_WIFI_STATE espera |
| vibrate | duration | Vibración | VIBRATE |
| ping_host / dns_lookup | host | Diagnóstico de red | red |
| get_weather | city(Opcional) | clima | red |
| speak_text | text | TTS leer en voz alta | Ninguno |

### 3.4 Portapapeles y recursos

| Herramientas | parámetros | Descripción |
|---|---|---|
| clipboard_read | - | leer portapapeles |
| clipboard_write | text | escribir en el portapapeles |
| resource_add / resource_list / resource_search / resource_get / resource_delete | - | Gestión de recursos en la aplicación(Archivo/fragmento de texto) |
| quick_note_add / list / search / get / update / delete / pin | - | Notas rápidas CRUD + fijadas |

### 3.5 memoria y experiencia

| Herramientas | parámetros | Descripción |
|---|---|---|
| pin_memory | content / keyword | arreglar un recuerdo(Cada inyección) |
| unpin_memory | keyword / id | Desanclar |
| recall_experience | query | Recordar experiencias pasadas |
| record_experience | title/content | experiencia récord |
| search_memory | query | Buscar memoria a largo plazo(skill darse cuenta) |

### 3.6 comunicación y presentación

| Herramientas | parámetros | Descripción |
|---|---|---|
| notify | title/body | Notificación emergente del sistema |
| show_card | title/code | Renderizar tarjetas interactivas |
| current_status | - | Consultar sesión actual/estado ambiental |
| todo_write | todos | Lista de tareas de mantenimiento |
| delegate_agent | task/agent | Delegar tareas a otros asistentes |
| subagent_task | task | enviar subtareas |
| translate | text/target | AI Traducción(skill darse cuenta) |

### 3.7 generación de medios

| Herramientas | parámetros | Descripción |
|---|---|---|
| generate_image | prompt | AI imagen cruda |
| generate_video | prompt | AI vídeo en bruto |
| generate_qr_code | text | Generar código QR |
| list_stickers / send_sticker | id | Emoticones(En desuso,Retención de datos) |

### 3.8 automatización del navegador

| Herramientas | parámetros | Descripción |
|---|---|---|
| browser_navigate | url | Abrir página del navegador |
| browser_click | ref | elemento de clic |
| browser_type | text | Introducir texto |
| browser_extract | - | Extraer contenido de la página |
| browser_scroll_bottom | - | desplazarse hacia abajo |
| browser_get_html | - | Obtener página HTML |

### 3.9 espacio de trabajo(Workspace)

| Herramientas | parámetros | Descripción |
|---|---|---|
| workspace_list | path | directorio de lista |
| workspace_read | path | leer archivo |
| workspace_write | path/content | escribir archivo(Cubrible/anexar) |
| workspace_delete | path | Eliminar archivos |
| workspace_mkdir | path | Crear directorio |
| workspace_move | from/to | moverse/Cambiar nombre |

### 3.10 tareas programadas

| Herramientas | parámetros | Descripción |
|---|---|---|
| scheduled_task_create | cron/action espera | Crear una tarea programada cron |
| scheduled_task_list | - | enumerar tareas |
| scheduled_task_update | id/... | tarea de actualización |
| scheduled_task_delete | id | Eliminar tarea |
| scheduled_task_execute | id | Ejecutar inmediatamente |
| scheduled_task_get_history | id | historial de ejecución |

### 3.11 entorno de ejecución

| Herramientas | parámetros | Descripción | riesgo |
|---|---|---|---|
| execute_javascript | code | Ejecución JS en espacio aislado | NORMAL |
| execute_node_script | code / timeout_ms | Tiempo de ejecución incorporado de Node.js para ejecutar JS (capacidades completas: archivo/red/Proceso hijo; puede requerir el paquete npm) | HIGH |
| execute_python_script | code / timeout_ms | Script de ejecución en tiempo de ejecución Python 3.14 incorporado (biblioteca estándar completa:ssl/sqlite3/zlib/json/re; Lectura y escritura de archivos, red; Los paquetes de Python puros de terceros se pueden instalar mediante pip). Asignar resultado a variable `result` | HIGH |
| execute_shell | command | Shell ejecución de comando(**No en la lista blanca por defecto**,HIGH riesgo,Los usuarios deben habilitarlo manualmente en la página de administración de herramientas.) | HIGH |
| read_file / write_file | path/content | Lectura y escritura de archivos Sandbox(Ver sección de Habilidad) | - |

## Cuatro.Skill Lista completa(30 incorporados)

### 4.1 clase de archivo
- read_file: Leer texto de la zona de pruebas(límite superior 1MB,path relativamente filesDir,Admite segmentación de desplazamiento/longitud por línea)
- write_file: Escribir archivo de zona de pruebas(path/content/append)
- list_dir: directorio de lista
- delete_file: Eliminar archivos(Admite procesamiento por lotes de rutas)
- file_exists: El juicio existe
- file_download: Descargar archivo a la zona de pruebas
- read_public_file: Leer contenido:// archivo URI(cooperar list_public_files)
- save_to_downloads: Guardar en el directorio de descarga del sistema
- list_public_files: Listar archivos públicos(La salida contiene uri= Se puede alimentar directamente read_public_file)

### 4.2 Red e información
- http_get: HTTP GET(url/headers,límite de respuesta 1MB)
- http_post: HTTP POST(url/body/content_type/headers)
- web_search: motor de búsqueda(query/max_results 1-10 Predeterminado 5)
- web_fetch: Capturar texto de página web(url,Máximo 200.000 caracteres/Devolver 50.000)
- knowledge_search: Búsqueda de texto completo en la base de conocimientos(query/threshold Predeterminado 0.3;devdoc La documentación interna también está aquí.)
- arxiv_search: arXiv búsqueda de papel(query/max_results)

### 4.3 Autoexpansión y gestión
- install_skill: **LLM Habilidades de creación en sitio.**(skill_json;implementationKotlin Limitado a 8 implementaciones de lista blanca)
- list_skills: Listar todas las habilidades(Incorporado/Usuario/Estado de inicio y parada)
- uninstall_skill: Habilidades de desinstalación(id/name)
- disable_skill: Desactivar habilidades
- task_plan: Haz un plan de misión
- update_plan_step: Actualizar los pasos de planificación

### 4.4 Agent chatear con grupo
- delegate_agent: Delegar otros asistentes(task/agent Seleccionar objetivo)
- channel_reply / channel_pass / channel_read_context: Conjunto de tres piezas de chat grupal(hablar/saltar/leer contexto)
- agent_phone: Modo celular(conversacional)

### 4.5 Otros
- generate_image: AI imagen cruda
- translate: AI Traducción
- list_stickers / send_sticker: Emoticones(En desuso)

## 5. Cómo elegir herramientas para modelos.

1. **Determinar el tipo primero**: Lo que quieren los usuarios es"Consultar información/realizar operaciones/Generar contenido/Administrar datos"¿Qué categoría?
2. **capacidad de emparejamiento**: Consulta información en tiempo real→web_search;Leer páginas web específicas→web_fetch;escribir archivo→write_file;Sincronización→scheduled_task_*;confiar→delegate_agent
3. **Corrección de parámetros**: Pasar parámetros estrictamente según el esquema.,La herramienta de parámetros faltantes informará un error
4. **Principio de una llamada**: No lo desmontes varias veces si se puede completar de una sola vez.;Si el resultado no es suficiente, realice ajustes adicionales.
5. **El fracaso se explica con sinceridad.**: La herramienta devuelve error/tiempo de espera,decir la verdad,No lo inventes

## 6. Preguntas frecuentes Q&A

1. **"¿Por qué no puedo utilizar algunas herramientas?"**: Herramientas filtradas por lista blanca de asistentes(toolIdsJson Completamente abierto por defecto);execute_shell Desactivado de forma predeterminada(alto riesgo),Debe habilitarse manualmente en la página de administración de herramientas.
2. **"¿Cuál es la diferencia entre herramientas y habilidades?"**: Las herramientas son capacidades fijas registradas en el sistema.;Skill es un paquete extensible(Personalizable e instalable),Implementación de herramientas de reutilización subyacentes.
3. **"MCP Cómo utilizar la herramienta"**: La herramienta se registra automáticamente después de conectarse al servidor MCP.(prefijo mcp_{serverId}__),Llamado como una herramienta local.
4. **"El modelo dice que cierta herramienta no está disponible."**: Consulta la lista blanca de asistentes(toolIdsJson/skillIdsJson/mcpServerIdsJson)¿Contiene;MCP Primero debes conectar y vincular al asistente.
5. **"execute_shell ¿Se puede abrir?"**: puede,Habilitar en la página de administración de herramientas;Pero puede ejecutar comandos arbitrarios.,Úselo bajo su propio riesgo(Se recomienda abrirlo sólo en un entorno de confianza.).

## 7.LLM Puntos de llamada

- Los índices de capacidad de la herramienta se enumeran en la lista blanca del asistente en las indicaciones del sistema.,Consistente con el esquema de herramientas.;Lo que ves se puede ajustar
- Equipo involucrado/comunicación/Número de cuenta/Herramientas para cambios irreversibles,Debe confirmar la intención del usuario antes de realizar ajustes.
- Se debe priorizar las herramientas de búsqueda con web_search para posicionamiento y luego web_fetch para lectura intensiva.
- El acceso a archivos solo se aplica al directorio de archivos/espacio de trabajo del espacio aislado
- Cuando los usuarios preguntan a Muse sobre sus propias funciones,Verifique primero Knowledge_Search devdocs,No lo inventes de memoria
