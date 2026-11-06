# Muse Web Workspace - Informe de revisión de enlaces funcionales de un extremo a otro

> Fecha de revisión: 2026-09-13
> Alcance de la revisión: Completa 17 módulos/submódulo
> Ruta del proyecto: E:\1Project\Muse\1muse\web

---

## resumen ejecutivo

| categoría | Estado |
|------|------|
| arquitectura general | ⚠️ Parcialmente desaparecido |
| @muse/contracts | ✅ completo |
| @muse/core | ⚠️ Parcialmente desaparecido |
| @muse/provider | ⚠️ Parcialmente desaparecido |
| @muse/database | ⚠️ Parcialmente desaparecido |
| @muse/memory | ⚠️ Parcialmente desaparecido |
| @muse/rag | ⚠️ Parcialmente desaparecido |
| @muse/files | ✅ completo |
| @muse/tools | ⚠️ Parcialmente desaparecido |
| @muse/mcp | ✅ completo |
| @muse/agents | ✅ completo |
| @muse/jobs | ⚠️ Parcialmente desaparecido |
| @muse/storage | ⚠️ Parcialmente desaparecido |
| @muse/client | ⚠️ Parcialmente desaparecido |
| @muse/api | ❌ problema grave |
| @muse/admin | ⚠️ Parcialmente desaparecido |
| @muse/worker | ❌ problema grave |

---

## 1. arquitectura general

### gráfico de dependencia

```
packages/
  contracts → (Sin dependencias)
  core → contracts
  provider → contracts, core
  database → contracts, core, memory, rag, files, jobs, storage
  memory → (Sin dependencias)
  rag → (Sin dependencias)
  files → (Sin dependencias)
  tools → contracts
  mcp → contracts, tools
  agents → (Sin dependencias)
  jobs → (Sin dependencias)
  storage → contracts

apps/
  api → todos packages
  client → contracts, core, provider
  admin → contracts
  worker → database, jobs
```

### tsconfig.base.json mapeo de ruta

- `@muse/*` Mapeo de comodines configurado, apuntando a `src/index.ts`
- Orden de construcción: contracts → core → provider → database → memory/rag/files → tools/mcp → agents → jobs → storage → client/admin/api/worker

### Problemas encontrados

**[bajo] Build Cadena de referencia del producto rota**

`database` El paquete declara dependencias `@muse/memory`, `@muse/rag`, `@muse/files`, pero los tipos exportados por estos paquetes en realidad no se utilizan a nivel de tipo.`PostgresMemoryRepository`,`PostgresRagRepository`,`PostgresFileRepository` La importación de tipos directamente desde definiciones de interfaz puede generar dependencias indirectas innecesarias durante la construcción.

**Archivo**: `packages/database/package.json`
**Sugerencias**: Declararlo explícitamente como peerDependencies o eliminar la declaración directamente

---

## 2. @muse/contracts(capa de contrato)

### Estado: ✅ completo

Todos los tipos de claves están completamente definidos:
- `ChatMessage`: contiene reasoning, toolCalls, imageUrls
- `ChatStreamEvent`: 11 Cobertura completa de tipos de eventos.
- `ProviderConfig`: 7 tipo de proveedor
- `ChatRequest`: apoyo byok/hosted transport
- `ToolDefinition`: Contiene riskLevel
- `PushSubscriptionRecord`: Web Push Registro de suscripción
- `WebCapabilities`: Contiene detectWebCapabilities()

---

## 3. @muse/core(capa central)

### Estado: ⚠️ Parcialmente desaparecido

**[alto] Riesgo de dependencia circular**

`@muse/core` Exportar `AssistantRecord`, mientras `@muse/database` de `PostgresAssistantCatalog` de `@muse/core` Importa este tipo. al mismo tiempo `@muse/database` ser `@muse/core` referencia de prueba o verificación de tipo, creando un ciclo potencial.

**Archivo**: `packages/core/src/assistant.ts`, `packages/database/src/assistant.ts`
**Sugerencias**: voluntad `AssistantRecord` Mover a `@muse/contracts` Como tipo compartido entre paquetes

**[en] InMemoryAssistantCatalog No implementado ProviderSecretStore**

`ChatRequest` de `secretRef` El campo no se consume en ninguna parte.

**Archivo**: `packages/core/src/chat.ts` L50-52
**Sugerencias**: suplemento `ProviderSecretStore` implementación o eliminación de campos no utilizados en ChatRequest

---

## 4. @muse/provider(Provider capa)

