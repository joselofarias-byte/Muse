# API de búsqueda integrada Obtener tutorial

Muse Incorporado **13 casa**Buscar/Los servicios de rastreo se dividen en dos categorías:**sin llave**(disponible listo para usar) con**Se requiere clave**(Rellénelo después de aplicarlo usted mismo). Este artículo explica uno a uno dónde postularse, cómo llenarlo y las características y cupo libre de cada empresa.

> Entrada: Configuración → Buscar(Web búsqueda). Después de seleccionar el proveedor, complete el campo "API Key"; Algunos proveedores también pueden cambiar la "dirección de interfaz" (endpoint), déjelo en blanco para usar la dirección predeterminada.
> Quiere guardar problemas:**Bing HTTP / Baidu / SearXNG / Jina** Puedes usarlo sin abrir ninguna cuenta.

---

## 1. Sin clave (no es necesario solicitarla, solo elija y use)

### 1. Bing HTTP
- **Tipo**: raspado web
- **Aplicar**: No requerido
- **como usar**:Seleccione la ruta predeterminada en la configuración como `Bing HTTP` Eso es todo
- **Características**: Los resultados en chino son buenos, la estabilidad depende de la estructura de la página de Bing, cambios ocasionales
- **Nota**: Este es un rastreo de páginas en lugar de una API oficial. Las solicitudes excesivas pueden ser limitadas.

### 2. Baidu
- **Tipo**: raspado web
- **Aplicar**: No requerido
- **como usar**: seleccionado `Baidu` Eso es todo
- **Características**: El chino tiene la cobertura más completa y es adecuado para contenido nacional.
- **Nota**: Ambos son rastreadores de páginas y el análisis de resultados depende de la estructura de la página de Baidu.

### 3. SearXNG
- **Tipo**:Metasearch (agregando múltiples motores de búsqueda)
- **Aplicar**: No es necesario (use una instancia pública); también puedes construirlo tú mismo
- **Dirección predeterminada**:`https://searx.be`
- **como usar**: seleccionado `SearXNG`;Cuando tenga una instancia creada usted mismo, complete su propio nombre de dominio en la "Dirección de interfaz"
- **Características**: Respetuoso con la privacidad, amplia gama de fuentes de resultados
- **Nota**: Las instancias públicas tienen límites de tarifas y ocasionalmente no están disponibles;**Las instancias autoconstruidas brindan la mejor experiencia**

### 4. Jina Reader Search
- **Tipo**:AI Búsqueda + rastreo de texto
- **Aplicar**: No es necesario (nivel gratuito); Si desea un límite más alto, puede registrarse en https://jina.ai para obtenerlo. Key
- **Dirección predeterminada**:`https://s.jina.ai/`(También hay espejos domésticos `https://s.jinaai.cn/`,App Probaré ambos)
- **como usar**: seleccionado `Jina`; Si no hay ninguna clave, déjela en blanco; si hay una clave, complete "API Key"
- **Características**: Capture directamente el texto de la página web, adecuado para escenarios que requieren contenido en lugar de enlaces.
- **Nota**:**este es el único"No Key también es compatible oficialmente"servicios empresariales**, el nivel gratuito tiene límites de tarifas

---

## 2. Se requiere una clave (vaya al sitio web oficial para presentar la solicitud)

### 5. Tavily
- **Tipo**:AI API de búsqueda (diseñada específicamente para LLM)
- **Sitio web oficial**:https://tavily.com
- **Pasos de la solicitud**:
  1. Abra tavily.com y utilice su dirección de correo electrónico/Google Registro de cuenta
  2. entrar Dashboard → API Keys, copia el formulario `tvly-xxxx` de Key
  3. Pegar en Muse "API Key"
- **Dirección predeterminada**:`https://api.tavily.com`
- **Cuota libre**: Regístrese para obtener una cuota de llamadas mensual gratuita (aproximadamente 1000 veces/mes, sujeto al sitio web oficial)
- **Características**: Devuelve un resumen estructurado,AI La escena más libre de preocupaciones.

