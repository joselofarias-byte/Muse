# Web Instrucciones de implementación

Actualmente, solo se proporcionan plantillas de desarrollo y preparación, y no se realiza una implementación real del servidor en la nube.

## locales

```bash
npm install
npm run typecheck
npm run test
npm run build
# Servicios dependientes
docker compose -f infra/docker-compose.dev.yml up -d
```

## Destino provisional del servidor en la nube

- Linux + Docker Compose
- HTTPS Terminado por Caddy/Nginx
- client,api,admin,worker Contenedor de separación
- PostgreSQL y acceso a la red privada Valkey
- S3 Compatible con almacenamiento de objetos,API Solo se emiten URL PUT/GET de corta duración y se escriben metadatos de archivos PostgreSQL
- `REDIS_URL` Se debe proporcionar tanto a la API como al trabajador para garantizar que compartan la misma cola de tareas.
- Variables de entorno de puesta en escena independientes,Provider Key,`SECRETS_MASTER_KEY` y VAPID Key
- La migración de la base de datos primero se realiza en seco y luego se ejecuta;API/Worker La migración también se parcheará de forma segura con un bloqueo de aviso al inicio.
- Tutorial diario de copia de seguridad y recuperación
- No vincule el nombre de dominio de producción y no utilice credenciales de pago de producción.

## Asuntos prohibidos antes de la producción.

Nombre de dominio oficial, correo electrónico,VAPID,KMS/Custodia de clave equivalente,S3 Antes del ciclo de vida, la clave comercial de Alipay, la recuperación y el monitoreo de la copia de seguridad, no se permite afirmar que la producción está disponible y no se permite abrir el pago real.

## Notas sobre la construcción de contenedores

API La imagen necesita ser construida. `@muse/storage` con `@muse/jobs`;Worker Es necesario traer el espejo. `@muse/database`,`@muse/jobs` y sus artefactos del espacio de trabajo. Si no hay un comando Docker en el entorno actual, solo se pueden completar verificaciones de compilación estática y no se puede decir que la imagen o el inicio provisional estén verificados.