### Estado: ⚠️ Parcialmente desaparecido

**[alto] Falta el analizador SSE nativo de búsqueda profunda**

`parseSseData()` La función maneja antropopic,openai_responses,gemini tres protocolos específicos, pero `deepseek` Se permite pasar tipos a la rama predeterminada de OpenAI.DeepSeek El formato de respuesta de transmisión puede diferir de OpenAI.

**Archivo**: `packages/provider/src/index.ts` L140-143
**Sugerencias**: Agregue lógica de análisis dedicada a DeepSeek o documente claramente su compatibilidad con el protocolo OpenAI

**[en] SSE Hay casos extremos en la lógica de segmentación de cuadros**

```typescript
const frames = buffer.split(/\r?\n\r?\n/);
```

Cuando los datos SSE contienen líneas en blanco que no son delimitadores, pueden provocar una desalineación del marco.

**Archivo**: `packages/provider/src/index.ts` L225
**Sugerencias**: Utilice bibliotecas de análisis SSE maduras como `eventsource-parser`

---

## 5. @muse/database(capa de datos)

### Estado: ⚠️ Parcialmente desaparecido

**[serio] push.ts SQL Anular el valor de autenticación de inserción a una constante fija**

```sql
on conflict (user_id, endpoint) do update set
  p256dh = excluded.p256dh,
  auth = <Marcadores de posición codificados>,  -- BUG: debería ser excluded.auth
  user_agent = excluded.user_agent,
  ...
```

Esto daba como resultado que cada vez que se enviaba una actualización de suscripción, la clave de autenticación VAPID del usuario se sobrescribía a un valor fijo y todas las notificaciones automáticas fallaban.

**Archivo**: `packages/database/src/push.ts` L12
**riesgo**: P0 - La función de empuje está completamente desactivada.
**Sugerencias**: Fijar inmediatamente a `auth = excluded.auth`

**[en] FileDescriptor sizeBytes Tipos inconsistentes**

Uso de la base de datos `bigint`, pero el tipo TypeScript se define como `number`.PostgreSQL bigint La pérdida de precisión se produce cuando se excede el rango de JavaScript SafeInteger.

**Archivo**: `packages/database/src/domains.ts` L35
**Sugerencias**: uso `BigInt` escribir o manejar la conversión en la capa ORM

**[bajo] PostgresJobQueue idempotencyKey detección de conflictos no implementada**

`PostgresJobQueue.enqueue()` uso `on conflict (idempotency_key) do update`, pero se devuelve la primera fila insertada y no se garantiza que la semántica idempotente sea correcta.

**Archivo**: `packages/database/src/jobs.ts` L14-18
**Sugerencias**: Distinguir entre las rutas INSERTAR y ACTUALIZAR

---

## 6. @muse/memory(capa de memoria)

### Estado: ⚠️ Parcialmente desaparecido

**[en] MemoryStore.search() La búsqueda entre espacios no es compatible**

cuando `spaceId` para `"default"` Cuando , la búsqueda se limita a un solo espacio. No proporcionado `listSpaces()` o `searchAcrossSpaces()` método.

**Archivo**: `packages/memory/src/index.ts` L35-45
**Sugerencias**: agregar `search(accountId, query, assistantId, limit?)` El método admite la búsqueda entre espacios.

**[bajo] decay() lógico Y search() Las condiciones del filtro son inconsistentes**

`decay()` Reducir la importancia a 0, pero `search()` Los criterios de filtrado incluyen `importance === 0` entrada (siempre que tenga una fecha de vencimiento), puede causar que aún se devuelvan datos deteriorados.

**Archivo**: `packages/memory/src/index.ts` L55-66, L42-44
**Sugerencias**: Ajustar la lógica de decadencia para excluir de los resultados de búsqueda hechos cuya importancia cae a 0

---

## 7. @muse/rag(RAG capa)

### Estado: ⚠️ Parcialmente desaparecido

**[alto] Sólo búsqueda de bolsa de palabras, no búsqueda de vectores**

`searchChunks()` Solo se utiliza una coincidencia de frecuencia de palabras simple, no se utilizan llamadas de modelo de incrustación. Notas "vector provider can replace scoring later", pero la implementación actual no puede admitir la búsqueda semántica.

**Archivo**: `packages/rag/src/index.ts` L55-72
**Sugerencias**: Al menos reserve una interfaz integrada o integre una biblioteca vectorial liviana

**[en] chunkText() Los cálculos de ventanas superpuestas tienen límites bug**

