# Muse Web / PWA SaaS

Web versión en `web/`, aislado de los módulos de Android existentes. El objetivo es proporcionar una PWA instalable,BYOK SaaS público preferido, con créditos del modelo de alojamiento de plataforma opcional.

## Estado actual

Se ha completado el primer corte vertical construible:

- npm workspaces:`apps/client`,`apps/api`,`apps/admin`,`apps/worker`
- contrato compartido, núcleo,Provider, base de datos, memoria,RAG, archivos, herramientas,MCP,Agent, paquetes de almacenamiento de tareas y objetos
- Client Almacén de claves de proveedor operable,Assistant editar,Memory Space/hechos,RAG índice de texto,S3 Portal de transferencia directa y registro Push
- API Admite la persistencia de PostgreSQL,Redis/Valkey o cola de tareas de PostgreSQL,S3-compatible presign Registro completo con documentos.
- Provider Compatible con OpenAI,Anthropic,Gemini,Responses Transmisión de texto, delta de llamadas de herramientas y normalización de uso para
- Docker Compose desarrollar infraestructura,staging Plantillas y manifiesto de PWA,Service Worker
- Android Límites de capacidad exclusivos y controles de seguridad.

Botón de función empresarial `docs/IMPLEMENTATION_PLAN.md` Las etapas se van realizando gradualmente.Android Accesibilidad exclusiva,Shizuku,Root, el control entre aplicaciones y las capacidades de notificación del sistema no vienen en la versión web.

## Host Mode(Computación de host celular)

Host Mode Dejemos que Android Muse sea el único runtime: el teléfono sigue siendo responsable de los proveedores, las sesiones, la memoria,RAG, herramientas, aprobación y persistencia de datos, el navegador solo es responsable de representar y enviar comandos interactivos.Android WebServer Proporcionar PIN/JWT autenticado `/ws` WebSocket,React El cliente puede pasar `?mode=host` Ingrese a la interfaz del Anfitrión; establecido durante el desarrollo entre dispositivos `VITE_HOST_BASE=http://LAN celularIP:puerto`.

Host Mode El primer soporte de bucle cerrado actual: instantánea de estado, selección de sesión, nueva sesión, envío de mensaje, visualización del estado de transmisión de mensajes y parada de generación. Todavía se encuentra en la etapa experimental y requiere cookies de PIN de verificación de máquina real, conexión LAN, desconexión y reconexión y un ciclo de vida en segundo plano antes de reemplazar oficialmente las páginas integradas de Android.

## desarrollo local

Requiere Node.js 22+.

```bash
npm install
npm run typecheck
npm run test
```

Cuando se necesitan base de datos y caché:

```bash
docker compose -f infra/docker-compose.dev.yml up -d
```

## modelo de tarifa

- BYOK: Se da prioridad a la compatibilidad con la conexión directa del navegador al Proveedor; cuando se requiere memoria del servidor, herramientas o agentes de seguridad, se puede seleccionar la ruta de solicitud del servidor.
- Hosted: La plataforma unifica la clave del proveedor y utiliza el libro de cuotas del lado del servidor; el backend puede gestionar paquetes, precios, funciones y límites.
- Actualmente, sólo se implementan paquetes de RMB, modelos de límite e interfaces de pago simuladas; la producción de credenciales de Alipay y la implementación del servidor en la nube no se ejecutan automáticamente en esta etapa.
- Uso de almacenamiento de archivos `S3_ENDPOINT`,`S3_BUCKET`,`S3_ACCESS_KEY`,`S3_SECRET_KEY`; Cuando las credenciales son valores de marcador de posición, la URL de desarrollo local se devuelve explícitamente, lo que no significa que se haya conectado el almacenamiento de objetos real.
- `REDIS_URL` Después de la configuración, la API y el trabajador utilizan la misma cola de tareas de Redis/Valkey; cuando no está configurado, se utiliza PostgreSQL o cola de memoria según el entorno.
