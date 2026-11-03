# Muse Plan anticipado (actualización continua)

> Propósito: compresión del contexto/Mapa de batalla de continuación después de cambiar de sesión. Las nuevas sesiones leen este archivo primero. + `git log --oneline -30` + `current_status`.
> Actualizar acuerdo: regrese y cambie las secciones "Estado actual" y "Cola" cada vez que se complete un lote.
> Última actualización:2026-09-27 A altas horas de la noche (incluidos los resultados completos de la auditoría) §3.7)

---

## 0. Objetivo general (este lote)

Muse(Android AI compañero, almacén `E:\1Project\Muse\1muse`) Grandes lotes de iteraciones, instrucciones para el usuario:"Empuja en tu dirección;**No entregues la bolsa primero.**(Habrá muchos cambios más adelante), complete la terminal y la automatización".

Temas de este lote:**gran caja de arena → Automatización → terminales → Importar calentamiento**, finalmente elige la oportunidad de hacer las maletas **2.2.1**(o superior) y luego presione hasta el extremo remoto después de la verificación en la máquina real.

---

## 1. Estado actual (enviado/no comprometido/antecedentes)

### Enviado(local,**no empujado**;El extremo remoto está estacionado en el punto de lanzamiento v2.2.0)
Esta ronda de envío de lotes (secuencia de tiempo,git log Marque todo):
- `eb194b2f` escritura de memoria/Eliminar + desinstalar el complemento/Iniciar y detener herramientas
- `7e4e2e1b`/`681d5068`/`7f1841d3`(+`76fb83ee`) Tres etapas de adelgazamiento de herramientas (estrechamiento en capas / recorte de descripción / find_tools)
- `e981c63c` Actualización de licencia de código abierto;`d4793ec9` Comentar para hacer referencia al nombre de la plataforma.
- `899b2ef8` **2.2.0 publicar compromiso**(Se ha enviado la etiqueta principal + v2.2.0,GitHub Release Publicado como Último con 4 recursos)
- **Las siguientes son nuevas incorporaciones después del lanzamiento (no publicadas)**:
  - `44e06a48` Dos correcciones de UI (piense/MOOD/Bloque de reflexión alineación de 16 dp + inserción de barra de estado de capa flotante de búsqueda)
  - `550bc726` PDF Tapa dura de extracción (CappedStringWriter)+ Analizando el desbordamiento de memoria
  - `26a04e91`/`aa21225c`/`14bc1ae7`/`0df12a83`/`e6747de3` ktlint Actualización de línea base (intercalada)
  - `33cffc90` find_tools Modo de descripción general de capacidades (consulta vacía=lista completa)
  - `10471a62` **Gran caja de arena Fase 1**(Extensión de capacidad de puente + cableado de capacidad declarativa)
  - `61b005fb` **Automatización Fase I**(device_shell Canal de línea de comando + ejecución en capas de herramienta de dispositivo)
  - `2046d6fd` **fase terminal uno**(Terminal incorporado xterm.js + puente de sesión + terminal_exec)
  - `0d36158c` **Terminal Fase 2 (motor listo)**(pty.c + Pty.kt + sesión de doble motor, canalización autoescrita)
  - `b5568c77` **Terminal Fase II (Recopilación y Acceso)**(externalNativeBuild ABI dual + alineación de página de 16 KB + pruebas instrumentadas en el lado del dispositivo)
  - `e728cdd5` **Precalentamiento de importación (cuatro clavos)**(Prioridad de cantidad total/solo una reposición/anti-reclamo/imagen según texto)
  - `fe802842` **Corrección de calibración del tamaño de página del terminal**(xterm rows=1 Curación de pantalla negra + capacidades de diagnóstico)
  - `c03bb6ad` **Comentarios mejorados sobre fallos de herramientas**(ToolFailureText:HTTP dirección/Fase de tiempo de espera + nombre del servidor MCP + captura de excepción)
  - `654bc4d4` **GUI Agent anillo**(`ui_agent`: Operación de varios pasos impulsada por visión + protocolo de hacer/finalizar + adelgazamiento de captura de pantalla)
  - `1e277622` **Reparación de enlace de modelo de mensaje activo**(Preferido para tareas en segundo plano"Seleccionar modelo"Cadena de tres niveles + conexión de mensajes activa y agrupación de modelos auxiliares + prueba de comentarios veraces)
  - `562b4adf`/`d55ddb8f`/`ec0eb67e`/`f00c69b7`/`6d6e2e7a` ktlint Actualización de línea base (intercalada)

### GUI Agent anillo —— Terminado ✅(para ser verificado por una máquina real)
- `ui_agent` Herramientas (HIGH + Aprobación obligatoria): captura de pantalla del bucle interno→Compresión→toma de decisiones visuales(do/finish)→ejecutar→Historial de acción; las capturas de pantalla históricas no se incluirán en la siguiente ronda (las capturas de pantalla se reducen);15 paso predeterminado/límite superior 30
- Dependencias: Modelo de ayuda visual (configuraciones→ayuda visual)+ Accesibilidad/Shizuku/Root cualquier canal;VisionBridge Nuevo `askWithImage` Preguntas generales con imágenes.
- Sugerencias para pruebas de máquinas reales: después de configurar el modelo visual, primero use "Abrir configuración"→WLAN"Verificación de tareas tan pequeñas; observar el historial de acciones y el consumo de pasos

