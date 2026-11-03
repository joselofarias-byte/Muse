# Enlace 10: Documentación / Salud del código abierto / Informe de revisión de cobertura de prueba

Fecha de revisión:2026-10-10(Basado en la línea principal 24bf247f, limpieza del árbol de trabajo `.audit/`)
Revisado por: Documentación/prueba/Subagente de enlace de salud de código abierto

## Resumen

esta ronda `E:\1Project\Muse\1muse` 10 documentos del directorio raíz,426 aplicación/src/prueba de prueba,9 prueba de Android,.gitignore/Higiene del almacén,NOTICE/LICENSE/lista de permisos,`gradle/libs.versions.toml` Superficie de dependencia y almacén independiente del sitio web oficial. `E:\1Project\Muse\museai.ltd` Se realizó una revisión exhaustiva. Método: Leer el texto completo del documento. → Grep Posicionamiento v2.5.8 cinco funciones (A2A/Tasker/Gancho de complemento/Exportación de diagnóstico/platform_selfcheck) código de producción → Glob/Grep Verificar la cobertura de la prueba → Comando git de solo lectura para verificar el estado de seguimiento → Consulte el enlace de la versión archivo por archivo en el almacén del sitio web oficial.

Conclusión general:**v2.5.8 Cinco nuevas características de"Documento 0 Cobertura + Prueba 0 Cobertura"doble vacío**——A2A,Tasker, complemento Hook, exportación de diagnóstico,platform_selfcheck Un total de 0 pruebas de comportamiento en app/src/test y app/src/androidTest (la única excepción es la prueba de línea roja a nivel de campo de GenerationTrace) y el Capítulo 19 del Manual del usuario.README Los documentos de desarrollo bilingües en la aplicación y los documentos del sitio web oficial no mencionan ninguno de ellos. También se descubrió que el almacén del sitio web oficial DEPLOY.md había escrito la IP del servidor y la ruta local de la clave privada SSH en el historial de git. Desorden del directorio raíz (compile*.log etc.) verificar**todavía no**Seguimiento por git, sin problemas.

**Descubra las estadísticas:P0 = 0,P1 = 8,P2 = 5, 13 artículos en total.**

Los 3 más importantes:F10a(La información de credenciales del sitio web oficial DEPLOY.md se almacena en la base de datos),F2(A2A JSON-RPC Prueba cero del protocolo + defectos del código de error + contaminación de concurrencia),F6(Busque 13 proveedores que analizan la prueba cero + devdocs LLM Knowledge Source Drift para"3 casa").

---

## lista de descubrimiento

### F1. Línea roja de contenido zip de exportación de diagnóstico sin prueba de nivel de combinación (P1, credibilidad: alta)

- Ubicación:`app/src/main/java/io/zer0/muse/diagnostic/DiagnosticExporter.kt:38-74`;`app/src/test/java/io/zer0/muse/diagnostic/GenerationTraceTest.kt:77-86`
- Evidencia:
  - `DiagnosticExporter.exportZip`(:38-74) Empaque tres artículos:`generation-trace.jsonl` **tal cual** `copyTo(zos)`(:43-45, no verificado por fila),`debug-log.txt` para `DebugLogStore.exportToFile()` Embalado como está (:66-70),`device-info.txt`.
  - `DebugLogStore` es el sumidero global del registrador (`DebugLogStore.kt:23-27`:"Logger.sink Empuje automáticamente cada registro para [log]"), almacenando en búfer los 1000 registros.
  - La única prueba de línea roja `GenerationTraceTest.kt:77-86` sólo afirmar **TraceRecord Nombre del campo serializado** no contiene `content/prompt/message/text/body/apikey`;derecha `DiagnosticExporter` mismo (Grep `DiagnosticExporter` en app/src/test → cero aciertos) y comprime tres artículos**nivel de valor**Escanear (fragmentos del cuerpo que pueden aparecer en el texto del mensaje de registro,token,key) sin ninguna afirmación.
