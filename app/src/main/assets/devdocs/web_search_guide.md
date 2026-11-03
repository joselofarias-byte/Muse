<!-- devdoc: Documentación de desarrollo interno.,No se muestra a los usuarios,LLM Consulta a través de Knowledge_search -->
# Guía de búsqueda en Internet versión completa

> escena desencadenante: Usuario preguntó"Cómo habilitar la búsqueda en Internet""Cómo configurar el motor de búsqueda""Resultados de búsqueda incorrectos""Tiempo de espera de búsqueda""web_search ¿Cuál es la diferencia entre web_fetch y web_fetch?""Bing/Tavily/SearXNG como elegir""quieres API key"tiempo,Consulte este documento y responda con sinceridad.
> Este documento está basado en la implementación real del código fuente WebSearchService.kt / SkillExecutor.kt,Está prohibido inventar proveedores o parámetros que no existen.

## 1. Descripción general

Muse de las búsquedas en Internet se basan en dos herramientas:
- **web_search**: búsqueda de palabras clave,Título de retorno + URL + lista resumida(Elija para LLM)
- **web_fetch**: Obtener el texto de la página web de la URL especificada(Leer artículo completo)

La búsqueda es implementada por tres proveedores.,Cambiar por configuración de usuario:Bing(Predeterminado,Gratis key)/ SearXNG(Autohospedado,Gratis key)/ Tavily(necesidad API key).

Campo de entrada en el chat"+"No. se abre el menú"búsqueda en internet"después,LLM Decide de forma autónoma cuándo llamar a estas dos herramientas;No enciendas este interruptor,LLM También se puede llamar a pedido(Las herramientas siempre están en el esquema.),El interruptor solo hace el modelo."Más inclinado a buscar"consejos.

## 2. Explicación detallada de tres tipos de Proveedores

### 1. BingProvider(Predeterminado)

| artículo | valor |
|---|---|
| URL | `https://cn.bing.com/search?q={codificadoquery}&count={maxResults*2}` |
| darse cuenta | HTML Rastreo + análisis regular |
| API key | No es necesario |
| analizar | partido `<li class="b_algo">` bloquear;dentro del bloque `<h2><a href="URL">title</a></h2>` Obtener título y enlace;Primero los resúmenes `<p class="b_lineclamp...">`,revertir `<div class="b_caption">` dentro `<p>` |
| User-Agent | `Mozilla/5.0 (Linux; Android 12) muse/1.0` |
| Características | Configuración cero lista para usar;Analizar la estructura de la página de Bing dependiente,La revisión de la página puede no ser válida(Devolver resultados vacíos en este momento,No chocará) |

### 2. SearXNGProvider(Autohospedado)

| artículo | valor |
|---|---|
| URL | `{endpoint}/search?q={query}&format=json&categories=general&language=zh-CN` |
| Predeterminado endpoint | `https://searx.be`(instancia publica,Puede ser inestable) |
| darse cuenta | JSON analizar `results` matriz,tomar `title` / `url` / `content` |
| API key | No es necesario(Instancia autohospedada;Las instancias públicas pueden tener un flujo de corriente limitado) |
| Configuración | ajustes → Modelos y Servicios → Web Buscar,Complete la instancia de construcción propia endpoint |
| Características | Privacidad amigable y controlable;Debes implementar la instancia tú mismo para que sea estable. |

### 3. TavilyProvider(API)

| artículo | valor |
|---|---|
| URL | `POST {endpoint}/search`,Predeterminado `https://api.tavily.com` |
| Body | `{ api_key, query, max_results, search_depth="basic" }` |
| API key | **requerido**,Ninguna clave devuelve una lista vacía |
| Configuración | ajustes → Modelos y Servicios → Web Buscar,rellenar API key |
| Características | Resultados estables y de alta calidad.;Pero debes presentar tu solicitud en tavily.com. key(Cuota libre) |

## Tres.Provider Conmutación y configuración

### Entrada de configuración
1. **ajustes → Modelos y Servicios → Web Buscar**: Configuración provider / apiKey / endpoint(Configuración avanzada)
2. **ajustes → charlar → Motor de búsqueda predeterminado**: Cambio rápido auto / searxng / tavily / bing

### mecanismo de distribución
- `CompositeWebSearchService` Según `WebSearchConfig.providerName` correspondencia de construcción delegate
- Nombre de soporte: `"SearXNG"` / `"Tavily"` / `"Bing"`(Predeterminado `"Bing"`)
- El cambio de proveedores en tiempo de ejecución se reconstruirá delegate(synchronized sincronizar,El cambio surte efecto inmediatamente)

## Cuatro.web_fetch Detalles de implementación(Capturar texto de página web)

- usar OkHttpClient(named("chat")) enviar GET,User-Agent: `Mozilla/5.0 (Android LLM client)`
- **El límite de rastreo es de 200.000 caracteres.**(take(200_000))
- **Límite de devolución 50.000 caracteres**(take(50_000))
- htmlToText cadena de procesamiento: Primero elimine el bloque script/style/noscript → Volver a todas las etiquetas HTML → Finalmente Html.fromHtml maneja la entidad(&amp; &lt; espera)
- No hay necesidad Jsoup(Evite aumentar el tamaño del APK)
- Contraer espacios en blanco continuos

