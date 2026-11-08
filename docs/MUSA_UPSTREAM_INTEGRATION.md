# Musa: integración completa de upstream

## Base y estrategia

- Upstream incorporado: `Zer0Qing/Muse@408592d28ed42f4938a6fa29adfab4beeb9b5595`.
- Estado Musa anterior preservado: `703ded5a6a64e968d47b7fc905c5992e894558fb`.
- Se parte del árbol upstream completo y se reaplican por funcionalidad identidad,
  español, empaquetado reproducible y controles de publicación. No se restaura el
  árbol Kotlin antiguo ni sus líneas base: eso perdería correcciones nuevas.
- No se fusiona `main`. El PR requiere revisión y pruebas antes de integrarse.

## Versiones y compatibilidad comprobadas

Se descargaron las cuatro APK publicadas del fork y se inspeccionaron con
`aapt dump badging` y `apksigner verify --print-certs`:

| Release | versionCode | Certificado SHA-256 |
| --- | ---: | --- |
| `musa-es-ci-42228191207-1` | 999999 | `7d3984d3fd1511a3c69e4b9241c7b2ae3efbb0d323c08b8e23c738b29517450c` |
| `musa-es-ci-42225632797-1` | 999999 | `e20777a116b972adbb7a0174c0c3af7143556857786818845f74fb93daf5dabe` |
| `muse-arm64-2.6.19-dev-20261106` | 282 | `d31e95985b4ea4d2ede034e23b9d76b50d2eab9784652e4656d9816071e151c8` |
| `muse-lite-es-arm64-8bbb2dc-20261106` | 282 | `d31e95985b4ea4d2ede034e23b9d76b50d2eab9784652e4656d9816071e151c8` |

Todas tienen `CN=Android Debug`. No constituyen una referencia de firma de
producción. Se reserva `1000001` para `2.6.23-musa-es`, superior al máximo publicado
999999 y a la candidata anterior 1000000. No se puede garantizar actualización
sobre esas APK con otro certificado: un número mayor no sustituye la firma.
Debe aportarse una release previa de producción verificable, no su clave privada.
El workflow firmado compara certificado, paquete y versión contra esa release y
rechaza certificados de depuración. No publica automáticamente.

## Diagnóstico de secretos (sin leer valores)

Los workflows upstream y Musa consumen exactamente:

- `KEYSTORE_BASE64`
- `KEYSTORE_PASSWORD`
- `KEY_ALIAS`
- `KEY_PASSWORD`

El workflow manual del fork `42423842590` falló en su runner porque las cuatro
expresiones `secrets.*` resultaron vacías. El 403 de la integración al listar
Secrets **no** demuestra ausencia. Sí demuestra el fallo del runner que esos
secretos no estaban disponibles para ese job en esa ejecución.

Se verificó que Actions está habilitado y permite todas las acciones; la API del
repositorio devuelve cero environments. El job no declara `environment`, no usa
un workflow reutilizable ni depende de `workflow_call`. El evento fue
`workflow_dispatch` dentro del fork, no un PR externo ni Dependabot. Por tanto,
no hay un salto reutilizable sin `secrets: inherit` ni una restricción de secretos
de PR externos que explique ese intento. Los secretos del repositorio padre no
se heredan en un fork. No se puede distinguir desde el job entre un secreto
inexistente, vacío o no autorizado por una política de organización.

Configurar los cuatro nombres en **joselofarias-byte/Muse → Settings → Secrets
and variables → Actions → Repository secrets**. Alternativamente, los secretos
de organización deben autorizar explícitamente este repositorio. Si se elige un
environment, habrá que declarar ese mismo environment en el job y cumplir sus
protecciones; no basta con guardarlos allí. No enviar valores por chat.

Referencia: https://docs.github.com/en/actions/security-for-github-actions/security-guides/using-secrets-in-github-actions

## Verificación y entrega

`musa-integration.yml` ejecuta verificaciones sin claves y compila R8 en modo
`-PmusaUnsignedVerification=true`. Ese modo deja explícitamente sin firma tanto
la aplicación como el proveedor; no usa un keystore alternativo. No se suben APK
sin firma como artefactos de entrega. La instalación de ese resultado no se
considera verificada ni es una release distribuible.