```typescript
current = current.slice(Math.max(0, current.length - overlapCharacters));
```

cuando `current` longitud menor que `overlapCharacters` cuando,`slice` El texto completo se conserva en lugar de truncarse como se esperaba.

**Archivo**: `packages/rag/src/index.ts` L28
**Sugerencias**: fijo a `current = current.slice(Math.max(0, current.length - overlapCharacters)).trim()`

---

## 8. @muse/files(Capa de archivo)

### Estado: ✅ completo

`FileDescriptor` interfaz,`validateUpload()`,`objectKey()`,`sanitizeDownloadName()` Lograr la plenitud. La verificación de seguridad cubre la inyección de nombres de archivos,MIME Restricciones de tipo y tamaño.

---

## 9. @muse/tools(Capa de herramientas)

### Estado: ⚠️ Parcialmente desaparecido

**[alto] ToolRegistry Falta el entorno de pruebas de ejecución de parámetros de herramienta**

`validateJsonSchema()` Solo se realiza la verificación de tipos, no hay límite en el tamaño o la complejidad de los valores de los parámetros.`high` herramientas de riesgo (por ej. `write_file`) puede contener cadenas arbitrariamente grandes.

**Archivo**: `packages/tools/src/index.ts` L23-40
**Sugerencias**: Agregue límite de tamaño de parámetro y filtrado de complejidad

**[en] builtinTools Sin control de acceso en write_file**

`write_file` marcado como `high` riesgo pero `ToolRegistry.execute()` La asociación de accountId con el archivo no está marcada.

**Archivo**: `packages/tools/src/index.ts` L75-77
**Sugerencias**: Verifique la propiedad del archivo antes de la ejecución o agregue middleware de permisos

---

## 10. @muse/mcp(MCP capa)

### Estado: ✅ completo

URL La autenticación, la interceptación de direcciones privadas y el espacio de nombres de herramientas se implementan por completo.

---

## 11. @muse/agents(Agent capa)

### Estado: ✅ completo

La lógica de conversión de la máquina de estados es correcta,terminal El Estado es irreversible.

---

## 12. @muse/jobs(nivel de trabajo)

### Estado: ⚠️ Parcialmente desaparecido

**[en] InMemoryJobQueue La lógica de reclamo no admite la concurrencia**

Varios trabajadores que presenten solicitudes al mismo tiempo pueden obtener el mismo trabajo. Requiere bloqueos distribuidos o control de simultaneidad a nivel de base de datos.

**Archivo**: `packages/jobs/src/index.ts` L30-34
**Sugerencias**: Redis La implementación ha utilizado scripts Lua para garantizar la atomicidad, pero la versión InMemory debe bloquearse.

**[bajo] JobWorker.runOnce() Tipo de excepción no controlada**

```typescript
catch (error) { await this.queue.fail(job.id, error instanceof Error ? error.message : "job failed"); }
```

Los objetos que no son de error se convierten en cadenas "job failed", perdiendo contexto.

**Archivo**: `packages/jobs/src/index.ts` L85-87
**Sugerencias**: uso `String(error)` o mantener el tipo original

---

## 13. @muse/storage(capa de almacenamiento)

### Estado: ⚠️ Parcialmente desaparecido

**[alto] S3ObjectStorage Falta limpieza automática TTL**

La URL prefirmada generada es válida durante 900 segundos, pero si la carga falla,object permanecerá.

**Archivo**: `packages/storage/src/index.ts` L25-28
**Sugerencias**: Agregue una tabla de seguimiento de cargas completadas y limpie los objetos huérfanos con regularidad

**[en] InMemoryObjectStorage En realidad no almacenado**

En modo de desarrollo, sólo se devuelve la URL falsa, sin verificar si el contenido está realmente escrito.

**Archivo**: `packages/storage/src/index.ts` L44-48
**Sugerencias**: Marcado explícitamente como simulado, la configuración de S3 es obligatoria para entornos de producción

---

## 14. @muse/client(React interfaz)

### Estado: ⚠️ Parcialmente desaparecido

**[en] API Key Transferir a través de encabezado personalizado**

```typescript
headers.set("x-muse-provider-key", input.apiKey);
```

Los encabezados personalizados deben declararse explícitamente en la verificación previa de CORS, pero `server.ts` Este encabezado ya está incluido en la respuesta de OPCIONES, que es básicamente segura. Sin embargo, se recomienda utilizar el estándar. `Authorization` Cabeza con esquema al portador.

**Archivo**: `apps/client/src/api.ts` L187
**Sugerencias**: Evaluar si se debe migrar al mecanismo de autenticación estándar

