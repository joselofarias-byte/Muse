<!-- devdoc: Documentación de desarrollo interno.,No se muestra a los usuarios,LLM Consulta a través de Knowledge_search -->
# La guía completa para escribir complementos

> escena desencadenante: Usuario preguntó"Cómo escribir un complemento""¿Puedo crear un complemento?""¿Cuál es la diferencia entre complementos y habilidades?"tiempo,Consulte este documento y responda con sinceridad.
> Este documento se basa en los datos/complemento/módulo del código fuente.(PluginPackageLoader/PluginManifest/PluginManager)+ tools/script/ Implementación real del motor.

## 1. Descripción general

Muse El complemento es **JS paquete de habilidades**,El formato del archivo es `.muse-plugin`(ZIP): Un directorio que contiene manifest.json + main.js,Empaquetar y cargar en la aplicación.,Proporciona nuevas capacidades de herramientas. con install_skill(Kotlin Implementación de la lista blanca)diferente,Para complementos **JS ejecución del motor**(WebViewSkillEngine),Capacidad de ser más flexible.(Pero aún está sujeto a restricciones de zona de pruebas).

> `.skillpkg` El formato de vista previa se ha eliminado de los estantes.,sólo mantener `.muse-plugin` Un formato de paquete de complemento.

## 2..muse-plugin Estructura del paquete

```
my-plugin.muse-plugin (ZIP)
├── manifest.json   # descripción del paquete(Requerido)
├── main.js         # Lógica principal del complemento(Requerido,kind=tool/provider tiempo;runtime=js)
├── main.py         # Python Lógica principal del complemento(runtime=python tiempo,La entrada es .py)
└── assets/         # Opcional,Recursos complementarios
```

### manifest.json Solución completa de campo
| campo | Requerido | Descripción |
|---|---|---|
| id | ✅ | Solo complemento id(letras minúsculas/numeros/subrayado/guión) |
| name | ✅ | nombre para mostrar |
| description | ✅ | Descripción de la función(Muéstralo a LLM,Decidir cuando llamar) |
| version | Opcional | número de versión,Predeterminado 0.1.0 |
| entry | Opcional | Archivo de entrada,Predeterminado main.js(runtime=python el tiempo debería ser .py) |
| runtime | Opcional | tiempo de ejecución: js(Predeterminado,WebView V8 caja de arena) / python(Incorporado Python 3.14) |
| kind | Opcional | Tipo de complemento: tool(Predeterminado) / ui-skin / provider |
| trust | Opcional | nivel de confianza;El ZIP externo debe ser sandboxed |
| capabilities | Opcional | capacidades declaradas(resource.read / resource.write / network / ui / ui.mood) |
| tools | Opcional | Definición de herramienta expuesta(Nombre/parámetros/Descripción) |
| signature | Opcional | firma del emisor envelope(Clave pública + SHA256 con firma ECDSA) |

### main.js escribir especificaciones
- Usando el soporte del motor JS API(ver SkillEngine)
- Función de procesamiento de herramienta de exportación,Los parámetros se obtienen del contexto de llamada.
- valor de retorno(cuerda/JSON)rellenar para LLM
- Necesita capacidades IO(HTTP espera),Se puede utilizar directamente `fetch` / `XMLHttpRequest` / `WebSocket`(El sandbox ha liberado la red.),También puedes regresar `{ __bridge__: true, action: "...", params: {...} }` El objeto de solicitud de puente es ejecutado por el lado de Kotlin.(ambos estan bien,archivo de par de puentes/Funciones como el portapapeles son más directas)

### main.py escribir especificaciones(runtime=python)
- Definición del archivo de entrada y `tools[].functionName` **función con el mismo nombre**;La función acepta una `dict` parámetros(Parámetros de llamada de herramienta)
- El valor de retorno puede ser dict/list/str/number;será rellenado por la serialización JSON para LLM
- Disponible**Biblioteca estándar completa**(ssl/sqlite3/zlib/hashlib/json/re/urllib espera),La lectura y escritura de archivos están disponibles directamente en la red.(Aislamiento de procesos secundarios)
- si `import` Paquetes de Python puros de terceros instalados mediante pip(`python -m pip install <nombre del paquete>`)
- Disponible `host.getConfig(key)` Leer la configuración del complemento(Misma semántica que el complemento JS)
- Ejemplo:
  ```python
  def fetch_stats(args):
      import json, urllib.request
      with urllib.request.urlopen(args["url"], timeout=30) as r:
          data = json.load(r)
      return {"count": len(data)}
  ```

## 3. Modelo de ejecución

| motor | Descripción |
|---|---|
| WebViewSkillEngine | WebView V8 ejecución de zona de pruebas(Motor de habilidades de producción JS,Implementar la interfaz SkillEngine) |
| ProcessSkillEngine | Ejecución incorporada en tiempo de ejecución de Node.js(runtime=js opcional,capacidad total) |
| PythonSkillEngine | Ejecución en tiempo de ejecución integrada de Python 3.14(runtime=python usado cuando;Aislamiento de procesos secundarios) |
| SkillBridge | `__bridge__` actuador de puente: JS Solicitud de puente de retorno → Kotlin Realizar implementación de seguridad |