- Impacto: Declaración de línea roja (DiagnosticExporter.kt:28 "No incluye texto de mensaje,prompt,API key Texto original") dependencia"Cada campo aguas arriba está limpio"Esta premisa no probada;Logger Una vez que el mensaje se mezcla con un mensaje de excepción (que a menudo contiene URL/parámetros)/key Fragment) o el archivo de seguimiento se escribe mediante otras rutas, la exportación se filtra y no hay ninguna interceptación de prueba.
- Recomendación: Compensar la prueba Robolectric——Construya una cadena que contenga cadenas confidenciales conocidas (texto,`sk-…`,sessionId original) escena, llamando `exportZip`, descomprima la afirmación zip archivo por archivo de que las cadenas confidenciales no aparecen (escaneo de nivel de valor, no solo nombres de campo).

### F2. A2A JSON-RPC Pruebas de protocolo cero de punto final + fallas en la implementación del código de error + contaminación de concurrencia (P1, credibilidad: alta)

- Ubicación:`app/src/main/java/io/zer0/muse/web/WebServer.kt:648-707`;`app/src/main/java/io/zer0/muse/web/a2a/A2aProtocol.kt:117-124`;`app/src/main/java/io/zer0/muse/web/a2a/A2aHandler.kt:59-124`
- Evidencia:
  1. **Prueba cero**:Grep `A2a|a2a` en aplicación/src/prueba con app/src/androidTest → 0 golpear.v2.5.8 Protocolo emblemático (Agent Card + JSON-RPC, vinculado al puerto web LAN) sin solicitudes con formato incorrecto/código de error/Prueba de distribución del método.
  2. **`INVALID_REQUEST` código muerto**:`A2aProtocol.kt:119` definición `const val INVALID_REQUEST = -32600`, la única referencia en todo el almacén es la definición (Grep `INVALID_REQUEST` → Sólo 1 acierto).`handleA2aJsonRpc`(WebServer.kt:649-652) poner**todo**Falló la decodificación (incluido"JSON válido pero falta el campo de método"MissingFieldException lanzada) siempre se asigna a `PARSE_ERROR(-32700)`. Por JSON-RPC 2.0,"No es un objeto de solicitud legítimo"debería devolver -32600,-32700 solo"JSON El análisis en sí falló".
  3. **id Uso mixto de semántica de campo (hipótesis por verificar, nivel de confianza)**:GetTask(WebServer.kt:663-664) y Cancelar Tarea (:670) para envolver JSON-RPC `id`(código de asociación de solicitud) se usa directamente como identificación de tarea, y la identificación de tarea de SendMessage proviene de `params.message.messageId`——dentro del mismo punto final"Tarea id"La fuente es inconsistente. Los clientes externos A2A colocan la identificación de la tarea en los parámetros de acuerdo con la especificación y no podrán revisar la tarea.
  4. **contaminación concurrente**:`A2aHandler.sendMessage`(A2aHandler.kt:59-124) Sin exclusión mutua——Dos solicitudes simultáneas operarán en el mismo `chatViewModel`(:74 switchSession / :87-88 `updateInput(text); send()`), se interrumpirá el cambio de sesión de la solicitud B y la escritura del cuadro de entrada./Generación y lectura de la solicitud contaminada A (:104 Obtener el último mensaje del asistente según la sesión actual).
- Impacto: el código de error devuelto por la solicitud con formato incorrecto no es coherente con el protocolo; se llaman varios agentes simultáneamente (A2A El uso típico) produce diafonía; No hay línea de defensa de retorno.
- Sugerencia: suplemento `A2aJsonRpcProtocolTest`(JSON con formato incorrecto/Método faltante/Parámetros faltantes/Método desconocido/GetTask no existe → Afirme la matriz de códigos de error) y agregue un único mutex de vuelo a sendMessage (o haga cola por sessionId).

### F3. TaskerTriggerReceiver Prueba cero + mensaje sin longitud/barandillas presupuestarias (P1, credibilidad: alta)

