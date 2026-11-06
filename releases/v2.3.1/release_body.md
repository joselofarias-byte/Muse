# Muse v2.3.1

Esta vez, la atención se centra en la importación y la estabilidad de la base de conocimientos: los paquetes comprimidos se pueden importar directamente y la base de datos se crea automáticamente, y las importaciones de archivos grandes ya no fallan ni se atascan; al mismo tiempo, el panel de registro de fallos está vacío y el problema está solucionado.Agent Problemas como el enhebrado ocasional de sesiones en páginas.

## Correcciones importantes

- **44MB Fallo al importar paquete comprimido**: Se corrigió la causa raíz——Anteriormente, los archivos y binarios se leían e indexaban como texto sin formato. Ahora el paquete comprimido será reconocido correctamente y ya no fallará; Otros archivos binarios también se bloquearán en la página principal de la base de conocimientos y se mostrará un mensaje, y el contenido confuso ya no se escribirá en la base de conocimientos.
- **El panel de registro de fallos y la exportación están vacíos**: El registro de fallos se leyó antes desde el directorio incorrecto. Después de la actualización, los registros históricos de fallos se mostrarán normalmente y se podrán exportar.
- **Agent Página sesión esporádica**: ya no aparece al cambiar rápidamente de sesión o asistente"El contenido de otra conversación parpadea".
- **La importación fue exitosa pero aún se muestra"0 documentos"**: La cantidad de documentos de la base de conocimientos se ha cambiado a estadísticas en tiempo real y se mostrarán correctamente inmediatamente después de la importación o eliminación.
- **Interfaz atascada de importación de archivos grandes**: Las operaciones de CPU y disco de fragmentación de documentos se eliminan del hilo principal y la interfaz permanece disponible durante el proceso de importación.

## nuevas capacidades

- **Importar directamente el paquete comprimido**: Seleccione zip en la página de administración de la base de conocimientos, la aplicación se descomprimirá automáticamente, creará una nueva base de conocimientos con el nombre del paquete comprimido e importará los documentos uno por uno (compatible md / txt / csv / json / log / pdf / docx / doc / epub / pptx). Los directorios, archivos ocultos y metadatos de macOS se omiten automáticamente, hasta un máximo de 500 entradas procesadas a la vez.

## Mejoras en la experiencia

- **Entrada unificada de descarga y actualización**: Indicaciones y configuraciones de actualización de la página de inicio → Con respecto al botón de descarga en , ahora todos abren la página de descarga del sitio web oficial y la conexión directa nacional es más estable.
- Completé la traducción de la terminal, el chat grupal, la herramienta y la copia relacionada con la búsqueda en 7 idiomas (chino/inglés/español/japonés/coreano/portugués/ruso).

---

# Muse v2.3.1 (English)

This release focuses on knowledge-base import and stability: archives can be imported directly and become their own knowledge base, and large-file imports no longer crash or freeze. It also fixes an empty crash-log panel and occasional session mix-ups on the Agent page.

## Important Fixes

- **Crash when importing a 44 MB archive**: fixed the root cause - archives and binary files were previously read as plain text and indexed. Archives are now recognized correctly and no longer crash; other binary files are also rejected on the main knowledge page with a clear message, instead of writing garbled content into your knowledge base.
- **Empty crash-log panel and export**: crash logs were being read from the wrong directory; existing crash records now show up and can be exported.
- **Occasional session mix-ups on the Agent page**: rapidly switching sessions or assistants no longer makes another conversation's content flash on screen.
- **Import succeeded but still shows "0 documents"**: document counts are now computed live, so they are correct immediately after importing or deleting.
- **Large-file imports freezing the screen**: chunking work (CPU and disk) now runs off the main thread, so the interface stays responsive while importing.

## New Capabilities

- **Import archives directly**: pick a zip in the knowledge-base management page and the app will extract it, create a new knowledge base named after the archive, and import the documents inside one by one (md / txt / csv / json / log / pdf / docx / doc / epub / pptx are supported). Directories, hidden files, and macOS metadata are skipped, up to 500 entries per import.

## Experience Improvements

- **Unified download and update entry**: the update banner on the home screen and the download button under Settings -> About now both open the official download page, which is faster to reach from mainland China.
- Completed the translations for terminal, group-chat, tool, and search strings in all seven languages (Chinese, English, Spanish, Japanese, Korean, Portuguese, Russian).