### 6. Brave Search
- **Tipo**:Motor de búsqueda indexado independientemente API
- **Sitio web oficial**:https://brave.com/search/api/
- **Pasos de la solicitud**:
  1. Abrir el sitio web oficial → Get Started / Sign up
  2. elegir **Free** Plan (requiere verificación de tarjeta, sin deducciones para el nivel gratuito)
  3. Copie la clave API en el panel (en forma de `BSA...`)
- **Dirección predeterminada**:`https://api.search.brave.com/res/v1`
- **Cuota libre**: Nivel gratuito unas 2000 veces/mes,1 veces/segundos (sujeto al sitio web oficial)
- **Características**: Índice de creación propia (no depende de Google/Bing), resultados en inglés de alta calidad

### 7. Serper
- **Tipo**:Google API de resultados de búsqueda (terceros)
- **Sitio web oficial**:https://serper.dev
- **Pasos de la solicitud**:
  1. Registrar una cuenta (correo electrónico/Google)
  2. Dashboard mostrar directamente API Key
  3. Las cuentas nuevas suelen recibir 2.500 créditos gratis
- **Dirección predeterminada**:`https://google.serper.dev`
- **Características**: La forma más económica de obtener resultados de Google, rápidamente
- **Nota**: Se requiere recarga después de que se agote la cuota gratuita

### 8. Bocha(Bocha)
- **Tipo**: API de búsqueda de IA china (nacional)
- **Sitio web oficial**:https://bochaai.com
- **Pasos de la solicitud**:
  1. Registre una cuenta y complete su nombre real (generalmente requerido para servicios domésticos)
  2. Creación de consola API Key
- **Dirección predeterminada**:`https://api.bochaai.com/v1`
- **Características**:**Buena cobertura del contenido chino.**, adecuado para información nacional./consulta de enciclopedia
- **Nota**: Principalmente para uso doméstico, acceso rápido

### 9. Metaso(Búsqueda de la torre secreta)
- **Tipo**: Búsqueda de IA china API
- **Sitio web oficial**:https://metaso.cn
- **Pasos de la solicitud**:
  1. Abra metaso.cn, regístrese e inicie sesión
  2. Ingresa a la plataforma abierta/API Aplicación de página Key
- **Dirección predeterminada**:`https://metaso.cn/api/v1`
- **Características**: Fuerte comprensión semántica china y alta calidad de resumen.

### 10. Zhipu(Búsqueda web Zhipu)
- **Tipo**: Capacidades de búsqueda en Internet de Zhipu AI
- **Sitio web oficial**:https://open.bigmodel.cn
- **Pasos de la solicitud**:
  1. Regístrese en la plataforma abierta Zhipu
  2. En "API KeysCreado en Key
  3. **Nota**: Se utiliza la clave API de Zhipu (la misma que se usa para llamar al modelo GLM), pero esta clave requiere el permiso de búsqueda web.
- **Dirección predeterminada**:`https://open.bigmodel.cn/api/paas/v4`
- **Características**: Si ya está utilizando el modelo de espectro inteligente, una clave es común

### 11. Exa
- **Tipo**: Búsqueda semántica de IA API
- **Sitio web oficial**:https://exa.ai
- **Pasos de la solicitud**:
  1. Registrarse exa.ai
  2. Dashboard → API Keys Copiar (forma como `exa-...`)
- **Dirección predeterminada**:`https://api.exa.ai`
- **Cuota libre**: Regístrese para obtener cuota gratuita (sujeto al sitio web oficial)
- **Características**: Búsqueda semántica (búsqueda por significado, no por palabras clave), adecuada para consultas de investigación

### 12. Firecrawl
- **Tipo**: Rastreo web + extracción estructurada
- **Sitio web oficial**:https://firecrawl.dev
- **Pasos de la solicitud**:
  1. Registrarse firecrawl.dev
  2. Dashboard Copie la clave API (forma como `fc-...`)
- **Dirección predeterminada**:`https://api.firecrawl.dev/v1`
- **Cuota libre**: Regístrese para obtener cuota gratuita (sujeto al sitio web oficial)
- **Características**:**Fuerte en agarrar**——Limpie todo el contenido de la página en texto limpio, adecuado para"Dame el contenido de esta pagina"

