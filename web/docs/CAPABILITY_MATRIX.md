# Web Límite de capacidad

## Web Cobertura completa

chat, transmisión de respuestas,Provider,BYOK,modelo alojado,sesión,Assistant,Persona,Mood, memoria,RAG, archivo, análisis de documentos,OCR, fotos/tareas de vídeo,TTS/ASR, búsqueda, herramientas,Skill,MCP,Agent, chat grupal, importación y exportación, temas, multilenguaje,Web Push, cuotas, paquetes y operaciones backend.

El cliente actual ha formado el primer circuito cerrado de chat disponible: creación automática de sesión, persistencia de mensajes,BYOK conexión directa/Proxy del lado del servidor, salida de streaming, generación de paradas, bloques de código Markdown básicos e inyección de avisos del Asistente. Las partes que aún tengan lagunas en la experiencia de Android se completarán gradualmente según las siguientes etapas.

## Web sustituto

Room → PostgreSQL;DataStore → PostgreSQL + IndexedDB;WorkManager → Worker/Cola;Keystore → KMS/Cifrado de sobres; archivo local → S3;Android TTS/ASR → Web API + Proveedor de nube; servicio de front-end → Tareas asincrónicas del lado del servidor + SSE/Web Push.

## Explícitamente no soportado

Android sin barreras,Shizuku,Root, clics entre aplicaciones/Deslizar/Entrada, monitoreo de notificaciones del sistema, mensajes de texto, contactos, lectura y escritura directa del calendario del sistema, alimentación del dispositivo/bluetooth/Wi-Fi/brillo/control de volumen,Android Ventanas flotantes, widgets, instalaciones silenciosas y otros datos privados de aplicaciones.