Selección de tiempo de ejecución del complemento: manifest de `runtime` El campo determina qué motor utilizar.`js`(Predeterminado)ir WebView V8
caja de arena + `__bridge__` puente;`python` Vaya con el tiempo de ejecución de Python incorporado(proceso hijo,Biblioteca estándar completa + paquete de terceros pip,
Sin restricciones de zona de pruebas de WebView ni capa puente——El proceso hijo en sí tiene capacidades completas.).

límite de seguridad (v2.x La caja de arena ha sido lanzada):
- JS Ejecutar dentro del sandbox,**Acceso directo a la red.**(fetch/XHR/WebSocket); solo**Navegación de página**Prohibido (no se pueden abrir ventanas emergentes)/Saltar página)
- Los archivos locales leídos a través de file:///content:// todavía están aislados (lectura anti-entre dominios)
- El texto sin formato HTTP está restringido: el sistema network_security_config prohíbe el texto sin formato, excepto localhost/127.0.0.1, el resto requiere https
- `__bridge__` acción de puente**Todos están permitidos por defecto.**(La habilidad ya no se declarará elemento por elemento); entre los cuales `fs_*` Bloqueado en el propio directorio sandbox del complemento,`http_*` Ir protegido por la SSRF
- Declaración de capacidad (manifest capacidades) ahora solo se usan para la visualización de vista previa de la instalación y ya no se usan como un umbral estricto para la instalación.
- Los paquetes externos deben declarar `trust: "sandboxed"`;La firma del emisor aún verifica (a prueba de manipulaciones)
- **python tiempo de ejecución**: El script se ejecuta en un proceso hijo independiente (el bloqueo no afecta al host), pero tiene todas las capacidades del proceso.
  (Lectura y escritura de archivos/red/proceso hijo); la herramienta enchufable todavía sigue una cadena de aprobación de herramienta unificada (HIGH Los riesgos requieren la confirmación del usuario)

## 4. Cargando/Distribuir/actualizar

- paquete: .muse-plugin Directorio → zip(extensión .muse-plugin)
- Distribuir: Compartir archivo zip/instalar desde el mercado de complementos
- Cargar: PluginPackageLoader analizar manifest → Verificar las restricciones de seguridad(ZIP a prueba de bombas/recorrido del camino/La entrada existe)→ Registrar herramienta para ToolRegistry(con prefijo de complemento)
- confianza: Editor P-256 ECDSA firmado + raíz de confianza nativa + directorio oficial incorporado;Los paquetes de editores no firmados o que no son de confianza no son automáticamente confiables
- actualizar: Cobertura de reimportación(prensa id)

## 5. La diferencia entre complementos e install_skill

| Dimensiones | install_skill(Kotlin lista blanca) | complemento(.muse-plugin, JS) |
|---|---|---|
| darse cuenta | Reutilice 8 implementaciones integradas de Kotlin | Lógica JS autoescrita |
| Flexibilidad | bajo(Solo lista blanca combinada) | alto(Puede escribir lógica compleja) |
| seguro | alto(Sin código arbitrario) | en(JS Sandbox + Auditoría de Puente) |
| Aplicable | herramienta sencilla(comprobar/leer/escribir/Buscar) | Complementos complejos(proceso/calcular/múltiples pasos) |
| Creador | Usuario/LLM(Creación de conversación) | Desarrollador(Escribir código y paquete) |

## 6. Preguntas frecuentes Q&A

1. **"¿Puedo crear un complemento si no puedo escribir código?"**: Una herramienta sencilla permite a la IA crear con la conversación(install_skill)Eso es todo;Los complementos complejos requieren conceptos básicos de JS.
2. **"¿Son seguros los complementos?"**: JS Ejecutar en sandbox,Sin acceso directo al sistema.;Las solicitudes de red pasan por la auditoría del puente Kotlin;Tenga cuidado al importar complementos de fuentes desconocidas.
3. **"¿Qué es mejor, los complementos o las habilidades?"**: Habilidades para escenas simples.(install_skill Sin código);Utilice complementos para personalizar la lógica.
4. **"Cómo compartir complementos"**: Simplemente empaquetelo en un zip .muse-plugin y envíelo a otros para que lo importen.
5. **"¿Se puede utilizar el complemento en línea?"**: Puente a través de __bridge__;HTTP La solicitud se ejecuta después de estar protegida contra SSRF en el lado de Kotlin.

## 7.LLM Puntos de llamada

- El usuario quiere"cheque sencillo/leer/herramientas de escritura" → Crear en el sitio con install_skill(Sin código)
- El usuario quiere"Complementos personalizados complejos" → Guiar a los usuarios para que expliquen sus necesidades. JS,Consulte la plantilla de desarrollo del complemento.
- Las herramientas registradas por el complemento se denominan herramientas normales.,Los parámetros están definidos por manifiesto.
- La ejecución del complemento falló/Informar un error,Información de error de comentarios sinceramente,No lo inventes