### Fase terminal II —— Terminado ✅(Incluyendo verificación de equipos)
- Motor: JNI forkpty autoescrito, pruebas instrumentadas del lado del dispositivo **MuMu 2/2 Pase**(echo/código de salida/tamaño de ventana)
- Compilación: empaquetado dual ABI .so;**16KB alineación de página**(La máquina real PHY110 Android 15 demuestra una falla de apertura directa de alineación de 4 KB)
- UI:xterm Corrección de calibración de tamaño (rows=34/termH=524),**MuMu Evidencia oficial de captura de pantalla**:`echo TERMINAL-OK → TERMINAL-OK` + Mensaje del espacio de trabajo + botón Ctrl-C
- Elementos restantes (no bloqueados):① runProbe Sondas de diagnóstico montadas bajo demanda ② StrictMode hilo principal asegurarComenzó movido a IO ③ IME Vuelva a probar el vínculo del tamaño del área terminal cuando aparezca una ventana emergente

### 🔥 Hallazgos importantes (arreglados/registro)
- **16KB Problema de alineación de página (demostración de máquina real)**: Cuando se ejecutó la prueba instrumentada por primera vez, gradle seleccionó la máquina real PHY110 (Android 15)——`libpty.so` La carga falló; readelf local confirmó nuestra alineación de 4KB (0x1000) y onnxruntime, etc. son todos de 16 KB (0x4000). Enmienda:CMake `-Wl,-z,max-page-size=16384`.Android 15 16KB Se requieren dispositivos de página (y nuevos requisitos de Google Play).
- **PHY110 = La máquina real del usuario.**, estuvo brevemente conectado a adb (ahora desconectado): estará disponible más tarde cuando esté en línea `adb -s <serial>` Vuelva a probar.
- **MuMu NX Múltiples pantallas virtuales**: Cada vez que se reinicia la aplicación, se asigna una nueva identificación de pantalla (3→…→10),adb La pantalla actual debe ser analizada por dumpsys antes de la entrada; la captura de pantalla de la pantalla secundaria es rechazada (Status -2); para ver la pantalla, use la herramienta informática para capturar la ventana y, para obtener las coordenadas precisas, use el volcado uiautomator (no restringido por visualización).
- **Medición real de la interfaz de usuario del terminal (xterm Misterio de pantalla negra)**:Enlace completo (forkpty→empujar→WebView) Después de la verificación paso a paso de los registros, se descubrió que `rows=1`——load Cuando ajuste mide 0 altura y no vuelve a calcular. Enmienda:CSS cambiar `position:absolute;inset:0` + ResizeObserver Ajuste automático + reacondicionamiento del host onLayoutChange.

### reservar
- Reproducir árbol de trabajo:`E:\1Project\Muse\v21-repro`(v2.1.0 construir réplicas,**Contiene 202MB mapping.txt**) y mapeo maestro de almacén (2.2.0,`app/build/outputs/mapping/release/`).
  R8 Huella digital:**2.1.0 = `9e691928…`;2.2.0 = `9031fced…`**. Propósito: Deconstruir la pila de fallos del usuario (consulte §5 jugabilidad).

---

## 2. Cola (ejecución secuencial)

1. ✅ Terminal Fase II/Precalentamiento de importación/Retroalimentación de falla de herramienta/Bucle de agente GUI/Enlace de mensaje activo (consulte §3.1–3.4)
2. ✅ Paquete de tres piezas de infraestructura de la Fase III:Termux Canal/Proceso Independiente Accesible/Pantalla Virtual v1 (§3.5,§3.6;Todos incluyen verificación de equipos)
3. ✅ canal social & Agent Lote de reparación de páginas (enlace de escaneo de WeChat/recuperación de desplazamiento/representación fantasma;§3.6)+ Auditar lote de reparaciones menores (borrado de mensajes activos/reparaciones menores del terminal dos/reparación automática del bloqueo del proveedor/doble archivo raíz de pantalla virtual/firma de liberación del proveedor en bucle cerrado);§3.7)
4. **A la espera de la aceptación del usuario**: Prueba real de escaneo de código WeChat,Agent volver a la página/Enviar prueba real (la nueva compilación tiene MuMu instalado)
5. ✅ **Embalaje 2.2.1 (local)**: Tres variantes totalmente firmadas + validador oficial PASS + humo R8 aprobado (ver detalles §6);**empujar/tag/publicar** Espere a que se complete la prueba real del usuario antes de emitir el pedido.
6. Grupo de candidatos (desplazamiento): pantalla virtual × ui_agent encajar (ojos)+mano);Shizuku terminal de archivos; reprueba de vinculación IME del terminal

---

## 3. Detalles del trabajo en tránsito

### 3.1 Fase Terminal II:PTY(**Da el siguiente paso inmediatamente**)

**Estado actual (se ha completado la primera fase)**:
- `app/src/main/assets/terminal/`:xterm.js + xterm.css + addon-fit.js + terminal.html(sin conexión incorporada)
- `app/src/main/java/io/zer0/muse/terminal/TerminalSessionManager.kt`: sesión de shell de tubería (`/system/bin/sh`, directorio de trabajo=`filesDir/workspace`);`TerminalSessionStore` Singleton a nivel de proceso;**El espacio de sustitución del motor ha sido reservado.**(Listener + ensureStarted/restart/write/close La interfaz se mantiene sin cambios, cambiando el motor PTY solo cambia la parte interna)
- `app/src/main/java/io/zer0/muse/ui/terminal/TerminalScreen.kt`:WebView + xterm.js Mostrar; inferior Componer línea de entrada (eco local `❯ cmd` → escribir entrada estándar); barra superior borrar pantalla/Reiniciar
- Entrada: Configuración → Herramientas → terminales (`MuseRoutes.TERMINAL` = "terminal";`TerminalRoute` in MuseDestinations;SettingsNavGraph cableado)
- `terminal_exec` Herramientas: Aplicación Sandbox Ejecución de comando único (`tools/TerminalExecTool.kt`, registrado en AgentToolsRegistrar)

