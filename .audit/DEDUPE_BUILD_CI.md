# Enlace 8: Construcción/Puerta CI/Proyecto de lanzamiento — Línea base de deduplicación

## Solución confirmada/Decisión (no más informes)
- cinco carriles (ci-scripts/lanes/static/unit/debug) Registros verdes completos locales (sujetos al último lote).
- Se ha solucionado el error UnicodeEncodeError de la consola china (PYTHONIOENCODING=utf-8 + Reconfiguración de la salida estándar del script).
- vdserver lintVitalRelease del ensamblado de nivel raíz se ha corregido (el nivel del módulo exime dos reglas).
- La activación automática de CI remota está desactivada (workflow_dispatch only) es una decisión.
- 11 Control de acceso al script del artículo 2026-09-29 Todo borrado (reparación real); falta control de acceso/cerrado por error (B1/C2 ha sido revisado).
- detekt 1.23.8 Se ha registrado la solución alternativa para el error de compensación de firma (borre el campo primero).→detektBaseline→Borre el tipo nuevamente).
- Kotlin Error falso de compilación incremental retryFailedStep (volver a ejecutar la recuperación,CI Limpiar primero si ocurre recurrencia)——Problemas conocidos de la cadena de herramientas.
- lint baseline 1 Registro antiguo perdido (stock conocido);lint 0 error 22 warning dentro de la línea de base.
- strict locale El control de acceso bloquea nuevos espacios normalmente (se usa para bloquear valores; faltan 10 claves).
- Distribución:GitHub Releases Cadena recta (v2.4.2 (de ), sin CDN;dl.museai.ltd La desactivación no requiere renovación.

## Se sabe que no está resuelto (se pueden agregar nuevas pruebas citando el número)
- C3: Los dos escáneres de UI tienen capacidades de detección insuficientes y revelarán una gran cantidad de inventario después de la corrección.——La línea de base no se puede eliminar y se requiere una revisión manual (punto de estancamiento conocido).
- P1 #40-42/46/47 Automatización de versiones y recuperación ante desastres (keystore Copia de seguridad dual fuera de línea, sondeo de caducidad de certificados, liberación de un comando)——Verificar lo que se ha hecho.
- P1 #43-45,#47-48 Piezas pendientes de reparación.

## La dirección de exploración clave de este enlace.
1. **ci/script/*.py Desviarse de la base del código actual**:archivo de referencia (ktlint/detekt/lint/component_convergence/row_text_width) todavía coincide con el código fuente real; la ruta codificada en el script/nombre del módulo/Si el umbral deja de ser válido con la evolución v2.5.x (como la nueva accessibility-provider/virtual-display-* incluido en el alcance de la inspección).
2. check_migration_coverage si FactDb v15 / MemoryDb v5 La corrección del análisis de (se había debido a"version = CONST Método de escritura"falso positivo——Verifique que las tres bases de datos se puedan analizar).
3. Publicar cadena de script (validate_release_apks/generate_release_manifest/publish_release) para material v2.5.8 (manifest Cambios de firma en lista blanca) adaptación.
4. CI El script se prueba a sí mismo (ci-scripts lane test/) qué scripts están cubiertos y qué scripts nuevos faltan.
5. Gradle Deriva de configuración:build.gradle.kts Si el historial de versiones en los comentarios omite la versión 2.5.x; La verificación de inyección del número de versión prueba la ruta de la variable de entorno VERSION_CODE.
6. .gitattributes/.editorconfig Coherencia de la política de fin de línea con el estado real del archivo del repositorio (históricamente, el ruido CRLF se ha producido repetidamente——Compruebe si .gitattributes realmente bloquea LF y si falta algún tipo de archivo).