- Ubicación:`app/src/main/java/io/zer0/muse/intent/TaskerTriggerReceiver.kt:42-48, 70-76, 97-98`;`AndroidManifest.xml:262-269`
- Evidencia:
  1. **Prueba cero**:Grep `(?i)tasker` en app/src/test → 0 golpear.intent Analizar límites (action no coincide,prompt desaparecido/en blanco,extra Nombre de clave incorrecto) no tienen afirmaciones de comportamiento.
  2. **Sin truncamiento de la capa de aplicación**:`onReceive`(:44)`getStringExtra(EXTRA_PROMPT)?.trim()` Luego ingrese directamente toda la cadena `chatService.completeText`(:70-76) y tal cual `appendMessage` caído (:97-98). El enlace principal tiene la canalización ContextBudget/compresión, que esta ruta omite por completo.——Un extra de unos 500KB (Binder Entrega directa dentro del límite) se enviará al modelo en su totalidad para su facturación.
  3. **exported=true Ya tengo una declaración de compensación**(manifest :262-264 Nota: Es necesaria la transmisión entre aplicaciones + no otorga la ejecución de la herramienta)——Esto no es un descubrimiento según la disciplina de revisión; sin embargo, el límite de seguridad declarado en la anotación sólo cubre"Operación del equipo", no cubierto**Abuso de tarifas**: Cualquier aplicación de terceros puede transmitir para activar la finalización sin restricciones (sin verificación de llamadas, sin limitaciones ni cambios), y las aplicaciones maliciosas pueden agotar el saldo de API del usuario (se supone que debe verificarse, la credibilidad es media)——Inferencia estática, no se ha medido el límite superior de la frecuencia de transmisión).
- Impacto: El modelo de transferencia rápida y muy largo genera tarifas elevadas; pruebas externas/Volver a estar indefenso.
- Sugerencia: Prueba única del receptor suplementario (Robolectric Acción medible/límites adicionales) y agregar un límite de longitud de mensaje antes de ejecutarTrigger (como truncamiento o rechazo de caracteres de 4k); considere agregar limitación a fuentes fuera del mismo paquete.

### F4. Gancho enchufable(onPromptFinalize)Prueba cero de la matriz de excepción(P1, credibilidad: alta)

- Ubicación:`app/src/main/java/io/zer0/muse/data/plugin/PluginPromptFinalizeHook.kt:47-72`;`app/src/main/java/io/zer0/muse/data/plugin/PluginHookBridge.kt:33-69`
- Evidencia:
  1. **Prueba cero**:Grep `PluginPromptFinalize|WebViewSkillEngine` en app/src/test → 0 golpear.fail-open Contrato (:34 Comentario"falló/Conservar el historial original cuando la devolución sea ilegal.") tiene 4 ramas: el motor lanza una excepción (:51-55),`SkillEngineResult.Error`(:56-59), devolver error de análisis (:61-65), devolver el contexto histórico vacío de la defensa aérea (:66-69)——Todas las afirmaciones de no acción.JS Las excepciones laterales están envueltas en `__error`(:85-87), no existe ninguna prueba sobre cómo presentarlo.
  2. **Sincronización de simultaneidad de rutina dual (hipótesis por verificar, credibilidad media)**:`start()`(PluginHookBridge.kt:33-39) empezar dos `appScope.launch`(collect suscripción + primera sincronización),`sync()` de unregister-all → re-register secuencia (:42-69) Sin exclusión mutua; cuando se entrelazan dos corrutinas, la misma ID se cubre de manera idempotente para que el estado final sea consistente con una alta probabilidad, pero puede aparecer instantáneamente una ventana de falta de gancho en el registro.HookRegistry.register Se confirma la semántica de anulación del mismo ID (HookRegistry.kt:42-46), por lo que el peligro se limita a la ventana instantánea.
  3. La superficie de inyección está cerrada:`buildCallScript`(:75-89) en el contenido del sutra `JsonPrimitive` codificación,role lista blanca `wireName()`——Si no hay ningún problema con la inyección, no se informará.
- Impacto: errores comunes de los autores de complementos (lanzar excepciones/devuelve nulo/devuelve una matriz vacía) degenerará silenciosamente en"Preservar la historia original.", el contrato no tiene bloqueo de prueba y la refactorización futura romperá fácilmente la semántica de apertura fallida.
- Sugerencia: suplemento `PluginPromptFinalizeHookTest`(4 rama de apertura fallida + reescritura normal + lista blanca de roles), use un código de entrada falso para ejecutar WebViewSkillEngine o extraiga la función analítica pura para realizar pruebas `parseHistory`.