**Tarea de fase II (se ha enviado el código del motor) `0d36158c`, dejando cableado y verificación)**:
1. ✅ Ya escrito:`app/src/main/jni/pty.c`(Forkpty autoescrito: proceso de construcción/Bloquear lectura y escritura/FIONREAD/tamaño de ventana/close fd, la lectura y la escritura son todas nativas sin reflexión de FileDescriptor)+ `CMakeLists.txt` + `Pty.kt`(loadLibrary falló→available=false→Tubería de reversión automática)+ `TerminalSessionManager` Motor dual + TerminalScreen compatible con PTY (eco local desactivado,Ctrl-C botón)+ HTML Informe de tamaño
2. ✅ **cableado**:app/build.gradle.kts recoger `externalNativeBuild{cmake}` + `ndkVersion="27.0.12077973"` + abiFilters(arm64-v8a/armeabi-v7a);⚠️ Después del acceso**Requerido para todas las compilaciones NDK**(Ya instalado en esta máquina)
3. ✅ **CI Sincronizado**:`.github/workflows/ci.yml` Se ha completado la instalación del SDK de los tres trabajos. `ndk;27.0.12077973 cmake;3.22.1`
4. ✅ Verificación del equipo:instrumented `OK (2 tests)`(MuMu)+ UI Tiro real (captura de pantalla oficial:`echo TERMINAL-OK → TERMINAL-OK` + símbolo del espacio de trabajo + botón Ctrl-C)
5. ✅ Enviado (`b5568c77` Acceso a compilación + `fe802842` Calibración de dimensiones + tareas de ktlint)
6. Aceptación de la máquina real (usuario): programa interactivo, pantalla completa,Ctrl-C

**Candidatos a la tercera fase**:Shizuku terminal de archivos (`ShellService` Ya ahí);Termux RUN_COMMAND Canal de detección.

### 3.2 importar precalentamiento (✅ El usuario ha emitido un pedido.“avanzar integralmente” → Implementación completada, compilación.+La prueba única direccional pasó y se enviará después de la regresión completa)

**Soluciones entregadas a los usuarios ("cuatro clavos")**:
1. Prioridad de cantidad total, clasificación de súper ventana:token presupuesto (modelo contextWindow × Importe total dentro del factor de seguridad); exceder→Truncar por presupuesto e informar al modelo; exceder→Línea de compresión existente
2. Agregar solo una vez: marca de sesión `warmupPending`(Se establece cuando la importación se completa y se borra después de la primera ronda de recuperación exitosa); compartido por sesiones de copia de seguridad y restauración
3. Defensa informativa del sistema"Reclamar": Inyectar instrucciones del SISTEMA"Importa el historial externamente, las respuestas del asistente son generadas por otros modelos y solo responden los mensajes más recientes."
4. Las imágenes solo siguen al texto.

**Conclusión diagnóstica (comprobar al realizarlo)**: Historial de solicitudes = `accessor.messagesSnapshot`(Ventana de paginación de memoria, 50 elementos en la primera pantalla)→ `limitContextWithContext(contextSize)`(`assistant.contextMessageSize`,**Predeterminado 20 artículos**). Dos puntos de corte:`ChatStreamCoordinator.kt:555` con `ChatGenerationController.kt:601`(Dos lugares en el mismo patrón).