**[en] HostConnection La lógica de reconexión tiene el riesgo de pérdidas de memoria**

```typescript
socket.addEventListener("error", () => { ... }, { once: true });
```

Aunque usando `{ once: true }`, pero si `attachSocket` Llamado varias veces, el oyente del socket antiguo no se limpiará.

**Archivo**: `apps/client/src/api.ts` L267-279
**Sugerencias**: Eliminar el oyente antiguo antes de volver a conectarlo

**[en] A la suscripción push le falta la verificación de clave pública VAPID**

En la solicitud de suscripción push enviada por el front-end,auth Los campos utilizan marcadores de posición codificados.

**Archivo**: `apps/client/src/api.ts` L174
**Sugerencias**: Elimine la codificación frontal y obtenga la configuración VAPID del servidor.

**[bajo] dependencia del modo oscuro prefers-color-scheme**

CSS El modo oscuro se implementa mediante consultas de medios, pero no hay un botón de alternancia manual. Para aplicaciones PWA, se recomienda agregar opciones controlables por el usuario.

**Archivo**: `apps/client/src/styles.css` L135-156
**Sugerencias**: agregar `[data-theme="dark"]` botón de soporte o alternancia

**[bajo] Service Worker Registro sin manejo de errores**

```typescript
if ("serviceWorker" in navigator) window.addEventListener("load", () => navigator.serviceWorker.register("/sw.js"));
```

SW Los errores de registro se ignoran silenciosamente.

**Archivo**: `apps/client/src/main.tsx` L485
**Sugerencias**: Agregar manejo de capturas y mensajes de usuario

---

## 15. @muse/api(API Server)

### Estado: ❌ problema grave

**[serio] Se filtraron credenciales codificadas**

Las credenciales codificadas existen en/Clave (verificada con grep):

| ubicación | Número de línea | contenido |
|------|------|------|
| `apps/api/src/server.ts` | L71-72 | `bootstrapAdminPassword = <valor codificado>` |
| `apps/api/src/runtime.ts` | L123-124 | `secretAccessKey = <valor codificado>` |
| `packages/database/src/secrets.ts` | L36 | `hasSecret = <constante> !== null` (error lógico) |

**riesgo**: Cualquier persona con acceso al código fuente puede obtener credenciales de producción.
**Sugerencias**: Migre ahora a una variable de entorno o un servicio de administración de claves

**[serio] CORS Riesgo de asignación**

```typescript
"access-control-allow-origin": allowedOrigin,
"access-control-allow-credentials": "true",
```

cuando `allowedOrigin` para `*` , el navegador rechazará la solicitud que lleve las credenciales. Código actual verificado `WEB_ORIGIN` variables de entorno, pero los valores predeterminados pueden causar problemas de seguridad.

**Archivo**: `apps/api/src/server.ts` L23-29
**Sugerencias**: Configuración obligatoria del entorno de producción. `WEB_ORIGIN`, rechazar `*` Combinación con credenciales

**[alto] El token de sesión se almacena en sessionStorage en lugar de secure cookie**

```typescript
sessionStorage.setItem("muse.session", JSON.stringify(value));
```

sessionStorage Legible a través de XSS. Se recomienda utilizar HttpOnly + Secure + SameSite cookie.

**Archivo**: `apps/client/src/main.tsx` L437
**Sugerencias**: Migrar al mecanismo de cookies de sesión del lado del servidor

**[alto] Admin audit log Solo almacenamiento en memoria**

```typescript
const adminAudit: Array<{...}> = [];
```

Una vez reiniciado el servicio, los registros de auditoría se pierden y no se pueden cumplir los requisitos de cumplimiento.

**Archivo**: `apps/api/src/server.ts` L73
**Sugerencias**: Persistencia en la base de datos o servicio de registro externo

**[alto] Falta el límite de tasa de respuesta de transmisión de chat**

```typescript
const REQUEST_LIMIT = 30;
const REQUEST_WINDOW_MS = 60_000;
```

30 veces/El límite de minutos es demasiado flexible para escenarios de chat y puede hacer que los costos se salgan de control.

**Archivo**: `apps/api/src/server.ts` L77-78
**Sugerencias**: Implementar limitación actual jerárquica según el tipo de proveedor y el paquete de cuenta.

---

## 16. @muse/admin(Experiencia en gestión)

### Estado: ⚠️ Parcialmente desaparecido

**[en] Los puntos finales de administración comparten la autenticación con los puntos finales de los usuarios**