### F5. platform_selfcheck prueba cero (P2, credibilidad: alta)

- Ubicación:`app/src/main/java/io/zer0/muse/tools/PlatformSelfCheckTool.kt`(archivo completo);`AppKoinModule.kt:389`(Registro)
- Evidencia:Grep `platform_selfcheck|PlatformSelfCheck` en app/src/test → 0 golpear.`execute`(:44-107) empalme de salida (node -v Rama de resultados: 55-60,npm No listo: 78, rama de estado del canal: 90-92, rama de conclusión: 97-105) no tienen aserciones.commit 24bf247f La herramienta se envía únicamente con actualizaciones de ktlint-baseline.
- Impacto:v2.5.8 Redacción publicitaria de la propia función de resolución de problemas./La regresión de ramas no tiene línea de defensa; Los usuarios sacan conclusiones erróneas cuando lo utilizan para solucionar problemas (como"Listo en tiempo de ejecución ✓"Falso positivo) Sin intercepción de prueba.
- Sugerencia: extraiga el resultado y ensamblelo para que sea inyectable `execNode` Prueba de complemento de unión de función pura del resultado (Node existir/desaparecido × exec éxito o fracaso × matriz de autorización de canales).

### F6. Buscar proveedores de API 13 analizando cero pruebas + devdocs (LLM fuente de conocimiento) se desplaza hacia"3 casa"(P1, credibilidad: alta)

- Ubicación:`app/src/main/java/io/zer0/muse/web/WebSearchProviders.kt`(13 Clases de proveedores:Baidu:40 / SearXNG:90 / Tavily:151 / Bing:532 / Jina:590 / Zhipu:657 / Brave:721 / Serper:784 / Bocha:849 / Metaso:918 / Exa:984 / Firecrawl:1058 / Perplexity:1129);`app/src/main/assets/devdocs/web_search_guide.md:13, 59`
- Evidencia:
  1. **Prueba de análisis cero**:Grep `WebSearchProvider|TavilyProvider|SearXNGProvider|BraveSearch|SerperSearch|ExaSearch|JinaProvider|Bocha|Metaso|Perplexity|Firecrawl` en app/src/test → 0 golpear (WebSearchCoordinatorTest Pruebe solo la capa de orquestación, use un servicio falso). cada familia `parseResults` Todas son extracciones de rutas JSON escritas a mano (como Brave :758-773 `root["web"]["results"]`,Serper :824-834 `root["organic"]`), cualquier campo ascendente renombrado/Cambios anidados → `?: return emptyList()` **resultado vacío silencioso**, ni el usuario ni el LLM lo saben.
  2. **devdocs deriva**:`devdocs/web_search_guide.md:13`"La búsqueda es implementada por tres proveedores.…Bing(Predeterminado,Gratis key)/ SearXNG(Autohospedado,Gratis key)/ Tavily(necesidad API key)",:59"Nombre de soporte: SearXNG/Tavily/Bing"——Seriamente inconsistente con el Código 13. El encabezado del archivo declara"Basado en la implementación real del código fuente, está prohibido inventar cosas que no existen. provider"(:5), y es la verdadera fuente de consulta de LLM a través de Knowledge_search: el usuario pregunta en la aplicación."Buscar qué servicios son compatibles"Obtendrá respuestas obsoletas.
  3. Comparar: directorio raíz `guia-api-de-busqueda.md` Enumere 13 casas una por una (:3, :176-190), consistente con el código——La documentación contradice los devdocs.
- Impacto:13 Cualquier revisión de cualquier API comercial será silenciosamente inválida y no habrá una línea de defensa de regresión;LLM La fuente verdadera enseña mal.
- Sugerencia: agregar uno por cada una de las 13 empresas"Muestra JSON fija → Analizar resultados"Prueba de análisis (se pueden tomar muestras de ejemplos de respuestas a documentos oficiales de varias empresas); reescriba sincrónicamente devdocs/web_search_guide.md.

### F7. La documentación del usuario no cubre las cinco funciones de v2.5.8 (P1, credibilidad: alta)