**Pasos de implementación**:
- `SessionEntity` agregar `@ColumnInfo(defaultValue = "0") val warmupPending: Boolean = false`;MuseDb Migrar **104→105**
- `ThirdPartyImporter` cada uno `createSession(...)` punto (rikka/kelivo/chatgpt/…Alrededor de 5 lugares) marcado; ruta de recuperación de copia de seguridad (BackupService import) Marca lo mismo
- Coordinator/GenerationController:warmup tiempo `rawHistory` = cantidad total (MessageDao Necesita completar la interfaz de lectura) y omitir `contextSize` Truncar el número de elementos y cambiar el truncamiento del presupuesto del token (TokenEstimator Ya); inyectar información del SISTEMA;**Oferta clara después del éxito**(finalize (en)
- Prueba:warmup Prueba única lógica + regresión de suite existente

### 3.3 Memo para líneas completadas (cambiar puntos de anclaje, evitar la regresión)

- **gran caja de arena**:`tools/script/SkillBridge.kt`(Host + 12 acción),`SkillBridgeFs.kt`(protección de cruce de camino),`SkillBridgeHttpClient.post`,`PluginSecurityGate.allowedCapabilities`(+network/storage.read/write/clipboard.read/write/notify/device.info),`SkillMediaToolsImpl`(capacidad→Mapeo de acciones, bloqueo de archivos de complemento `workspace/plugins/<id>/`)
- **Automatización**:`automation/core/DeviceCommandPolicy.kt`(Lista blanca de dos niveles;⚠️ `VERBS[verb]` Usar para juicios breves `containsKey`,null valor=parámetros libres);`AutomationManager.execTiered`(Shizuku→Root);`ShellExecutor.execDetailed`;`RootToolsRegistrar`(En capas, el constructor de comandos está en `RootExecutor.buildXxx`)
- **Superficie de la herramienta**:`find_tools` consulta vacía=Lista completa de capacidades (`FindToolsTool.fullList`);CORE/lista blanca/riesgo/Muestre que cada tabla se ha sincronizado con device_shell,terminal_exec

---

### 3.4 GUI Agent anillo (④, el diseño ha sido finalizado y está por implementarse)

**Conclusión del reconocimiento (hecho)**: Las herramientas atómicas automatizadas están completas——`screen_read`(árbol de control+coordenadas)/`screen_tap`/`screen_tap_text`/`screen_swipe`/`screen_input`/`screen_back`/`screen_home`/`screen_launch_app`/`screen_wait`/`device_shell`(todo en `automation/tools/AutomationTools.kt`); captura de pantalla=`AutomationManager.captureSnapshot()`;Ejecutar=`AutomationManager` en capas (Shizuku→Root) ya existe; lista blanca de acciones `DeviceCommandPolicy` Anular toque/deslizar/texto/evento clave.**lo que falta es“anillo”mismo**:

**diseño (Operit Aprende de:do(action)/finish + maxSteps + Captura de pantalla para adelgazar)**:
- nuevas herramientas `ui_agent`(automation clan,`ToolRiskLevel.HIGH`, una única homologación cubre todo el anillo)
- bucle (maxSteps Predeterminado 15, límite máximo):
  1. `captureSnapshot()` → Captura de pantalla PNG + texto del árbol de control
  2. Toma de decisiones visuales:**Traiga solo las últimas capturas de pantalla** + Descripción de la tarea + historial de acciones recientes (texto sin formato)→ Salida del modelo visual `do(type=..., ...)` / `finish(...)`
  3. Ejecución del análisis:tap/swipe/text/keyevent/back/home/wait/launch, todos van `DeviceCommandPolicy` verificar + `execTiered`
  4. Las capturas de pantalla históricas no se incluirán en la siguiente ronda (solo queda el historial de acciones)——Captura de pantalla para adelgazar
- Llamada visual: imitación `VisionBridge` Modo (análisis de modelo + texto completo), TBD: modelo visual tomado de“ayuda visual”La configuración sigue siendo el modelo de diálogo actual (cuando se admite la visión)
- Fin:finish o maxSteps → El resumen (historial de acciones + final) se devuelve al modelo principal como resultados de la herramienta; el diálogo principal solo acepta resultados y no está contaminado por capturas de pantalla intermedias
- Lista de implementación: Nuevo `automation/agent/UiAgentRunner.kt` + Registrarse `AutomationTools`/`AgentToolsRegistrar` + Prueba única (función pura del analizador de protocolos de acción)

## 3.5 Infraestructura fase III: pantalla virtual / canal Termux / proceso independiente sin barreras (decisión del usuario: todas)

Instrucciones de usuario (2026-09-27): Se deben hacer los tres,“De lo contrario, no hay forma de lograr el verdadero propósito.”.

### A. Termux Canal (recomendado primero, independiente y sin dependencias)
Mecanismo (verificado en línea): a `com.termux/com.termux.app.RunCommandService` Enviar intención RUN_COMMAND.
- Prefijo: declaración de aplicación principal `<uses-permission android:name="com.termux.permission.RUN_COMMAND"/>`(Permisos peligrosos, información de la aplicación del usuario en Termux→Permisos adicionales concedidos)+ `<queries><package android:name="com.termux"/></queries>`;Termux lado `~/.termux/termux.properties` necesidad `allow-external-apps = true`;Android≥10 necesito dar Termux“Aplicaciones relacionadas”Permisos
- Ejecución:`RUN_COMMAND_PATH=/data/data/com.termux/files/usr/bin/bash` + `RUN_COMMAND_ARGUMENTS=["-c", cmd]` + `RUN_COMMAND_RUNNER="app-shell"` + `WORKDIR`;Resultados `RUN_COMMAND_PENDING_INTENT`(broadcast) regresar stdout/stderr/exitCode
- Implementación: nueva `TermuxChannel`(Detección/ejecutar/tiempo de espera)+ `termux_exec` Herramientas (HIGH+aprobación obligatoria)+ UI de estado y guía (pasos del asistente, saltos de permiso)
- ✅ **Se ha enviado un problema. `00f45e23`**(Permisos/declaración de consulta + Channel + termux_exec + Formulario de inscripción×5 + i18n×7 + 8 prueba única;app Cantidad total de verde)
- ✅ Página de guía enviada `6a0bf80c`(Tarjeta de canal extendido Termux del asistente de permisos,i18n×7)
- ✅ **MuMu Enlace completo pases e2e (2026-09-27 tarde)**: Instalar Termux (F-Droid Espejo Tsinghua com.termux_1022)→ root escribir allow-external-apps → pm grant Permisos → La IA que llama a termux_exec en el chat devuelve TERMUX_E2E_OK; registro de servicio del sistema `RunCommandService c:io.zer0.muse` Evidencia; captura de pantalla de evidencia `OH-WorkSpace\muse-e2e-termux.png`
- ⚠️ Limitaciones conocidas (Android 8+ Se inicia el servicio en segundo plano):App startService se rechaza cuando se usa fondo puro → Se ha iniciado el error del canal (mantenga el primer plano o active "seguir ejecutándose en segundo plano");instrumentation El proceso siempre está en segundo plano. Utilice asumir-omitir para realizar pruebas sin falsos positivos.
- Nota medioambiental:MuMu `adb root` Disponible;f-droid.org/GitHub Directamente conectado a la pared, una imagen especular de la Universidad de Tsinghua `mirrors.tuna.tsinghua.edu.cn/fdroid/repo/` Disponible

### B. Proceso independiente accesible —— Terminado ✅(Incluyendo verificación de equipos)
entrega (3 presentaciones):
- `c53b2c07` Módulo independiente + encuadernación de doble vía: nuevo `:accessibility-provider`(io.zer0.muse.a11y:`A11yProviderAccessibilityService` + `A11yBridgeService` AIDL Puente, permisos a nivel de firma `io.zer0.muse.permission.A11Y_BRIDGE` barandilla);`AccessibilityClient` Enrutamiento bidireccional (Provider Prioridad de proxy/Cobertura de instancia estática en la aplicación/Reenlace automático y respaldo después de la muerte del enlazador);`isEnabled` Cubre dos conjuntos de registros de autorización.
- `ffbec7cd` Embalaje y arranque:`syncA11yProviderApk` Copie el APK del proveedor de depuración en activos/a11y (preBuild dependencia;release Cambiar a variante firmada (dejar TODO); instalador `installProvider`(FileProvider + Instalador del sistema + REQUEST_INSTALL_PACKAGES); Tarjeta opcional del Asistente de permisos "Proveedor de accesibilidad independiente" (i18n×7)
- `8f04a704` Causa raíz:**Configuración original `android:packageNames=""` se analiza en“Nombre del paquete vacío”filtrar → Evento de servicio cero**(getCurrentActivityName espacio constante,rootInActiveWindow Nulo constante; un error latente que afecta a ambos servicios); eliminar este atributo y pasar todo
Verificación del equipo (MuMu/PHY110):
- Provider e2e:`bindService → nivel de firma AIDL → Leer la jerarquía de la interfaz de usuario entre procesos/Activity` Afirmar vía (`OK (1 test)` Regreso estable a correr ×2;evidencia `OH-WorkSpace\muse-e2e-a11y-provider.txt`)
- Casos de uso en la aplicación:instrument force-stop Posteriormente, la ROM no vuelve a vincular los servicios dentro de la aplicación. → assume-skip(Las rutas entre procesos se han sobrescrito por completo)
- Problemas ambientales (ya registrados en la base de datos de experiencias):AGP connectedAndroidTest La aplicación probada se desinstalará cuando termine. → Activar ROM para borrar la configuración de accesibilidad; El front-end persistente e2e utiliza un proceso manual (assembleDebugAndroidTest + adb install + am instrument); La lógica WIU rechaza el enlace entre aplicaciones en segundo plano de ROM personalizado (debe estar en primer plano);settings Las entradas de escritura se borran cuando se desinstala el componente.

### C. pantalla virtual (Shower fórmula)—— v1 Entregado ✅(Incluyendo verificación de equipos)
envío de entrega `e2e292fc`(Juego completo de pantalla Virtual v1,22 archivo):
- contrato `:virtual-display-protocol`(IVirtualDisplayService:ensureDisplay/launchApp/destroyDisplay/requestScreenshot/isAlive)+ `:virtual-display-server`(shell uid app_process:FakeContext/Workarounds/BinderHandoff/ServerService)
- Aplicación principal:ServerManager(jar Despliegue en trozos→pkill→empezar→Espere la carpeta)/Registry/Exportar Receiver/Client/`virtual_screen` herramientas;assets paquete vd-server.jar
- Ruta de captura de pantalla:ImageReader Conectado directamente a la superficie de la pantalla virtual (sin codificador)/Sin pantalla espejo, JPEG de un solo cuadro a pedido)
- Evidencia del equipo: construcción de pantallas. 720x1280 → Inicie la configuración en la pantalla (las nuevas tareas desaparecen de la pantalla)→ Captura de pantalla 57 KB reales UI → externo `input -d` swipe → desplazamiento de lista + captura de nuevo cuadro (58KB;evidencia `muse-e2e-vd-before/after.jpg`)
- Legado:Shizuku La cadena de inicio completa del canal no ha sido probada en este dispositivo (el dispositivo no tiene Shizuku,e2e Reemplace con el mismo comando que adb/root);root El canal requiere un iniciador setuid (TODO); sin integración profunda del agente UI (v2)

Notas de desmontaje:`OH-WorkSpace\muse-Desmontaje de pantalla virtual-Shower.md`(v1 Base de implementación, mecanismo revisado y verificado). Mecanismo central:
- Servidor = **shell uid(2000) independencia `app_process` proceso**(Servicio de usuario que no es de Shizuku);Shizuku/Root solo“shell Canal de ejecución”
- **FakeContext/Workarounds Disfraz de nombre de paquete**(com.android.shell + uid Autoconsistente) es un umbral estricto (pasa la verificación DisplayManagerService/WMS)
- Binder Mano a mano por emisora;server 15s Ningún cliente se suicida (más 5 segundos de reliberación periódica, se tolera el reinicio del host); el host necesita isBinderAlive para reiniciarse
- Entrada:`input -d <id>` Puede reemplazar completamente a InputController (Se ha ampliado DeviceCommandPolicy)
- Nuevos obstáculos durante el período de implementación (ya registrados en la base de datos de experiencias):`--activity-multiple-task` Evitar la reutilización de tareas (ninguna `--activity-new-task` opciones); límite de clases individuales.dex;FakeContext Análisis de servicios del sistema.

### Orden sugerido
① ✅ **Termux**(Entrega completa + e2e)→ ② ✅ **Proceso independiente accesible**(3 Enviar + e2e aprobado)→ ③ ✅ **pantalla virtual v1**(`e2e292fc` Entrega + verificación del equipo aprobada)—— Todos los juegos de tres piezas están en el suelo. 🎉

## 3.6 canal social & Agent Reparación de página (2026-09-27 Lote nocturno)
Los usuarios informaron tres problemas, todos los cuales se solucionaron esta ronda (3 Envío de bolígrafo:`755c3079` Actualización de línea base → `1ca17156` canal → `6135869a` Agent):

### WeChat(ClawBot)Escanee el código QR para vincular el enlace (`1ca17156`)
- Verificación de antecedentes:`get_qrcode_status` si**encuesta larga**(Servidor hold≈30s,Es normal que el cliente se agote el tiempo.,Reintentar oficialmente con espera); Implementación de referencia oficial (npm `@tencent-weixin/openclaw-weixin` login-qr.ts, leer localmente) en `expired` tiempo**Cambiar automáticamente el código (≤3 veces)**
- Solución: el bucle de sondeo se alinea con la semántica oficial (comience a sondear inmediatamente/Reintento fallido de 1,5 s/3 mensajes de error consecutivos“Anormalidad de la red”);`expired` Actualizar automáticamente el código QR (≤3); tolerancia estatal `scanned` Ortografía; cada estado alcanzado `WeClawBind` Registro (disponible en línea); Vinculación exitosa de Toast "Iniciado sesión" (antes no había ningún mensaje, el usuario pensó erróneamente que no estaba vinculado e hizo clic en Cancelar)
- Esperando la verificación de la máquina real del usuario (el escaneo real no se puede completar localmente)

### Agent preguntas de la página dos (`6135869a`)
- **posición de desplazamiento**: Caché independiente del agente agregado (index/offset + ID de sesión de pertenencia); ingresar a la sesión del agente y retomar la posición antes de salir (la nueva sesión vuelve al final); mantenimiento normal de la sesión“sigue el ultimo”; Después de regresar, espere a que el diseño esté listo y restáurelo una vez; bloquear userScrolledUp cuando no esté en la parte inferior para evitar que la transmisión lo arrastre
- **El mensaje no se muestra (fantasma)**: causa raíz = outbox La reenvío de recuperación (reenvío después de la reversión de la sesión) no utiliza la inserción optimista enqueueSend → marcador de posición faltante → `updateAssistant` El guardia destacado se salta todo el segmento (registro“saltar detached assistant”;El backend se eliminó de la biblioteca y el frontend no se procesa. Inténtalo de nuevo./Aparece sólo después del reingreso). Arreglar:`rehydrateOptimisticPlaceholders` Complete cuando se muestre la sesión actual y falte el marcador de posición
- Nota:ChatScreen/ChatViewModel Todo el archivo se reorganiza mediante ktlint (formato puro, todo verde cuando se prueba)

---

## 3.7 Hallazgos completos de la auditoría (2026-09-27 tarde en la noche)

Alcance de la auditoría: código de marca TODO (elementos de nivel de comentario 0,135 los éxitos originales son todos“herramienta de tareas pendientes”Falsos positivos de vocabulario de dominio)/ Señales de excepción del registro del dispositivo/elementos de cierre de liberación y verificación/legado conocido.

**Reparado en el acto (esta ronda)**
- ✅ mensajes no solicitados“La prueba se envía con éxito y el retroceso no está claro.”: Después de arreglar la configuración, todavía está suprimida por el retroceso exponencial de 32 h y no se recupera; ahora se borra cuando el envío de prueba es exitoso (el envío de prueba evita el retraso y el éxito demuestra que el enlace está disponible).
- ✅ Dos reparaciones menores al terminal: Las sondas de diagnóstico están apagadas por defecto (`TERMINAL_PROBES_ENABLED`, abrir cuando sea necesario);`ensureStarted` Mueva el hilo IO (elimine la alarma del hilo principal StrictMode).
- ✅ El proveedor a nivel de sesión anula la autocuración pendiente: genere una verificación de inicio, apunte a un proveedor/modelo que ya no existe y borrelo (memoria+Persistencia), la advertencia ya no actualizará la pantalla.
- ✅ Raíz de pantalla virtual de doble canal:ServerManager Cambiar a AutomationManager(Shizuku prioridad,Root Cubre el fondo), ya no se limita a Shizuku.
- ✅ Bucle cerrado de liberación del proveedor sin barreras: firmado con el mismo almacén de claves que la aplicación principal (se ha comparado el certificado SHA-256) y la aplicación principal está empaquetada según variantes (debug↔debug,release↔release, los permisos a nivel de firma están disponibles en la versión).

**Morderá (recomendado lo antes posible)/Sujeto a condiciones)**
- El enlace auxiliar del dispositivo está lleno del archivo gratuito 429 de Agnes:moment Comentarios / Resumen diario / Las funciones backend como la compresión y el embolsado han fallado durante mucho tiempo (el registro del usuario lo confirma); Se recomienda atar un canal de límite ancho separado al modelo auxiliar (“Mensaje activo conectado a la agrupación de modelos auxiliares”ya soportado).
- AnrWatcher Informará después de que el sistema congele el proceso en segundo plano.“El hilo principal no responde durante N segundos”(Falsa alarma, ya hay un fondo que no recuerda el ANR)—— Tolerable, observado.

