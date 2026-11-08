# Musa: integración completa de upstream

## Base y estrategia

- Upstream incorporado: `Zer0Qing/Muse@408592d28ed42f4938a6fa29adfab4beeb9b5595`.
- Estado Musa anterior preservado: `703ded5a6a64e968d47b7fc905c5992e894558fb`.
- Se parte del árbol upstream completo y se reaplican por funcionalidad identidad,
  español, empaquetado reproducible y controles de publicación. No se restaura el
  árbol Kotlin antiguo ni sus líneas base: eso perdería correcciones nuevas.
- No se fusiona el PR en `main`. La base se incorpora únicamente al historial
  de la rama de integración, preservando el árbol upstream reconstruido. El PR
  requiere revisión y pruebas antes de integrarse.

## Conciliación del historial con la base del PR

La base `main@8a65ba669f7cacdf31d9d6a9298ce5963fb894b6` ya es antecesora del
estado Musa preservado `703ded5a`. Esta integración reemplaza deliberadamente
ese árbol antiguo por upstream completo más las funcionalidades Musa reaplicadas.
Una combinación textual de ambas arquitecturas produce 412 mensajes de conflicto
y puede reintroducir implementaciones, recursos y workflows obsoletos.

Se concilian ambos historiales mediante un merge de estrategia `ours`: se conserva
íntegro el árbol de integración `aea8d151` y se registra la base como segundo padre.
La única modificación adicional es esta documentación. No se recuperan el workflow
de APK de prueba, las firmas debug ni las versiones antiguas de descargas o del
estado de credenciales. Esta decisión no acredita Lint, firma o instalación: los
bloqueos de aceptación documentados más abajo siguen vigentes. `main` no se modifica.

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
Para futuras actualizaciones debe aportarse una release previa de producción verificable, no su clave privada.
El propietario confirmó el 2026-11-08 que no tiene Muse instalada y autorizó
una firma permanente nueva; esta primera instalación no requiere APK anterior
ni prueba de conservación de datos.
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

Para esta primera instalación, con los secretos disponibles, usar
`-f first_release=true` sin `previous_release_tag`. Para futuras actualizaciones:

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

La compilación local previa a las correcciones de CI produjo una APK Release ARM64 sin firma con paquete
`io.zer0.muse`, nombre `Musa`, versión `2.6.23-musa-es` y código `1000001`;
la integridad ZIP y la ABI exclusiva `arm64-v8a` se comprobaron. SHA-256:
`3f355612714787f247db916da91515cccf6fcc6c3915d850efd17c89981e4ade`.
La tarea `assembleRelease` terminó correctamente en la ejecución conjunta con
`lintRelease --continue`; el comando global falló por Lint, no por el empaquetado.
Una compilación anterior aprobó también `lintVitalRelease`; en la ejecución
conjunta el Lint vital se omite en favor del completo. No hay aprobación global
de Release ni prueba de firma o actualización. No se distribuye esta APK.
Las pruebas dirigidas finales aprobaron seis casos de idioma, seis de QR y tres
de cancelación. Los auxiliares de firma aprobaron 13 casos y pytest aprobó 41
casos de continuidad, localización y residuos (43 tras añadir las dos pruebas de
plurales). La batería completa de IA de esa compilación aprobó 497 pruebas, sin fallos, errores
ni omisiones.

En esa compilación, Lint completo terminó con 141 errores, 860 advertencias y 12 avisos informativos:
98 errores `LocalContextGetResourceValueCall`, seis `MissingQuantity` en ruso,
35 `MissingTranslation` de otros idiomas y dos `JavascriptInterface`. No está
aprobado; no se relajan checks ni baselines. El análisis detectó cinco plurales
malformados en español y en los recursos predeterminados: se restauraron sus
elementos `item` y formas españolas. El verificador ahora exige las claves de
plurales y rechaza definiciones sin `other` o con texto directo. La repetición
eliminó los diez errores correspondientes y los avisos de formas españolas.

La verificación web completa (build/typecheck, pruebas de paquetes y cliente)
terminó con código cero. La CI construye el workspace antes de ejecutar pruebas
que importan `dist`; el orden anterior fallaba en un checkout limpio. No se
eliminan ni se relajan pruebas.

En la web se comprobó el paso de login a registro y se inspeccionó una captura
actual: el placeholder de contraseña se muestra completo. No acredita el resto
de flujos autenticados ni una migración real contra PostgreSQL.

## Correcciones de los checks del PR

Los fallos de CI del head `f462b56b` se diagnosticaron a partir de sus informes,
sin usar el contenido de los logs como instrucciones. Las correcciones mantienen
las reglas y baselines existentes:

- Gemini y ambas rutas de OpenAI cierran el productor al abortar durante backoff
  y cancelan las tareas hijas pendientes; tres regresiones cubren esperas largas.
- Miniphone observa cambios del contador de no leídos mediante el flujo de
  conversaciones existente, no solo inserciones de mensajes. La regresión separa
  deliberadamente la inserción y el incremento posterior del contador.
- Compose lee recursos con `LocalResources`; los sinks JavaScript mantienen su
  tipo explícito y sus métodos anotados, sin ampliar el puente.
- Se completan traducciones y plurales de otros locales y se restauran los
  contratos españoles de títulos, temas y estados wake. Las pruebas de DNS
  controlan el prewarm para distinguir trabajo síncrono de trabajo en IO.
- Se corrige el formato detectado por ktlint, sin generar nuevas líneas base,
  añadir supresiones ni desactivar puertas. Los contratos JSON se conservan.

La compilación diagnóstica de app/miniphone y las 500 pruebas de IA pasaron;
también pasaron 390 pruebas de miniphone antes de reutilizar el flujo existente,
localización estricta y residuos. Estos resultados no sustituyen la verificación
global del árbol final: Lint, pruebas completas y CI deben concluir en el nuevo
head. No se acredita otra APK, firma, instalación ni prueba de actualización.

## Firma permanente Musa (primera instalación)

La identidad pública está fijada en `docs/MUSA_RELEASE_CERT.sha256`.
El workflow rechaza otra clave incluso si los secretos se reemplazan por error.
No regenerar la firma en versiones siguientes ni usar claves debug o de otras apps.

El keystore JKS RSA-4096 y sus dos contraseñas aleatorias se mantienen fuera
del repositorio, con permisos privados. No guardar material privado en commits,
logs, descripciones de PR ni releases. La copia del workspace no sustituye un
respaldo externo verificado; guardar un respaldo cifrado en custodia del propietario.

Recuperación desde ese respaldo: restaurar el JKS y las credenciales en un
directorio privado; cargar en GitHub Actions `KEYSTORE_BASE64` (base64 del JKS),
`KEYSTORE_PASSWORD`, `KEY_ALIAS` y `KEY_PASSWORD`. Usar el helper existente
`ci/script/validate_release_signing.py` para crear `keystore.properties` fuera de Git,
y comprobar que el certificado coincide con la huella pública fijada.
La app y el proveedor de accesibilidad comparten esa configuración de firma.

Build local: `./gradlew :app:assembleRelease --no-daemon -PnoUniversal
-PversionCode=1000001 -PversionName=2.6.23-musa-es` (en una sola línea).
No usar `musaUnsignedVerification` ni saltarse las comprobaciones de firma.
Para nuevas versiones incrementar versionCode, reutilizar la clave y comparar
con esta primera release; no volver a seleccionar `first_release=true`.