- Ubicación:`manual-de-usuario.md`(939 Búsqueda de texto completo);`README.md:136-246`;`README_EN.md`;`app/src/main/assets/devdocs/*`(17 artículos)
- Evidencia:
  1. **Manual de usuario**:Grep `A2A|Tasker|Exportación de diagnóstico|platform_selfcheck|onPromptFinalize|Hook` → solo :386"Búsqueda más reciente de diagnóstico"(irrelevante). Vacantes específicas:§14.2 sección de complementos (:664-671) no menciona que se puedan declarar complementos `hooks`/`onPromptFinalize`;§19.1 Versión web (:840-846) no menciona el punto final A2A (`/.well-known/agent-card.json` con `/a2a`,JWT autenticación);§10/11 No mencionado en el capítulo de herramientas. `platform_selfcheck`;§19 No hay entrada de activación de transmisión de Tasker (nombre de la acción/extra El uso solo existe en release_notes.md:7).
  2. **README bilingüe**:Cinco grupos de funciones de un vistazo (README.md:136-246) Cobertura cero para A2A/Tasker/plug-in Hook/autoprueba de exportación de diagnóstico;`README_EN.md` Cobertura cero isomorfa (Grep Pulsa 0).
  3. **17 documentos de desarrollo en la aplicación**:Grep cinco palabras clave → Sólo 2 visitas irrelevantes (tools_guide.md:87 "Diagnóstico de red",assistant_capability_playbook.md "Proceso de autocomprobación");plugin_authoring_guide.md No hay ninguna sección ganchos/onPromptFinalize (Grep 0 golpe)——A la Guía del autor del complemento le faltan puntos de extensión principales v2.5.8.
  4. `release_notes.md:2-12` Las entradas de la v2.5.8 están completas (una buena señal);`Función de software.md` Ya es una página de salto de 3 líneas (sin deriva).
- Impacto:v2.5.8 si"Acceso a protocolo externo y solución de problemas"La versión del tema, pero sus usuarios (Tasker usuario,A2A Las personas que llaman, los autores de complementos y los usuarios que solucionan problemas) no pueden encontrar ninguna guía de operación en el almacén o la aplicación.——release_notes es la única fuente de conocimiento, exactamente lo que DEDUPE dirección 2 nombró"La documentación desde la perspectiva del usuario no puede enseñar".
- Recomendación: Manual de usuario §14.2 Gancho de parche,§19.1 Suplemento A2A,§10 Se agregó platform_selfcheck y se agregó la sección de activación de Tasker;plugin_authoring_guide.md Capítulo de ganchos agregado;README"Multiasistentes y ecología."Crea un A2A/Tasker.

### F8. CONTRIBUTING.md Referencia a AGENTS.md que no existe (P2, credibilidad: alta)

- Ubicación:`CONTRIBUTING.md:49`
- Evidencia:`"Siguiendo el directorio raíz del repositorio AGENTS.md(Las disciplinas de ingeniería y los acuerdos de colaboración están sujetos a este documento.)"`——AGENTS.md en realidad no existe en el directorio raíz del almacén (`Get-ChildItem` No existe tal archivo en la lista del directorio raíz;`git ls-files AGENTS.md` vacío; posición completa Glob `AGENTS.md` 0 resultados).
- Impacto: Colaboradores encontrados por guía"Este documento prevalecerá"El documento de disciplina de ingeniería fallará y entrará en conflicto con la aplicabilidad del resto del contenido CONTRIBUYENTE.
- Recomendación: elimine esta línea o, en su lugar, señale un documento de disciplina existente real (como docs/ENGINEERING_DISCIPLINE.md si se conserva).

### F9. NOTICE Nuevas dependencias con v2.5.x no cubiertas por el manifiesto de licencia en la aplicación (P1, credibilidad: alta)