**Refinamiento/Tipo de lanzamiento (en espera de pruebas o pedidos reales)**
- Push: envío local antes que el remoto 44 (esperando la aceptación real de la prueba).
- Embalaje 2.2.1: Esperando el pedido.
- Pantalla virtual:Shizuku La cadena de inicio aún no se ha verificado en máquinas reales (adb/root El mismo comando ha verificado toda la cadena; esta ronda ha completado la doble fila raíz);assets Paquete por firma variante (jar No se requiere firma).
- Proveedor de accesibilidad:release Se han cerrado la firma y el embalaje de variantes (el certificado SHA-256 es coherente con la aplicación principal,debug/release A cada uno lo suyo).
- Código de escaneo de WeChat & Agent Reparación de página: para ser probada por los usuarios.

**Nivel quisquilloso (la grabación no se bloquea)**
- Legado de terminal: solo queda la nueva prueba de vinculación del tamaño de la ventana emergente IME (la sonda está desactivada de forma predeterminada,ensureStarted Movido IO).
- Gradle 10 Advertencia de obsolescencia (registro de compilación);`v21-repro` worktree(incluido el mapeo de 202 MB) se pueden limpiar en una oportunidad.
- Contrato de pantalla virtual/El módulo de servidor no tiene un control de acceso de cobertura y prueba único (deliberadamente liviano y se puede agregar a medida que aumenta la complejidad).
- ktlint género de referencia“Editar es desplazar”Artesanía, mesa grande 2,58 MB; actualizado dos veces en esta ronda (primera reubicación de toda la mesa +542/−3616, la segunda fila única +60/−60).

