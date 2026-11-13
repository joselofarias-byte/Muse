# Revisión transversal del español — 13 de noviembre de 2026

Base: `03c7b6ef8f1c06cffa7584d84874805e0a0e6cbc`. Esta revisión continúa la lectura completa del catálogo registrada en `spanish-review-2026-11-13.md`.

## Cambios

- Android: 26 recursos existentes corregidos y 73 recursos extraídos de literales. Los nuevos recursos tienen versiones en chino, inglés y español.
- Se localizaron pantallas virtuales, recuerdos fijados, identificadores de subtareas, ajustes de depuración, muestras de componentes, el widget, la marca de las tarjetas exportadas y el marcador de imágenes de WeChat.
- La pregunta de la prueba de visión usa el idioma seleccionado.
- Los prefijos de reintento cancelado, agotado o que exige intervención también usan traducciones. El clasificador de resultados reconoce los nuevos marcadores en español e inglés para conservar el estado de fallo.
- Web Host: 38 textos corregidos, incluida la distinción entre conversaciones y sesiones de acceso, y entre solicitudes y mensajes en diagnóstico. Web SaaS: 3 etiquetas unificadas.
- Administración web: 39 textos traducibles, selección del español según el navegador, errores locales y pruebas de renderizado. Los importes siguen siendo CNY: las etiquetas aclaran que los formularios reciben centavos de yuan.
- Web de compatibilidad: 33 etiquetas de la plantilla estática usan recursos localizados antes de servir la página. Los mensajes recibidos y los identificadores del protocolo permanecen intactos.
- El módulo visible se llama «Teléfono virtual». Se siguió `MiniPhoneFeature.ROOM` hasta `RoomPage`: el acceso abre una habitación interactiva, por lo que conserva «Casa virtual» y no se confunde con «Inicio».

## Comprobado

- Cobertura y estructura de los cuatro módulos Android; claves, XML, plurales, placeholders, residuos de chino y ejemplos truncados.
- 24 pruebas de comprobadores: 12 de calidad, 7 de localizaciones, 3 de detección de literales y 2 de la página web de compatibilidad. Estas últimas también analizan JavaScript en chino, inglés y español con Node.
- Barrido de 586 archivos Kotlin de capas de interfaz y widgets: cero literales directos de texto visible en chino. Este análisis no es un AST y no cubre todas las expresiones calculadas.
- Suite completa del cliente web, comprobación TypeScript y compilación Vite aprobadas.
- Administración web: pruebas de 39 entradas, prioridad de idioma, placeholders, importes CNY y 2 pantallas renderizadas; TypeScript y Vite aprobados.
- Argumentos de recursos y control de residuos aprobados. Localizaciones estrictas en/es completas. `git diff --check` aprobado.

## Límites y comprobación heredada

No se ejecutaron la compilación ni las pruebas Android en esta revisión. Se ampliaron los tests Android de recursos y de clasificación de estados; su ejecución y la comprobación visual en dispositivo siguen pendientes.

El comprobador general anterior `check_hardcoded_cjk.py` sigue fallando por el mismo inventario que en la base: `GreetingHelper.kt` (103 frente a 43 del baseline), `ChatMessageController.kt` (2 frente a 0) y `ChatTaskCardCoordinator.kt` (6 frente a 4). Se reprodujo el resultado sobre los archivos exactos del commit base para comprobar que esta revisión no incrementa esas cifras. Incluye alias de reconocimiento y textos de respaldo; no equivale al inventario de etiquetas visibles. No se aumentó el baseline para ocultarlo.

No hubo merge ni publicación de APK.