- Ubicación:`NOTICE:40-68`;`app/src/main/assets/licenses/manifest.json:5-6, Texto completo`;`gradle/libs.versions.toml:110-112`;`app/build.gradle.kts:344-347`;`tools/runtime-pack/README.md:63-77`
- Evidencia:
  1. **backdrop / liquid desaparecido**:`libs.versions.toml:111-112` Declaración `io.github.kyant0:backdrop 1.0.6` con `io.github.fletchmckee.liquid 1.1.1`,`app/build.gradle.kts:346-347` Introducido y realmente utilizado (`LiquidGlass.kt:28-29` import liquid,`ChatScreen.kt:81` / `GroupChatDetailScreen.kt:75` import liquefiable). Pero el AVISO depende del manifiesto (:44-68) y licenses/manifest.json no tienen estas dos bibliotecas.
  2. **Versión del manifiesto de licencia bloqueada**:`licenses/manifest.json:5-6` `"generatedAt": "2026-09-27", "appVersion": "2.1.0"`——La lista está dentro de la aplicación."Licencia de código abierto"La fuente de datos de la página (LicenseRepository.kt:14, :71), la versión actual es 2.5.8, que está detrás de 4 versiones menores (se agregaron fondos/líquidos, etc. durante el período).
  3. **Node El componente de tiempo de ejecución no está en el manifiesto.**:APK distribuido Node 24.18.0/npm/OpenSSL/ICU/c-ares/sqlite/zlib(runtime-pack README:63-74) Sólo hay una declaración general en runtime-pack README:76-77 ("Cada componente conserva su licencia original de código abierto."),licenses/manifest.json No los contiene en absoluto;assets/licenses/ Sólo hay 5 copias del texto de la Licencia Universal a continuación (apache-2.0/mit/bsd-2/bsd-3/epl-1.0),MIT/ISC componente(OpenSSL Para Apache-2.0,c-ares para el MIT,zlib licencia zlib), el texto y la atribución correspondientes no se distribuyen con el paquete.
- Impacto:GPL v3 La distribución del proyecto muestra una lista incompleta de licencias de terceros para los usuarios finales.——Existen tanto fallas de cumplimiento legal como violaciones a la política de autoconstrucción del proyecto."Página de licencias en la aplicación"La intención original del mecanismo.
- Sugerencias:licenses/manifest.json Complemente el fondo/líquido e incluya el mecanismo de actualización de appVersion en la lista de lanzamientos (release El proceso ya tiene el gancho generate_release_manifest para colgar);Node Los componentes de tiempo de ejecución (al menos Node/OpenSSL/ICU/c-ares/zlib) se agregan al manifiesto y al texto de licencia correspondiente.

### F10a. El almacén del sitio web oficial DEPLOY.md escribe la IP del servidor y la ruta de la clave privada SSH en el repositorio (P1, credibilidad: alta)

- Ubicación:`E:\1Project\Muse\museai.ltd\DEPLOY.md:3-4`(Seguimiento,`git ls-files` Confirmar;commit 839dfb3 "docs: Documentar las credenciales de implementación y el proceso de implementación de Windows listo para ejecutar")
- Evidencia:
  ```
  Servidor:`root@117.72.212.102`(Ubuntu / nginx, directorio raíz del sitio `/var/www/museai.ltd`)
  SSH Clave privada:`C:\Users\21192\Downloads\HANA.pem`(RSA,`ssh -i` Usar directamente;**Ruta local, no la almacene en la base de datos./No le digas a nadie**)
  ```
  Autoetiquetado de archivos"no almacenar"Pero ha sido almacenado y rastreado (contradictorio); implementar bloque de comando (:19-46) contiene un enlace completo de operación y mantenimiento con calibre raíz. El repositorio actualmente no tiene git remoto (DEPLOY.md:6 Léame"No git push"), pero este directorio es un repositorio git independiente, y tanto el historial como el árbol de trabajo contienen esta información.
- Impacto: una vez que este directorio se envíe a cualquier público/El alojamiento semipúblico (mismo disco y directorio que el repositorio principal, gran posibilidad de mal funcionamiento), la ubicación de la raíz del servidor de destino y la clave privada se hacen públicas, convirtiéndose en una superficie de ataque dirigida.
- Recomendación: eliminar DEPLOY.md del seguimiento (`git rm --cached` + .gitignore), reescrito en una versión insensible del manual de implementación; Se recomienda rotar la autorización SSH en el lado del servidor (porque la inteligencia ya existe en varias copias del historial local).

### F10b. Página de descarga del sitio web oficial./La versión codificada de la página de inicio se detiene en v2.3.0 (P2, credibilidad: alta)

