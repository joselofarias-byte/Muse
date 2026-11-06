# X Descubrimiento transversal de la serie (autoevaluación del agente de revisión principal,2026-10-10)

Alcance: Módulos cubiertos por dos proxies sin enlaces —— `accessibility`/`accessibility-provider`(Puente de accesibilidad de APK dual) con
`virtual-display-server`(vdserver Servidor de pantalla virtual).

---

## X1. vdserver Todas las llamadas de Binder están encadenadas en un candado y las llamadas de Shell no tienen tiempo de espera (P1, credibilidad: alta)

- Ubicación:`virtual-display-server/src/main/java/io/zer0/muse/vdserver/ServerService.java:356-367`(`execShellCapture`),
  `:372-397`(`resolveLauncherComponent`),`:49/:68/:124/:158/:171`(todos `synchronized` entrada)
- Evidencia:
  - `ensureDisplay/createDisplay/launchApp/destroyDisplay/requestScreenshot` Las cinco entradas de Binder `synchronized`
    (Mismo monitor,Binder El grupo de subprocesos está serializado).
  - `launchApp` → `execShellCapture` → `Runtime.exec("sh -c am start ...")` → `process.waitFor()` **Sin tiempo de espera**;
    `resolveLauncherComponent` de `cmd package resolve-activity` Tampoco hay tiempo de espera `waitFor()`(:361/:377).
  - `requestScreenshot` dentro de la cerradura `Thread.sleep(20ms)` Gira hasta 1 s (:182-187).
- Impacto: Cualquiera `am start`/`cmd package resolve-activity` Cuelgue (ROM del fabricante atascada, sistema ocupado)→
  Todas las operaciones de pantalla virtual (incluidas capturas de pantalla de otras pantallas)/Destruir) bloqueo de colas; El método de recuperación es solo 15 segundos de vigilancia inactiva. `System.exit(0)`
  ——Y la sentencia ociosa también se llama por la suspensión. `touch()` Se actualiza repetidamente y es posible que nunca salga.
- Sugerencias:`waitFor(timeout)` + Destruir el proceso; o saque la llamada del shell de la cerradura y presione la pantalla para desbloquearla.

## X2. ui_screenshot La ruta predeterminada entra en conflicto con la escritura entre UID del proveedor (P1, credibilidad: alta)

- Ubicación:`app/src/main/java/io/zer0/muse/tools/defaultTool/UIToolsSkill.kt:206-220`(Predeterminado `context.cacheDir`),
  `app/src/main/java/io/zer0/muse/tools/system/AccessibilityClient.kt:218-219/:234-253`(AIDL Enrutamiento dual),
  `accessibility/src/main/java/io/zer0/muse/accessibility/MuseAccessibilityService.kt:367-390`(`FileOutputStream(path)`).
- Evidencia:
  - `ui_screenshot` Se utiliza cuando no se pasa la ruta**aplicación principal** `cacheDir` generar camino;`AccessibilityClient.withProvider` ir primero
    Proveedor independiente AIDL `takeScreenshot(path)`.
  - Provider APK `io.zer0.muse.a11y` es un UID independiente (ninguno `sharedUserId`), a la aplicación principal `cacheDir` Sin permiso de escritura
    → `saveScreenshot` de `FileOutputStream` Lanzar excepción → Devuelve falso.
  - Sólo aviso de fallo de herramienta"La capacidad de captura de pantalla no está disponible/Requiere Android 14+ o excepción de servicio",**No incluye semántica de permisos entre UID**,
    Usuario/El modelo no puede localizar la verdadera causa a partir de la pista.
  - `path` Los parámetros son cualquier ruta absoluta controlable por el modelo (ToolDef :127 "Opcional,guardar ruta(camino absoluto)"),Provider El proceso será directamente
    `FileOutputStream(path)` escribir——No hay una lista blanca de rutas en el lado del proveedor independiente/Verificación (en comparación con vdserver)
    `packageName.matches(...)` Prácticas antiinyección).HIGH La aprobación de riesgos mitiga el abuso externo, pero el alcance de la redacción después de la aprobación no está limitado.
- Impacto: los usuarios que hayan instalado el APK del proveedor,ui_screenshot Tome la ruta prioritaria del proveedor → La ruta predeterminada debe fallar;
  Las indicaciones de error desvían la dirección de la solución de problemas.
- Recomendación: la aplicación principal escribe la ruta en un directorio en el que ambas partes pueden escribir (o los bytes se devuelven a través de AIDL en lugar de la ruta);
  Provider Cree una lista blanca de directorios para la ruta (por ejemplo, permita solo cada cacheDir); se distinguen los avisos de falla"Permiso fallido".

## X3. AccessibilityExecutor La capacidad de captura de pantalla no está conectada al canal dual del proveedor (P2, credibilidad: alta)

- Ubicación:`app/src/main/java/io/zer0/muse/automation/executors/AccessibilityExecutor.kt:40-64`.
- Evidencia:`screenshot()` solo lectura `MuseAccessibilityService.instance`(instancia estática dentro de la aplicación);
  y `DeviceCapabilityProfile` de `accessibilityScreenshot` Los marcadores de habilidad y las capturas de pantalla del canal de tres capas se basan en esto.
  Usuarios que hayan instalado el Proveedor independiente y tengan únicamente esta autorización:`isAvailable()`(Igual que solo comprobar instancia) y `screenshot()` Todos ellos se consideran inutilizables.
  El expediente de competencias"Realmente tiene accesibilidad.+captura de pantalla"Los informes no están disponibles.
- Nota:`AccessibilityClient`(tools capa ui_* herramienta) tiene canales duales, pero el canal de automatización de tres capas no ——
  Las capacidades de los dos conjuntos de clientes en el mismo dispositivo son inconsistentes.
- Sugerencias:`AccessibilityExecutor` Reutilizar `AccessibilityClient` Enrutamiento bidireccional o detección de perfil de capacidad de canales de proveedores complementarios.

## Otros controles (sin descubrimiento)

- Provider APK La cadena de distribución está completa:`app/build.gradle.kts:38-71` CopyProviderApkTask poner
  `accessibility-provider` Cree el producto para integrarlo en la aplicación principal. `assets/a11y/`,`AccessibilityProviderInstaller`
  Responsable de la instalación —— Ninguno"Los usuarios no pueden obtenerlo. Provider APK"pregunta.
- La cadena de permisos del puente es correcta:signature nivel `io.zer0.muse.permission.A11Y_BRIDGE`, la declaración de permisos de uso de la aplicación principal es coherente.
- `A11yBridgeService` La degradación del valor nulo tiene la misma semántica que la clase base;`mCanTakeScreenshots` Los fallos de reflexión están marcados con rutas de degradación.