Con los secretos y una release de producción previa disponibles:

```sh
gh workflow run jolufasoft-musa-apk.yml \
  --repo joselofarias-byte/Muse \
  --ref hoplite/kos-04cbd875--upstream-full \
  -f previous_release_tag=ETIQUETA_DE_PRODUCCION_PREVIA
```

Antes de publicar: comprobar firma de app y proveedor, continuidad con la release
previa, versión, SHA-256, integridad ZIP y alineación; instalar en dispositivo,
probar inicio y funciones, y probar actualización conservando datos. El workflow
solo prepara un borrador y vuelve a descargarlo para comprobar sus bytes.

## Seguridad e idioma

- El primer inicio usa español; una selección explícita de Sistema se conserva.
  La migración de caché y el guardado de idioma se serializan entre instancias.
- El QR excluye por defecto claves API, secretos OAuth, claves privadas,
  encabezados, cuerpos y plantillas personalizados. Compartirlos requiere
  consentimiento explícito que se reinicia al reabrir el diálogo. Las URL y el
  resto de la configuración siguen incluidos; no es una anonimización completa.
- Los campos sensibles del editor no se guardan en `rememberSaveable`.
- El panel de herramientas usa identificadores de recursos, no títulos chinos,
  para agrupar búsqueda, razonamiento, adjuntos y conocimiento.
- Se corrige el cierre del flujo Anthropic y se cancelan sus tareas hijas al
  abortar durante un reintento. Se prueban respuestas 503 y 429 con
  `Retry-After: 60`, exigiendo finalizar en menos de dos segundos tras abortar.
- La web conserva contratos y migraciones históricas; una nueva migración cambia
  los textos predeterminados sin renombrar planes personalizados ni conversaciones.

## Estado de aceptación

Los controles de recursos completos no prueban por sí solos que toda la interfaz
esté traducida: hay literales Kotlin y errores externos que requieren revisión.
La comprobación global `:app:ktlintKotlinSourceCheck` detectó deuda de formato en
upstream; no se eleva ninguna línea base ni se elimina la puerta. El workflow usa
`--continue` para obtener también resultados de compilación y pruebas sin ocultar
el fallo de los controles estáticos.

El PR permanece en borrador hasta cerrar las verificaciones pendientes. No hay
un dispositivo Android conectado para acreditar instalación o actualización con
datos. No se entrega la APK sin firma ni se sustituye el certificado permanente.

La compilación local inicial produjo una APK Release ARM64 sin firma con paquete
`io.zer0.muse`, nombre `Musa`, versión `2.6.23-musa-es` y código `1000001`;
la integridad ZIP y la ABI exclusiva `arm64-v8a` se comprobaron. No se distribuye.
Las pruebas dirigidas finales aprobaron seis casos de idioma, seis de QR y tres
de cancelación. Los auxiliares de firma aprobaron 13 casos y pytest aprobó 41
casos de continuidad, localización y residuos. La batería de IA inicial ejecutó
496 pruebas con un timeout Anthropic; las tres pruebas dirigidas no sustituyen
la repetición completa. Lint completo sigue pendiente: el primer intento se
interrumpió tras más de 40 minutos; un intento posterior de compilación encontró
una excepción interna FIR al analizar `AnthropicProvider.kt`. La compilación final
se repite sin modificar las fuentes durante el análisis; no se acredita aprobación
de Lint ni una APK final mientras no termine.

La verificación web completa (build/typecheck, pruebas de paquetes y cliente)
terminó con código cero. La CI construye el workspace antes de ejecutar pruebas
que importan `dist`; el orden anterior fallaba en un checkout limpio. No se
eliminan ni se relajan pruebas.

En la web se comprobó el paso de login a registro y se inspeccionó una captura
actual: el placeholder de contraseña se muestra completo. No acredita el resto
de flujos autenticados ni una migración real contra PostgreSQL.