- Ubicación:`E:\1Project\Muse\museai.ltd\download\index.html:53-54`;`index.html:47`
- Evidencia:`<span id="dl-version" ...>v2.3.0</span>`,`<span id="dl-date" ...>2026-09-27</span>`(Descargar página); El héroe de la página de inicio es v2.3.0. Versión actual v2.5.8 (release_notes.md:2). camino normal `js/main.js:151` usará `/api/latest.json` Anulación, pero cadena alternativa (main.js:153-156 Comentario"Todo falla: mantenga la versión codificada") bajo——`/api/` La falla de caché del mismo origen + el tiempo de espera nacional de api.github.com es una situación común reportada en main.js:86 readme——Los usuarios ven una página que tiene 5 versiones detrás. El botón de descarga href apunta a la página de lista de lanzamientos (download/index.html:62-72), la cadena no estará muerta.
- Impacto: el número y la fecha de la versión caducada se muestran en un estado críptico, lo que debilita la percepción de las actualizaciones; es un problema de higiene en la capa de visualización (el enlace de datos en sí tiene sincronización systemd del lado del servidor y el diseño es razonable).
- Sugerencia: El script de lanzamiento actualiza la versión codificada en conjunto o cambia el valor predeterminado a"última versión"Texto de marcador de posición.

### F10c. Los 10 documentos en el sitio web oficial tienen cobertura cero de las funciones v2.5.8 (P2, credibilidad: alta)

- Ubicación:`E:\1Project\Muse\museai.ltd\docs\**\*.html`(10 Guía del módulo + Inicio rápido + Preguntas frecuentes)
- Evidencia: escaneo completo del directorio `A2A|Tasker|Exportación de diagnóstico|Autoprueba de plataforma|onPromptFinalize` → 0 golpear. El centro de documentos del sitio web oficial y el manual del usuario del almacén principal derivan simultáneamente (F7), los sitios web oficiales externos tampoco pueden enseñarlo.
- Impacto: el sitio web oficial es la primera opción para los usuarios domésticos (README.md:83"Conexión directa nacional, recomendable"), la documentación no está actualizada con las características de la nueva versión.
- Recomendación: Los documentos del sitio web oficial y el manual del usuario deben complementarse con A2A/Tasker/plug-in Hook/entradas de exportación de diagnóstico (se pueden organizar junto con F7).

### F11. guia-api-de-busqueda.md Para documentos huérfanos (cero referencias en todo el almacén) (P2, credibilidad: alta)

- Ubicación:`guia-api-de-busqueda.md`(190 Está bien,commit 24bf247f entrega)
- Evidencia:Grep `BuscarAPIObtener tutorial` YuQuanCang (incluido README/Manual de usuario)/devdocs/Página de configuración Kotlin)→ 0 golpear;Grep `Obtener tutorial|BuscarAPI` en app/src/main → 0 golpear. El documento en sí: 5 da la entrada."ajustes → Buscar(Web Buscar)", pero no existen documentos ni portales de aplicaciones que enlacen a este tutorial de aplicaciones de 13 proveedores.
- Impacto:commit el titulo es"API de búsqueda integrada Obtener tutorial", pero ni el usuario ni LLM tienen una ruta para descubrirlo——El contenido es en vano y se superpone con la deriva de devdocs de F6 (devdocs Dice 3, el tutorial dice 13, contradiciéndose).
- Sugerencia: En el manual de usuario §7.1,devdocs/web_search_guide.md Agregue una cita al área de búsqueda de la página de configuración (o combine los puntos en devdocs).

---

## Verifique los elementos conocidos (confirme que no se informen repetidamente)