## 5. Tiempo de espera y parámetros de red

| cliente | connectTimeout | readTimeout | writeTimeout | Propósito |
|---|---|---|---|---|
| named("chat") | 30s | 120s | 30s | web_fetch / Solicitudes generales de LLM |
| named("webSearch") | 8s | 15s | - | web_search dedicado |

web_search Se necesitan 8 segundos para conectarse + 15 segundos para leer,Las instancias públicas cuya búsqueda es lenta son propensas a tiempos de espera(Se comporta como si devolviera un resultado vacío.),En este momento, se recomienda cambiar de proveedor o crear su propio SearXNG.

## 6. Preguntas frecuentes del usuario Q&A

1. **"Cómo habilitar la búsqueda en Internet"**: Campo de entrada de chat + menú numérico → encender"búsqueda en internet". Luego haga preguntas que requieran información en tiempo real.(Noticias/precio/política/versión)tiempo,LLM web_search se llamará automáticamente.
2. **"Resultados de búsqueda incorrectos/No encontrado"**: ①Confirmar usando Bing(Predeterminado)Y la red es normal.;②Bing La revisión de la página puede afectar el análisis,Esperando actualización;③Se puede cambiar a Tavily(necesidad key)O construye tu propio SearXNG, que es más estable;④Sea lo más específico posible con los términos de búsqueda(palabras clave chinas,Evite hablar con oraciones completas).
3. **"quieres API key"**: Bing No es necesario con SearXNG autohospedado;Tavily Necesario.
4. **"web_search ¿Cuál es la diferencia entre web_fetch y web_fetch?"**: web_search título de devolución/URL/lista resumida(Para posicionamiento);web_fetch Capture el texto completo de la URL especificada(Para lectura intensiva). La postura correcta es"Busca primero y luego lee.":web_search encontrar candidatos URL → web_fetch Lee el contenido.
5. **"¿Los resultados de búsqueda son en tiempo real?"**: si,Cada llamada es una petición en tiempo real al buscador,No caché.
6. **"¿La búsqueda revelará mi privacidad?"**: La solicitud de búsqueda va a la configuración de red del sistema;Bing Las consultas se enviarán a Microsoft,SearXNG autohospedado y centrado en la privacidad está disponible.
7. **"¿Por qué las búsquedas muestran resultados en inglés?"**: Bing Ir al nombre de dominio cn,La mayoría de las consultas chinas tienen resultados chinos.;SearXNG Predeterminado language=zh-CN.Tavily Los resultados están principalmente en inglés.
8. **"Qué hacer si la búsqueda se agota"**: comprobar la red/agente;La instancia pública de SearXNG es inestable y se recomienda reemplazarla.;Tavily Confirme que la clave sea válida y que no se haya excedido el límite.

## 7.LLM Puntos de llamada

- **cuando usar web_search**: Los usuarios piden información en tiempo real(Noticias/precio/política/clima/última versión/Situación actual de las personas.), Hechos que requieren verificación externa, requisitos explícitos del usuario."Compruébalo/Buscar"tiempo.
- **cuando usar web_fetch**: web_search Entre las URL candidatas devueltas,Título/Cuando el resumen no alcanza para responder;Cuando el usuario proporciona una URL específica y solicita leer el contenido.
- **parámetros**: query Utilice palabras clave refinadas(No es una frase hablada completa);max_results Predeterminado 5,Máximo 10.
- **frontera**: Una sola búsqueda devuelve hasta 10 artículos;El texto de la página web supera los 50.000 caracteres y está truncado;Error de recuperación(tiempo de espera/404/Subir hacia atrás)explica sinceramente,No inventes cosas.
- **secuencia**: Primer posicionamiento web_search → Entonces web_fetch lectura intensiva,No te saltes la búsqueda y pasa directamente a adivinar la URL.
- **No se pudo bajar de categoría**: La búsqueda devuelve vacía/tiempo de espera,No inventes resultados;decirle al usuario"El canal de búsqueda actual no está disponible",Se recomienda consultar la red o cambiar de proveedor.

## 8. Tabla de resolución de problemas

| Síntomas | Posibles razones | solución de problemas |
|---|---|---|
| web_search Resultados siempre vacíos | Bing Fallo de análisis/fallo de red/intercepción de proxy | Abra el registro de depuración y mírelo. WebSearchService;Intenta cambiar provider |
| El cambio de proveedor no tiene efecto | Error al guardar la configuración | Confirme que la página de configuración se haya guardado correctamente;CompositeWebSearchService es una reconstrucción en tiempo de ejecución,No es necesario reiniciar |
| web_fetch No puedo captar el texto | Anti-rastreo del sitio de destino/renderizado JS(Carga dinámica de contenidos.) | HTML estático rastreable;JS Se espera que no se pueda capturar la página renderizada.;informar a los usuarios |
| Tavily Resultado vacío | key No válido/límite excedido/punto final incorrectamente completado | comprobar key;Tavily Comprueba el uso en la consola. |
| Búsqueda lenta | Congestión de instancias públicas de SearXNG | Instancia autoconstruida;o intercambiar Bing(Predeterminado,más rápido) |