---

## 3.8 Reparación de defectos de archivo del sistema de memoria (2026-09-27 A altas horas de la noche, informaron los usuarios.“No conserva la memoria del último tipo.”)

**Causa raíz (martillo real)**:`FactDb.archiveUnknownVersionDatabase` El guardia de la versión está escrito hasta la muerte. `if (version <= 13) return`,
La base de datos ha sido actualizada a **v14**(MIGRATION_13_14)——v14 Zhenku fue juzgado mal como“Versión alta desconocida”,**Devuelto cada vez que se inicia
Reconstruir el archivo en una biblioteca vacía**; Y si elimina -wal sin realizar ningún punto de control al archivar, incluso la copia de seguridad estará básicamente vacía (MuMu en datos antiguos
83 un `.pre-destructive.bak.N` Todas las bibliotecas vacías de 4 KB). Factores de superposición:`MemoryLegacyReset` El mecanismo de aviso está escrito.
Nunca ha sido consumido por la capa de aplicaciones. → El usuario no recibe ningún aviso durante todo el proceso. Cadena de síntomas: la escritura durante la sesión es normal (registro“Absorber 9 artículos/Proyección 13 artículos”)
→ Borrar la próxima vez (“Flujo de memoria de hechos/hechos originales 0”)→ Se muestra como“No conserva la memoria del último tipo.”.