### 13. Perplexity
- **Tipo**:AI Búsqueda de preguntas y respuestas API
- **Sitio web oficial**:https://www.perplexity.ai(La entrada para desarrolladores está en docs.perplexity.ai)
- **Pasos de la solicitud**:
  1. Regístrese para obtener una cuenta de Perplejidad
  2. Ingrese a la plataforma API (debe abrirse por separado y vincularse al método de pago)
  3. Cree una clave API (forma como `pplx-...`)
- **Dirección predeterminada**:`https://api.perplexity.ai`
- **Características**: Devuelve directamente la respuesta a la que se hace referencia, no una lista vinculada
- **Nota**:**Sin nivel gratuito**, necesita recargarse; facturado por llamada

---

## 3. Cómo elegir (según escena)

| tus necesidades | Recomendado |
|---|---|
| No quiero molestar en absoluto | **Bing HTTP** o **Jina**(sin llave) |
| Contenido principalmente chino | **Baidu**(gratis),**Bocha**(requiere clave, mejor calidad) |
| AI Guarda la escena y resúmela. | **Tavily** |
| Obtener resultados de Google | **Serper** |
| Recuperación semántica/Investigación | **Exa** |
| Para capturar el contenido de la página web. | **Firecrawl** o **Jina** |
| Modelo de espectro de sabiduría existente Key | **Zhipu**(Una clave es universal) |
| Quiere resultados de preguntas y respuestas | **Perplexity** |

**Sugerencias**: Equipado con 1 llave sin llave al mismo tiempo (garantizada)+ 1 pagado (calidad),Muse Elegirá lo mejor según la estrategia.

---

## 4. Preguntas frecuentes

**Q: ¿Completó la clave pero aún no puede encontrarla?**
A: Verificar en orden:① Key Ya sea para copiar completamente (sin espacios adicionales);② Si se ha activado el servicio correspondiente para esta Clave (Zhipu Preste especial atención a);③ Si la "Dirección de interfaz" se ha cambiado por error (déjelo en blanco como valor predeterminado);④ ¿La cuenta tiene saldo?/Cuota.

**Q: ¿Puedo tener varios clientes al mismo tiempo?**
A:capaz. Las claves de cada empresa en el área de configuración se almacenan por separado, y sus respectivas claves se traen automáticamente al cambiar de proveedor.

**Q:endpoint ¿Cuándo se debe cambiar?**
A: Tres situaciones: utilice SearXNG de construcción propia; usar transferencia/Dirección del agente; el funcionario cambió el nombre del dominio. Si no está seguro, déjelo en blanco.

**Q: ¿Dónde existen estas claves? ¿Es seguro?**
A: Almacene los datos de la aplicación local y realice la copia de seguridad. No comparta archivos de respaldo que contengan claves.

---

## 5. Hoja de referencia de una frase

| proveedor | necesitar Key | Punto final predeterminado | Principales ventajas |
|---|---|---|---|
| Bing HTTP | No | raspado de página | No requiere configuración, buen idioma chino. |
| Baidu | No | raspado de página | Cobertura completa en chino |
| SearXNG | No | `https://searx.be` | metabuscador, privacidad |
| Jina | No (opcional) | `https://s.jina.ai/` | Rastreo de texto |
| Tavily | si | `https://api.tavily.com` | AI Resumen de búsqueda |
| Brave | si | `https://api.search.brave.com/res/v1` | Índice autoconstruido |
| Serper | si | `https://google.serper.dev` | Google resultado |
| Bocha | si | `https://api.bochaai.com/v1` | Búsqueda de IA china |
| Metaso | si | `https://metaso.cn/api/v1` | semántica china |
| Zhipu | si | `https://open.bigmodel.cn/api/paas/v4` | Espectro de sabiduría ecológico universal. |
| Exa | si | `https://api.exa.ai` | Recuperación semántica |
| Firecrawl | si | `https://api.firecrawl.dev/v1` | Rastreo y extracción web |
| Perplexity | si | `https://api.perplexity.ai` | Búsqueda de preguntas y respuestas |
