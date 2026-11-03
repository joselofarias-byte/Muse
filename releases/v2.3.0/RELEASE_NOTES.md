# Muse 2.3.0 Instrucciones de archivo (compilación local · Reparación de memoria + ciclo de herramientas/Continuidad contextual para lotes grandes)

- Tiempo de archivo:2026-09-28 Temprano en la mañana (GMT+8)
- Fuente de compilación:`E:\1Project\Muse\1muse` @ commit `5aba0591`(Listas locales remotas por 58 confirmaciones en total; línea de base remota `899b2ef8` = v2.2.0)
- Comando de compilación:`.\gradlew :app:assembleRelease "-PversionName=2.3.0" "-PversionCode=230"`
- Estado:**Archivo local, no enviado/no etiquetado/no publicado**(Esperando pruebas de usuarios reales)
- Descripción: Este paquete**Reemplaza 2.2.1 Archivado**(2.2.1 Nunca empujado, sólo circuló localmente; sus reparaciones de memoria están todas incluidas en este paquete)

## Correcciones y cambios incluidos en esta versión.

### 1. Cura radical para los defectos de archivo del sistema de memoria (2.2.1 lote, incluido el rescate de datos)
- Guardián de la versión escrito hasta la muerte. `≤13` la biblioteca tiene v14 → Cada vez que se inicia, la biblioteca real actual se archiva por error y se reconstruye en una biblioteca vacía. Ahora la versión guard y la base de datos tienen el mismo origen en tres lugares;
- Antes de archivar `wal_checkpoint` Fidelidad (WAL se conserva con la copia de seguridad en caso de falla);
- Se agregó recuperación de archivos accidental por única vez: la versión reparada se recuperará automáticamente de"Copia de seguridad mal archivada que contiene datos"Recuperar memoria (la copia de seguridad vacía no se puede restaurar)——El WAL histórico ha sido eliminado por versiones anteriores);
- Archivo de cableado de la página de memoria/Restaurar consejos; Memoria larga, haga clic para ampliar.

### 2. Liberación de rotación de herramienta (nueva)
- Nueva configuración "Límite de ronda de herramientas": sin límite (predeterminado)/100/50/25(ajustes→charlar→contexto);
- El límite superior de llamadas totales en una sola ronda 60→300;Protección de bucle infinito (llamada repetida de huellas dactilares/Sin progreso/La falla continua (parada anticipada) permanece sin cambios.

### 3. Solución de continuidad del contexto (nueva, central)
- La historia fuera de la ventana ya no se descarta silenciosamente: generar `[HISTORY_DIGEST]` Extracto determinista (usuario/Asistente de fragmento de texto original en lenguaje natural + entrada de herramienta) se inserta delante de la ventana;
- Resumen de compresión de rondas de herramientas conservado"Instrucciones del asistente"Vista previa;
- Agregue una barrera de tiempo de espera de 20 segundos para las llamadas de compresión (las pruebas reales han ralentizado la primera palabra) ~31s).

### 4. Estratificación de permisos de herramientas (nuevo)
- Requiere Shizuku/Root, Accesibilidad,Termux Todas las herramientas están completamente etiquetadas;find_tools traer `[needs X; READY/NOT READY]` + Línea de estado del canal; índice de herramientas de aviso del sistema `*` + leyenda;
- Verificación previa antes de la ejecución: el canal no está listo y regresa directamente a la estructura `permission_required` Error (reemplazando"falso éxito"redacción);
- `screen_permission_status` Hazlo permanentemente visible; registrar 4 herramientas de clasificación faltantes.

### 5. Habilidades y Diagnóstico (Nuevo)
- El soporte rápido para habilidades de palabras es opcional `input` Parámetros (`{{input}}`/`{{args}}` marcador de posición; no se añade ningún marcador de posición al final);
- La semilla de habilidad incorporada se cambia a"Falta inserción + campo de definición de actualización existente (conservar inicio y detención)"——Se corrigió el antiguo esquema de la biblioteca que nunca se actualizaba;
- knowledge_search: Puntuación continua (antigua distribución discreta) threshold≤0.5 Completamente indistinguible——"Pasar 0.5 no tiene ningún efecto"la verdadera causa raíz)+ Eco diagnóstico perdido;
- MOOD salida de depuración (`MoodDebug` registro);`skill_system_guide` Se agregó una guía de selección de habilidades versus complementos.

### 6.UI con arreglos poco a poco
- Rediseño de dos líneas de la tarjeta del canal de mensajes (nombre largo/Las identificaciones largas ya no se agrupan en filas verticales;ID Se omite la sección central);
- suplemento `com.android.alarm.permission.SET_ALARM`(anteriormente set_alarm/set_timer fue interceptado por el sistema); el despertador no pudo agregar texto `[falló]` Marca de juicio.

## producto con suma de comprobación (SHA-256)

| Archivo | tamaño | SHA-256 |
|---|---|---|
| Muse_v2.3.0_arm64-v8a.apk | 66MB | `87fd0a8cf8042b30d14eabec42e8881e2616d98ebed4eace3595dd88f0dfebd7` |
| Muse_v2.3.0_armeabi-v7a.apk | 55MB | `c56d221aab2bea8de9b325e6d9dd86aff3c3fa206bf6bb80ced35301fe7aded2` |
| Muse_v2.3.0_universal.apk | 167MB | `10827b06df5b7d8203aa15e5fe19f0c70eab4aa161b03e4887c8bf633b289a75` |
| Muse_v2.3.0_accessibility-provider.apk | 2MB | `e70be8d0d82e727a664e966cf0853ddc5e217a70985ade3307208855b6f179e3` |

> La aplicación principal y el proveedor utilizan el mismo almacén de claves de versión (certificado SHA-256 `e82f4ecde8304b7d78b530336a48e41a42b80c0ebac8af2d2c10448277644ebf`).
> Tres variantes en el paquete. `assets/a11y/accessibility-provider.apk` Sea coherente byte por byte con el producto independiente (verificado).

## Registro de verificación de fábrica

- aapt2:`versionCode=230 / versionName=2.3.0` ✓
- Validador oficial `ci/script/validate_release_apks.py`:Tres firmas de garantía PASS ✓
- Prueba única completa (app+memory+ai) volver a estar en verde (2m28s); Nueva prueba:ContextHistoryDigest 5 / ContextWindowSplit 4 / ToolTraceProjection +1 / ToolPermissionStatus 4 / seedOrRefreshBuiltIn 1 ✓
- ktlint Control de acceso:app+ai Segunda ejecución después de la actualización de la línea base SUCCESS ✓
- Ejercicio de recuperación de memoria (MuMu,2.2.1 Lote): Fabricación"Copia de seguridad mal archivada con 1 memoria + marca no consumida" → Primer registro de inicio `Memoria de hechos recuperada del archivo: 1 Artículo` → Dos reinicios consecutivos con hechos persistidos y sin nuevos archivos ✓

## Notas de instalación

- Si tiene la versión de reparación 2.2.1 instalada en su teléfono/2.2.0 Versión oficial: cobertura directa y actualización con la misma firma;
- Si el paquete de depuración está instalado: primero es necesario desinstalarlo (la firma es diferente).

## Convenciones de archivo

- Lugar oficial de presentación: En el almacén `E:\1Project\Muse\1muse\releases\v2.3.0\`(2026-09-28 habilitado desdeAPK por `.gitignore` Excluido, solo para productos locales y carga de versión de GitHub y almacenamiento temporal);
- La versión de Git que se ha enviado está sujeta a los activos de la versión de GitHub;2.2.0 No se realizó ningún archivo local anteriormente.
