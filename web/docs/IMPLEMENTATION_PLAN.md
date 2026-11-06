# Muse Web / PWA SaaS Plan de implementación

## Decisión confirmada

- Ubicación del proyecto: almacén actual `web/`
- Tecnología:TypeScript + React + Node.js
- Implementación: servidor en la nube; aún no hay ningún nombre de dominio de producción vinculado
- Moneda: RMB
- Pago: Alipay; primero realice un ajuste de precios en segundo plano, limite y pago simulado, luego reciba credenciales comerciales reales
- Modelo:BYOK Modelo de alojamiento de plataforma opcional y preferido
- BYOK: Priorizar la conexión directa del navegador; permitir el proxy del lado del servidor cuando se requieren memoria, herramientas y políticas de seguridad
- Equipo: El primer lanzamiento no será un espacio de trabajo en equipo
- Provider: El primer lote que cubre todos los tipos de proveedores que se pueden migrar en el lado de Android existente
- Android Habilidades exclusivas: sin barreras,Shizuku,Root, control entre aplicaciones, notificaciones del sistema, ventanas flotantes, etc. no entran Web

## Secuencia de etapas

1. esqueleto de ingeniería,PWA, contrato, infraestructura de desarrollo
2. Autenticación, inquilinos individuales, permisos y auditoría
3. Provider Gateway,SSE,BYOK, modelo de alojamiento
4. conversación, mensaje,Assistant y chat responsivo UI
5. Cuatro capas de memoria,RAG, importar y exportar
6. ToolRegistry,Skill,MCP y disposiciones de aprobación
7. Agent, chat grupal y tareas asincrónicas
8. archivos, documentos,OCR, fotos/vídeo,TTS/ASR
9. Libro mayor de cuotas, paquete RMB, backend de ajuste de precios, pago simulado, capa de adaptación de Alipay
10. Seguridad, cumplimiento, compatibilidad del navegador,PWA empujar
11. CI, integración/E2E,Docker,staging y revertir

Cada etapa debe tener: código, pruebas, migración, documentación, registros de aceptación; solo las plantillas de implementación y la verificación de la capacidad de ejecución se completan sin credenciales reales en la nube.