Administrador aprobado `/api/v1/admin/*` Verificación de ruta `platform_admin` función, pero la interfaz de inicio de sesión es la misma `/api/v1/auth/login`. No existe un proceso de autenticación de administrador independiente.

**Archivo**: `apps/admin/src/main.tsx`
**Sugerencias**: Considere agregar inicio de sesión específico de administrador o autenticación de dos factores

**[bajo] La edición del precio del paquete no tiene historial de cambios**

Las operaciones de ajuste de precios solo se registran en el registro de auditoría de memoria y las comparaciones históricas de versiones de precios no se conservan.

**Archivo**: `apps/admin/src/main.tsx` L33-36
**Sugerencias**: Mostrar comparación antes y después de los cambios de precios

---

## 17. @muse/worker(Trabajador de fondo)

### Estado: ❌ problema grave

**[serio] Todos los controladores de trabajos son implementaciones vacías.**

```typescript
const worker = new JobWorker(queue, {
  memory_extract: async () => {},
  rag_index: async () => {},
  document_parse: async () => {},
  media_poll: async () => {},
  notification: async () => {},
});
```

Todo el procesamiento de tareas asincrónicas es una función vacía, lo que significa:
- No se realizará la recuperación de la memoria.
- RAG El índice no se activa
- El análisis del documento está vacío.
- Las encuestas de medios no funcionan
- Las notificaciones push no se envían

**Archivo**: `apps/worker/src/worker.ts` L17-22
**riesgo**: La funcionalidad asíncrona principal no está disponible en absoluto
**Sugerencias**: Implementar la lógica de negocios de cada controlador.

**[alto] Worker Sin controles de salud y salida elegante**

No hay sonda de preparación,Kubernetes etc. El sistema de orquestación no puede gestionar el proceso correctamente.

**Archivo**: `apps/worker/src/worker.ts` L30-35
**Sugerencias**: agregar `/health` y `/ready` punto final

**[en] Poll Intervalo codificado**

```typescript
const intervalMs = Math.max(250, Number(process.env.WORKER_POLL_INTERVAL_MS ?? 5000));
```

El sondeo predeterminado de 5 segundos puede ser demasiado frecuente para escenarios de alto rendimiento.

**Archivo**: `apps/worker/src/worker.ts` L3
**Sugerencias**: Ajuste dinámicamente según la carga o implemente un sondeo largo

---

## Resumen de riesgos clave

### Prioridad alta (debe arreglarse)

1. **push.ts SQL error** - La autenticación de inserción se sobrescribe con un valor fijo y la inserción no es completamente válida.
2. **Se filtraron credenciales codificadas** - S3 La clave y la contraseña de administrador están codificadas en el código fuente.
3. **Worker handlers esta vacio** - Falta toda la lógica de procesamiento de trabajos asincrónicos
4. **CORS Riesgo de asignación** - Se debe configurar el entorno de producción. WEB_ORIGIN

### Prioridad media (debe arreglarse)

5. **Provider Tipos inconsistentes** - contracts vs provider darse cuenta
6. **DeepSeek SSE analizar** - Requiere procesador dedicado
7. **Memory decay lógica** - Todavía se devuelven hechos deteriorados
8. **RAG Sólo búsqueda de bolsa de palabras** - No se puede admitir la búsqueda semántica
9. **Session Seguridad de almacenamiento** - Se recomienda migrar a httpOnly cookie

### Prioridad baja (mejoras sugeridas)

10. **Modo oscuro sin cambio manual**
11. **Service Worker Manejo de errores**
12. **Limpieza de carga de archivos huérfanos**
13. **Se filtró el punto final de control de salud requestId**

---

## Corregir línea de tiempo sugerida

| escenario | Tarea | Horas estimadas |
|------|------|----------|
| P0 | Solucionar el error de SQL push.ts (fallo de inserción) | 1h |
| P0 | Eliminar todas las credenciales codificadas | 2h |
| P0 | darse cuenta Worker job handlers | 16h |
| P1 | Arreglar la configuración de CORS | 1h |
| P1 | persistencia admin audit | 4h |
| P1 | Implementación del análisis DeepSeek SSE | 4h |
| P2 | Memory decay arreglo lógico | 2h |
| P2 | RAG Interfaz de búsqueda de vectores reservada | 8h |
| P2 | Session Migrar a cookie | 8h |
| P3 | Cambio de modo oscuro | 2h |
| P3 | SW Manejo de errores | 1h |

---

*Revisión completada*