**Reparación**:
- guardia cambiada para citar `FACT_DB_VERSION` Constantes (similar a la versión @Database y al final de la cadena de migración);
- Fidelidad del archivo: antes de archivar `wal_checkpoint(TRUNCATE)`, no puedo abrir/En caso de falla, se cambiará el nombre de -wal/-shm y se conservará junto con la copia de seguridad;
- Rescate de datos único: solo cuando "hay una marca de archivo no consumido + la base de datos principal falta o está vacía + hay una copia de seguridad no vacía y que migra la versión"
  Migrar nuevamente (inténtelo solo una vez para cada base de datos; guarde la base de datos vacía actual como `.replaced-by-recovery.bak`);
- La página de memoria indica el cableado: recuperación exitosa → «N recuerdos restaurados del archivo de cambio de versión»;Solo eventos archivados → «Los archivos fueron reconstruidos debido a cambios de versión.»(i18n×7).
- UI: Memoria larga, haga clic para ampliar (título/Haga clic en texto o“Expandir/cerrar”, umbral ≥80 caracteres o incluir saltos de línea).

**verificar**:FactDbLegacyResetTest Ampliado a 7 casos de uso (v14 Regresar sin borrar después de reabrir / archivo v15 / recuperación de un archivo equivocado /
Sin marcar, no restaurar / archivo v1-v2 / v11 no archivar);MuMu El almirante realizará simulacros de recuperación de los respaldos de fabricación.
**La verdad a los usuarios.**: Si lo tienes en tu teléfono“copia de seguridad no vacía”Se puede recuperar automáticamente; la copia de seguridad vacía no se puede restaurar (se ha eliminado el WAL histórico)——
MuMu Todas las copias de seguridad medidas están vacías. No lo vuelvas a tirar nunca más.

---

## 4. Operit Investigación de referencia (para referencia posterior)

Ubicación:`E:\1Project\Muse\Proyecto de referencia(QingTian)\Operit`(LGPL-3.0;**Se pueden tomar prestadas ideas, pero la reutilización a nivel de código debe firmarse NOTICE**;PTY Lo escribimos nosotros mismos)

Lista de verificación (en orden de prioridad):
1. PTY autocompilado (forkpty JNI)+ reflexión fd —— Para el segundo número (autoescrito)
2. Shell oculto residente + marcadores de inicio y fin (BEGIN/END/PID) Obtener salida y código de salida; matar el tiempo de espera**grupo de proceso** —— Nuestro dispositivo_shell es actualizable
3. binario `.so` Nombramiento + enlace suave en comandos (alrededor de restricciones API29+)—— Úselo cuando se necesiten comandos integrados (nota:busybox GPLv2-only Conflictos con GPLv3, uso **toybox(0BSD)**)
4. Permisos de cinco niveles + interfaz ShellExecutor unificada —— Nuestros tres canales son isomórficos y pueden complementar el concepto ADMIN.
5. APK de proveedor independiente accesible (AIDL)—— Candidatos de la segunda fase
6. UI controlar"Prioridad de inyección de comandos, seguridad sin barreras" —— Hemos aterrizado aquí ✓
7. GUI Agent Anillo:`do(action=..)/finish` acuerdo de texto,maxSteps=20,**Solo se guardarán las últimas capturas de pantalla en cada ronda.**(Las imágenes históricas se han eliminado para guardar tokens visuales)—— para el elemento 4 de la cola
8. Las herramientas de modelo débiles exponen modos duales (search+proxy)—— Complementario con find_tools, consulte

---

## 5. Nota sobre el entorno y la jugabilidad (¡importante!)

