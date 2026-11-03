# Web límite de seguridad

- Provider API Key Sin acceso a registros, seguimiento de errores o artefactos de compilación del cliente.
- BYOK La prioridad predeterminada es la conexión directa al navegador; El cifrado KMS/sobre se utiliza cuando se requiere el proxy del servidor y el descifrado solo se realiza en la memoria de solicitud.
- Los mensajes de usuario, los archivos adjuntos, los parámetros de herramientas y las respuestas de MCP se consideran entradas no confiables.
- MCP/HTTP/El rastreo del navegador debe realizar SSRF,DNS Rebinding, Dirección de intranet y verificación de redirecciones.
- La herramienta utiliza niveles de riesgo, aprobaciones y cuotas explícitos; el servidor no ejecuta código de host arbitrario.
- El backend de gestión requiere MFA,RBAC, auditoría y confirmación secundaria obligatoria.
- Tamaño de ejecución de carga de archivos,MIME, comprobaciones de aislamiento de almacenamiento de objetos, extensiones y contenidos.
- Operaciones como eliminación, reembolso, prohibición y liberación de precios deben ser idempotentes y dejar registros de auditoría.
