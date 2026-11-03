<!-- devdoc: Documentación de desarrollo interno.,No se muestra a los usuarios,LLM Consulta a través de Knowledge_search -->
# Skill Llamada a la herramienta Guía del usuario del sistema function calling

Muse El sistema Skill es una colección de herramientas para que LLM realice llamadas a funciones. Cuando el usuario pregunta"que herramientas tienes""¿Cómo puedes llamar?""skill que es""Cómo personalizar herramientas""Cómo crear habilidades""¿Puedes ayudarme a crear una habilidad?"Consulte este documento al hacerlo.

Hay 23 habilidades integradas en total(Basado en BUILT_IN_SKILLS del código fuente SkillExecutor.kt). 8 de ellos están en la lista blanca de install_skill(Definido por el usuario/LLM La implementación de la habilidad autoinstaladaKotlin solo puede hacer referencia a estos 8):

8 lista blanca skill(competencias básicas):

1. read_file — Leer el archivo de texto en el entorno limitado de la aplicación(límite superior 1MB). parámetros: path(relativamente filesDir), offset/length(Opcional,prensa"OK"segmentación,offset=Número de línea inicial,length=Número de líneas leídas,no byte). tiempo de llamada: Necesidad de leer las notas almacenadas del usuario./archivo de datos.
2. write_file — Escriba texto en un archivo sandbox. parámetros: path, content, append(Opcional,Anulación falsa predeterminada). El valor de retorno contiene una vista previa del contenido.(primeros 200 caracteres). tiempo de llamada: Necesidad de persistir notas/datos/código generado.
3. http_get — Inicie un GET HTTP. parámetros: url, headers(Opcional JSON). Límite de respuesta 1 MB. tiempo de llamada: Llame a la API REST para obtener datos.
4. http_post — Inicie una POST HTTP. parámetros: url, body(cuerda,Necesita ser serializado por usted mismo JSON), content_type(Predeterminado application/json), headers(Opcional). tiempo de llamada: Envíe datos a la API.
5. web_search — Utilice el motor de búsqueda configurado(SearXNG/Tavily/Bing)buscar en la web,título de devolución/URL/Resumen. parámetros: query, max_results(1-10,Predeterminado 5). tiempo de llamada: Los usuarios quieren información en tiempo real, últimas noticias y explicaciones de conceptos.
6. web_fetch — Obtener el texto de la página web URL especificada(Ir a la etiqueta HTML,Devolver texto sin formato). parámetros: url, headers(Opcional). El límite de rastreo es de 200.000 caracteres.,El límite de devolución es de 50.000 caracteres. tiempo de llamada: Lea el texto completo de la URL devuelta por web_search(Busque primero y luego lea dos pasos.).
7. knowledge_search — Búsqueda de texto completo en la base de conocimientos del usuario(título + contenido). parámetros: query, threshold(Opcional,0-1 sistema decimal,Predeterminado 0.3). tiempo de llamada: Los usuarios preguntan sobre las funciones propias del proyecto./Problemas relacionados con documentos importados(Documentación de desarrollo fileType=devdoc También aquí).
8. arxiv_search — arXiv Búsqueda de artículos académicos. parámetros: query(se prefiere el ingles), max_results. tiempo de llamada: investigación académica/Preguntas de ensayo.

El resto de los incorporados. skill(No en la lista blanca de install_skill,Regístrese directamente desde el sistema): delete_file(Admite procesamiento por lotes de rutas),file_exists,list_dir,file_download,read_public_file,save_to_downloads,list_public_files,list_skills,uninstall_skill,install_skill,delegate_agent,channel_reply,channel_pass,channel_read_context,task_plan,update_plan_step,generate_image,translate,generate_qr,list_stickers,send_sticker Esperar. Consulte BUILT_IN_SKILLS en SkillExecutor.kt para obtener la lista completa.

---

# Tutorial completo sobre creación de habilidades.(Usuario/LLM Universales)

## ¿Qué son las habilidades?

Habilidades(skill)es un"Empaquetar operaciones comunes en herramientas reutilizables"definición. Después de que un usuario crea una habilidad,LLM Se invocará automáticamente cuando se vean escenas apropiadas en conversaciones posteriores.,No es necesario repetir los pasos descritos cada vez.

## Tres caminos de creación

| camino | quien opera | camino | Escenarios aplicables |
|---|---|---|---|
| A. Deja que LLM te ayude a crear | conversación de usuario | decir LLM"Ayúdame a crear una habilidad XX",LLM Hecho con install_skill | más común,Recomendado |
| B. Importe el archivo .skill.json | Usuario | ajustes → Skill archivo de importación de página | Compartir/copia de seguridad/Migrar |
| C. Escribir código y paquete .muse-plugin | Desarrollador | manifest.json + JS guión | habilidades complejas/JS motor |