- **construir**:`cd E:\1Project\Muse\1muse`;compilar `.\gradlew :app:compileDebugKotlin --console=plain`; prueba completa `:app:testDebugUnitTest :memory:testDebugUnitTest`
- **PowerShell pozo**:
  - `-PversionName=2.2.0` será derribado → **Se requieren comillas** `"-PversionName=2.2.0"`
  - Invoke-WebRequest Capture jsdelivr e informe de error de SSL → **Usar en su lugar `curl.exe -sL -o`** ✓
- **terminal de ejecución"espera falsa"**:exec/Las tareas terminales a menudo se encuentran"No completado/tiempo de espera". Postura confiable:`Get-CimInstance Win32_Process` encontrar `gradle-wrapper` de java PID → `WaitForExit`; o usar `wait_for_tasks` Regístrate y espera
- **ktlint mecanismo de referencia**: eliminar `app/ktlint-baseline.xml` → correr `:app:ktlintKotlinSourceCheck`(La primera ejecución FALLÓ y se regeneró)→ Pasaron dos carreras → Enviar línea de base (compromiso de tarea por separado)
- **R8 Juego de solución inversa de fallos**:
  - Mire primero cualquier pila de fallos `r8-map-id-...` huella digital → Compare los encabezados de mapeo de cada versión de esta máquina `pg_map_id`(2.1.0=9e6919…,2.2.0=9031fced…)
  - No está bien → usar `git worktree add Directorio <tag/commit>` Construcción reproducible (el mapeo se puede reproducir con la misma cadena de herramientas, verificado)
  - Solución inversa manual: en mapeo.txt `Select-String ' -> <nombre confuso>:'`, luego mire las entradas en línea del bloque de clase;ZIP dentro `resources.arsc` Los nombres de recursos se pueden buscar usando cadenas Latin1"Comparación de los artículos reales en el paquete."(usado `knowledge_import_indexed_chunks` Prueba 2.2.0 con correcciones)
- **Especificaciones de envío**: chino,`type(scope): Tema — Detalles`;Las comillas simples aquí evitan que PowerShell se escape
- **Proceso de aceptación**(por etapa):compile → prueba de orientación → Aplicación completa + prueba de memoria → ktlint dos carreras → Enviar por tema; (al empaquetar)assemble + instalar Mumu `adb -s 127.0.0.1:16384 install -r` + aapt Verificar versión
- **Nota de preferencia del usuario**: Funciones garantizadas y parches a voluntad; sin confirmación secundaria para borrar la memoria; no escriba el nombre de la plataforma de referencia en los comentarios (referencia del diseño);emoji Restricción (el usuario seguirá después de enviar el mensaje primero); para cambios de configuración, cambie directamente el código sin tomar capturas de pantalla página por página; presione y espere la verificación de la máquina real (2.2.0 Se envía, las nuevas confirmaciones posteriores no se envían)

---

## 6. Versión y estado de lanzamiento

- **2.2.0 Publicado**(GitHub Release Latest,4 activos + manifiesto.json); comprometerse después de publicar (§1 lista)**no empujado**
- **2.2.1 Embalaje local completado ✅**(2026-09-27 A altas horas de la noche, el usuario emitió una orden.“Empaquétalo primero y déjame probarlo.”):
  - línea de versión `6f578644` Aumentando a 221/2.2.1 (consistente con la reconciliación resolve_release_version);v2.2.1 Se han ingresado notas históricas. build.gradle
  - Las tres variantes firmadas:arm64 66MB / v7a 55MB / universal 167MB;Oficial `validate_release_apks.py` Tres paquetes de PASS;aapt2 verificar `versionCode=221 / versionName=2.2.1`;release El proveedor en el paquete es coherente con el producto firmado byte a byte.
  - **R8 Iniciar pase de humo**(MuMu Instalar la versión de lanzamiento: no FATAL/ClassNotFound, los registros de inicialización son todos normales);MuMu Cambiado a **2.2.1 release Aplicación principal + firma de autorización Provider**(`A11Y_BRIDGE granted=true`;Los datos principales de la aplicación se restablecen debido al reemplazo de la firma.Termux Permisos/Accesibilidad restaurada)
  - **No empujado, no etiquetado, no publicado** —— Espere a que el usuario pase la prueba real antes de continuar. publish_release.py
- Guión del proceso de publicación:`ci/script/`(resolve_release_version → assembleRelease → generate/validate manifest → publish_release.py); la línea predeterminada de la versión es ahora **221/2.2.1**(app/build.gradle.kts)
- ⚠️ Comentarios de OOM de la base de conocimientos del lado del usuario (v2.1.0 colapso de la era): causas fundamentales `readText()` Texto completo en la memoria,**2.2.0 Fijo**(Índice de transmisión); Verificado en paquete. Si el usuario aún falla después de la actualización, solicite una nueva falla (la huella digital debe ser `9031fced…`), que se puede resolver instantáneamente localmente

---

## 7. Ubicación del documento

- Este documento:`C:\Users\21192\Desktop\OH-WorkSpace\muse-plan anticipado.md`
- **Liberar archivo de producto (acuerdo)**:`C:\Users\21192\Desktop\OH-WorkSpace\muse-releases\<número de versión>\`(APK Variantes + Provider + SHA-256 + RELEASE_NOTES + Instantánea del plan de lanzamiento actual; a partir de 2.2.1)
- Notas sobre el desmontaje de la pantalla virtual:`C:\Users\21192\Desktop\OH-WorkSpace\muse-Desmontaje de pantalla virtual-Shower.md`
- Descubre el fondo/Informe de rectificación:`C:\Users\21192\Desktop\OH-WorkSpace\muse-Descubre el fondo-2026-09-26\`
- Proyectos de referencia:`E:\1Project\Muse\Proyecto de referencia(QingTian)\`(chatbox / kelivo / openhanako / Operit / rikkahub)
