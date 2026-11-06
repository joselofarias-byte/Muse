# Informe de revisión del proyecto de construcción/CI Gate/lanzamiento (2026-10-10)

## Resumen

Alcance de la revisión:`ci/`(run_ci_checks.ps1,29 un script/*.py,13 un test/*.py,baseline/,detekt_debt_cap.json),
root build.gradle.kts con 8 módulos,settings.gradle.kts,gradle.properties,detekt.yml,
cadena de liberación (resolve_release_version / validate_release_signing / validate_release_apks /
generate_release_manifest / validate_release_manifest / publish_release),
`.github/workflows/ci.yml` con `web-ci.yml`,`.gitattributes` / `.editorconfig`,
y materiales de lanzamiento v2.5.8 con herramientas/market-signer.

Método: leer todos los scripts y crear archivos; verificar el descubrimiento de cada candidato con una ejecución real (Python nativo 3.13 + espacio de trabajo de almacén,
Operación de solo lectura, no se modifican archivos de almacén; solo los scripts de sonda temporales se escriben en la temperatura del sistema y luego se limpian).

Conclusión general:**El sistema de control de acceso está actualmente en rojo en HEAD** —— lanes lane tres"Corre incondicionalmente"Uno de los controles rápidos.
(hardcoded_cjk) y controles locales estrictos, ambos probados FAIL;tag↔Gradle El mecanismo de sincronización del número de versión predeterminado está en
v2.5.6/2.5.7/2.5.8 Ha sido sustancialmente omitido por el proceso de construcción local en tres distribuciones;detekt El techo de la deuda no está por sí solo
"Regulación a la baja simultánea"La póliza se regenera siguiendo la línea base del 10-08/10-09; la política de fin de línea todavía solo bloquea `*.sh`,624 archivos de almacenamiento
El espacio de trabajo es CRLF (contiene 6 líneas base de CI y 2 líneas base JSON).DEDUPE en la línea de base"Cinco carriles todos verdes registrados
(Basado en el último lote)"La declaración está desactualizada. Además, se han verificado una serie de dudas históricas como sigue:**No hay problema**(Ver"Comprobando elementos conocidos").

Descubra las estadísticas:**12 Artículo —— P0 = 0,P1 = 3,P2 = 9**.

---

## lista de descubrimiento

### F1. hardcoded_cjk El control de acceso se mide FAIL en HEAD (P1, credibilidad: alta)

- Ubicación:
  - `ci/baseline/hardcoded_cjk_baseline.txt:19`(`app/src/main/java/io/zer0/muse/ui/SettingsScreen.kt: 68`, línea de base actualizada por última vez el 8b587cd4 / 2026-09-29)
  - `ci/run_ci_checks.ps1:89-95`(Este guión pertenece a lanes lane **Corre incondicionalmente**uno de los tres controles rápidos)
  - Violando el código fuente:`app/src/main/java/io/zer0/muse/ui/chat/ChatToolLoopRunner.kt:399,576,931-932,975-976`(6 en, archivo nuevo, fuera de la línea base);`app/src/main/java/io/zer0/muse/ui/chat/ChatToolResumeCoordinator.kt:77,82`(2 , nuevo archivo);`app/src/main/java/io/zer0/muse/ui/SettingsScreen.kt`(69 en > Línea de base 68,d60aed71 Nueva entrada de exportación de diagnóstico `listOf("Diagnóstico", "Exportar", "Iniciar sesión", ...)` cadena de búsqueda)
- Evidencia (ejecución de prueba real `python ci/script/check_hardcoded_cjk.py`,exit=1):

  ```
  [hardcoded-cjk] Escanee el directorio ui/: 508 Literales chinos codificados,57 archivos distribuidos
  [hardcoded-cjk] FAILED — Se agregaron literales chinos codificados:
    app/.../ui/SettingsScreen.kt: 69 en (baseline 68,Nuevo 1)
    app/.../ui/chat/ChatToolLoopRunner.kt: 6 en (baseline 0,Nuevo 6)
    app/.../ui/chat/ChatToolResumeCoordinator.kt: 2 en (baseline 0,Nuevo 2)
  ```

  Tres documentos ilegales `git status` Limpio (es decir, el estado de confirmación HEAD es rojo).S1-S5 compromiso dividido (dfe3bbe3,15341441)
  Cada uno declara"ktlint+detekt Todo verde, prueba única de cantidad completa exit=0", pero no corriendo/La puerta del script no está declarada;d60aed71 La misma razón.
- Impacto:lanes lane de "Lane checks" pasos (`ci.yml:97-100`, ninguno `run_full` ejecución condicional, incondicional)
  en HEAD actual**cualquiera** PR / La operación manual fallará;DEDUPE línea de base"Cinco carriles todos verdes"El registro no es válido.
  10-09~10-10 Los tres lotes de presentaciones dejaron atrás la línea de base y el control de acceso cambió de"interceptar nueva deuda"degenerar en"ruido rojo constante".
- Sugerencia: Limpiar 9 nuevos literales (ChatToolLoopRunner La copia del error va stringResource;SettingsScreen
  Si la cadena del mensaje de búsqueda es para exención de acciones, debe completar la línea de base y explicar), y luego `--update-baseline` apretar (MemorySpaceSwitcher
  4. Los documentos han sido mejorados y se pueden registrar juntos); y"Control de acceso al script todo verde"Volver a los elementos de verificación previa al lanzamiento.

### F2. strict locale Control de acceso medido en HEAD FAIL:values-en Falta 1 tecla (P1, credibilidad: alta)

- Ubicación:
  - `app/src/main/res/values/strings_chat_core.xml:377`(`<string name="chat_token_estimated_cost">El costo histórico es de aprox. $%1$s(Estimación aproximada basada en el precio del modelo actual)</string>`)
  - `ci/script/check_localizations.py:140-142`(strict locale desaparecido total_missing → exit 1)
  - `ci/run_ci_checks.ps1:96`(scope Ruta:localization Debe ejecutarse al cambiar)
- Evidencia (ejecución de prueba real,exit=1):

  ```
  values-en: 5901 keys [MISSING 1 keys (FAIL)]
    MISSING: chat_token_estimated_cost
  values-es/ja/ko/pt-rBR/ru: 5784 keys [MISSING 118 keys (WARN)]
  ```

  Introducir la presentación:2bd5bebb(2026-10-08"Plan de optimización implementado en cinco fases") sólo en `values/` Añade la clave,
  No sincronizado `values-en/`(`git show 2bd5bebb --name-only` En res cambia solo este archivo).
  `app/lint-baseline.xml` No hay ninguna entrada MissingTranslation a decir verdad (grep sin aciertos),`error.add("MissingTranslation")`
  En teoría, la puerta de pelusa también será roja la próxima vez que se use lintDebug.
- Impacto: cualquier PR/ejecución local completa que toque el alcance de la localización fallará;en(Lenguaje estricto predeterminado)
  "Traducción completa"Promesa rota; proceso de traducción posterior (translate_locales usando valores-en como fuente) pondrá esto
  Los marcadores de posición chinos se extienden a todos los idiomas.
- Sugerencia: suplemento `values-en/strings_chat_core.xml` de `chat_token_estimated_cost` entrada en ingles
  (Por cierto, verifique la brecha es/ja/ko/pt-rBR/ru del libro mayor 101 → 118 Si es necesario complementar el incremento).

### F3. tag↔Gradle Se omite el mecanismo de sincronización del número de versión predeterminado:v2.5.6/v2.5.8 tag No se puede pasar la verificación previa de autoedición (P1, credibilidad: alta)

- Ubicación:
  - `app/build.gradle.kts:140-148`(El valor predeterminado está congelado desde f7dc0dd2/v2.5.1 `?: 251` / `?: "2.5.1"`)
  - `ci/script/resolve_release_version.py:57-61`(`tag versionName does not match Gradle default` es decir, FALLA)
  - `.github/workflows/ci.yml:255-257`(release job El primer paso es ejecutar la verificación previa)
  - `app/build.gradle.kts:466`(Notas de guardia"La línea predeterminada actual es 242/2.4.2"——Ha caducado por dos generaciones)
- Evidencia (reproduzca resolve_release_version de las etiquetas una por una, medición real):

  ```
  v2.5.1: PASS -> 2.5.1/251
  v2.5.6: FAIL -> tag versionName 2.5.6 does not match Gradle default 2.5.1
  v2.5.8: FAIL -> tag versionName 2.5.8 does not match Gradle default 2.5.1
  ```

  Y la etiqueta v2.5.6/v2.5.8 en el árbol `releases/*/manifest.json`(versionCode 256/258) y dentro de la etiqueta
  Gradle El valor predeterminado 251/2.5.1 es inconsistente, lo que demuestra que la versión se creó localmente.
  (`releases/v2.5.8/release_verification.txt:4`:`gradlew :app:assembleRelease -PversionName=2.5.8 -PversionCode=258`).
- Impacto: en el diseño, la etiqueta es la única fuente verdadera de la versión, verificando previamente y previniendo"tag A la deriva con los valores predeterminados de Gradle", pero 3 lanzamientos consecutivos
  (2.5.6/2.5.7/2.5.8) El valor predeterminado no está sincronizado y no se utiliza la canalización de etiquetas. Cualquiera puede manualmente dispatch release
  job FALLARÁ en el primer paso; de lo contrario, si alguien ha realizado una verificación previa,"Complementar el valor predeterminado", el control de acceso se convierte en un dispositivo de postgrabación.
  La verdadera fuente de los números de versión en realidad ha pasado del valor predeterminado de Gradle al registro manual en release_verification.txt.
- Recomendación: proceso de recuperación——El envío de la versión debe estar sincronizado `versionCode/versionName` Valor predeterminado (puede agregar un script CI
  afirmar `releases/vX/manifest.json.versionCode == Gradle Predeterminado`); al menos solucionalo primero:466 comentarios caducados.

### F4. check_ktlint_report Falta el proveedor de accesibilidad en la lista de módulos (P2, credibilidad: alta)

- Ubicación:`ci/script/check_ktlint_report.py:22`(`MODULES = ["app", "common", "memory", "ai", "material3", "accessibility"]`)
- Evidencia:`accessibility-provider/build.gradle.kts:6` aplicado `alias(libs.plugins.ktlint)`,
  raíz `build.gradle.kts:68-118` El bloque de subproyectos lo registrará para `ktlintKotlinSourceCheck`,
  y `accessibility-provider/ktlint-baseline.xml` Ya en stock (glob verificar existencia)——es
  **El séptimo módulo que realmente ejecuta el control de acceso.**, pero el script de aserción de informes no lo anula.
- Impacto: si el informe ktlint de este módulo se degrada a una ejecución vacía/desaparecido (P4-1 reparado"verde falso"el problema vuelve a aparecer),P4-1 afirmar
  No llamará a la policía;static lane de"El informe no está vacío y registra el número real de archivos."El compromiso es un punto ciego para este módulo.
- Sugerencias:MODULES anexar `accessibility-provider`(virtual-display-* El complemento ktlint no está conectado y no es necesario incluirlo).

### F5. detekt El techo de deuda no se ha alcanzado"Regulación a la baja simultánea"La póliza sigue la regeneración de línea base del 10-08/10-09 (P2, credibilidad: alta)

- Ubicación:`ci/detekt_debt_cap.json:2-9`(total 3497 / app 3085;note El último es el lote 2026-10-07)
- Evidencia:
  - Medición real `python ci/script/check_detekt_debt.py`:`detekt Medición de la deuda: 3465 Artículo (app 3053/ai 217/memory 174/common 12/accessibility 9)` —— PASO control de acceso;
  - `git log -1 -- ci/detekt_debt_cap.json` = 4d032c50(10-07), mientras `app/detekt-baseline.xml`
    En daf627b1 (10-08,3057 Artículo) y 776fec47 (10-09,3053 Artículo) fue regenerado dos veces sin tocar la tapa.
- Impacto:cap Documente sus propias políticas"Ajuste manualmente este archivo después de limpiarlo en lotes(DOWN)". El statu quo se mantiene
  `3497 - 3465 = 32` Sin espacio de aumento neto de cuenta——La deuda puede aumentar silenciosamente 32 y la puerta está toda verde,
  con"Está prohibido aumentar únicamente la cantidad sin limpiarlo."La restricción de simetría (la regulación negativa no va seguida de la regulación negativa medida) no coincide.
- Sugerencia: sincronizar la tapa con la medida real actual (total 3465 / app 3053) y agregue un libro mayor de 10-10 en la nota.

### F6. check_horizontal_inset es el único"Falta el objetivo de escaneo, es decir. PASS"control de acceso (P2, credibilidad: alta)

- Ubicación:`ci/script/check_horizontal_inset.py:59-62`

  ```python
  root = Path(args.root)
  if not root.is_dir():
      print(f"[horizontal-inset] El directorio no existe: {root}")
      return 0
  ```

- Evidencia (medición real): Ejecutar en el directorio principal del almacén `python ci/script/check_horizontal_inset.py` →
  `El directorio no existe: app\src\main\java\io\zer0\muse\ui` → **exit=0**; Luego transfiera cualquier directorio no existente → igualmente exit=0.
  Grupo de control:check_localizations Cuando falta el directorio res `sys.exit(2)`;Otras escrituras de control de acceso `baseline_utils.require_baseline`
  fail-closed(test_baseline_fail_closed.py Especialmente vigilado).
- Impacto: predeterminado `--root` es una ruta relativa CWD (`ci/run_ci_checks.ps1:13` de `Set-Location $root` Garantizado
  lane internamente seguro), pero cualquier llamada fuera de esta condición (manual, otro corredor,future Refactorizando y moviendo cwd) obtendrá
  silencio PASS——Cuando falta la entrada de control de acceso, debería fallar en lugar de pasar. Este es un caso aislado que viola la convención de cierre fallido de este almacén.
- Sugerencia: devuelva 2 cuando falte el directorio (o use en su lugar `PROJECT_ROOT = Path(__file__).parents[2]` la raíz absoluta, consistente con el resto del guión).

### F7. Bloqueos de estrategia de final de línea únicamente `*.sh`:624 Espacio de trabajo de archivos entrantes CRLF,19 una mixta,CI Línea base en la columna (P2, credibilidad: alta)

- Ubicación:`.gitattributes:1-3`(sólo `*.sh text eol=lf`);`.editorconfig:12`(`end_of_line = lf`, sólo restringe el editor/ktlint, no restringe git)
- evidencia (`git ls-files --eol`,core.autocrlf=false):
  - 2669 en un archivo de base de datos **624 espacio de trabajo w/crlf**(app 494 / material3 48 / memory 31 / ai 25 / ci 10 ...), incluyendo `app/src/main/java/io/zer0/muse/ui/ChatViewModel.kt` Espere los archivos que están i/crlf en el índice;
  - **19 un w/mixed**, incluido el script de control de acceso `ci/script/check_localizations.py`(En realidad midió 180 líneas de CRLF + 21 líneas de LF desnudo);
  - CI 6 archivos de referencia `ci/baseline/*.txt` con `ci/component_convergence_baseline.json`,`ci/font_scale_clipping_baseline.json`,`ci/translation_residue_baseline.json` Ambos son i/lf + w/crlf;
  - Mecanismo de reproducción:`write_baseline`(check_hardcoded_cjk.py:157,check_row_text_width.py:257 etc.) uso
    `path.write_text(...)`,Windows Utilice la traducción de nueva línea predeterminada `\n` escrito como CRLF → Windows lado
    `--update-baseline` Genera una diferencia de archivo completa de las versiones LF indexadas y de referencia;`git ls-files --eol` abajo
    detekt-baseline.xml Ya apareció `Missing newline` advertencia de clase (check_repo_hygiene 1128 avisos medidos).
    Otra prueba real: toque check_repo_hygiene.py en el espacio de trabajo CRLF una vez y generará `167/167` Diferencia fantasma de líneas.
- Impacto: Esto es"Históricamente, el ruido CRLF ha sido repetidamente"La causa raíz no ha sido eliminada.——línea de base/Script para regenerar el archivo completo en Windows
  Voltear los extremos de la línea, contaminar la revisión de diferencias, aumentar el volumen de relaciones públicas;mixed Los archivos continúan a la deriva con el comportamiento de guardado de diferentes herramientas.
- Sugerencias:`.gitattributes` aumentar `* text=auto eol=lf`(o al menos `*.py`/`*.kts`/`*.kt`/`*.json`/
  `ci/baseline/*` explícito `text eol=lf`), combinado con una sola vez `git add --renormalize .` stock de convergencia;
  Python Escribe la línea de base de manera uniforme. `open(..., newline="\n")`(generate_release_manifest.py:52 Esta es una demostración correcta).

### F8. ci.yml La anotación de prueba de Android se refiere al cron nocturno inexistente (P2, credibilidad: alta)

- Ubicación:`.github/workflows/ci.yml:155-157`("P4-3: ……ahora schedule(Misma frecuencia que el cron nocturno anterior)también desencadenar emulator smoke",job Condiciones `if: github.event_name == 'schedule' || ...`)controlar `ci.yml:13-16`(`on:` solo `workflow_dispatch`,v2.1.0 activa automáticamente el apagado completo)
- Evidencia:`on:` Ninguno `schedule`,`github.event_name == 'schedule'` licencia permanente;`reactivecircus/android-emulator-runner`
  En realidad, la prueba instrumentada solo tiene un punto de entrada para el envío manual, como se muestra en los comentarios."nightly"Nunca ocurre bajo el disparador actual.
- Impacto: la desviación de la documentación puede llevar a los mantenedores a creer erróneamente que el humo del emulador tiene cobertura de regresión nocturna (HANDOFF conocido"Falta de aceptación del equipo."
  otra capa de malentendidos); nuevos canales/Instrumentación accesible Si espera que todas las noches lo cubra todo, se sentirá decepcionado.
- Sugerencia: cambie el comentario a"solo manual dispatch", o agregar de nuevo `schedule`(Si entra en conflicto con la decisión v2.1.0, simplemente conserve el comentario y modifíquelo).

### F9. ci-scripts lane Conjunto de diferencias de cobertura de prueba única:horizontal_inset / repo_hygiene / release_body_bilingual sin ninguna prueba (P2, credibilidad: alta)

- Ubicación:`ci/run_ci_checks.ps1:43-58`(13 lista de archivos de prueba) comparación `git ls-files ci/script/*.py`(29 guión)
- Evidencia: 13 guiones probados (pr_check / engineering_discipline / hardcoded_cjk / release conjunto de cuatro piezas
  por test_release_preflight / ktlint_report / detekt_debt / baseline_fail_closed(5 control de acceso a la interfaz de usuario) /
  localizations / migration_coverage / component_convergence / translation_residue /
  font_scale_clipping / row_text_width).**Prueba cero**Script de nivel de acceso:
  `check_horizontal_inset.py`(Ver F6, que es exactamente el caso huérfano de apertura fallida que más necesita protección de regresión),
  `check_repo_hygiene.py`,`check_release_body_bilingual.py`(v2.3.0 Verificación de las especificaciones de lanzamiento a partir de
  v2.5.8 Reclamaciones materiales ad18f74c"Verificación bilingüe PASS"Pero no existe una prueba de regresión para este guión);
  Herramientas dedup_strings / sync_missing_keys / translate_locales / show_detekt Ninguno (aceptable).
- Impacto: Las regresiones de tres calibres sin control de acceso a la prueba (cambios regulares, cambios de ruta) no tendrán ninguna alarma de prueba;F6 pregunta
  Si en el futuro"Fácil de reparar"No hay forma de verificarlo.
- Sugerencia: complemente la prueba única mínima para horizontal_inset (incluido el nuevo calibre que debe fallar si falta el directorio) y release_body_bilingual.
  Incluido en el manifiesto de carril de ci-scripts.

### F10. Los calibres de determinación del directorio local de los dos scripts de localización son inconsistentes.`vi` Los nuevos idiomas serán omitidos silenciosamente por check_localizations (P2, credibilidad: alta[mecanismo] / bajo[impacto actual])

- Ubicación:`ci/script/check_localizations.py:86`(`if suffix.startswith("v") or suffix in (...)`)controlar `ci/script/check_translation_residue.py:53`(`LOCALE_DIR_RE = re.compile(r"^values-([a-z]{2,3}(?:-r[A-Z]{2})?)$")`)
- Evidencia (simulación medida):`values-vi` / `values-vi-rVN` de suffix `vi` golpear `startswith("v")` ser
  check_localizations Excluya, y la expresión regular de Translation_residue coincidirá y la escaneará; dos reglas en el mismo directorio.
  Por cierto: en la lista de exclusión `"hdpiv"`,`"wdpiv"` es una entrada muerta con un calificador no válido y un error ortográfico. Almacén actual
  (values/en/es/ja/ko/pt-rBR/ru/v31,`values-v31` Sólo temas.xml (sin cadenas) no se ve afectado.
- Impacto: cuando en el futuro se agreguen vietnamita y otras configuraciones regionales que comiencen con v,strict Los controles de cordura lo ciegan permanentemente, mientras que los controles residuales de CJK
  pero lo escaneare——CI nunca interceptará las claves faltantes para nuevos paquetes de idiomas.
- Sugerencias:find_locale_dirs Utilice la misma expresión regular que Translation_residue en su lugar (`v\d+` exclusión explícita),
  Elimine fácilmente las entradas hdpiv/wdpiv muertas.

### F11. CI Falta común/accesibilidad/material3 en el directorio del informe de prueba único cargado (P2, credibilidad: alta)

- Ubicación:`.github/workflows/ci.yml:113-118`(upload-artifact path solo `app|memory|ai` de `build/reports/tests`)controlar `ci/run_ci_checks.ps1:135-143`(unit lane Realmente ejecuta 6 módulos, incluyendo `:common:/:accessibility:/:material3:testDebugUnitTest`,P4-4 Material especialmente completado3)
- Evidencia: la comparación estática es suficiente;material3 La lista de artefactos no se sincronizó cuando se incluyó P4-4 en el carril de prueba único.
- Impacto: si la prueba de tres módulos falla, el posicionamiento del informe no se puede descargar en el CI (solo se requiere reproducción local). Para un control de acceso con cobertura del 95%,
  material3 Especialmente inconveniente.
- Sugerencias:path Complemento de lista `common/build/reports/tests`,`accessibility/build/reports/tests`,`material3/build/reports/tests`.

### F12. sign.py↔App Algoritmo de firma"criterio autorizado"Las pruebas se omiten para siempre en CI (P2, credibilidad: alta)

- Ubicación:`app/src/test/java/io/zer0/muse/data/plugin/market/MarketSigningFixtureTest.kt:46`(`assumeTrue("Producto de firma no encontrado, omitir (es necesario ejecutar tools/market-signer/sign.py primero)", keysFile.isFile && catalogFile.isFile)`);fixture raíz `app/build/market-signing/out`(build/ Ignorado por .gitignore:5,CI nunca producido)
- Evidencia: control estático;v2.5.8 Transformación de la lista blanca de firmas (PluginSecurityGate.SIGNED_TOP_LEVEL_FIELDS,
  `PluginSecurityGate.kt:403-408`) tiene sólo tres líneas de defensa para lograr coherencia entre los extremos:SignedManifestFieldCoverageTest
  (App El conjunto de campos internos es autoconsistente y realmente puede ejecutarse),MarketSamplePackageTest(Las muestras almacenadas en la base de datos realmente se ejecutarán),
  MarketSigningFixtureTest(Aceptación del producto del guión,**CI saltar en asumir Verdadero**).
  Prueba real en esta revisión. `sign.py` MANIFEST_ORDER conjunto de campos con SIGNED_TOP_LEVEL_FIELDS **completamente consistente**
  (20/20, el conjunto de diferencias bidireccionales está vacío), actualmente no hay deriva——Pero esta coherencia no es vigilada continuamente por nadie en CI.
- Impacto: los estándares de serialización de sign.py y la aplicación variarán aún más en el futuro (v2.0.0 Cuando los accidentes de mercado sean del mismo tipo),CI no rojo,
  Esto solo se puede descubrir ejecutando sign.py manualmente antes de publicar.
- Sugerencia: agregue un paquete de muestra fijo usando sign.py en CI (o agréguelo en el trabajo de lanzamiento) y luego ejecute la prueba.
  Convierta la condición de omisión asumirTrue en presencia de artefactos de CI.

---

## Comprobación de elementos conocidos (no se repetirán informes en esta ronda)

1. **detekt 1.23.8 Método de derivación de fallos de compensación de firma/falsa alarma incremental de Kotlin retryFailedStep** —— Elemento conocido de la cadena de herramientas, no descubierto como (F5 Solo informa que el libro mayor de límites no está sincronizado y no implica métodos de derivación de fallos).
2. **detekt Deuda Estructurada (LargeClass/CyclomaticComplexMethod/LongParameterList/NestedBlockDepth, Libro mayor 3497)** —— No informar"La deuda existe";Los 3465 elementos medidos reales están dentro del límite (ver F5).
3. **lint baseline 1 El viejo récord no se alcanza,lint 0 error 22 warning** —— Se conoce el inventario y no se han descubierto nuevas pruebas.
4. **C3 Dos escáneres de UI tienen capacidades de detección insuficientes** —— F1 Se trata de una desviación de la línea de base (los archivos nuevos más allá de la línea de base se interceptan correctamente), no un problema de capacidad de detección del escáner, y ambos no se repiten.
5. **Cinco paquetes de idiomas distintos del inglés carecen de traducción (101 libros de contabilidad)** —— No se considera un nuevo descubrimiento; Sólo se registraron nuevas pruebas incidentalmente:es/ja/ko/pt-rBR/ru Según las mediciones reales, faltan 118 llaves cada una (+17 respecto al libro mayor, que es una evolución del mismo elemento conocido);F2 Lo que se informa es que al lenguaje estricto valores-en le falta una clave, lo cual es otro problema.
6. **La activación automática de CI remota está desactivada (workflow_dispatch only) es una decisión** —— F3 Esta decisión no se cuestiona, pero lo que se informa es la desconexión entre la verificación previa de la etiqueta y el proceso de liberación real según esta decisión;F8 Solo informa que la anotación no es consistente con el disparador.
7. **Distribuir lanzamientos de GitHub enlace directo, ninguno CDN** —— No cubierto.
8. **P1 #40-42/46/47(keystore Copia de seguridad dual fuera de línea, sondeo de caducidad de certificados, liberación de un comando)** —— No reportado repetidamente;F3 Es un problema de sincronización del número de versión que no se superpone con este grupo.
9. **La migración cubre los falsos positivos del historial de control de acceso (version = CONST Método de escritura) ha sido revisado.** —— Revisión y confirmación: medición real `check_migration_coverage.py` PASS, las tres bibliotecas se analizan correctamente (MuseDb v110 literal `MuseDb.kt:174`;FactDb v15 por `FACT_DB_VERSION` rastreo constante `FactDb.kt:53,64`;MemoryDb v5 por `MEMORY_DB_VERSION` rastreo constante `MemoryDb.kt:8,31`, el análisis de registro incluye MemoryModule.kt:43-48).
10. **Falta control de acceso/cerrado por error (B1/C2 (Revisado)** —— La revisión test_baseline_fail_closed cubre todos los controles de acceso a la interfaz de usuario PASS of 5;F6 Es el único caso aislado de este acuerdo que se ha colado en la red (horizontal_inset No existe el concepto de línea de base. Es un camino faltante más que una línea de base faltante, por lo que no cuenta como un vuelco."Fijo").
11. **v2.5.8 Adaptación de la lista blanca de firmas** —— Revisar como**Completado, sin deriva**:sign.py Bidireccionalmente consistente con el conjunto de campos de la lista blanca de aplicaciones;generate/validate_release_manifest apkVerificación de artefactos,validate_release_apks Fijación de huellas dactilares del certificado,publish_release La continuación idempotente y la comprobación remota del resumen son compatibles con el material v2.5.8 (test_release_preflight PASA TOTAL medición real).

## Instantánea de los elementos medidos reales (registro actual de esta ronda)

- PASS:check_migration_coverage(Sanku),check_detekt_debt(3465/3497),check_component_convergence(14/14),
  check_translation_residue(todos 0),check_font_scale_clipping(0/0),check_row_text_width(0),
  check_touch_target(0),check_hardcoded_font_size(0),check_icon_content_description(0),
  check_design_tokens(0 color desnudo),check_repo_hygiene(0 error/1129 warning),
  check_engineering_discipline(0 error/441 warning),check_release_body_bilingual(v2.5.8 PASS).
- FAIL:check_hardcoded_cjk(F1),check_localizations(F2).
- ci-scripts lane 13 Todos los archivos de prueba se prueban PASS.
