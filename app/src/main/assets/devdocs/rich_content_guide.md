<!-- devdoc: Documentación de desarrollo interno.,No se muestra a los usuarios,LLM Consulta a través de Knowledge_search -->
# La guía completa para la renderización de contenido enriquecido

> escena desencadenante: Usuario preguntó"Cómo publicar fotos""¿Se puede reconocer el texto a partir de imágenes?""AI código en respuesta/Cómo mostrar la fórmula""¿Puedo enviar un vídeo?""Cómo cargar archivos adjuntos"tiempo,Consulte este documento y responda con sinceridad.
> Este documento se basa en el componente de renderizado Markdown del código fuente ui/chat/.MessageBubble procesamiento de contenido enriquecido,AttachmentChip.kt verdadera realización.

## 1. Descripción general

Muse El chat admite contenido rico y rico:
- **AI Lado de salida**: Markdown Representación completa(resaltado de código/LaTeX fórmula/mesa/Cotización/lista/fotos/vídeo)
- **Lado de entrada del usuario**: fotos(Compresión/OCR), vídeos, archivos adjuntos y procesamiento inteligente de contenido pegado

## 2.Markdown Rango de soporte de renderizado

| Tipo | apoyo | Descripción |
|---|---|---|
| bloque de código | ✅ | resaltado de sintaxis(CodeHighlighter),Admite idiomas comunes |
| código en línea | ✅ | `code` |
| LaTeX fórmula | ✅ | $...$ Dentro de la industria / $$...$$ nivel de bloque(FormulaView) |
| mesa | ✅ | GFM mesa |
| bloque de cotización | ✅ | > Cotización |
| lista | ✅ | ordenado/desorden/anidado |
| Título | ✅ | # ~ ###### |
| Negrita/cursiva/tachado | ✅ | **bold** *italic* ~~strike~~ |
| enlace | ✅ | [text](url),Haga clic para abrir |
| fotos | ✅ | ![alt](url),Transmisión de renderizado en línea |
| lista de tareas | ✅ | - [x] Artículos completos |
| linea horizontal | ✅ | --- |

Nota: Markdown Soporte limitado para algunas etiquetas incrustadas en HTML;Las fórmulas matemáticas se basan en implementaciones similares a KaTeX,Si la fórmula es demasiado compleja, la representación puede fallar.(Mantener el texto original.).

## 3. Capacidad de imagen

### 3.1 Usuario envía foto
- entrada: Campo de entrada + signo → Imágenes/Cámara
- **Compresión**: Comprimir automáticamente antes de enviar,Reducir el tráfico y la memoria
- **OCR**: Procesamiento automático de imágenes ML Kit reconocimiento de texto sin conexión(chino+ingles),Los resultados del reconocimiento se pueden utilizar como contexto para LLM
- Varias imágenes: Admite varias imágenes a la vez(Máximo 4 tarjetas,folleto ≤2MB Enviar restricciones)

### 3.2 AI Fotos en respuestas
- Transmisión de datos de representación URI/diagrama de red
- **SmartImage decodificar**: Primero detecta el tamaño con inJustDecodeBounds → inSampleSize Reducción de resolución OOM → explícito después de la visualización recycle()
- Haga clic en la imagen para ampliarla

## 4. Capacidades de vídeo

- Los usuarios pueden enviar archivos de video locales.
- AI Generar vídeo(generate_video Herramientas)Reproducir en línea en el mensaje
- El jugador apoya el progreso./pantalla completa(Utilice el reproductor del sistema o integrado)

## 5. Archivos adjuntos

- Los usuarios pueden adjuntar cualquier archivo.(AttachmentChip Mostrar nombre de archivo/tamaño/Tipo)
- Los archivos adjuntos están referenciados por URI,No ocupa texto del mensaje
- AI Los archivos se pueden procesar a través de file_download / save_to_downloads
- Al pegar texto largo se detecta automáticamente como un archivo(>5000 personaje)Solicitar transferir el archivo adjunto

## 6. Límites y restricciones del contenido enriquecido

- Folleto de imagen base64 ≤2MB,Descartar y avisar cuando se excede el límite
- Máximo 4 imágenes a la vez
- El contenido rastreado en la web que supera los 50.000 caracteres se trunca
- Colapsar automáticamente mensajes de IA súper largos
- Audio: TTS leer en voz alta,No reproducir archivos de grabación en línea

## 7. Preguntas frecuentes Q&A

1. **"¿Puede la IA ver la imagen después de enviarla?"**: puede,Después de la compresión de imágenes, utilice canales multimodales.;Los modelos de texto sin formato se degradan automáticamente a descripciones de texto OCR.
2. **"¿Por qué la fórmula de respuesta de la IA muestra caracteres confusos?"**: Es posible que el LaTeX complejo no se renderice,Se mostrará el texto original.;Puedes dejar que la IA cambie a escritura simple.
3. **"Cómo enviar archivos grandes"**: Enviar como archivo adjunto;Para archivos muy grandes, se recomienda colocarlos primero en el disco de red para vincularlos.
4. **"¿Se pueden enviar vídeos a la IA para su análisis?"**: Soporte para enviar archivos de video,Pero lo que analiza la IA es el nombre del archivo/Metadatos(Cuando no hay capacidad de comprensión de video),Se requieren capturas de pantalla.
5. **"¿La compresión de la imagen la hará borrosa?"**: Claridad garantizada por compresión,Suficiente para escenarios generales;La imagen original permanece en el álbum de fotos.

## 8.LLM Puntos de llamada

- Se puede utilizar de forma natural en las respuestas. Markdown(código/mesa/fórmula/lista),UI se renderizará correctamente
- Se utiliza para generar imágenes. generate_image,Para vídeo generate_video,Los resultados se insertan automáticamente
- Al citar imágenes de usuarios, describe el contenido en lugar de pretender ver la imagen original.(Escena del modelo de texto)
- Utilice títulos para contenido extenso/Estructuración de listas,Evite grandes bloques de texto sin formato