**La ruta A es la recomendada**:Los usuarios no necesitan conocer ningún formato.,Habla directamente con LLM"haz uno para mi'revisa la receta'Habilidades",LLM Responsable de generar .skill.json e instalarlo.

## .skill.json formato completo(install_skill / Importación de archivos generales)

```json
{
  "id": "fetch_recipe",
  "name": "revisa la receta",
  "description": "Consulte la receta y la lista de ingredientes para ver recetas caseras según el nombre del plato. Se utiliza cuando el usuario pregunta cómo hacer un determinado plato.",
  "parametersJson": "{\"type\":\"object\",\"properties\":{\"dish\":{\"type\":\"string\",\"description\":\"nombre del plato,Como por ejemplo'cerdo estofado'\"}},\"required\":[\"dish\"]}",
  "requiredJson": "[\"dish\"]",
  "implementationKotlin": "web_search",
  "category": "custom"
}
```

## Descripción del campo

| campo | Requerido | Descripción | reglas |
|---|---|---|---|
| id | ✅ | Identificador único de habilidad | letras minúsculas/numeros/subrayado/guión,Como por ejemplo `fetch_recipe`;No puede tener el mismo nombre que una habilidad/herramienta integrada(entrará en conflicto) |
| name | ✅ | nombre para mostrar | El chino es suficiente.,Como por ejemplo"revisa la receta" |
| description | ✅ | Instrucciones para el LLM | **lo mas importante**:Escribe claramente"Qué escenarios usar, qué ingresar, qué generar";Un LLM bien redactado sabrá cuándo llamar |
| parametersJson | Opcional | Definición de parámetros | JSON Schema forma de cadena(Ver escritura de parámetros a continuación) |
| requiredJson | Opcional | Parámetros requeridos | cadena de matriz,Como por ejemplo `["dish"]` |
| implementationKotlin | ✅ | implementación subyacente | **Solo puedes elegir una de las 8 listas blancas.**:read_file / write_file / http_get / http_post / web_search / web_fetch / knowledge_search / arxiv_search |
| category | Opcional | Clasificación | Predeterminado `custom` |

## parametersJson Método de escritura de parámetros(JSON Schema)

```json
{
  "type": "object",
  "properties": {
    "Nombre del parámetro1": { "type": "string", "description": "Descripción del parámetro,chino" },
    "Nombre del parámetro2": { "type": "integer", "description": "Límite de cantidad" },
    "Nombre del parámetro3": { "type": "boolean", "description": "Ya sea para encender" }
  },
  "required": ["Nombre del parámetro1"]
}
```

- type apoyo: string / integer / number / boolean / array / object
- description Escribe claramente en chino,LLM Confíe en él para decidir qué valor pasar
- required Listar los parámetros requeridos;No introduzca parámetros no obligatorios.

## Elija la implementación según el uso(El paso más crítico)

| Habilidades que quieres hacer | Elija esta implementación | Ejemplo |
|---|---|---|
| Consultar información/buscar en la web | web_search | Consulta recetas, películas, guías. |
| Leer el texto completo de la página web. | web_fetch | Leer artículos, leer anuncios. |
| Llame a la API para obtener datos | http_get | Consultar tipos de cambio, consultar entrega urgente, consultar el tiempo API |
| Enviar datos a API | http_post | Enviar mensajes y enviar formularios |
| Leer y escribir archivos locales | read_file / write_file | Libro de contabilidad, tareas pendientes, favoritos |
| Consulta la base de conocimientos/Documentación interna | knowledge_search | Consultar documentos importados por los usuarios. |
| Consultar trabajos académicos. | arxiv_search | búsqueda de papel |

## install_skill Ejemplo de llamada(LLM perspectiva)

Cuando el usuario dice"Ayúdame a crear una habilidad que pueda buscar recetas."tiempo,LLM debería:

1. Confirmar implementación: revisa la receta → web_search
2. estructura skill_json(Tenga en cuenta que parámetrosJson es una cadena,necesidad de escapar):
```
install_skill(skill_json = {"id":"fetch_recipe","name":"revisa la receta","description":"Busque recetas e ingredientes caseros según el nombre del plato. Se llama cuando el usuario pregunta cómo cocinar un determinado plato.","parametersJson":"{\"type\":\"object\",\"properties\":{\"dish\":{\"type\":\"string\",\"description\":\"nombre del plato\"}},\"required\":[\"dish\"]}","requiredJson":"[\"dish\"]","implementationKotlin":"web_search","category":"custom"})
```
3. Notificar al usuario después de una instalación exitosa:"Creado'revisa la receta'Habilidades,en la configuración → Skill Se pueden ver las páginas/Desactivar. Ahora puedes preguntarme cómo cocinar cualquier plato."