| Artículos conocidos | Conclusión de esta ronda de verificación |
| --- | --- |
| A cada uno de los cinco paquetes de idiomas distintos del inglés le faltan 101 traducciones | No involucrado en la traducción, no reportado |
| P1 #43"Las pruebas faltantes con el mismo nombre no son prueba de cobertura" | Presiona toda esta ronda"Falta una afirmación de comportamiento clave + evidencia de defectos de implementación"Informe de calibre, teoría del documento sin nombre. |
| SECURITY.md / docs La estrategia de seguimiento requiere decisiones sobre la propiedad del almacén | No se hizo ningún descubrimiento independiente;**Agregar nueva evidencia**:README.md:298 Ambos con README_EN.md:297 [SECURITY.md](SECURITY.md) Como enlace de entrada público, .gitignore:37 ignora el archivo y no se realiza un seguimiento (`git ls-files` vacío)——Los visitantes del repositorio clonado obtienen un 404 cuando hacen clic. Tenga en cuenta que CONTRIBUTING.md está en .gitignore:35-36"ignorar+exención"reglas de par,SECURITY.md Simplemente escribe e ignora (:37) Sin línea de exención |
| CDN La imagen 404 y la renovación del certificado son operaciones y mantenimiento externos. | El último envío 84d3022 en el sitio web oficial se ha cambiado al enlace directo de lanzamientos de GitHub (main.js:111-121),CDN El vínculo se ha retirado y el estado del elemento conocido es bueno; no se ha informado en esta ronda. CDN |
| La exportación de diagnóstico no registra texto/key es la línea roja establecida (globalmente conocida 13) | No reportado"línea roja no alcanzada";F1 es una linea roja**Faltan pruebas de nivel de combinación**, que es una dirección de inspección expresamente permitida por la línea base |
| El lanzamiento del complemento sandbox es una decisión del producto v2.5.8 (conocido globalmente 12) | F4 Solo implica la falta de pruebas del sistema Hook y no involucra la zona de pruebas en sí. |
| Disciplina de documentos (README Sin emoji, hechos versus código) sincronizados con los documentos de entrega | Cumplido; reportado esta ronda es**brecha de cobertura**(v2.5.8 función sin capítulo), infracción no disciplinaria |
| detekt/ktlint Inventario, falta de aceptación de equipos y lagunas en la traducción | Ninguno tocado |

**Dudas despejadas después de la verificación.**(Se ha investigado pero se ha llegado a la conclusión de que no hay problema):
- directorio raíz `baseline_compile.log` / `compile2-4.log` / `app/hs_err_pid46000.log`:`git ls-files '*.log'` está vacío,`git check-ignore -v` Confirma que todos están .gitignore:15 `*.log` Portada——**No rastreado, no encontrado**(La respuesta para la dirección de referencia 4 es"Cubierto").
- manual-de-usuario.md / Función de software.md / guia-api-de-busqueda.md / guia-biblioteca-ui.md Todos son rastreados por git;README Imagen de encabezado y todas las capturas de pantalla./En realidad, se mide que la ruta de referencia del código de pago existe.
- `guia-api-de-busqueda.md` 13 Existe una correspondencia uno a uno entre las clases de proveedor de inicio y del código 13 (el número no tiene deriva, la deriva está en los devdocs).
- `.gitattributes`,gradle.properties,ci/(55 seguimiento de archivos),run-all-tests.ps1 Todo rastreado normalmente.
- CONTRIBUTING.md puntiagudo `./ci/run_ci_checks.ps1` existe con cada carril;ci.yml con web-ci.yml rastreado.

## Descripción de métodos y cobertura

- Herramientas:Glob/Grep/Read Principalmente; sólo los almacenes de higiene del almacén y los almacenes del sitio web oficial utilizan el comando git/pwsh de solo lectura (`ls-files`/`check-ignore`/`status`/`log`), no se modificaron archivos.
- Ruta de verificación de cobertura de prueba:app/src/test 426 archivos,app/src/androidTest 9 archivos respectivamente presionando A2A / Tasker / Diagnostic / onPromptFinalize / 13 Inicio nombre del proveedor / platform_selfcheck Seis conjuntos de búsquedas de palabras clave y comprobaciones aleatorias adicionales WebSearchCoordinatorTest,GenerationTraceTest El texto completo confirma sus límites de cobertura.
- F2-3(GetTask ¿El uso de la identificación del sobre como identificación de la tarea viola la especificación A2A 1.0?) y F3-3 (transmisión de terceros sin limitación) son inferencias estáticas, marcadas como hipótesis para ser verificadas y acompañadas de métodos de verificación (la primera se define de acuerdo con los parámetros de tareas/obtención de la especificación A2A, y el segundo en realidad mide la frecuencia de transmisión).
