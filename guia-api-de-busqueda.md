# Cómo conseguir una API de búsqueda

Muse trae **13** servicios de búsqueda y rastreo. Se parten en dos: **sin clave**, listos para usar, y **con clave**, que pedís en el sitio del proveedor. Acá está, uno por uno, dónde pedirla, cómo cargarla, qué hace bien y qué cuota gratis ofrece.

> Entrada: **Ajustes → Búsqueda web**. Elige el proveedor y completa «API Key». Algunos también dejan cambiar la dirección de la interfaz (endpoint). Si la dejas vacía, se usa la dirección por defecto.
>
> Si no quieres dar de alta nada: **Bing HTTP, Baidu, SearXNG y Jina** funcionan sin cuenta.

---

## 1. Sin clave

### 1. Bing HTTP

- **Tipo**: rastreo de la página de resultados.
- **Alta**: no hace falta.
- **Cómo usarlo**: en Ajustes, deja la ruta por defecto en `Bing HTTP`.
- **Qué hace bien**: los resultados en chino suelen ser buenos. La estabilidad depende de cómo esté armada la página de Bing, y a veces cambia.
- **Ojo**: no es la API oficial. Si pides demasiado, te pueden limitar.

### 2. Baidu

- **Tipo**: rastreo de la página de resultados.
- **Alta**: no hace falta.
- **Cómo usarlo**: elige `Baidu`.
- **Qué hace bien**: es la cobertura más amplia en chino, sobre todo para contenido de China.
- **Ojo**: también es un rastreador. El análisis depende de la estructura de la página de Baidu.

### 3. SearXNG

- **Tipo**: metabúsqueda. Junta varios motores.
- **Alta**: no hace falta si usas una instancia pública. También puedes levantar la tuya.
- **Dirección por defecto**: `https://searx.be`
- **Cómo usarlo**: elige `SearXNG`. Si tienes una instancia propia, pon su dominio en «Dirección de la interfaz».
- **Qué hace bien**: cuida la privacidad y trae resultados de muchas fuentes.
- **Ojo**: las instancias públicas tienen límite de uso y a veces se caen. Una instancia propia se siente mejor.

### 4. Jina Reader Search

- **Tipo**: búsqueda con IA y extracción del texto de la página.
- **Alta**: no hace falta para el plan gratis. Si quieres un límite más alto, regístrate en https://jina.ai y pide una clave.
- **Dirección por defecto**: `https://s.jina.ai/`. También existe el espejo `https://s.jinaai.cn/`. La app prueba los dos.
- **Cómo usarlo**: elige `Jina`. Sin clave, deja el campo vacío. Con clave, complétala en «API Key».
- **Qué hace bien**: se queda con el texto de la página, no solo con el enlace.
- **Ojo**: es el único servicio de esta lista que, sin clave, igual está respaldado de forma oficial. El plan gratis tiene límite de uso.

---

## 2. Con clave

Pide la clave en el sitio del proveedor.

### 5. Tavily

- **Tipo**: API de búsqueda hecha para modelos.
- **Sitio**: https://tavily.com
- **Cómo pedirla**:
  1. Entra a tavily.com y crea la cuenta con el correo o con Google.
  2. Abre Dashboard → API Keys y copia la clave. Empieza por `tvly-xxxx`.
  3. Pégala en Muse, en «API Key».
- **Dirección por defecto**: `https://api.tavily.com`
- **Cuota gratis**: al registrarte hay una cuota mensual (cerca de 1000 llamadas al mes; confirma el número en el sitio).
- **Qué hace bien**: devuelve un resumen ya estructurado. Es la opción más simple cuando el modelo tiene que leer y resumir.

### 6. Brave Search

- **Tipo**: API de un índice propio.
- **Sitio**: https://brave.com/search/api/
- **Cómo pedirla**:
  1. Entra al sitio y elige Get Started o Sign up.
  2. Elige el plan **Free**. Pide verificar una tarjeta, pero el plan gratis no cobra.
  3. Copia la clave del panel. Empieza por `BSA...`.
- **Dirección por defecto**: `https://api.search.brave.com/res/v1`
- **Cuota gratis**: cerca de 2000 llamadas al mes y 1 por segundo. Confírmalo en el sitio.
- **Qué hace bien**: el índice es propio, no depende de Google ni de Bing. Los resultados en inglés son sólidos.

### 7. Serper

- **Tipo**: resultados de Google a través de un tercero.
- **Sitio**: https://serper.dev
- **Cómo pedirla**:
  1. Crea la cuenta con el correo o con Google.
  2. El Dashboard muestra la API Key.
  3. Las cuentas nuevas suelen recibir 2500 créditos gratis.
- **Dirección por defecto**: `https://google.serper.dev`
- **Qué hace bien**: es la forma más barata de obtener resultados de Google, y responde rápido.
- **Ojo**: cuando se acaban los créditos gratis, hay que recargar.

### 8. Bocha

- **Tipo**: API china de búsqueda con IA.
- **Sitio**: https://bochaai.com
- **Cómo pedirla**:
  1. Crea la cuenta y completa la verificación de identidad, si el servicio la pide.
  2. En la consola, crea una API Key.
- **Dirección por defecto**: `https://api.bochaai.com/v1`
- **Qué hace bien**: cubre bien el contenido en chino, tanto noticias como enciclopedias.
- **Ojo**: está pensado sobre todo para usuarios de China. Desde ahí responde rápido.

### 9. Metaso

- **Tipo**: API china de búsqueda con IA.
- **Sitio**: https://metaso.cn
- **Cómo pedirla**:
  1. Entra a metaso.cn, regístrate e inicia sesión.
  2. Pide la clave en la plataforma abierta o en la página de API.