## Errores y correcciones comunes

| error | Razón | Reparación |
|---|---|---|
| "Falta el campo de identificación" | no escribí id | suplemento id,usar minúsculas slug |
| "Falta implementación del campo Kotlin" | No escribí la implementación. | Añade una de las 8 listas blancas |
| "Implementación no permitida" | implementationKotlin No en la lista blanca | Cambiar la implementación de la lista blanca,O dividir en múltiples habilidades |
| "id Conflicto con incorporado" | id Pulsa read_file/calculadora, etc. | Cambiar a uno que no se repita id |
| "Error de formato de parámetro" | parametersJson No legal JSON Schema | Reescribir de acuerdo con el método de escritura de parámetros anterior |
| La habilidad está instalada pero no tiene efecto. | description Escrito demasiado vagamente,LLM No se cuando llamar | Reescribir descripción: qué escenario, entrada, salida |
| Quiere la habilidad para ejecutar"código arbitrario" | No compatible | Las habilidades solo se pueden implementar reutilizando la lista blanca,Este es un diseño seguro. |

## Gestión post-instalación

- Ver/Iniciar y detener: ajustes → Skill página
- Eliminar: ajustes → Skill página(o uso de LLM uninstall_skill)
- Compartir: Exportar archivos .skill.json a otros,La otra parte lo importa en la página de Habilidad.

## Habilidades de palabras rápidas(prompt skill)

excepto"Implementación de la lista blanca"Habilidades fuera del tipo,También puedes crear**Habilidades de palabras rápidas**:`implementationKotlin` pasar `"prompt"`,El texto del comando se coloca en `prompt` campo. no es un fragmento de código,más bien"Plantilla de comando fija",Al llamar, devuelva el modelo tal cual para su ejecución.——Adecuado para empaquetar palabras clave de uso común en habilidades(Como por ejemplo"Redactar informes semanales en un formato fijo.""Reescribir la copia en un estilo específico").

- Parámetros de llamada: Se puede pasar opcional al llamar al modelo. `input`(Texto de solicitud específico). Las instrucciones incluyen `{{input}}` Los marcadores de posición se reemplazan con entradas;Si no hay ningún marcador de posición, la entrada se agrega al final del comando.
- adecuado para: Tareas con estilos de salida fijos y sin necesidad de fuentes de datos externas;Se requiere conexión a Internet/Las habilidades comunes todavía se implementan usando la lista blanca al leer y escribir archivos.

## Skill Aún así Plugin?como elegir

| demanda | elegir | Razón |
|---|---|---|
| palabras rápidas comunes/Embalaje y reutilización del formato de escritura. | Skill(Habilidades de palabras rápidas) | código cero,Instalar y usar,Compartible .skill.json |
| Consulta la pagina web/API, leer y escribir archivos y otras operaciones estándar | Skill(Implementación de la lista blanca) | Reutilice las capacidades integradas dentro de los límites de seguridad |
| Requiere lógica JS arbitraria, flujo de control complejo y complementos de acceso API | Plugin | puede escribir cualquier JS,Capacidad cercana a los programas pequeños.;Requiere la firma del usuario antes de la ejecución. |
| acción de alto riesgo | Ambos están sujetos a aprobación.;Plugin Se requiere firma adicional | Diseño seguro |

una palabra: **Skill si"Plantilla + herramienta de lista blanca",Plugin si"Expansión programable"**. Ligero, compartible y sin código → Skill;Complejo, requiere programación, dispuesto a pasar por el proceso de firma. → Plugin. Sugerir primero cuando no esté seguro Skill(Más seguro y ligero).

## El sistema solicita la inyección(Límites dados a LLM)

- La habilidad es"Llamada bajo demanda"herramientas,No se ajusta automáticamente para cada conversación;Determinar según la intención del usuario
- Cuando los usuarios quieren personalizar herramientas,oferta"Puedo ayudarte a crear una habilidad.",y completar con install_skill,No te limites a dar pasos
- La implementación restringida de las habilidades del usuario es un diseño de seguridad.,No prometas lo que no puedes cumplir"ejecución de código arbitrario"