- **Dirección por defecto**: `https://metaso.cn/api/v1`
- **Qué hace bien**: entiende bien el chino y los resúmenes salen cuidados.

### 10. Zhipu

- **Tipo**: búsqueda web de Zhipu AI.
- **Sitio**: https://open.bigmodel.cn
- **Cómo pedirla**:
  1. Regístrate en la plataforma abierta de Zhipu.
  2. Crea la clave en «API Keys».
  3. Es la misma API Key que usas para el modelo GLM, pero tiene que tener permiso de búsqueda web.
- **Dirección por defecto**: `https://open.bigmodel.cn/api/paas/v4`
- **Qué hace bien**: si ya usas un modelo de Zhipu, una sola clave alcanza para las dos cosas.

### 11. Exa

- **Tipo**: API de búsqueda semántica.
- **Sitio**: https://exa.ai
- **Cómo pedirla**:
  1. Regístrate en exa.ai.
  2. En Dashboard → API Keys, copia la clave. Empieza por `exa-...`.
- **Dirección por defecto**: `https://api.exa.ai`
- **Cuota gratis**: al registrarte hay una cuota. Confírmala en el sitio.
- **Qué hace bien**: busca por significado, no solo por palabras. Sirve para investigar un tema.

### 12. Firecrawl

- **Tipo**: rastreo y extracción estructurada.
- **Sitio**: https://firecrawl.dev
- **Cómo pedirla**:
  1. Regístrate en firecrawl.dev.
  2. En el Dashboard, copia la clave. Empieza por `fc-...`.
- **Dirección por defecto**: `https://api.firecrawl.dev/v1`
- **Cuota gratis**: al registrarte hay una cuota. Confírmala en el sitio.
- **Qué hace bien**: convierte la página en texto limpio. Sirve cuando pides «tráeme el contenido de esta página».

### 13. Perplexity

- **Tipo**: API de preguntas y respuestas.
- **Sitio**: https://www.perplexity.ai. La documentación para desarrolladores está en docs.perplexity.ai.
- **Cómo pedirla**:
  1. Crea una cuenta de Perplexity.
  2. Entra a la plataforma de API. Hay que activarla aparte y asociar un medio de pago.
  3. Crea la clave. Empieza por `pplx-...`.
- **Dirección por defecto**: `https://api.perplexity.ai`
- **Qué hace bien**: responde directo, con fuentes, en lugar de devolver una lista de enlaces.
- **Ojo**: no tiene plan gratis. Se cobra por llamada.

---

## 3. Cuál elegir

| Lo que necesitas | Opción |
|---|---|
| No quiero dar de alta nada | **Bing HTTP** o **Jina** |
| Casi todo el contenido está en chino | **Baidu** (sin clave) o **Bocha** (con clave, mejor calidad) |
| Que el modelo reciba un resumen | **Tavily** |
| Resultados de Google | **Serper** |
| Buscar por significado, o investigar | **Exa** |
| Quedarme con el texto de una página | **Firecrawl** o **Jina** |
| Ya tengo una clave de Zhipu | **Zhipu** |
| Quiero una respuesta, no una lista de enlaces | **Perplexity** |

Conviene tener a la vez uno sin clave, para que siempre haya una salida, y uno de pago, por la calidad. En modo automático, Muse prueba el siguiente si uno falla.

---

## 4. Preguntas frecuentes

**Pegué la clave y sigue sin buscar.**

Revisa en este orden:

1. Que la clave esté completa, sin espacios de más.
2. Que esa clave tenga activado el servicio. En Zhipu, el permiso de búsqueda web es aparte.
3. Que no hayas cambiado «Dirección de la interfaz» sin querer. Vacío significa la dirección por defecto.
4. Que la cuenta todavía tenga saldo o cuota.

**¿Puedo tener varios a la vez?**

Sí. Cada proveedor guarda su clave por separado. Al cambiar de proveedor, Muse usa la que le corresponde.

**¿Cuándo cambio el endpoint?**

En tres casos: tienes un SearXNG propio, usas un proxy o una pasarela, o el proveedor cambió de dominio. Si no estás seguro, déjalo vacío.

**¿Dónde quedan las claves? ¿Es seguro?**

Quedan en los datos locales de la app y entran en la copia de seguridad. No compartas una copia que tenga claves adentro.

---

## 5. Tabla rápida

| Proveedor | ¿Clave? | Dirección por defecto | Para qué sirve |
|---|---|---|---|
| Bing HTTP | No | Rastreo de la página | Sin configuración. Buenos resultados en chino |
| Baidu | No | Rastreo de la página | Cobertura amplia en chino |
| SearXNG | No | `https://searx.be` | Metabúsqueda y privacidad |
| Jina | No (opcional) | `https://s.jina.ai/` | Texto de la página |
| Tavily | Sí | `https://api.tavily.com` | Resumen para el modelo |
| Brave | Sí | `https://api.search.brave.com/res/v1` | Índice propio |
| Serper | Sí | `https://google.serper.dev` | Resultados de Google |
| Bocha | Sí | `https://api.bochaai.com/v1` | Búsqueda en chino |
| Metaso | Sí | `https://metaso.cn/api/v1` | Semántica en chino |
| Zhipu | Sí | `https://open.bigmodel.cn/api/paas/v4` | La misma clave que el modelo GLM |
| Exa | Sí | `https://api.exa.ai` | Búsqueda por significado |
| Firecrawl | Sí | `https://api.firecrawl.dev/v1` | Rastreo y extracción |
| Perplexity | Sí | `https://api.perplexity.ai` | Respuesta con fuentes |
